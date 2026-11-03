#!/usr/bin/env python3
"""Small two-layer grid router for the USB stick board (A* on a 0.1 mm grid, with vias).

Reads usb-stick.kicad_pcb as written by gen_board.py, routes every net, adds GND pours on
both layers and saves the board in place. The result is checked with KiCad's DRC (drc.py).
"""
import heapq
import math
import os
import sys

import numpy as np
import pcbnew
from pcbnew import FromMM as mm, ToMM, VECTOR2I
from scipy.ndimage import distance_transform_edt

HERE = os.path.dirname(os.path.abspath(__file__))
PCB = os.path.join(HERE, "usb-stick.kicad_pcb")
OX, OY = 100.0, 100.0
G = 0.05                     # grid step, mm
TRACK = 0.2                  # track width
VIA_D, VIA_DRILL = 0.6, 0.3     # JLCPCB standard (no extra cost)
CLR = 0.15                   # target clearance
EDGE = 0.3                   # copper to board edge
SLACK = 0.03                 # raster error margin
VIA_COST, BOTTOM_COST = 15.0, 1.25

F, B = 0, 1

board = pcbnew.LoadBoard(PCB)
_bb = board.GetBoardEdgesBoundingBox()
W, H = round(ToMM(_bb.GetWidth()), 2), round(ToMM(_bb.GetHeight()), 2)
NX, NY = int(round(W / G)) + 1, int(round(H / G)) + 1
netcodes = {n.GetNetname(): n.GetNetCode() for n in board.GetNetInfo().NetsByName().values() if n.GetNetname()}

copper = [np.zeros((NX, NY), dtype=np.int32) for _ in range(2)]
pads_by_net = {}   # net -> list of (layers, cells, centre)


def cell_of(x, y):
    return int(round(x / G)), int(round(y / G))


def raster_rect(x0, y0, x1, y1):
    i0, i1 = int(math.ceil(x0 / G - 1e-6)), int(math.floor(x1 / G + 1e-6))
    j0, j1 = int(math.ceil(y0 / G - 1e-6)), int(math.floor(y1 / G + 1e-6))
    return [(i, j) for i in range(max(i0, 0), min(i1, NX - 1) + 1) for j in range(max(j0, 0), min(j1, NY - 1) + 1)]


for fp in board.GetFootprints():
    for p in fp.Pads():
        bb = p.GetBoundingBox()
        x0, y0 = ToMM(bb.GetX()) - OX, ToMM(bb.GetY()) - OY
        x1, y1 = x0 + ToMM(bb.GetWidth()), y0 + ToMM(bb.GetHeight())
        if p.GetAttribute() == pcbnew.PAD_ATTRIB_NPTH:
            x0, y0, x1, y1 = x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12   # hole clearance is 0.25
        cells = raster_rect(x0, y0, x1, y1)
        if not cells:
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            cells = [cell_of(cx, cy)]
        layers = [l for l, lid in ((F, pcbnew.F_Cu), (B, pcbnew.B_Cu)) if p.IsOnLayer(lid)]
        code = p.GetNetCode()
        if p.GetAttribute() == pcbnew.PAD_ATTRIB_NPTH:
            code, layers = -1, [F, B]   # holes: keep everything away
        elif code == 0:
            code = -1                   # unused pins: copper of no net, still an obstacle
        for l in layers:
            for c in cells:
                if copper[l][c] == 0:
                    copper[l][c] = code
        if code > 0:
            centre = (ToMM(p.GetPosition().x) - OX, ToMM(p.GetPosition().y) - OY)
            # route ends stay inside the pad by half a track width
            core = raster_rect(x0 + TRACK / 2, y0 + TRACK / 2, x1 - TRACK / 2, y1 - TRACK / 2)
            if not core:
                core = [cell_of(*centre)]
            pads_by_net.setdefault(code, []).append((layers, core, centre))

# board edge band, as foreign copper for every net
edge_band = np.zeros((NX, NY), dtype=bool)
k = int(math.ceil((EDGE - TRACK / 2) / G))
edge_band[:k, :] = edge_band[-k:, :] = True
edge_band[:, :k] = edge_band[:, -k:] = True

tracks, vias = [], []


def free_maps(code):
    out = []
    for l in (F, B):
        foreign = ((copper[l] != 0) & (copper[l] != code))
        d = distance_transform_edt(~foreign) * G
        track_ok = (d >= TRACK / 2 + CLR + SLACK) & ~edge_band
        via_ok = (d >= VIA_D / 2 + CLR + SLACK)
        out.append((track_ok, via_ok))
    vok = out[F][1] & out[B][1]
    vband = np.zeros_like(edge_band)
    kv = int(math.ceil((EDGE + VIA_D / 2) / G))
    vband[:kv, :] = vband[-kv:, :] = vband[:, :kv] = vband[:, -kv:] = True
    return [out[F][0], out[B][0]], vok & ~vband


MOVES = [(1, 0, 1.0), (-1, 0, 1.0), (0, 1, 1.0), (0, -1, 1.0),
         (1, 1, 1.414), (1, -1, 1.414), (-1, 1, 1.414), (-1, -1, 1.414)]


def astar(sources, targets, ok, via_ok, code, soft=None, soft_via=None):
    """sources/targets: sets of (layer, i, j). Returns list of (layer, i, j)."""
    tgt = np.zeros((2, NX, NY), dtype=bool)
    for l, i, j in targets:
        tgt[l, i, j] = True
    hmap = distance_transform_edt(~(tgt[0] | tgt[1]))
    term = np.zeros((2, NX, NY), dtype=bool)
    for l, i, j in list(sources) + list(targets):
        term[l, i, j] = True
    passable = [ok[l] | term[l] for l in (F, B)]
    best = {}
    heap = []
    for s in sources:
        best[s] = 0.0
        heapq.heappush(heap, (hmap[s[1], s[2]], 0.0, s, None))
    parent = {}
    while heap:
        f, g, s, par = heapq.heappop(heap)
        if g > best.get(s, 1e18) + 1e-9:
            continue
        if s in parent or par is not None:
            parent.setdefault(s, par)
        l, i, j = s
        if tgt[l, i, j]:
            path = [s]
            while parent.get(path[-1]) is not None:
                path.append(parent[path[-1]])
            return path[::-1]
        mult = BOTTOM_COST if l == B else 1.0
        for di, dj, c in MOVES:
            ni, nj = i + di, j + dj
            if not (0 <= ni < NX and 0 <= nj < NY) or not passable[l][ni, nj]:
                continue
            if di and dj and not (passable[l][i + di, j] and passable[l][i, j + dj]):
                continue
            ng = g + c * mult + (soft[l][ni, nj] if soft is not None else 0.0)
            ns = (l, ni, nj)
            if ng < best.get(ns, 1e18) - 1e-9:
                best[ns] = ng
                parent[ns] = s
                heapq.heappush(heap, (ng + hmap[ni, nj], ng, ns, s))
        if via_ok[i, j] or (term[F, i, j] and term[B, i, j]):
            ns = (1 - l, i, j)
            ng = g + VIA_COST + (soft_via[i, j] if soft_via is not None else 0.0)
            if passable[1 - l][i, j] and ng < best.get(ns, 1e18) - 1e-9:
                best[ns] = ng
                parent[ns] = s
                heapq.heappush(heap, (ng + hmap[i, j], ng, ns, s))
    return None


def mark_disk(l, i, j, r, code):
    ri = int(math.ceil(r / G))
    for a in range(-ri, ri + 1):
        for b in range(-ri, ri + 1):
            if (a * a + b * b) * G * G <= r * r + 1e-9:
                x, y = i + a, j + b
                if 0 <= x < NX and 0 <= y < NY and copper[l][x, y] == 0:
                    copper[l][x, y] = code


def commit(path, code):
    """Turns a cell path into track segments and vias, and rasterises them."""
    segs = []
    start = path[0]
    prev = path[0]
    direction = None
    for cur in path[1:]:
        if cur[0] != prev[0]:          # layer change: via
            if prev != start:
                segs.append((start, prev))
            vias.append((prev[1], prev[2], code))
            for l in (F, B):
                mark_disk(l, prev[1], prev[2], VIA_D / 2, code)
            start, prev, direction = cur, cur, None
            continue
        d = (cur[1] - prev[1], cur[2] - prev[2])
        if direction is not None and d != direction:
            segs.append((start, prev))
            start = prev
        direction = d
        prev = cur
    if prev != start:
        segs.append((start, prev))
    for a, b in segs:
        tracks.append((a[0], a[1] * G, a[2] * G, b[1] * G, b[2] * G, code))
    for l, i, j in path:
        mark_disk(l, i, j, TRACK / 2, code)


def pad_stub(layer_cells_centre, cell, code):
    """Short track from the real pad centre to the grid cell the route starts from."""
    layers, cells, (cx, cy) = layer_cells_centre
    l, i, j = cell
    if abs(cx - i * G) > 1e-3 or abs(cy - j * G) > 1e-3:
        tracks.append((l, cx, cy, i * G, j * G, code))


ORDER = ["LORA_BUSY", "LORA_RESET", "LORA_MISO", "LORA_MOSI", "LORA_SCK", "LORA_NSS", "USB_DP", "USB_DN",
         "CC1", "CC2", "LORA_DIO1", "LORA_DIO2", "LORA_RXEN", "ACT_N", "LED_A",
         "VBUS", "+3V3", "GND"]
BASE = [c.copy() for c in copper]
PLANE_NET = "GND"   # poured on both layers; each pad only needs a via to the planes


def fanout(code, pads):
    """Connects every top-side pad of the plane net to a via (the pours join the vias)."""
    for pad in pads:
        layers, cells, centre = pad
        if B in layers:
            continue   # bottom pads touch the bottom pour directly
        ok, via_ok = free_maps(code)
        sources = set((F, i, j) for (i, j) in cells)
        own_vias = [(vi, vj) for vi, vj, vc in vias if vc == code]
        far = np.ones_like(via_ok)
        r = int(math.ceil(0.9 / G))
        for vi, vj in own_vias:   # no new via right next to another one of the plane
            far[max(vi - r, 0):vi + r + 1, max(vj - r, 0):vj + r + 1] = False
        targets = set((F, int(i), int(j)) for i, j in zip(*np.nonzero(via_ok & far)))
        targets |= set((F, vi, vj) for vi, vj in own_vias)   # or join a via already there
        targets -= sources
        if not targets:
            return False
        path = astar(sources, targets, ok, via_ok, code)
        if path is None:
            return False
        pad_stub(pad, path[0], code)
        commit(path, code)
        end = path[-1]
        if (end[1], end[2]) not in own_vias:
            vias.append((end[1], end[2], code))
            for l in (F, B):
                mark_disk(l, end[1], end[2], VIA_D / 2, code)
    return True



def disk_offsets(r):
    ri = int(math.ceil(r / G))
    return [(a, b) for a in range(-ri, ri + 1) for b in range(-ri, ri + 1) if (a * a + b * b) * G * G <= r * r + 1e-9]


TRACK_DISK, VIA_DISK = disk_offsets(TRACK / 2), disk_offsets(VIA_D / 2)


def stamp(arr, i, j, offs):
    for a, b in offs:
        x, y = i + a, j + b
        if 0 <= x < NX and 0 <= y < NY:
            arr[x, y] = True


def route_net(code, pads, ok, via_ok, soft, soft_via):
    """Routes one net as a tree; returns the list of paths or None."""
    tree = set((l, i, j) for l in pads[0][0] for (i, j) in pads[0][1])
    remaining = list(range(1, len(pads)))
    paths = []
    while remaining:
        targets = {}
        for idx in remaining:
            for l in pads[idx][0]:
                for (i, j) in pads[idx][1]:
                    targets[(l, i, j)] = idx
        path = astar(tree, set(targets), ok, via_ok, code, soft, soft_via)
        if path is None:
            return None
        paths.append(path)
        reached = targets[path[-1]]
        tree |= set(path)
        tree |= set((l, i, j) for l in pads[reached][0] for (i, j) in pads[reached][1])
        remaining.remove(reached)
    return paths


def path_rasters(paths):
    copper_r = [np.zeros((NX, NY), dtype=bool) for _ in (F, B)]
    centres = [np.zeros((NX, NY), dtype=bool) for _ in (F, B)]
    via_c = np.zeros((NX, NY), dtype=bool)
    for path in paths:
        for k, (l, i, j) in enumerate(path):
            centres[l][i, j] = True
            stamp(copper_r[l], i, j, TRACK_DISK)
            if k + 1 < len(path) and path[k + 1][0] != l:
                via_c[i, j] = True
                for ll in (F, B):
                    stamp(copper_r[ll], i, j, VIA_DISK)
    return copper_r, centres, via_c


# Negotiated routing (PathFinder style): nets may overlap at a price that rises every round
# on the cells that stayed congested, until no two nets are too close.
signals = [n for n in ORDER if n != PLANE_NET and len(pads_by_net.get(netcodes.get(n), [])) >= 2]
hard = {}
for name in signals:
    code = netcodes[name]
    hard[code] = free_maps(code)   # pads only at this point
hist = [np.zeros((NX, NY)) for _ in (F, B)]
routes = {}      # code -> paths
rasters = {}     # code -> (copper, centres, vias)
pfac = 2.0
TT = TRACK / 2 + CLR + SLACK
TV = VIA_D / 2 + CLR + SLACK
converged = False
import random
rng = random.Random(1)
last_over, stall = None, 0
order = list(signals)
for it in range(int(os.environ.get("ROUNDS", "60"))):
    if it > 0:
        rng.shuffle(order)   # a different order each round breaks ties between nets
    for name in order:
        code = netcodes[name]
        rasters.pop(code, None)
        others = [np.zeros((NX, NY), dtype=bool) for _ in (F, B)]
        for c2, (cr, _, _) in rasters.items():
            for l in (F, B):
                others[l] |= cr[l]
        soft = []
        dist = []
        for l in (F, B):
            d = distance_transform_edt(~others[l]) * G
            dist.append(d)
            soft.append(0.4 * hist[l] + pfac * (1.0 + hist[l]) * (d < TT))
        soft_via = 3.0 * pfac * (1.0 + hist[F] + hist[B]) * ((dist[F] < TV) | (dist[B] < TV))
        ok, via_ok = hard[code]
        paths = route_net(code, pads_by_net[code], ok, via_ok, soft, soft_via)
        if paths is None:
            print("  no path at all for", name, flush=True)
            paths = []
        routes[code] = paths
        rasters[code] = path_rasters(paths)
    # find the cells where nets are still too close
    over = 0
    for code, (cr, centres, via_c) in rasters.items():
        others = [np.zeros((NX, NY), dtype=bool) for _ in (F, B)]
        for c2, (cr2, _, _) in rasters.items():
            if c2 != code:
                for l in (F, B):
                    others[l] |= cr2[l]
        for l in (F, B):
            d = distance_transform_edt(~others[l]) * G
            bad = centres[l] & (d < TT)
            badv = via_c & (d < TV)
            hist[l][bad | badv] += 1 + 3 * stall
            over += int(bad.sum() + badv.sum())
    print(f"round {it + 1}: congested cells {over}", flush=True)
    if over == 0:
        converged = True
        break
    stall = stall + 1 if over == last_over else 0
    last_over = over
    pfac *= 1.3

# Final geometry: tracks, vias and pad stubs from the converged paths.
for name in signals:
    code = netcodes[name]
    pads = pads_by_net[code]
    for path in routes.get(code, []):
        for idx in range(len(pads)):
            for end_cell in (path[0], path[-1]):
                if (end_cell[1], end_cell[2]) in pads[idx][1] and end_cell[0] in pads[idx][0]:
                    pad_stub(pads[idx], end_cell, code)
        commit(path, code)
failed = [] if converged else ["congestion"]
if not fanout(netcodes[PLANE_NET], pads_by_net[netcodes[PLANE_NET]]):
    failed.append(PLANE_NET)

# ---------------------------------------------------------------- write tracks, vias, zones
layer_id = {F: pcbnew.F_Cu, B: pcbnew.B_Cu}
nets_by_code = {n.GetNetCode(): n for n in board.GetNetInfo().NetsByName().values()}
for l, x0, y0, x1, y1, code in tracks:
    if abs(x0 - x1) < 1e-6 and abs(y0 - y1) < 1e-6:
        continue
    t = pcbnew.PCB_TRACK(board)
    t.SetStart(VECTOR2I(mm(OX + x0), mm(OY + y0)))
    t.SetEnd(VECTOR2I(mm(OX + x1), mm(OY + y1)))
    t.SetWidth(mm(TRACK))
    t.SetLayer(layer_id[l])
    t.SetNet(nets_by_code[code])
    board.Add(t)
for i, j, code in vias:
    v = pcbnew.PCB_VIA(board)
    v.SetPosition(VECTOR2I(mm(OX + i * G), mm(OY + j * G)))
    v.SetWidth(mm(VIA_D))
    v.SetDrill(mm(VIA_DRILL))
    v.SetNet(nets_by_code[code])
    board.Add(v)

for lid in (pcbnew.F_Cu, pcbnew.B_Cu):
    z = pcbnew.ZONE(board)
    z.SetLayer(lid)
    z.SetNet(nets_by_code[netcodes["GND"]])
    z.SetLocalClearance(mm(0.2))
    z.SetMinThickness(mm(0.2))
    z.SetPadConnection(pcbnew.ZONE_CONNECTION_FULL)
    z.SetThermalReliefGap(mm(0.25))
    z.SetThermalReliefSpokeWidth(mm(0.3))
    ol = z.Outline()
    ol.NewOutline()
    for x, y in [(0.0, 0.0), (W, 0.0), (W, H), (0.0, H)]:
        ol.Append(mm(OX + x), mm(OY + y))
    board.Add(z)
pcbnew.ZONE_FILLER(board).Fill(board.Zones())

board.Save(PCB)
print("tracks", len(tracks), "vias", len(vias), "failed", failed)
sys.exit(1 if failed else 0)

#!/usr/bin/env python3
"""Generates the MeshCore USB LoRa stick PCB (KiCad 7) from a netlist written in code.

    CH341F (USB to SPI, no MCU, no crystal) + Waveshare Core1262-HF (SX1262, TCXO)

Wiring is the USB MeshStick's, so the AndroidMeshcore CH341 driver works unchanged:
CH341 D0 = NSS, D2 = RESET, D4 = BUSY, D6 = DIO1, D3/D5/D7 = SCK/MOSI/MISO,
TCXO on DIO3 (inside the module), RF switch from DIO2 (TXEN = DIO2, RXEN = not DIO2).

Run with the Python that ships with KiCad 7:  python3 gen_board.py
It writes usb-stick.kicad_pcb (unrouted) and usb-stick.dsn for Freerouting; see build.sh.
"""
import os
import sys

import pcbnew
from pcbnew import FromMM as mm, VECTOR2I

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = "/usr/share/kicad/footprints"
OX, OY = 100.0, 100.0          # board origin on the KiCad sheet
BOARD_W, BOARD_H = 29.0, 22.0  # x = length (USB end at x=0), y = width

board = pcbnew.BOARD()
nets = {}


def net(name):
    if name not in nets:
        n = pcbnew.NETINFO_ITEM(board, name)
        board.Add(n)
        nets[name] = n
    return nets[name]


def P(x, y):
    return VECTOR2I(mm(OX + x), mm(OY + y))


def place(lib, name, ref, value, x, y, rot=0.0, pads=None, lcsc="", side="F"):
    fp = pcbnew.FootprintLoad(f"{LIB}/{lib}.pretty", name)
    fp.SetReference(ref)
    fp.SetValue(value)
    fp.SetPosition(P(x, y))
    fp.SetOrientationDegrees(rot)
    if lcsc:
        fp.SetProperty("LCSC", lcsc)
    board.Add(fp)
    fp.Reference().SetVisible(False)
    fp.Value().SetVisible(False)
    if side == "B":
        fp.Flip(fp.GetPosition(), False)
    for p in fp.Pads():
        num = p.GetNumber()
        if pads and num in pads and pads[num]:
            p.SetNet(net(pads[num]))
    return fp


# ---------------------------------------------------------------- nets
GND, VBUS, V33 = "GND", "VBUS", "+3V3"

# ---------------------------------------------------------------- USB-C receptacle (USB 2.0, device)
usb_pads = {
    "A1": GND, "A12": GND, "B1": GND, "B12": GND, "S1": GND,
    "A4": VBUS, "A9": VBUS, "B4": VBUS, "B9": VBUS,
    "A5": "CC1", "B5": "CC2",
    "A6": "USB_DP", "B6": "USB_DP", "A7": "USB_DN", "B7": "USB_DN",
}
# Rotated so the opening faces -x and the front of the shell is flush with the board edge at x=0.
place("Connector_USB", "USB_C_Receptacle_HRO_TYPE-C-31-M-12", "J1", "USB-C 16P", 3.65, 11.0, 270,
      usb_pads, lcsc="C165948")

# ---------------------------------------------------------------- CH341F, QFN-28 4x4 (pin numbers = CH341A SOP-28)
ch341_pads = {
    "1": "ACT_N",      # ACT#: low once USB is configured -> LED
    # 2 RSTI: internal pull-down and power-on reset, left open
    "9": V33,          # V3 tied to VCC for 3.3 V operation
    "10": "USB_DP", "11": "USB_DN",
    "12": GND,
    "13": GND,         # XI to GND: built-in clock, no crystal (CH341F/B only)
    "15": "LORA_NSS",  # D0
    # 16 D1: spare (the MeshStick leaves it free for RXEN; here RXEN comes from DIO2)
    "17": "LORA_RESET",  # D2
    "18": "LORA_SCK",  # D3 / DCK
    "19": "LORA_BUSY",  # D4
    "20": "LORA_MOSI",  # D5 / DOUT
    "21": "LORA_DIO1",  # D6
    "22": "LORA_MISO",  # D7 / DIN
    "23": GND,         # SDA low, SCL open: EPP/MEM/SPI mode, 1a86:5512
    "28": V33,         # VCC
    "29": GND,         # exposed pad
}
u1 = place("Package_DFN_QFN", "QFN-28-1EP_4x4mm_P0.4mm_EP2.4x2.4mm", "U1", "CH341F", 20.0, 9.5, 0,
           ch341_pads, lcsc="C412870")

# ---------------------------------------------------------------- 3.3 V LDO, ME6211C33 (500 mA)
place("Package_TO_SOT_SMD", "SOT-23-5", "U2", "ME6211C33M5G-N", 11.5, 3.0, 90,
      {"1": VBUS, "2": GND, "3": VBUS, "5": V33}, lcsc="C82942")

# ---------------------------------------------------------------- RF switch: RXEN = not DIO2
# SN74LVC1G04: 1 NC, 2 A, 3 GND, 4 Y, 5 VCC
place("Package_TO_SOT_SMD", "SOT-23-5", "U3", "SN74LVC1G04DBVR", 13.5, 17.5, 0,
      {"2": "LORA_DIO2", "3": GND, "4": "LORA_RXEN", "5": V33}, lcsc="C7827")

# ---------------------------------------------------------------- passives
R0402 = ("Resistor_SMD", "R_0402_1005Metric")
C0402 = ("Capacitor_SMD", "C_0402_1005Metric")
C0603 = ("Capacitor_SMD", "C_0603_1608Metric")


def two(kind, ref, value, x, y, rot, a, b, lcsc):
    place(kind[0], kind[1], ref, value, x, y, rot, {"1": a, "2": b}, lcsc=lcsc)


two(R0402, "R1", "5.1k", 9.6, 8.6, 90, "CC1", GND, "C25905")   # CC pull-downs: this is a device (sink)
two(R0402, "R2", "5.1k", 9.6, 13.4, 90, "CC2", GND, "C25905")
two(C0402, "C1", "1uF", 9.0, 3.0, 90, VBUS, GND, "C52923")     # LDO in
two(C0603, "C2", "10uF", 14.2, 3.0, 90, V33, GND, "C19702")    # LDO out / bulk for TX
two(C0402, "C3", "100nF", 17.8, 6.2, 0, V33, GND, "C1525")     # CH341 VCC
two(C0603, "C5", "10uF", 16.4, 2.4, 90, V33, GND, "C19702")    # module 3V3
two(C0402, "C6", "100nF", 18.0, 2.4, 90, V33, GND, "C1525")
two(C0402, "C7", "100nF", 16.2, 19.8, 0, V33, GND, "C1525")    # inverter VCC
# Status LED: on once the phone has configured the stick
place("LED_SMD", "LED_0603_1608Metric", "D1", "Green", 13.4, 6.4, 0, {"1": "ACT_N", "2": "LED_A"},
      lcsc="C72043")
two(R0402, "R5", "1k", 15.85, 6.4, 0, "LED_A", V33, "C11702")

# ---------------------------------------------------------------- Core1262-HF, hand-soldered on the back
# Pads as seen from the top of this board, i.e. the module's own bottom face (its pinout photo),
# rotated so the antenna side (IPEX) faces the far end. Module outline: x 9..28, y 0..22.
MOD_X0, MOD_X1 = 9.0, 28.0
ROW_NEAR = MOD_X0 + 1.0          # hole centres 1.00 mm from this module edge
ROW_FAR = ROW_NEAR + 15.24       # and 15.24 mm further (2.76 mm from the other edge)
PITCH, Y0 = 2.54, (BOARD_H - 7 * 2.54) / 2
near = ["+3V3", GND, "LORA_DIO1", "LORA_DIO2", "LORA_DIO2", "LORA_RXEN", GND, GND]  # TXEN = DIO2
far = ["LORA_BUSY", "LORA_RESET", "LORA_MISO", "LORA_MOSI", "LORA_SCK", "LORA_NSS", GND, "LORA_ANT"]
labels_near = ["3V3", "GND", "DIO1", "DIO2", "TXEN", "RXEN", "GND", "GND"]
labels_far = ["BUSY", "RST", "MISO", "MOSI", "CLK", "CS", "GND", "ANT"]

mod = pcbnew.FOOTPRINT(board)
mod.SetReference("M1")
mod.SetValue("Core1262-HF")
mod.SetFPID(pcbnew.LIB_ID("usb-stick", "Waveshare_Core1262_Castellated"))
mod.SetPosition(P(0, 0))
mod.SetAttributes(pcbnew.FP_SMD | pcbnew.FP_EXCLUDE_FROM_BOM | pcbnew.FP_EXCLUDE_FROM_POS_FILES)
board.Add(mod)
mod.Reference().SetVisible(False)
mod.Value().SetVisible(False)


def mod_pad(num, x0, x1, y, netname):
    p = pcbnew.PAD(mod)
    p.SetNumber(str(num))
    p.SetShape(pcbnew.PAD_SHAPE_RECT)
    p.SetAttribute(pcbnew.PAD_ATTRIB_SMD)
    ls = pcbnew.LSET()
    ls.AddLayer(pcbnew.B_Cu)
    ls.AddLayer(pcbnew.B_Mask)
    p.SetLayerSet(ls)  # no paste: soldered by hand
    p.SetSize(VECTOR2I(mm(x1 - x0), mm(1.6)))
    p.SetNet(net(netname))
    mod.Add(p)
    p.SetPos0(VECTOR2I(mm((x0 + x1) / 2), mm(y)))  # relative to the module origin = board origin
    p.SetPosition(P((x0 + x1) / 2, y))


for i in range(8):
    y = Y0 + i * PITCH
    mod_pad(i + 1, MOD_X0 - 0.8, ROW_NEAR + 1.0, y, near[i])
    mod_pad(16 - i, ROW_FAR - 1.0, MOD_X1 + 0.6, y, far[i])


def text(s, x, y, layer, size=0.8, rot=0, mirror=False):
    t = pcbnew.PCB_TEXT(board)
    t.SetText(s)
    t.SetPosition(P(x, y))
    t.SetLayer(layer)
    t.SetTextSize(VECTOR2I(mm(size), mm(size)))
    t.SetTextThickness(mm(size * 0.15))
    t.SetTextAngleDegrees(rot)
    t.SetMirrored(mirror)
    board.Add(t)


def line(x0, y0, x1, y1, layer, w=0.12):
    s = pcbnew.PCB_SHAPE(board)
    s.SetShape(pcbnew.SHAPE_T_SEGMENT)
    s.SetStart(P(x0, y0))
    s.SetEnd(P(x1, y1))
    s.SetLayer(layer)
    s.SetWidth(mm(w))
    board.Add(s)


# Module outline and pin names on the back silkscreen (pad areas left clear).
for (a, b, c, d) in [(MOD_X0 + 2.6, 0.3, ROW_FAR - 1.4, 0.3), (MOD_X0 + 2.6, BOARD_H - 0.3, ROW_FAR - 1.4, BOARD_H - 0.3)]:
    line(a, b, c, d, pcbnew.B_SilkS)
for i in range(8):
    y = Y0 + i * PITCH
    text(labels_near[i], ROW_NEAR + 3.0, y, pcbnew.B_SilkS, 0.8, 0, True)
    text(labels_far[i], ROW_FAR - 3.0, y, pcbnew.B_SilkS, 0.8, 0, True)
text("Core1262-HF", (MOD_X0 + MOD_X1) / 2, 6.0, pcbnew.B_SilkS, 0.9, 90, True)
text("IPEX side", ROW_FAR - 5.2, 15.5, pcbnew.B_SilkS, 0.8, 90, True)
text("AndroidMeshcore USB LoRa v0.1", 18.0, 21.0, pcbnew.F_SilkS, 0.8, 0)

# ---------------------------------------------------------------- outline (rounded rectangle)
r = 1.0
edges = [
    (r, 0, BOARD_W - r, 0), (BOARD_W, r, BOARD_W, BOARD_H - r),
    (BOARD_W - r, BOARD_H, r, BOARD_H), (0, BOARD_H - r, 0, r),
]
for e in edges:
    line(*e, pcbnew.Edge_Cuts, 0.1)
for cx, cy, sx, sy in [(r, r, 0, r), (BOARD_W - r, r, BOARD_W - r, 0), (BOARD_W - r, BOARD_H - r, BOARD_W, BOARD_H - r),
                       (r, BOARD_H - r, r, BOARD_H)]:
    a = pcbnew.PCB_SHAPE(board)
    a.SetShape(pcbnew.SHAPE_T_ARC)
    a.SetCenter(P(cx, cy))
    a.SetStart(P(sx, sy))
    a.SetArcAngleAndEnd(pcbnew.EDA_ANGLE(90, pcbnew.DEGREES_T), True)
    a.SetLayer(pcbnew.Edge_Cuts)
    a.SetWidth(mm(0.1))
    board.Add(a)

# ---------------------------------------------------------------- design rules (JLCPCB 2-layer capable)
ds = board.GetDesignSettings()
ds.m_TrackMinWidth = mm(0.15)
ds.m_ViasMinSize = mm(0.6)
ds.m_ViasMinDrill = mm(0.3)
ds.m_MinThroughDrill = mm(0.3)
ds.m_MinClearance = mm(0.15)
ds.m_CopperEdgeClearance = mm(0.25)
ds.SetCopperLayerCount(2)
nc = ds.m_NetSettings.m_DefaultNetClass
nc.SetTrackWidth(mm(0.25))
nc.SetClearance(mm(0.15))
nc.SetViaDiameter(mm(0.6))
nc.SetViaDrill(mm(0.3))

# Every net must exist on the board before routing.
board.BuildConnectivity()

out = os.path.join(HERE, "usb-stick.kicad_pcb")
board.Save(out)
dsn = os.path.join(HERE, "build", "usb-stick.dsn")
os.makedirs(os.path.dirname(dsn), exist_ok=True)
ok = pcbnew.ExportSpecctraDSN(board, dsn)
print("saved", out, "dsn", dsn, ok)

# Quick sanity print: where the USB pads ended up.
for fp in board.GetFootprints():
    if fp.GetReference() == "J1":
        xs = sorted({round(pcbnew.ToMM(p.GetPosition().x) - OX, 2) for p in fp.Pads() if p.GetNumber().startswith(("A", "B"))})
        print("J1 signal pad x:", xs)
sys.exit(0 if ok else 1)

#!/usr/bin/env python3
"""Generates the MeshCore USB LoRa stick PCB (KiCad 7) from a netlist written in code. v0.2:

    CH341F (USB to SPI, no MCU, no crystal) + Seeed Wio-SX1262 (SX1262, TCXO, IPEX)

Wiring is the USB MeshStick's, so the AndroidMeshcore CH341 driver works unchanged:
CH341 D0 = NSS, D2 = RESET, D4 = BUSY, D6 = DIO1, D3/D5/D7 = SCK/MOSI/MISO. The module's TCXO is on DIO3
and DIO2 switches it to transmit internally; its RF_SW pin (high = receive) goes to CH341 D1, the pin the
MeshStick leaves free for RXEN, and the app drives D1 as RXEN.

Run with the Python that ships with KiCad 7:  python3 gen_board.py   (see build.sh for the whole chain)
"""
import os
import sys

import pcbnew
from pcbnew import FromMM as mm, VECTOR2I

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = "/usr/share/kicad/footprints"
OX, OY = 100.0, 100.0          # board origin on the KiCad sheet
BOARD_W, BOARD_H = 20.5, 14.0  # x = length (USB end at x=0), y = width
VERSION = "v0.2"

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


def place(lib, name, ref, value, x, y, rot=0.0, pads=None, lcsc=""):
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
    for p in fp.Pads():
        num = p.GetNumber()
        if pads and num in pads and pads[num]:
            p.SetNet(net(pads[num]))
    return fp


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


GND, VBUS, V33 = "GND", "VBUS", "+3V3"

# ---------------------------------------------------------------- USB-C receptacle (USB 2.0, device)
usb_pads = {
    "A1": GND, "A12": GND, "B1": GND, "B12": GND, "S1": GND,
    "A4": VBUS, "A9": VBUS, "B4": VBUS, "B9": VBUS,
    "A5": "CC1", "B5": "CC2",
    "A6": "USB_DP", "B6": "USB_DP", "A7": "USB_DN", "B7": "USB_DN",
}
# Rotated so the opening faces -x and the front of the shell is flush with the board edge at x=0.
place("Connector_USB", "USB_C_Receptacle_HRO_TYPE-C-31-M-12", "J1", "USB-C 16P", 3.65, BOARD_H / 2, 270,
      usb_pads, lcsc="C165948")

# ---------------------------------------------------------------- CH341F, QFN-28 4x4 (pin numbers = CH341A SOP-28)
ch341_pads = {
    "1": "ACT_N",        # ACT#: low once USB is configured -> LED
    # 2 RSTI: internal pull-down and power-on reset, left open
    "9": V33,            # V3 tied to VCC for 3.3 V operation
    "10": "USB_DP", "11": "USB_DN",
    "12": GND,
    "13": GND,           # XI to GND: built-in clock, no crystal (CH341F/B only)
    "15": "LORA_NSS",    # D0
    "16": "LORA_RXEN",   # D1 -> module RF_SW (high = receive)
    "17": "LORA_RESET",  # D2
    "18": "LORA_SCK",    # D3 / DCK
    "19": "LORA_BUSY",   # D4
    "20": "LORA_MOSI",   # D5 / DOUT
    "21": "LORA_DIO1",   # D6
    "22": "LORA_MISO",   # D7 / DIN
    "23": GND,           # SDA low, SCL open: EPP/MEM/SPI mode, 1a86:5512
    "28": V33,           # VCC
    "29": GND,           # exposed pad
}
place("Package_DFN_QFN", "QFN-28-1EP_4x4mm_P0.4mm_EP2.4x2.4mm", "U1", "CH341F", 14.6, 7.0, 0,
      ch341_pads, lcsc="C412870")

# ---------------------------------------------------------------- 3.3 V LDO, ME6211C33 (500 mA)
place("Package_TO_SOT_SMD", "SOT-23-5", "U2", "ME6211C33M5G-N", 12.9, 2.2, 0,
      {"1": VBUS, "2": GND, "3": VBUS, "5": V33}, lcsc="C82942")

# ---------------------------------------------------------------- passives
R0402 = ("Resistor_SMD", "R_0402_1005Metric")
C0402 = ("Capacitor_SMD", "C_0402_1005Metric")
C0603 = ("Capacitor_SMD", "C_0603_1608Metric")


def two(kind, ref, value, x, y, rot, a, b, lcsc):
    place(kind[0], kind[1], ref, value, x, y, rot, {"1": a, "2": b}, lcsc=lcsc)


two(R0402, "R1", "5.1k", 9.65, 5.2, 90, "CC1", GND, "C25905")   # CC pull-downs: this is a device (sink)
two(R0402, "R2", "5.1k", 9.65, 9.9, 90, "CC2", GND, "C25905")
two(C0402, "C1", "1uF", 10.25, 2.6, 90, VBUS, GND, "C52923")      # LDO in
two(C0603, "C2", "10uF", 16.45, 1.4, 0, V33, GND, "C19702")     # LDO out / bulk for TX
two(C0402, "C3", "100nF", 13.4, 10.9, 0, V33, GND, "C1525")    # CH341 VCC / V3
two(C0603, "C5", "10uF", 18.6, 12.4, 0, V33, GND, "C19702")     # module VCC
two(C0402, "C6", "100nF", 16.1, 12.4, 0, V33, GND, "C1525")
# Status LED: on once the phone has configured the stick
place("LED_SMD", "LED_0603_1608Metric", "D1", "Green", 10.95, 12.7, 0, {"1": "ACT_N", "2": "LED_A"},
      lcsc="C72043")
two(R0402, "R5", "1k", 13.55, 12.7, 0, "LED_A", V33, "C11702")

# ---------------------------------------------------------------- Wio-SX1262, hand-soldered on the back
# Land pattern from Seeed's footprint MODULE12P-1.27-11X11.6MM (11.6 x 11 mm, 12 edge pads, 1.27 mm pitch),
# in KiCad coordinates seen from the module's top: pins 1-8 along one 11.6 mm edge, 9-12 along the other,
# IPEX in the corner past pin 9.
MOD_PINS = {
    1: (-4.471, 5.2, "LORA_RXEN"),   # RF_SW, high = receive
    2: (-3.201, 5.2, "LORA_MISO"),
    3: (-1.931, 5.2, "LORA_MOSI"),
    4: (-0.661, 5.2, "LORA_SCK"),
    5: (0.609, 5.2, "LORA_RESET"),   # NRST
    6: (1.879, 5.2, "LORA_NSS"),
    7: (3.149, 5.2, GND),
    8: (4.419, 5.2, V33),            # VCC
    9: (0.609, -5.2, "LORA_ANT"),    # RF pad, only on the -N variant; the IPEX is used instead
    10: (-0.661, -5.2, GND),
    11: (-1.931, -5.2, "LORA_BUSY"),
    12: (-3.201, -5.2, "LORA_DIO1"),
}
MOD_CX, MOD_CY = 14.15, BOARD_H / 2

mod = pcbnew.FOOTPRINT(board)
mod.SetReference("M1")
mod.SetValue("Wio-SX1262")
mod.SetFPID(pcbnew.LIB_ID("usb-stick", "Seeed_Wio-SX1262_MODULE12P-1.27-11x11.6mm"))
mod.SetAttributes(pcbnew.FP_SMD | pcbnew.FP_EXCLUDE_FROM_BOM | pcbnew.FP_EXCLUDE_FROM_POS_FILES)
board.Add(mod)
mod.SetPosition(P(0, 0))
for num, (x, y, netname) in MOD_PINS.items():
    p = pcbnew.PAD(mod)
    p.SetNumber(str(num))
    p.SetShape(pcbnew.PAD_SHAPE_RECT)
    p.SetAttribute(pcbnew.PAD_ATTRIB_SMD)
    ls = pcbnew.LSET()
    ls.AddLayer(pcbnew.F_Cu)
    ls.AddLayer(pcbnew.F_Mask)
    p.SetLayerSet(ls)   # no paste: soldered by hand
    p.SetSize(VECTOR2I(mm(0.6), mm(1.6)))
    p.SetNet(net(netname))
    mod.Add(p)
    p.SetPos0(VECTOR2I(mm(x), mm(y)))
    p.SetPosition(P(x, y))


def mod_shape(x0, y0, x1, y1, w=0.12):
    s = pcbnew.FP_SHAPE(mod)
    s.SetShape(pcbnew.SHAPE_T_SEGMENT)
    s.SetStart0(VECTOR2I(mm(x0), mm(y0)))
    s.SetEnd0(VECTOR2I(mm(x1), mm(y1)))
    s.SetLayer(pcbnew.F_SilkS)
    s.SetWidth(mm(w))
    mod.Add(s)
    s.SetDrawCoord()


# outline (between the pad rows), pin 1 mark and IPEX corner
mod_shape(-5.8, -4.2, -5.8, 4.2)
mod_shape(5.8, -4.2, 5.8, 4.2)
mod_shape(-5.3, 3.4, -4.5, 3.4)
mod_shape(1.6, -4.2, 1.6, -1.6)
mod_shape(1.6, -1.6, 5.8, -1.6)
# SPI pins (1-8) towards the far end, under the CH341's SPI side; IPEX towards the USB end.
mod.SetPosition(P(MOD_CX, MOD_CY))
mod.SetOrientationDegrees(90)
mod.Flip(mod.GetPosition(), False)
mod.Reference().SetVisible(False)
mod.Value().SetVisible(False)

text("Wio-SX1262", MOD_CX - 0.5, MOD_CY, pcbnew.B_SilkS, 0.8, 90, True)
text("IPEX", MOD_CX - 3.0, MOD_CY + 3.7, pcbnew.B_SilkS, 0.8, 0, True)
text("MeshCore LoRa " + VERSION, 1.0, BOARD_H / 2, pcbnew.B_SilkS, 0.7, 90, True)

# ---------------------------------------------------------------- outline (rounded rectangle)
r = 1.0
for e in [(r, 0, BOARD_W - r, 0), (BOARD_W, r, BOARD_W, BOARD_H - r),
          (BOARD_W - r, BOARD_H, r, BOARD_H), (0, BOARD_H - r, 0, r)]:
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

# ---------------------------------------------------------------- design rules (JLCPCB 2-layer, standard)
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

board.BuildConnectivity()
out = os.path.join(HERE, "usb-stick.kicad_pcb")
board.Save(out)
print("saved", out)
for fp in board.GetFootprints():
    if fp.GetReference() == "M1":
        for p in sorted(fp.Pads(), key=lambda p: int(p.GetNumber())):
            print(f"  M1 pin {p.GetNumber():>2} {p.GetNetname():<11} x={pcbnew.ToMM(p.GetPosition().x) - OX:6.2f} "
                  f"y={pcbnew.ToMM(p.GetPosition().y) - OY:6.2f} {'B' if p.IsOnLayer(pcbnew.B_Cu) else 'F'}")
sys.exit(0)

#!/usr/bin/env python3
"""Writes the JLCPCB order files for the routed board into fab/:

  usb-stick-gerbers.zip   Gerber + Excellon drill, upload as the PCB
  usb-stick-bom.csv       BOM for SMT assembly (Comment, Designator, Footprint, LCSC Part #)
  usb-stick-cpl.csv       pick-and-place (Designator, Mid X, Mid Y, Layer, Rotation)

The Core1262-HF module (M1) and the 0R option R4 are left out of the assembly: solder M1 by hand.
"""
import csv
import os
import shutil
import subprocess
import zipfile

import pcbnew

HERE = os.path.dirname(os.path.abspath(__file__))
PCB = os.path.join(HERE, "usb-stick.kicad_pcb")
FAB = os.path.join(HERE, "fab")
TMP = os.path.join(HERE, "build", "gerbers")

shutil.rmtree(TMP, ignore_errors=True)
os.makedirs(TMP)
os.makedirs(FAB, exist_ok=True)

layers = "F.Cu,B.Cu,F.Paste,B.Paste,F.SilkS,B.SilkS,F.Mask,B.Mask,Edge.Cuts"
subprocess.run(["kicad-cli", "pcb", "export", "gerbers", "--layers", layers, "--subtract-soldermask",
                "--no-x2", "-o", TMP + "/", PCB], check=True, capture_output=True)
subprocess.run(["kicad-cli", "pcb", "export", "drill", "--format", "excellon", "--drill-origin", "absolute",
                "--excellon-separate-th", "-u", "mm", "-o", TMP + "/", PCB], check=True, capture_output=True)
zpath = os.path.join(FAB, "usb-stick-gerbers.zip")
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    for f in sorted(os.listdir(TMP)):
        z.write(os.path.join(TMP, f), f)

board = pcbnew.LoadBoard(PCB)
origin_x, origin_y = 100.0, 122.0   # bottom-left corner of the board, as JLCPCB expects (y up)
groups = {}
cpl = []
for fp in sorted(board.GetFootprints(), key=lambda f: f.GetReference()):
    ref = fp.GetReference()
    lcsc = fp.GetProperty("LCSC") if fp.HasProperty("LCSC") else ""
    if not lcsc:
        continue   # hand-soldered or not fitted
    key = (fp.GetValue(), fp.GetFPID().GetLibItemName().wx_str(), lcsc)
    groups.setdefault(key, []).append(ref)
    pos = fp.GetPosition()
    cpl.append([ref, f"{pcbnew.ToMM(pos.x) - origin_x:.3f}mm", f"{origin_y - pcbnew.ToMM(pos.y):.3f}mm",
                "Top" if fp.GetLayer() == pcbnew.F_Cu else "Bottom", f"{fp.GetOrientationDegrees() % 360:.0f}"])

with open(os.path.join(FAB, "usb-stick-bom.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["Comment", "Designator", "Footprint", "LCSC Part #"])
    for (value, footprint, lcsc), refs in sorted(groups.items(), key=lambda kv: kv[1][0]):
        w.writerow([value, ",".join(refs), footprint, lcsc])
with open(os.path.join(FAB, "usb-stick-cpl.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
    w.writerows(cpl)
print("wrote", sorted(os.listdir(FAB)))

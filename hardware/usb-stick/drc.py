#!/usr/bin/env python3
"""Runs KiCad's DRC on a board and prints a summary. Exit code 1 if there are violations."""
import collections
import re
import sys

import pcbnew

pcb = sys.argv[1]
report = sys.argv[2]
ignore_unconnected = "--ignore-unconnected" in sys.argv

board = pcbnew.LoadBoard(pcb)
pcbnew.WriteDRCReport(board, report, pcbnew.EDA_UNITS_MILLIMETRES, True)
text = open(report).read()

counts = collections.Counter(re.findall(r"^\[(\w+)\]", text, re.M))
for k, v in sorted(counts.items()):
    print(f"{v:4d} {k}")
bad = {k: v for k, v in counts.items() if not (ignore_unconnected and k == "unconnected_items")}
sys.exit(1 if bad else 0)

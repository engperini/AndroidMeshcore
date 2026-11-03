#!/usr/bin/env python3
"""Checks the Meshtastic PC test output: every STEP passed and every event is valid JSON the app can read."""
import json
import sys

steps, events, bad = {}, [], []
with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
    for line in f:
        line = line.rstrip("\n")
        if line.startswith("STEP "):
            _, name, ok = line.split()
            steps[name] = ok == "1"
        elif line.startswith("EV "):
            try:
                events.append(json.loads(line[3:]))
            except json.JSONDecodeError as e:
                bad.append(f"{line[:120]} ({e})")
        elif line.startswith("REBOOT "):
            print("node asked to reboot after the region change:", line.split()[1] == "1")

kinds = {}
for e in events:
    kinds[e["t"]] = kinds.get(e["t"], 0) + 1
print("events:", ", ".join(f"{k} {v}" for k, v in sorted(kinds.items())))
my = next((e for e in events if e["t"] == "my"), None)
if my:
    print(f"node !{my['num']:08x}")
for name, ok in steps.items():
    print(f"  {'ok  ' if ok else 'FAIL'} {name}")
for b in bad:
    print("  bad JSON:", b)

need = ["my", "node", "chan", "lora", "ready"]
missing = [k for k in need if k not in kinds]
if missing:
    print("missing events:", missing)
failed = not steps or not all(steps.values()) or bad or missing
print("FAILED" if failed else "All checks passed")
sys.exit(1 if failed else 0)

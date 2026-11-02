#!/usr/bin/env bash
# Rebuilds the board from gen_board.py: place -> route -> DRC -> JLCPCB files -> preview images.
# Needs KiCad 7 (pcbnew Python module and kicad-cli), numpy, scipy and cairosvg.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build preview
python3 gen_board.py
python3 route.py | tee build/route.log
python3 drc.py usb-stick.kicad_pcb build/drc.rpt || true
# Only library and edge-silkscreen warnings are acceptable:
if grep -qE '^\[(clearance|unconnected_items|shorting_items|hole_clearance|holes_co_located|hole_near_hole|copper_edge_clearance|courtyards_overlap|solder_mask_bridge|starved_thermal|track_width|annular_width|drill_out_of_range)\]' build/drc.rpt; then
  echo "DRC errors, see build/drc.rpt" >&2
  exit 1
fi
python3 fab.py
kicad-cli pcb export svg --layers F.Cu,F.SilkS,F.Mask,Edge.Cuts --page-size-mode 2 --exclude-drawing-sheet \
  -o build/top.svg usb-stick.kicad_pcb >/dev/null
kicad-cli pcb export svg --layers B.Cu,B.SilkS,Edge.Cuts --mirror --page-size-mode 2 --exclude-drawing-sheet \
  -o build/bottom.svg usb-stick.kicad_pcb >/dev/null
python3 -c "
import cairosvg
cairosvg.svg2png(url='build/top.svg', write_to='preview/top.png', output_width=1300, background_color='white')
cairosvg.svg2png(url='build/bottom.svg', write_to='preview/bottom.png', output_width=1300, background_color='white')"
echo "done: fab/ and preview/"

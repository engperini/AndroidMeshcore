// Case for the MeshCore USB LoRa stick v0.2 (hardware/usb-stick: CH341F + Wio-SX1262)
// with a straight USB-C male-male adapter. Adjust the adapter numbers to the one you bought.
include <case_lib.scad>

part = "both";   // "base", "lid" or "both"

// --- board (see hardware/usb-stick): 20.5 x 14 mm, Wio-SX1262 soldered underneath
pcb_len = 20.5;
pcb_w = 14.0;
pcb_t = 1.6;
module_h = 3.0;    // Wio-SX1262 (2.95 mm) under the board, resting on the floor
top_h = 3.3;       // tallest top part: the USB-C socket (3.26 mm)
usb_z = module_h + pcb_t + 1.63;   // socket centre above the cavity floor

// --- straight USB-C male-male adapter
a_len = 8.0;
a_w = 11.0;
a_h = 5.0;
plug_w = 8.4;
plug_h = 2.6;

ant_slot = [3.0, 2.2];   // U.FL cable from the module's IPEX: under the board, near the USB end
ipex_x = 11.25;          // IPEX position along the board (see gen_board.py)

board_x = a_len - (wall - lip);   // board starts right behind the adapter body
in_len = board_x + pcb_len + tol;
in_w = max(pcb_w, a_w) + 2 * tol;
in_h = max(module_h + pcb_t + top_h, usb_z + a_h / 2) + tol;
out_w = in_w + 2 * wall;

y0 = (in_w - pcb_w) / 2;                    // board offset across the cavity
led = [board_x + 10.95, y0 + 12.7];         // status LED D1
board_top = module_h + pcb_t;
// lid posts: one on the USB-C socket shell, one at the far end of the board
press = [[board_x + 3.65, y0 + pcb_w / 2, in_h - (board_top + 3.26) - 0.1],
         [board_x + 19.6, y0 + pcb_w / 2, in_h - board_top - 0.1]];

if (part == "base" || part == "both")
    case_base(in_len, in_w, in_h, a_len, a_w, a_h, plug_w, plug_h, usb_z, ant_slot, "USB LoRa",
              ant_side = [board_x + ipex_x, module_h / 2 + 0.3]);
if (part == "lid" || part == "both")
    translate([0, part == "both" ? out_w + 5 : 0, 0]) case_lid(in_len, in_w, ant_slot, led, press, false);

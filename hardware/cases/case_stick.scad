// Case for the MeshCore USB LoRa stick (hardware/usb-stick, CH341F + Core1262-HF)
// with a straight USB-C male-male adapter. Adjust the adapter numbers to the one you bought.
include <case_lib.scad>

part = "both";   // "base", "lid" or "both"

// --- board (see hardware/usb-stick): 29 x 22 mm, module soldered underneath
pcb_len = 29.0;
pcb_w = 22.0;
pcb_t = 1.6;
module_h = 3.3;    // Core1262-HF incl. shield, under the board
top_h = 3.3;       // tallest top part: the USB-C socket (3.26 mm)
usb_z = module_h + pcb_t + 1.63;   // socket centre above the cavity floor

// --- straight USB-C male-male adapter
a_len = 8.0;
a_w = 11.0;
a_h = 5.0;
plug_w = 8.4;
plug_h = 2.6;

ant_slot = [3.0, 2.2];   // U.FL cable from the module's IPEX (far end, under the board)

board_x = a_len - (wall - lip);   // board starts right behind the adapter body
in_len = board_x + pcb_len + tol;
in_w = pcb_w + 2 * tol;
in_h = max(module_h + pcb_t + top_h, usb_z + a_h / 2) + tol;
out_w = in_w + 2 * wall;

led = [board_x + 14.6, tol + 6.4];          // status LED D1
board_top = module_h + pcb_t;
press = [[board_x + 27.6, tol + 1.4, in_h - board_top - 0.1],
         [board_x + 27.6, tol + pcb_w - 1.4, in_h - board_top - 0.1]];

if (part == "base" || part == "both")
    case_base(in_len, in_w, in_h, a_len, a_w, a_h, plug_w, plug_h, usb_z, ant_slot, "USB LoRa");
if (part == "lid" || part == "both")
    translate([0, part == "both" ? out_w + 5 : 0, 0]) case_lid(in_len, in_w, ant_slot, led, press);

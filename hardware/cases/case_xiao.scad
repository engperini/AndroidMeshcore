// Case for the Seeed XIAO nRF52840 + Wio-SX1262 kit with a straight USB-C male-male adapter.
// MEASURE YOUR PARTS and adjust the numbers below before printing (calipers, 0.1 mm is enough).
include <case_lib.scad>

part = "both";   // "base", "lid" or "both"

// --- XIAO + Wio-SX1262 stack
xiao_len = 21.0;   // from the USB-C end to the far end
xiao_w = 17.8;     // width
stack_h = 9.0;     // full height of the stack, bottom of the Wio board to the top of the XIAO's USB-C
usb_z = 6.6;       // centre of the XIAO's USB-C socket above the bottom of the stack

// --- straight USB-C male-male adapter
a_len = 8.0;       // body length (without the two plugs)
a_w = 11.0;        // body width
a_h = 5.0;         // body height
plug_w = 8.4;      // USB-C plug shell (standard: 8.25 x 2.4)
plug_h = 2.6;

ant_slot = [3.0, 2.2];   // U.FL antenna cable slot (rear wall)

in_len = a_len - (wall - lip) + xiao_len + tol;
in_w = max(xiao_w, a_w) + 2 * tol;
in_h = max(stack_h, usb_z + a_h / 2) + tol;
out_w = in_w + 2 * wall;

if (part == "base" || part == "both")
    case_base(in_len, in_w, in_h, a_len, a_w, a_h, plug_w, plug_h, usb_z, ant_slot, "XIAO LoRa");
if (part == "lid" || part == "both")
    translate([0, part == "both" ? out_w + 5 : 0, 0]) case_lid(in_len, in_w, ant_slot);

// Shared parts for the two USB LoRa cases (XIAO kit and the CH341 stick).
// Units: mm. Print: PLA/PETG, 0.2 mm layers, base upside-up, lid upside-down, no supports.
//
// Layout (x = length, the phone end is x = 0):
//   [front wall + lip] [straight USB-C male-male adapter body] [board stack] [rear wall, antenna slot]

$fn = 48;

wall = 1.6;      // side walls
floor_t = 1.2;   // bottom
lid_t = 1.2;     // top
tol = 0.3;       // clearance around parts
lip = 0.6;       // front lip that keeps the adapter in when unplugging from the phone
skirt = 1.6;     // depth of the lid plug that goes inside the walls
fit = 0.15;      // lid plug clearance (press fit); raise to 0.2 if too tight

module rrect(size, r) {   // rounded rectangle in XY, extruded in Z
    x = size[0]; y = size[1]; z = size[2];
    hull() for (i = [r, x - r], j = [r, y - r]) translate([i, j, 0]) cylinder(r = r, h = z);
}

// Plug opening, a rounded slot like the USB-C plug shell.
module plug_hole(w, h, depth) {
    r = h / 2;
    hull() for (k = [-(w / 2 - r), w / 2 - r]) translate([0, k, 0]) rotate([0, 90, 0]) cylinder(r = r, h = depth);
}

// in_len/in_w/in_h: cavity. a_*: adapter body. a_z: adapter (plug) centre height above the cavity floor.
// ant_slot: [width, height] of the antenna cable slot in the rear wall, at the top.
// ant_side: [x, z] to put that slot in the side wall instead (x along the case, z above the floor).
// led: [x, y] above the cavity origin for a light hole in the lid, or [] for none.
module case_base(in_len, in_w, in_h, a_len, a_w, a_h, plug_w, plug_h, a_z, ant_slot, label = "", ant_side = []) {
    out_len = in_len + 2 * wall;
    out_w = in_w + 2 * wall;
    out_h = in_h + floor_t;
    difference() {
        rrect([out_len, out_w, out_h], 1.5);
        // cavity, open at the top
        translate([wall, wall, floor_t]) cube([in_len, in_w, in_h + 1]);
        // adapter body pocket reaches into the front wall, leaving only the lip
        translate([lip, out_w / 2 - (a_w + tol) / 2, floor_t + a_z - (a_h + tol) / 2])
            cube([wall - lip + 0.01, a_w + tol, a_h + tol]);
        // plug goes out through the lip
        translate([-1, out_w / 2, floor_t + a_z]) plug_hole(plug_w + tol, plug_h + tol, lip + 2);
        // antenna cable slot: rear wall from the top edge down, or a side wall hole
        if (len(ant_side) == 2)
            translate([wall + ant_side[0] - ant_slot[0] / 2, out_w - wall - 1, floor_t + ant_side[1] - ant_slot[1] / 2])
                cube([ant_slot[0], wall + 2, ant_slot[1]]);
        else
            translate([out_len - wall - 1, out_w / 2 - ant_slot[0] / 2, out_h - ant_slot[1]])
                cube([wall + 2, ant_slot[0], ant_slot[1] + 1]);
        // name on the bottom
        if (label != "")
            translate([out_len / 2, out_w / 2, 0.4]) rotate([180, 0, 0])
                linear_extrude(0.5) text(label, size = 3, halign = "center", valign = "center");
    }
    // adapter cradle: two side rails that keep the body centred
    for (s = [-1, 1])
        translate([wall, out_w / 2 + s * ((a_w + tol) / 2 + 0.6) - 0.6, floor_t])
            cube([a_len, 1.2, max(0.01, a_z - (a_h + tol) / 2 + a_h * 0.6)]);
}

module case_lid(in_len, in_w, ant_slot, led = [], press = [], rear_slot = true) {
    out_len = in_len + 2 * wall;
    out_w = in_w + 2 * wall;
    difference() {
        union() {
            rrect([out_len, out_w, lid_t], 1.5);
            // plug that fits inside the walls
            translate([wall + fit, wall + fit, lid_t])
                difference() {
                    rrect([in_len - 2 * fit, in_w - 2 * fit, skirt], 0.8);
                    translate([1.2, 1.2, -1]) cube([in_len - 2 * fit - 2.4, in_w - 2 * fit - 2.4, skirt + 2]);
                    // leave the antenna slot free
                    if (rear_slot)
                        translate([in_len - 2 * fit - 2, (in_w - 2 * fit) / 2 - ant_slot[0] / 2, -1])
                            cube([3, ant_slot[0], skirt + 2]);
                }
            // posts that press the board down: [x, y, length] from the cavity origin
            for (p = press) translate([wall + p[0], wall + p[1], lid_t]) cylinder(d = 2.4, h = p[2]);
        }
        if (len(led) == 2) translate([wall + led[0], wall + led[1], -1]) cylinder(d = 1.6, h = lid_t + 2);
    }
}

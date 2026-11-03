// Hands the app's USB radio bus to the firmware's CH341 HAL (pinedio_bus.cpp).
#pragma once

#include "RadioUsbBus.h"

// logical_pins: true for the XIAO bridge (logical pin ids), false for a CH341 (D-lines).
void mt_set_bus(RadioUsbBus* bus, bool logical_pins);

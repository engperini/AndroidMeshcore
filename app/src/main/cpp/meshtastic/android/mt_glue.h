// Glue between the Meshtastic firmware and the app (mt_glue.cpp).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

void mt_set_data_root(const std::string& root);
void mt_fs_mount();
bool mt_take_reboot_request();

// True for the PC test build (no USB radio): the firmware uses its SimRadio.
bool mt_use_simradio();

int mt_set_region(int region);

// Sets the node's clock from the phone's (network/NTP time), as the official apps do when they connect.
// Without a valid clock the node does not record when it last heard each node.
void mt_sync_clock();

// Idle wait of the firmware loop (see emscripten.h): returns after [ms], when the radio raises its IRQ line,
// or when mt_wake() is called (the app queued a request).
void mt_idle(unsigned int ms);
void mt_wake();

// Polls the attached radio interrupts (pinedio_bus.cpp); true if one fired.
bool mt_poll_irqs();
bool mt_api_to_radio(const uint8_t* buf, size_t len);
size_t mt_api_from_radio(uint8_t* out, size_t max);

// Firmware entry points and its portduino init (PortduinoGlue.cpp).
extern "C" void setup();
extern "C" void loop();
void portduinoSetup();

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
bool mt_api_to_radio(const uint8_t* buf, size_t len);
size_t mt_api_from_radio(uint8_t* out, size_t max);

// Firmware entry points and its portduino init (PortduinoGlue.cpp).
extern "C" void setup();
extern "C" void loop();
void portduinoSetup();

#pragma once

#include <cstddef>
#include <cstdint>

// A USB device that gives the phone the SX1262's SPI bus and control pins.
// Implemented by Ch341Bridge (USB MeshStick) and XiaoBridge (XIAO running
// xiao-sx1262-usb-bridge). Pin numbers are whatever the device uses: CH341
// D0..D7 lines, or the bridge's logical pin ids.
class RadioUsbBus {
public:
  // Radio wiring the device reports about itself, if it can.
  struct Hints {
    bool valid = false;
    int tcxo_mv = 0;          // TCXO voltage on DIO3, 0 for a crystal
    bool dio2_rf_switch = false;
    bool has_rxen = false;
    bool has_txen = false;
  };

  virtual ~RadioUsbBus() = default;

  // Driver name reported in the radio diagnostics, e.g. "sx1262_ch341".
  virtual const char* driverName() const = 0;

  // Throws std::runtime_error on failure.
  virtual void open() = 0;
  virtual void close() = 0;

  virtual void pinMode(int pin, bool output) = 0;
  virtual void digitalWrite(int pin, bool high) = 0;
  virtual bool digitalRead(int pin) = 0;

  // Full-duplex SPI transfer (mode 0, MSB first). Chip select is not touched.
  virtual void spiTransfer(const uint8_t* tx, uint8_t* rx, size_t len) = 0;

  // One SX1262 command: chip select low, transfer, chip select high.
  virtual void spiCommand(int cs_pin, const uint8_t* tx, uint8_t* rx, size_t len) {
    digitalWrite(cs_pin, false);
    try {
      spiTransfer(tx, rx, len);
    } catch (...) {
      digitalWrite(cs_pin, true);
      throw;
    }
    digitalWrite(cs_pin, true);
  }

  // True when spiCommand() already waits for BUSY low, so the radio driver
  // does not need to poll it first.
  virtual bool waitsForBusy() const { return false; }

  virtual Hints hints() const { return {}; }

  // Added to radio start-up errors, to point at the likely cause.
  virtual const char* troubleshooting() const { return ""; }
};

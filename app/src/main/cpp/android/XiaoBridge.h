#pragma once

#include "RadioUsbBus.h"

#include <cstddef>
#include <cstdint>
#include <vector>

// Driver for a Seeed XIAO + Wio-SX1262 kit running the xiao-sx1262-usb-bridge
// firmware (https://github.com/engperini/xiao-sx1262-usb-bridge). The XIAO is
// a USB CDC serial device; the bridge moves SPI bytes and the radio pins over
// a small framed protocol (docs/PROTOCOL.md in that repository).
//
// The app opens the device with the Android USB Host API, claims the CDC
// interfaces, asserts DTR and hands over the file descriptor and the bulk
// endpoints with setAndroidUsb(). Transfers then go straight to usbdevfs.
//
// Pins are the bridge's logical ids: 0 NSS, 1 RESET, 2 BUSY, 3 DIO1, 4 RXEN,
// 5 TXEN.
class XiaoBridge : public RadioUsbBus {
public:
  static constexpr int PIN_NSS = 0;
  static constexpr int PIN_RESET = 1;
  static constexpr int PIN_BUSY = 2;
  static constexpr int PIN_DIO1 = 3;
  static constexpr int PIN_RXEN = 4;
  static constexpr int PIN_TXEN = 5;

  XiaoBridge() = default;
  ~XiaoBridge() override;

  XiaoBridge(const XiaoBridge&) = delete;
  XiaoBridge& operator=(const XiaoBridge&) = delete;

  static void setAndroidUsb(int fd, int ep_in, int ep_out, int data_iface);

  const char* driverName() const override { return "sx1262_xiao"; }
  void open() override;
  void close() override;

  void pinMode(int pin, bool output) override;
  void digitalWrite(int pin, bool high) override;
  bool digitalRead(int pin) override;
  void spiTransfer(const uint8_t* tx, uint8_t* rx, size_t len) override;
  void spiCommand(int cs_pin, const uint8_t* tx, uint8_t* rx, size_t len) override;
  bool waitsForBusy() const override { return true; }
  Hints hints() const override { return radio_hints; }
  const char* troubleshooting() const override;

private:
  bool is_open = false;
  int fd = -1;
  uint8_t ep_in = 0;
  uint8_t ep_out = 0;
  unsigned int iface = 0;
  uint8_t seq = 0;
  size_t max_transfer = 0;
  Hints radio_hints;
  std::vector<uint8_t> rx_buf;
  std::vector<uint8_t> frame;
  std::vector<uint8_t> reply;

  // Sends a request and returns the bridge status; data goes to `reply`.
  uint8_t request(uint8_t type, const uint8_t* payload, size_t len, int timeout_ms);
  void requestOk(uint8_t type, const uint8_t* payload, size_t len, int timeout_ms = 500);
  void hello();
  void drainInput();
  int bulkIn(uint8_t* buf, int len, unsigned int timeout_ms);
  void bulkOut(const uint8_t* buf, int len);
  void xfer(uint8_t flags, const uint8_t* tx, uint8_t* rx, size_t len);
};

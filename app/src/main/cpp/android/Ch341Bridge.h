#pragma once

#include "RadioUsbBus.h"

#include <cstddef>
#include <cstdint>

// Userspace driver for the WCH CH341A USB bridge in SPI/GPIO mode (1a86:5512),
// as used by the MeshStick and PineDio USB LoRa dongles.
//
// Android port: there is no libusb. The app obtains the device through the
// Android USB Host API and hands its file descriptor to setAndroidFd(); bulk
// transfers then go straight to usbdevfs with ioctl().
//
// Pins are the CH341 D0..D7 lines. In SPI mode D3 = SCK, D5 = MOSI and
// D7 = MISO are owned by the SPI engine; D0..D2 and D4 are usable as GPIO
// outputs/inputs, D6 as input.
class Ch341Bridge : public RadioUsbBus {
public:
  Ch341Bridge() = default;
  ~Ch341Bridge() override;

  Ch341Bridge(const Ch341Bridge&) = delete;
  Ch341Bridge& operator=(const Ch341Bridge&) = delete;

  const char* driverName() const override { return "sx1262_ch341"; }

  // Opens the device handed over with setAndroidFd(). Throws std::runtime_error on failure.
  void open() override;
  void close() override;

  // File descriptor from UsbDeviceConnection.getFileDescriptor(). The app
  // keeps ownership of the connection; close() only releases the interface.
  static void setAndroidFd(int fd);
  bool isOpen() const { return handle != nullptr; }

  void pinMode(int pin, bool output) override;
  void digitalWrite(int pin, bool high) override;
  bool digitalRead(int pin) override;

  // Full-duplex SPI transfer (mode 0, MSB first). Chip select is not touched.
  void spiTransfer(const uint8_t* tx, uint8_t* rx, size_t len) override;

  const char* troubleshooting() const override {
    return "; check the dongle, antenna and RPI_CS/RESET/BUSY pins (MeshStick: 0/2/4)";
  }

private:
  void* handle = nullptr;
  int fd = -1;
  uint8_t pin_dir = 0;
  uint8_t pin_state = 0;

  void flushPins();
  void bulkOut(const uint8_t* buf, int len);
  void bulkIn(uint8_t* buf, int len);
};

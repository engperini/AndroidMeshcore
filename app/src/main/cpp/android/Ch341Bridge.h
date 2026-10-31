#pragma once

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
class Ch341Bridge {
public:
  Ch341Bridge() = default;
  ~Ch341Bridge();

  Ch341Bridge(const Ch341Bridge&) = delete;
  Ch341Bridge& operator=(const Ch341Bridge&) = delete;

  // Opens the first matching USB device. Throws std::runtime_error on failure.
  void open(uint16_t vid = 0x1A86, uint16_t pid = 0x5512);
  void close();

  // File descriptor from UsbDeviceConnection.getFileDescriptor(). The app
  // keeps ownership of the connection; close() only releases the interface.
  static void setAndroidFd(int fd);
  bool isOpen() const { return handle != nullptr; }

  void pinMode(int pin, bool output);
  void digitalWrite(int pin, bool high);
  bool digitalRead(int pin);

  // Full-duplex SPI transfer (mode 0, MSB first). Chip select is not touched.
  void spiTransfer(const uint8_t* tx, uint8_t* rx, size_t len);

private:
  void* handle = nullptr;
  int fd = -1;
  uint8_t pin_dir = 0;
  uint8_t pin_state = 0;

  void flushPins();
  void bulkOut(const uint8_t* buf, int len);
  void bulkIn(uint8_t* buf, int len);
};

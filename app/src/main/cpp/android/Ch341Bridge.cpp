#include "Ch341Bridge.h"

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstring>
#include <stdexcept>
#include <string>

namespace {
constexpr uint8_t EP_OUT = 0x02;
constexpr uint8_t EP_IN = 0x82;
constexpr unsigned int USB_TIMEOUT_MS = 1000;
constexpr size_t PACKET_LEN = 32;

constexpr uint8_t CMD_GET_INPUT = 0xA0;
constexpr uint8_t CMD_SPI_STREAM = 0xA8;
constexpr uint8_t CMD_UIO_STREAM = 0xAB;
constexpr uint8_t UIO_STM_OUT = 0x80;
constexpr uint8_t UIO_STM_DIR = 0x40;
constexpr uint8_t UIO_STM_END = 0x20;

constexpr int SCK_PIN = 3;
constexpr int MOSI_PIN = 5;

std::atomic<int> g_android_fd{-1};

// The CH341 shifts SPI data LSB first; SX126x expects MSB first.
uint8_t reverseBits(uint8_t x) {
  x = static_cast<uint8_t>(((x >> 1) & 0x55) | ((x << 1) & 0xAA));
  x = static_cast<uint8_t>(((x >> 2) & 0x33) | ((x << 2) & 0xCC));
  return static_cast<uint8_t>((x >> 4) | (x << 4));
}

std::string usbError(const char* what) {
  return std::string("CH341 ") + what + " failed: " + std::strerror(errno);
}

int bulk(int fd, uint8_t ep, uint8_t* buf, int len) {
  usbdevfs_bulktransfer xfer{};
  xfer.ep = ep;
  xfer.len = static_cast<unsigned int>(len);
  xfer.timeout = USB_TIMEOUT_MS;
  xfer.data = buf;
  return ioctl(fd, USBDEVFS_BULK, &xfer);
}
}

void Ch341Bridge::setAndroidFd(int fd) {
  g_android_fd.store(fd);
}

Ch341Bridge::~Ch341Bridge() {
  close();
}

void Ch341Bridge::open() {
  if (handle) return;
  fd = g_android_fd.load();
  if (fd < 0) {
    throw std::runtime_error("CH341: USB MeshStick (1a86:5512) not connected or permission not granted");
  }
  unsigned int iface = 0;
  // Detach any kernel driver (ch341 serial) from interface 0, then claim it.
  usbdevfs_ioctl cmd{};
  cmd.ifno = 0;
  cmd.ioctl_code = USBDEVFS_DISCONNECT;
  cmd.data = nullptr;
  ioctl(fd, USBDEVFS_IOCTL, &cmd);
  if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
    throw std::runtime_error(usbError("claim interface 0"));
  }
  handle = &fd;

  // SCK and MOSI must be driven as outputs for the SPI stream to reach the radio.
  pin_dir = static_cast<uint8_t>((1u << SCK_PIN) | (1u << MOSI_PIN));
  pin_state = 0;
  flushPins();
}

void Ch341Bridge::close() {
  if (handle) {
    unsigned int iface = 0;
    ioctl(fd, USBDEVFS_RELEASEINTERFACE, &iface);
    handle = nullptr;
    fd = -1;
  }
}

void Ch341Bridge::bulkOut(const uint8_t* buf, int len) {
  if (!handle) throw std::runtime_error("CH341 not open");
  const int rc = bulk(fd, EP_OUT, const_cast<uint8_t*>(buf), len);
  if (rc < 0) throw std::runtime_error(usbError("bulk OUT"));
  if (rc != len) throw std::runtime_error("CH341 short bulk OUT");
}

void Ch341Bridge::bulkIn(uint8_t* buf, int len) {
  if (!handle) throw std::runtime_error("CH341 not open");
  const int rc = bulk(fd, EP_IN, buf, len);
  if (rc < 0) throw std::runtime_error(usbError("bulk IN"));
  if (rc != len) throw std::runtime_error("CH341 short bulk IN");
}

void Ch341Bridge::flushPins() {
  // Only D0..D5 are writable through the UIO stream.
  const uint8_t cmd[4] = {
    CMD_UIO_STREAM,
    static_cast<uint8_t>(UIO_STM_OUT | (pin_state & 0x3F)),
    static_cast<uint8_t>(UIO_STM_DIR | (pin_dir & 0x3F)),
    UIO_STM_END,
  };
  bulkOut(cmd, sizeof(cmd));
}

void Ch341Bridge::pinMode(int pin, bool output) {
  if (pin < 0 || pin > 5) return;
  if (output) pin_dir = static_cast<uint8_t>(pin_dir | (1u << pin));
  else pin_dir = static_cast<uint8_t>(pin_dir & ~(1u << pin));
  flushPins();
}

void Ch341Bridge::digitalWrite(int pin, bool high) {
  if (pin < 0 || pin > 5) return;
  if (high) pin_state = static_cast<uint8_t>(pin_state | (1u << pin));
  else pin_state = static_cast<uint8_t>(pin_state & ~(1u << pin));
  flushPins();
}

bool Ch341Bridge::digitalRead(int pin) {
  if (pin < 0 || pin > 7) return false;
  const uint8_t cmd = CMD_GET_INPUT;
  bulkOut(&cmd, 1);
  uint8_t reply[6] = {0};
  bulkIn(reply, sizeof(reply));
  return (reply[0] & (1u << pin)) != 0;
}

void Ch341Bridge::spiTransfer(const uint8_t* tx, uint8_t* rx, size_t len) {
  uint8_t out[PACKET_LEN];
  uint8_t in[PACKET_LEN];
  for (size_t pos = 0; pos < len;) {
    const size_t n = std::min(PACKET_LEN - 1, len - pos);
    out[0] = CMD_SPI_STREAM;
    for (size_t i = 0; i < n; ++i) out[i + 1] = reverseBits(tx ? tx[pos + i] : 0x00);
    bulkOut(out, static_cast<int>(n + 1));
    bulkIn(in, static_cast<int>(n));
    if (rx) {
      for (size_t i = 0; i < n; ++i) rx[pos + i] = reverseBits(in[i]);
    }
    pos += n;
  }
}

#include "XiaoBridge.h"

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <thread>

namespace {

// Protocol v1, see docs/PROTOCOL.md in xiao-sx1262-usb-bridge.
constexpr uint8_t SYNC = 0xA5;
constexpr size_t HEADER_LEN = 5;
constexpr size_t MAX_PAYLOAD = 600;
constexpr uint8_t RESPONSE_FLAG = 0x80;
constexpr uint8_t FIRST_EVENT_TYPE = 0xC0;
constexpr uint8_t PROTOCOL_VERSION = 1;

constexpr uint8_t REQ_HELLO = 0x01;
constexpr uint8_t REQ_SPI_TRANSFER = 0x02;
constexpr uint8_t REQ_PIN_WRITE = 0x03;
constexpr uint8_t REQ_PIN_READ = 0x04;

constexpr uint8_t ST_OK = 0x00;
constexpr uint8_t ST_BUSY_TIMEOUT = 0x04;

constexpr uint8_t XFER_AUTO_CS = 0x01;
constexpr uint8_t XFER_WAIT_BUSY = 0x02;

constexpr uint8_t CAP_RXEN = 0x02;
constexpr uint8_t CAP_TXEN = 0x04;
constexpr uint8_t RADIO_FLAG_DIO2_RF_SWITCH = 0x01;

// One full-speed bulk packet per read: a read always completes when a packet
// arrives, without relying on short packets.
constexpr int READ_CHUNK = 64;

std::atomic<int> g_fd{-1};
std::atomic<int> g_ep_in{-1};
std::atomic<int> g_ep_out{-1};
std::atomic<int> g_iface{-1};

const char* statusName(uint8_t status) {
  switch (status) {
    case 0x00: return "OK";
    case 0x01: return "unknown command";
    case 0x02: return "bad length";
    case 0x03: return "bad pin";
    case 0x04: return "BUSY timeout";
    case 0x05: return "bad argument";
    default: return "unknown status";
  }
}

std::string requestError(uint8_t type, uint8_t status) {
  char buf[96];
  std::snprintf(buf, sizeof(buf), "XIAO bridge request 0x%02X failed: %s (0x%02X)", type, statusName(status), status);
  return buf;
}

}  // namespace

void XiaoBridge::setAndroidUsb(int fd, int ep_in, int ep_out, int data_iface) {
  g_fd.store(fd);
  g_ep_in.store(ep_in);
  g_ep_out.store(ep_out);
  g_iface.store(data_iface);
}

XiaoBridge::~XiaoBridge() {
  close();
}

void XiaoBridge::open() {
  if (is_open) return;
  fd = g_fd.load();
  const int in = g_ep_in.load();
  const int out = g_ep_out.load();
  const int ifc = g_iface.load();
  if (fd < 0 || in < 0 || out < 0 || ifc < 0) {
    throw std::runtime_error("XIAO bridge not connected or USB permission not granted");
  }
  ep_in = static_cast<uint8_t>(in);
  ep_out = static_cast<uint8_t>(out);
  iface = static_cast<unsigned int>(ifc);

  // The app has claimed the interface already; claiming it again from the
  // same file descriptor is harmless and makes this side self-contained.
  if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, &iface) < 0) {
    throw std::runtime_error(std::string("XIAO bridge: claim interface failed: ") + std::strerror(errno));
  }
  is_open = true;
  seq = 0;
  rx_buf.clear();

  try {
    hello();
  } catch (...) {
    close();
    throw;
  }
}

void XiaoBridge::close() {
  if (!is_open) return;
  ioctl(fd, USBDEVFS_RELEASEINTERFACE, &iface);
  is_open = false;
  fd = -1;
}

const char* XiaoBridge::troubleshooting() const {
  return "; check that the Wio-SX1262 board is seated on the XIAO and the antenna is connected";
}

// ------------------------------------------------------------------- USB

int XiaoBridge::bulkIn(uint8_t* buf, int len, unsigned int timeout_ms) {
  usbdevfs_bulktransfer x{};
  x.ep = ep_in;
  x.len = static_cast<unsigned int>(len);
  x.timeout = std::max(1u, timeout_ms);
  x.data = buf;
  const int rc = ioctl(fd, USBDEVFS_BULK, &x);
  if (rc < 0) {
    if (errno == ETIMEDOUT) return 0;
    throw std::runtime_error(std::string("XIAO bridge: USB read failed: ") + std::strerror(errno));
  }
  return rc;
}

void XiaoBridge::bulkOut(const uint8_t* buf, int len) {
  usbdevfs_bulktransfer x{};
  x.ep = ep_out;
  x.len = static_cast<unsigned int>(len);
  x.timeout = 1000;
  x.data = const_cast<uint8_t*>(buf);
  const int rc = ioctl(fd, USBDEVFS_BULK, &x);
  if (rc < 0) throw std::runtime_error(std::string("XIAO bridge: USB write failed: ") + std::strerror(errno));
  if (rc != len) throw std::runtime_error("XIAO bridge: short USB write");
}

void XiaoBridge::drainInput() {
  uint8_t buf[READ_CHUNK];
  for (int i = 0; i < 64; ++i) {
    if (bulkIn(buf, sizeof(buf), 20) == 0) break;
  }
  rx_buf.clear();
}

uint8_t XiaoBridge::request(uint8_t type, const uint8_t* payload, size_t len, int timeout_ms) {
  if (!is_open) throw std::runtime_error("XIAO bridge not open");
  if (len > MAX_PAYLOAD) throw std::runtime_error("XIAO bridge: request too large");

  seq = static_cast<uint8_t>(seq + 1);
  frame.resize(HEADER_LEN + len);
  frame[0] = SYNC;
  frame[1] = type;
  frame[2] = seq;
  frame[3] = static_cast<uint8_t>(len & 0xFF);
  frame[4] = static_cast<uint8_t>(len >> 8);
  if (len > 0) std::memcpy(&frame[HEADER_LEN], payload, len);
  bulkOut(frame.data(), static_cast<int>(frame.size()));

  using clock = std::chrono::steady_clock;
  const auto deadline = clock::now() + std::chrono::milliseconds(timeout_ms);
  uint8_t chunk[READ_CHUNK];

  while (true) {
    // Parse whatever is buffered.
    while (true) {
      auto start = std::find(rx_buf.begin(), rx_buf.end(), SYNC);
      rx_buf.erase(rx_buf.begin(), start);
      if (rx_buf.size() < HEADER_LEN) break;
      const size_t plen = static_cast<size_t>(rx_buf[3]) | (static_cast<size_t>(rx_buf[4]) << 8);
      if (plen > MAX_PAYLOAD + 1) {
        rx_buf.erase(rx_buf.begin());  // not a real header; resync
        continue;
      }
      if (rx_buf.size() < HEADER_LEN + plen) break;

      const uint8_t ftype = rx_buf[1];
      const uint8_t fseq = rx_buf[2];
      const bool ours = ftype == (type | RESPONSE_FLAG) && fseq == seq && plen >= 1;
      uint8_t status = 0;
      if (ours) {
        status = rx_buf[HEADER_LEN];
        reply.assign(rx_buf.begin() + HEADER_LEN + 1, rx_buf.begin() + HEADER_LEN + plen);
      }
      // Events (DIO1) and stale responses are dropped.
      rx_buf.erase(rx_buf.begin(), rx_buf.begin() + HEADER_LEN + plen);
      if (ours) return status;
    }

    const auto now = clock::now();
    if (now >= deadline) {
      char buf[80];
      std::snprintf(buf, sizeof(buf), "XIAO bridge did not answer request 0x%02X", type);
      throw std::runtime_error(buf);
    }
    const auto left = std::chrono::duration_cast<std::chrono::milliseconds>(deadline - now).count();
    const int n = bulkIn(chunk, sizeof(chunk), static_cast<unsigned int>(left));
    if (n > 0) rx_buf.insert(rx_buf.end(), chunk, chunk + n);
  }
}

void XiaoBridge::requestOk(uint8_t type, const uint8_t* payload, size_t len, int timeout_ms) {
  const uint8_t status = request(type, payload, len, timeout_ms);
  if (status != ST_OK) throw std::runtime_error(requestError(type, status));
}

void XiaoBridge::hello() {
  // A host that went away mid-frame may have left bytes in either direction;
  // the bridge drops partial frames after 200 ms, so retry a few times.
  std::string last_error;
  for (int attempt = 0; attempt < 3; ++attempt) {
    try {
      drainInput();
      requestOk(REQ_HELLO, nullptr, 0, 400);
      last_error.clear();
      break;
    } catch (const std::exception& ex) {
      last_error = ex.what();
    }
  }
  if (!last_error.empty()) {
    throw std::runtime_error(
        "XIAO found, but it did not answer as a bridge (" + last_error +
        "). Flash the xiao-sx1262-usb-bridge firmware: github.com/engperini/xiao-sx1262-usb-bridge");
  }

  if (reply.size() < 12) throw std::runtime_error("XIAO bridge: short HELLO reply");
  if (reply[0] != PROTOCOL_VERSION) {
    throw std::runtime_error("XIAO bridge speaks protocol v" + std::to_string(reply[0]) +
                             "; this app needs v" + std::to_string(PROTOCOL_VERSION) +
                             ". Update the app or the bridge firmware");
  }
  const uint8_t caps = reply[5];
  const uint8_t radio_flags = reply[6];
  radio_hints.valid = true;
  radio_hints.tcxo_mv = reply[7] | (reply[8] << 8);
  radio_hints.dio2_rf_switch = (radio_flags & RADIO_FLAG_DIO2_RF_SWITCH) != 0;
  radio_hints.has_rxen = (caps & CAP_RXEN) != 0;
  radio_hints.has_txen = (caps & CAP_TXEN) != 0;
  max_transfer = static_cast<size_t>(reply[9] | (reply[10] << 8));
  if (max_transfer == 0 || max_transfer > MAX_PAYLOAD - 1) max_transfer = MAX_PAYLOAD - 1;
}

// ------------------------------------------------------------- bus calls

void XiaoBridge::pinMode(int pin, bool output) {
  // Pin directions are fixed by the bridge firmware.
  (void)pin;
  (void)output;
}

void XiaoBridge::digitalWrite(int pin, bool high) {
  if (pin < 0) return;
  const uint8_t p[2] = {static_cast<uint8_t>(pin), static_cast<uint8_t>(high ? 1 : 0)};
  requestOk(REQ_PIN_WRITE, p, sizeof(p));
}

bool XiaoBridge::digitalRead(int pin) {
  if (pin < 0) return false;
  const uint8_t p = static_cast<uint8_t>(pin);
  requestOk(REQ_PIN_READ, &p, 1);
  return !reply.empty() && reply[0] != 0;
}

void XiaoBridge::xfer(uint8_t flags, const uint8_t* tx, uint8_t* rx, size_t len) {
  std::vector<uint8_t> payload(1 + len, 0);
  payload[0] = flags;
  if (tx && len > 0) std::memcpy(&payload[1], tx, len);

  uint8_t status = request(REQ_SPI_TRANSFER, payload.data(), payload.size(), 500);
  if (status == ST_BUSY_TIMEOUT && (flags & XFER_WAIT_BUSY)) {
    // Same as the CH341 path, which gives up waiting and sends anyway.
    payload[0] = static_cast<uint8_t>(flags & ~XFER_WAIT_BUSY);
    status = request(REQ_SPI_TRANSFER, payload.data(), payload.size(), 500);
  }
  if (status != ST_OK) throw std::runtime_error(requestError(REQ_SPI_TRANSFER, status));
  if (reply.size() != len) throw std::runtime_error("XIAO bridge: SPI reply has the wrong length");
  if (rx && len > 0) std::memcpy(rx, reply.data(), len);
}

void XiaoBridge::spiTransfer(const uint8_t* tx, uint8_t* rx, size_t len) {
  for (size_t pos = 0; pos < len;) {
    const size_t n = std::min(max_transfer, len - pos);
    xfer(0, tx ? tx + pos : nullptr, rx ? rx + pos : nullptr, n);
    pos += n;
  }
}

void XiaoBridge::spiCommand(int cs_pin, const uint8_t* tx, uint8_t* rx, size_t len) {
  if (len == 0) return;
  if (cs_pin != PIN_NSS || len > max_transfer) {
    // Not the bridge's own chip select, or too long for one request. The
    // radio driver skips its own BUSY wait for this bus, so do it here.
    for (int i = 0; i < 200 && digitalRead(PIN_BUSY); ++i) {
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    RadioUsbBus::spiCommand(cs_pin, tx, rx, len);
    return;
  }
  // Chip select, BUSY wait and transfer in one USB round trip.
  xfer(XFER_AUTO_CS | XFER_WAIT_BUSY, tx, rx, len);
}

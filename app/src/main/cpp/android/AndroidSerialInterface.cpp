#include "AndroidSerialInterface.h"

#include <cstring>

void AndroidSerialInterface::pushInbound(const uint8_t* data, size_t len) {
  if (len == 0 || len > MAX_FRAME_SIZE) return;
  std::lock_guard<std::mutex> lock(mutex);
  inbound.emplace_back(data, data + len);
}

size_t AndroidSerialInterface::writeFrame(const uint8_t src[], size_t len) {
  if (!enabled || !sink) return 0;
  sink(src, len);
  return len;
}

size_t AndroidSerialInterface::checkRecvFrame(uint8_t dest[]) {
  std::lock_guard<std::mutex> lock(mutex);
  if (inbound.empty()) return 0;
  const auto frame = std::move(inbound.front());
  inbound.pop_front();
  std::memcpy(dest, frame.data(), frame.size());
  return frame.size();
}

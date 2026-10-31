#pragma once

#include <helpers/BaseSerialInterface.h>

#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <vector>

// In-process companion "serial" link: the Android UI is the companion app.
// Frames from the app are queued by the JNI thread and consumed by the mesh
// loop; frames from the mesh are handed straight to the sink callback.
class AndroidSerialInterface : public BaseSerialInterface {
public:
  using FrameSink = std::function<void(const uint8_t*, size_t)>;

  void setSink(FrameSink sink) { this->sink = std::move(sink); }
  void pushInbound(const uint8_t* data, size_t len);

  void enable() override { enabled = true; }
  void disable() override { enabled = false; }
  bool isEnabled() const override { return enabled; }

  bool isConnected() const override { return enabled; }
  bool isWriteBusy() const override { return false; }
  size_t writeFrame(const uint8_t src[], size_t len) override;
  size_t checkRecvFrame(uint8_t dest[]) override;

private:
  bool enabled = false;
  FrameSink sink;
  std::mutex mutex;
  std::deque<std::vector<uint8_t>> inbound;
};

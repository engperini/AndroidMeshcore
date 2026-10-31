#pragma once
// Host test target: same API as app/src/main/cpp/android/include/target.h, but
// the "radio" is a UDP broadcast on localhost so several node processes form a mesh.

#include <MeshCore.h>
#include <helpers/ArduinoHelpers.h>
#include <helpers/SensorManager.h>
#include "LittleFS.h"

#include <ctime>

class LinuxBoard : public mesh::MainBoard {
public:
  uint16_t getBattMilliVolts() override { return 4100; }
  const char* getManufacturerName() const override { return "HostTest"; }
  void reboot() override {}
  uint8_t getStartupReason() const override { return BD_STARTUP_NORMAL; }
};

class LinuxRTCClock : public mesh::RTCClock {
  int64_t offset = 0;
public:
  uint32_t getCurrentTime() override { return static_cast<uint32_t>(std::time(nullptr) + offset); }
  void setCurrentTime(uint32_t t) override { offset = static_cast<int64_t>(t) - std::time(nullptr); }
};

class UdpRadio : public mesh::Radio {
  int fd = -1;
  int my_port = 0;
  int base_port = 0;
  int num_nodes = 0;
  uint32_t recv_count = 0, sent_count = 0;
public:
  void setup(int base, int index, int nodes);
  int recvRaw(uint8_t* bytes, int sz) override;
  uint32_t getEstAirtimeFor(int len_bytes) override { return 50 + len_bytes; }
  float packetScore(float snr, int packet_len) override { return 1.0f; }
  bool startSendRaw(const uint8_t* bytes, int len) override;
  bool isSendComplete() override { return true; }
  void onSendFinished() override {}
  bool isInRecvMode() const override { return true; }
  float getLastRSSI() const override { return -60.0f; }
  float getLastSNR() const override { return 9.5f; }
  uint32_t getPacketsRecv() const { return recv_count; }
  uint32_t getPacketsSent() const { return sent_count; }
  uint32_t getPacketsRecvErrors() const { return 0; }
  void resetStats() { recv_count = sent_count = 0; }
};

extern LinuxBoard board;
extern UdpRadio radio_driver;
extern LinuxRTCClock rtc_clock;
extern SensorManager sensors;

void radio_set_params(float freq, float bw, uint8_t sf, uint8_t cr);
void radio_set_tx_power(int8_t dbm);
mesh::LocalIdentity radio_new_identity();

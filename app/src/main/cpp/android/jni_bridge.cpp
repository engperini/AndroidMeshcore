// JNI entry points: runs the MeshCore companion firmware (MyMesh) on a native
// thread, with a USB radio (MeshStick or XIAO bridge) and the Android UI as
// companion app.

#include "AndroidSerialInterface.h"
#include "Ch341Bridge.h"
#include "XiaoBridge.h"
#include "target.h"

#include <helpers/ArduinoHelpers.h>
#include <helpers/SimpleMeshTables.h>

#include "DataStore.h"
#include "MyMesh.h"

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

StdRNG fast_rng;
SimpleMeshTables tables;
DataStore store(LittleFS, rtc_clock);
MyMesh the_mesh(radio_driver, fast_rng, rtc_clock, tables, store, nullptr);

namespace {

constexpr const char* TAG = "MeshCoreNative";

enum Status { STATUS_STOPPED = 0, STATUS_STARTING = 1, STATUS_RUNNING = 2, STATUS_ERROR = 3 };

// Must match NativeMesh.RADIO_* in Kotlin.
enum RadioKind { RADIO_MESHSTICK = 0, RADIO_XIAO = 1 };
std::atomic<int> g_radio_kind{RADIO_MESHSTICK};

// Must match NativeMesh.MODE_* in Kotlin. One protocol runs on the radio at a time.
enum Mode { MODE_MESHCORE = 0, MODE_LORA = 1 };
std::atomic<int> g_mode{MODE_MESHCORE};

// Generic LoRa mode: radio parameters, and packets waiting to be sent.
struct LoraParams {
  int freq_hz = 915000000;
  int bw_hz = 125000;
  int sf = 7;
  int cr = 5;
  int sync_word = 0x1424;   // SX126x register value (0x12 private)
  int tx_dbm = 14;
  int preamble = 8;
};
std::mutex g_lora_mutex;
LoraParams g_lora;
std::atomic<bool> g_lora_dirty{false};
std::deque<std::vector<uint8_t>> g_lora_tx;

JavaVM* g_vm = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_on_frame = nullptr;
jmethodID g_on_status = nullptr;
jmethodID g_on_raw_packet = nullptr;
jmethodID g_on_raw_sent = nullptr;

AndroidSerialInterface g_serial;
std::thread g_thread;
std::mutex g_thread_mutex;
std::atomic<bool> g_stop{false};
bool g_mesh_started = false;

std::atomic<int> g_advert_interval_min{0};
std::atomic<bool> g_advert_flood{true};
std::atomic<bool> g_diag_requested{false};

std::mutex g_diag_mutex;
std::string g_diag = "{}";

JNIEnv* g_loop_env = nullptr;

void notifyStatus(int status, const std::string& message) {
  __android_log_print(status == STATUS_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, TAG, "status %d: %s", status,
                      message.c_str());
  if (!g_loop_env || !g_bridge_class) return;
  jstring msg = g_loop_env->NewStringUTF(message.c_str());
  g_loop_env->CallStaticVoidMethod(g_bridge_class, g_on_status, static_cast<jint>(status), msg);
  g_loop_env->DeleteLocalRef(msg);
  if (g_loop_env->ExceptionCheck()) g_loop_env->ExceptionClear();
}

void deliverRawPacket(const uint8_t* data, int len, float rssi, float snr) {
  if (!g_loop_env || !g_bridge_class || !g_on_raw_packet) return;
  jbyteArray arr = g_loop_env->NewByteArray(len);
  g_loop_env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
  g_loop_env->CallStaticVoidMethod(g_bridge_class, g_on_raw_packet, arr, static_cast<jfloat>(rssi), static_cast<jfloat>(snr));
  g_loop_env->DeleteLocalRef(arr);
  if (g_loop_env->ExceptionCheck()) g_loop_env->ExceptionClear();
}

void deliverRawSent(bool ok) {
  if (!g_loop_env || !g_bridge_class || !g_on_raw_sent) return;
  g_loop_env->CallStaticVoidMethod(g_bridge_class, g_on_raw_sent, static_cast<jboolean>(ok ? JNI_TRUE : JNI_FALSE));
  if (g_loop_env->ExceptionCheck()) g_loop_env->ExceptionClear();
}

void deliverFrame(const uint8_t* data, size_t len) {
  if (!g_loop_env || !g_bridge_class) return;
  jbyteArray arr = g_loop_env->NewByteArray(static_cast<jsize>(len));
  g_loop_env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte*>(data));
  g_loop_env->CallStaticVoidMethod(g_bridge_class, g_on_frame, arr);
  g_loop_env->DeleteLocalRef(arr);
  if (g_loop_env->ExceptionCheck()) g_loop_env->ExceptionClear();
}

// Same snapshot as the Raspberry Pi `radio-diag:` startup line, plus counters.
void updateDiag() {
  const auto cfg = radio_driver.getConfig();
  const uint8_t st = radio_driver.debugGetStatus();
  const uint16_t irq = radio_driver.debugGetIrqStatus();
  const uint16_t errs = radio_driver.debugGetDeviceErrors();

  char diag[700]{};
  std::snprintf(diag, sizeof(diag),
                "{\"driver\":\"%s\",\"status\":%u,\"irq_hex\":\"0x%04X\",\"dev_errors_hex\":\"0x%04X\","
                "\"freq\":%d,\"bw\":%d,\"sf\":%d,\"cr\":%d,\"tx\":%d,\"pre\":%d,\"sync\":\"0x%04X\","
                "\"cs_pin\":%d,\"reset_pin\":%d,\"busy_pin\":%d,\"irq_pin\":%d,"
                "\"dio2_rf\":%d,\"tcxo\":%d,"
                "\"last_rssi\":%.1f,\"last_snr\":%.2f,\"noise_floor\":%d,"
                "\"packets_recv\":%u,\"packets_sent\":%u,\"recv_errors\":%u}",
                radio_driver.getDriverName(), st, irq, errs,
                cfg.frequency_hz, cfg.bandwidth_hz, cfg.spreading_factor, cfg.coding_rate, cfg.tx_power_dbm,
                cfg.preamble_len, cfg.sync_word,
                cfg.cs_pin, cfg.reset_pin, cfg.busy_pin, cfg.irq_pin,
                cfg.use_dio2_rf_switch ? 1 : 0, cfg.use_dio3_tcxo ? 1 : 0,
                radio_driver.getLastRSSI(), radio_driver.getLastSNR(), radio_driver.getNoiseFloor(),
                radio_driver.getPacketsRecv(), radio_driver.getPacketsSent(), radio_driver.getPacketsRecvErrors());
  std::lock_guard<std::mutex> lock(g_diag_mutex);
  g_diag = diag;
}

// CH341 profile (USB MeshStick, and the no-MCU USB LoRa stick in hardware/): pins D0/D2/D4/D6,
// DIO3 TCXO and DIO2 RF switch. D1 is driven as RXEN (high while receiving): the MeshStick leaves it
// unconnected, and the USB LoRa stick wires it to the Wio-SX1262's RF_SW.
// XIAO profile: bridge logical pins; TCXO, DIO2 and RXEN come from the bridge.
// Selecting a driver also drops the old radio backend, which releases USB.
void configureRadio() {
  if (g_radio_kind.load() == RADIO_XIAO) {
    radio_set_driver("sx1262_xiao");
    radio_set_hw_config("", 0, 0, 0, XiaoBridge::PIN_NSS, XiaoBridge::PIN_RESET, XiaoBridge::PIN_BUSY,
                        XiaoBridge::PIN_DIO1, XiaoBridge::PIN_TXEN, XiaoBridge::PIN_RXEN, true, true);
  } else {
    radio_set_driver("sx1262_ch341");
    radio_set_hw_config("", 0, 0, 0, -1, -1, -1, -1, -1, /* rxen = D1 */ 1, true, true);
  }
}

// MeshCore mode: the companion node. Returns when stopped or when the mode changes; the node's state
// (identity, contacts, channels, queues) stays in memory for the next time.
void runMeshCore(const std::string& data_dir) {
  if (!g_mesh_started) {
    LittleFS.setRoot(data_dir);
    configureRadio();
    radio_init();

    fast_rng.begin(static_cast<long>(radio_get_rng_seed()));
    the_mesh.begin(false);
    g_serial.setSink(deliverFrame);
    the_mesh.startInterface(g_serial);
    g_mesh_started = true;
  } else {
    // Mesh state survives in memory; only the (re-plugged) radio is reopened,
    // and it may be a different kind of radio than last time, or come back from another mode.
    configureRadio();
    radio_driver.begin();
  }

  updateDiag();
  notifyStatus(STATUS_RUNNING, "Radio running");

  auto next_advert = std::chrono::steady_clock::now();
  int advert_interval = -1;
  auto next_diag = std::chrono::steady_clock::now() + std::chrono::seconds(15);

  while (!g_stop.load() && g_mode.load() == MODE_MESHCORE) {
    const auto now = std::chrono::steady_clock::now();

    const int interval = g_advert_interval_min.load();
    if (interval != advert_interval) {
      advert_interval = interval;
      next_advert = now + std::chrono::minutes(interval);
    }
    if (advert_interval > 0 && now >= next_advert) {
      next_advert += std::chrono::minutes(advert_interval);
      if (!the_mesh.sendSelfAdvert(g_advert_flood.load())) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "auto-advert: packet pool full, skipped");
      }
    }

    if (g_diag_requested.exchange(false) || now >= next_diag) {
      next_diag = now + std::chrono::seconds(15);
      updateDiag();
    }

    if (board_take_reboot_request()) {
      radio_driver.begin();
    }

    the_mesh.loop();
    rtc_clock.tick();
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
}

void applyLoraParams() {
  LoraParams p;
  {
    std::lock_guard<std::mutex> lock(g_lora_mutex);
    p = g_lora;
  }
  // Same radio, other parameters. The MeshCore parameters are kept apart (g_cfg) and come back with
  // configureRadio() when MeshCore runs again.
  LinuxRadioConfig cfg = radio_driver.getConfig();
  cfg.frequency_hz = p.freq_hz;
  cfg.bandwidth_hz = p.bw_hz;
  cfg.spreading_factor = p.sf;
  cfg.coding_rate = p.cr;
  cfg.sync_word = static_cast<uint16_t>(p.sync_word);
  cfg.tx_power_dbm = p.tx_dbm;
  cfg.preamble_len = p.preamble;
  radio_driver.setConfig(cfg);
}

// Generic LoRa mode: every packet heard with the configured parameters goes to the app as raw bytes,
// and the app can send raw packets.
void runLora() {
  configureRadio();
  g_lora_dirty.store(false);
  {
    std::lock_guard<std::mutex> lock(g_lora_mutex);
    g_lora_tx.clear();   // nothing queued while another mode was running goes out now
  }
  applyLoraParams();
  radio_driver.begin();
  updateDiag();
  notifyStatus(STATUS_RUNNING, "LoRa mode");

  bool sending = false;
  auto send_deadline = std::chrono::steady_clock::now();
  auto next_diag = std::chrono::steady_clock::now() + std::chrono::seconds(15);
  uint8_t buf[256];

  while (!g_stop.load() && g_mode.load() == MODE_LORA) {
    const auto now = std::chrono::steady_clock::now();

    if (!sending && g_lora_dirty.exchange(false)) {
      applyLoraParams();   // never in the middle of a transmission
    }

    if (sending) {
      if (radio_driver.isSendComplete()) {
        radio_driver.onSendFinished();
        sending = false;
        deliverRawSent(true);
      } else if (now >= send_deadline) {
        radio_driver.onSendFinished();
        sending = false;
        deliverRawSent(false);
      }
    } else {
      std::vector<uint8_t> next;
      {
        std::lock_guard<std::mutex> lock(g_lora_mutex);
        if (!g_lora_tx.empty()) {
          next = std::move(g_lora_tx.front());
          g_lora_tx.pop_front();
        }
      }
      if (!next.empty()) {
        const int len = static_cast<int>(next.size());
        const uint32_t airtime = radio_driver.getEstAirtimeFor(len);
        send_deadline = now + std::chrono::milliseconds(2 * airtime + 1000);
        sending = radio_driver.startSendRaw(next.data(), len);
        if (!sending) deliverRawSent(false);
      } else {
        const int n = radio_driver.recvRaw(buf, sizeof(buf));
        if (n > 0) deliverRawPacket(buf, n, radio_driver.getLastRSSI(), radio_driver.getLastSNR());
        radio_driver.loop();   // noise floor
      }
    }

    if (g_diag_requested.exchange(false) || now >= next_diag) {
      next_diag = now + std::chrono::seconds(15);
      updateDiag();
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(2));
  }
}

void runLoop(std::string data_dir) {
  g_vm->AttachCurrentThread(&g_loop_env, nullptr);
  notifyStatus(STATUS_STARTING, "Starting radio");

  try {
    board.begin();
    while (!g_stop.load()) {
      if (g_mode.load() == MODE_LORA) {
        runLora();
      } else {
        runMeshCore(data_dir);
      }
    }
    notifyStatus(STATUS_STOPPED, "Stopped");
  } catch (const std::exception& ex) {
    notifyStatus(STATUS_ERROR, ex.what());
  }

  // Drop the radio backend so the USB interface is released for the next start.
  configureRadio();
  Ch341Bridge::setAndroidFd(-1);
  XiaoBridge::setAndroidUsb(-1, -1, -1, -1);

  g_loop_env = nullptr;
  g_vm->DetachCurrentThread();
}

std::string toStdString(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string out(chars ? chars : "");
  if (chars) env->ReleaseStringUTFChars(s, chars);
  return out;
}

void joinLoop() {
  g_stop.store(true);
  if (g_thread.joinable()) g_thread.join();
}

}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = env->FindClass("com/engperini/meshcore/core/NativeMesh");
  if (!cls) return JNI_ERR;
  g_bridge_class = static_cast<jclass>(env->NewGlobalRef(cls));
  g_on_frame = env->GetStaticMethodID(cls, "onFrame", "([B)V");
  g_on_status = env->GetStaticMethodID(cls, "onStatus", "(ILjava/lang/String;)V");
  g_on_raw_packet = env->GetStaticMethodID(cls, "onRawPacket", "([BFF)V");
  g_on_raw_sent = env->GetStaticMethodID(cls, "onRawSent", "(Z)V");
  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeStart(JNIEnv* env, jclass, jstring data_dir,
                                                                             jint usb_fd, jint radio_kind,
                                                                             jint ep_in, jint ep_out,
                                                                             jint data_iface) {
  std::lock_guard<std::mutex> lock(g_thread_mutex);
  joinLoop();
  g_stop.store(false);
  g_radio_kind.store(radio_kind == RADIO_XIAO ? RADIO_XIAO : RADIO_MESHSTICK);
  if (radio_kind == RADIO_XIAO) {
    Ch341Bridge::setAndroidFd(-1);
    XiaoBridge::setAndroidUsb(usb_fd, ep_in, ep_out, data_iface);
  } else {
    XiaoBridge::setAndroidUsb(-1, -1, -1, -1);
    Ch341Bridge::setAndroidFd(usb_fd);
  }
  g_thread = std::thread(runLoop, toStdString(env, data_dir));
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeStop(JNIEnv*, jclass) {
  std::lock_guard<std::mutex> lock(g_thread_mutex);
  joinLoop();
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeSendFrame(JNIEnv* env, jclass,
                                                                                 jbyteArray frame) {
  const jsize len = env->GetArrayLength(frame);
  if (len <= 0 || len > MAX_FRAME_SIZE) return;
  uint8_t buf[MAX_FRAME_SIZE];
  env->GetByteArrayRegion(frame, 0, len, reinterpret_cast<jbyte*>(buf));
  g_serial.pushInbound(buf, static_cast<size_t>(len));
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeSetBattery(JNIEnv*, jclass, jint millivolts,
                                                                                  jboolean charging) {
  board_set_battery(static_cast<uint16_t>(millivolts), charging == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeSetAutoAdvert(JNIEnv*, jclass, jint minutes,
                                                                                     jboolean flood) {
  g_advert_flood.store(flood == JNI_TRUE);
  g_advert_interval_min.store(minutes < 0 ? 0 : minutes);
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeSetMode(JNIEnv*, jclass, jint mode) {
  g_mode.store(mode == MODE_LORA ? MODE_LORA : MODE_MESHCORE);
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeSetLoraConfig(JNIEnv*, jclass, jint freq_hz,
                                                                                     jint bw_hz, jint sf, jint cr,
                                                                                     jint sync_word, jint tx_dbm,
                                                                                     jint preamble) {
  {
    std::lock_guard<std::mutex> lock(g_lora_mutex);
    g_lora.freq_hz = freq_hz;
    g_lora.bw_hz = bw_hz;
    g_lora.sf = sf;
    g_lora.cr = cr;
    g_lora.sync_word = sync_word;
    g_lora.tx_dbm = tx_dbm;
    g_lora.preamble = preamble;
  }
  g_lora_dirty.store(true);
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeSendRaw(JNIEnv* env, jclass, jbyteArray data) {
  const jsize len = env->GetArrayLength(data);
  if (len <= 0 || len > 255) return;
  std::vector<uint8_t> buf(static_cast<size_t>(len));
  env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
  std::lock_guard<std::mutex> lock(g_lora_mutex);
  if (g_lora_tx.size() < 16) g_lora_tx.push_back(std::move(buf));
}

JNIEXPORT jstring JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeRadioDiag(JNIEnv* env, jclass) {
  g_diag_requested.store(true);
  std::lock_guard<std::mutex> lock(g_diag_mutex);
  return env->NewStringUTF(g_diag.c_str());
}

}

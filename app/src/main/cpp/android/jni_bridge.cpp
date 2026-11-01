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
#include <mutex>
#include <string>
#include <thread>

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

JavaVM* g_vm = nullptr;
jclass g_bridge_class = nullptr;
jmethodID g_on_frame = nullptr;
jmethodID g_on_status = nullptr;

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

// MeshStick profile: CH341 + SX1262, pins D0/D2/D4/D6, DIO3 TCXO and DIO2 RF switch.
// XIAO profile: bridge logical pins; TCXO, DIO2 and RXEN come from the bridge.
// Selecting a driver also drops the old radio backend, which releases USB.
void configureRadio() {
  if (g_radio_kind.load() == RADIO_XIAO) {
    radio_set_driver("sx1262_xiao");
    radio_set_hw_config("", 0, 0, 0, XiaoBridge::PIN_NSS, XiaoBridge::PIN_RESET, XiaoBridge::PIN_BUSY,
                        XiaoBridge::PIN_DIO1, XiaoBridge::PIN_TXEN, XiaoBridge::PIN_RXEN, true, true);
  } else {
    radio_set_driver("sx1262_ch341");
    radio_set_hw_config("", 0, 0, 0, -1, -1, -1, -1, -1, -1, true, true);
  }
}

void runLoop(std::string data_dir) {
  g_vm->AttachCurrentThread(&g_loop_env, nullptr);
  notifyStatus(STATUS_STARTING, "Starting radio");

  try {
    if (!g_mesh_started) {
      LittleFS.setRoot(data_dir);
      board.begin();
      configureRadio();
      radio_init();

      fast_rng.begin(static_cast<long>(radio_get_rng_seed()));
      the_mesh.begin(false);
      g_serial.setSink(deliverFrame);
      the_mesh.startInterface(g_serial);
      g_mesh_started = true;
    } else {
      // Mesh state survives in memory; only the (re-plugged) radio is reopened,
      // and it may be a different kind of radio than last time.
      configureRadio();
      radio_driver.begin();
    }

    updateDiag();
    notifyStatus(STATUS_RUNNING, "Radio running");

    auto next_advert = std::chrono::steady_clock::now();
    int advert_interval = -1;
    auto next_diag = std::chrono::steady_clock::now() + std::chrono::seconds(15);

    while (!g_stop.load()) {
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

JNIEXPORT jstring JNICALL Java_com_engperini_meshcore_core_NativeMesh_nativeRadioDiag(JNIEnv* env, jclass) {
  g_diag_requested.store(true);
  std::lock_guard<std::mutex> lock(g_diag_mutex);
  return env->NewStringUTF(g_diag.c_str());
}

}

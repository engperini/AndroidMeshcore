// JNI of the Meshtastic engine (libmeshtastic.so). It is loaded only in the app's ":meshtastic" process
// (MtEngineService): the firmware is a set of process-wide singletons that boot once, so a node reboot (or
// leaving Meshtastic mode) ends that process and the next start begins a fresh one.
//
// One radio thread runs the firmware: portduinoSetup() + setup() once, then loop() forever. The app's
// requests are queued and run on that thread between loop() calls; the node's FromRadio stream comes back
// as JSON events (mt_api.cpp) through MtNative.onEvent.

#include "Ch341Bridge.h"
#include "XiaoBridge.h"
#include "mt_api.h"
#include "mt_bus.h"
#include "mt_glue.h"

#include "RadioLibInterface.h"

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <cstdlib>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <random>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char* TAG = "Meshtastic";

// Must match MtNative.RADIO_* in Kotlin (same values as NativeMesh.RADIO_*).
enum RadioKind { RADIO_MESHSTICK = 0, RADIO_XIAO = 1 };

JavaVM* g_vm = nullptr;
jclass g_cls = nullptr;
jmethodID g_on_event = nullptr;
JNIEnv* g_env = nullptr;   // radio thread

std::mutex g_mutex;
std::deque<std::function<void()>> g_queue;
std::atomic<bool> g_started{false};
std::unique_ptr<RadioUsbBus> g_bus;
std::mt19937 g_rng{std::random_device{}()};
std::mutex g_rng_mutex;

void emit(const std::string& json)
{
    if (json.empty() || !g_env) return;
    jstring s = g_env->NewStringUTF(json.c_str());
    if (!s) {
        g_env->ExceptionClear();
        return;
    }
    g_env->CallStaticVoidMethod(g_cls, g_on_event, s);
    g_env->DeleteLocalRef(s);
    if (g_env->ExceptionCheck()) g_env->ExceptionClear();
}

std::string jsonString(const std::string& v)
{
    std::string s = "\"";
    for (char c : v) {
        if (c == '"' || c == '\\') s += '\\';
        if (static_cast<unsigned char>(c) < 0x20) c = ' ';
        s += c;
    }
    return s + "\"";
}

void status(const char* state, const std::string& message, bool radio)
{
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s: %s", state, message.c_str());
    emit(std::string("{\"t\":\"status\",\"state\":\"") + state + "\",\"radio\":" + (radio ? "true" : "false") +
         ",\"message\":" + jsonString(message) + "}");
}

void post(std::function<void()> f)
{
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_queue.size() > 64) return;
        g_queue.push_back(std::move(f));
    }
    mt_wake();
}

void drainRequests()
{
    for (;;) {
        std::function<void()> f;
        {
            std::lock_guard<std::mutex> lock(g_mutex);
            if (g_queue.empty()) return;
            f = std::move(g_queue.front());
            g_queue.pop_front();
        }
        f();
    }
}

void drainEvents()
{
    static uint8_t buf[1024];
    size_t n;
    while ((n = mt_api_from_radio(buf, sizeof(buf))) > 0) emit(mt_event_json(buf, n));
}

void run(std::string data_dir, bool xiao)
{
    g_vm->AttachCurrentThread(&g_env, nullptr);
    status("starting", "Starting Meshtastic", false);

    // Probe the USB radio first, so a missing or wrong device is a clear error instead of a node without radio.
    try {
        g_bus->open();
        g_bus->close();
    } catch (const std::exception& e) {
        status("error", e.what(), false);
        return;   // the app ends this process
    }
    mt_set_bus(g_bus.get(), xiao);

    mt_set_data_root(data_dir);
    mt_fs_mount();
    portduinoSetup();
    setup();
    const bool radio = RadioLibInterface::instance != nullptr;
    status("running", radio ? "Meshtastic running" : "The radio did not answer", radio);

    for (;;) {
        drainRequests();
        loop();
        drainEvents();
        if (mt_take_reboot_request()) {
            drainEvents();
            emit("{\"t\":\"reboot\"}");
            // The node saved its state before asking; the app ends this process and starts a new one.
            for (;;) std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
}

std::string str(JNIEnv* env, jstring s)
{
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

bool mt_use_simradio()
{
    return false;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = env->FindClass("com/engperini/meshcore/mt/MtNative");
    if (!cls) return JNI_ERR;
    g_cls = static_cast<jclass>(env->NewGlobalRef(cls));
    g_on_event = env->GetStaticMethodID(cls, "onEvent", "(Ljava/lang/String;)V");
    return g_on_event ? JNI_VERSION_1_6 : JNI_ERR;
}

// Boots the node once per process. [fd] is a duplicate of the app's USB connection, owned from now on.
JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_start(JNIEnv* env, jclass, jstring data_dir, jint fd,
                                                                    jint kind, jint ep_in, jint ep_out, jint iface)
{
    if (g_started.exchange(true)) return;
    const bool xiao = kind == RADIO_XIAO;
    if (xiao) {
        XiaoBridge::setAndroidUsb(fd, ep_in, ep_out, iface);
        g_bus = std::make_unique<XiaoBridge>();
    } else {
        Ch341Bridge::setAndroidFd(fd);
        g_bus = std::make_unique<Ch341Bridge>();
    }
    std::thread(run, str(env, data_dir), xiao).detach();
}

JNIEXPORT jint JNICALL Java_com_engperini_meshcore_mt_MtNative_newPacketId(JNIEnv*, jclass)
{
    std::lock_guard<std::mutex> lock(g_rng_mutex);
    uint32_t id = 0;
    while (id == 0) id = g_rng();
    return static_cast<jint>(id);
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_requestConfig(JNIEnv*, jclass, jint nonce)
{
    post([nonce] { mt_request_config(static_cast<uint32_t>(nonce)); });
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_sendText(JNIEnv* env, jclass, jint id, jint to,
                                                                       jint channel, jstring text)
{
    std::string t = str(env, text);
    post([id, to, channel, t] {
        if (!mt_send_text(static_cast<uint32_t>(id), static_cast<uint32_t>(to), channel, t)) {
            emit("{\"t\":\"ack\",\"req\":" + std::to_string(static_cast<uint32_t>(id)) + ",\"err\":-1}");
        }
    });
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_traceroute(JNIEnv*, jclass, jint id, jint dest,
                                                                         jint channel)
{
    post([id, dest, channel] { mt_traceroute(static_cast<uint32_t>(id), static_cast<uint32_t>(dest), channel); });
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_setOwner(JNIEnv* env, jclass, jstring long_name,
                                                                       jstring short_name)
{
    std::string l = str(env, long_name), s = str(env, short_name);
    post([l, s] { mt_set_owner(l, s); });
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_setLora(JNIEnv*, jclass, jint region, jint preset,
                                                                      jint hop_limit, jboolean tx_enabled,
                                                                      jint tx_power)
{
    post([=] { mt_set_lora(region, preset, hop_limit, tx_enabled == JNI_TRUE, tx_power); });
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_setChannel(JNIEnv* env, jclass, jint index, jint role,
                                                                         jstring name, jbyteArray psk)
{
    std::string n = str(env, name);
    std::vector<uint8_t> key;
    if (psk) {
        key.resize(static_cast<size_t>(env->GetArrayLength(psk)));
        env->GetByteArrayRegion(psk, 0, static_cast<jsize>(key.size()), reinterpret_cast<jbyte*>(key.data()));
    }
    post([index, role, n, key] { mt_set_channel(index, role, n, key); });
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_removeNode(JNIEnv*, jclass, jint num)
{
    post([num] { mt_remove_node(static_cast<uint32_t>(num)); });
}

JNIEXPORT void JNICALL Java_com_engperini_meshcore_mt_MtNative_setPosition(JNIEnv*, jclass, jint lat_i, jint lon_i,
                                                                          jint alt)
{
    post([=] { mt_set_position(lat_i, lon_i, alt); });
}

} // extern "C"

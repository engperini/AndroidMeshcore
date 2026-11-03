// Android glue for the Meshtastic firmware, adapted from its WASM glue
// (src/platform/portduino/wasm/portduino_glue_wasm.cpp, GPL-3.0, the Meshtastic authors).
//
// It supplies what the firmware's WASM build expects from its host: the LoRa adapter config
// (wasm_config_apply), the data directory, the node's MAC (its identity), the PhoneAPI seam the app
// talks to (ToRadio / FromRadio protobufs), and the framework symbols the excluded Linux core would
// provide (delay, random, reboot...). Everything runs on the radio thread (mt_jni.cpp).

#include "mt_glue.h"

#include "MeshRadio.h"
#include "MeshService.h"
#include "NodeDB.h"
#include "PhoneAPI.h"
#include "PortduinoFS.h"
#include "PortduinoGlue.h"
#include "RadioInterface.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

namespace {
std::string g_root = "/tmp/meshtastic";
std::atomic<bool> g_reboot{false};
}

extern "C" volatile bool g_wasm_in_firmware = false;

void mt_set_data_root(const std::string& root)
{
    g_root = root;
}

bool mt_take_reboot_request()
{
    return g_reboot.exchange(false);
}

void wasm_fs_sync() {}   // files are written straight to the app's storage

void mt_fs_mount()
{
    mkdir(g_root.c_str(), 0700);
    mkdir((g_root + "/prefs").c_str(), 0700);
    mkdir((g_root + "/oem").c_str(), 0700);
    portduinoVFS->mountpoint(g_root.c_str());
}

// The node's MAC: its lower 4 bytes become the NodeNum, the node's identity on the mesh. Generated
// once (locally administered, unicast) and kept with the node's data.
static std::string resolveMac()
{
    const std::string path = g_root + "/oem/mac";
    if (FILE* f = fopen(path.c_str(), "rb")) {
        char buf[13] = {0};
        const size_t n = fread(buf, 1, 12, f);
        fclose(f);
        if (n == 12) return std::string(buf, 12);
    }
    unsigned char m[6] = {0};
    const int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) {
        if (read(fd, m, sizeof(m)) != static_cast<ssize_t>(sizeof(m))) {
            for (auto& b : m) b = static_cast<unsigned char>(rand());
        }
        close(fd);
    }
    m[0] = (m[0] | 0x02) & 0xFE;
    char hex[13];
    snprintf(hex, sizeof(hex), "%02X%02X%02X%02X%02X%02X", m[0], m[1], m[2], m[3], m[4], m[5]);
    if (FILE* f = fopen(path.c_str(), "wb")) {
        fwrite(hex, 1, 12, f);
        fclose(f);
    }
    return std::string(hex, 12);
}

// LoRa adapter: an SX1262 behind a CH341 (MeshStick, USB LoRa stick) or the XIAO bridge, both addressed
// with the MeshStick's D-lines (pinedio_bus.cpp maps them for the XIAO): CS D0, RESET D2, BUSY D4,
// IRQ D6, RXEN D1, 1.8 V TCXO on DIO3, DIO2 as RF switch. Called from portduinoSetup().
void wasm_config_apply()
{
    auto setPin = [](pinMapping& p, int n) {
        p.pin = n;
        p.enabled = true;
    };
    if (mt_use_simradio()) {
        portduino_config.lora_module = use_simradio;
    } else {
        portduino_config.lora_module = use_sx1262;
        portduino_config.lora_usb_vid = 0x1A86;
        portduino_config.lora_usb_pid = 0x5512;
        portduino_config.lora_usb_serial_num = "";
        portduino_config.dio2_as_rf_switch = true;
        portduino_config.dio3_tcxo_voltage = 1800;
        portduino_config.spiSpeed = 2000000;
        setPin(portduino_config.lora_cs_pin, 0);
        setPin(portduino_config.lora_irq_pin, 6);
        setPin(portduino_config.lora_busy_pin, 4);
        setPin(portduino_config.lora_reset_pin, 2);
        setPin(portduino_config.lora_rxen_pin, 1);
    }
    portduino_config.lora_spi_dev = "ch341";
    portduino_config.displayPanel = no_screen;
    portduino_config.has_gps = false;
    portduino_config.MaxNodes = 200;
    portduino_config.mac_address = resolveMac();
}

// Region from the app (same path as the WASM build's wasm_set_region / AdminModule). 0 ok, -1 invalid.
int mt_set_region(int region)
{
    auto newRegion = static_cast<meshtastic_Config_LoRaConfig_RegionCode>(region);
    if (config.lora.region == newRegion) return 0;
    meshtastic_Config_LoRaConfig validated = config.lora;
    validated.region = newRegion;
    if (!(RadioInterface::validateConfigRegion(validated) && RadioInterface::validateConfigLora(validated))) return -1;
    int changes = SEGMENT_CONFIG;
    const bool wasUnset = config.lora.region == meshtastic_Config_LoRaConfig_RegionCode_UNSET;
    if (wasUnset && newRegion > meshtastic_Config_LoRaConfig_RegionCode_UNSET) {
#if !(MESHTASTIC_EXCLUDE_PKI_KEYGEN || MESHTASTIC_EXCLUDE_PKI)
        if (nodeDB && nodeDB->ensurePkiIdentity()) changes |= SEGMENT_DEVICESTATE | SEGMENT_NODEDATABASE;
#endif
        validated.tx_enabled = true;
    }
    if (!wasUnset && newRegion == meshtastic_Config_LoRaConfig_RegionCode_UNSET) validated.tx_enabled = false;
    config.lora = validated;
    initRegion();
    if (service) service->reloadConfig(changes);
    return 0;
}

// ---- PhoneAPI: the app is always the connected client
class AppPhoneAPI : public PhoneAPI
{
  public:
    bool checkIsConnected() override { return true; }
};

static AppPhoneAPI* g_phone = nullptr;

static AppPhoneAPI* phone()
{
    if (!g_phone && service) g_phone = new AppPhoneAPI();
    return g_phone;
}

bool mt_api_to_radio(const uint8_t* buf, size_t len)
{
    AppPhoneAPI* p = phone();
    return p && p->handleToRadio(const_cast<uint8_t*>(buf), len);
}

size_t mt_api_from_radio(uint8_t* out, size_t max)
{
    AppPhoneAPI* p = phone();
    if (!p || max < 512 || !p->available()) return 0;
    return p->getFromRadio(out);
}

// ---- idle wait of the firmware loop
namespace {
std::mutex g_idle_mutex;
std::condition_variable g_idle_cv;
bool g_wake_flag = false;
constexpr auto IRQ_POLL = std::chrono::milliseconds(4);
}

void mt_wake()
{
    {
        std::lock_guard<std::mutex> lock(g_idle_mutex);
        g_wake_flag = true;
    }
    g_idle_cv.notify_all();
}

void mt_idle(unsigned int ms)
{
    const auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
    for (;;) {
        if (mt_poll_irqs()) return;
        std::unique_lock<std::mutex> lock(g_idle_mutex);
        const auto now = std::chrono::steady_clock::now();
        if (g_wake_flag || now >= end) {
            g_wake_flag = false;
            return;
        }
        g_idle_cv.wait_until(lock, std::min(end, now + IRQ_POLL), [] { return g_wake_flag; });
    }
}

// ---- framework symbols the excluded linux/LinuxCommon.cpp would provide
void delay(unsigned long ms)
{
    usleep(ms * 1000);
}
void yield(void)
{
    usleep(0);
}
long random(long howbig)
{
    return howbig > 0 ? (::rand() % howbig) : 0;
}
long random(long howsmall, long howbig)
{
    return howsmall >= howbig ? howsmall : howsmall + random(howbig - howsmall);
}
void randomSeed(unsigned long seed)
{
    ::srand(static_cast<unsigned>(seed));
}
// The node cannot restart its process; the app restarts the radio thread instead.
void reboot()
{
    g_reboot.store(true);
}
void tone(unsigned char, unsigned int, unsigned long) {}
void noTone(unsigned char) {}
void delayMicroseconds(unsigned int us)
{
    usleep(us);
}
bool shouldWakeOnReceivedMessage()
{
    return false;
}
void initApiServer(int) {}
void deInitApiServer() {}

// Referenced by modules that are built even with the input broker excluded; there is none on a phone.
class InputBroker;
InputBroker* inputBroker = nullptr;

// Command-line arguments come from the framework's main(), which the app does not use.
#include <argp.h>
void portduinoAddArguments(const struct argp_child&, void*) {}

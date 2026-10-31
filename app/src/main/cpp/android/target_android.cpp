#include "target.h"

#include <cstdlib>
#include <atomic>
#include <cmath>
#include <random>
#include <string>

LinuxBoard board;

static LinuxRadioConfig g_cfg{};
static std::string g_driver_name = "sx1262";
LinuxRadioDriver radio_driver(g_cfg);
LinuxRTCClock rtc_clock;
SensorManager sensors;

namespace {

std::atomic<uint16_t> g_batt_mv{4200};
std::atomic<bool> g_charging{false};
std::atomic<bool> g_reboot_requested{false};

}

void board_set_battery(uint16_t millivolts, bool charging) {
  if (millivolts > 0) g_batt_mv.store(millivolts);
  g_charging.store(charging);
}

bool board_take_reboot_request() {
  return g_reboot_requested.exchange(false);
}

LinuxRadioDriver::LinuxRadioDriver(const LinuxRadioConfig& cfg_) : cfg(cfg_) {}

void LinuxRadioDriver::ensureBackend() {
  if (backend && backend->getDriverName() == driver_name) {
    return;
  }

  if (driver_name == "sx1262_ch341") {
    backend = std::make_unique<SX1262LinuxRadio>(cfg, true);
  } else {
    driver_name = "sx1262";
    backend = std::make_unique<SX1262LinuxRadio>(cfg);
  }
}

void LinuxRadioDriver::setDriverName(const std::string& name) {
  if (radio_driver_is_ch341(name.c_str())) {
    driver_name = "sx1262_ch341";
  } else {
    driver_name = "sx1262";
  }
  backend.reset();
}

const char* LinuxRadioDriver::getDriverName() const {
  return driver_name.c_str();
}

void LinuxRadioDriver::setConfig(const LinuxRadioConfig& cfg_) {
  cfg = cfg_;
  if (backend) {
    backend->setConfig(cfg);
  }
}

const LinuxRadioConfig& LinuxRadioDriver::getConfig() const {
  return cfg;
}

void LinuxRadioDriver::begin() {
  ensureBackend();
  backend->setConfig(cfg);
  backend->begin();
}

uint32_t LinuxRadioDriver::getEstAirtimeFor(int len_bytes) {
  ensureBackend();
  return backend->getEstAirtimeFor(len_bytes);
}

float LinuxRadioDriver::packetScore(float snr, int packet_len) {
  ensureBackend();
  return backend->packetScore(snr, packet_len);
}

bool LinuxRadioDriver::isInRecvMode() const {
  return backend ? backend->isInRecvMode() : false;
}

bool LinuxRadioDriver::isReceiving() {
  ensureBackend();
  return backend->isReceiving();
}

void LinuxRadioDriver::loop() {
  ensureBackend();
  backend->loop();
}

float LinuxRadioDriver::getLastRSSI() const {
  return backend ? backend->getLastRSSI() : 0.0f;
}

float LinuxRadioDriver::getLastSNR() const {
  return backend ? backend->getLastSNR() : 0.0f;
}

int LinuxRadioDriver::getNoiseFloor() const {
  return backend ? backend->getNoiseFloor() : 0;
}

void LinuxRadioDriver::triggerNoiseFloorCalibrate(int threshold) {
  ensureBackend();
  backend->triggerNoiseFloorCalibrate(threshold);
}

void LinuxRadioDriver::resetAGC() {
  ensureBackend();
  backend->resetAGC();
}

uint8_t LinuxRadioDriver::debugGetStatus() {
  ensureBackend();
  return backend->debugGetStatus();
}

uint16_t LinuxRadioDriver::debugGetIrqStatus() {
  ensureBackend();
  return backend->debugGetIrqStatus();
}

uint16_t LinuxRadioDriver::debugGetDeviceErrors() {
  ensureBackend();
  return backend->debugGetDeviceErrors();
}

void LinuxRadioDriver::debugClearDeviceErrors() {
  ensureBackend();
  backend->debugClearDeviceErrors();
}

void LinuxBoard::begin() {
  boot_voltage_mv = getBattMilliVolts();
}

bool LinuxBoard::setAdcMultiplier(float multiplier) {
  if (multiplier <= 0.0f) {
    adc_multiplier = 1.0f;
    return true;
  }
  adc_multiplier = multiplier;
  return true;
}

uint16_t LinuxBoard::getBattMilliVolts() {
  const float scaled = static_cast<float>(g_batt_mv.load()) * adc_multiplier;
  if (scaled <= 0.0f) {
    return 0;
  }
  if (scaled >= 65535.0f) {
    return 65535;
  }
  return static_cast<uint16_t>(scaled + 0.5f);
}

float LinuxBoard::getMCUTemperature() {
  return NAN;
}

// A companion app may send CMD_REBOOT; exiting would kill the Android app, so
// the service is asked to restart the radio instead.
void LinuxBoard::reboot() {
  g_reboot_requested.store(true);
}

bool LinuxBoard::isExternalPowered() {
  return g_charging.load();
}

void LinuxRadioDriver::setRuntimeRadio(float freq, float bw, uint8_t sf, uint8_t cr, int8_t tx) {
  LinuxRadioConfig cfg = g_cfg;
  cfg.frequency_hz = static_cast<int>(freq * 1000000.0f);
  cfg.bandwidth_hz = static_cast<int>(bw * 1000.0f);
  cfg.spreading_factor = sf;
  cfg.coding_rate = cr;
  cfg.tx_power_dbm = tx;
  g_cfg = cfg;
  // Apply immediately so begin()/TX uses updated settings.
  this->setConfig(g_cfg);
}

bool radio_init() {
  rtc_clock.begin();
  LittleFS.begin();
  radio_driver.setDriverName(g_driver_name);
  // Apply any config changes that happened before init.
  radio_driver.setConfig(g_cfg);
  radio_driver.begin();
  return true;
}

uint32_t radio_get_rng_seed() {
  static std::mt19937 rng{std::random_device{}()};
  return rng();
}

bool radio_driver_is_ch341(const char* driver_name) {
  if (!driver_name) return false;
  const std::string name(driver_name);
  return name == "sx1262_ch341" || name == "meshstick";
}

void radio_set_driver(const char* driver_name) {
  if (radio_driver_is_ch341(driver_name)) {
    g_driver_name = "sx1262_ch341";
  } else {
    g_driver_name = "sx1262";
  }
  radio_driver.setDriverName(g_driver_name);
}

void radio_set_params(float freq, float bw, uint8_t sf, uint8_t cr) {
  radio_driver.setRuntimeRadio(freq, bw, sf, cr, 22);
}

void radio_set_tx_power(int8_t dbm) {
  radio_driver.setRuntimeRadio(static_cast<float>(g_cfg.frequency_hz) / 1000000.0f,
                               static_cast<float>(g_cfg.bandwidth_hz) / 1000.0f,
                               static_cast<uint8_t>(g_cfg.spreading_factor),
                               static_cast<uint8_t>(g_cfg.coding_rate),
                               dbm);
}

void radio_set_hw_config(const char* spi_dev_prefix, int spi_bus, int spi_cs, int spi_speed_hz,
                         int cs_pin,
                         int reset_pin, int busy_pin, int irq_pin,
                         int txen_pin, int rxen_pin,
                         bool use_dio3_tcxo,
                         bool use_dio2_rf_switch) {
  if (spi_dev_prefix && spi_dev_prefix[0] != '\0') {
    g_cfg.spi_dev_prefix = spi_dev_prefix;
  } else {
    g_cfg.spi_dev_prefix = "/dev/spidev";
  }
  g_cfg.spi_bus = spi_bus;
  g_cfg.spi_cs = spi_cs;
  g_cfg.spi_speed_hz = spi_speed_hz;
  g_cfg.cs_pin = cs_pin;
  g_cfg.reset_pin = reset_pin;
  g_cfg.busy_pin = busy_pin;
  g_cfg.irq_pin = irq_pin;
  g_cfg.txen_pin = txen_pin;
  g_cfg.rxen_pin = rxen_pin;
  g_cfg.use_dio3_tcxo = use_dio3_tcxo;
  g_cfg.use_dio2_rf_switch = use_dio2_rf_switch;

  if (g_driver_name == "sx1262_ch341") {
    // Pins are CH341 D0..D7 lines here. Values left over from a GPIO-header
    // config (e.g. BCM 21/18/20/16) fall back to the MeshStick wiring.
    // D3 (SCK), D5 (MOSI) and D7 (MISO) belong to the SPI engine.
    auto usable = [](int pin, bool output) {
      return pin >= 0 && pin <= (output ? 4 : 6) && pin != 3 && pin != 5;
    };
    if (!usable(g_cfg.cs_pin, true)) g_cfg.cs_pin = 0;
    if (!usable(g_cfg.reset_pin, true)) g_cfg.reset_pin = 2;
    if (!usable(g_cfg.busy_pin, false)) g_cfg.busy_pin = 4;
    if (!usable(g_cfg.irq_pin, false)) g_cfg.irq_pin = 6;
    if (g_cfg.txen_pin >= 0 && !usable(g_cfg.txen_pin, true)) g_cfg.txen_pin = -1;
    if (g_cfg.rxen_pin >= 0 && !usable(g_cfg.rxen_pin, true)) g_cfg.rxen_pin = -1;
  }
  radio_driver.setConfig(g_cfg);
}

mesh::LocalIdentity radio_new_identity() {
  StdRNG rng;
  rng.begin(static_cast<long>(radio_get_rng_seed()));
  return mesh::LocalIdentity(&rng);
}

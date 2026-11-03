// libpinedio-usb API (what the firmware's Ch341Hal drives) on top of this app's USB radio buses:
// Ch341Bridge for the MeshStick / USB LoRa stick and XiaoBridge for the XIAO bridge. The firmware
// addresses the radio by CH341 D-line numbers; for the XIAO bridge they are mapped to its logical pins.
//
// The bus is handed over with mt_set_bus() before setup() and may change when the radio is re-plugged
// (mt_set_bus(nullptr) while it is away). Without a bus every call fails softly, which is also what the
// PC test build (SimRadio, no USB) relies on.

#include "libpinedio-usb.h"
#include "mt_bus.h"

#include <cstring>
#include <exception>
#include <mutex>

namespace {

std::mutex g_mutex;
RadioUsbBus* g_bus = nullptr;
bool g_logical_pins = false;   // XIAO bridge: logical pin ids instead of CH341 D-lines

// CH341 D-line -> XIAO bridge logical pin (NSS 0, RESET 1, BUSY 2, DIO1 3, RXEN 4, TXEN 5); -1 = none.
int mapPin(uint32_t pin)
{
    if (!g_logical_pins) return static_cast<int>(pin);
    switch (pin) {
        case 0: return 0;   // D0 CS
        case 2: return 1;   // D2 RESET
        case 4: return 2;   // D4 BUSY
        case 6: return 3;   // D6 DIO1
        case 1: return 4;   // D1 RXEN
        default: return -1; // D3/D5/D7 are the SPI lines, handled by the bridge itself
    }
}

template <typename F> int32_t guarded(pinedio_inst* inst, F f)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_bus) {
        if (inst) inst->in_error = true;
        return -1;
    }
    try {
        return f();
    } catch (const std::exception&) {
        if (inst) inst->in_error = true;
        return -1;
    }
}

} // namespace

void mt_set_bus(RadioUsbBus* bus, bool logical_pins)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    g_bus = bus;
    g_logical_pins = logical_pins;
}

extern "C" {

int32_t pinedio_set_option(struct pinedio_inst* inst, enum pinedio_option option, uint32_t value)
{
    if (option < PINEDIO_OPTION_MAX) inst->options[option] = value;
    return 0;
}

int32_t pinedio_init(struct pinedio_inst* inst, void*)
{
    inst->in_error = false;
    for (int i = 0; i < PINEDIO_INT_PIN_MAX; i++) inst->interrupts[i].callback = nullptr;
    std::strncpy(inst->serial_number, "android", sizeof(inst->serial_number) - 1);
    std::strncpy(inst->product_string, "MeshCommunity USB radio", sizeof(inst->product_string) - 1);
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_bus) return 0;   // PC test build: no radio, SimRadio is used
    try {
        g_bus->open();
    } catch (const std::exception&) {
        inst->in_error = true;
        return -2;
    }
    return 0;
}

void pinedio_deinit(struct pinedio_inst*) {}

int32_t pinedio_set_pin_mode(struct pinedio_inst* inst, uint32_t pin, uint32_t mode)
{
    return guarded(inst, [&] {
        const int p = mapPin(pin);
        if (p >= 0) g_bus->pinMode(p, mode != 0);
        return 0;
    });
}

int32_t pinedio_digital_write(struct pinedio_inst* inst, uint32_t pin, bool active)
{
    return guarded(inst, [&] {
        const int p = mapPin(pin);
        if (p >= 0) g_bus->digitalWrite(p, active);
        return 0;
    });
}

int32_t pinedio_set_cs(struct pinedio_inst* inst, bool active)
{
    return pinedio_digital_write(inst, 0, active);   // D0 is CS
}

int32_t pinedio_digital_read(struct pinedio_inst* inst, uint32_t pin)
{
    return guarded(inst, [&] {
        const int p = mapPin(pin);
        return p >= 0 ? (g_bus->digitalRead(p) ? 1 : 0) : 0;
    });
}

int32_t pinedio_get_irq_state(struct pinedio_inst* inst, uint32_t pin)
{
    return pinedio_digital_read(inst, pin);
}

int32_t pinedio_transceive(struct pinedio_inst* inst, uint8_t* write_buf, uint8_t* read_buf, uint32_t count)
{
    return guarded(inst, [&] {
        g_bus->spiTransfer(write_buf, read_buf, count);
        return 0;
    });
}

int32_t pinedio_write_read(struct pinedio_inst* inst, uint8_t* writearr, uint32_t writecnt, uint8_t* readarr,
                           uint32_t readcnt)
{
    return guarded(inst, [&] {
        uint8_t buf[512];
        const uint32_t total = writecnt + readcnt;
        if (total > sizeof(buf)) return -1;
        std::memcpy(buf, writearr, writecnt);
        std::memset(buf + writecnt, 0, readcnt);
        g_bus->spiTransfer(buf, buf, total);
        std::memcpy(readarr, buf + writecnt, readcnt);
        return 0;
    });
}

// Interrupts are polled: the firmware calls pollMissedIrqs() every loop, which reads the IRQ line.
int32_t pinedio_attach_interrupt(struct pinedio_inst* inst, enum pinedio_int_pin int_pin, enum pinedio_int_mode int_mode,
                                 void (*callback)(void))
{
    if (int_pin >= PINEDIO_INT_PIN_MAX) return -1;
    inst->interrupts[int_pin].previous_state = 255;
    inst->interrupts[int_pin].mode = int_mode;
    inst->interrupts[int_pin].callback = callback;
    return 0;
}

int32_t pinedio_deattach_interrupt(struct pinedio_inst* inst, enum pinedio_int_pin int_pin)
{
    if (int_pin >= PINEDIO_INT_PIN_MAX) return -1;
    inst->interrupts[int_pin].callback = nullptr;
    return 0;
}

} // extern "C"

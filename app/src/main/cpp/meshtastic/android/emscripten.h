// Native stand-in for <emscripten.h>. The Meshtastic firmware's WASM build (ARCH_PORTDUINO_WASM) is the
// one this app compiles, because it already leaves out the Linux host services; the few browser calls it
// makes become plain native ones here.
#pragma once

#include <unistd.h>

#define EMSCRIPTEN_KEEPALIVE __attribute__((used))

// The firmware's loop idles through this between radio polls (capped at 50 ms by main.cpp). On Android it
// waits on the radio thread, polling the radio's IRQ line and waking early for the app (mt_glue.cpp).
void mt_idle(unsigned int ms);
static inline void emscripten_sleep(unsigned int ms)
{
    mt_idle(ms);
}

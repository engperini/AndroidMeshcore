// Native stand-in for <emscripten.h>. The Meshtastic firmware's WASM build (ARCH_PORTDUINO_WASM) is the
// one this app compiles, because it already leaves out the Linux host services; the few browser calls it
// makes become plain native ones here.
#pragma once

#include <unistd.h>

#define EMSCRIPTEN_KEEPALIVE __attribute__((used))

// The firmware's loop "suspends" through this between radio polls; on Android the loop runs on its
// own thread, so it simply sleeps.
static inline void emscripten_sleep(unsigned int ms)
{
    usleep(ms * 1000);
}

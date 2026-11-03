// Stand-in for libi2c's <i2c/smbus.h>: the phone has no I2C bus, so the portduino core's I2C shim only
// needs the declarations to compile, and every call fails like a missing device would.
#pragma once
#include <linux/types.h>

static inline __s32 i2c_smbus_write_quick(int file, __u8 value)
{
    (void)file;
    (void)value;
    return -1;
}

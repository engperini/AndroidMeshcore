// getrandom() for Android below API 28 (the app supports API 26): the syscall exists on every kernel the
// app runs on, bionic only exposes the wrapper from API 28. Elsewhere this is the system header.
#pragma once
#if defined(__ANDROID__) && __ANDROID_API__ < 28
#include <stddef.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>
static inline ssize_t getrandom(void* buf, size_t len, unsigned int flags)
{
    return (ssize_t)syscall(__NR_getrandom, buf, len, flags);
}
#else
#include_next <sys/random.h>
#endif

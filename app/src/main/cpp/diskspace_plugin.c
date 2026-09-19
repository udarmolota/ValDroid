// ValDroid — ARM64 build of Valheim's DiskSpacePlugin for native ARM64 Mono.
//
// assembly_utils.dll P/Invokes DiskSpacePlugin.get_free_space(path) before every save. Under native
// Mono the game's x86_64 libDiskSpacePlugin.so cannot be loaded, and the box64 Mono wrapper's dl
// fallback looks for a library of the same name next to the ARM64 runtime (the APK's native library
// directory), which is this file. Same contract as the original: bytes available to the user, -1 on
// error (the game then assumes unlimited space).

#include <stdint.h>
#include <sys/statvfs.h>

__attribute__((visibility("default"))) int64_t get_free_space(const char* path)
{
    struct statvfs st;
    if (!path || statvfs(path, &st) != 0) return -1;
    return (int64_t)st.f_bavail * (int64_t)st.f_frsize;
}

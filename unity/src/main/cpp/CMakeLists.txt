cmake_minimum_required(VERSION 3.22.1)
project(valdroid_native_unity C)

# il2mono: libil2cpp.so for Unity's Android player, backed by ValDroid's ARM64 Mono
# (libmonobdwgc-2.0.so in the app's native libs, loaded at run time). gen/stubs.c holds the weak
# "UNIMPLEMENTED" default for every il2cpp_* API function; regenerate it with
# il2mono/gen_stubs.py from the IL2CPP sources of a Unity Gradle export when the Unity version changes.
add_library(il2cpp SHARED
    il2mono/core.c
    il2mono/api_init.c
    il2mono/api_runtime.c
    il2mono/api_more.c
    il2mono/pad.c
    il2mono/config.c
    il2mono/gen/stubs.c)
target_compile_options(il2cpp PRIVATE -std=c11 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(il2cpp log dl)
target_link_options(il2cpp PRIVATE -Wl,--no-undefined)

# ARM64 stand-in for Valheim's x86_64 DiskSpacePlugin (checked before every save).
add_library(DiskSpacePlugin SHARED plugins/disk_space_plugin.c)
target_compile_options(DiskSpacePlugin PRIVATE -fvisibility=hidden -Wall)

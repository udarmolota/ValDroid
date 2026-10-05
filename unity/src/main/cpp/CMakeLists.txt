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
    il2mono/bepinex.c
    il2mono/gen/stubs.c)
target_compile_options(il2cpp PRIVATE -std=c11 -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(il2cpp log dl)
target_link_options(il2cpp PRIVATE -Wl,--no-undefined)

# ARM64 stand-in for Valheim's x86_64 DiskSpacePlugin (checked before every save).
add_library(DiskSpacePlugin SHARED plugins/disk_space_plugin.c)
target_compile_options(DiskSpacePlugin PRIVATE -fvisibility=hidden -Wall)

# Unity's Swappy wrapper (frame pacing), loaded by libunity when present. Its source comes with Unity's
# Android Support package and is copied by prepare_unity_module.sh into unity/prepared/swappy; built as
# Unity's own CMake file for it does.
set(SWAPPY_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../prepared/swappy)
if(EXISTS ${SWAPPY_SRC}/UnitySwappyWrapper.cpp)
    enable_language(CXX)
    find_package(games-frame-pacing REQUIRED CONFIG)
    add_library(swappywrapper SHARED ${SWAPPY_SRC}/UnitySwappyWrapper.cpp)
    target_compile_definitions(swappywrapper PRIVATE EXTERNAL_FRAME_PACING_CODE)
    target_include_directories(swappywrapper PRIVATE ${SWAPPY_SRC})
    target_link_options(swappywrapper PRIVATE -Wl,--wrap=__android_log_print)
    target_link_libraries(swappywrapper android games-frame-pacing::swappy_static log)
endif()

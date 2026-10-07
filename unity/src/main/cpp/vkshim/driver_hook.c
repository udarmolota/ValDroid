// libvkdriverhook.so: lets the native engine run on a Vulkan driver other than the phone's own (a test
// switch, VALDROID_VULKAN_DRIVER; see driver.c). vkshim loads this library into a linker namespace of its
// own, ahead of a private copy of the system libvulkan.so. Linked with -z global, it comes before libdl
// for every library in that namespace, so when the copy looks for the phone's driver
// ("vulkan.<board>.so", through android_load_sphal_library or android_dlopen_ext) it gets the driver
// vkshim loaded instead. Everything else passes through to the real linker. The same mechanism as the
// box64 launch (app/src/main/cpp/linker.c).
#include <android/dlext.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default"), used))

typedef void* (*LoaderDlopenExt)(const char* filename, int flags, const android_dlextinfo* info, const void* caller);

static LoaderDlopenExt g_loader_dlopen_ext;
static void* g_driver;

EXPORT void vkdriverhook_set(void* loader_dlopen_ext, void* driver)
{
    g_loader_dlopen_ext = (LoaderDlopenExt)loader_dlopen_ext;
    g_driver = driver;
}

// A Vulkan HAL driver's file name: vulkan.<board>.so (not libvulkan.so, the loader itself).
static int is_driver(const char* filename)
{
    if (!filename)
        return 0;
    const char* base = strrchr(filename, '/');
    base = base ? base + 1 : filename;
    return strncmp(base, "vulkan.", 7) == 0;
}

EXPORT void* android_dlopen_ext(const char* filename, int flags, const android_dlextinfo* info)
{
    if (g_driver && is_driver(filename))
        return g_driver;
    return g_loader_dlopen_ext ? g_loader_dlopen_ext(filename, flags, info, (const void*)android_dlopen_ext) : NULL;
}

EXPORT void* android_load_sphal_library(const char* filename, int flags)
{
    if (g_driver && is_driver(filename))
        return g_driver;
    return g_loader_dlopen_ext ? g_loader_dlopen_ext(filename, flags, NULL, (const void*)android_load_sphal_library)
                               : NULL;
}

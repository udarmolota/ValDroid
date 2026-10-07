// A Vulkan loader on another driver, for VALDROID_VULKAN_DRIVER (an absolute path to a Vulkan HAL driver:
// the launcher resolves "custom" to the imported driver and a bare name to the bundled ones). A private
// copy of the system libvulkan.so is loaded into a linker namespace of our own, behind libvkdriverhook.so
// (driver_hook.c), which hands the copy our driver when it looks for the phone's. The copy keeps doing
// what the loader does on Android (the HAL entry point, the swapchain on top of the driver), so the
// driver only has to be one the system loader could load. Anything failing here leaves the phone's own
// driver in charge: vkshim then opens the system libvulkan.so as usual.
#include <android/log.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>

#include "android_linker_ns.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VKSHIM", __VA_ARGS__)

void* vkshim_open_custom_loader(const char* driver_path)
{
    if (driver_path[0] != '/')
    {
        LOG("custom driver: '%s' is not an absolute path", driver_path);
        return NULL;
    }
    if (!linkernsbypass_load_status())
    {
        LOG("custom driver: linker namespaces are not available on this phone");
        return NULL;
    }

    // Search path: our own native library directory (the hook) and the driver's directory.
    Dl_info self;
    if (!dladdr((void*)vkshim_open_custom_loader, &self) || !self.dli_fname)
    {
        LOG("custom driver: cannot find our own library directory");
        return NULL;
    }
    char search[2 * PATH_MAX + 2];
    const char* self_slash = strrchr(self.dli_fname, '/');
    const char* driver_slash = strrchr(driver_path, '/');
    snprintf(search, sizeof(search), "%.*s:%.*s", (int)(self_slash ? self_slash - self.dli_fname : 0), self.dli_fname,
             (int)(driver_slash - driver_path), driver_path);

    struct android_namespace_t* ns =
        android_create_namespace("valdroid-vulkan", search, search, ANDROID_NAMESPACE_TYPE_SHARED, NULL, NULL);
    if (!ns)
    {
        LOG("custom driver: android_create_namespace failed");
        return NULL;
    }
    void* hook = linkernsbypass_namespace_dlopen("libvkdriverhook.so", RTLD_GLOBAL, ns);
    void (*hook_set)(void*, void*) = hook ? (void (*)(void*, void*))dlsym(hook, "vkdriverhook_set") : NULL;
    void* libdl = dlopen("libdl.so", RTLD_LAZY);
    void* loader_dlopen_ext = libdl ? dlsym(libdl, "__loader_android_dlopen_ext") : NULL;
    if (!hook_set || !loader_dlopen_ext)
    {
        LOG("custom driver: hook unavailable (%s)", hook ? "no __loader_android_dlopen_ext" : dlerror());
        return NULL;
    }

    void* driver = linkernsbypass_namespace_dlopen(driver_path, RTLD_LOCAL | RTLD_NOW, ns);
    if (!driver)
    {
        // A driver may need system libraries the app's namespace does not see (libsync, for one).
        LOG("custom driver: %s did not load (%s); retrying with the system libraries visible", driver_path, dlerror());
        linkernsbypass_link_namespace_to_default_all_libs(ns);
        driver = linkernsbypass_namespace_dlopen(driver_path, RTLD_LOCAL | RTLD_NOW, ns);
    }
    if (!driver)
    {
        LOG("custom driver: %s did not load: %s", driver_path, dlerror());
        return NULL;
    }
    if (!dlsym(driver, "HMI"))
    {
        LOG("custom driver: %s has no HMI entry point: not an Android Vulkan HAL driver", driver_path);
        return NULL;
    }
    hook_set(loader_dlopen_ext, driver);

    void* loader = linkernsbypass_namespace_dlopen_unique("/system/lib64/libvulkan.so", NULL, RTLD_GLOBAL | RTLD_NOW, ns);
    if (!loader)
    {
        // The loader too can need vendor libraries (libgpud_sys.so on a MediaTek Xiaomi, 2026-10-07).
        LOG("custom driver: a private copy of libvulkan.so did not load (%s); retrying with the system libraries visible",
            dlerror());
        linkernsbypass_link_namespace_to_default_all_libs(ns);
        loader = linkernsbypass_namespace_dlopen_unique("/system/lib64/libvulkan.so", NULL, RTLD_GLOBAL | RTLD_NOW, ns);
    }
    if (!loader)
    {
        // Then the vendor's libraries, where the phone's own driver and its helpers live.
        struct android_namespace_t* vendor = android_get_exported_namespace ? android_get_exported_namespace("sphal") : NULL;
        LOG("custom driver: still no libvulkan.so (%s); retrying with the vendor libraries visible%s", dlerror(),
            vendor ? "" : " (no sphal namespace)");
        if (vendor && android_link_namespaces_all_libs && android_link_namespaces_all_libs(ns, vendor))
            loader = linkernsbypass_namespace_dlopen_unique("/system/lib64/libvulkan.so", NULL, RTLD_GLOBAL | RTLD_NOW, ns);
    }
    if (!loader)
    {
        LOG("custom driver: a private copy of libvulkan.so did not load: %s", dlerror());
        return NULL;
    }
    LOG("custom driver: %s, through a private libvulkan.so %p", driver_path, loader);
    return loader;
}

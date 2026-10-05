// BepInEx (mods) on the native engine. BepInEx normally comes in through Doorstop, a preloaded library
// that hooks mono_jit_init_version; il2mono makes that call itself, so it does Doorstop's job: once the
// root domain exists, load BepInEx.Preloader.dll and run Doorstop.Entrypoint.Start(). The same as the
// box64 launch on native Mono (box64 wrappedlibmonobdwgc.c, rd_bepinex_boot), so both use the
// instance's one BepInEx folder, mods and configs.
//
// Off unless VALDROID_BEPINEX_PRELOADER names the preloader DLL. The launcher also sets the
// DOORSTOP_* variables BepInEx reads (the game's executable path and Managed folder): there is no
// executable to derive them from here.
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "il2mono.h"

// dlopen() for managed code (Mono's config sends dlopen of "dl", "libdl" and "libdl.so.2" here: see
// NativeEngine.prepareMonoConfig). Linux code opens system libraries by their glibc names; MonoMod's
// DynDll does it for BepInEx (libc.so.6 for dup/fdopen), past Mono's dllmap. Bionic's names differ.
IL2MONO_API void* il2mono_dlopen(const char* name, int flags)
{
    static const char* const names[][2] = {
        { "libc.so.6", "libc.so" },
        { "libc", "libc.so" },
        { "libdl.so.2", "libdl.so" },
        { "libm.so.6", "libm.so" },
        { "libpthread.so.0", "libc.so" },
    };
    if (name)
    {
        for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); i++)
        {
            if (!strcmp(name, names[i][0]))
            {
                name = names[i][1];
                break;
            }
        }
    }
    return dlopen(name, flags);
}

static void log_exception(void* exception)
{
    void* klass = p_mono_object_get_class(exception);
    LOGE("BepInEx: Start() threw %s.%s", klass ? p_mono_class_get_namespace(klass) : "?",
         klass ? p_mono_class_get_name(klass) : "?");
    // The type name alone says little (a TypeInitializationException hides the real failure in its
    // InnerException): log Exception.ToString() too.
    void* inner = NULL;
    void* text = p_mono_object_to_string(exception, &inner);
    char* utf8 = (text && !inner) ? p_mono_string_to_utf8(text) : NULL;
    if (utf8)
    {
        LOGE("BepInEx: %s", utf8);
        p_mono_free(utf8);
    }
}

void il2mono_bepinex_boot(void* domain)
{
    const char* dll = getenv("VALDROID_BEPINEX_PRELOADER");
    if (!dll || !dll[0])
        return;
    if (!getenv("DOORSTOP_INVOKE_DLL_PATH"))
        setenv("DOORSTOP_INVOKE_DLL_PATH", dll, 1);
    if (!getenv("DOORSTOP_INITIALIZED"))
        setenv("DOORSTOP_INITIALIZED", "TRUE", 1);

    // The engine gives the domain its base directory and config file only after this point; BepInEx
    // needs them sooner (System.Configuration via Trace.Listeners throws "ExeConfigFilename cannot be
    // null"). Doorstop sets them to <exe dir> and <exe>.config; so do we.
    const char* exe = getenv("DOORSTOP_PROCESS_PATH");
    const char* slash = exe ? strrchr(exe, '/') : NULL;
    if (slash)
    {
        char base_dir[1024], config[1024];
        size_t dir_len = (size_t)(slash - exe);
        if (dir_len < sizeof(base_dir) && strlen(exe) + sizeof(".config") < sizeof(config))
        {
            memcpy(base_dir, exe, dir_len);
            base_dir[dir_len] = 0;
            snprintf(config, sizeof(config), "%s.config", exe);
            p_mono_domain_set_config(domain, base_dir, config);
            LOGI("BepInEx: domain config %s (base %s)", config, base_dir);
        }
    }

    LOGI("BepInEx: loading %s", dll);
    void* assembly = p_mono_domain_assembly_open(domain, dll);
    if (!assembly)
    {
        LOGE("BepInEx: cannot open the preloader assembly");
        return;
    }
    void* image = p_mono_assembly_get_image(assembly);
    void* klass = image ? p_mono_class_from_name(image, "Doorstop", "Entrypoint") : NULL;
    void* method = klass ? p_mono_class_get_method_from_name(klass, "Start", 0) : NULL;
    if (!method)
    {
        LOGE("BepInEx: Doorstop.Entrypoint.Start() not found");
        return;
    }
    void* exception = NULL;
    p_mono_runtime_invoke(method, NULL, NULL, &exception);
    if (exception)
    {
        log_exception(exception);
        return;
    }
    LOGI("BepInEx: preloader finished");
}

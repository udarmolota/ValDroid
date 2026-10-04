// Loader, diagnostics and the JNI entry point.
#include <dlfcn.h>
#include <jni.h>
#include <stdatomic.h>
#include <unistd.h>

#include "il2mono.h"

#define X(ret, name, params) ret(*p_##name) params;
IL2MONO_MONO_FUNCS(X)
#undef X

static atomic_uchar g_unimpl_seen[IL2MONO_API_COUNT];
static atomic_int g_call_order;

void il2mono_first_call(const char* name)
{
    LOGI("#%d %s (tid=%d)", atomic_fetch_add(&g_call_order, 1) + 1, name, gettid());
}

void il2mono_unimplemented(int index)
{
    if (index < 0 || index >= IL2MONO_API_COUNT)
        return;
    if (!atomic_exchange(&g_unimpl_seen[index], 1))
        LOGW("#%d UNIMPLEMENTED %s (tid=%d)", atomic_fetch_add(&g_call_order, 1) + 1, il2mono_api_names[index], gettid());
}

bool il2mono_load_mono(void)
{
    static int state; // 0 = not tried, 1 = ok, -1 = failed
    if (state)
        return state > 0;
    state = -1;

    void* lib = dlopen("libmonobdwgc-2.0.so", RTLD_NOW | RTLD_GLOBAL);
    if (!lib)
    {
        LOGE("dlopen libmonobdwgc-2.0.so failed: %s", dlerror());
        return false;
    }
    bool ok = true;
#define X(ret, name, params)                                   \
    p_##name = (ret(*) params)dlsym(lib, #name);               \
    if (!p_##name)                                             \
    {                                                          \
        LOGE("Mono export missing: %s", #name);                \
        ok = false;                                            \
    }
    IL2MONO_MONO_FUNCS(X)
#undef X
    if (!ok)
        return false;

    char* info = p_mono_get_runtime_build_info();
    LOGI("Mono loaded: %s", info ? info : "(no build info)");
    if (info)
        p_mono_free(info);
    state = 1;
    return true;
}

// libmain.so loads libil2cpp.so first and calls this (the real IL2CPP logs "JNI_OnLoad" here).
IL2MONO_API jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    (void)vm;
    (void)reserved;
    LOGI("JNI_OnLoad: il2mono (libil2cpp.so replacement) loaded");
    return JNI_VERSION_1_6;
}

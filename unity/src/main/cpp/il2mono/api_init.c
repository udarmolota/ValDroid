// Start-up API, in the order libunity calls it (see spy/spy_run1.log).
// libunity calls several of these BEFORE il2cpp_init (icall registration, arguments, policy), so they
// are recorded here and applied once the Mono domain exists.
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "il2mono.h"

void* il2mono_domain;

typedef void (*Il2CppLogCallback)(const char*);
static Il2CppLogCallback g_log_callback;
static char* g_config_dir;
static char* g_data_dir;

static int g_argc;
static char** g_argv;
static int g_exception_policy = -1;
static size_t g_pool_region_size;

// Internal calls registered before il2cpp_init; Mono's icall table exists only after init.
typedef struct
{
    char* name;
    const void* method;
} PendingIcall;
static PendingIcall* g_pending;
static int g_pending_count, g_pending_cap;
static pthread_mutex_t g_icall_lock = PTHREAD_MUTEX_INITIALIZER;

static void set_str(char** dst, const char* src)
{
    free(*dst);
    *dst = src ? strdup(src) : NULL;
}

const char* il2mono_managed_dir(void)
{
    return il2mono_cfg.primary_managed_dir;
}

IL2MONO_API void il2cpp_register_log_callback(Il2CppLogCallback callback)
{
    FIRST_CALL();
    g_log_callback = callback;
}

IL2MONO_API void il2cpp_unity_set_android_network_up_state_func(void* func)
{
    FIRST_CALL();
}

// Unity's allocator hooks. Mono keeps using its own allocator; engine memory stats will not
// include managed metadata, which is fine for the PoC.
IL2MONO_API void il2cpp_set_memory_callbacks(void* callbacks)
{
    FIRST_CALL();
}

IL2MONO_API size_t il2cpp_memory_pool_get_region_size(void)
{
    FIRST_CALL();
    return g_pool_region_size;
}

IL2MONO_API void il2cpp_memory_pool_set_region_size(size_t size)
{
    FIRST_CALL();
    g_pool_region_size = size;
}

// ---- internal-call overrides -----------------------------------------------------------------
// Unity 6 bindings return strings as a ManagedSpanWrapper filled by the engine; managed code copies
// it and frees the buffer through BindingsAllocator.Free, so an override must allocate with the
// engine's BindingsAllocator.Malloc (captured when the engine registers it).
typedef struct
{
    void* begin;
    int32_t length;
} ManagedSpanWrapper;

typedef void (*GetPathFn)(ManagedSpanWrapper* ret);
typedef void* (*BindingsMallocFn)(int32_t size);
typedef void (*BindingsFreeFn)(void* ptr);

static GetPathFn g_orig_streaming_assets;
static GetPathFn g_orig_persistent_data;
static BindingsMallocFn g_bindings_malloc;
static BindingsFreeFn g_bindings_free;

// Answers a path icall with `path` instead of the engine's value, in the engine's own encoding and
// allocator. Falls back to the engine's answer when anything is missing.
static void answer_path(ManagedSpanWrapper* ret, GetPathFn orig_fn, const char* path, const char* what)
{
    ManagedSpanWrapper orig = {0};
    if (orig_fn)
        orig_fn(&orig);
    if (!path || !*path)
    {
        *ret = orig;
        return;
    }
    // Encoding check on the engine's own answer: UTF-16 has a 0 high byte.
    bool utf16 = orig.begin && orig.length > 1 && ((const uint8_t*)orig.begin)[1] == 0;
    size_t n = strlen(path);
    int32_t bytes = (int32_t)(utf16 ? n * 2 : n);
    void* buf = g_bindings_malloc ? g_bindings_malloc(bytes) : NULL;
    if (!buf)
    {
        *ret = orig;
        return;
    }
    if (utf16)
        for (size_t i = 0; i < n; i++)
            ((uint16_t*)buf)[i] = (uint8_t)path[i];
    else
        memcpy(buf, path, n);
    if (orig.begin && g_bindings_free)
        g_bindings_free(orig.begin);
    ret->begin = buf;
    ret->length = (int32_t)n;
    if (*what)
        LOGI("%s -> %s (%s)", what, path, utf16 ? "utf16" : "utf8");
}

// Bundles stay in the game instance (Linux layout): <instance>/valheim_Data/StreamingAssets.
static void streaming_assets_override(ManagedSpanWrapper* ret)
{
    static atomic_uchar logged;
    answer_path(ret, g_orig_streaming_assets, il2mono_cfg.streaming_assets,
                atomic_exchange(&logged, 1) ? "" : "streamingAssetsPath");
}

// Saves shared with the emulated launch: the instance's unity3d/<company>/<product> folder.
static void persistent_data_override(ManagedSpanWrapper* ret)
{
    static atomic_uchar logged;
    answer_path(ret, g_orig_persistent_data, il2mono_cfg.persistent_data,
                atomic_exchange(&logged, 1) ? "" : "persistentDataPath");
}

// The game is a desktop build: Valheim picks its graphics config by Application.platform and has
// none for Android ("Couldn't find any graphics configurations for the specified platform!").
// Managed code sees LinuxPlayer, matching the Linux data it runs on; the native engine is unaffected.
#define RUNTIME_PLATFORM_LINUX_PLAYER 13

// Keep the screen on: Unity applies Screen.sleepTimeout (SystemSetting by default) and clears the
// window's keep-screen-on flag. Every value the game sets becomes NeverSleep, and NeverSleep is
// applied once from the main thread (the first Application.platform query comes from there).
#define SLEEP_TIMEOUT_NEVER_SLEEP (-1)
typedef void (*SetSleepTimeoutFn)(int32_t value);
static SetSleepTimeoutFn g_orig_set_sleep_timeout;

static void set_sleep_timeout_override(int32_t value)
{
    if (g_orig_set_sleep_timeout)
        g_orig_set_sleep_timeout(SLEEP_TIMEOUT_NEVER_SLEEP);
}

// Desktop games use targetFrameRate -1 for "unlimited"; on Android -1 means the platform default,
// 30 fps. Map "unlimited" (<= 0) to 120, the S25's refresh rate; explicit limits pass through.
#define UNLIMITED_FRAME_RATE 120
typedef void (*SetTargetFrameRateFn)(int32_t value);
static SetTargetFrameRateFn g_orig_set_target_frame_rate;

static void set_target_frame_rate_override(int32_t value)
{
    int32_t applied = value <= 0 ? UNLIMITED_FRAME_RATE : value;
    static int32_t last = -2;
    if (applied != last)
    {
        LOGI("Application.targetFrameRate %d -> %d", value, applied);
        last = applied;
    }
    if (g_orig_set_target_frame_rate)
        g_orig_set_target_frame_rate(applied);
}

static int32_t platform_override(void)
{
    static atomic_uchar applied;
    if (g_orig_set_sleep_timeout && !atomic_exchange(&applied, 1))
    {
        g_orig_set_sleep_timeout(SLEEP_TIMEOUT_NEVER_SLEEP);
        LOGI("Screen.sleepTimeout -> NeverSleep");
    }
    return RUNTIME_PLATFORM_LINUX_PLAYER;
}

static const void* override_icall(const char* name, const void* method)
{
    if (!strcmp(name, "UnityEngine.Application::set_targetFrameRate"))
    {
        g_orig_set_target_frame_rate = (SetTargetFrameRateFn)method;
        LOGI("icall override: %s (unlimited -> %d)", name, UNLIMITED_FRAME_RATE);
        return (const void*)set_target_frame_rate_override;
    }
    if (!strcmp(name, "UnityEngine.Screen::set_sleepTimeout"))
    {
        g_orig_set_sleep_timeout = (SetSleepTimeoutFn)method;
        LOGI("icall override: %s -> NeverSleep", name);
        return (const void*)set_sleep_timeout_override;
    }
    if (!strcmp(name, "UnityEngine.Application::get_platform"))
    {
        LOGI("icall override: %s -> LinuxPlayer", name);
        return (const void*)platform_override;
    }
    if (!strcmp(name, "UnityEngine.Bindings.BindingsAllocator::Malloc"))
        g_bindings_malloc = (BindingsMallocFn)method;
    else if (!strcmp(name, "UnityEngine.Bindings.BindingsAllocator::Free"))
        g_bindings_free = (BindingsFreeFn)method;
    else if (!strcmp(name, "UnityEngine.Application::get_streamingAssetsPath_Injected"))
    {
        g_orig_streaming_assets = (GetPathFn)method;
        LOGI("icall override: %s", name);
        return (const void*)streaming_assets_override;
    }
    else if (!strcmp(name, "UnityEngine.Application::get_persistentDataPath_Injected"))
    {
        g_orig_persistent_data = (GetPathFn)method;
        LOGI("icall override: %s", name);
        return (const void*)persistent_data_override;
    }
    return method;
}

IL2MONO_API void il2cpp_add_internal_call(const char* name, const void* method)
{
    FIRST_CALL();
    method = override_icall(name, method);
    pthread_mutex_lock(&g_icall_lock);
    if (il2mono_domain)
    {
        pthread_mutex_unlock(&g_icall_lock);
        p_mono_add_internal_call(name, method);
        return;
    }
    if (g_pending_count == g_pending_cap)
    {
        g_pending_cap = g_pending_cap ? g_pending_cap * 2 : 1024;
        g_pending = realloc(g_pending, g_pending_cap * sizeof(*g_pending));
    }
    g_pending[g_pending_count].name = strdup(name);
    g_pending[g_pending_count].method = method;
    g_pending_count++;
    pthread_mutex_unlock(&g_icall_lock);
}

IL2MONO_API void il2cpp_runtime_unhandled_exception_policy_set(int policy)
{
    FIRST_CALL();
    g_exception_policy = policy;
    if (il2mono_domain)
        p_mono_runtime_unhandled_exception_policy_set(policy);
}

IL2MONO_API void il2cpp_set_commandline_arguments(int argc, const char* const argv[], const char* basedir)
{
    FIRST_CALL();
    g_argc = argc;
    g_argv = calloc(argc + 1, sizeof(char*));
    for (int i = 0; i < argc; i++)
        g_argv[i] = strdup(argv[i] ? argv[i] : "");
}

IL2MONO_API void il2cpp_set_config_dir(const char* path)
{
    FIRST_CALL();
    set_str(&g_config_dir, path);
}

IL2MONO_API void il2cpp_set_data_dir(const char* path)
{
    FIRST_CALL();
    set_str(&g_data_dir, path);
    LOGI("data dir: %s", path ? path : "(null)");
}

IL2MONO_API void il2cpp_debugger_set_agent_options(const char* options)
{
    FIRST_CALL();
}

IL2MONO_API void il2cpp_set_config(const char* executable_path)
{
    FIRST_CALL();
}

IL2MONO_API void il2cpp_override_stack_backtrace(void* callback)
{
    FIRST_CALL();
}

IL2MONO_API void il2cpp_set_find_plugin_callback(void* callback)
{
    FIRST_CALL();
    if (il2mono_domain)
        p_mono_set_find_plugin_callback(callback);
}

static void mono_log_to_logcat(const char* log_domain, const char* log_level, const char* message, int fatal, void* user_data)
{
    __android_log_print(fatal ? ANDROID_LOG_FATAL : ANDROID_LOG_INFO, "IL2MONO-mono", "[%s %s] %s",
                        log_domain ? log_domain : "", log_level ? log_level : "", message);
}

static void mono_print_to_logcat(const char* text, int is_stdout)
{
    __android_log_print(is_stdout ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, "IL2MONO-mono", "%s", text);
}

IL2MONO_API int il2cpp_init(const char* domain_name)
{
    FIRST_CALL();
    if (!il2mono_load_mono() || !il2mono_config_load(g_data_dir))
        return 0;
    LOGI("booting Mono: assemblies=%s config=%s", il2mono_cfg.managed_dirs, il2mono_cfg.etc_dir);

    // Games read files relative to the working directory (Valheim: steam_appid.txt), which is
    // the game folder on desktop but "/" in an Android app process.
    if (il2mono_cfg.cwd[0] && chdir(il2mono_cfg.cwd) != 0)
        LOGW("chdir(%s) failed", il2mono_cfg.cwd);

    p_mono_trace_set_log_handler(mono_log_to_logcat, NULL);
    p_mono_trace_set_print_handler(mono_print_to_logcat);
    p_mono_trace_set_printerr_handler(mono_print_to_logcat);
    p_mono_set_dirs(il2mono_cfg.primary_managed_dir, il2mono_cfg.etc_dir);
    p_mono_set_assemblies_path(il2mono_cfg.managed_dirs);
    p_mono_config_parse(NULL);

    void* domain = p_mono_jit_init_version(domain_name ? domain_name : "IL2CPP Root Domain", "v4.0.30319");
    if (!domain)
    {
        LOGE("mono_jit_init_version failed");
        return 0;
    }

    pthread_mutex_lock(&g_icall_lock);
    for (int i = 0; i < g_pending_count; i++)
    {
        p_mono_add_internal_call(g_pending[i].name, g_pending[i].method);
        free(g_pending[i].name);
    }
    LOGI("registered %d internal calls queued before init", g_pending_count);
    free(g_pending);
    g_pending = NULL;
    g_pending_count = g_pending_cap = 0;
    il2mono_domain = domain;
    pthread_mutex_unlock(&g_icall_lock);

    il2mono_register_pad_icalls();
    if (g_exception_policy >= 0)
        p_mono_runtime_unhandled_exception_policy_set(g_exception_policy);
    if (g_argc > 0)
        p_mono_runtime_set_main_args(g_argc, g_argv);

    LOGI("Mono domain up: %p", domain);
    return 1;
}

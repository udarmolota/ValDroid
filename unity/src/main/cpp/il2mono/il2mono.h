// il2mono: a libil2cpp.so that serves Unity's Android libunity.so from our ARM64 Mono.
// Handle model is one-to-one: Il2CppClass* == MonoClass*, MethodInfo* == MonoMethod*, objects are
// Mono objects, and so on. See docs/research/unity-arm64-stepC-plan.md in the ValDroid repo.
#pragma once

#include <android/log.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#include "gen/api_count.h"

#define IL2MONO_TAG "IL2MONO"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, IL2MONO_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, IL2MONO_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, IL2MONO_TAG, __VA_ARGS__)

#define IL2MONO_API __attribute__((visibility("default")))

// Logs the first call of the enclosing API function (call order is the main debugging signal).
#define FIRST_CALL()                                          \
    do                                                        \
    {                                                         \
        static atomic_uchar seen_;                            \
        if (!atomic_exchange(&seen_, 1))                      \
            il2mono_first_call(__func__);                     \
    } while (0)

extern const char* const il2mono_api_names[IL2MONO_API_COUNT];
void il2mono_unimplemented(int index);
void il2mono_first_call(const char* name);

// ---- Mono, resolved at run time from libmonobdwgc-2.0.so -------------------------------------
// Handles are opaque (void*); gboolean is int.
typedef void (*MonoLogCallback)(const char* log_domain, const char* log_level, const char* message, int fatal, void* user_data);
typedef void (*MonoPrintCallback)(const char* string, int is_stdout);

#define IL2MONO_MONO_FUNCS(X)                                                                 \
    X(char*, mono_get_runtime_build_info, (void))                                             \
    X(void, mono_free, (void* ptr))                                                           \
    X(void, mono_set_dirs, (const char* assembly_dir, const char* config_dir))                \
    X(void, mono_set_assemblies_path, (const char* path))                                     \
    X(void, mono_config_parse, (const char* filename))                                        \
    X(void*, mono_jit_init_version, (const char* domain_name, const char* runtime_version))   \
    X(int, mono_runtime_set_main_args, (int argc, char* argv[]))                              \
    X(void, mono_runtime_unhandled_exception_policy_set, (int policy))                        \
    X(void, mono_trace_set_log_handler, (MonoLogCallback callback, void* user_data))          \
    X(void, mono_trace_set_print_handler, (MonoPrintCallback callback))                       \
    X(void, mono_trace_set_printerr_handler, (MonoPrintCallback callback))                    \
    X(void, mono_add_internal_call, (const char* name, const void* method))                   \
    X(void, mono_set_find_plugin_callback, (void* callback))                                  \
    X(void*, mono_domain_get, (void))                                                         \
    X(void*, mono_thread_current, (void))                                                     \
    X(void*, mono_thread_internal_current, (void))                                            \
    X(void*, mono_custom_attrs_construct, (void* ainfo))                                      \
    X(uintptr_t, mono_array_length, (void* array))                                            \
    X(void*, mono_signature_get_params, (void* sig, void** iter))                             \
    X(int, mono_type_is_byref, (void* type))                                                  \
    X(void*, mono_class_get_element_class, (void* klass))                                     \
    X(void, mono_gchandle_free_v2, (void* gchandle))                                          \
    X(void, mono_unity_install_unitytls_interface, (const void* unitytls))                   \
    X(void*, mono_thread_attach, (void* domain))                                              \
    X(void, mono_gc_wbarrier_generic_store, (void* ptr, void* value))                         \
    X(void*, mono_domain_assembly_open, (void* domain, const char* name))                     \
    X(void*, mono_assembly_get_image, (void* assembly))                                       \
    X(const char*, mono_image_get_name, (void* image))                                        \
    X(int, mono_image_get_table_rows, (void* image, int table_id))                            \
    X(void*, mono_class_get, (void* image, uint32_t type_token))                              \
    X(void*, mono_get_corlib, (void))                                                         \
    X(void*, mono_class_from_name, (void* image, const char* name_space, const char* name))  \
    X(void*, mono_class_get_methods, (void* klass, void** iter))                              \
    X(void*, mono_class_get_fields, (void* klass, void** iter))                               \
    X(void*, mono_class_get_nested_types, (void* klass, void** iter))                         \
    X(void*, mono_class_get_parent, (void* klass))                                            \
    X(void*, mono_class_get_nesting_type, (void* klass))                                      \
    X(const char*, mono_class_get_name, (void* klass))                                        \
    X(const char*, mono_class_get_namespace, (void* klass))                                   \
    X(void*, mono_class_get_image, (void* klass))                                             \
    X(int, mono_class_is_subclass_of, (void* klass, void* parent, int check_interfaces))      \
    X(int, mono_class_is_enum, (void* klass))                                                 \
    X(int, mono_class_is_generic, (void* klass))                                              \
    X(int, mono_class_is_inflated, (void* klass))                                             \
    X(int, mono_unity_class_is_abstract, (void* klass))                                       \
    X(int, mono_class_array_element_size, (void* klass))                                      \
    X(void*, mono_class_get_field_from_name, (void* klass, const char* name))                 \
    X(void*, mono_class_get_method_from_name, (void* klass, const char* name, int param_count)) \
    X(int, mono_class_get_userdata_offset, (void))                                            \
    X(void, mono_class_set_userdata, (void* klass, void* userdata))                           \
    X(void*, mono_class_from_mono_type, (void* type))                                         \
    X(void*, mono_reflection_type_get_type, (void* reftype))                                  \
    X(int, mono_type_get_type, (void* type))                                                  \
    X(const char*, mono_method_get_name, (void* method))                                      \
    X(uint32_t, mono_method_get_flags, (void* method, uint32_t* iflags))                      \
    X(void*, mono_method_signature, (void* method))                                           \
    X(uint32_t, mono_signature_get_param_count, (void* sig))                                  \
    X(void*, mono_signature_get_return_type, (void* sig))                                     \
    X(int, unity_mono_method_is_inflated, (void* method))                                     \
    X(int, unity_mono_method_is_generic, (void* method))                                      \
    X(const char*, mono_field_get_name, (void* field))                                        \
    X(void*, mono_field_get_type, (void* field))                                              \
    X(void*, mono_field_get_parent, (void* field))                                            \
    X(uint32_t, mono_field_get_flags, (void* field))                                          \
    X(uint32_t, mono_field_get_offset, (void* field))                                         \
    X(void, mono_field_get_value, (void* obj, void* field, void* value))                      \
    X(void*, mono_custom_attrs_from_class, (void* klass))                                     \
    X(void*, mono_custom_attrs_from_field, (void* klass, void* field))                        \
    X(int, mono_custom_attrs_has_attr, (void* ainfo, void* attr_klass))                       \
    X(void*, mono_custom_attrs_get_attr, (void* ainfo, void* attr_klass))                     \
    X(void, mono_custom_attrs_free, (void* ainfo))                                            \
    X(void*, mono_runtime_invoke, (void* method, void* obj, void** params, void** exc))       \
    X(void*, mono_object_new, (void* domain, void* klass))                                    \
    X(void*, mono_object_get_class, (void* obj))                                              \
    X(void*, mono_object_to_string, (void* obj, void** exc))                                  \
    X(char*, mono_string_to_utf8, (void* str))                                                \
    X(void, mono_domain_set_config, (void* domain, const char* base_dir, const char* config)) \
    X(void*, mono_array_class_get, (void* element_class, uint32_t rank))                      \
    X(void*, mono_array_new, (void* domain, void* element_class, uintptr_t n))                \
    X(void*, mono_string_new_len, (void* domain, const char* text, unsigned int length))     \
    X(void*, mono_string_new_wrapper, (const char* text))                                     \
    X(int, mono_string_length, (void* str))                                                   \
    X(uint16_t*, mono_string_chars, (void* str))                                              \
    X(void*, mono_gchandle_new_v2, (void* obj, int pinned))                                   \
    X(void, mono_gc_collect, (int generation))                                                \
    X(int, mono_gc_is_incremental, (void))                                                    \
    X(int64_t, mono_gc_get_max_time_slice_ns, (void))                                         \
    X(void, mono_gc_set_max_time_slice_ns, (int64_t max_time_slice_ns))                       \
    X(int, mono_gc_collect_a_little, (void))

#define X(ret, name, params) extern ret(*p_##name) params;
IL2MONO_MONO_FUNCS(X)
#undef X

// Loads libmonobdwgc-2.0.so and resolves IL2MONO_MONO_FUNCS. Returns false (and logs why) on failure.
bool il2mono_load_mono(void);

// The Mono domain once il2cpp_init succeeded, else NULL.
extern void* il2mono_domain;

// ---- configuration (config.c) ----------------------------------------------------------------
typedef struct
{
    char managed_dirs[2048];        // ':'-separated, searched in order (Android overrides first)
    char primary_managed_dir[512];  // first entry; holds mscorlib
    char etc_dir[512];              // Mono config root (contains mono/config)
    char cwd[512];                  // working directory (Valheim reads steam_appid.txt from it)
    char streaming_assets[512];     // Application.streamingAssetsPath
    char persistent_data[512];      // Application.persistentDataPath, empty = engine default
} Il2MonoConfig;

extern Il2MonoConfig il2mono_cfg;
bool il2mono_config_load(const char* data_dir);
bool il2mono_find_assembly(const char* file_name, char* out, size_t out_size);

// Registers the ValDroidBridge internal calls (pad.c: gamepad, keyboard/mouse, game state);
// called right after the domain is up.
void il2mono_register_input_icalls(void);

// Starts BepInEx in the new root domain when VALDROID_BEPINEX_PRELOADER is set (bepinex.c).
void il2mono_bepinex_boot(void* domain);

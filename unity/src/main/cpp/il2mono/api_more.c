// The remaining functions libunity references (static scan, docs/research/unity-android-arm64-libunity-6000.md).
// Stack-frame walking and memory snapshots stay as logging stubs: only the debugger/profiler use them.
#include <stdlib.h>
#include <string.h>

#include "il2mono.h"

// Mono functions used only here (resolved by name at first use; all checked present in our build).
#include <dlfcn.h>
static void* sym(const char* name)
{
    static void* lib;
    if (!lib)
        lib = dlopen("libmonobdwgc-2.0.so", RTLD_NOW | RTLD_NOLOAD);
    void* p = lib ? dlsym(lib, name) : NULL;
    if (!p)
        LOGE("Mono export missing: %s", name);
    return p;
}
#define MONO(ret, name, params) \
    static ret(*name##_p) params; \
    if (!name##_p) name##_p = (ret(*) params)sym(#name)

#define MONO_TYPE_NAME_FORMAT_IL 0
#define MONO_TYPE_NAME_FORMAT_REFLECTION 1
#define MONO_TYPE_NAME_FORMAT_ASSEMBLY_QUALIFIED 3

// ---- arrays, classes ---------------------------------------------------------------------------

IL2MONO_API int il2cpp_array_element_size(const void* array_class)
{
    FIRST_CALL();
    MONO(int, mono_array_element_size, (void*));
    return mono_array_element_size_p((void*)array_class);
}

IL2MONO_API void* il2cpp_array_new_full(void* array_class, uintptr_t* lengths, intptr_t* lower_bounds)
{
    FIRST_CALL();
    MONO(void*, mono_array_new_full, (void*, void*, uintptr_t*, intptr_t*));
    return mono_array_new_full_p(il2mono_domain, array_class, lengths, lower_bounds);
}

IL2MONO_API const void* il2cpp_class_enum_basetype(void* klass)
{
    FIRST_CALL();
    MONO(void*, mono_class_enum_basetype, (void*));
    return mono_class_enum_basetype_p(klass);
}

IL2MONO_API void* il2cpp_class_get_element_class(void* klass)
{
    FIRST_CALL();
    return p_mono_class_get_element_class(klass);
}

IL2MONO_API int il2cpp_class_get_flags(const void* klass)
{
    FIRST_CALL();
    MONO(uint32_t, mono_class_get_flags, (void*));
    return (int)mono_class_get_flags_p((void*)klass);
}

IL2MONO_API int il2cpp_class_get_rank(const void* klass)
{
    FIRST_CALL();
    MONO(int, mono_class_get_rank, (void*));
    return mono_class_get_rank_p((void*)klass);
}

IL2MONO_API const void* il2cpp_class_get_type(void* klass)
{
    FIRST_CALL();
    MONO(void*, mono_class_get_type, (void*));
    return mono_class_get_type_p(klass);
}

IL2MONO_API int32_t il2cpp_class_instance_size(void* klass)
{
    FIRST_CALL();
    MONO(int32_t, mono_class_instance_size, (void*));
    return mono_class_instance_size_p(klass);
}

IL2MONO_API bool il2cpp_class_is_blittable(const void* klass)
{
    FIRST_CALL();
    MONO(int, mono_class_is_blittable, (void*));
    return mono_class_is_blittable_p((void*)klass) != 0;
}

IL2MONO_API bool il2cpp_class_is_interface(const void* klass)
{
    FIRST_CALL();
    MONO(int, mono_unity_class_is_interface, (void*));
    return mono_unity_class_is_interface_p((void*)klass) != 0;
}

IL2MONO_API bool il2cpp_class_is_valuetype(const void* klass)
{
    FIRST_CALL();
    MONO(int, mono_class_is_valuetype, (void*));
    return mono_class_is_valuetype_p((void*)klass) != 0;
}

// ---- custom attributes, reflection -------------------------------------------------------------

IL2MONO_API void* il2cpp_custom_attrs_from_method(const void* method)
{
    FIRST_CALL();
    MONO(void*, mono_custom_attrs_from_method, (void*));
    return mono_custom_attrs_from_method_p((void*)method);
}

IL2MONO_API bool il2cpp_method_has_attribute(const void* method, void* attr_class)
{
    FIRST_CALL();
    MONO(void*, mono_custom_attrs_from_method, (void*));
    void* ainfo = mono_custom_attrs_from_method_p((void*)method);
    if (!ainfo)
        return false;
    bool has = p_mono_custom_attrs_has_attr(ainfo, attr_class) != 0;
    p_mono_custom_attrs_free(ainfo);
    return has;
}

// MonoReflectionField: object header (16 bytes), MonoClass* klass @0x10, MonoClassField* field @0x18.
// libunity itself reads RuntimeFieldInfo.field at 0x18 inline (stage 1), so the layouts agree.
IL2MONO_API const void* il2cpp_field_get_from_reflection(const void* reflection_field)
{
    FIRST_CALL();
    return reflection_field ? *(void* const*)((const char*)reflection_field + 0x18) : NULL;
}

IL2MONO_API void* il2cpp_field_get_object(void* field, void* refclass)
{
    FIRST_CALL();
    MONO(void*, mono_field_get_object, (void*, void*, void*));
    return mono_field_get_object_p(il2mono_domain, refclass ? refclass : p_mono_field_get_parent(field), field);
}

IL2MONO_API void* il2cpp_field_get_parent(void* field)
{
    FIRST_CALL();
    return p_mono_field_get_parent(field);
}

IL2MONO_API void* il2cpp_method_get_class(const void* method)
{
    FIRST_CALL();
    MONO(void*, mono_method_get_class, (void*));
    return mono_method_get_class_p((void*)method);
}

IL2MONO_API const void* il2cpp_method_get_from_reflection(const void* reflection_method)
{
    FIRST_CALL();
    MONO(void*, unity_mono_reflection_method_get_method, (void*));
    return reflection_method ? unity_mono_reflection_method_get_method_p((void*)reflection_method) : NULL;
}

IL2MONO_API void* il2cpp_method_get_object(const void* method, void* refclass)
{
    FIRST_CALL();
    MONO(void*, mono_method_get_object, (void*, void*, void*));
    return mono_method_get_object_p(il2mono_domain, (void*)method, refclass);
}

IL2MONO_API const void* il2cpp_object_get_virtual_method(void* obj, const void* method)
{
    FIRST_CALL();
    MONO(void*, mono_object_get_virtual_method, (void*, void*));
    return mono_object_get_virtual_method_p(obj, (void*)method);
}

IL2MONO_API void* il2cpp_object_unbox(void* obj)
{
    FIRST_CALL();
    MONO(void*, mono_object_unbox, (void*));
    return mono_object_unbox_p(obj);
}

IL2MONO_API void* il2cpp_type_get_object(const void* type)
{
    FIRST_CALL();
    MONO(void*, mono_type_get_object, (void*, void*));
    return mono_type_get_object_p(il2mono_domain, (void*)type);
}

// ---- types -------------------------------------------------------------------------------------

IL2MONO_API bool il2cpp_type_equals(const void* a, const void* b)
{
    FIRST_CALL();
    MONO(int, mono_metadata_type_equal, (void*, void*));
    return mono_metadata_type_equal_p((void*)a, (void*)b) != 0;
}

// Returned strings are freed by libunity with il2cpp_free (= mono_free here).
IL2MONO_API char* il2cpp_type_get_name(const void* type)
{
    FIRST_CALL();
    MONO(char*, mono_type_get_name_full, (void*, int));
    return mono_type_get_name_full_p((void*)type, MONO_TYPE_NAME_FORMAT_IL);
}

IL2MONO_API char* il2cpp_type_get_reflection_name(const void* type)
{
    FIRST_CALL();
    MONO(char*, mono_type_get_name_full, (void*, int));
    return mono_type_get_name_full_p((void*)type, MONO_TYPE_NAME_FORMAT_REFLECTION);
}

IL2MONO_API char* il2cpp_type_get_assembly_qualified_name(const void* type)
{
    FIRST_CALL();
    MONO(char*, mono_type_get_name_full, (void*, int));
    return mono_type_get_name_full_p((void*)type, MONO_TYPE_NAME_FORMAT_ASSEMBLY_QUALIFIED);
}

IL2MONO_API bool il2cpp_type_is_pointer_type(const void* type)
{
    FIRST_CALL();
    MONO(int, mono_unity_type_is_pointer_type, (void*));
    return mono_unity_type_is_pointer_type_p((void*)type) != 0;
}

IL2MONO_API bool il2cpp_type_is_static(const void* type)
{
    FIRST_CALL();
    MONO(int, mono_unity_type_is_static, (void*));
    return mono_unity_type_is_static_p((void*)type) != 0;
}

IL2MONO_API void il2cpp_free(void* ptr)
{
    FIRST_CALL();
    if (ptr)
        p_mono_free(ptr);
}

// ---- images ------------------------------------------------------------------------------------

IL2MONO_API const char* il2cpp_image_get_name(const void* image)
{
    FIRST_CALL();
    return p_mono_image_get_name((void*)image);
}

IL2MONO_API const char* il2cpp_image_get_filename(const void* image)
{
    FIRST_CALL();
    MONO(const char*, mono_image_get_filename, (void*));
    return mono_image_get_filename_p((void*)image);
}

IL2MONO_API const void* il2cpp_image_get_entry_point(const void* image)
{
    FIRST_CALL();
    MONO(uint32_t, mono_image_get_entry_point, (void*));
    MONO(void*, mono_get_method, (void*, uint32_t, void*));
    uint32_t token = mono_image_get_entry_point_p((void*)image);
    return token ? mono_get_method_p((void*)image, token, NULL) : NULL;
}

// ---- exceptions --------------------------------------------------------------------------------

IL2MONO_API void* il2cpp_exception_from_name_msg(const void* image, const char* ns, const char* name, const char* msg)
{
    FIRST_CALL();
    MONO(void*, mono_exception_from_name_msg, (void*, const char*, const char*, const char*));
    return mono_exception_from_name_msg_p((void*)image, ns, name, msg);
}

IL2MONO_API void* il2cpp_get_exception_argument_null(const char* arg)
{
    FIRST_CALL();
    MONO(void*, mono_get_exception_argument_null, (const char*));
    return mono_get_exception_argument_null_p(arg);
}

IL2MONO_API void il2cpp_raise_exception(void* exc)
{
    FIRST_CALL();
    MONO(void, mono_raise_exception, (void*));
    mono_raise_exception_p(exc);
}

// IL2CPP: params are boxed objects; value types are unboxed, by-ref slots point into the array,
// a value-type `this` is unboxed (Runtime::InvokeConvertArgs).
IL2MONO_API void* il2cpp_runtime_invoke_convert_args(const void* method, void* obj, void** params, int count, void** exc)
{
    FIRST_CALL();
    MONO(int, mono_class_is_valuetype, (void*));
    MONO(void*, mono_object_unbox, (void*));
    MONO(void*, mono_method_get_class, (void*));
    void* sig = p_mono_method_signature((void*)method);
    void** args = count > 0 ? alloca(sizeof(void*) * count) : NULL;
    void* iter = NULL;
    for (int i = 0; i < count; i++)
    {
        void* type = p_mono_signature_get_params(sig, &iter);
        void* klass = type ? p_mono_class_from_mono_type(type) : NULL;
        bool valuetype = klass && mono_class_is_valuetype_p(klass);
        if (valuetype)
            args[i] = params[i] ? mono_object_unbox_p(params[i]) : NULL;
        else if (type && p_mono_type_is_byref(type))
            args[i] = &params[i];
        else
            args[i] = params[i];
    }
    if (obj && mono_class_is_valuetype_p(mono_method_get_class_p((void*)method)))
        obj = mono_object_unbox_p(obj);
    return p_mono_runtime_invoke((void*)method, obj, args, exc);
}

// ---- strings -----------------------------------------------------------------------------------

IL2MONO_API void* il2cpp_string_new_utf16(const uint16_t* text, int32_t len)
{
    FIRST_CALL();
    MONO(void*, mono_string_new_utf16, (void*, const uint16_t*, int32_t));
    return mono_string_new_utf16_p(il2mono_domain, text, len);
}

IL2MONO_API void* il2cpp_string_intern(void* str)
{
    FIRST_CALL();
    MONO(void*, mono_string_intern, (void*));
    return mono_string_intern_p(str);
}

IL2MONO_API void* il2cpp_string_is_interned(void* str)
{
    FIRST_CALL();
    MONO(void*, mono_string_is_interned, (void*));
    return mono_string_is_interned_p(str);
}

// ---- threads, GC -------------------------------------------------------------------------------

IL2MONO_API void il2cpp_thread_detach(void* thread)
{
    FIRST_CALL();
    MONO(void, mono_thread_detach, (void*));
    if (thread)
        mono_thread_detach_p(thread);
}

// IL2CPP: true for runtime-internal threads (finalizer etc.); libunity skips those when walking.
IL2MONO_API bool il2cpp_is_vm_thread(void* thread)
{
    FIRST_CALL();
    return false;
}

IL2MONO_API void* il2cpp_gchandle_get_target(void* gchandle)
{
    FIRST_CALL();
    MONO(void*, mono_gchandle_get_target_v2, (void*));
    return gchandle ? mono_gchandle_get_target_v2_p(gchandle) : NULL;
}

IL2MONO_API void* il2cpp_gchandle_new_weakref(void* obj, bool track_resurrection)
{
    FIRST_CALL();
    MONO(void*, mono_gchandle_new_weakref_v2, (void*, int));
    return mono_gchandle_new_weakref_v2_p(obj, track_resurrection);
}

IL2MONO_API void il2cpp_gc_disable(void)
{
    FIRST_CALL();
    MONO(void, mono_unity_gc_disable, (void));
    mono_unity_gc_disable_p();
}

IL2MONO_API void il2cpp_gc_enable(void)
{
    FIRST_CALL();
    MONO(void, mono_unity_gc_enable, (void));
    mono_unity_gc_enable_p();
}

IL2MONO_API int64_t il2cpp_gc_get_heap_size(void)
{
    FIRST_CALL();
    MONO(int64_t, mono_gc_get_heap_size, (void));
    return mono_gc_get_heap_size_p();
}

IL2MONO_API int64_t il2cpp_gc_get_used_size(void)
{
    FIRST_CALL();
    MONO(int64_t, mono_gc_get_used_size, (void));
    return mono_gc_get_used_size_p();
}

IL2MONO_API void il2cpp_gc_set_mode(int mode)
{
    FIRST_CALL();
    MONO(void, mono_unity_gc_set_mode, (int));
    mono_unity_gc_set_mode_p(mode);
}

IL2MONO_API void il2cpp_gc_start_incremental_collection(void)
{
    FIRST_CALL();
    MONO(void, mono_gc_start_incremental_collection, (void));
    mono_gc_start_incremental_collection_p();
}

IL2MONO_API void il2cpp_stop_gc_world(void)
{
    FIRST_CALL();
    MONO(void, mono_unity_stop_gc_world, (void));
    mono_unity_stop_gc_world_p();
}

IL2MONO_API void il2cpp_start_gc_world(void)
{
    FIRST_CALL();
    MONO(void, mono_unity_start_gc_world, (void));
    mono_unity_start_gc_world_p();
}

// Liveness (Resources.UnloadUnusedAssets): IL2CPP's API mirrors Unity-Mono's one-to-one.
IL2MONO_API void* il2cpp_unity_liveness_allocate_struct(void* filter, int max_object_count, void* callback, void* userdata, void* reallocate)
{
    FIRST_CALL();
    MONO(void*, mono_unity_liveness_allocate_struct, (void*, int, void*, void*, void*));
    return mono_unity_liveness_allocate_struct_p(filter, max_object_count, callback, userdata, reallocate);
}

IL2MONO_API void il2cpp_unity_liveness_calculation_from_root(void* root, void* state)
{
    FIRST_CALL();
    MONO(void, mono_unity_liveness_calculation_from_root, (void*, void*));
    mono_unity_liveness_calculation_from_root_p(root, state);
}

IL2MONO_API void il2cpp_unity_liveness_calculation_from_statics(void* state)
{
    FIRST_CALL();
    MONO(void, mono_unity_liveness_calculation_from_statics, (void*));
    mono_unity_liveness_calculation_from_statics_p(state);
}

IL2MONO_API void il2cpp_unity_liveness_finalize(void* state)
{
    FIRST_CALL();
    MONO(void, mono_unity_liveness_finalize, (void*));
    mono_unity_liveness_finalize_p(state);
}

IL2MONO_API void il2cpp_unity_liveness_free_struct(void* state)
{
    FIRST_CALL();
    MONO(void, mono_unity_liveness_free_struct, (void*));
    mono_unity_liveness_free_struct_p(state);
}

// Engine shutdown: leave the runtime to process exit (Unity does not restart it in-process).
IL2MONO_API void il2cpp_shutdown(void)
{
    FIRST_CALL();
}

// The rest of the start-up API (spy/spy_run1.log #17..#79), mapped onto Mono one-to-one.
// Il2Cpp handle == Mono handle; see il2mono.h.
#include <stdio.h>
#include <string.h>

#include "il2mono.h"


#define METHOD_ATTRIBUTE_STATIC 0x0010
#define MONO_TABLE_TYPEDEF 2
#define MONO_TOKEN_TYPE_DEF 0x02000000

// ---- domain, threads, GC ---------------------------------------------------------------------

IL2MONO_API void* il2cpp_domain_get(void)
{
    FIRST_CALL();
    return p_mono_domain_get();
}

// IL2CPP returns NULL on a thread that is not attached (libunity relies on it to decide whether
// to attach); mono_thread_current() asserts instead, so check the internal thread first.
IL2MONO_API void* il2cpp_thread_current(void)
{
    FIRST_CALL();
    if (!p_mono_thread_internal_current())
        return NULL;
    return p_mono_thread_current();
}

IL2MONO_API void* il2cpp_thread_attach(void* domain)
{
    FIRST_CALL();
    return p_mono_thread_attach(domain ? domain : il2mono_domain);
}

IL2MONO_API void il2cpp_gc_wbarrier_set_field(void* obj, void** target, void* value)
{
    FIRST_CALL();
    p_mono_gc_wbarrier_generic_store(target, value);
}

IL2MONO_API void* il2cpp_gchandle_new(void* obj, bool pinned)
{
    FIRST_CALL();
    return p_mono_gchandle_new_v2(obj, pinned);
}

IL2MONO_API void il2cpp_gchandle_free(void* gchandle)
{
    FIRST_CALL();
    if (gchandle)
        p_mono_gchandle_free_v2(gchandle);
}

IL2MONO_API void il2cpp_unity_install_unitytls_interface(const void* unitytls)
{
    FIRST_CALL();
    p_mono_unity_install_unitytls_interface(unitytls);
}

IL2MONO_API void il2cpp_gc_collect(int generation)
{
    FIRST_CALL();
    p_mono_gc_collect(generation);
}

IL2MONO_API bool il2cpp_gc_is_incremental(void)
{
    FIRST_CALL();
    return p_mono_gc_is_incremental() != 0;
}

IL2MONO_API int64_t il2cpp_gc_get_max_time_slice_ns(void)
{
    FIRST_CALL();
    return p_mono_gc_get_max_time_slice_ns();
}

IL2MONO_API void il2cpp_gc_set_max_time_slice_ns(int64_t ns)
{
    FIRST_CALL();
    p_mono_gc_set_max_time_slice_ns(ns);
}

IL2MONO_API bool il2cpp_gc_collect_a_little(void)
{
    FIRST_CALL();
    return p_mono_gc_collect_a_little() != 0;
}

// ---- assemblies and images -------------------------------------------------------------------

// IL2CPP resolves by assembly name, with or without ".dll"; Mono needs a file path. The managed dirs
// are searched in order, so the Android builds of engine/package assemblies win over the game's
// Linux copies.
IL2MONO_API const void* il2cpp_domain_assembly_open(void* domain, const char* name)
{
    FIRST_CALL();
    if (!name || !*name)
        return NULL;
    char file[256], path[768];
    size_t len = strlen(name);
    bool has_ext = len > 4 && (!strcasecmp(name + len - 4, ".dll") || !strcasecmp(name + len - 4, ".exe"));
    snprintf(file, sizeof(file), "%s%s", name, has_ext ? "" : ".dll");
    if (!il2mono_find_assembly(file, path, sizeof(path)))
    {
        LOGW("assembly not found in %s: %s", il2mono_cfg.managed_dirs, file);
        return NULL;
    }
    return p_mono_domain_assembly_open(domain ? domain : il2mono_domain, path);
}

IL2MONO_API const void* il2cpp_assembly_get_image(const void* assembly)
{
    FIRST_CALL();
    return assembly ? p_mono_assembly_get_image((void*)assembly) : NULL;
}

IL2MONO_API const void* il2cpp_get_corlib(void)
{
    FIRST_CALL();
    return p_mono_get_corlib();
}

// Both count the <Module> pseudo-type, so the index ranges match.
IL2MONO_API size_t il2cpp_image_get_class_count(const void* image)
{
    FIRST_CALL();
    return image ? (size_t)p_mono_image_get_table_rows((void*)image, MONO_TABLE_TYPEDEF) : 0;
}

IL2MONO_API const void* il2cpp_image_get_class(const void* image, size_t index)
{
    FIRST_CALL();
    return p_mono_class_get((void*)image, MONO_TOKEN_TYPE_DEF | (uint32_t)(index + 1));
}

// ---- classes ---------------------------------------------------------------------------------

IL2MONO_API void* il2cpp_class_from_name(const void* image, const char* ns, const char* name)
{
    FIRST_CALL();
    return image ? p_mono_class_from_name((void*)image, ns, name) : NULL;
}

IL2MONO_API const void* il2cpp_class_get_methods(void* klass, void** iter)
{
    FIRST_CALL();
    return p_mono_class_get_methods(klass, iter);
}

IL2MONO_API void* il2cpp_class_get_fields(void* klass, void** iter)
{
    FIRST_CALL();
    return p_mono_class_get_fields(klass, iter);
}

IL2MONO_API void* il2cpp_class_get_nested_types(void* klass, void** iter)
{
    FIRST_CALL();
    return p_mono_class_get_nested_types(klass, iter);
}

IL2MONO_API void* il2cpp_class_get_parent(void* klass)
{
    FIRST_CALL();
    return p_mono_class_get_parent(klass);
}

IL2MONO_API void* il2cpp_class_get_declaring_type(void* klass)
{
    FIRST_CALL();
    return p_mono_class_get_nesting_type(klass);
}

IL2MONO_API const char* il2cpp_class_get_name(void* klass)
{
    FIRST_CALL();
    return p_mono_class_get_name(klass);
}

IL2MONO_API const char* il2cpp_class_get_namespace(void* klass)
{
    FIRST_CALL();
    return p_mono_class_get_namespace(klass);
}

IL2MONO_API const void* il2cpp_class_get_image(void* klass)
{
    FIRST_CALL();
    return p_mono_class_get_image(klass);
}

// IL2CPP: the assembly name without extension; Mono's image name is exactly that.
IL2MONO_API const char* il2cpp_class_get_assemblyname(const void* klass)
{
    FIRST_CALL();
    return p_mono_image_get_name(p_mono_class_get_image((void*)klass));
}

IL2MONO_API bool il2cpp_class_is_subclass_of(void* klass, void* parent, bool check_interfaces)
{
    FIRST_CALL();
    return p_mono_class_is_subclass_of(klass, parent, check_interfaces) != 0;
}

IL2MONO_API bool il2cpp_class_is_abstract(const void* klass)
{
    FIRST_CALL();
    return p_mono_unity_class_is_abstract((void*)klass) != 0;
}

IL2MONO_API bool il2cpp_class_is_generic(const void* klass)
{
    FIRST_CALL();
    return p_mono_class_is_generic((void*)klass) != 0;
}

IL2MONO_API bool il2cpp_class_is_inflated(const void* klass)
{
    FIRST_CALL();
    return p_mono_class_is_inflated((void*)klass) != 0;
}

IL2MONO_API bool il2cpp_class_is_enum(const void* klass)
{
    FIRST_CALL();
    return p_mono_class_is_enum((void*)klass) != 0;
}

IL2MONO_API int il2cpp_class_array_element_size(const void* klass)
{
    FIRST_CALL();
    return p_mono_class_array_element_size((void*)klass);
}

IL2MONO_API void* il2cpp_class_get_field_from_name(void* klass, const char* name)
{
    FIRST_CALL();
    return p_mono_class_get_field_from_name(klass, name);
}

IL2MONO_API int il2cpp_class_get_userdata_offset(void)
{
    FIRST_CALL();
    int offset = p_mono_class_get_userdata_offset();
    LOGI("class userdata offset (Mono): %d", offset);
    return offset;
}

IL2MONO_API void il2cpp_class_set_userdata(void* klass, void* userdata)
{
    FIRST_CALL();
    p_mono_class_set_userdata(klass, userdata);
}

IL2MONO_API void* il2cpp_class_from_type(const void* type)
{
    FIRST_CALL();
    return type ? p_mono_class_from_mono_type((void*)type) : NULL;
}

#define MONO_TYPE_ARRAY 0x14
#define MONO_TYPE_SZARRAY 0x1d

// IL2CPP mimics an old Mono quirk: the element class for arrays, the class itself otherwise.
IL2MONO_API void* il2cpp_type_get_class_or_element_class(const void* type)
{
    FIRST_CALL();
    if (!type)
        return NULL;
    void* klass = p_mono_class_from_mono_type((void*)type);
    int t = p_mono_type_get_type((void*)type);
    if (klass && (t == MONO_TYPE_ARRAY || t == MONO_TYPE_SZARRAY))
        return p_mono_class_get_element_class(klass);
    return klass;
}

// IL2CPP Class::HasParent: is `parent` in the hierarchy of `klass` (klass itself included).
IL2MONO_API bool il2cpp_class_has_parent(void* klass, void* parent)
{
    FIRST_CALL();
    for (void* k = klass; k; k = p_mono_class_get_parent(k))
        if (k == parent)
            return true;
    return false;
}

IL2MONO_API void* il2cpp_class_from_system_type(void* reflection_type)
{
    FIRST_CALL();
    return reflection_type ? p_mono_class_from_mono_type(p_mono_reflection_type_get_type(reflection_type)) : NULL;
}

IL2MONO_API void* il2cpp_array_class_get(void* element_class, uint32_t rank)
{
    FIRST_CALL();
    return p_mono_array_class_get(element_class, rank);
}

// ---- custom attributes -----------------------------------------------------------------------

IL2MONO_API void* il2cpp_custom_attrs_from_class(void* klass)
{
    FIRST_CALL();
    return p_mono_custom_attrs_from_class(klass);
}

IL2MONO_API bool il2cpp_custom_attrs_has_attr(void* ainfo, void* attr_klass)
{
    FIRST_CALL();
    return ainfo && p_mono_custom_attrs_has_attr(ainfo, attr_klass);
}

IL2MONO_API void* il2cpp_custom_attrs_get_attr(void* ainfo, void* attr_klass)
{
    FIRST_CALL();
    return ainfo ? p_mono_custom_attrs_get_attr(ainfo, attr_klass) : NULL;
}

IL2MONO_API void* il2cpp_custom_attrs_construct(void* ainfo)
{
    FIRST_CALL();
    return ainfo ? p_mono_custom_attrs_construct(ainfo) : NULL;
}

IL2MONO_API void il2cpp_custom_attrs_free(void* ainfo)
{
    FIRST_CALL();
    if (ainfo)
        p_mono_custom_attrs_free(ainfo);
}

IL2MONO_API bool il2cpp_class_has_attribute(void* klass, void* attr_klass)
{
    FIRST_CALL();
    void* ainfo = p_mono_custom_attrs_from_class(klass);
    if (!ainfo)
        return false;
    bool has = p_mono_custom_attrs_has_attr(ainfo, attr_klass) != 0;
    p_mono_custom_attrs_free(ainfo);
    return has;
}

IL2MONO_API bool il2cpp_field_has_attribute(void* field, void* attr_klass)
{
    FIRST_CALL();
    void* ainfo = p_mono_custom_attrs_from_field(p_mono_field_get_parent(field), field);
    if (!ainfo)
        return false;
    bool has = p_mono_custom_attrs_has_attr(ainfo, attr_klass) != 0;
    p_mono_custom_attrs_free(ainfo);
    return has;
}

// ---- methods ---------------------------------------------------------------------------------

IL2MONO_API const char* il2cpp_method_get_name(const void* method)
{
    FIRST_CALL();
    return p_mono_method_get_name((void*)method);
}

IL2MONO_API bool il2cpp_method_is_inflated(const void* method)
{
    FIRST_CALL();
    return p_unity_mono_method_is_inflated((void*)method) != 0;
}

IL2MONO_API bool il2cpp_method_is_generic(const void* method)
{
    FIRST_CALL();
    return p_unity_mono_method_is_generic((void*)method) != 0;
}

IL2MONO_API bool il2cpp_method_is_instance(const void* method)
{
    FIRST_CALL();
    return !(p_mono_method_get_flags((void*)method, NULL) & METHOD_ATTRIBUTE_STATIC);
}

IL2MONO_API uint32_t il2cpp_method_get_param_count(const void* method)
{
    FIRST_CALL();
    return p_mono_signature_get_param_count(p_mono_method_signature((void*)method));
}

IL2MONO_API const void* il2cpp_method_get_param(const void* method, uint32_t index)
{
    FIRST_CALL();
    void* sig = p_mono_method_signature((void*)method);
    void* iter = NULL;
    void* type = NULL;
    for (uint32_t i = 0; i <= index; i++)
    {
        type = p_mono_signature_get_params(sig, &iter);
        if (!type)
            return NULL;
    }
    return type;
}

IL2MONO_API bool il2cpp_type_is_byref(const void* type)
{
    FIRST_CALL();
    return type && p_mono_type_is_byref((void*)type);
}

IL2MONO_API const void* il2cpp_method_get_return_type(const void* method)
{
    FIRST_CALL();
    return p_mono_signature_get_return_type(p_mono_method_signature((void*)method));
}

// Same convention on both sides: value-type `this` is passed unboxed (IL2CPP Runtime::Invoke /
// ConvertArgs unbox it before the invoker; mono_runtime_invoke expects a pointer to the value).
IL2MONO_API void* il2cpp_runtime_invoke(const void* method, void* obj, void** params, void** exc)
{
    FIRST_CALL();
    return p_mono_runtime_invoke((void*)method, obj, params, exc);
}

// IL2CPP: run the parameterless constructor, report a managed exception through *exc.
IL2MONO_API void il2cpp_runtime_object_init_exception(void* obj, void** exc)
{
    FIRST_CALL();
    if (exc)
        *exc = NULL;
    void* ctor = p_mono_class_get_method_from_name(p_mono_object_get_class(obj), ".ctor", 0);
    if (ctor)
        p_mono_runtime_invoke(ctor, obj, NULL, exc);
}

// ---- fields ----------------------------------------------------------------------------------

IL2MONO_API const char* il2cpp_field_get_name(void* field)
{
    FIRST_CALL();
    return p_mono_field_get_name(field);
}

IL2MONO_API const void* il2cpp_field_get_type(void* field)
{
    FIRST_CALL();
    return p_mono_field_get_type(field);
}

IL2MONO_API int il2cpp_field_get_flags(void* field)
{
    FIRST_CALL();
    return (int)p_mono_field_get_flags(field);
}

IL2MONO_API size_t il2cpp_field_get_offset(void* field)
{
    FIRST_CALL();
    return p_mono_field_get_offset(field);
}

IL2MONO_API void il2cpp_field_get_value(void* obj, void* field, void* value)
{
    FIRST_CALL();
    p_mono_field_get_value(obj, field, value);
}

IL2MONO_API int il2cpp_type_get_type(const void* type)
{
    FIRST_CALL();
    return p_mono_type_get_type((void*)type);
}

// ---- objects, arrays, strings ----------------------------------------------------------------

IL2MONO_API void* il2cpp_object_new(const void* klass)
{
    FIRST_CALL();
    return p_mono_object_new(il2mono_domain, (void*)klass);
}

IL2MONO_API void* il2cpp_object_get_class(void* obj)
{
    FIRST_CALL();
    return p_mono_object_get_class(obj);
}

IL2MONO_API void* il2cpp_array_new(void* element_class, uintptr_t length)
{
    FIRST_CALL();
    return p_mono_array_new(il2mono_domain, element_class, length);
}

IL2MONO_API void* il2cpp_string_new_len(const char* str, uint32_t length)
{
    FIRST_CALL();
    return p_mono_string_new_len(il2mono_domain, str, length);
}

IL2MONO_API void* il2cpp_string_new_wrapper(const char* str)
{
    FIRST_CALL();
    return p_mono_string_new_wrapper(str);
}

IL2MONO_API uint32_t il2cpp_array_length(void* array)
{
    FIRST_CALL();
    return array ? (uint32_t)p_mono_array_length(array) : 0;
}

IL2MONO_API int32_t il2cpp_string_length(void* str)
{
    FIRST_CALL();
    return p_mono_string_length(str);
}

IL2MONO_API uint16_t* il2cpp_string_chars(void* str)
{
    FIRST_CALL();
    return p_mono_string_chars(str);
}

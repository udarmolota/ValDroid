#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#define _GNU_SOURCE         /* See feature_test_macros(7) */
#include <dlfcn.h>

#include "wrappedlibs.h"

#include "debug.h"
#include "wrapper.h"
#include "bridge.h"
#include "librarian/library_private.h"
#include "x64emu.h"
#include "emu/x64emu_private.h"
#include "box64context.h"
#include "librarian.h"
#include "callback.h"
#include "myalign.h"
#include "build_info.h"
#include "elfloader.h"
#include "custommem.h"
#include "callback.h"
#include "librarian.h"
#include <sys/mman.h>
#include <dirent.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <alloca.h>

static int vd_vk_flag(const char* name)
{
    const char* value = getenv(name);
    return value && value[0]
        && strcasecmp(value, "0") && strcasecmp(value, "off")
        && strcasecmp(value, "false") && strcasecmp(value, "no");
}

// Per-frame callers (acquire/present) read this one, so cache it instead of scanning environ each frame.
static int vd_ignore_suboptimal(void)
{
    static int cached = -1;
    if(cached < 0) cached = vd_vk_flag("VALDROID_VK_IGNORE_SUBOPTIMAL");
    return cached;
}

//extern char* libvulkan;

const char* vulkanName = "libvulkan.so.1";
#define LIBNAME vulkan

typedef void(*vFpUp_t)      (void*, uint64_t, void*);

#define ADDED_FUNCTIONS()                           \

#include "generated/wrappedvulkantypes.h"

#define ADDED_SUPER 1
#include "wrappercallback.h"

void fillVulkanProcWrapper(box64context_t*);
void freeVulkanProcWrapper(box64context_t*);

static symbol1_t* getWrappedSymbol(x64emu_t* emu, const char* rname, int warning)
{
    khint_t k = kh_get(symbolmap, emu->context->vkwrappers, rname);
    if(k==kh_end(emu->context->vkwrappers) && strstr(rname, "KHR")==NULL) {
        // try again, adding KHR at the end if not present
        char tmp[200];
        strcpy(tmp, rname);
        strcat(tmp, "KHR");
        k = kh_get(symbolmap, emu->context->vkwrappers, tmp);
    }
    if(k==kh_end(emu->context->vkwrappers)) {
        if(warning) {
            printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
            printf_dlsym(LOG_INFO, "Warning, no wrapper for %s\n", rname);
        }
        return NULL;
    }
    return &kh_value(emu->context->vkwrappers, k);
}

static void* resolveSymbol(x64emu_t* emu, void* symbol, void* fnc, const char* rname)
{
    // get wrapper
    symbol1_t *s = getWrappedSymbol(emu, rname, 1);

    khint_t k = kh_get(symbolmap, emu->context->vkwrappers, rname);
    const char* constname = kh_key(emu->context->vkwrappers, k);
    s->addr = AddCheckBridge2(emu->context->system, s->w, symbol, fnc, 0, constname);

    void* ret = (void*)s->addr;
    printf_dlsym_prefix(0, LOG_DEBUG, "%p (%p)\n", ret, symbol);
    return ret;
}

EXPORT void* my_vkGetDeviceProcAddr(x64emu_t* emu, void* device, void* name)
{
    khint_t k;
    const char* rname = (const char*)name;

    pFpp_t getprocaddr = getBridgeFnc2((void*)R_RIP);
    if(!getprocaddr) getprocaddr=my->vkGetDeviceProcAddr;

    printf_dlsym(LOG_DEBUG, "Calling my_vkGetDeviceProcAddr[%p](%p, \"%s\") => ", getprocaddr, device, rname);
    if(!emu->context->vkwrappers)
        fillVulkanProcWrapper(emu->context);

    k = kh_get(symbolmap, emu->context->vkmymap, rname);
    int is_my = (k==kh_end(emu->context->vkmymap))?0:1;
    void* symbol = getprocaddr(device, name);
    void* fnc = NULL;
    if(symbol && is_my) {   // only wrap if symbol exist
        // try again, by using custom "my_" now...
        char tmp[200];
        strcpy(tmp, "my_");
        strcat(tmp, rname);
        fnc = symbol;
        symbol = dlsym(emu->context->box64lib, tmp);
        // need to update symbol link maybe
        #define GO(A, W) if(!strcmp(rname, #A)) my->A = (W)getprocaddr(device, name);
        SUPER()
        #undef GO
    }
    if(!symbol) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
        return NULL;    // easy
    }
    void* guestsym = resolveSymbol(emu, symbol, fnc, rname);
    return guestsym;
}

EXPORT void* my_vkGetInstanceProcAddr(x64emu_t* emu, void* instance, void* name)
{
    khint_t k;
    const char* rname = (const char*)name;

   pFpp_t getprocaddr = getBridgeFnc2((void*)R_RIP);
   if(!getprocaddr) getprocaddr=(pFpp_t)my_context->vkprocaddress;

   printf_dlsym(LOG_DEBUG, "Calling my_vkGetInstanceProcAddr[%p](%p, \"%s\") => ", getprocaddr, instance, rname);
    if(!emu->context->vkwrappers)
        fillVulkanProcWrapper(emu->context);

    // check if vkprocaddress is filled, and search for lib and fill it if needed
    // get proc adress using actual glXGetProcAddress
    k = kh_get(symbolmap, emu->context->vkmymap, rname);
    int is_my = (k==kh_end(emu->context->vkmymap))?0:1;
    void* symbol = getprocaddr(instance, (void*)rname);
    void* fnc = NULL;
#ifdef ANDROID
    // RimDroid: the Android-only host driver has no X11 WSI entry points; resolve them to our
    // my_ wrappers, which route surface creation to vkCreateAndroidSurfaceKHR (rd_android_surface)
    // and report presentation support. See memory rimworld_16_port (route A2).
    if(!symbol && rname && is_my && (
            !strcmp(rname, "vkCreateXlibSurfaceKHR") ||
            !strcmp(rname, "vkCreateXcbSurfaceKHR") ||
            !strcmp(rname, "vkGetPhysicalDeviceXlibPresentationSupportKHR") ||
            !strcmp(rname, "vkGetPhysicalDeviceXcbPresentationSupportKHR"))) {
        char tmp[200];
        strcpy(tmp, "my_");
        strcat(tmp, rname);
        void* mysym = dlsym(emu->context->box64lib, tmp);
        if(mysym) {
            printf_log(LOG_INFO, "RIMDROID: emulated X11 WSI \"%s\"\n", rname);
            return resolveSymbol(emu, mysym, NULL, rname);
        }
    }
#endif
    if(!symbol) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
        return NULL;    // easy
    }
    if(is_my) {
        // try again, by using custom "my_" now...
        char tmp[200];
        strcpy(tmp, "my_");
        strcat(tmp, rname);
        fnc = symbol;
        symbol = dlsym(emu->context->box64lib, tmp);
        // need to update symbol link maybe
        #define GO(A, W) if(!strcmp(rname, #A)) my->A = (W)getprocaddr(instance, (void*)rname);;
        SUPER()
        #undef GO
    }
    return resolveSymbol(emu, symbol, fnc, rname);
}

void* my_GetVkProcAddr(x64emu_t* emu, void* name, void*(*getaddr)(const char*))
{
    khint_t k;
    const char* rname = (const char*)name;

    printf_dlsym(LOG_DEBUG, "Calling my_GetVkProcAddr(\"%s\", %p) => ", rname, getaddr);
    if(!emu->context->vkwrappers)
        fillVulkanProcWrapper(emu->context);

    // check if vkprocaddress is filled, and search for lib and fill it if needed
    // get proc adress using actual glXGetProcAddress
    k = kh_get(symbolmap, emu->context->vkmymap, rname);
    int is_my = (k==kh_end(emu->context->vkmymap))?0:1;
    void* symbol = getaddr(rname);
    if(!symbol) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
        return NULL;    // easy
    }
    void* fnc = NULL;
    if(is_my) {
        // try again, by using custom "my_" now...
        char tmp[200];
        strcpy(tmp, "my_");
        strcat(tmp, rname);
        fnc = symbol;
        symbol = dlsym(emu->context->box64lib, tmp);
        // need to update symbol link maybe
        #define GO(A, W) if(!strcmp(rname, #A)) my->A = (W)getaddr(rname);
        SUPER()
        #undef GO
    }
    return resolveSymbol(emu, symbol, fnc, rname);
}

void* my_GetVkProcAddr2(x64emu_t* emu, void* a, void* name, void*(*getaddr)(void* a, const char*))
{
    khint_t k;
    const char* rname = (const char*)name;

    printf_dlsym(LOG_DEBUG, "Calling my_GetVkProcAddr2(%p, \"%s\", %p) => ", a, rname, getaddr);
    if(!emu->context->vkwrappers)
        fillVulkanProcWrapper(emu->context);

    // get proc adress using actual glXGetProcAddress
    k = kh_get(symbolmap, emu->context->vkmymap, rname);
    int is_my = (k==kh_end(emu->context->vkmymap))?0:1;
    void* symbol = getaddr(a, rname);
    if(!symbol) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
        return NULL;    // easy
    }
    void* fnc = NULL;
    if(is_my) {
        // try again, by using custom "my_" now...
        char tmp[200];
        strcpy(tmp, "my_");
        strcat(tmp, rname);
        fnc = symbol;
        symbol = dlsym(emu->context->box64lib, tmp);
        // need to update symbol link maybe
        #define GO(A, W) if(!strcmp(rname, #A)) my->A = (W)getaddr(a, rname);
        SUPER()
        #undef GO
    }
    return resolveSymbol(emu, symbol, fnc, rname);
}

#undef SUPER

typedef struct my_VkAllocationCallbacks_s {
    void*   pUserData;
    void*   pfnAllocation;
    void*   pfnReallocation;
    void*   pfnFree;
    void*   pfnInternalAllocation;
    void*   pfnInternalFree;
} my_VkAllocationCallbacks_t;

typedef struct my_VkDebugUtilsMessengerCreateInfoEXT_s {
    int          sType;
    const void*  pNext;
    int          flags;
    int          messageSeverity;
    int          messageType;
    void*        pfnUserCallback;
    void*        pUserData;
} my_VkDebugUtilsMessengerCreateInfoEXT_t;

typedef struct my_VkDebugReportCallbackCreateInfoEXT_s {
    int         sType;
    const void* pNext;
    int         flags;
    void*       pfnCallback;
    void*       pUserData;
} my_VkDebugReportCallbackCreateInfoEXT_t;

typedef struct my_VkXcbSurfaceCreateInfoKHR_s {
    int         sType;
    const void* pNext;
    uint32_t    flags;
    void**      connection;
    int         window;
} my_VkXcbSurfaceCreateInfoKHR_t;

#define VK_MAX_DRIVER_NAME_SIZE 256
#define VK_MAX_DRIVER_INFO_SIZE 256

typedef struct my_VkPhysicalDeviceVulkan12Properties_s {
    int   sType;
    void* pNext;
    int   driverID;
    char  driverName[VK_MAX_DRIVER_NAME_SIZE];
    char  driverInfo[VK_MAX_DRIVER_INFO_SIZE];
    uint32_t __others[49];
} my_VkPhysicalDeviceVulkan12Properties_t;

typedef struct my_VkStruct_s {
    int         sType;
    struct my_VkStruct_s* pNext;
} my_VkStruct_t;

#define SUPER() \
GO(0)   \
GO(1)   \
GO(2)   \
GO(3)   \
GO(4)

// Allocation ...
#define GO(A)   \
static uintptr_t my_Allocation_fct_##A = 0;                                             \
static void* my_Allocation_##A(void* a, size_t b, size_t c, int d)                      \
{                                                                                       \
    return (void*)RunFunctionFmt(my_Allocation_fct_##A, "pLLi", a, b, c, d);      \
}
SUPER()
#undef GO
static void* find_Allocation_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_Allocation_fct_##A == (uintptr_t)fct) return my_Allocation_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_Allocation_fct_##A == 0) {my_Allocation_fct_##A = (uintptr_t)fct; return my_Allocation_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for Vulkan Allocation callback\n");
    return NULL;
}
// Reallocation ...
#define GO(A)   \
static uintptr_t my_Reallocation_fct_##A = 0;                                                   \
static void* my_Reallocation_##A(void* a, void* b, size_t c, size_t d, int e)                   \
{                                                                                               \
    return (void*)RunFunctionFmt(my_Reallocation_fct_##A, "ppLLi", a, b, c, d, e);        \
}
SUPER()
#undef GO
static void* find_Reallocation_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_Reallocation_fct_##A == (uintptr_t)fct) return my_Reallocation_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_Reallocation_fct_##A == 0) {my_Reallocation_fct_##A = (uintptr_t)fct; return my_Reallocation_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for Vulkan Reallocation callback\n");
    return NULL;
}
// Free ...
#define GO(A)   \
static uintptr_t my_Free_fct_##A = 0;                       \
static void my_Free_##A(void* a, void* b)                   \
{                                                           \
    RunFunctionFmt(my_Free_fct_##A, "pp", a, b);      \
}
SUPER()
#undef GO
static void* find_Free_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_Free_fct_##A == (uintptr_t)fct) return my_Free_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_Free_fct_##A == 0) {my_Free_fct_##A = (uintptr_t)fct; return my_Free_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for Vulkan Free callback\n");
    return NULL;
}
// InternalAllocNotification ...
#define GO(A)   \
static uintptr_t my_InternalAllocNotification_fct_##A = 0;                                  \
static void my_InternalAllocNotification_##A(void* a, size_t b, int c, int d)               \
{                                                                                           \
    RunFunctionFmt(my_InternalAllocNotification_fct_##A, "pLii", a, b, c, d);         \
}
SUPER()
#undef GO
static void* find_InternalAllocNotification_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_InternalAllocNotification_fct_##A == (uintptr_t)fct) return my_InternalAllocNotification_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_InternalAllocNotification_fct_##A == 0) {my_InternalAllocNotification_fct_##A = (uintptr_t)fct; return my_InternalAllocNotification_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for Vulkan InternalAllocNotification callback\n");
    return NULL;
}
// InternalFreeNotification ...
#define GO(A)   \
static uintptr_t my_InternalFreeNotification_fct_##A = 0;                                   \
static void my_InternalFreeNotification_##A(void* a, size_t b, int c, int d)                \
{                                                                                           \
    RunFunctionFmt(my_InternalFreeNotification_fct_##A, "pLii", a, b, c, d);          \
}
SUPER()
#undef GO
static void* find_InternalFreeNotification_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_InternalFreeNotification_fct_##A == (uintptr_t)fct) return my_InternalFreeNotification_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_InternalFreeNotification_fct_##A == 0) {my_InternalFreeNotification_fct_##A = (uintptr_t)fct; return my_InternalFreeNotification_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for Vulkan InternalFreeNotification callback\n");
    return NULL;
}
// DebugReportCallbackEXT ...
#define GO(A)   \
static uintptr_t my_DebugReportCallbackEXT_fct_##A = 0;                                                         \
static int my_DebugReportCallbackEXT_##A(int a, int b, uint64_t c, size_t d, int e, void* f, void* g, void* h)  \
{                                                                                                               \
    return RunFunctionFmt(my_DebugReportCallbackEXT_fct_##A, "iiULippp", a, b, c, d, e, f, g, h);         \
}
SUPER()
#undef GO
static void* find_DebugReportCallbackEXT_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_DebugReportCallbackEXT_fct_##A == (uintptr_t)fct) return my_DebugReportCallbackEXT_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_DebugReportCallbackEXT_fct_##A == 0) {my_DebugReportCallbackEXT_fct_##A = (uintptr_t)fct; return my_DebugReportCallbackEXT_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for Vulkan DebugReportCallbackEXT callback\n");
    return NULL;
}
// DebugUtilsMessengerCallback ...
#define GO(A)   \
static uintptr_t my_DebugUtilsMessengerCallback_fct_##A = 0;                            \
static int my_DebugUtilsMessengerCallback_##A(int a, int b, void* c, void* d)           \
{                                                                                       \
    return RunFunctionFmt(my_DebugUtilsMessengerCallback_fct_##A, "iipp", a, b, c, d);  \
}
SUPER()
#undef GO
static void* find_DebugUtilsMessengerCallback_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_DebugUtilsMessengerCallback_fct_##A == (uintptr_t)fct) return my_DebugUtilsMessengerCallback_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_DebugUtilsMessengerCallback_fct_##A == 0) {my_DebugUtilsMessengerCallback_fct_##A = (uintptr_t)fct; return my_DebugUtilsMessengerCallback_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for Vulkan DebugUtilsMessengerCallback callback\n");
    return NULL;
}

#undef SUPER

//#define PRE_INIT if(libGL) {lib->w.lib = dlopen(libGL, RTLD_LAZY | RTLD_GLOBAL); lib->path = box_strdup(libGL);} else

#ifdef ANDROID
// RimDroid: Android has no libvulkan.so.1 — the host library is "libvulkan.so"
// (the system loader). Our librimdroidlinker interposes dlopen process-wide and
// hands back the namespace-loaded loader whose ICD is the bundled Turnip driver
// (see app cpp linker.c), so this one dlopen wires guest Vulkan straight to Turnip.
#define PRE_INIT           \
    if(BOX64ENV(novulkan)) \
        return -1;         \
    if((lib->w.lib = dlopen("libvulkan.so", RTLD_LAZY | RTLD_GLOBAL)) != NULL) \
        lib->path = box_strdup("libvulkan.so"); \
    else
#else
#define PRE_INIT           \
    if(BOX64ENV(novulkan)) \
        return -1;
#endif

#define CUSTOM_INIT \
    lib->w.priv = dlsym(lib->w.lib, "vkGetInstanceProcAddr"); \
    box64->vkprocaddress = lib->w.priv;

#include "wrappedlib_init.h"

void fillVulkanProcWrapper(box64context_t* context)
{
    int cnt, ret;
    khint_t k;
    kh_symbolmap_t * symbolmap = kh_init(symbolmap);
    // populates maps...
    cnt = sizeof(vulkansymbolmap)/sizeof(map_onesymbol_t);
    for (int i=0; i<cnt; ++i) {
        k = kh_put(symbolmap, symbolmap, vulkansymbolmap[i].name, &ret);
        kh_value(symbolmap, k).w = vulkansymbolmap[i].w;
        kh_value(symbolmap, k).resolved = 0;
    }
    // and the my_ symbols map
    cnt = sizeof(MAPNAME(mysymbolmap))/sizeof(map_onesymbol_t);
    for (int i=0; i<cnt; ++i) {
        k = kh_put(symbolmap, symbolmap, vulkanmysymbolmap[i].name, &ret);
        kh_value(symbolmap, k).w = vulkanmysymbolmap[i].w;
        kh_value(symbolmap, k).resolved = 0;
    }
    context->vkwrappers = symbolmap;
    // my_* map
    symbolmap = kh_init(symbolmap);
    cnt = sizeof(MAPNAME(mysymbolmap))/sizeof(map_onesymbol_t);
    for (int i=0; i<cnt; ++i) {
        k = kh_put(symbolmap, symbolmap, vulkanmysymbolmap[i].name, &ret);
        kh_value(symbolmap, k).w = vulkanmysymbolmap[i].w;
        kh_value(symbolmap, k).resolved = 0;
    }
    context->vkmymap = symbolmap;
}
void freeVulkanProcWrapper(box64context_t* context)
{
    if(!context)
        return;
    if(context->vkwrappers)
        kh_destroy(symbolmap, context->vkwrappers);
    if(context->vkmymap)
        kh_destroy(symbolmap, context->vkmymap);
    context->vkwrappers = NULL;
    context->vkmymap = NULL;
}

my_VkAllocationCallbacks_t* find_VkAllocationCallbacks(my_VkAllocationCallbacks_t* dest, my_VkAllocationCallbacks_t* src)
{
    if(!src) return src;
    dest->pUserData = src->pUserData;
    dest->pfnAllocation = find_Allocation_Fct(src->pfnAllocation);
    dest->pfnReallocation = find_Reallocation_Fct(src->pfnReallocation);
    dest->pfnFree = find_Free_Fct(src->pfnFree);
    dest->pfnInternalAllocation = find_InternalAllocNotification_Fct(src->pfnInternalAllocation);
    dest->pfnInternalFree = find_InternalFreeNotification_Fct(src->pfnInternalFree);
    return dest;
}
// functions....
#define CREATE(A)   \
EXPORT int my_##A(x64emu_t* emu, void* device, void* pAllocateInfo, my_VkAllocationCallbacks_t* pAllocator, void* p)    \
{                                                                                                                       \
    my_VkAllocationCallbacks_t my_alloc;                                                                                \
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);                                                                         \
    if(!fnc) fnc=my->A;                                                                                                 \
    return fnc(device, pAllocateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);                            \
}
#define DESTROY(A)   \
EXPORT void my_##A(x64emu_t* emu, void* device, void* p, my_VkAllocationCallbacks_t* pAllocator)                        \
{                                                                                                                       \
    my_VkAllocationCallbacks_t my_alloc;                                                                                \
    vFppp_t fnc = getBridgeFnc2((void*)R_RIP);                                                                          \
    if(!fnc) fnc=my->A;                                                                                                 \
    fnc(device, p, find_VkAllocationCallbacks(&my_alloc, pAllocator));                                                  \
}
#define IDESTROY(A)   \
EXPORT int my_##A(x64emu_t* emu, void* device, void* p, my_VkAllocationCallbacks_t* pAllocator)                         \
{                                                                                                                       \
    my_VkAllocationCallbacks_t my_alloc;                                                                                \
    iFppp_t fnc = getBridgeFnc2((void*)R_RIP);                                                                          \
    if(!fnc) fnc=my->A;                                                                                                 \
    return fnc(device, p, find_VkAllocationCallbacks(&my_alloc, pAllocator));                                           \
}
#define DESTROY64(A)   \
EXPORT void my_##A(x64emu_t* emu, void* device, uint64_t p, my_VkAllocationCallbacks_t* pAllocator)                     \
{                                                                                                                       \
    my_VkAllocationCallbacks_t my_alloc;                                                                                \
    vFpUp_t fnc = getBridgeFnc2((void*)R_RIP);                                                                          \
    if(!fnc) fnc=my->A;                                                                                                 \
    fnc(device, p, find_VkAllocationCallbacks(&my_alloc, pAllocator));                                                  \
}

CREATE(vkAllocateMemory)
CREATE(vkCreateBuffer)
CREATE(vkCreateBufferView)
CREATE(vkCreateCommandPool)

EXPORT int my_vkCreateComputePipelines(x64emu_t* emu, void* device, void* pipelineCache, uint32_t count, void* pCreateInfos, my_VkAllocationCallbacks_t* pAllocator, void* pPipelines)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFppuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateComputePipelines;
    int ret = fnc(device, pipelineCache, count, pCreateInfos, find_VkAllocationCallbacks(&my_alloc, pAllocator), pPipelines);
    return ret;
}

CREATE(vkCreateDescriptorPool)
CREATE(vkCreateDescriptorSetLayout)
CREATE(vkCreateDescriptorUpdateTemplate)
CREATE(vkCreateDescriptorUpdateTemplateKHR)
EXPORT int my_vkCreateDevice(x64emu_t* emu, void* physdev, void* pCreateInfo, my_VkAllocationCallbacks_t* pAllocator, void* pDevice)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateDevice;
    int ret = fnc(physdev, pCreateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), pDevice);
    // RimDroid: log the device extensions — a device without VK_KHR_swapchain explains the
    // missing-swapchain path (Unity then silently renders offscreen). VkDeviceCreateInfo:
    // enabledExtensionCount +48, ppEnabledExtensionNames +56.
    if(pCreateInfo) {
        uint32_t n = *(uint32_t*)((char*)pCreateInfo+48);
        const char** ext = *(const char***)((char*)pCreateInfo+56);
        printf_log(LOG_NONE, "RIMDROID: vkCreateDevice ret=%d ext_count=%u\n", ret, n);
        for(uint32_t i=0; i<n && i<40 && ext; ++i)
            printf_log(LOG_NONE, "RIMDROID:   dev-ext[%u]=%s\n", i, ext[i]);
    }
    return ret;
}

EXPORT int my_vkCreateDisplayModeKHR(x64emu_t* emu, void* physical, void* display, void* pCreateInfo, my_VkAllocationCallbacks_t* pAllocator, void* pMode)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFppppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateDisplayModeKHR;
    return fnc(physical, display, pCreateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), pMode);
}

CREATE(vkCreateDisplayPlaneSurfaceKHR)
CREATE(vkCreateEvent)
CREATE(vkCreateFence)
CREATE(vkCreateFramebuffer)

EXPORT int my_vkCreateGraphicsPipelines(x64emu_t* emu, void* device, void* pipelineCache, uint32_t count, void* pCreateInfos, my_VkAllocationCallbacks_t* pAllocator, void* pPipelines)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFppuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateGraphicsPipelines;
    int ret = fnc(device, pipelineCache, count, pCreateInfos, find_VkAllocationCallbacks(&my_alloc, pAllocator), pPipelines);
    return ret;
}

// Capture Unity's per-frame OFFSCREEN render targets for the blit-present workaround: full-screen
// (2340x1080) images with COLOR_ATTACHMENT usage. Unity renders here (acquire=1 init proves it's NOT
// the swapchain) and never blits→presents. We'll blit the newest one to the swapchain ourselves.
// ---- Texture shrink on the Vulkan route (ValDroid) --------------------------------------------
// Same idea and knobs as the GL shim in wrappedsdl2.c (RIMDROID_TEX_SHRINK = mip shift 0/1/2,
// RIMDROID_TEX_DEEP_MIN = smallest side that gets shift 2): Valheim renders through Vulkan, where
// texture uploads never pass through GL, so the launcher's Low / Ultra low tiers did nothing here.
// vkCreateImage: a sampled, mipped 2D upload target with a side >= 1024 is created `shift` mips
// smaller (extent >> shift, mipLevels - shift), and the handle -> shift is remembered. Every later
// call that names a mip of that image (buffer->image copies, views, barriers, blits, image copies,
// clears) has its mip indices moved down by `shift`; regions that only cover the dropped top mips
// are skipped, so their texels are never uploaded. Render targets, depth/stencil, storage and
// un-mipped images are never touched. Struct offsets below are the 64-bit Vulkan ABI.
#define VD_TS_SLOTS 8192u                 /* open addressing, power of two */
#define VD_TS_TOMB  ((void*)(uintptr_t)1)
typedef struct { void* image; uint8_t shift; } vd_ts_entry_t;
static vd_ts_entry_t   vd_ts_tab[VD_TS_SLOTS];
static uint32_t        vd_ts_used;        /* live + tombstones, bounds the probe length */
static uint32_t        vd_ts_live;
static pthread_mutex_t vd_ts_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t        vd_ts_n[3], vd_ts_saved_kb;

static int vd_ts_shift_max(void) {
    static int v = -1;
    if(v < 0) {
        const char* e = getenv("RIMDROID_TEX_SHRINK");
        v = (e && e[0]) ? atoi(e) : 0;
        if(v < 0) v = 0;
        if(v > 2) v = 2;
        if(v) printf_log(LOG_NONE, "RIMDROID TEXSHRINK (vulkan) enabled: mip shift=%d deep-min=%d\n", v, (int)({
            const char* d = getenv("RIMDROID_TEX_DEEP_MIN"); int m = (d && d[0]) ? atoi(d) : 2048; m < 1024 ? 1024 : m; }));
    }
    return v;
}
static int vd_ts_deep_min(void) {
    static int v = -1;
    if(v < 0) {
        const char* e = getenv("RIMDROID_TEX_DEEP_MIN");
        v = (e && e[0]) ? atoi(e) : 2048;
        if(v < 1024) v = 1024;
    }
    return v;
}
static inline uint32_t vd_ts_hash(void* p) {
    uint64_t x = (uint64_t)(uintptr_t)p;
    x ^= x >> 17; x *= 0x9E3779B97F4A7C15ull; x ^= x >> 29;
    return (uint32_t)x & (VD_TS_SLOTS - 1u);
}
static void vd_ts_rebuild_locked(void) {   /* drop the tombstones */
    static vd_ts_entry_t old[VD_TS_SLOTS];
    memcpy(old, vd_ts_tab, sizeof(old));
    memset(vd_ts_tab, 0, sizeof(vd_ts_tab));
    vd_ts_used = 0;
    for(uint32_t j = 0; j < VD_TS_SLOTS; ++j) {
        if(!old[j].image || old[j].image == VD_TS_TOMB) continue;
        uint32_t i = vd_ts_hash(old[j].image);
        while(vd_ts_tab[i].image) i = (i + 1) & (VD_TS_SLOTS - 1u);
        vd_ts_tab[i] = old[j];
        ++vd_ts_used;
    }
}
static void vd_ts_put(void* image, int shift) {
    pthread_mutex_lock(&vd_ts_lock);
    if(vd_ts_used >= VD_TS_SLOTS * 3u / 4u) vd_ts_rebuild_locked();
    if(vd_ts_live < VD_TS_SLOTS / 2u) {
        uint32_t i = vd_ts_hash(image), tomb = ~0u;
        while(vd_ts_tab[i].image) {
            if(vd_ts_tab[i].image == image) break;
            if(vd_ts_tab[i].image == VD_TS_TOMB && tomb == ~0u) tomb = i;
            i = (i + 1) & (VD_TS_SLOTS - 1u);
        }
        if(vd_ts_tab[i].image != image) {
            if(tomb != ~0u) i = tomb; else ++vd_ts_used;
            ++vd_ts_live;
        }
        vd_ts_tab[i].image = image;
        vd_ts_tab[i].shift = (uint8_t)shift;
    }
    pthread_mutex_unlock(&vd_ts_lock);
}
static int vd_ts_get(void* image) {   /* 0 = not a shrunk image */
    if(!vd_ts_live || !image) return 0;
    int s = 0;
    pthread_mutex_lock(&vd_ts_lock);
    uint32_t i = vd_ts_hash(image);
    while(vd_ts_tab[i].image) {
        if(vd_ts_tab[i].image == image) { s = vd_ts_tab[i].shift; break; }
        i = (i + 1) & (VD_TS_SLOTS - 1u);
    }
    pthread_mutex_unlock(&vd_ts_lock);
    return s;
}
static void vd_ts_del(void* image) {
    if(!vd_ts_live || !image) return;
    pthread_mutex_lock(&vd_ts_lock);
    uint32_t i = vd_ts_hash(image);
    while(vd_ts_tab[i].image) {
        if(vd_ts_tab[i].image == image) { vd_ts_tab[i].image = VD_TS_TOMB; --vd_ts_live; break; }
        i = (i + 1) & (VD_TS_SLOTS - 1u);
    }
    pthread_mutex_unlock(&vd_ts_lock);
}
/* Move a (baseMip, levelCount) subresource range down by `s` mips. */
static inline void vd_ts_range(uint32_t* base, uint32_t* count, int s) {
    uint32_t b = *base, c = *count;
    uint32_t nb = b >= (uint32_t)s ? b - s : 0;
    if(c != 0xffffffffu) {   /* not VK_REMAINING_MIP_LEVELS */
        uint32_t end = b + c, nend = end > (uint32_t)s ? end - s : 0;
        c = nend > nb ? nend - nb : 1;
    }
    *base = nb; *count = c;
}

EXPORT int my_vkCreateImage(x64emu_t* emu, void* device, void* pCreateInfo, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateImage;
    int shift = 0;
    uint64_t kb = 0;
    char ci[88];   // VkImageCreateInfo: flags@16 imageType@20 format@24 extent@28 mipLevels@40 arrayLayers@44 samples@48 usage@56
    if(pCreateInfo && vd_ts_shift_max()) {
        memcpy(ci, pCreateInfo, sizeof(ci));
        uint32_t flags = *(uint32_t*)(ci+16), type = *(uint32_t*)(ci+20), fmt = *(uint32_t*)(ci+24);
        uint32_t w = *(uint32_t*)(ci+28), h = *(uint32_t*)(ci+32), d = *(uint32_t*)(ci+36);
        uint32_t mips = *(uint32_t*)(ci+40), layers = *(uint32_t*)(ci+44), samples = *(uint32_t*)(ci+48), usage = *(uint32_t*)(ci+56);
        uint32_t side = w > h ? w : h;
        int depthfmt = (fmt >= 124 && fmt <= 130);   // VK_FORMAT_D16_UNORM .. D32_SFLOAT_S8_UINT
        // Upload targets only: SAMPLED(4)|TRANSFER_DST(2) set, none of COLOR_ATTACHMENT(0x10),
        // DEPTH_STENCIL_ATTACHMENT(0x20), STORAGE(8), INPUT_ATTACHMENT(0x80); no sparse flags (7).
        if(type == 1 && d == 1 && samples == 1 && mips >= 2 && !depthfmt && !(flags & 7u)
           && (usage & 4u) && (usage & 2u) && !(usage & (0x10u|0x20u|0x8u|0x80u)) && side >= 1024u) {
            shift = (side >= (uint32_t)vd_ts_deep_min()) ? vd_ts_shift_max() : 1;
            if((uint32_t)shift >= mips) shift = (int)mips - 1;
            if(shift > 0) {
                *(uint32_t*)(ci+28) = (w >> shift) ? (w >> shift) : 1u;
                *(uint32_t*)(ci+32) = (h >> shift) ? (h >> shift) : 1u;
                *(uint32_t*)(ci+40) = mips - shift;
                pCreateInfo = ci;
                // dropped texels, RGBA8-equivalent: the top mip is 3/4 of the chain, the top two 15/16
                kb = ((uint64_t)w * h * layers * 4u * (shift == 1 ? 3u : 15u) / (shift == 1 ? 4u : 16u)) >> 10;
            }
        }
    }
    int ret = fnc(device, pCreateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
    if(ret == 0 && shift && p && *(void**)p) {
        vd_ts_put(*(void**)p, shift);
        uint64_t n = ++vd_ts_n[shift];
        vd_ts_saved_kb += kb;
        if(n <= 8 || (n % 256) == 0)
            printf_log(LOG_NONE, "RIMDROID TEXSHRINK img=%p shift=%d %ux%u fmt=%u mips=%u -> %ux%u/%u (shift1=%llu shift2=%llu saved~%lluMB)\n",
                       *(void**)p, shift, *(uint32_t*)((char*)ci+28) << shift, *(uint32_t*)((char*)ci+32) << shift,
                       *(uint32_t*)(ci+24), *(uint32_t*)(ci+40) + shift, *(uint32_t*)(ci+28), *(uint32_t*)(ci+32), *(uint32_t*)(ci+40),
                       (unsigned long long)vd_ts_n[1], (unsigned long long)vd_ts_n[2], (unsigned long long)(vd_ts_saved_kb >> 10));
    }
    return ret;
}
EXPORT int my_vkCreateImageView(x64emu_t* emu, void* device, void* pCreateInfo, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateImageView;
    char ci[80];   // VkImageViewCreateInfo: image@24 subresourceRange{aspect@56 baseMip@60 levelCount@64 ...}
    int s = pCreateInfo ? vd_ts_get(*(void**)((char*)pCreateInfo + 24)) : 0;
    if(s) {
        memcpy(ci, pCreateInfo, sizeof(ci));
        vd_ts_range((uint32_t*)(ci+60), (uint32_t*)(ci+64), s);
        pCreateInfo = ci;
    }
    return fnc(device, pCreateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
}
EXPORT void my_vkDestroyImage(x64emu_t* emu, void* device, void* p, my_VkAllocationCallbacks_t* pAllocator)
{
    my_VkAllocationCallbacks_t my_alloc;
    vFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkDestroyImage;
    vd_ts_del(p);
    fnc(device, p, find_VkAllocationCallbacks(&my_alloc, pAllocator));
}
/* VkBufferImageCopy (56 bytes, imageSubresource.mipLevel @20) array -> shifted copy; returns the new count. */
static uint32_t vd_ts_copy_regions(const char* in, uint32_t count, size_t stride, size_t mipoff, int s, char* out)
{
    uint32_t n = 0;
    for(uint32_t i = 0; i < count; ++i) {
        const char* r = in + i * stride;
        uint32_t mip = *(const uint32_t*)(r + mipoff);
        if(mip < (uint32_t)s) continue;   // a dropped top mip: never uploaded
        memcpy(out + n * stride, r, stride);
        *(uint32_t*)(out + n * stride + mipoff) = mip - s;
        ++n;
    }
    return n;
}
EXPORT void my_vkCmdCopyBufferToImage(x64emu_t* emu, void* cmd, void* buffer, void* image, uint32_t layout, uint32_t count, void* pRegions)
{
    vFpppuup_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdCopyBufferToImage;
    int s = vd_ts_get(image);
    if(!s || !count || !pRegions) { fnc(cmd, buffer, image, layout, count, pRegions); return; }
    char* out = alloca((size_t)count * 56);
    uint32_t n = vd_ts_copy_regions(pRegions, count, 56, 20, s, out);
    if(n) fnc(cmd, buffer, image, layout, n, out);
}
EXPORT void my_vkCmdCopyImageToBuffer(x64emu_t* emu, void* cmd, void* image, uint32_t layout, void* buffer, uint32_t count, void* pRegions)
{
    vFppupup_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdCopyImageToBuffer;
    int s = vd_ts_get(image);
    if(!s || !count || !pRegions) { fnc(cmd, image, layout, buffer, count, pRegions); return; }
    char* out = alloca((size_t)count * 56);
    uint32_t n = vd_ts_copy_regions(pRegions, count, 56, 20, s, out);
    if(n) fnc(cmd, image, layout, buffer, n, out);
}
/* VkCopyBufferToImageInfo2 (48 bytes: dstImage@24 regionCount@36 pRegions@40), VkBufferImageCopy2 (72 bytes, mipLevel@36). */
static void vd_ts_copy_buffer_to_image2(vFpp_t fnc, void* cmd, void* pInfo)
{
    int s = pInfo ? vd_ts_get(*(void**)((char*)pInfo + 24)) : 0;
    uint32_t count = pInfo ? *(uint32_t*)((char*)pInfo + 36) : 0;
    const char* regions = pInfo ? *(const char**)((char*)pInfo + 40) : NULL;
    if(!s || !count || !regions) { fnc(cmd, pInfo); return; }
    char info[48];
    memcpy(info, pInfo, sizeof(info));
    char* out = alloca((size_t)count * 72);
    uint32_t n = vd_ts_copy_regions(regions, count, 72, 36, s, out);
    if(!n) return;
    *(uint32_t*)(info + 36) = n;
    *(void**)(info + 40) = out;
    fnc(cmd, info);
}
EXPORT void my_vkCmdCopyBufferToImage2(x64emu_t* emu, void* cmd, void* pInfo)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdCopyBufferToImage2;
    vd_ts_copy_buffer_to_image2(fnc, cmd, pInfo);
}
EXPORT void my_vkCmdCopyBufferToImage2KHR(x64emu_t* emu, void* cmd, void* pInfo)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdCopyBufferToImage2KHR;
    vd_ts_copy_buffer_to_image2(fnc, cmd, pInfo);
}
/* Two-image transfers: regions carry a src and a dst mip; a region whose src or dst mip was dropped is skipped. */
static uint32_t vd_ts_pair_regions(const char* in, uint32_t count, size_t stride, size_t smip, size_t dmip, int ss, int ds, char* out)
{
    uint32_t n = 0;
    for(uint32_t i = 0; i < count; ++i) {
        const char* r = in + i * stride;
        uint32_t sm = *(const uint32_t*)(r + smip), dm = *(const uint32_t*)(r + dmip);
        if(sm < (uint32_t)ss || dm < (uint32_t)ds) continue;
        memcpy(out + n * stride, r, stride);
        *(uint32_t*)(out + n * stride + smip) = sm - ss;
        *(uint32_t*)(out + n * stride + dmip) = dm - ds;
        ++n;
    }
    return n;
}
EXPORT void my_vkCmdBlitImage(x64emu_t* emu, void* cmd, void* src, uint32_t srcLayout, void* dst, uint32_t dstLayout, uint32_t count, void* pRegions, uint32_t filter)
{
    vFppupuupu_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdBlitImage;
    int ss = vd_ts_get(src), ds = vd_ts_get(dst);
    if((!ss && !ds) || !count || !pRegions) { fnc(cmd, src, srcLayout, dst, dstLayout, count, pRegions, filter); return; }
    char* out = alloca((size_t)count * 80);   // VkImageBlit: srcSubresource.mipLevel@4 dstSubresource.mipLevel@44
    uint32_t n = vd_ts_pair_regions(pRegions, count, 80, 4, 44, ss, ds, out);
    if(n) fnc(cmd, src, srcLayout, dst, dstLayout, n, out, filter);
}
EXPORT void my_vkCmdCopyImage(x64emu_t* emu, void* cmd, void* src, uint32_t srcLayout, void* dst, uint32_t dstLayout, uint32_t count, void* pRegions)
{
    vFppupuup_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdCopyImage;
    int ss = vd_ts_get(src), ds = vd_ts_get(dst);
    if((!ss && !ds) || !count || !pRegions) { fnc(cmd, src, srcLayout, dst, dstLayout, count, pRegions); return; }
    char* out = alloca((size_t)count * 68);   // VkImageCopy: srcSubresource.mipLevel@4 dstSubresource.mipLevel@32
    uint32_t n = vd_ts_pair_regions(pRegions, count, 68, 4, 32, ss, ds, out);
    if(n) fnc(cmd, src, srcLayout, dst, dstLayout, n, out);
}
/* VkBlitImageInfo2 (72 bytes: src@16 dst@32 regionCount@48 pRegions@56), VkImageBlit2 (96 bytes: src mip@20 dst mip@60).
 * VkCopyImageInfo2 (64 bytes: src@16 dst@32 regionCount@48 pRegions@56), VkImageCopy2 (88 bytes: src mip@20 dst mip@48). */
static void vd_ts_pair2(vFpp_t fnc, void* cmd, void* pInfo, size_t infosz, size_t stride, size_t smip, size_t dmip)
{
    int ss = pInfo ? vd_ts_get(*(void**)((char*)pInfo + 16)) : 0;
    int ds = pInfo ? vd_ts_get(*(void**)((char*)pInfo + 32)) : 0;
    uint32_t count = pInfo ? *(uint32_t*)((char*)pInfo + 48) : 0;
    const char* regions = pInfo ? *(const char**)((char*)pInfo + 56) : NULL;
    if((!ss && !ds) || !count || !regions) { fnc(cmd, pInfo); return; }
    char info[72];
    memcpy(info, pInfo, infosz);
    char* out = alloca(count * stride);
    uint32_t n = vd_ts_pair_regions(regions, count, stride, smip, dmip, ss, ds, out);
    if(!n) return;
    *(uint32_t*)(info + 48) = n;
    *(void**)(info + 56) = out;
    fnc(cmd, info);
}
EXPORT void my_vkCmdBlitImage2(x64emu_t* emu, void* cmd, void* pInfo)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdBlitImage2;
    vd_ts_pair2(fnc, cmd, pInfo, 72, 96, 20, 60);
}
EXPORT void my_vkCmdBlitImage2KHR(x64emu_t* emu, void* cmd, void* pInfo)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdBlitImage2KHR;
    vd_ts_pair2(fnc, cmd, pInfo, 72, 96, 20, 60);
}
EXPORT void my_vkCmdCopyImage2(x64emu_t* emu, void* cmd, void* pInfo)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdCopyImage2;
    vd_ts_pair2(fnc, cmd, pInfo, 64, 88, 20, 48);
}
EXPORT void my_vkCmdCopyImage2KHR(x64emu_t* emu, void* cmd, void* pInfo)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdCopyImage2KHR;
    vd_ts_pair2(fnc, cmd, pInfo, 64, 88, 20, 48);
}
EXPORT void my_vkCmdClearColorImage(x64emu_t* emu, void* cmd, void* image, uint32_t layout, void* pColor, uint32_t count, void* pRanges)
{
    vFppupup_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdClearColorImage;
    int s = vd_ts_get(image);
    if(!s || !count || !pRanges) { fnc(cmd, image, layout, pColor, count, pRanges); return; }
    char* out = alloca((size_t)count * 20);   // VkImageSubresourceRange: baseMipLevel@4 levelCount@8
    memcpy(out, pRanges, (size_t)count * 20);
    for(uint32_t i = 0; i < count; ++i) vd_ts_range((uint32_t*)(out + i*20 + 4), (uint32_t*)(out + i*20 + 8), s);
    fnc(cmd, image, layout, pColor, count, out);
}
/* Barriers name whole mip ranges (Unity: VK_REMAINING_MIP_LEVELS or the full count), so only the
 * count moves. Copied only when a shrunk image is in the list; the common case pays one lookup per
 * image barrier. VkImageMemoryBarrier: 72 bytes, image@40, baseMip@52, levelCount@56.
 * VkImageMemoryBarrier2: 96 bytes, image@64, baseMip@76, levelCount@80. */
static char* vd_ts_fix_barriers(const char* in, uint32_t count, size_t stride, size_t imgoff, size_t baseoff)
{
    if(!vd_ts_live || !count || !in) return NULL;
    char* out = NULL;
    for(uint32_t i = 0; i < count; ++i) {
        int s = vd_ts_get(*(void* const*)(in + i*stride + imgoff));
        if(!s) continue;
        if(!out) { out = alloca(count * stride); memcpy(out, in, count * stride); }
        vd_ts_range((uint32_t*)(out + i*stride + baseoff), (uint32_t*)(out + i*stride + baseoff + 4), s);
    }
    return out;
}
EXPORT void my_vkCmdPipelineBarrier(x64emu_t* emu, void* cmd, uint32_t srcStage, uint32_t dstStage, uint32_t dep,
                                    uint32_t memCount, void* pMem, uint32_t bufCount, void* pBuf, uint32_t imgCount, void* pImg)
{
    vFpuuuupupup_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdPipelineBarrier;
    char* fixed = vd_ts_fix_barriers(pImg, imgCount, 72, 40, 52);
    fnc(cmd, srcStage, dstStage, dep, memCount, pMem, bufCount, pBuf, imgCount, fixed ? fixed : pImg);
}
/* VkDependencyInfo: 64 bytes, imageMemoryBarrierCount@48, pImageMemoryBarriers@56. */
static void vd_ts_barrier2(vFpp_t fnc, void* cmd, void* pDep)
{
    char* fixed = pDep ? vd_ts_fix_barriers(*(const char**)((char*)pDep + 56), *(uint32_t*)((char*)pDep + 48), 96, 64, 76) : NULL;
    if(!fixed) { fnc(cmd, pDep); return; }
    char dep[64];
    memcpy(dep, pDep, sizeof(dep));
    *(void**)(dep + 56) = fixed;
    fnc(cmd, dep);
}
EXPORT void my_vkCmdPipelineBarrier2(x64emu_t* emu, void* cmd, void* pDep)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdPipelineBarrier2;
    vd_ts_barrier2(fnc, cmd, pDep);
}
EXPORT void my_vkCmdPipelineBarrier2KHR(x64emu_t* emu, void* cmd, void* pDep)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCmdPipelineBarrier2KHR;
    vd_ts_barrier2(fnc, cmd, pDep);
}
// ---- end texture shrink ------------------------------------------------------------------------


#define VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT 1000011000
#define VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT 1000128004
EXPORT int my_vkCreateInstance(x64emu_t* emu, void* pCreateInfos, my_VkAllocationCallbacks_t* pAllocator, void* pInstance)
{
    // RimDroid GLES pivot: deny Vulkan to Unity so its auto graphics-API selection
    // (Linux order = Vulkan -> GLES) falls back to the GfxDeviceGLES backend, which
    // presents via native EGL/eglSwapBuffers and bypasses Unity's broken Vulkan
    // display-composite/present gate entirely. Gated so it is fully reversible.
    if(getenv("RIMDROID_FORCE_GLES")) {
        printf_log(LOG_INFO, "RIMDROID: RIMDROID_FORCE_GLES set -> vkCreateInstance returns VK_ERROR_INCOMPATIBLE_DRIVER (force GLES fallback)\n");
        return -9;  // VK_ERROR_INCOMPATIBLE_DRIVER
    }
    iFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateInstance;
    my_VkAllocationCallbacks_t my_alloc;
    my_VkStruct_t *p = (my_VkStruct_t*)pCreateInfos;
    void* old[20] = {0};
    int old_i = 0;
    while(p) {
        if(p->sType==VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT) {
            my_VkDebugReportCallbackCreateInfoEXT_t* vk = (my_VkDebugReportCallbackCreateInfoEXT_t*)p;
            old[old_i] = vk->pfnCallback;
            vk->pfnCallback = find_DebugReportCallbackEXT_Fct(old[old_i]);
            old_i++;
        } else if(p->sType==VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT) {
            my_VkDebugUtilsMessengerCreateInfoEXT_t* vk = (my_VkDebugUtilsMessengerCreateInfoEXT_t*)p;
            old[old_i] = vk->pfnUserCallback;
            vk->pfnUserCallback = find_DebugUtilsMessengerCallback_Fct(old[old_i]);
            old_i++;
        }
        p = p->pNext;
    }
#ifdef ANDROID
    // RimDroid: if the guest enables the X11 WSI extensions (we advertise them in
    // vkEnumerateInstanceExtensionProperties), swap them for VK_KHR_android_surface —
    // the host driver would fail vkCreateInstance with EXTENSION_NOT_PRESENT otherwise.
    typedef struct { int sType; const void* pNext; uint32_t flags; void* pApp;
                     uint32_t nLayers; void* ppLayers; uint32_t nExt; const char** ppExt; } rd_VkInstanceCreateInfo_t;
    rd_VkInstanceCreateInfo_t* rd_ci = (rd_VkInstanceCreateInfo_t*)pCreateInfos;
    const char** rd_old_ext = NULL;
    uint32_t rd_old_n = 0;
    const char** rd_new_ext = NULL;
    if(rd_ci && rd_ci->ppExt && rd_ci->nExt) {
        int has_android = 0, has_x11 = 0;
        for(uint32_t i=0; i<rd_ci->nExt; ++i) {
            if(!strcmp(rd_ci->ppExt[i], "VK_KHR_android_surface")) has_android = 1;
            else if(!strcmp(rd_ci->ppExt[i], "VK_KHR_xlib_surface") || !strcmp(rd_ci->ppExt[i], "VK_KHR_xcb_surface")) has_x11 = 1;
        }
        if(has_x11) {
            rd_new_ext = (const char**)box_malloc(rd_ci->nExt*sizeof(char*));
            uint32_t n = 0;
            for(uint32_t i=0; i<rd_ci->nExt; ++i) {
                const char* e = rd_ci->ppExt[i];
                if(!strcmp(e, "VK_KHR_xlib_surface") || !strcmp(e, "VK_KHR_xcb_surface")) {
                    if(!has_android) { rd_new_ext[n++] = "VK_KHR_android_surface"; has_android = 1; }
                    continue;
                }
                rd_new_ext[n++] = e;
            }
            rd_old_ext = rd_ci->ppExt; rd_old_n = rd_ci->nExt;
            rd_ci->ppExt = rd_new_ext; rd_ci->nExt = n;
            printf_log(LOG_INFO, "RIMDROID: vkCreateInstance X11 WSI -> android_surface (%u -> %u ext)\n", rd_old_n, n);
        }
    }
#endif
    int ret = fnc(pCreateInfos, find_VkAllocationCallbacks(&my_alloc, pAllocator), pInstance);
#ifdef ANDROID
    if(rd_new_ext) {
        rd_ci->ppExt = rd_old_ext; rd_ci->nExt = rd_old_n;
        box_free(rd_new_ext);
    }
#endif
    if(old_i) {// restore, just in case it's re-used?
        p = (my_VkStruct_t*)pCreateInfos;
        old_i = 0;
        while(p) {
            if(p->sType==VK_STRUCTURE_TYPE_DEBUG_REPORT_CALLBACK_CREATE_INFO_EXT) {
                my_VkDebugReportCallbackCreateInfoEXT_t* vk = (my_VkDebugReportCallbackCreateInfoEXT_t*)p;
                vk->pfnCallback = old[old_i];
                old_i++;
            } else if(p->sType==VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT) {
                my_VkDebugUtilsMessengerCreateInfoEXT_t* vk = (my_VkDebugUtilsMessengerCreateInfoEXT_t*)p;
                vk->pfnUserCallback = old[old_i];
                old_i++;
            }
            p = p->pNext;
        }
    }
    return ret;
}

CREATE(vkCreatePipelineCache)
CREATE(vkCreatePipelineLayout)
CREATE(vkCreateQueryPool)
CREATE(vkCreateRenderPass)
CREATE(vkCreateSampler)
CREATE(vkCreateSamplerYcbcrConversion)
CREATE(vkCreateSemaphore)
CREATE(vkCreateShaderModule)

EXPORT int my_vkCreateSharedSwapchainsKHR(x64emu_t* emu, void* device, uint32_t count, void** pCreateInfos, my_VkAllocationCallbacks_t* pAllocator, void* pSwapchains)
{
    iFpuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateSharedSwapchainsKHR;
    my_VkAllocationCallbacks_t my_alloc;
    int ret = fnc(device, count, pCreateInfos, find_VkAllocationCallbacks(&my_alloc, pAllocator), pSwapchains);
    return ret;
}

// RimDroid: swapchain/present telemetry for the 1.6 GPU-memory-leak hunt (see rimworld_16_port).
// Log the first 10 of each, every error, then 1 in 300.
static int rd_swap_creates = 0, rd_swap_destroys = 0;
EXPORT int my_vkCreateSwapchainKHR(x64emu_t* emu, void* device, void* pCreateInfo, my_VkAllocationCallbacks_t* pAllocator, void* pSwapchain)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateSwapchainKHR;
    const char* ci = (const char*)pCreateInfo;
    // VkSwapchainCreateInfoKHR (64-bit): presentMode +88, clipped +92, oldSwapchain +96.
    printf_log(LOG_NONE, "RIMDROID: vkCreateSwapchainKHR ENTER extent=%ux%u fmt=%d minImages=%u mode=%d clipped=%u preTransform=0x%x old=%p fnc=%p\n",
               ci?*(uint32_t*)(ci+44):0, ci?*(uint32_t*)(ci+48):0,
               ci?*(int*)(ci+36):0, ci?*(uint32_t*)(ci+32):0,
               ci?*(int*)(ci+88):0, ci?*(uint32_t*)(ci+92):0,
               ci?*(uint32_t*)(ci+80):0,   // preTransform @80
               ci?*(void**)(ci+96):NULL, fnc);
    int ret = fnc(device, pCreateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), pSwapchain);
    ++rd_swap_creates;
    {
        printf_log(LOG_NONE, "RIMDROID: vkCreateSwapchainKHR EXIT #%d ret=%d extent=%ux%u fmt=%d minImages=%u old=%p -> %p\n",
                   rd_swap_creates, ret,
                   ci?*(uint32_t*)(ci+44):0, ci?*(uint32_t*)(ci+48):0,
                   ci?*(int*)(ci+36):0, ci?*(uint32_t*)(ci+32):0,
                   ci?*(void**)(ci+96):NULL,
                   pSwapchain?*(void**)pSwapchain:NULL);
    }
    return ret;
}
CREATE(vkCreateWaylandSurfaceKHR)
#ifdef ANDROID
// RimDroid: the host Vulkan driver (Turnip) has NO X11 WSI — only Android WSI. When the guest
// (Unity 2022's SDL x11 driver) asks for an Xlib/Xcb Vulkan surface, build an ANDROID surface on
// our ANativeWindow instead, so Turnip's real swapchain/present runs straight on our Surface.
// See memory rimworld_16_port (RimWorld 1.6 route A2).
extern __attribute__((weak)) void* rimdroid_get_native_window(void);

typedef struct rd_VkAndroidSurfaceCreateInfoKHR_s {
    int         sType;      // VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR = 1000008000
    const void* pNext;
    uint32_t    flags;
    void*       window;     // ANativeWindow*
} rd_VkAndroidSurfaceCreateInfoKHR_t;

// Returns VK_SUCCESS(0)/error on success-path, or 1 = "not handled, fall back to host X path".
static int rd_android_surface(x64emu_t* emu, void* instance,
                              my_VkAllocationCallbacks_t* pAllocator, void* pSurface)
{
    if(!(&rimdroid_get_native_window) || !rimdroid_get_native_window)
        return 1;
    void* win = rimdroid_get_native_window();
    printf_log(LOG_INFO, "RIMDROID: rd_android_surface win=%p\n", win);
    if(!win)
        return 1;
    // Fetch the host vkCreateAndroidSurfaceKHR via the real vkGetInstanceProcAddr.
    pFpp_t getproc = (pFpp_t)emu->context->vkprocaddress;
    if(!getproc)
        return 1;
    iFpppp_t createAndroid = (iFpppp_t)getproc(instance, "vkCreateAndroidSurfaceKHR");
    printf_log(LOG_INFO, "RIMDROID: createAndroid=%p\n", createAndroid);
    if(!createAndroid)
        return 1;
    rd_VkAndroidSurfaceCreateInfoKHR_t aci = { 1000008000, NULL, 0, win };
    my_VkAllocationCallbacks_t my_alloc;
    printf_log(LOG_INFO, "RIMDROID: vkCreateAndroidSurfaceKHR ENTER tid=%d win=%p\n", GetTID(), win);
    int ret = createAndroid(instance, &aci, find_VkAllocationCallbacks(&my_alloc, pAllocator), pSurface);
    printf_log(LOG_NONE, "RIMDROID: vkCreateAndroidSurfaceKHR EXIT tid=%d ret=%d surface=%p\n",
               GetTID(), ret, pSurface?*(void**)pSurface:NULL);
    return ret;
}
#endif

EXPORT int my_vkCreateXcbSurfaceKHR(x64emu_t* emu, void* instance, void* info, my_VkAllocationCallbacks_t* pAllocator, void* pFence)
{
    if(info)
        printf_log(LOG_NONE, "RIMDROID: XcbSurface XID=0x%x conn=%p\n",
                   *(uint32_t*)((char*)info + 32), *(void**)((char*)info + 24));
#ifdef ANDROID
    int rd = rd_android_surface(emu, instance, pAllocator, pFence);
    if(rd != 1) return rd;
#endif
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateXcbSurfaceKHR;
    if(!fnc) {
        printf_log(LOG_NONE, "RIMDROID: vkCreateXcbSurfaceKHR — no host fn and no ANativeWindow (screen off?)\n");
        return -3; // VK_ERROR_INITIALIZATION_FAILED — never jump to NULL
    }
    my_VkAllocationCallbacks_t my_alloc;
    my_VkXcbSurfaceCreateInfoKHR_t* surfaceinfo = info;
    void* old_conn = surfaceinfo->connection;
    surfaceinfo->connection = align_xcb_connection(old_conn);
    int ret = fnc(instance, info, find_VkAllocationCallbacks(&my_alloc, pAllocator), pFence);
    surfaceinfo->connection = old_conn;
    return ret;
}

EXPORT int my_vkCreateXlibSurfaceKHR(x64emu_t* emu, void* instance, void* info, my_VkAllocationCallbacks_t* pAllocator, void* pSurface)
{
    printf_log(LOG_INFO, "RIMDROID: vkCreateXlibSurfaceKHR(instance=%p)\n", instance);
    // Which X11 window does Unity present to? (expert Q: surface XID vs the window we heal)
    if(info)
        printf_log(LOG_NONE, "RIMDROID: XlibSurface XID=0x%lx dpy=%p\n",
                   *(unsigned long*)((char*)info + 32), *(void**)((char*)info + 24));
#ifdef ANDROID
    int rd = rd_android_surface(emu, instance, pAllocator, pSurface);
    if(rd != 1) return rd;
#endif
    my_VkAllocationCallbacks_t my_alloc;
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateXlibSurfaceKHR;
    if(!fnc) {
        printf_log(LOG_NONE, "RIMDROID: vkCreateXlibSurfaceKHR — no host fn and no ANativeWindow (screen off?)\n");
        return -3; // VK_ERROR_INITIALIZATION_FAILED — never jump to NULL
    }
    return fnc(instance, info, find_VkAllocationCallbacks(&my_alloc, pAllocator), pSurface);
}

// RimDroid: on Android the host driver has no X11 WSI, so these queries have no host function.
// Presentation goes onto our ANativeWindow (rd_android_surface) -> report "supported".
EXPORT uint32_t my_vkGetPhysicalDeviceXlibPresentationSupportKHR(x64emu_t* emu, void* dev, uint32_t queueFamily, void* dpy, void* visualID)
{
    (void)emu;
    printf_log(LOG_INFO, "RIMDROID: XlibPresentationSupport(dev=%p qf=%u)\n", dev, queueFamily);
#ifdef ANDROID
    if(&rimdroid_get_native_window && rimdroid_get_native_window())
        return 1;
#endif
    uFpupp_t fnc = (uFpupp_t)my->vkGetPhysicalDeviceXlibPresentationSupportKHR;
    if(!fnc) return 0;
    return fnc(dev, queueFamily, dpy, visualID);
}
EXPORT uint32_t my_vkGetPhysicalDeviceXcbPresentationSupportKHR(x64emu_t* emu, void* dev, uint32_t queueFamily, void* conn, void* visualID)
{
    (void)emu;
#ifdef ANDROID
    if(&rimdroid_get_native_window && rimdroid_get_native_window())
        return 1;
#endif
    uFpupp_t fnc = (uFpupp_t)my->vkGetPhysicalDeviceXcbPresentationSupportKHR;
    if(!fnc) return 0;
    return fnc(dev, queueFamily, conn, visualID);
}

// Consistent fake memory picture (see rimworld_16_port session 5, expert consensus):
// heap.size inflated to 8GB for DEVICE_LOCAL heaps, budget = size - 512MB, usage = tracked.
// The 12GB-UMA device has plenty of real RAM; Unity's own pre-check was the "OOM".
#define RD_FAKE_HEAP  (8LL*1024*1024*1024)
static void rd_patch_memory_heaps(void* pMemProps, const char* who)
{
    // VkPhysicalDeviceMemoryProperties: memoryTypeCount@0, memoryTypes[32]@4 (8B each),
    // memoryHeapCount@260, memoryHeaps[16]@264 {u64 size; u32 flags; u32 pad}
    if(!pMemProps) return;
    uint32_t heapCount = *(uint32_t*)((char*)pMemProps + 260);
    for(uint32_t i=0; i<heapCount && i<16; ++i) {
        long long* size = (long long*)((char*)pMemProps + 264 + i*16);
        uint32_t  flags = *(uint32_t*)((char*)pMemProps + 264 + i*16 + 8);
        printf_log(LOG_NONE, "RIMDROID: heap[%u] size=%lldMB flags=%u (%s)%s\n",
                   i, *size/(1024*1024), flags, who, (*size < RD_FAKE_HEAP) ? " -> 8GB" : "");
        if(*size < RD_FAKE_HEAP) *size = RD_FAKE_HEAP;
    }
}
static void rd_patch_memory_budget(void* pProps, const char* who)
{
    // VkPhysicalDeviceMemoryProperties2: sType(0), pNext(8), memoryProperties(16)
    if(!pProps) return;
    rd_patch_memory_heaps((char*)pProps + 16, who);
    char* p = *(char**)((char*)pProps + 8);   // pNext chain
    while(p) {
        int sType = *(int*)p;
        if(sType == 1000237000) {  // VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MEMORY_BUDGET_PROPERTIES_EXT
            // { sType, pNext, VkDeviceSize heapBudget[16], VkDeviceSize heapUsage[16] }
            long long* budget = (long long*)(p + 16);
            long long* usage  = (long long*)(p + 16 + 16*8);
            for(int i=0; i<16; ++i) {
                if(budget[i]) budget[i] = RD_FAKE_HEAP - 512LL*1024*1024;
                usage[i] = 0;   // the allocation tracker is gone; the fake picture says 'nothing used'
            }
            printf_log(LOG_NONE, "RIMDROID: inflated memory budget (%s)\n", who);
        }
        p = *(char**)(p + 8);
    }
}
EXPORT void my_vkGetPhysicalDeviceMemoryProperties(x64emu_t* emu, void* physdev, void* pMemProps)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFpp_t)my->vkGetPhysicalDeviceMemoryProperties;
    fnc(physdev, pMemProps);
    if(vd_vk_flag("VALDROID_VK_FAKE_MEMORY_8GB")) rd_patch_memory_heaps(pMemProps, "props1");
}
EXPORT void my_vkGetPhysicalDeviceMemoryProperties2(x64emu_t* emu, void* physdev, void* pProps)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFpp_t)my->vkGetPhysicalDeviceMemoryProperties2;
    fnc(physdev, pProps);
    if(vd_vk_flag("VALDROID_VK_FAKE_MEMORY_8GB")) rd_patch_memory_budget(pProps, "props2");
}
EXPORT void my_vkGetPhysicalDeviceMemoryProperties2KHR(x64emu_t* emu, void* physdev, void* pProps)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFpp_t)my->vkGetPhysicalDeviceMemoryProperties2KHR;
    fnc(physdev, pProps);
    if(vd_vk_flag("VALDROID_VK_FAKE_MEMORY_8GB")) rd_patch_memory_budget(pProps, "props2KHR");
}

// RimDroid: hide VK_EXT_memory_budget from the guest — Unity 2022 self-declares
// "Vulkan - Out of memory!" from budget numbers at ~1.75GB during the 1.6 load
// (no vkAllocateMemory ever fails). Without the extension it allocates until the
// real driver limit, which is far higher on this 12GB device. See rimworld_16_port.
EXPORT int my_vkEnumerateDeviceExtensionProperties(x64emu_t* emu, void* physdev, void* pLayerName, uint32_t* pCount, void* pProperties)
{
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpppp_t)my->vkEnumerateDeviceExtensionProperties;
    int ret = fnc(physdev, pLayerName, pCount, pProperties);
#ifdef ANDROID
    if(vd_vk_flag("VALDROID_VK_HIDE_MEMORY_BUDGET") && ret>=0 && pProperties && pCount) {
        typedef struct { char name[256]; uint32_t spec; } rd_VkExtProps_t;
        rd_VkExtProps_t* arr = (rd_VkExtProps_t*)pProperties;
        for(uint32_t i=0; i<*pCount; ++i)
            if(!strcmp(arr[i].name, "VK_EXT_memory_budget")) {
                strcpy(arr[i].name, "VK_RD_hidden_budget");   // unknown to Unity -> not enabled
                printf_log(LOG_NONE, "RIMDROID: hid VK_EXT_memory_budget from the guest\n");
            }
    }
#endif
    return ret;
}

// RimDroid: advertise the X11 WSI instance extensions on top of the Android-only host driver.
// Unity 2022's SDL x11 backend refuses to create a SDL_WINDOW_VULKAN window unless the instance
// enumerates VK_KHR_xlib_surface/VK_KHR_xcb_surface; the actual surfaces are built on our
// ANativeWindow in rd_android_surface(). See memory rimworld_16_port (route A2).
EXPORT int my_vkEnumerateInstanceExtensionProperties(x64emu_t* emu, void* pLayerName, uint32_t* pCount, void* pProperties)
{
    (void)emu;
    iFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFppp_t)my->vkEnumerateInstanceExtensionProperties;
#ifdef ANDROID
    if(!pLayerName && (&rimdroid_get_native_window)) {
        typedef struct { char name[256]; uint32_t spec; } rd_VkExtProps_t;
        static const char* const rd_x11_ext[2] = { "VK_KHR_xlib_surface", "VK_KHR_xcb_surface" };
        if(!pProperties) {
            int ret = fnc(pLayerName, pCount, NULL);
            if(ret==0) *pCount += 2;
            return ret;
        }
        uint32_t cap = *(uint32_t*)pCount;
        uint32_t hostn = 0;
        fnc(pLayerName, &hostn, NULL);
        *(uint32_t*)pCount = cap<hostn ? cap : hostn;
        fnc(pLayerName, pCount, pProperties);
        uint32_t filled = *(uint32_t*)pCount;
        rd_VkExtProps_t* arr = (rd_VkExtProps_t*)pProperties;
        for(int i=0; i<2 && filled<cap; ++i) {
            memset(&arr[filled], 0, sizeof(rd_VkExtProps_t));
            strcpy(arr[filled].name, rd_x11_ext[i]);
            arr[filled].spec = 6;
            ++filled;
        }
        *(uint32_t*)pCount = filled;
        return (filled < hostn+2) ? 5/*VK_INCOMPLETE*/ : 0/*VK_SUCCESS*/;
    }
#endif
    return fnc(pLayerName, pCount, pProperties);
}
CREATE(vkCreateAndroidSurfaceKHR)
CREATE(vkCreateRenderPass2)
CREATE(vkCreateRenderPass2KHR)

EXPORT int my_vkRegisterDeviceEventEXT(x64emu_t* emu, void* device, void* info, my_VkAllocationCallbacks_t* pAllocator, void* pFence)
{
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkRegisterDeviceEventEXT;
    my_VkAllocationCallbacks_t my_alloc;
    return fnc(device, info, find_VkAllocationCallbacks(&my_alloc, pAllocator), pFence);
}
EXPORT int my_vkRegisterDisplayEventEXT(x64emu_t* emu, void* device, void* disp, void* info, my_VkAllocationCallbacks_t* pAllocator, void* pFence)
{
    iFppppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkRegisterDisplayEventEXT;
    my_VkAllocationCallbacks_t my_alloc;
    return fnc(device, disp, info, find_VkAllocationCallbacks(&my_alloc, pAllocator), pFence);
}

CREATE(vkCreateValidationCacheEXT)

EXPORT int my_vkCreateShadersEXT(x64emu_t* emu, void* device, uint32_t count, void** pCreateInfos, my_VkAllocationCallbacks_t* pAllocator, void* pShaders)
{
    iFpuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateShadersEXT;
    my_VkAllocationCallbacks_t my_alloc;
    int ret = fnc(device, count, pCreateInfos, find_VkAllocationCallbacks(&my_alloc, pAllocator), pShaders);
    return ret;
}

EXPORT int my_vkCreateExecutionGraphPipelinesAMDX(x64emu_t* emu, void* device, uint64_t pipelineCache, uint32_t count, void** pCreateInfos, my_VkAllocationCallbacks_t* pAllocator, void* pPipeLines)
{
    iFpUuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateExecutionGraphPipelinesAMDX;
    my_VkAllocationCallbacks_t my_alloc;
    int ret = fnc(device, pipelineCache, count, pCreateInfos, find_VkAllocationCallbacks(&my_alloc, pAllocator), pPipeLines);
    return ret;
}

DESTROY(vkDestroyShaderEXT)


DESTROY(vkDestroyBuffer)
DESTROY(vkDestroyBufferView)
DESTROY(vkDestroyCommandPool)
DESTROY(vkDestroyDescriptorPool)
DESTROY(vkDestroyDescriptorSetLayout)
DESTROY(vkDestroyDescriptorUpdateTemplate)
DESTROY(vkDestroyDescriptorUpdateTemplateKHR)

EXPORT void my_vkDestroyDevice(x64emu_t* emu, void* pDevice, my_VkAllocationCallbacks_t* pAllocator)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkDestroyDevice;
    my_VkAllocationCallbacks_t my_alloc;
    fnc(pDevice, find_VkAllocationCallbacks(&my_alloc, pAllocator));
}

DESTROY(vkDestroyEvent)
DESTROY(vkDestroyFence)
DESTROY(vkDestroyFramebuffer)
DESTROY(vkDestroyImageView)

EXPORT void my_vkDestroyInstance(x64emu_t* emu, void* instance, my_VkAllocationCallbacks_t* pAllocator)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkDestroyInstance;
    my_VkAllocationCallbacks_t my_alloc;
    fnc(instance, find_VkAllocationCallbacks(&my_alloc, pAllocator));
}

DESTROY(vkDestroyPipeline)
DESTROY(vkDestroyPipelineCache)
DESTROY(vkDestroyPipelineLayout)
DESTROY(vkDestroyQueryPool)
DESTROY(vkDestroyRenderPass)
DESTROY(vkDestroySampler)
DESTROY(vkDestroySamplerYcbcrConversion)
DESTROY(vkDestroySemaphore)
DESTROY(vkDestroyShaderModule)
EXPORT void my_vkDestroySwapchainKHR(x64emu_t* emu, void* device, void* swapchain, my_VkAllocationCallbacks_t* pAllocator)
{
    my_VkAllocationCallbacks_t my_alloc;
    vFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkDestroySwapchainKHR;
    ++rd_swap_destroys;
    printf_log(LOG_NONE, "RIMDROID: vkDestroySwapchainKHR #%d handle=%p (creates=%d)\n",
               rd_swap_destroys, swapchain, rd_swap_creates);
    fnc(device, swapchain, find_VkAllocationCallbacks(&my_alloc, pAllocator));
}

EXPORT int my_vkAcquireNextImageKHR(x64emu_t* emu, void* device, void* swapchain, uint64_t timeout,
                                    void* semaphore, void* fence, uint32_t* pImageIndex)
{
    iFppUppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFppUppp_t)my->vkAcquireNextImageKHR;
    int ret = fnc(device, swapchain, timeout, semaphore, fence, pImageIndex);
    if(ret == 1000001003 /*VK_SUBOPTIMAL_KHR*/ && vd_ignore_suboptimal())
        ret = 0;   // see my_vkQueuePresentKHR
    return ret;
}

EXPORT int my_vkQueuePresentKHR(x64emu_t* emu, void* queue, void* pPresentInfo)
{
    iFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpp_t)my->vkQueuePresentKHR;
    int ret = fnc(queue, pPresentInfo);
    // ValDroid: the launcher's FPS overlay counts presents through rimdroid_frame_tick(); on the
    // direct-Vulkan route this is the only present call, so tick here (GL/SDL routes tick in their swaps).
    { extern __attribute__((weak)) void rimdroid_frame_tick(void); if(rimdroid_frame_tick) rimdroid_frame_tick(); }
    // ValDroid: Android WSI answers VK_SUBOPTIMAL_KHR whenever the swapchain's preTransform differs
    // from the panel rotation. The Unity Linux player never pre-rotates, so it recreates the surface
    // and swapchain on every frame and nothing ever shows. The frame WAS presented; report success and
    // let the compositor rotate.
    if(ret == 1000001003 /*VK_SUBOPTIMAL_KHR*/ && vd_ignore_suboptimal()) {
        static int logged = 0;
        if(!logged) { logged = 1; printf_log(LOG_NONE, "RIMDROID: vkQueuePresentKHR SUBOPTIMAL -> SUCCESS (VALDROID_VK_IGNORE_SUBOPTIMAL)\n"); }
        ret = 0;
    }
    return ret;
}

EXPORT uint32_t my_vkGetPhysicalDeviceSurfaceSupportKHR(x64emu_t* emu, void* dev, uint32_t qf, void* surface, void* pSupported)
{
    uFpupp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc = (uFpupp_t)my->vkGetPhysicalDeviceSurfaceSupportKHR;
    printf_log(LOG_NONE, "RIMDROID: SurfaceSupport ENTER qf=%u surface=%p fnc=%p\n", qf, surface, fnc);
    if(!fnc) return (uint32_t)-3;
    uint32_t ret = fnc(dev, qf, surface, pSupported);
    printf_log(LOG_NONE, "RIMDROID: SurfaceSupport ret=%d supported=%u\n",
               (int)ret, pSupported?*(uint32_t*)pSupported:0);
    return ret;
}
EXPORT int my_vkGetPhysicalDeviceSurfaceFormatsKHR(x64emu_t* emu, void* dev, void* surface, uint32_t* pCount, void* pFormats)
{
    iFpppp_t fnc = (iFpppp_t)my->vkGetPhysicalDeviceSurfaceFormatsKHR;
    int ret = fnc ? fnc(dev, surface, pCount, pFormats) : -3;
    printf_log(LOG_NONE, "RIMDROID: SurfaceFormats surface=%p ret=%d count=%u query=%d\n",
               surface, ret, pCount?*pCount:0, pFormats?1:0);
    return ret;
}
EXPORT int my_vkGetPhysicalDeviceSurfaceCapabilitiesKHR(x64emu_t* emu, void* dev, void* surface, void* pCaps)
{
    iFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFppp_t)my->vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
    // Print BEFORE the host call — if Turnip dies inside, we still see the entry.
    printf_log(LOG_NONE, "RIMDROID: SurfaceCaps ENTER surface=%p fnc=%p\n", surface, fnc);
    if(!fnc) return -3;
    int ret = fnc(dev, surface, pCaps);
    if(pCaps) {
        // VkSurfaceCapabilitiesKHR: supportedTransforms@36, currentTransform@40, compositeAlpha@44.
        uint32_t supT = *(uint32_t*)((char*)pCaps+36), curT = *(uint32_t*)((char*)pCaps+40);
        printf_log(LOG_NONE, "RIMDROID: SurfaceCaps ret=%d currentExtent=%ux%u min/maxImages=%u/%u supportedTransforms=0x%x currentTransform=0x%x\n",
                   ret, *(uint32_t*)((char*)pCaps+8), *(uint32_t*)((char*)pCaps+12),
                   *(uint32_t*)pCaps, *(uint32_t*)((char*)pCaps+4), supT, curT);
        // SPOOF (AI consensus): make the Android surface look like a plain identity X11 surface, so Unity's
        // Linux Vulkan present-decision (which may read currentTransform and bail on non-IDENTITY) unlocks.
        if(vd_vk_flag("VALDROID_VK_FORCE_IDENTITY_TRANSFORM")) {
            *(uint32_t*)((char*)pCaps+36) = 0x1;
            *(uint32_t*)((char*)pCaps+40) = 0x1;
        }
        if(vd_vk_flag("VALDROID_VK_FORCE_IDENTITY_TRANSFORM") && (curT != 0x1 || supT != 0x1))
            printf_log(LOG_NONE, "RIMDROID: SurfaceCaps SPOOFED transforms -> IDENTITY (was cur=0x%x sup=0x%x)\n", curT, supT);
    }
    return ret;
}
// The 2KHR variant wraps VkSurfaceCapabilitiesKHR at +16 inside VkSurfaceCapabilities2KHR — spoof there too.
EXPORT int my_vkGetPhysicalDeviceSurfaceCapabilities2KHR(x64emu_t* emu, void* dev, void* pSurfaceInfo, void* pCaps)
{
    iFppp_t fnc = (iFppp_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFppp_t)my->vkGetPhysicalDeviceSurfaceCapabilities2KHR;
    if(!fnc) return -3;
    int ret = fnc(dev, pSurfaceInfo, pCaps);
    if(pCaps) {
        char* c = (char*)pCaps + 16;   // VkSurfaceCapabilitiesKHR surfaceCapabilities@16
        uint32_t supT = *(uint32_t*)(c+36), curT = *(uint32_t*)(c+40);
        if(vd_vk_flag("VALDROID_VK_FORCE_IDENTITY_TRANSFORM")) {
            *(uint32_t*)(c+36) = 0x1; *(uint32_t*)(c+40) = 0x1;
            printf_log(LOG_NONE, "RIMDROID: SurfaceCaps2 ret=%d currentTransform=0x%x sup=0x%x -> IDENTITY\n", ret, curT, supT);
        }
    }
    return ret;
}

DESTROY(vkFreeMemory)

EXPORT int my_vkCreateDebugUtilsMessengerEXT(x64emu_t* emu, void* device, my_VkDebugUtilsMessengerCreateInfoEXT_t* pAllocateInfo, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateDebugUtilsMessengerEXT;
    #define VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT 1000128004
    my_VkAllocationCallbacks_t my_alloc;
    my_VkDebugUtilsMessengerCreateInfoEXT_t* info = pAllocateInfo;
    while(info && info->sType==VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT) {
        info->pfnUserCallback = find_DebugUtilsMessengerCallback_Fct(info->pfnUserCallback);
        info = (my_VkDebugUtilsMessengerCreateInfoEXT_t*)info->pNext;
    }
    return fnc(device, pAllocateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
}
DESTROY(vkDestroyDebugUtilsMessengerEXT)

EXPORT void my_vkDestroySurfaceKHR(x64emu_t* emu, void* instance, void* surface, my_VkAllocationCallbacks_t* pAllocator)
{
    my_VkAllocationCallbacks_t my_alloc;
    vFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkDestroySurfaceKHR;
    printf_log(LOG_NONE, "RIMDROID: vkDestroySurfaceKHR(%p)\n", surface);
    fnc(instance, surface, find_VkAllocationCallbacks(&my_alloc, pAllocator));
}

CREATE(vkCreateSamplerYcbcrConversionKHR)
DESTROY(vkDestroySamplerYcbcrConversionKHR)

DESTROY(vkDestroyValidationCacheEXT)

CREATE(vkCreateVideoSessionKHR)
CREATE(vkCreateVideoSessionParametersKHR)
DESTROY(vkDestroyVideoSessionKHR)
DESTROY(vkDestroyVideoSessionParametersKHR)

CREATE(vkCreatePrivateDataSlot)
CREATE(vkCreatePrivateDataSlotEXT)
DESTROY(vkDestroyPrivateDataSlot)
DESTROY(vkDestroyPrivateDataSlotEXT)

CREATE(vkCreateAccelerationStructureKHR)
DESTROY(vkDestroyAccelerationStructureKHR)

EXPORT int my_vkCreateDeferredOperationKHR(x64emu_t* emu, void* device, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    iFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateDeferredOperationKHR;
    my_VkAllocationCallbacks_t my_alloc;
    return fnc(device, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
}
DESTROY(vkDestroyDeferredOperationKHR)

EXPORT int my_vkCreateRayTracingPipelinesKHR(x64emu_t* emu, void* device, void* op, void* pipeline, uint32_t count, void* infos, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    iFpppuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateRayTracingPipelinesKHR;
    my_VkAllocationCallbacks_t my_alloc;
    return fnc(device, op, pipeline, count, infos, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
}

CREATE(vkCreateCuFunctionNVX)
CREATE(vkCreateCuModuleNVX)
DESTROY(vkDestroyCuFunctionNVX)
DESTROY(vkDestroyCuModuleNVX)

CREATE(vkCreateIndirectCommandsLayoutNV)
DESTROY(vkDestroyIndirectCommandsLayoutNV)

CREATE(vkCreateAccelerationStructureNV)
EXPORT int my_vkCreateRayTracingPipelinesNV(x64emu_t* emu, void* device, void* pipeline, uint32_t count, void* infos, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    iFppuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateRayTracingPipelinesNV;
    my_VkAllocationCallbacks_t my_alloc;
    return fnc(device, pipeline, count, infos, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
}
DESTROY(vkDestroyAccelerationStructureNV)


CREATE(vkCreateOpticalFlowSessionNV)
DESTROY(vkDestroyOpticalFlowSessionNV)

CREATE(vkCreateMicromapEXT)
DESTROY(vkDestroyMicromapEXT)

CREATE(vkCreateCudaFunctionNV)
CREATE(vkCreateCudaModuleNV)
DESTROY64(vkDestroyCudaFunctionNV)
DESTROY64(vkDestroyCudaModuleNV)

EXPORT int my_vkCreateDebugReportCallbackEXT(x64emu_t* emu, void* instance,
                                             my_VkDebugReportCallbackCreateInfoEXT_t* create,
                                             my_VkAllocationCallbacks_t* alloc, void* callback)
{
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateDebugReportCallbackEXT;
    my_VkDebugReportCallbackCreateInfoEXT_t dbg = *create;
    my_VkAllocationCallbacks_t my_alloc;
    dbg.pfnCallback = find_DebugReportCallbackEXT_Fct(dbg.pfnCallback);
    return fnc(instance, &dbg, find_VkAllocationCallbacks(&my_alloc, alloc), callback);
}

EXPORT void my_vkDestroyDebugReportCallbackEXT(x64emu_t* emu, void* instance, void* callback, void* alloc)
{
    vFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkDestroyDebugReportCallbackEXT;
    my_VkAllocationCallbacks_t my_alloc;
    fnc(instance, callback, find_VkAllocationCallbacks(&my_alloc, alloc));
}

CREATE(vkCreateHeadlessSurfaceEXT)

EXPORT void my_vkGetPhysicalDeviceProperties2(x64emu_t* emu, void* device, void* pProps)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkGetPhysicalDeviceProperties2;
    fnc(device, pProps);
    my_VkStruct_t *p = pProps;
    while (p != NULL) {
        // find VkPhysicalDeviceVulkan12Properties
        // VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES = 52
        if(p->sType == 52) {
            my_VkPhysicalDeviceVulkan12Properties_t *pp = (my_VkPhysicalDeviceVulkan12Properties_t*)p;
            strncat(pp->driverInfo, " with " BOX64_BUILD_INFO_STRING, VK_MAX_DRIVER_INFO_SIZE - strlen(pp->driverInfo) - 1);
            break;
        }
        p = p->pNext;
    }
}

CREATE(vkCreateIndirectCommandsLayoutEXT)
CREATE(vkCreateIndirectExecutionSetEXT)
DESTROY(vkDestroyIndirectCommandsLayoutEXT)
DESTROY(vkDestroyIndirectExecutionSetEXT)

CREATE(vkCreatePipelineBinariesKHR)
DESTROY(vkDestroyPipelineBinaryKHR)
IDESTROY(vkReleaseCapturedPipelineDataKHR)

CREATE(vkCreateTensorARM)
CREATE(vkCreateTensorViewARM)
DESTROY(vkDestroyTensorARM)
DESTROY(vkDestroyTensorViewARM)

CREATE(vkCreateDataGraphPipelineSessionARM)
EXPORT int my_vkCreateDataGraphPipelinesARM(x64emu_t* emu, void* device, void* deferredOperation, void* pipelineCache,
                                             uint32_t createInfoCount, void* pCreateInfos,
                                             my_VkAllocationCallbacks_t* alloc, void* pPipelines)
{
    iFpppuppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateDataGraphPipelinesARM;
    my_VkAllocationCallbacks_t my_alloc;
    return fnc(device, deferredOperation, pipelineCache, createInfoCount, pCreateInfos, find_VkAllocationCallbacks(&my_alloc, alloc), pPipelines);
}
DESTROY(vkDestroyDataGraphPipelineSessionARM)

CREATE(vkCreateWin32SurfaceKHR)

CREATE(vkCreateShaderInstrumentationARM)
DESTROY(vkDestroyShaderInstrumentationARM)

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

static int vd_vk_flag(const char* name)
{
    const char* value = getenv(name);
    return value && value[0]
        && strcasecmp(value, "0") && strcasecmp(value, "off")
        && strcasecmp(value, "false") && strcasecmp(value, "no");
}

// Resolve a guest code address to a Mono method name via the guest's own mono_pmip(ip) — the
// cheapest way to name RimWorld's managed frames (0x321xxxx = Mono-JIT code, box64 shows "???").
// mono_pmip returns a guest-heap char* like " Verse.Foo:Bar () + 0x.. (0x.. 0x..)". Read-only query.
static uintptr_t rd_mono_pmip_addr(void) {
    static uintptr_t addr = (uintptr_t)-1;
    if(addr != (uintptr_t)-1) return addr;
    addr = 0;
    uintptr_t start = 0, end = 0;
    // Search exported (.dynsym) symbols across all loaded guest libs.
    if(GetGlobalSymbolStartEnd(my_context->maplib, "mono_pmip", &start, &end, NULL, -1, NULL, 0, NULL))
        addr = start;
    printf_log(LOG_NONE, "RIMDROID: mono_pmip guest addr=%p\n", (void*)addr);
    return addr;
}
static const char* rd_mono_name(void* ip) {
    uintptr_t pmip = rd_mono_pmip_addr();
    if(!pmip) return NULL;
    uint64_t r = RunFunction(pmip, 1, (uint64_t)(uintptr_t)ip);
    const char* s = (const char*)(uintptr_t)r;
    if(s && (msync((void*)((uintptr_t)s & ~4095ULL), 4096, MS_ASYNC) == 0))
        return s;
    return NULL;
}

// Resolve any exported guest symbol (mono_* API) once — same lookup as pmip.
static uintptr_t rd_gsym(const char* name) {
    uintptr_t start = 0, end = 0;
    if(GetGlobalSymbolStartEnd(my_context->maplib, name, &start, &end, NULL, -1, NULL, 0, NULL))
        return start;
    printf_log(LOG_NONE, "RIMDROID: gsym MISSING %s\n", name);
    return 0;
}
static int rd_ptr_ok(const void* p) {
    return p && (msync((void*)((uintptr_t)p & ~4095ULL), 4096, MS_ASYNC) == 0);
}
// Read a guest .NET string (System.String) into a native buffer. MonoString layout: chars are
// UTF-16 at +0x10, length (int32) at +0x8 (MonoBleedingEdge). Convert to ASCII-ish for logging.
static void rd_read_monostring(void* mstr, char* out, int outsz) {
    out[0] = 0;
    if(!rd_ptr_ok(mstr)) return;
    int len = *(int*)((char*)mstr + 0x8);
    if(len < 0 || len > outsz - 1) len = outsz - 1;
    const unsigned short* c = (const unsigned short*)((char*)mstr + 0x10);
    if(!rd_ptr_ok(c)) return;
    int i = 0;
    for(; i < len; ++i) out[i] = (c[i] < 0x80) ? (char)c[i] : '?';
    out[i] = 0;
}
// Dump RimWorld's Verse.LongEventHandler static state: is a long event stuck "in progress"?
// Answers WHY Root.OnGUI keeps repainting the loading screen (see rimworld_16_port session 6f).
static void rd_dump_longevent(void) {
    uintptr_t f_rootdom = rd_gsym("mono_get_root_domain");
    uintptr_t f_imgload = rd_gsym("mono_image_loaded");
    uintptr_t f_clsname = rd_gsym("mono_class_from_name");
    uintptr_t f_fldname = rd_gsym("mono_class_get_field_from_name");
    uintptr_t f_vtable  = rd_gsym("mono_class_vtable");
    uintptr_t f_sget    = rd_gsym("mono_field_static_get_value");
    uintptr_t f_objcls  = rd_gsym("mono_object_get_class");
    uintptr_t f_clsnm   = rd_gsym("mono_class_get_name");
    uintptr_t f_fget    = rd_gsym("mono_field_get_value");
    if(!f_rootdom || !f_imgload || !f_clsname || !f_fldname || !f_vtable || !f_sget) {
        printf_log(LOG_NONE, "RIMDROID: LONGEVENT probe — missing mono API, abort\n");
        return;
    }
    uint64_t domain = RunFunction(f_rootdom, 0);
    uint64_t image  = RunFunction(f_imgload, 1, (uint64_t)(uintptr_t)"Assembly-CSharp");
    if(!image) { printf_log(LOG_NONE, "RIMDROID: LONGEVENT — Assembly-CSharp image not loaded\n"); return; }
    uint64_t klass  = RunFunction(f_clsname, 3, image, (uint64_t)(uintptr_t)"Verse",
                                  (uint64_t)(uintptr_t)"LongEventHandler");
    if(!klass) { printf_log(LOG_NONE, "RIMDROID: LONGEVENT — class Verse.LongEventHandler not found\n"); return; }
    uint64_t vt = RunFunction(f_vtable, 2, domain, klass);
    printf_log(LOG_NONE, "RIMDROID: LONGEVENT domain=%p image=%p klass=%p vt=%p\n",
               (void*)domain, (void*)image, (void*)klass, (void*)vt);
    // Read each static field of interest: null vs object + its runtime class name.
    const char* fields[] = { "currentEvent", "eventQueue", "eventThread",
                             "toExecuteWhenFinished", "executingToExecuteWhenFinished", NULL };
    void* currentEvent = NULL;
    for(int i = 0; fields[i]; ++i) {
        uint64_t fld = RunFunction(f_fldname, 2, klass, (uint64_t)(uintptr_t)fields[i]);
        if(!fld) { printf_log(LOG_NONE, "RIMDROID: LE field %s = <not found>\n", fields[i]); continue; }
        void* val = NULL;
        RunFunction(f_sget, 3, vt, fld, (uint64_t)(uintptr_t)&val);
        const char* cn = "?";
        if(rd_ptr_ok(val) && f_objcls && f_clsnm) {
            uint64_t oc = RunFunction(f_objcls, 1, (uint64_t)(uintptr_t)val);
            uint64_t nm = RunFunction(f_clsnm, 1, oc);
            if(rd_ptr_ok((void*)(uintptr_t)nm)) cn = (const char*)(uintptr_t)nm;
        }
        printf_log(LOG_NONE, "RIMDROID: LE %s = %p (%s)\n", fields[i], val, rd_ptr_ok(val) ? cn : "null");
        if(!strcmp(fields[i], "currentEvent")) currentEvent = val;
    }
    // If a long event IS running, read its status text + async flags + any stored exception —
    // the worker thread (eventThread) may have thrown, and the main thread may not be seeing it.
    if(rd_ptr_ok(currentEvent) && f_objcls && f_fldname && f_fget) {
        uintptr_t f_parent = rd_gsym("mono_class_get_parent");
        uint64_t qcls = RunFunction(f_objcls, 1, (uint64_t)(uintptr_t)currentEvent);
        const char* strs[] = { "eventText", "eventTextKey", NULL };
        for(int i = 0; strs[i]; ++i) {
            uint64_t fld = RunFunction(f_fldname, 2, qcls, (uint64_t)(uintptr_t)strs[i]);
            if(!fld) continue;
            void* str = NULL;
            RunFunction(f_fget, 3, (uint64_t)(uintptr_t)currentEvent, fld, (uint64_t)(uintptr_t)&str);
            char buf[160]; rd_read_monostring(str, buf, sizeof(buf));
            printf_log(LOG_NONE, "RIMDROID: LE currentEvent.%s = \"%s\"\n", strs[i], buf);
        }
        // bool/ref flags on QueuedLongEvent
        const char* bools[] = { "doAsynchronously", "alreadyDisplayed", "canEverUseStandardWindow", NULL };
        for(int i = 0; bools[i]; ++i) {
            uint64_t fld = RunFunction(f_fldname, 2, qcls, (uint64_t)(uintptr_t)bools[i]);
            if(!fld) continue;
            unsigned char b = 0xff;
            RunFunction(f_fget, 3, (uint64_t)(uintptr_t)currentEvent, fld, (uint64_t)(uintptr_t)&b);
            printf_log(LOG_NONE, "RIMDROID: LE currentEvent.%s = %d\n", bools[i], (int)b);
        }
        // eventAction: the delegate the worker thread is executing — name its method = the exact
        // load step we're stuck on. MonoDelegate.method lives at +0x28 (MonoBleedingEdge layout).
        uintptr_t f_mfn = rd_gsym("mono_method_full_name");
        uint64_t afld = RunFunction(f_fldname, 2, qcls, (uint64_t)(uintptr_t)"eventAction");
        if(afld) {
            void* act = NULL;
            RunFunction(f_fget, 3, (uint64_t)(uintptr_t)currentEvent, afld, (uint64_t)(uintptr_t)&act);
            if(rd_ptr_ok(act)) {
                void* method = *(void**)((char*)act + 0x28);
                const char* mn = "?";
                if(rd_ptr_ok(method) && f_mfn) {
                    uint64_t r = RunFunction(f_mfn, 2, (uint64_t)(uintptr_t)method, (uint64_t)1);
                    if(rd_ptr_ok((void*)(uintptr_t)r)) mn = (const char*)(uintptr_t)r;
                }
                printf_log(LOG_NONE, "RIMDROID: LE currentEvent.eventAction.method = %s (delegate=%p method=%p)\n",
                           mn, act, method);
            } else {
                printf_log(LOG_NONE, "RIMDROID: LE currentEvent.eventAction = null\n");
            }
        }
        // exception: did the worker thread throw? read its type + Message.
        uint64_t efld = RunFunction(f_fldname, 2, qcls, (uint64_t)(uintptr_t)"exception");
        if(efld) {
            void* exc = NULL;
            RunFunction(f_fget, 3, (uint64_t)(uintptr_t)currentEvent, efld, (uint64_t)(uintptr_t)&exc);
            if(rd_ptr_ok(exc)) {
                uint64_t ecls = RunFunction(f_objcls, 1, (uint64_t)(uintptr_t)exc);
                const char* ecn = "?";
                uint64_t enm = RunFunction(f_clsnm, 1, ecls);
                if(rd_ptr_ok((void*)(uintptr_t)enm)) ecn = (const char*)(uintptr_t)enm;
                // Exception._message may live on a parent class — walk up to find the field.
                uint64_t mfld = 0, wc = ecls;
                for(int depth = 0; depth < 5 && wc && !mfld; ++depth) {
                    mfld = RunFunction(f_fldname, 2, wc, (uint64_t)(uintptr_t)"_message");
                    if(!mfld && f_parent) wc = RunFunction(f_parent, 1, wc); else break;
                }
                char msg[200] = "";
                if(mfld) {
                    void* ms = NULL;
                    RunFunction(f_fget, 3, (uint64_t)(uintptr_t)exc, mfld, (uint64_t)(uintptr_t)&ms);
                    rd_read_monostring(ms, msg, sizeof(msg));
                }
                printf_log(LOG_NONE, "RIMDROID: LE currentEvent.exception = %s : \"%s\"\n", ecn, msg);
            } else {
                printf_log(LOG_NONE, "RIMDROID: LE currentEvent.exception = null\n");
            }
        }
    }
    printf_log(LOG_NONE, "RIMDROID: LONGEVENT dump done\n");
}
// Dump native thread states from /proc: is any worker thread actually RUNNING (R) the load, or are
// they all sleeping (S) — i.e. finished/blocked while the main thread spins OnGUI waiting on IsAlive?
static const char* rd_unity_event_type_name(int t)
{
    switch(t) {
        case 0: return "MouseDown";
        case 1: return "MouseUp";
        case 2: return "MouseMove";
        case 3: return "MouseDrag";
        case 4: return "KeyDown";
        case 5: return "KeyUp";
        case 6: return "ScrollWheel";
        case 7: return "Repaint";
        case 8: return "Layout";
        case 9: return "DragUpdated";
        case 10: return "DragPerform";
        case 11: return "DragExited";
        case 12: return "Ignore";
        case 13: return "Used";
        case 14: return "ValidateCommand";
        case 15: return "ExecuteCommand";
        case 16: return "ContextClick";
        case 17: return "MouseEnterWindow";
        case 18: return "MouseLeaveWindow";
        case 19: return "TouchDown";
        case 20: return "TouchUp";
        case 21: return "TouchMove";
        case 22: return "TouchEnter";
        case 23: return "TouchLeave";
        case 24: return "TouchStationary";
        default: return "?";
    }
}

static int rd_unbox_i32(uintptr_t boxed, uintptr_t f_unbox)
{
    if(!boxed || !f_unbox) return -999;
    uint64_t raw = RunFunction(f_unbox, 1, boxed);
    if(!rd_ptr_ok((void*)(uintptr_t)raw)) return -998;
    return *(int*)(uintptr_t)raw;
}

static uint64_t rd_unity_event_invoke(uintptr_t f_invoke, uintptr_t method, void* obj, void** exc)
{
    if(exc) *exc = NULL;
    if(!f_invoke || !method) return 0;
    return RunFunction(f_invoke, 4, method, (uint64_t)(uintptr_t)obj, 0,
                       (uint64_t)(uintptr_t)exc);
}

static void rd_dump_unity_event_current(void)
{
    uintptr_t f_thread_current = rd_gsym("mono_thread_current");
    uintptr_t f_imgload = rd_gsym("mono_image_loaded");
    uintptr_t f_clsname = rd_gsym("mono_class_from_name");
    uintptr_t f_method = rd_gsym("mono_class_get_method_from_name");
    uintptr_t f_invoke = rd_gsym("mono_runtime_invoke");
    uintptr_t f_unbox = rd_gsym("mono_object_unbox");
    if(!f_thread_current || !f_imgload || !f_clsname || !f_method || !f_invoke || !f_unbox) {
        printf_log(LOG_NONE, "RIMDROID: UNITYEVENT probe missing mono API\n");
        return;
    }
    uint64_t mono_thread = RunFunction(f_thread_current, 0);
    if(!mono_thread) {
        printf_log(LOG_NONE, "RIMDROID: UNITYEVENT no attached Mono thread at vk bind site\n");
        return;
    }

    const char* images[] = { "UnityEngine.IMGUIModule", "UnityEngine.IMGUIModule.dll",
                             "UnityEngine.CoreModule", "UnityEngine.CoreModule.dll", NULL };
    uint64_t image = 0;
    for(int i = 0; images[i] && !image; ++i)
        image = RunFunction(f_imgload, 1, (uint64_t)(uintptr_t)images[i]);
    if(!image) {
        printf_log(LOG_NONE, "RIMDROID: UNITYEVENT UnityEngine image not loaded\n");
        return;
    }
    uint64_t klass = RunFunction(f_clsname, 3, image, (uint64_t)(uintptr_t)"UnityEngine",
                                 (uint64_t)(uintptr_t)"Event");
    if(!klass) {
        printf_log(LOG_NONE, "RIMDROID: UNITYEVENT UnityEngine.Event class not found (image=%p)\n",
                   (void*)image);
        return;
    }
    uint64_t m_current = RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)"get_current", 0);
    uint64_t m_type    = RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)"get_type", 0);
    uint64_t m_rawtype = RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)"get_rawType", 0);
    uint64_t m_cmd     = RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)"get_commandName", 0);
    if(!m_current || !m_type) {
        printf_log(LOG_NONE, "RIMDROID: UNITYEVENT missing get_current/get_type methods current=%p type=%p\n",
                   (void*)m_current, (void*)m_type);
        return;
    }

    void* exc = NULL;
    uint64_t current = rd_unity_event_invoke(f_invoke, m_current, NULL, &exc);
    if(exc || !rd_ptr_ok((void*)(uintptr_t)current)) {
        printf_log(LOG_NONE, "RIMDROID: UNITYEVENT current=%p exc=%p\n",
                   (void*)current, exc);
        return;
    }
    void* type_exc = NULL;
    uint64_t boxed_type = rd_unity_event_invoke(f_invoke, m_type, (void*)(uintptr_t)current, &type_exc);
    int type = rd_unbox_i32((uintptr_t)boxed_type, f_unbox);
    int rawtype = -999;
    void* raw_exc = NULL;
    if(m_rawtype) {
        uint64_t boxed_raw = rd_unity_event_invoke(f_invoke, m_rawtype, (void*)(uintptr_t)current, &raw_exc);
        rawtype = rd_unbox_i32((uintptr_t)boxed_raw, f_unbox);
    }
    char cmd[128] = "";
    void* cmd_exc = NULL;
    if(m_cmd) {
        uint64_t cmdstr = rd_unity_event_invoke(f_invoke, m_cmd, (void*)(uintptr_t)current, &cmd_exc);
        rd_read_monostring((void*)(uintptr_t)cmdstr, cmd, sizeof(cmd));
    }
    printf_log(LOG_NONE,
               "RIMDROID: UNITYEVENT current=%p type=%d(%s) rawType=%d(%s) cmd=\"%s\" exc(type/raw/cmd)=%p/%p/%p\n",
               (void*)current, type, rd_unity_event_type_name(type),
               rawtype, rd_unity_event_type_name(rawtype), cmd, type_exc, raw_exc, cmd_exc);
}

// Read UnityEngine.Time.frameCount (static int). If it's CONSTANT across millions of OnGUI(Repaint),
// this is genuinely ONE Unity frame that never ends (Codex idea #3) — not many non-presenting frames.
static int rd_unity_frame_count(void) {
    uintptr_t f_imgload = rd_gsym("mono_image_loaded");
    uintptr_t f_clsname = rd_gsym("mono_class_from_name");
    uintptr_t f_method  = rd_gsym("mono_class_get_method_from_name");
    uintptr_t f_invoke  = rd_gsym("mono_runtime_invoke");
    uintptr_t f_unbox   = rd_gsym("mono_object_unbox");
    if(!f_imgload || !f_clsname || !f_method || !f_invoke || !f_unbox) return -1;
    const char* imgs[] = { "UnityEngine.CoreModule", "UnityEngine.CoreModule.dll", "UnityEngine", NULL };
    uint64_t image = 0;
    for(int i = 0; imgs[i] && !image; ++i) image = RunFunction(f_imgload, 1, (uint64_t)(uintptr_t)imgs[i]);
    if(!image) return -2;
    uint64_t klass = RunFunction(f_clsname, 3, image, (uint64_t)(uintptr_t)"UnityEngine",
                                 (uint64_t)(uintptr_t)"Time");
    if(!klass) return -3;
    uint64_t m_fc = RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)"get_frameCount", 0);
    if(!m_fc) return -4;
    void* exc = NULL;
    uint64_t boxed = rd_unity_event_invoke(f_invoke, m_fc, NULL, &exc);
    if(exc) return -5;
    return rd_unbox_i32((uintptr_t)boxed, f_unbox);
}
// Forward tentative-defs of the vk counters (defined with initializers later in the file; C merges them).
static int rd_cb_begin, rd_cb_end, rd_cb_reset, rd_rp_begin, rd_rp_end;
static int rd_swap_creates, rd_presents;
static int rd_acquires, rd_submits;
static int rd_dynr_begin, rd_dynr_end, rd_draws;
// Live-captured Vulkan handles for the synthetic-present WSI oracle (Codex #6).
static void* rd_h_device;
static void* rd_h_swapchain;
static void* rd_h_queue;
static uint32_t rd_h_queue_family = 0xffffffff;
static void* rd_h_images[8];
static uint32_t rd_h_image_count;
extern __attribute__((weak)) void* rimdroid_get_native_window(void);

static void rd_synthetic_present(void);   // body defined below, after 'my' is declared (wrappercallback.h)
// Read a UnityEngine static int getter (e.g. Screen.get_width). class in CoreModule.
static int rd_unity_static_int(const char* ns, const char* cls, const char* getter) {
    uintptr_t f_imgload = rd_gsym("mono_image_loaded"), f_clsname = rd_gsym("mono_class_from_name");
    uintptr_t f_method = rd_gsym("mono_class_get_method_from_name"), f_invoke = rd_gsym("mono_runtime_invoke");
    uintptr_t f_unbox = rd_gsym("mono_object_unbox");
    if(!f_imgload || !f_clsname || !f_method || !f_invoke || !f_unbox) return -1;
    const char* imgs[] = { "UnityEngine.CoreModule", "UnityEngine", NULL };
    uint64_t image = 0;
    for(int i = 0; imgs[i] && !image; ++i) image = RunFunction(f_imgload, 1, (uint64_t)(uintptr_t)imgs[i]);
    if(!image) return -2;
    uint64_t klass = RunFunction(f_clsname, 3, image, (uint64_t)(uintptr_t)ns, (uint64_t)(uintptr_t)cls);
    if(!klass) return -3;
    uint64_t m = RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)getter, 0);
    if(!m) return -4;
    void* exc = NULL;
    uint64_t boxed = rd_unity_event_invoke(f_invoke, m, NULL, &exc);
    if(exc) return -5;
    return rd_unbox_i32((uintptr_t)boxed, f_unbox);
}
// Is Unity's Display/Screen object sane, or did patch-C hide a crash and leave a no-present state?
// (ChatGPT top bet.) Reads Screen.width/height/fullScreen + Display.displays.Length.
static void rd_dump_display_state(void) {
    int w = rd_unity_static_int("UnityEngine", "Screen", "get_width");
    int h = rd_unity_static_int("UnityEngine", "Screen", "get_height");
    int fs = rd_unity_static_int("UnityEngine", "Screen", "get_fullScreen");
    // Display.displays is a static Display[] — read its managed array length (MonoArray max_length@+0x18).
    int ndisp = -1;
    {
        uintptr_t f_imgload = rd_gsym("mono_image_loaded"), f_clsname = rd_gsym("mono_class_from_name");
        uintptr_t f_method = rd_gsym("mono_class_get_method_from_name"), f_invoke = rd_gsym("mono_runtime_invoke");
        if(f_imgload && f_clsname && f_method && f_invoke) {
            uint64_t image = RunFunction(f_imgload, 1, (uint64_t)(uintptr_t)"UnityEngine.CoreModule");
            uint64_t klass = image ? RunFunction(f_clsname, 3, image, (uint64_t)(uintptr_t)"UnityEngine",
                                                 (uint64_t)(uintptr_t)"Display") : 0;
            uint64_t m = klass ? RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)"get_displays", 0) : 0;
            if(m) {
                void* exc = NULL;
                uint64_t arr = rd_unity_event_invoke(f_invoke, m, NULL, &exc);
                if(!exc && rd_ptr_ok((void*)(uintptr_t)arr))
                    ndisp = (int)*(uintptr_t*)((char*)(uintptr_t)arr + 0x18);
            }
        }
    }
    // Display.main (static) → Display obj; read active/systemWidth/renderingWidth = is the display
    // object fully initialized or half-init (the present-gate suspect)?
    int active = -9, sysW = -9, sysH = -9, renW = -9, renH = -9;
    {
        uintptr_t f_imgload = rd_gsym("mono_image_loaded"), f_clsname = rd_gsym("mono_class_from_name");
        uintptr_t f_method = rd_gsym("mono_class_get_method_from_name"), f_invoke = rd_gsym("mono_runtime_invoke");
        uintptr_t f_unbox = rd_gsym("mono_object_unbox");
        uint64_t image = f_imgload ? RunFunction(f_imgload, 1, (uint64_t)(uintptr_t)"UnityEngine.CoreModule") : 0;
        uint64_t klass = image ? RunFunction(f_clsname, 3, image, (uint64_t)(uintptr_t)"UnityEngine", (uint64_t)(uintptr_t)"Display") : 0;
        uint64_t m_main = klass ? RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)"get_main", 0) : 0;
        void* exc = NULL;
        uint64_t disp = m_main ? rd_unity_event_invoke(f_invoke, m_main, NULL, &exc) : 0;
        if(!exc && rd_ptr_ok((void*)(uintptr_t)disp)) {
            struct { const char* n; int* out; } fields[] = {
                {"get_active", &active}, {"get_systemWidth", &sysW}, {"get_systemHeight", &sysH},
                {"get_renderingWidth", &renW}, {"get_renderingHeight", &renH}, {NULL,NULL} };
            for(int i = 0; fields[i].n; ++i) {
                uint64_t mm = RunFunction(f_method, 3, klass, (uint64_t)(uintptr_t)fields[i].n, 0);
                if(!mm) continue;
                void* e2 = NULL;
                uint64_t boxed = rd_unity_event_invoke(f_invoke, mm, (void*)(uintptr_t)disp, &e2);
                if(!e2) *fields[i].out = rd_unbox_i32((uintptr_t)boxed, f_unbox);
            }
        }
    }
    printf_log(LOG_NONE, "RIMDROID: DISPLAY Screen=%dx%d fullScreen=%d displays.Length=%d | Display.main active=%d system=%dx%d rendering=%dx%d\n",
               w, h, fs, ndisp, active, sysW, sysH, renW, renH);
}
// Frame watchdog summary (Codex idea #2/#4): one line — where is the frame stuck?
static void rd_dump_frame_summary(const char* where) {
    void* win = (&rimdroid_get_native_window && rimdroid_get_native_window) ? rimdroid_get_native_window() : (void*)-1;
    printf_log(LOG_NONE, "RIMDROID: FRAME[%s] Time.frameCount=%d nativeWin=%p | CB begin=%d end=%d reset=%d | "
               "RP begin=%d end=%d | DynRender begin=%d end=%d draws=%d | submit=%d acquire=%d present=%d\n",
               where, rd_unity_frame_count(), win, rd_cb_begin, rd_cb_end, rd_cb_reset,
               rd_rp_begin, rd_rp_end, rd_dynr_begin, rd_dynr_end, rd_draws,
               rd_submits, rd_acquires, rd_presents);
}

static void rd_dump_threads(void) {
    DIR* d = opendir("/proc/self/task");
    if(!d) { printf_log(LOG_NONE, "RIMDROID: THREADS — cannot open /proc/self/task\n"); return; }
    struct dirent* e;
    int running = 0, sleeping = 0, other = 0, total = 0;
    while((e = readdir(d))) {
        if(e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        ++total;
        char path[128], buf[512];
        snprintf(path, sizeof(path), "/proc/self/task/%s/stat", e->d_name);
        int fd = open(path, O_RDONLY);
        if(fd < 0) continue;
        int r = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if(r <= 0) continue;
        buf[r] = 0;
        // stat: "pid (comm) STATE ..." — find ") " then the state char after it.
        char* p = strrchr(buf, ')');
        char st = (p && p[1] == ' ') ? p[2] : '?';
        if(st == 'R') ++running; else if(st == 'S' || st == 'D') ++sleeping; else ++other;
        // Log running threads (the interesting ones) + a few others, with comm.
        char comm[64] = "";
        char* c1 = strchr(buf, '('); char* c2 = p;
        if(c1 && c2 && c2 > c1) { int L = c2 - c1 - 1; if(L > 63) L = 63; memcpy(comm, c1 + 1, L); comm[L] = 0; }
        if(st == 'R' || total <= 6)
            printf_log(LOG_NONE, "RIMDROID: THREAD tid=%s state=%c comm=%s\n", e->d_name, st, comm);
    }
    closedir(d);
    printf_log(LOG_NONE, "RIMDROID: THREADS total=%d running=%d sleeping=%d other=%d\n",
               total, running, sleeping, other);
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
    // RimDroid: trace every vk* the guest resolves (1.6 surface-bail hunt) — remove later.
    if(rname && strstr(rname, "Surface"))
        printf_log(LOG_NONE, "RIMDROID: resolve \"%s\" my=%p host=%p\n", rname, symbol, fnc);
    // get wrapper
    symbol1_t *s = getWrappedSymbol(emu, rname, 1);

    khint_t k = kh_get(symbolmap, emu->context->vkwrappers, rname);
    const char* constname = kh_key(emu->context->vkwrappers, k);
    s->addr = AddCheckBridge2(emu->context->system, s->w, symbol, fnc, 0, constname);

    void* ret = (void*)s->addr;
    printf_dlsym_prefix(0, LOG_DEBUG, "%p (%p)\n", ret, symbol);
    return ret;
}

// Guest-side bridge addresses of the present-path entry points (as returned to Unity).
// The bind-#1000 probe scans UnityPlayer's data/BSS for these values to locate the slot
// of Unity's vk function table that holds vkQueuePresentKHR — its file xrefs are the
// present call-sites we need to disassemble.
void* rd_qp_bridge = NULL;
void* rd_acq_bridge = NULL;

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
    // RimDroid: swapchain-path telemetry — Unity silently drops the surface if these resolve NULL
    // (device created without VK_KHR_swapchain would do exactly that). See rimworld_16_port.
    if(rname && (!strcmp(rname,"vkCreateSwapchainKHR") || !strcmp(rname,"vkQueuePresentKHR") ||
                 !strcmp(rname,"vkAcquireNextImageKHR")))
        printf_log(LOG_NONE, "RIMDROID: vkGetDeviceProcAddr(\"%s\") host=%p\n", rname, fnc?fnc:symbol);
    if(!symbol) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
        return NULL;    // easy
    }
    void* guestsym = resolveSymbol(emu, symbol, fnc, rname);
    if(rname && !strcmp(rname, "vkQueuePresentKHR")) {
        rd_qp_bridge = guestsym;
        printf_log(LOG_NONE, "RIMDROID: guest bridge vkQueuePresentKHR=%p\n", guestsym);
    } else if(rname && !strcmp(rname, "vkAcquireNextImageKHR")) {
        rd_acq_bridge = guestsym;
        printf_log(LOG_NONE, "RIMDROID: guest bridge vkAcquireNextImageKHR=%p\n", guestsym);
    }
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

// RimDroid: device-memory telemetry for the load-phase Vulkan OOM (rimworld_16_port session 5).
static long long rd_mem_allocs = 0, rd_mem_frees = 0, rd_mem_bytes = 0;
EXPORT int my_vkAllocateMemory(x64emu_t* emu, void* device, void* pAllocateInfo, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkAllocateMemory;
    int ret = fnc(device, pAllocateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
    if(ret==0 && pAllocateInfo) {
        long long size = *(long long*)((char*)pAllocateInfo+16);   // VkMemoryAllocateInfo.allocationSize
        uint32_t type = *(uint32_t*)((char*)pAllocateInfo+24);     // VkMemoryAllocateInfo.memoryTypeIndex
        rd_mem_bytes += size;
        ++rd_mem_allocs;
        if(rd_mem_allocs <= 20 || (rd_mem_allocs % 100)==0)
            printf_log(LOG_NONE, "RIMDROID: vkAllocateMemory #%lld size=%lldKB type=%u frees=%lld totalMB~%lld\n",
                       rd_mem_allocs, size/1024, type, rd_mem_frees, rd_mem_bytes/(1024*1024));
    } else if(ret!=0)
        printf_log(LOG_NONE, "RIMDROID: vkAllocateMemory FAILED ret=%d size=%lld (allocs=%lld frees=%lld totalMB=%lld)\n",
                   ret, pAllocateInfo?*(long long*)((char*)pAllocateInfo+16):0,
                   rd_mem_allocs, rd_mem_frees, rd_mem_bytes/(1024*1024));
    return ret;
}
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
static void* rd_offscreen_img[16];
static uint32_t rd_offscreen_fmt[16];
static int rd_offscreen_n;
EXPORT int my_vkCreateImage(x64emu_t* emu, void* device, void* pCreateInfo, my_VkAllocationCallbacks_t* pAllocator, void* p)
{
    my_VkAllocationCallbacks_t my_alloc;
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkCreateImage;
    int ret = fnc(device, pCreateInfo, find_VkAllocationCallbacks(&my_alloc, pAllocator), p);
    if(ret == 0 && pCreateInfo && p) {
        char* ci = (char*)pCreateInfo;
        uint32_t w = *(uint32_t*)(ci+28), h = *(uint32_t*)(ci+32), fmt = *(uint32_t*)(ci+24), usage = *(uint32_t*)(ci+56);
        if((usage & 0x10) && w >= 640 && h >= 360) {   // any largish COLOR_ATTACHMENT — log where Unity renders
            static int rd_col_logged = 0;
            if(rd_col_logged++ < 30)
                printf_log(LOG_NONE, "RIMDROID: COLORIMG=%p fmt=%u usage=0x%x %ux%u\n", *(void**)p, fmt, usage, w, h);
            if(w == 2340 && h == 1080 && rd_offscreen_n < 16) {
                rd_offscreen_fmt[rd_offscreen_n] = fmt;
                rd_offscreen_img[rd_offscreen_n++] = *(void**)p;
            }
        }
    }
    return ret;
}
CREATE(vkCreateImageView)

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
static int rd_swap_creates = 0, rd_swap_destroys = 0, rd_presents = 0;
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
    if(ret == 0 && pSwapchain) { rd_h_device = device; rd_h_swapchain = *(void**)pSwapchain; }  // synthetic-present capture
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

// RimDroid: inflate VK_EXT_memory_budget numbers — Unity 2022 self-declares
// "Vulkan - Out of memory!" from these (no vkAllocateMemory ever fails) at ~1.75GB
// during the 1.6 load. Report budget = 8GB, usage = 0 for every heap.
// RimDroid: the System driver aborts INSIDE host vkCmdBindDescriptorSets on the first real frame
// (Turnip died with DEVICE_LOST at the same point — same root). Log the full argument set to see
// what garbage reaches the driver. Sig: (cmdbuf, bindPoint u32, layout, firstSet u32, count u32,
// pSets, dynCount u32, pDynOffs).
// --- SDL_Window oracle (1.6 no-present hunt, expert consensus session 6) ---
// Unity renders the full frame loop but never presents: the bet is SDL_Window.flags lost
// SHOWN / kept HIDDEN|MINIMIZED after the early unmap/map round-trip (or window identity
// split-brain). Read the flags straight from guest memory: UnityPlayer BSS 0x2025818 holds
// SDL_VideoDevice* (_this); scan its pointer slots for SDL_Window candidates
// (SDL 2.0.22 layout: id@0x08 title@0x10 x/y@0x20 w/h@0x28 flags@0x40).
// On the "force" pass also repair the flags: clear HIDDEN|MINIMIZED, set SHOWN|INPUT_FOCUS|VULKAN.
#define RD_SDL_VALID_FLAGS 0x301FFFFFu
static int rd_readable(const void* p) {
    if(!p || ((uintptr_t)p & 3)) return 0;
    if(getProtection((uintptr_t)p) & PROT_READ) return 1;
    // box64's tracker misses guest ELF BSS (false negative on UnityPlayer _this slot) —
    // fall back to the kernel: msync succeeds iff the page is mapped.
    return msync((void*)((uintptr_t)p & ~(uintptr_t)4095), 4096, MS_ASYNC) == 0;
}
// SDL_Window verdict (session 6): flags were PERFECT all along (SHOWN|FOCUS, not HIDDEN/MINIMIZED,
// 2340x1080, single window, surface XID matches) and forcing +VULKAN changed nothing → the present
// gate is NOT in SDL state. The old rd_sdl_probe walk crashed on freed heap neighbors (TOCTOU) and
// has been removed. What remains: scan UnityPlayer data+BSS for the vk-table slot holding the
// vkQueuePresentKHR bridge — its file xrefs are the present call-sites to disassemble.
static void rd_vkslot_scan(void) {
    static char* delta = NULL;
    static int scanned = 0;
    if(scanned) return;
    if(!delta) {
        for(int i = 0; i < my_context->elfsize; ++i) {
            elfheader_t* h = my_context->elfs[i];
            if(h && ElfName(h) && strstr(ElfName(h), "UnityPlayer")) {
                delta = (char*)GetElfDelta(h);
                break;
            }
        }
        if(!delta) { printf_log(LOG_NONE, "RIMDROID: VKSLOT no UnityPlayer elf\n"); return; }
    }
    extern void* rd_qp_bridge; extern void* rd_acq_bridge;
    if(!rd_qp_bridge && !rd_acq_bridge) return;
    scanned = 1;
    for(uintptr_t a = (uintptr_t)delta + 0x1ed7000; a < (uintptr_t)delta + 0x2200000; a += 8) {
        if(!(a & 4095) || a == (uintptr_t)delta + 0x1ed7000)
            if(msync((void*)(a & ~(uintptr_t)4095), 4096, MS_ASYNC) != 0) { a += 4088; continue; }
        void* v = *(void**)a;
        if(v && (v == rd_qp_bridge || v == rd_acq_bridge))
            printf_log(LOG_NONE, "RIMDROID: VKSLOT %s at UnityPlayer+0x%lx\n",
                       (v == rd_qp_bridge) ? "QueuePresent" : "AcquireNextImage",
                       (unsigned long)(a - (uintptr_t)delta));
    }
    printf_log(LOG_NONE, "RIMDROID: VKSLOT scan done\n");
}

// Command-buffer lifecycle telemetry: 1.4M binds but only 5 submits — is Unity writing ONE
// endless cmdbuf (never flushed during scene load) or recording+discarding whole frames?
static int rd_cb_begin, rd_cb_end, rd_cb_reset, rd_rp_begin, rd_rp_end;
EXPORT int my_vkBeginCommandBuffer(x64emu_t* emu, void* cmdbuf, void* info)
{
    iFpp_t fnc = (iFpp_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpp_t)my->vkBeginCommandBuffer;
    ++rd_cb_begin;
    if(rd_cb_begin <= 8 || (rd_cb_begin % 200) == 0)
        printf_log(LOG_NONE, "RIMDROID: CB Begin#%d cmd=%p (end=%d reset=%d rp=%d)\n",
                   rd_cb_begin, cmdbuf, rd_cb_end, rd_cb_reset, rd_rp_begin);
    return fnc(cmdbuf, info);
}
EXPORT int my_vkEndCommandBuffer(x64emu_t* emu, void* cmdbuf)
{
    iFp_t fnc = (iFp_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFp_t)my->vkEndCommandBuffer;
    ++rd_cb_end;
    if(rd_cb_end <= 8 || (rd_cb_end % 200) == 0)
        printf_log(LOG_NONE, "RIMDROID: CB End#%d cmd=%p (begin=%d reset=%d rp=%d)\n",
                   rd_cb_end, cmdbuf, rd_cb_begin, rd_cb_reset, rd_rp_begin);
    return fnc(cmdbuf);
}
EXPORT int my_vkResetCommandBuffer(x64emu_t* emu, void* cmdbuf, uint32_t flags)
{
    iFpu_t fnc = (iFpu_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpu_t)my->vkResetCommandBuffer;
    ++rd_cb_reset;
    if(rd_cb_reset <= 8 || (rd_cb_reset % 200) == 0)
        printf_log(LOG_NONE, "RIMDROID: CB Reset#%d cmd=%p\n", rd_cb_reset, cmdbuf);
    return fnc(cmdbuf, flags);
}
EXPORT void my_vkCmdBeginRenderPass(x64emu_t* emu, void* cmdbuf, void* info, uint32_t contents)
{
    vFppu_t fnc = (vFppu_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFppu_t)my->vkCmdBeginRenderPass;
    ++rd_rp_begin;
    if(rd_rp_begin <= 8 || (rd_rp_begin % 1000) == 0)
        printf_log(LOG_NONE, "RIMDROID: RenderPass Begin#%d cmd=%p\n", rd_rp_begin, cmdbuf);
    fnc(cmdbuf, info, contents);
}
EXPORT void my_vkCmdEndRenderPass(x64emu_t* emu, void* cmdbuf)
{
    vFp_t fnc = (vFp_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFp_t)my->vkCmdEndRenderPass;
    ++rd_rp_end;
    if(rd_rp_end <= 8 || (rd_rp_end % 1000) == 0)
        printf_log(LOG_NONE, "RIMDROID: RenderPass End#%d cmd=%p\n", rd_rp_end, cmdbuf);
    fnc(cmdbuf);
}
// Blind-spot closers (Sonnet/Codex): Unity 2022 may use DYNAMIC RENDERING (Vulkan 1.3) not legacy
// render passes, and our RP counter only saw legacy → "RP=0" could be a lie. Count these + draws so we
// know if the GPU actually renders per frame (vs render also skipped).
static int rd_dynr_begin, rd_dynr_end, rd_draws;
EXPORT void my_vkCmdBeginRendering(x64emu_t* emu, void* cmdbuf, void* info)
{
    vFpp_t fnc = (vFpp_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFpp_t)my->vkCmdBeginRendering;
    ++rd_dynr_begin;
    if(rd_dynr_begin <= 4 || (rd_dynr_begin % 2000) == 0)
        printf_log(LOG_NONE, "RIMDROID: DynRendering Begin#%d cmd=%p\n", rd_dynr_begin, cmdbuf);
    fnc(cmdbuf, info);
}
EXPORT void my_vkCmdEndRendering(x64emu_t* emu, void* cmdbuf)
{
    vFp_t fnc = (vFp_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFp_t)my->vkCmdEndRendering;
    ++rd_dynr_end;
    fnc(cmdbuf);
}
EXPORT void my_vkCmdDraw(x64emu_t* emu, void* cmdbuf, uint32_t vtx, uint32_t inst, uint32_t fv, uint32_t fi)
{
    vFpuuuu_t fnc = (vFpuuuu_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFpuuuu_t)my->vkCmdDraw;
    ++rd_draws;
    fnc(cmdbuf, vtx, inst, fv, fi);
}
EXPORT void my_vkCmdDrawIndexed(x64emu_t* emu, void* cmdbuf, uint32_t idx, uint32_t inst, uint32_t fidx, int32_t voff, uint32_t finst)
{
    vFpuuuiu_t fnc = (vFpuuuiu_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFpuuuiu_t)my->vkCmdDrawIndexed;
    ++rd_draws;
    fnc(cmdbuf, idx, inst, fidx, voff, finst);
}

extern void rd_x11_diag_dump(const char* where);

// Synthetic clear+present WSI oracle (Codex #6) — body here, where 'my' (host fn table) is in scope.
static void rd_synthetic_present(void) {
    void* win = (&rimdroid_get_native_window && rimdroid_get_native_window) ? rimdroid_get_native_window() : (void*)-1;
    printf_log(LOG_NONE, "RIMDROID: SYNPRESENT begin nativeWin=%p dev=%p swap=%p queue=%p imgs=%u qf=%u\n",
               win, rd_h_device, rd_h_swapchain, rd_h_queue, rd_h_image_count, rd_h_queue_family);
    if(!rd_h_device || !rd_h_swapchain || !rd_h_queue || !rd_h_image_count) {
        printf_log(LOG_NONE, "RIMDROID: SYNPRESENT missing handles — abort\n");
        return;
    }
    typedef int  (*p_cf)(void*, const void*, const void*, void**);
    typedef int  (*p_ac)(void*, void*, uint64_t, void*, void*, uint32_t*);
    typedef int  (*p_wf)(void*, uint32_t, void* const*, uint32_t, uint64_t);
    typedef int  (*p_rf)(void*, uint32_t, void* const*);
    typedef int  (*p_cp)(void*, const void*, const void*, void**);
    typedef int  (*p_al)(void*, const void*, void**);
    typedef int  (*p_bc)(void*, const void*);
    typedef int  (*p_ec)(void*);
    typedef void (*p_ba)(void*, uint32_t, uint32_t, uint32_t, uint32_t, const void*, uint32_t, const void*, uint32_t, const void*);
    typedef void (*p_cl)(void*, void*, uint32_t, const void*, uint32_t, const void*);
    typedef int  (*p_su)(void*, uint32_t, const void*, void*);
    typedef int  (*p_pr)(void*, const void*);
    // GOM functions have a my-> host slot; GO functions don't — resolve those via host vkGetDeviceProcAddr.
    typedef void* (*p_gdpa)(void*, const char*);
    p_gdpa gdpa = (p_gdpa)my->vkGetDeviceProcAddr;
    p_cf CreateFence=(p_cf)my->vkCreateFence; p_ac Acquire=(p_ac)my->vkAcquireNextImageKHR;
    p_wf WaitFences=(p_wf)my->vkWaitForFences; p_rf ResetFences=(p_rf)my->vkResetFences;
    p_cp CreatePool=(p_cp)my->vkCreateCommandPool;
    p_bc BeginCmd=(p_bc)my->vkBeginCommandBuffer; p_ec EndCmd=(p_ec)my->vkEndCommandBuffer;
    p_su Submit=(p_su)my->vkQueueSubmit; p_pr Present=(p_pr)my->vkQueuePresentKHR;
    typedef void (*p_bl)(void*, void*, uint32_t, void*, uint32_t, uint32_t, const void*, uint32_t);
    p_al AllocCmd = gdpa ? (p_al)gdpa(rd_h_device, "vkAllocateCommandBuffers") : NULL;
    p_ba Barrier  = gdpa ? (p_ba)gdpa(rd_h_device, "vkCmdPipelineBarrier") : NULL;
    p_cl Clear    = gdpa ? (p_cl)gdpa(rd_h_device, "vkCmdClearColorImage") : NULL;
    p_bl Blit     = gdpa ? (p_bl)gdpa(rd_h_device, "vkCmdBlitImage") : NULL;
    if(!CreateFence||!Acquire||!WaitFences||!ResetFences||!CreatePool||!AllocCmd||!BeginCmd||!EndCmd||!Barrier||!Clear||!Submit||!Present){
        printf_log(LOG_NONE, "RIMDROID: SYNPRESENT a host fn is NULL — abort\n"); return;
    }
    void* dev = rd_h_device;
    struct { uint32_t sType, pad; const void* pNext; uint32_t flags, pad2; } fci = { 8,0,NULL,0,0 };
    void* fence = NULL;
    int r = CreateFence(dev, &fci, NULL, &fence);
    uint32_t idx = 0;
    r = Acquire(dev, rd_h_swapchain, 2000000000ULL, NULL, fence, &idx);
    printf_log(LOG_NONE, "RIMDROID: SYNPRESENT acquire ret=%d idx=%u\n", r, idx);
    if(r != 0 && r != 1000001003) { printf_log(LOG_NONE, "RIMDROID: SYNPRESENT acquire failed\n"); return; }
    void* fences[1] = { fence };
    WaitFences(dev, 1, fences, 1, 2000000000ULL); ResetFences(dev, 1, fences);
    if(idx >= rd_h_image_count) { printf_log(LOG_NONE, "RIMDROID: SYNPRESENT idx OOB\n"); return; }
    void* image = rd_h_images[idx];
    struct { uint32_t sType, pad; const void* pNext; uint32_t flags, queueFamily; } cpi = { 39,0,NULL,0x2,rd_h_queue_family };
    void* pool = NULL; r = CreatePool(dev, &cpi, NULL, &pool);
    struct { uint32_t sType, pad; const void* pNext; void* pool; uint32_t level, count; } cai = { 40,0,NULL,pool,0,1 };
    void* cmd = NULL; r = AllocCmd(dev, &cai, &cmd);
    printf_log(LOG_NONE, "RIMDROID: SYNPRESENT pool=%p cmd=%p\n", pool, cmd);
    if(!pool || !cmd) return;
    struct { uint32_t sType, pad; const void* pNext; uint32_t flags, pad2; const void* inh; } bi = { 42,0,NULL,0x1,0,NULL };
    BeginCmd(cmd, &bi);
    struct VkImgBarrier { uint32_t sType, pad; const void* pNext; uint32_t srcA, dstA, oldL, newL, srcQF, dstQF; void* image;
                          uint32_t aspect, baseMip, levels, baseLayer, layers; };
    struct { uint32_t aspect, baseMip, levels, baseLayer, layers; } range = { 0x1,0,1,0,1 };
    (void)Blit; (void)image; (void)range;
    // Unity acquired swapchain image 0 at init and (per 0 offscreen images) likely renders INTO it every
    // frame but never presents. Present image 0 DIRECTLY: transition it (guess it's left in
    // COLOR_ATTACHMENT_OPTIMAL=2) to PRESENT_SRC, then present index 0 = show Unity's actual frame.
    uint32_t present_idx = 0;
    void* img0 = rd_h_images[0];
    printf_log(LOG_NONE, "RIMDROID: SYNPRESENT presenting Unity's swapchain image0=%p directly (idx 0)\n", img0);
    struct VkImgBarrier bp = { 45,0,NULL, 0x100,0, 2,1000001002, ~0u,~0u, img0, 0x1,0,1,0,1 };
    Barrier(cmd, 0x400, 0x2000, 0, 0,NULL,0,NULL, 1,&bp);
    EndCmd(cmd);
    struct { uint32_t sType, pad; const void* pNext; uint32_t waitC, pad2; const void* waitS; const void* waitStg;
             uint32_t cmdC, pad3; void* const* cmds; uint32_t sigC, pad4; const void* sigS; } si =
        { 4,0,NULL, 0,0,NULL,NULL, 1,0,&cmd, 0,0,NULL };
    r = Submit(rd_h_queue, 1, &si, fence);
    WaitFences(dev, 1, fences, 1, 2000000000ULL);
    printf_log(LOG_NONE, "RIMDROID: SYNPRESENT submit ret=%d\n", r);
    struct { uint32_t sType, pad; const void* pNext; uint32_t waitC, pad2; const void* waitS;
             uint32_t swapC, pad3; void* const* swaps; const uint32_t* idxs; const int* results; } pi =
        { 1000001001,0,NULL, 0,0,NULL, 1,0,&rd_h_swapchain,&present_idx,NULL };
    r = Present(rd_h_queue, &pi);
    printf_log(LOG_NONE, "RIMDROID: SYNPRESENT *** vkQueuePresentKHR(image0) ret=%d *** RimWorld frame should show if it renders to swapchain img0\n", r);
}

typedef void (*rd_vFpupuupup_t)(void*, uint32_t, void*, uint32_t, uint32_t, void*, uint32_t, void*);
EXPORT void my_vkCmdBindDescriptorSets(x64emu_t* emu, void* cmdbuf, uint32_t bindPoint, void* layout,
        uint32_t firstSet, uint32_t setCount, void** pSets, uint32_t dynCount, void* pDynOffs)
{
    rd_vFpupuupup_t fnc = (rd_vFpupuupup_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(rd_vFpupuupup_t)my->vkCmdBindDescriptorSets;
    static int rd_bind_calls = 0;
    ++rd_bind_calls;
    if(rd_bind_calls <= 12 || (rd_bind_calls % 500) == 0)
        printf_log(LOG_NONE, "RIMDROID: BindDescriptorSets #%d cmd=%p bp=%u layout=%p first=%u n=%u sets[0]=%p dyn=%u\n",
                   rd_bind_calls, cmdbuf, bindPoint, layout, firstSet, setCount,
                   (pSets && setCount) ? pSets[0] : NULL, dynCount);
    // Locate Unity's vk-table slot for QueuePresent/Acquire (bridges resolved by then)
    if(vd_vk_flag("VALDROID_VK_RIMWORLD_PROBES") && rd_bind_calls == 1000)
        rd_vkslot_scan();
    // The OnGUI spin is internal to RimWorld — read Verse.LongEventHandler state to see WHY it never
    // leaves the loading-screen draw path. Runs once, well into the storm (state is settled by #3000).
    if(vd_vk_flag("VALDROID_VK_RIMWORLD_PROBES") && (rd_bind_calls == 1200 || rd_bind_calls == 2500)) {
        printf_log(LOG_NONE, "RIMDROID: === LONGEVENT sample at bind #%d ===\n", rd_bind_calls);
        rd_dump_longevent();
        rd_dump_unity_event_current();
        rd_dump_threads();
        char where[64];
        snprintf(where, sizeof(where), "bind#%d", rd_bind_calls);
        rd_dump_frame_summary(where);
        rd_dump_display_state();
        rd_x11_diag_dump(where);
    }
    // SURVIVAL TEST (Q1 stuck-vs-slow): once we're clearly in the OnGUI spin, STOP feeding the driver
    // so per-frame GPU memory stops growing and the process doesn't OOM. This lets the load worker run
    // for minutes. Re-sample LongEvent + threads every 200k skipped binds: if the worker's task/logs
    // advance → it was SLOW; if b__10_1 stays put and no def-load logs → STUCK. (Render is garbage
    // during the skip — we're only watching the worker.) Toggle off by deleting the rd_x11 skip marker.
    int rd_skip = vd_vk_flag("VALDROID_VK_DIAG_SKIP_DESCRIPTOR_BINDS");
    // WSI oracle: once we're clearly in the no-present spin (survival mode active), present a magenta
    // frame OURSELVES to prove the Android surface can show anything. Runs once.
    // Present Unity's swapchain image0 at several points — early (#30000, loading) AND after the load
    // finishes (#500000/#1000000, menu should be composited). Shows Unity's actual frame content.
    if(vd_vk_flag("VALDROID_VK_DIAG_SYNTHETIC_PRESENT") && (rd_bind_calls == 30000 || rd_bind_calls == 500000 || rd_bind_calls == 1000000 || rd_bind_calls == 1300000))
        rd_synthetic_present();
    if(rd_skip && rd_bind_calls > 15000) {
        if(rd_bind_calls == 15001)
            printf_log(LOG_NONE, "RIMDROID: SURVIVAL — skipping host binds from #15001 to keep process alive\n");
        if((rd_bind_calls % 200000) == 0) {
            printf_log(LOG_NONE, "RIMDROID: === SURVIVAL re-sample at bind #%d ===\n", rd_bind_calls);
            rd_dump_longevent();
            rd_dump_unity_event_current();
            rd_dump_threads();
            char where[64];
            snprintf(where, sizeof(where), "bind#%d", rd_bind_calls);
            rd_dump_frame_summary(where);
        rd_dump_display_state();
            rd_x11_diag_dump(where);
        }
        return;   // skip the host driver call
    }
    fnc(cmdbuf, bindPoint, layout, firstSet, setCount, pSets, dynCount, pDynOffs);
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
                usage[i] = rd_mem_bytes; // consistent-ish tracked usage
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
DESTROY(vkDestroyImage)
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

static int rd_swap_image_queries = 0, rd_acquires = 0, rd_submits = 0;
static int rd_fence_waits = 0, rd_fence_statuses = 0, rd_fence_resets = 0;
EXPORT int my_vkGetSwapchainImagesKHR(x64emu_t* emu, void* device, void* swapchain, uint32_t* pCount, void* pImages)
{
    iFpppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpppp_t)my->vkGetSwapchainImagesKHR;
    int n = ++rd_swap_image_queries;
    printf_log(LOG_NONE, "RIMDROID: vkGetSwapchainImagesKHR ENTER #%d swap=%p query=%d cap=%u fnc=%p\n",
               n, swapchain, pImages?1:0, pCount?*pCount:0, fnc);
    int ret = fnc(device, swapchain, pCount, pImages);
    printf_log(LOG_NONE, "RIMDROID: vkGetSwapchainImagesKHR EXIT #%d ret=%d count=%u\n",
               n, ret, pCount?*pCount:0);
    if(ret == 0 && pImages && pCount) {   // synthetic-present capture
        uint32_t c = *pCount; if(c > 8) c = 8;
        for(uint32_t i = 0; i < c; ++i) rd_h_images[i] = ((void**)pImages)[i];
        rd_h_image_count = c;
    }
    return ret;
}
EXPORT void my_vkGetDeviceQueue(x64emu_t* emu, void* device, uint32_t family, uint32_t index, void** pQueue)
{
    vFpuup_t fnc = (vFpuup_t)getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(vFpuup_t)my->vkGetDeviceQueue;
    fnc(device, family, index, pQueue);
    if(pQueue && *pQueue) { rd_h_queue = *pQueue; rd_h_queue_family = family; }  // synthetic-present capture
}

EXPORT int my_vkAcquireNextImageKHR(x64emu_t* emu, void* device, void* swapchain, uint64_t timeout,
                                    void* semaphore, void* fence, uint32_t* pImageIndex)
{
    iFppUppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFppUppp_t)my->vkAcquireNextImageKHR;
    int n = ++rd_acquires;
    if(n <= 10 || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkAcquireNextImageKHR ENTER #%d swap=%p timeout=%llu sem=%p fence=%p fnc=%p\n",
                   n, swapchain, (unsigned long long)timeout, semaphore, fence, fnc);
    // Guest call-site of the ONE acquire: the "should we acquire this frame" branch lives
    // just above it in UnityPlayer — dump the stack so we can disassemble that condition.
    if(n <= 3) {
        extern int my_backtrace_ip(x64emu_t* emu, void** buffer, int size);
        extern char** my_backtrace_symbols(x64emu_t* emu, uintptr_t* buffer, int size);
        void* buf[24];
        int fr = my_backtrace_ip(emu, buf, 24);
        char** syms = my_backtrace_symbols(emu, (uintptr_t*)buf, fr);
        printf_log(LOG_NONE, "RIMDROID: acquire backtrace #%d (%d frames):\n", n, fr);
        for(int i = 0; i < fr; ++i)
            printf_log(LOG_NONE, "RIMDROID:   ABT %s\n", syms ? syms[i] : "?");
        if(syms) box_free(syms);
    }
    int ret = fnc(device, swapchain, timeout, semaphore, fence, pImageIndex);
    if(n <= 10 || ret != 0 || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkAcquireNextImageKHR EXIT #%d ret=%d image=%u\n",
                   n, ret, pImageIndex?*pImageIndex:~0u);
    if(ret == 1000001003 /*VK_SUBOPTIMAL_KHR*/ && vd_vk_flag("VALDROID_VK_IGNORE_SUBOPTIMAL"))
        ret = 0;   // see my_vkQueuePresentKHR
    return ret;
}

EXPORT int my_vkAcquireNextImage2KHR(x64emu_t* emu, void* device, void* pAcquireInfo, uint32_t* pImageIndex)
{
    iFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFppp_t)my->vkAcquireNextImage2KHR;
    int n = ++rd_acquires;
    printf_log(LOG_NONE, "RIMDROID: vkAcquireNextImage2KHR ENTER #%d info=%p fnc=%p\n", n, pAcquireInfo, fnc);
    int ret = fnc(device, pAcquireInfo, pImageIndex);
    printf_log(LOG_NONE, "RIMDROID: vkAcquireNextImage2KHR EXIT #%d ret=%d image=%u\n",
               n, ret, pImageIndex?*pImageIndex:~0u);
    return ret;
}

EXPORT int my_vkQueueSubmit(x64emu_t* emu, void* queue, uint32_t submitCount, void* pSubmits, void* fence)
{
    iFpupp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpupp_t)my->vkQueueSubmit;
    int n = ++rd_submits;
    if(n <= 10 || (n % 300) == 0) {
        uint32_t waits = 0, cmds = 0, signals = 0;
        void* wait0 = NULL; void* cmd0 = NULL; void* signal0 = NULL;
        if(submitCount && pSubmits) {
            const char* si = (const char*)pSubmits;
            waits = *(uint32_t*)(si+16);
            cmds = *(uint32_t*)(si+40);
            signals = *(uint32_t*)(si+56);
            void** pWait = *(void***)(si+24);
            void** pCmd = *(void***)(si+48);
            void** pSignal = *(void***)(si+64);
            if(waits && pWait) wait0 = pWait[0];
            if(cmds && pCmd) cmd0 = pCmd[0];
            if(signals && pSignal) signal0 = pSignal[0];
        }
        printf_log(LOG_NONE, "RIMDROID: vkQueueSubmit ENTER #%d submits=%u wait=%u(%p) cmd=%u(%p) signal=%u(%p) fence=%p fnc=%p\n",
                   n, submitCount, waits, wait0, cmds, cmd0, signals, signal0, fence, fnc);
    }
    int ret = fnc(queue, submitCount, pSubmits, fence);
    if(n <= 10 || ret != 0 || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkQueueSubmit EXIT #%d ret=%d\n", n, ret);
    return ret;
}

EXPORT int my_vkWaitForFences(x64emu_t* emu, void* device, uint32_t count, void* pFences,
                              uint32_t waitAll, uint64_t timeout)
{
    iFpupuU_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpupuU_t)my->vkWaitForFences;
    int n = ++rd_fence_waits;
    void* fence0 = (count && pFences) ? *(void**)pFences : NULL;
    if(n <= 30 || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkWaitForFences ENTER #%d count=%u first=%p all=%u timeout=%llu fnc=%p\n",
                   n, count, fence0, waitAll, (unsigned long long)timeout, fnc);
    int ret = fnc(device, count, pFences, waitAll, timeout);
    if(n <= 30 || (ret != 0 && ret != 2) || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkWaitForFences EXIT #%d ret=%d\n", n, ret);
    return ret;
}

EXPORT int my_vkGetFenceStatus(x64emu_t* emu, void* device, void* fence)
{
    iFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpp_t)my->vkGetFenceStatus;
    int n = ++rd_fence_statuses;
    int ret = fnc(device, fence);
    if(n <= 30 || (ret != 0 && ret != 1) || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkGetFenceStatus #%d fence=%p ret=%d\n", n, fence, ret);
    return ret;
}

EXPORT int my_vkResetFences(x64emu_t* emu, void* device, uint32_t count, void* pFences)
{
    iFpup_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpup_t)my->vkResetFences;
    int n = ++rd_fence_resets;
    void* fence0 = (count && pFences) ? *(void**)pFences : NULL;
    int ret = fnc(device, count, pFences);
    if(n <= 30 || ret != 0 || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkResetFences #%d count=%u first=%p ret=%d\n",
                   n, count, fence0, ret);
    return ret;
}

EXPORT int my_vkQueueWaitIdle(x64emu_t* emu, void* queue)
{
    iFp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFp_t)my->vkQueueWaitIdle;
    printf_log(LOG_NONE, "RIMDROID: vkQueueWaitIdle ENTER queue=%p fnc=%p\n", queue, fnc);
    int ret = fnc(queue);
    printf_log(LOG_NONE, "RIMDROID: vkQueueWaitIdle EXIT ret=%d\n", ret);
    return ret;
}

EXPORT int my_vkDeviceWaitIdle(x64emu_t* emu, void* device)
{
    iFp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFp_t)my->vkDeviceWaitIdle;
    printf_log(LOG_NONE, "RIMDROID: vkDeviceWaitIdle ENTER device=%p fnc=%p\n", device, fnc);
    int ret = fnc(device);
    printf_log(LOG_NONE, "RIMDROID: vkDeviceWaitIdle EXIT ret=%d\n", ret);
    return ret;
}

EXPORT int my_vkQueuePresentKHR(x64emu_t* emu, void* queue, void* pPresentInfo)
{
    iFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=(iFpp_t)my->vkQueuePresentKHR;
    int n = ++rd_presents;
    if(n <= 10 || (n % 300) == 0) {
        const char* pi = (const char*)pPresentInfo;
        uint32_t count = pi?*(uint32_t*)(pi+32):0;
        uint32_t image = (pi && count && *(uint32_t**)(pi+48)) ? **(uint32_t**)(pi+48) : ~0u;
        printf_log(LOG_NONE, "RIMDROID: vkQueuePresentKHR ENTER #%d count=%u image=%u fnc=%p\n",
                   n, count, image, fnc);
    }
    int ret = fnc(queue, pPresentInfo);
    if(n <= 10 || ret != 0 || (n % 300) == 0)
        printf_log(LOG_NONE, "RIMDROID: vkQueuePresentKHR EXIT #%d ret=%d\n", n, ret);
    // ValDroid: Android WSI answers VK_SUBOPTIMAL_KHR whenever the swapchain's preTransform differs
    // from the panel rotation. The Unity Linux player never pre-rotates, so it recreates the surface
    // and swapchain on every frame and nothing ever shows. The frame WAS presented; report success and
    // let the compositor rotate.
    if(ret == 1000001003 /*VK_SUBOPTIMAL_KHR*/ && vd_vk_flag("VALDROID_VK_IGNORE_SUBOPTIMAL")) {
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

EXPORT void my_vkFreeMemory(x64emu_t* emu, void* device, void* mem, my_VkAllocationCallbacks_t* pAllocator)
{
    my_VkAllocationCallbacks_t my_alloc;
    vFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->vkFreeMemory;
    ++rd_mem_frees;
    if(rd_mem_frees <= 20 || (rd_mem_frees % 100)==0)
        printf_log(LOG_NONE, "RIMDROID: vkFreeMemory #%lld allocs=%lld handle=%p\n",
                   rd_mem_frees, rd_mem_allocs, mem);
    fnc(device, mem, find_VkAllocationCallbacks(&my_alloc, pAllocator));
}

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

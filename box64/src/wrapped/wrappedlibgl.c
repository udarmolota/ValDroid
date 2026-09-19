#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define _GNU_SOURCE         /* See feature_test_macros(7) */
#include <dlfcn.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/syscall.h>

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
#include "gltools.h"

const char* libglName = "libGL.so.1";
#define ALTNAME "libGL.so"
#define LIBNAME libgl

#include "generated/wrappedlibgltypes.h"

#include "wrappercallback.h"

// FIXME: old wrapped* type of file, cannot use generated/wrappedlibgltypes.h

// ---- RimDroid GLX -> ZFA bridge (RimWorld 1.6) ------------------------------
// RimWorld 1.6 (Unity 2022.3, no SDL) creates its OpenGL Core context via GLX
// directly: glXChooseVisual -> glXCreateContext -> glXMakeCurrent -> glXSwapBuffers.
// On Android there is no host libGL/GLX, so box64 used to emulate the guest libGL
// shim whose glXCreateContext returns NULL -> "Unable to find a supported OpenGL
// core profile" -> Unity falls through to Vulkan (whose present path is gated/broken).
// Instead we feed Unity the SAME ZFA context (real desktop GL Core over Mesa Zink ->
// Turnip) that the 1.5 SDL path uses, by intercepting the glX entry points here and
// routing them to zfa*. Present goes glXSwapBuffers -> rimdroid_zfa_swap -> ZFA's
// Vulkan swapchain -> our ANativeWindow; Unity never calls vkQueuePresentKHR, so the
// broken Vulkan display/present gate is bypassed entirely.
// Requires BOX64_LIBGL=libzfa.so (so box64 WRAPS libGL.so.1 host-side instead of
// emulating the guest shim) + renderer=ZINK_ZFA (rimdroid.c creates g_zfa_context).
extern __attribute__((weak)) void* g_zfa_context;
extern __attribute__((weak)) void* g_zfa_handle;   // libzfa.so handle from rimdroid.c (rimdroid linker namespace)
extern __attribute__((weak)) int   rimdroid_zfa_make_current(void);
extern __attribute__((weak)) void  rimdroid_zfa_swap(void);
extern __attribute__((weak)) int   rimdroid_zfa_release_current(void);
extern __attribute__((weak)) void  rimdroid_frame_tick(void);
// Shared GL proc resolver (defined non-static in wrappedsdl2.c).
extern void* rimdroid_gl_getprocaddr(x64emu_t* emu, bridge_t* bridge, glprocaddress_t pa, const char* rname);

static int rd_zfa_active(void) { return (&g_zfa_context) && g_zfa_context; }

// ---- EGL-translator variant of the same bridge (2026-08-09) ------------------
// RimWorld 1.6 on a GL->GLES translator (MobileGlues / NG-GL4ES): same glX intercepts,
// but the real context is the EGL one rimdroid.c created for the GL4ES plumbing
// (BOX64_LIBGL=libmobileglues.so etc.). The translators have no glX of their own
// (MobileGlues exports ONLY glXGetProcAddress), so the ZFA-shaped bridge carries them:
// create/MakeCurrent alias the single EGL context, present = eglSwapBuffers.
// Single-threaded only for now: EGL contexts don't migrate threads without a release
// protocol (the 1.5 threaded A/B black-screened on exactly that), so getArgs forces
// -force-gfx-direct whenever a translator is active.
extern __attribute__((weak)) void* g_egl_context;
extern __attribute__((weak)) int   rimdroid_eglt_make_current(void);
extern __attribute__((weak)) int   rimdroid_eglt_release_current(void);
extern __attribute__((weak)) void  rimdroid_eglt_swap(void);
// Level-3 multi-context factory (rimdroid.c) — real EGL contexts per Unity logical context.
extern __attribute__((weak)) void* rimdroid_eglt_create_shared(void);
extern __attribute__((weak)) int   rimdroid_eglt_make_current_on(void* ctx, int with_window);
extern __attribute__((weak)) void  rimdroid_eglt_destroy_ctx(void* ctx);
static int rd_eglt_active(void) { return !((&g_zfa_context) && g_zfa_context) && (&g_egl_context) && g_egl_context; }
// RIMDROID_GLT_MULTICTX=1 (experimental, 2026-08-13): give every Unity GLX context its OWN real
// EGL context (share group with the primary) instead of aliasing them all onto one. This is the
// fix for the threaded-mode texture corruption (brief Level 3: zero upload collisions with TLS
// scratch, red patches persist ⇒ the corruption is context-state bleed). Objects are shared by
// EGL itself; per-context state is the driver's job; only the PRESENTER holds the window surface
// (workers are surfaceless/pbuffer). Presenter = whichever context swaps first; a presenter
// change does the deferred-steal dance (skip one present, converge next frame).
static int rd_eglt_multictx(void) {
    static int on = -1;
    if (on < 0) {
        const char* e = getenv("RIMDROID_GLT_MULTICTX");
        on = (e && e[0] == '1' && rimdroid_eglt_create_shared) ? 1 : 0;
        if (on) printf_log(LOG_NONE, "RIMDROID GLT MULTICTX enabled: real EGL context per GLX context\n");
    }
    return on;
}
#define RD_GLX_MAX_CTX 8
static struct { void* fake; void* real; } g_glx_ctx_map[RD_GLX_MAX_CTX];
static void* g_glx_presenter = NULL;    // the real ctx currently holding the window surface
static void* rd_glx_real_of(void* fake) {
    for (int i = 0; i < RD_GLX_MAX_CTX; i++)
        if (g_glx_ctx_map[i].fake == fake) return g_glx_ctx_map[i].real;
    return g_egl_context;   // unknown alias: fall back to the primary
}
// Which logical context the calling code is on, as a small slot index — wrappedsdl2.c keys its
// per-context texture-binding shadow on this (with one real context per logical context, the
// driver keeps real state per context; our upload/shrink bookkeeping must match).
// Per-thread since 2026-08-13: "which context am I on" is a property of the CALLING THREAD, and a
// process-wide answer made one thread's uploads consult the other thread's bindings.
__thread int rd_glx_ctx_slot = 0;
static int rd_glx_slot_of(void* fake) {
    for (int i = 0; i < RD_GLX_MAX_CTX; i++)
        if (g_glx_ctx_map[i].fake == fake) return i;
    return 0;
}
// One dispatch layer so every glX intercept below stays backend-agnostic.
static int rd_bridge_active(void) { return rd_zfa_active() || rd_eglt_active(); }
static void* rd_bridge_ctx(void) { return rd_zfa_active() ? g_zfa_context : g_egl_context; }
static int rd_bridge_make_current(void) {
    if (rd_zfa_active()) return rimdroid_zfa_make_current ? rimdroid_zfa_make_current() : 0;
    return rimdroid_eglt_make_current ? rimdroid_eglt_make_current() : 0;
}
static int rd_bridge_release_current(void) {
    if (rd_zfa_active()) return rimdroid_zfa_release_current ? rimdroid_zfa_release_current() : -1;
    return rimdroid_eglt_release_current ? rimdroid_eglt_release_current() : -1;
}
static void rd_bridge_swap(void) {
    if (rd_zfa_active()) { if (rimdroid_zfa_swap) rimdroid_zfa_swap(); }
    else if (rimdroid_eglt_swap) rimdroid_eglt_swap();
}

// GLX bridge state. g_glx_ctx_current = the opaque handle Unity currently holds
// (a distinct alias per glXCreateContext, all aliasing the one ZFA context — Unity
// creates a 2nd shared context and returning the same pointer twice confuses its
// bookkeeping). g_glx_display/drawable cached for GetCurrent*.
//
// PER-THREAD (2026-08-13, the threaded red-texture hunt). GLX defines the current context as
// thread state, and Unity's threaded renderer migrates contexts between its main and render
// threads. Holding this process-wide broke that in two ways: one thread's glXMakeCurrent(NULL)
// wiped the record for BOTH, and the "already current on this thread" fast path below answered
// from whichever thread wrote last — so a bind could be skipped for a thread that had nothing
// current, or attempted for a context still current elsewhere (EGL rejects that: the thread is
// then left with NO context and every GL call it makes is silently discarded — including texture
// mip uploads, which is what leaves undefined levels behind).
// g_glx_display/g_glx_drawable stay process-wide on purpose: our bridge fakes exactly ONE X
// display and ONE drawable, so a shared answer is always the right one, while a per-thread copy
// would hand NULL to any thread that queries before it ever binds.
static __thread void*     g_glx_ctx_current = NULL;
static void*              g_glx_display     = NULL;
static uintptr_t          g_glx_drawable    = 0;
static unsigned long      g_glx_create_n    = 0;
// Whether THIS thread has the context bound, and which thread that is (kept for the ZFA path's
// logging/assertions; with TLS it is always self). Mesa's st_api context is per-thread;
// zfaFlushFront on a thread with NO current context derefs NULL (+0xbc) — seen on-device
// when Unity's loading phase swaps from a different thread than the one that bound the
// context (and glXMakeCurrent(NULL) unbinds don't release: libzfa lacks zfaReleaseCurrent).
static __thread pthread_t g_glx_current_tid;
static __thread int       g_glx_have_current = 0;
// Swap-phase marker for the SEGV logger (signals.c): 0=outside swap, 1=inside pre-flush
// glFinish (batch already dead), 2=inside zfaFlushFront (present path is the killer).
volatile int rd_glx_swap_phase = 0;

// XVisualInfo we hand back from glXChooseVisual — non-NULL with a sane TrueColor
// 24-bit RGB visual so Unity proceeds (we bypass real GLX, so only non-NULL matters,
// but fill plausible fields in case Unity inspects depth/class).
typedef struct { void* visual; unsigned long visualid; int screen; int depth;
                 int c_class; unsigned long red_mask, green_mask, blue_mask;
                 int colormap_size; int bits_per_rgb; } rd_XVisualInfo;

EXPORT void* my_glXChooseVisual(x64emu_t* emu, void* dpy, int screen, void* attribList)
{
    (void)attribList;
    printf_log(LOG_NONE, "RIMDROID glXChooseVisual ENTER screen=%d\n", screen); fflush(NULL);
    if (!rd_bridge_active()) return my->glXChooseVisual ? my->glXChooseVisual(dpy, screen, attribList) : NULL;
    g_glx_display = dpy;
    rd_XVisualInfo* v = (rd_XVisualInfo*)calloc(1, sizeof(rd_XVisualInfo));
    if (v) { v->visualid = 0x21; v->screen = screen; v->depth = 24; v->c_class = 4 /*TrueColor*/;
             v->red_mask = 0xff0000; v->green_mask = 0x00ff00; v->blue_mask = 0x0000ff;
             v->colormap_size = 256; v->bits_per_rgb = 8; }
    static int n=0; if(n<2){n++; printf_log(LOG_NONE, "RIMDROID glXChooseVisual -> dummy TrueColor24 %p (ZFA)\n", v);}
    return v;
}

static void* rd_glx_make_context(void* dpy, void* win_unused)
{
    (void)win_unused;
    printf_log(LOG_NONE, "RIMDROID glXCreateContext ENTER\n"); fflush(NULL);
    g_glx_display = dpy;
    // Multi-context mode: the FIRST logical context takes the primary EGL context; every later
    // one gets its own real shared context. Aliasing mode: everything maps onto one (historic).
    void* real = rd_bridge_ctx();
    if (rd_eglt_active() && rd_eglt_multictx() && g_glx_create_n > 0) {
        void* fresh = rimdroid_eglt_create_shared ? rimdroid_eglt_create_shared() : NULL;
        if (fresh) real = fresh;   // creation failure falls back to aliasing the primary
    }
    if (!(rd_eglt_active() && rd_eglt_multictx()))
        rd_bridge_make_current();
    void* fake = (void*)((uintptr_t)rd_bridge_ctx() + (uintptr_t)(++g_glx_create_n * 0x10000UL));
    for (int i = 0; i < RD_GLX_MAX_CTX; i++)
        if (!g_glx_ctx_map[i].fake) { g_glx_ctx_map[i].fake = fake; g_glx_ctx_map[i].real = real; break; }
    g_glx_ctx_current = fake;
    printf_log(LOG_NONE, "RIMDROID glXCreateContext #%lu -> handle %p (%s real=%p) — MILESTONE\n", g_glx_create_n, fake,
               rd_zfa_active() ? "ZFA" : "EGL-translator", real);
    return fake;
}
EXPORT void* my_glXCreateContext(x64emu_t* emu, void* dpy, void* vis, void* share, int direct)
{
    if (!rd_bridge_active()) return my->glXCreateContext ? my->glXCreateContext(dpy, vis, share, direct) : NULL;
    (void)vis; (void)share; (void)direct;
    return rd_glx_make_context(dpy, NULL);
}
EXPORT void* my_glXCreateContextAttribsARB(x64emu_t* emu, void* dpy, void* config, void* share, int direct, void* attribs)
{
    if (!rd_bridge_active()) return my->glXCreateContextAttribsARB ? my->glXCreateContextAttribsARB(dpy, config, share, direct, attribs) : NULL;
    (void)config; (void)share; (void)direct; (void)attribs;
    return rd_glx_make_context(dpy, NULL);
}
EXPORT void my_glXDestroyContext(x64emu_t* emu, void* dpy, void* ctx)
{
    if (!rd_bridge_active()) { if (my->glXDestroyContext) my->glXDestroyContext(dpy, ctx); return; }
    if (ctx == g_glx_ctx_current) g_glx_ctx_current = NULL;
    // Multi-context: destroy the REAL worker context (never the primary — the factory refuses).
    if (rd_eglt_active() && rd_eglt_multictx()) {
        for (int i = 0; i < RD_GLX_MAX_CTX; i++)
            if (g_glx_ctx_map[i].fake == ctx) {
                void* real = g_glx_ctx_map[i].real;
                g_glx_ctx_map[i].fake = NULL; g_glx_ctx_map[i].real = NULL;
                if (real == g_glx_presenter) g_glx_presenter = NULL;
                if (real != g_egl_context && rimdroid_eglt_destroy_ctx) rimdroid_eglt_destroy_ctx(real);
                printf_log(LOG_NONE, "RIMDROID glXDestroyContext(%p) -> real %p destroyed\n", ctx, real);
                return;
            }
    }
    // Aliasing mode: never destroy the one shared context; just forget the alias.
    printf_log(LOG_NONE, "RIMDROID glXDestroyContext(%p) -> no-op (aliased)\n", ctx);
}
EXPORT int my_glXMakeCurrent(x64emu_t* emu, void* dpy, uintptr_t drawable, void* ctx)
{
    printf_log(LOG_NONE, "RIMDROID glXMakeCurrent ENTER dpy=%p drawable=0x%lx ctx=%p tid=%ld\n", dpy, (unsigned long)drawable, ctx, (long)syscall(SYS_gettid)); fflush(NULL);
    if (!rd_bridge_active()) return my->glXMakeCurrent ? my->glXMakeCurrent(dpy, drawable, ctx) : 0;
    g_glx_display = dpy; g_glx_drawable = drawable;
    if (!ctx) {
        // Unbind: release the real context from THIS thread so a legal single-context
        // migration between threads doesn't leave it current on two threads.
        //
        int rel = rd_bridge_release_current();
        g_glx_ctx_current = NULL;
        g_glx_have_current = 0;
        printf_log(LOG_NONE, "RIMDROID glXMakeCurrent(unbind) release=%d\n", rel);
        return 1;
    }
    void* prev_fake = g_glx_ctx_current;
    g_glx_ctx_current = ctx;
    // Multi-context mode: bind the REAL context of this logical one. No alias-skip here — a
    // logical switch is a real switch now. Presenter keeps the window surface, others go
    // surfaceless. The per-context binding shadow follows via rd_glx_ctx_slot.
    if (rd_eglt_active() && rd_eglt_multictx()) {
        void* real = rd_glx_real_of(ctx);
        rd_glx_ctx_slot = rd_glx_slot_of(ctx);
        if (g_glx_have_current && pthread_equal(g_glx_current_tid, pthread_self()) && prev_fake == ctx)
            return 1;   // same logical context already bound on this thread
        int ok = rimdroid_eglt_make_current_on ? rimdroid_eglt_make_current_on(real, real == g_glx_presenter) : 0;
        if (ok) { g_glx_current_tid = pthread_self(); g_glx_have_current = 1; }
        else {
            // NEVER rate-limited: a refused bind leaves this thread with no context, so every GL
            // call it makes next is dropped on the floor. That is the signature we are hunting —
            // if the threaded red textures come from lost uploads, this line names the moment.
            g_glx_have_current = 0;
            printf_log(LOG_NONE, "RIMDROID glXMakeCurrent(multictx) BIND FAILED fake=%p real=%p window=%d tid=%ld — thread now has NO context\n",
                       ctx, real, real == g_glx_presenter, (long)syscall(SYS_gettid));
            fflush(NULL);
        }
        static int n2=0; if(ok && n2<8){n2++; printf_log(LOG_NONE, "RIMDROID glXMakeCurrent(multictx) fake=%p real=%p window=%d tid=%ld -> OK\n",
                                                         ctx, real, real == g_glx_presenter, (long)syscall(SYS_gettid));}
        return ok ? 1 : 0;
    }
    // All Unity context handles alias ONE real ZFA context. Skip the full zfaMakeCurrent
    // (kopper window rebind) when this thread already has it current — Unity alternates its
    // logical contexts rapidly around scene transitions, and re-running kopper's drawable
    // binding for every alternation churns swapchain state for nothing (device-lost suspect).
    if (g_glx_have_current && pthread_equal(g_glx_current_tid, pthread_self())) {
        static int n=0; if(n<6){n++; printf_log(LOG_NONE, "RIMDROID glXMakeCurrent: alias switch, rebind skipped\n");}
        return 1;
    }
    int ok = rd_bridge_make_current();
    if (ok) { g_glx_current_tid = pthread_self(); g_glx_have_current = 1; }
    static int n=0; if(n<4){n++; printf_log(LOG_NONE, "RIMDROID glXMakeCurrent(drawable=0x%lx ctx=%p) -> %s %s\n", (unsigned long)drawable, ctx, rd_zfa_active()?"ZFA":"EGL-translator", ok?"OK":"FAIL");}
    return ok ? 1 : 0;
}
EXPORT void my_glXSwapBuffers(x64emu_t* emu, void* dpy, uintptr_t drawable)
{
    if (!rd_bridge_active()) { if (my->glXSwapBuffers) my->glXSwapBuffers(dpy, drawable); return; }
    // Log swaps only when something CHANGES (drawable/dpy/thread) — captures the transition
    // into the crashing call without per-frame spam. The deterministic NULL+0xbc crash sits
    // in this bridge right after RimWorld's startup resize (DestroyNotify/ConfigureNotify).
    static void* last_dpy = (void*)-1; static uintptr_t last_draw = (uintptr_t)-1; static long last_tid = -1;
    long tid = (long)syscall(SYS_gettid);
    if (dpy != last_dpy || drawable != last_draw || tid != last_tid) {
        printf_log(LOG_NONE, "RIMDROID glXSwapBuffers CHANGE dpy=%p drawable=0x%lx tid=%ld (was dpy=%p drawable=0x%lx tid=%ld)\n",
                   dpy, (unsigned long)drawable, tid, last_dpy, (unsigned long)last_draw, last_tid);
        fflush(NULL);
        last_dpy = dpy; last_draw = drawable; last_tid = tid;
    }
    // Multi-context mode: only the presenter holds the window surface. First swap from a context
    // promotes it; if the window is still bound elsewhere (old presenter's thread hasn't released
    // yet), skip ONE present — the next MakeCurrent/unbind over there frees it and we converge.
    if (rd_eglt_active() && rd_eglt_multictx()) {
        void* real = rd_glx_real_of(g_glx_ctx_current);
        if (real != g_glx_presenter) {
            if (rimdroid_eglt_make_current_on && rimdroid_eglt_make_current_on(real, 1)) {
                g_glx_presenter = real;
                printf_log(LOG_NONE, "RIMDROID glXSwapBuffers: presenter -> real %p (tid=%ld)\n", real, tid);
            } else {
                // Also never rate-limited now: a present skipped here means the window surface is
                // still owned by another thread's context — the same ownership conflict that a
                // refused MakeCurrent reports, seen from the presenting side.
                printf_log(LOG_NONE, "RIMDROID glXSwapBuffers: window busy elsewhere — present skipped (real=%p presenter=%p tid=%ld)\n",
                           real, g_glx_presenter, tid);
                fflush(NULL);
                return;
            }
        }
    }
    // Mesa flushes the THREAD-CURRENT context; a swap from a thread that never bound (or
    // after an unbind) crashes inside zfaFlushFront (NULL+0xbc). Rebind on this thread first.
    else if (!g_glx_have_current || !pthread_equal(g_glx_current_tid, pthread_self())) {
        int ok = rd_bridge_make_current();
        if (ok) { g_glx_current_tid = pthread_self(); g_glx_have_current = 1; }
        static int n = 0;
        if (n < 8) { n++; printf_log(LOG_NONE, "RIMDROID glXSwapBuffers: rebind on tid=%ld -> %s\n", tid, ok?"OK":"FAIL"); }
        if (!ok) return;   // no context -> flushing would crash; skip this present
    }
    if (rimdroid_frame_tick) rimdroid_frame_tick();
    {   // per-frame pacing reset (see rd_upload_pace_frame_reset in wrappedsdl2.c)
        extern void rd_upload_pace_frame_reset(void);
        rd_upload_pace_frame_reset();
    }
    // Diagnostics RETIRED (2026-07-11, killer caught = Unity's BC-compression shader):
    // the swap-skip env hook is gone, and so is the v12 pre-swap glFinish discriminator —
    // a FULL GPU drain before every present, live since 2026-07-09, stalling every frame
    // (prime suspect for the "flicker + slow-mo UI" reported at the menus). Present path
    // is now: flush-in-swap (Mesa) → zfa swap, nothing else.
    rd_glx_swap_phase = 2;
    rd_bridge_swap();
    rd_glx_swap_phase = 0;
}
EXPORT void* my_glXGetCurrentContext(x64emu_t* emu)
{
    if (!rd_bridge_active()) return my->glXGetCurrentContext ? my->glXGetCurrentContext() : NULL;
    return g_glx_ctx_current ? g_glx_ctx_current : rd_bridge_ctx();
}
EXPORT void* my_glXGetCurrentDisplay(x64emu_t* emu)
{
    if (!rd_bridge_active()) return my->glXGetCurrentDisplay ? my->glXGetCurrentDisplay() : NULL;
    return g_glx_display;
}
EXPORT uintptr_t my_glXGetCurrentDrawable(x64emu_t* emu)
{
    if (!rd_bridge_active()) return my->glXGetCurrentDrawable ? (uintptr_t)my->glXGetCurrentDrawable() : 0;
    return g_glx_drawable ? g_glx_drawable : 1;
}
EXPORT int my_glXQueryVersion(x64emu_t* emu, void* dpy, void* major, void* minor)
{
    printf_log(LOG_NONE, "RIMDROID glXQueryVersion ENTER\n"); fflush(NULL);
    if (!rd_bridge_active()) return my->glXQueryVersion ? my->glXQueryVersion(dpy, major, minor) : 0;
    if (major) *(int*)major = 1;
    if (minor) *(int*)minor = 4;
    return 1;
}
EXPORT int my_glXQueryExtension(x64emu_t* emu, void* dpy, void* errorBase, void* eventBase)
{
    printf_log(LOG_NONE, "RIMDROID glXQueryExtension ENTER dpy=%p errBase=%p evtBase=%p\n", dpy, errorBase, eventBase); fflush(NULL);
    if (!rd_bridge_active()) return my->glXQueryExtension ? my->glXQueryExtension(dpy, errorBase, eventBase) : 0;
    // NOTE (root cause of the 2026-07-09 corruption saga): these GOM bridges were first declared
    // WITHOUT the E in their wrapper signatures (vFpL instead of vFEpL etc.), so the guest args
    // arrived shifted by one (emu<-display, dpy<-errorBase, ...) and this write went through
    // uninitialized-stack garbage -> native memory corruption. With E in place the pointers are
    // the real SDL gl_data fields and writing them is correct.
    if (errorBase) *(int*)errorBase = 0;
    if (eventBase) *(int*)eventBase = 0;
    return 1;
}
EXPORT void* my_glXQueryExtensionsString(x64emu_t* emu, void* dpy, int screen)
{
    printf_log(LOG_NONE, "RIMDROID glXQueryExtensionsString ENTER\n"); fflush(NULL);
    if (!rd_bridge_active()) return my->glXQueryExtensionsString ? my->glXQueryExtensionsString(dpy, screen) : (void*)"";
    (void)dpy; (void)screen;
    return (void*)"";
}
// GLX name tokens: GLX_VENDOR=1, GLX_VERSION=2, GLX_EXTENSIONS=3.
EXPORT void* my_glXGetClientString(x64emu_t* emu, void* dpy, int name)
{
    printf_log(LOG_NONE, "RIMDROID glXGetClientString ENTER name=%d\n", name); fflush(NULL);
    if (!rd_bridge_active()) return my->glXGetClientString ? my->glXGetClientString(dpy, name) : (void*)"";
    (void)dpy;
    if (name == 2) return (void*)"1.4";        // GLX_VERSION
    if (name == 1) return (void*)"RimDroid";   // GLX_VENDOR
    return (void*)"";                          // GLX_EXTENSIONS / other
}
EXPORT void* my_glXQueryServerString(x64emu_t* emu, void* dpy, int screen, int name)
{
    printf_log(LOG_NONE, "RIMDROID glXQueryServerString ENTER name=%d\n", name); fflush(NULL);
    if (!rd_bridge_active()) return my->glXQueryServerString ? my->glXQueryServerString(dpy, screen, name) : (void*)"";
    (void)dpy; (void)screen;
    if (name == 2) return (void*)"1.4";        // GLX_VERSION
    if (name == 1) return (void*)"RimDroid";   // GLX_VENDOR
    return (void*)"";                          // GLX_EXTENSIONS / other
}
EXPORT void my_glXQueryDrawable(x64emu_t* emu, void* dpy, uintptr_t drawable, int attribute, void* value)
{
    if (!rd_bridge_active()) { if (my->glXQueryDrawable) my->glXQueryDrawable(dpy, drawable, attribute, value); return; }
    (void)dpy; (void)drawable; (void)attribute;
    if (value) *(unsigned int*)value = 0;
}
EXPORT int my_glXGetConfig(x64emu_t* emu, void* dpy, void* vis, int attrib, void* value)
{
    printf_log(LOG_NONE, "RIMDROID glXGetConfig ENTER attrib=%d\n", attrib); fflush(NULL);
    if (!rd_bridge_active()) return my->glXGetConfig ? my->glXGetConfig(dpy, vis, attrib, value) : 0;
    (void)dpy; (void)vis;
    if (!value) return 0;
    int* v = (int*)value;
    switch (attrib) {
        case 1:  *v = 1;  break; // GLX_USE_GL
        case 3:  *v = 8;  break; // GLX_RED_SIZE / buffer-ish -> nonzero
        case 12: *v = 24; break; // GLX_DEPTH_SIZE
        case 13: *v = 8;  break; // GLX_STENCIL_SIZE
        case 5:  *v = 1;  break; // GLX_DOUBLEBUFFER
        default: *v = 1;  break;
    }
    return 0;
}

EXPORT void* my_glXGetProcAddress(x64emu_t* emu, void* name)
{
    const char* rname = (const char*)name;
    if (rd_bridge_active())
        return rimdroid_gl_getprocaddr(emu, my_lib->w.bridge, NULL, rname);
    pFp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glXGetProcAddress;
    return getGLProcAddress(emu, NULL, (void*)fnc, rname);
}
EXPORT void* my_glXGetProcAddressARB(x64emu_t* emu, void* name)
{
    const char* rname = (const char*)name;
    if (rd_bridge_active()) {
        printf_log(LOG_NONE, "RIMDROID glXGetProcAddressARB('%s')\n", rname?rname:"(null)"); fflush(NULL);
        return rimdroid_gl_getprocaddr(emu, my_lib->w.bridge, NULL, rname);
    }
    pFp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glXGetProcAddressARB;
    return getGLProcAddress(emu, NULL, (void*)fnc, rname);
}

typedef int  (*iFi_t)(int);
typedef void (*vFpp_t)(void*, void*);
typedef void (*vFppp_t)(void*, void*, void*);
typedef void (*vFppi_t)(void*, void*, int);
typedef void*(*pFp_t)(void*);
typedef void (*debugProc_t)(int32_t, int32_t, uint32_t, int32_t, int32_t, void*, void*);

typedef struct gl_wrappers_s {
    glprocaddress_t      procaddress;
    kh_symbolmap_t      *glwrappers;    // the map of wrapper for glProcs (for GLX or SDL1/2)
    kh_symbolmap_t      *glmymap;       // link to the mysymbolmap of libGL
} gl_wrappers_t;

KHASH_MAP_INIT_INT64(gl_wrappers, gl_wrappers_t*)

static kh_gl_wrappers_t *gl_wrappers = NULL;

#define SUPER() \
GO(0)   \
GO(1)   \
GO(2)   \
GO(3)   \
GO(4)

// debug_callback ...
#define GO(A)   \
static uintptr_t my_debug_callback_fct_##A = 0;                                                                         \
static void my_debug_callback_##A(int32_t a, int32_t b, uint32_t c, int32_t d, int32_t e, const char* f, const void* g) \
{                                                                                                                       \
    RunFunctionFmt(my_debug_callback_fct_##A, "iiuiipp", a, b, c, d, e, f, g);                                    \
}
SUPER()
#undef GO
static void* find_debug_callback_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_debug_callback_fct_##A == (uintptr_t)fct) return my_debug_callback_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_debug_callback_fct_##A == 0) {my_debug_callback_fct_##A = (uintptr_t)fct; return my_debug_callback_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for libGL debug_callback callback\n");
    return NULL;
}
// egl_debug_callback ...
#define GO(A)   \
static uintptr_t my_egl_debug_callback_fct_##A = 0;                                                     \
    static void my_egl_debug_callback_##A(int a, void* b, int c, void* d, void* e, const char* f)       \
{                                                                                                       \
    RunFunctionFmt(my_egl_debug_callback_fct_##A, "ipippp", a, b, c, d, e, f);                          \
}
SUPER()
#undef GO
static void* find_egl_debug_callback_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_egl_debug_callback_fct_##A == (uintptr_t)fct) return my_egl_debug_callback_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_egl_debug_callback_fct_##A == 0) {my_egl_debug_callback_fct_##A = (uintptr_t)fct; return my_egl_debug_callback_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for libGL egl_debug_callback callback\n");
    return NULL;
}
// program_callback ...
#define GO(A)                                                       \
static uintptr_t my_program_callback_fct_##A = 0;                   \
static void my_program_callback_##A(int32_t a, void* b)             \
{                                                                   \
    RunFunctionFmt(my_program_callback_fct_##A, "ip", a, b);  \
}
SUPER()
#undef GO
static void* find_program_callback_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_program_callback_fct_##A == (uintptr_t)fct) return my_program_callback_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_program_callback_fct_##A == 0) {my_program_callback_fct_##A = (uintptr_t)fct; return my_program_callback_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for libGL program_callback callback\n");
    return NULL;
}
// set_blob_func ...
#define GO(A)                                                               \
static uintptr_t my_set_blob_func_fct_##A = 0;                              \
static void my_set_blob_func_##A(void* a, ssize_t b, void* c, ssize_t d)    \
{                                                                           \
    RunFunctionFmt(my_set_blob_func_fct_##A, "plpl", a, b, c, d);           \
}
SUPER()
#undef GO
static void* find_set_blob_func_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_set_blob_func_fct_##A == (uintptr_t)fct) return my_set_blob_func_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_set_blob_func_fct_##A == 0) {my_set_blob_func_fct_##A = (uintptr_t)fct; return my_set_blob_func_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for libGL set_blob_func callback\n");
    return NULL;
}
// get_blob_func ...
#define GO(A)                                                                       \
static uintptr_t my_get_blob_func_fct_##A = 0;                                      \
static ssize_t my_get_blob_func_##A(void* a, ssize_t b, void* c, ssize_t d)         \
{                                                                                   \
    return (ssize_t)RunFunctionFmt(my_get_blob_func_fct_##A, "plpl", a, b, c, d);   \
}
SUPER()
#undef GO
static void* find_get_blob_func_Fct(void* fct)
{
    if(!fct) return fct;
    if(GetNativeFnc((uintptr_t)fct))  return GetNativeFnc((uintptr_t)fct);
    #define GO(A) if(my_get_blob_func_fct_##A == (uintptr_t)fct) return my_get_blob_func_##A;
    SUPER()
    #undef GO
    #define GO(A) if(my_get_blob_func_fct_##A == 0) {my_get_blob_func_fct_##A = (uintptr_t)fct; return my_get_blob_func_##A; }
    SUPER()
    #undef GO
    printf_log(LOG_NONE, "Warning, no more slot for libGL get_blob_func callback\n");
    return NULL;
}
#undef SUPER

// RimDroid: libzfa.so lives in the special "rimdroid" linker namespace (loaded by
// rimdroid.c via linkernsbypass so Zink can find Turnip/libvulkan). A plain dlopen()
// here — box64's default namespace — CANNOT find it ("library libGL.so not found" →
// libGL emulated → our glX->ZFA bridge never fires → glXCreateContext NULL → Unity
// exits "no OpenGL core profile"). So reuse the ALREADY-open g_zfa_handle when the ZFA
// renderer is active; fall back to a normal dlopen (desktop-Linux box64 / other libs).
#define PRE_INIT                                                                \
    if((&g_zfa_handle) && g_zfa_handle) {                                       \
        lib->w.lib = g_zfa_handle;                                              \
        lib->path = strdup("libzfa.so");                                        \
        printf_log(LOG_INFO, "RIMDROID: libGL wrapper -> reuse g_zfa_handle %p (ZFA)\n", g_zfa_handle); \
    } else if(BOX64ENV(libgl)) {                                                \
        lib->w.lib = dlopen(BOX64ENV(libgl), RTLD_LAZY | RTLD_GLOBAL);          \
        lib->path = strdup(BOX64ENV(libgl));                                    \
    }

#include "wrappedlib_init.h"

// glDebugMessageCallback
EXPORT void my_glDebugMessageCallback(x64emu_t* emu, void* prod, void* param)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glDebugMessageCallback;
    fnc(find_debug_callback_Fct(prod), param);
}
// glDebugMessageCallbackARB
EXPORT void my_glDebugMessageCallbackARB(x64emu_t* emu, void* prod, void* param)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glDebugMessageCallbackARB;
    fnc(find_debug_callback_Fct(prod), param);
}
// glDebugMessageCallbackAMD
EXPORT void my_glDebugMessageCallbackAMD(x64emu_t* emu, void* prod, void* param)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glDebugMessageCallbackAMD;
    fnc(find_debug_callback_Fct(prod), param);
}
// glDebugMessageCallbackKHR
EXPORT void my_glDebugMessageCallbackKHR(x64emu_t* emu, void* prod, void* param)
{
    vFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glDebugMessageCallbackKHR;
    fnc(find_debug_callback_Fct(prod), param);
}
// eglDebugMessageControlKHR
EXPORT int my_eglDebugMessageControlKHR(x64emu_t* emu, void* prod, void* param)
{
    iFpp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->eglDebugMessageControlKHR;
    return fnc(find_debug_callback_Fct(prod), param);
}
// eglSetBlobCacheFuncsANDROID ...
EXPORT void my_eglSetBlobCacheFuncsANDROID(x64emu_t* emu, void* dpy, void* set, void* get)
{
    vFppp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->eglSetBlobCacheFuncsANDROID;
    fnc(dpy, find_set_blob_func_Fct(set), find_get_blob_func_Fct(get));
}
// glXSwapIntervalMESA ...
EXPORT int my_dummy_glXSwapIntervalMESA(unsigned int interval)
{
    return 5; // GLX_BAD_CONTEXT
}
EXPORT int my_glXSwapIntervalMESA(x64emu_t* emu, unsigned int interval)
{
    iFu_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glXSwapIntervalMESA;
    if(!fnc) fnc=my_dummy_glXSwapIntervalMESA;
    return fnc(interval);
}
// glXSwapIntervalEXT ...
EXPORT void my_dummy_glXSwapIntervalEXT(void* dpy, unsigned long drawable, int interval) {}
EXPORT void my_glXSwapIntervalEXT(x64emu_t* emu, void* dpy, unsigned long drawable, int interval)
{
    vFpLi_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glXSwapIntervalEXT;
    if(!fnc) fnc=my_dummy_glXSwapIntervalEXT;
    fnc(dpy, drawable, interval);
}
// glProgramCallbackMESA ...
EXPORT void my_glProgramCallbackMESA(x64emu_t* emu, int t, void* f, void* data)
{
    vFipp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glProgramCallbackMESA;
    fnc(t, find_program_callback_Fct(f), data);
}
void* my_GetVkProcAddr(x64emu_t* emu, void* name, void*(*getaddr)(void*));  // defined in wrappedvulkan.c
// glGetVkProcAddrNV ...
EXPORT void* my_glGetVkProcAddrNV(x64emu_t* emu, void* name)
{
    pFp_t fnc = getBridgeFnc2((void*)R_RIP);
    if(!fnc) fnc=my->glGetVkProcAddrNV;
    return my_GetVkProcAddr(emu, name, fnc);
}

gl_wrappers_t* getGLProcWrapper(box64context_t* context, glprocaddress_t procaddress)
{
    int cnt, ret;
    khint_t k;
    if(!gl_wrappers) {
        gl_wrappers = kh_init(gl_wrappers);
    }
    k = kh_put(gl_wrappers, gl_wrappers, (uintptr_t)procaddress, &ret);
    if(!ret)
        return kh_value(gl_wrappers, k);
    gl_wrappers_t* wrappers = kh_value(gl_wrappers, k) = (gl_wrappers_t*)calloc(1, sizeof(gl_wrappers_t));

    wrappers->procaddress = procaddress;
    wrappers->glwrappers = kh_init(symbolmap);
    // populates maps...
    cnt = sizeof(libglsymbolmap)/sizeof(map_onesymbol_t);
    for (int i=0; i<cnt; ++i) {
        k = kh_put(symbolmap, wrappers->glwrappers, libglsymbolmap[i].name, &ret);
        kh_value(wrappers->glwrappers, k).w = libglsymbolmap[i].w;
        kh_value(wrappers->glwrappers, k).resolved = 0;
    }
    // and the my_ symbols map
    cnt = sizeof(MAPNAME(mysymbolmap))/sizeof(map_onesymbol_t);
    for (int i=0; i<cnt; ++i) {
        k = kh_put(symbolmap, wrappers->glwrappers, libglmysymbolmap[i].name, &ret);
        kh_value(wrappers->glwrappers, k).w = libglmysymbolmap[i].w;
        kh_value(wrappers->glwrappers, k).resolved = 0;
    }
    // my_* map
    wrappers->glmymap = kh_init(symbolmap);
    cnt = sizeof(MAPNAME(mysymbolmap))/sizeof(map_onesymbol_t);
    for (int i=0; i<cnt; ++i) {
        k = kh_put(symbolmap, wrappers->glmymap, libglmysymbolmap[i].name, &ret);
        kh_value(wrappers->glmymap, k).w = libglmysymbolmap[i].w;
        kh_value(wrappers->glmymap, k).resolved = 0;
    }
    return wrappers;
}
void freeGLProcWrapper(box64context_t* context)
{
    if(!context)
        return;
    if(!gl_wrappers)
        return;
    gl_wrappers_t* wrappers;
    kh_foreach_value(gl_wrappers, wrappers,
        if(wrappers->glwrappers)
            kh_destroy(symbolmap, wrappers->glwrappers);
        if(wrappers->glmymap)
            kh_destroy(symbolmap, wrappers->glmymap);
        wrappers->glwrappers = NULL;
        wrappers->glmymap = NULL;
    );
    kh_destroy(gl_wrappers, gl_wrappers);
    gl_wrappers = NULL;
}

void* getGLProcAddress(x64emu_t* emu, const char* my, glprocaddress_t procaddr, const char* rname)
{
    if(!my) my = "my_";
    khint_t k;
    printf_dlsym(LOG_DEBUG, "Calling getGLProcAddress[%p](\"%s\") => ", procaddr, rname);
    gl_wrappers_t* wrappers = getGLProcWrapper(emu->context, procaddr);
    // check if glxprocaddress is filled, and search for lib and fill it if needed
    // get proc adress using actual glXGetProcAddress
    k = kh_get(symbolmap, wrappers->glmymap, rname);
    int is_my = (k==kh_end(wrappers->glmymap))?0:1;
    void* symbol = procaddr(rname);
    void* fnc = NULL;
    if(is_my) {
        char tmp[200];
        strcpy(tmp, my);
        strcat(tmp, rname);
        fnc = symbol;
        symbol = dlsym(emu->context->box64lib, tmp);
    }
    if(!symbol) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
        return NULL;    // easy
    }
    // check if alread bridged
    uintptr_t ret = CheckBridged2(emu->context->system, symbol, fnc);
    if(ret) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", (void*)ret);
        return (void*)ret; // already bridged
    }
    // get wrapper
    k = kh_get(symbolmap, wrappers->glwrappers, rname);
    if(k==kh_end(wrappers->glwrappers) && strstr(rname, "ARB")==NULL) {
        // try again, adding ARB at the end if not present
        char tmp[200];
        strcpy(tmp, rname);
        strcat(tmp, "ARB");
        k = kh_get(symbolmap, wrappers->glwrappers, tmp);
    }
    if(k==kh_end(wrappers->glwrappers) && strstr(rname, "EXT")==NULL) {
        // try again, adding EXT at the end if not present
        char tmp[200];
        strcpy(tmp, rname);
        strcat(tmp, "EXT");
        k = kh_get(symbolmap, wrappers->glwrappers, tmp);
    }
    if(k==kh_end(wrappers->glwrappers)) {
        printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", NULL);
        printf_dlsym_prefix(2, LOG_INFO, "Warning, no wrapper for %s\n", rname);
        return NULL;
    }
    symbol1_t* s = &kh_value(wrappers->glwrappers, k);
    const char* constname = kh_key(wrappers->glwrappers, k);
    ret = AddCheckBridge2(emu->context->system, s->w, symbol, fnc, 0, constname);
    printf_dlsym_prefix(0, LOG_DEBUG, "%p\n", (void*)ret);
    return (void*)ret;
}

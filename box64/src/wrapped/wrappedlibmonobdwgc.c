/*
 * RimDroid experiment: x86_64 UnityPlayer running on native ARM64 Unity Mono.
 *
 * UnityPlayer opens RimWorldLinux_Data/MonoBleedingEdge/x86_64/libmonobdwgc-2.0.so by full path. When
 * RIMDROID_NATIVE_MONO_PATH names an ARM64 build of the same Unity Mono (Unity 2022.3.35f1, fork commit
 * c11bc9adba), library.c routes that load here and this wrapper exposes the native runtime instead of
 * emulating the x86 one. Without the variable nothing changes: the emulated x86 Mono is loaded as before.
 *
 * The bridge mechanics were proven one by one with tools/mono-arm64/box64-probe (docs/MONO_ARM64_SPIKE.md):
 *   - reverse internal calls: generated typed thunks, one per Unity internal call (wrappedlibmonobdwgc_icalls.h)
 *   - managed exceptions raised by x86 code: pending exception + early return, never a native unwind (P4)
 *   - x86 guest state as GC roots: emulated registers and guest stacks pushed into Boehm (P3)
 *   - SIGSEGV shared between Mono and Box64: signal chaining forced on, crash chaining off (P5)
 */
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "wrappedlibs.h"

#include "debug.h"
#include "wrapper.h"
#include "bridge.h"
#include "librarian/library_private.h"
#include "x64emu.h"
#include "callback.h"
#include "box64context.h"
#include "emu/x64emu_private.h"
#include "regs.h"

const char* libmonobdwgcName = "libmonobdwgc-2.0.so";
#define LIBNAME libmonobdwgc

#include "generated/wrappedlibmonobdwgctypes.h"
#include "wrappercallback.h"

static void* rd_native_sym(const char* name)
{
    return (my_lib && my_lib->w.lib) ? dlsym(my_lib->w.lib, name) : NULL;
}

/*
 * GC roots held by x86 guest threads (P3, extended to every thread).
 *
 * Boehm scans native stacks and registers of the threads it stops, but an x86 guest keeps object
 * references in Box64 state: the emulated registers and a separately mapped guest stack. Every emulator
 * that runs Mono-facing guest code is registered here (the thread that initializes Mono, threads that call
 * mono_thread_attach, and any thread entering a reverse internal call), and a GC_push_other_roots hook
 * chained on top of Mono's own pushes their state on every collection.
 *
 * The whole guest stack is pushed, not only the part above RSP: while a thread runs translated code its
 * emulator RSP is only synchronized at exits from the dynarec, so it can be stale and too high. Scanning
 * dead frames only costs time and conservative retention.
 *
 * The hook runs with the world stopped and the GC allocation lock held: it must not lock, allocate or
 * print. The registry is only changed under the same lock, and an emulator is removed before Box64 frees
 * it (rd_emu_destroy_hook in threads.c).
 */
#define RD_GC_MAX_GUESTS 512

typedef void (*rd_gc_push_other_roots_t)(void);
static rd_gc_push_other_roots_t rd_gc_prev_push_other_roots;
static void (*rd_gc_push_all)(void*, void*);
static void* (*rd_gc_call_with_alloc_lock)(void* (*)(void*), void*);
static x64emu_t* rd_gc_guests[RD_GC_MAX_GUESTS];
static int rd_gc_guest_count;
static int rd_gc_ready;
static volatile unsigned long rd_gc_collections;
static volatile unsigned long rd_gc_bytes_last;
static __thread x64emu_t* rd_gc_registered_emu;

extern void (*rd_emu_destroy_hook)(x64emu_t* emu);

/*
 * Statistics (RIMDROID_MONO_STATS=1), see rd_stats_report() below. Off: one predictable branch per
 * internal call. Time comes straight from the virtual counter (a few nanoseconds per read, no syscall);
 * single calls are shorter than a counter tick, only the sums are meaningful.
 */
static int rd_stats_on;
static uint64_t rd_st_gc_hook_ticks;

static inline uint64_t rd_ticks(void)
{
    uint64_t value;
    __asm__ volatile("mrs %0, cntvct_el0" : "=r"(value));
    return value;
}

static void rd_gc_push_guest_roots(void)
{
    uint64_t stats_t0 = rd_stats_on ? rd_ticks() : 0;
    if (rd_gc_prev_push_other_roots)
        rd_gc_prev_push_other_roots();
    unsigned long bytes = 0;
    for (int i = 0; i < rd_gc_guest_count; ++i) {
        x64emu_t* emu = rd_gc_guests[i];
        if (!emu)
            continue;
        rd_gc_push_all(&emu->regs[0], &emu->regs[16]);
        rd_gc_push_all(&emu->xmm[0], &emu->xmm[16]);
        if (emu->init_stack && emu->size_stack) {
            rd_gc_push_all(emu->init_stack, (void*)((uintptr_t)emu->init_stack + emu->size_stack));
            bytes += emu->size_stack;
        }
    }
    rd_gc_bytes_last = bytes;
    rd_gc_collections++;
    if (rd_stats_on)
        rd_st_gc_hook_ticks += rd_ticks() - stats_t0;
}

static void* rd_gc_add_locked(void* arg)
{
    x64emu_t* emu = (x64emu_t*)arg;
    int free_slot = -1;
    for (int i = 0; i < rd_gc_guest_count; ++i) {
        if (rd_gc_guests[i] == emu)
            return (void*)1;
        if (!rd_gc_guests[i] && free_slot < 0)
            free_slot = i;
    }
    if (free_slot < 0) {
        if (rd_gc_guest_count == RD_GC_MAX_GUESTS)
            return NULL;
        free_slot = rd_gc_guest_count++;
    }
    rd_gc_guests[free_slot] = emu;
    return (void*)1;
}

static void* rd_gc_remove_locked(void* arg)
{
    x64emu_t* emu = (x64emu_t*)arg;
    for (int i = 0; i < rd_gc_guest_count; ++i)
        if (rd_gc_guests[i] == emu)
            rd_gc_guests[i] = NULL;
    return NULL;
}

static void rd_gc_register_emu(x64emu_t* emu)
{
    if (!rd_gc_ready || !emu)
        return;
    if (!rd_gc_call_with_alloc_lock(rd_gc_add_locked, emu)) {
        printf_log(LOG_NONE, "[RD-MONO] GC guest registry full (%d threads): emu %p is NOT scanned\n",
            RD_GC_MAX_GUESTS, emu);
        return;
    }
    if (rd_gc_registered_emu != emu) {
        rd_gc_registered_emu = emu;
        printf_log(LOG_INFO, "[RD-MONO] GC guest roots: registered emu %p stack=[%p,+0x%x)\n",
            emu, emu->init_stack, emu->size_stack);
    }
}

static void rd_gc_emu_destroyed(x64emu_t* emu)
{
    if (rd_gc_ready)
        rd_gc_call_with_alloc_lock(rd_gc_remove_locked, emu);
    // Box64 destroys an emulator on its own thread; a new one may reuse the address.
    if (rd_gc_registered_emu == emu)
        rd_gc_registered_emu = NULL;
}

static void rd_gc_install(x64emu_t* main_emu)
{
    const char* off = getenv("RIMDROID_MONO_NO_GUEST_ROOTS");
    if (off && off[0] == '1') {
        printf_log(LOG_NONE, "[RD-MONO] GC guest roots DISABLED (RIMDROID_MONO_NO_GUEST_ROOTS=1)\n");
        return;
    }
    rd_gc_push_other_roots_t (*get_push)(void) = rd_native_sym("GC_get_push_other_roots");
    void (*set_push)(rd_gc_push_other_roots_t) = rd_native_sym("GC_set_push_other_roots");
    rd_gc_push_all = rd_native_sym("GC_push_all");
    rd_gc_call_with_alloc_lock = rd_native_sym("GC_call_with_alloc_lock");
    if (!get_push || !set_push || !rd_gc_push_all || !rd_gc_call_with_alloc_lock) {
        printf_log(LOG_NONE, "[RD-MONO] GC guest roots NOT installed: missing GC hooks (get=%p set=%p push=%p lock=%p)\n",
            get_push, set_push, rd_gc_push_all, rd_gc_call_with_alloc_lock);
        return;
    }
    rd_gc_prev_push_other_roots = get_push();
    set_push(rd_gc_push_guest_roots);
    rd_emu_destroy_hook = rd_gc_emu_destroyed;
    rd_gc_ready = 1;
    rd_gc_register_emu(main_emu);
    printf_log(LOG_NONE, "[RD-MONO] GC guest roots installed (prev hook %p)\n", rd_gc_prev_push_other_roots);
}

/*
 * Managed exceptions raised by x86 code (P4).
 *
 * A native mono_raise_exception unwinds straight to the managed catch block through Mono's LMF chain and
 * drops everything in between without running it: the reverse thunk, RunFunctionFmt, DynaCall and the
 * dynarec frames, leaving the guest registers, guest stack and emulator state inside dead x86 frames.
 *
 * So inside a reverse internal call an exception never unwinds natively. It becomes Mono's pending
 * exception and the emulator returns early: emu->quit makes the dynarec exit right after the bridge call,
 * DynaCall restores RBX/RDI/RSI/RBP/RSP/RIP, and the thunk restores R12-R15, which DynaCall does not save.
 * The internal call returns normally and Mono's wrapper for foreign internal calls throws the pending
 * exception at its checkpoint, inside managed code. Outside any internal call there is no managed frame
 * to reach and the raise stays native, as it would be on x86 Mono.
 */
static __thread int rd_icall_depth;
static __thread int rd_icall_raised;
static int (*rd_set_pending_exception)(void*, int);

#define RD_MONO_ICALL_BRIDGE_DEFINED
typedef struct rd_icall_frame_s {
    x64emu_t* emu;
    uint64_t r12, r13, r14, r15;
    uint64_t stats_t0;
    int outer_raised;
    int index;
} rd_icall_frame_t;

static void rd_stats_icall(rd_icall_frame_t* frame, int outermost);

static inline void rd_icall_enter(rd_icall_frame_t* frame, int index)
{
    x64emu_t* emu = thread_get_emu();
    frame->index = index;
    frame->stats_t0 = rd_stats_on ? rd_ticks() : 0;
    frame->emu = emu;
    frame->r12 = emu->regs[_R12].q[0];
    frame->r13 = emu->regs[_R13].q[0];
    frame->r14 = emu->regs[_R14].q[0];
    frame->r15 = emu->regs[_R15].q[0];
    frame->outer_raised = rd_icall_raised;
    rd_icall_raised = 0;
    rd_icall_depth++;
    if (rd_gc_registered_emu != emu)
        rd_gc_register_emu(emu);
}

static inline int rd_icall_leave(rd_icall_frame_t* frame)
{
    rd_icall_depth--;
    int raised = rd_icall_raised;
    if (raised) {
        x64emu_t* emu = frame->emu;
        emu->regs[_R12].q[0] = frame->r12;
        emu->regs[_R13].q[0] = frame->r13;
        emu->regs[_R14].q[0] = frame->r14;
        emu->regs[_R15].q[0] = frame->r15;
    }
    rd_icall_raised = frame->outer_raised;
    if (rd_stats_on)
        rd_stats_icall(frame, rd_icall_depth == 0);
    return raised;
}

static inline float rd_icall_float_result(rd_icall_frame_t* frame)
{
    return frame->emu->xmm[0].f[0];
}

/* Returns 1 when the exception was turned into a pending exception for the innermost internal call. */
static int rd_defer_exception(x64emu_t* emu, void* exception)
{
    if (!exception || rd_icall_depth <= 0 || !rd_set_pending_exception)
        return 0;
    rd_set_pending_exception(exception, 1);
    rd_icall_raised = 1;
    emu->quit = 1;
    return 1;
}

#include "wrappedlibmonobdwgc_icalls.h"

/*
 * RIMDROID_MONO_STATS=1: where does a frame go?
 *
 * Every 2 s the main thread prints one line to the box64 log: internal calls per second and per frame,
 * the time spent inside them (bridge + the x86 callee), the time inside mono_runtime_invoke (all managed
 * code the engine runs on the main thread, internal calls included), collections, the GC's own total time,
 * the time of our root hook and the heap size. Every 10 s it adds the internal calls that were called most
 * and that cost most in that window, which is the list to optimize or to answer without crossing at all.
 * Per-call arrays are updated without locks: the main thread makes nearly all calls and a lost update from
 * a job thread does not matter to a profile. Nested calls are inclusive.
 */
#define RD_ST_TOP 15
static uint32_t rd_st_hits[RD_MONO_ICALL_COUNT];
static uint32_t rd_st_hits_prev[RD_MONO_ICALL_COUNT];
static uint64_t rd_st_call_ticks[RD_MONO_ICALL_COUNT];
static uint64_t rd_st_call_ticks_prev[RD_MONO_ICALL_COUNT];
static __thread int rd_st_is_main;
static __thread int rd_st_invoke_depth;
static uint64_t rd_st_calls_main, rd_st_ticks_main;
static uint64_t rd_st_calls_other, rd_st_ticks_other;
static uint64_t rd_st_invokes, rd_st_invoke_ticks;
static uint64_t rd_st_freq, rd_st_last_report, rd_st_reports;
/* Presented-frame counter of the RimDroid app (rimdroid.c), resolved like rimdroid_frame_tick in wrappedsdl2.c. */
extern __attribute__((weak)) volatile uint64_t g_rimdroid_frame_count;
static volatile uint64_t* rd_st_frame_count;
static unsigned long (*rd_st_gc_no)(void);
static size_t (*rd_st_gc_heap_size)(void);
static unsigned long (*rd_st_gc_total_ms)(void);
static struct {
    uint64_t calls_main, ticks_main, calls_other, ticks_other, invokes, invoke_ticks, gc_hook_ticks, frames;
    unsigned long gc_no, gc_ms;
} rd_st_prev;

static double rd_st_ms(uint64_t ticks)
{
    return rd_st_freq ? (double)ticks * 1000.0 / (double)rd_st_freq : 0.0;
}

static void rd_stats_top(const char* title, int by_ticks, double seconds)
{
    int top[RD_ST_TOP];
    uint64_t key[RD_ST_TOP];
    int used = 0;
    for (int i = 0; i < RD_MONO_ICALL_COUNT; ++i) {
        uint64_t k = by_ticks ? rd_st_call_ticks[i] - rd_st_call_ticks_prev[i]
                              : (uint64_t)(rd_st_hits[i] - rd_st_hits_prev[i]);
        if (!k)
            continue;
        int pos = used;
        while (pos > 0 && key[pos - 1] < k)
            --pos;
        if (pos >= RD_ST_TOP)
            continue;
        int last = used < RD_ST_TOP ? used : RD_ST_TOP - 1;
        for (int j = last; j > pos; --j) {
            key[j] = key[j - 1];
            top[j] = top[j - 1];
        }
        key[pos] = k;
        top[pos] = i;
        if (used < RD_ST_TOP)
            ++used;
    }
    printf_log(LOG_NONE, "[RD-MONO-STATS] top %s, last %.0f s:\n", title, seconds);
    for (int j = 0; j < used; ++j) {
        int i = top[j];
        uint32_t hits = rd_st_hits[i] - rd_st_hits_prev[i];
        double ms = rd_st_ms(rd_st_call_ticks[i] - rd_st_call_ticks_prev[i]);
        printf_log(LOG_NONE, "[RD-MONO-STATS]   %9u calls %9.1f ms %7.0f ns/call  %s (%s)\n",
            hits, ms, hits ? ms * 1e6 / hits : 0.0, rd_mono_icalls[i].name, rd_mono_icalls[i].abi);
    }
}

static void rd_stats_report(uint64_t now)
{
    double seconds = (double)(now - rd_st_last_report) / (double)rd_st_freq;
    rd_st_last_report = now;
    uint64_t frames_now = rd_st_frame_count ? *rd_st_frame_count : 0;
    unsigned long gc_no = rd_st_gc_no ? rd_st_gc_no() : 0;
    unsigned long gc_ms = rd_st_gc_total_ms ? rd_st_gc_total_ms() : 0;
    uint64_t calls_main = rd_st_calls_main - rd_st_prev.calls_main;
    uint64_t calls_other = rd_st_calls_other - rd_st_prev.calls_other;
    uint64_t frames = frames_now - rd_st_prev.frames;
    uint64_t invokes = rd_st_invokes - rd_st_prev.invokes;
    double icall_ms = rd_st_ms(rd_st_ticks_main - rd_st_prev.ticks_main);
    double other_ms = rd_st_ms(rd_st_ticks_other - rd_st_prev.ticks_other);
    double invoke_ms = rd_st_ms(rd_st_invoke_ticks - rd_st_prev.invoke_ticks);
    double hook_ms = rd_st_ms(rd_st_gc_hook_ticks - rd_st_prev.gc_hook_ticks);
    double per_frame = frames ? 1.0 / (double)frames : 0.0;
    printf_log(LOG_NONE, "[RD-MONO-STATS] %.1fs fps=%.0f | icalls main %.0f/s (%.0f/frame) other %.0f/s | "
        "in icalls main %.1f ms/s (%.2f ms/frame, %.0f ns/call) other %.1f ms/s | "
        "managed+icalls (runtime_invoke) %.1f ms/s (%.2f ms/frame, %.0f/s) | "
        "gc %lu, gc total %lu ms, root hook %.2f ms, heap %lu MB\n",
        seconds, frames / seconds,
        calls_main / seconds, calls_main * per_frame, calls_other / seconds,
        icall_ms / seconds, icall_ms * per_frame, calls_main ? icall_ms * 1e6 / calls_main : 0.0, other_ms / seconds,
        invoke_ms / seconds, invoke_ms * per_frame, invokes / seconds,
        gc_no - rd_st_prev.gc_no, gc_ms - rd_st_prev.gc_ms, hook_ms,
        rd_st_gc_heap_size ? (unsigned long)(rd_st_gc_heap_size() >> 20) : 0UL);
    rd_st_prev.calls_main = rd_st_calls_main;
    rd_st_prev.ticks_main = rd_st_ticks_main;
    rd_st_prev.calls_other = rd_st_calls_other;
    rd_st_prev.ticks_other = rd_st_ticks_other;
    rd_st_prev.invokes = rd_st_invokes;
    rd_st_prev.invoke_ticks = rd_st_invoke_ticks;
    rd_st_prev.gc_hook_ticks = rd_st_gc_hook_ticks;
    rd_st_prev.frames = frames_now;
    rd_st_prev.gc_no = gc_no;
    rd_st_prev.gc_ms = gc_ms;
    if (++rd_st_reports % 5 == 0) {
        rd_stats_top("by time", 1, seconds * 5);
        rd_stats_top("by calls", 0, seconds * 5);
        memcpy(rd_st_hits_prev, rd_st_hits, sizeof(rd_st_hits));
        memcpy(rd_st_call_ticks_prev, rd_st_call_ticks, sizeof(rd_st_call_ticks));
    }
}

static void rd_stats_icall(rd_icall_frame_t* frame, int outermost)
{
    uint64_t now = rd_ticks();
    uint64_t spent = now - frame->stats_t0;
    rd_st_hits[frame->index]++;
    rd_st_call_ticks[frame->index] += spent;
    if (!rd_st_is_main) {
        __atomic_fetch_add(&rd_st_calls_other, 1, __ATOMIC_RELAXED);
        if (outermost)
            __atomic_fetch_add(&rd_st_ticks_other, spent, __ATOMIC_RELAXED);
        return;
    }
    rd_st_calls_main++;
    if (!outermost)
        return;
    rd_st_ticks_main += spent;
    if ((rd_st_calls_main & 0x3ff) == 0 && now - rd_st_last_report >= 2 * rd_st_freq)
        rd_stats_report(now);
}

/* The thread that initializes Mono is Unity's main thread. */
static void rd_stats_init(void)
{
    const char* on = getenv("RIMDROID_MONO_STATS");
    if (!on || on[0] != '1')
        return;
    __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(rd_st_freq));
    if (!rd_st_freq)
        return;
    rd_st_gc_no = rd_native_sym("GC_get_gc_no");
    rd_st_gc_heap_size = rd_native_sym("GC_get_heap_size");
    rd_st_gc_total_ms = rd_native_sym("GC_get_full_gc_total_time");
    void (*start_measurement)(void) = rd_native_sym("GC_start_performance_measurement");
    if (start_measurement)
        start_measurement();
    rd_st_frame_count = &g_rimdroid_frame_count;
    rd_st_is_main = 1;
    rd_st_last_report = rd_ticks();
    rd_stats_on = 1;
    printf_log(LOG_NONE, "[RD-MONO-STATS] on: counter %lu Hz, frame counter %s, %d internal call thunks\n",
        (unsigned long)rd_st_freq, rd_st_frame_count ? "found" : "NOT found (per-frame values stay 0)",
        RD_MONO_ICALL_COUNT);
}

/* Time inside the outermost mono_runtime_invoke on the main thread = managed code plus its internal calls. */
static inline uint64_t rd_stats_invoke_enter(void)
{
    if (!rd_stats_on || !rd_st_is_main || rd_st_invoke_depth++ > 0)
        return 0;
    return rd_ticks();
}

static inline void rd_stats_invoke_leave(uint64_t t0)
{
    if (!rd_stats_on || !rd_st_is_main)
        return;
    if (--rd_st_invoke_depth > 0 || !t0)
        return;
    rd_st_invokes++;
    rd_st_invoke_ticks += rd_ticks() - t0;
}

static unsigned long rd_icall_bound;
static unsigned long rd_icall_missing;

/* ValDroid: BurstRuntime.LoadAdditionalLibrary (mods ship their own lib_burst_generated, e.g.
 * ValheimPerformanceOptimizations' VPOBurst_linux_x86_64.so) ends up here. Such a library is x86_64:
 * jobs the engine runs from it are fine under box64, but a [BurstCompile] method called straight from
 * C# (BurstDirectCall) jumps from the native ARM64 Mono into x86 code and crashes — seen with VPO's
 * water waves. Answer "not loaded" instead; mods then take their managed code path. */
static uint8_t rd_burst_refuse_additional_library(void* path)
{
    (void)path;
    static int logged;
    if (!logged) {
        logged = 1;
        printf_log(LOG_NONE, "[RD-MONO] BurstCompilerService.LoadBurstLibrary refused: an x86_64 Burst library "
            "cannot be called from the native ARM64 Mono; the mod falls back to its managed code\n");
    }
    return 0;
}

EXPORT void my_mono_add_internal_call(x64emu_t* emu, const char* name, void* method)
{
    (void)emu;
    if (name && !strcmp(name, "Unity.Burst.LowLevel.BurstCompilerService::LoadBurstLibrary_Injected")) {
        rd_icall_bound++;
        my->mono_add_internal_call((void*)name, (void*)rd_burst_refuse_additional_library);
        return;
    }
    void* host = NULL;
    if (method) {
        host = GetNativeFnc((uintptr_t)method);
        if (!host && name)
            host = rd_mono_icall_bind(name, (uintptr_t)method);
    }
    if (!host) {
        // Registering the x86 address would make ARM64 code jump into x86 code. Leaving the call
        // unregistered makes Mono throw MissingMethodException if managed code ever calls it.
        rd_icall_missing++;
        printf_log(LOG_NONE, "[RD-MONO] no thunk for internal call %s (guest %p), not registered\n",
            name ? name : "(null)", method);
        return;
    }
    if ((++rd_icall_bound % 1000) == 0)
        printf_log(LOG_INFO, "[RD-MONO] internal calls bound: %lu (missing %lu)\n", rd_icall_bound, rd_icall_missing);
    my->mono_add_internal_call((void*)name, host);
}

EXPORT void my_mono_raise_exception(x64emu_t* emu, void* exception)
{
    if (rd_defer_exception(emu, exception))
        return;
    my->mono_raise_exception(exception);
}

/*
 * With a NULL exception slot these APIs raise a managed exception natively (the same unwind as
 * mono_raise_exception). Always pass a slot and route the exception through the bridge.
 */
static void* rd_finish_invoke(x64emu_t* emu, void* ret, void* exception)
{
    if (!exception)
        return ret;
    if (!rd_defer_exception(emu, exception))
        my->mono_raise_exception(exception);
    return NULL;
}

EXPORT void* my_mono_runtime_invoke(x64emu_t* emu, void* method, void* obj, void* params, void** exc)
{
    uint64_t stats_t0 = rd_stats_invoke_enter();
    void* exception = NULL;
    void* ret = my->mono_runtime_invoke(method, obj, params, exc ? exc : &exception);
    rd_stats_invoke_leave(stats_t0);
    if (exc)
        return ret;
    return rd_finish_invoke(emu, ret, exception);
}

EXPORT void* my_mono_runtime_invoke_array(x64emu_t* emu, void* method, void* obj, void* params, void** exc)
{
    if (exc)
        return my->mono_runtime_invoke_array(method, obj, params, exc);
    void* exception = NULL;
    void* ret = my->mono_runtime_invoke_array(method, obj, params, &exception);
    return rd_finish_invoke(emu, ret, exception);
}

EXPORT void* my_mono_runtime_delegate_invoke(x64emu_t* emu, void* delegate, void* params, void** exc)
{
    if (exc)
        return my->mono_runtime_delegate_invoke(delegate, params, exc);
    void* exception = NULL;
    void* ret = my->mono_runtime_delegate_invoke(delegate, params, &exception);
    return rd_finish_invoke(emu, ret, exception);
}

EXPORT int my_mono_runtime_exec_main(x64emu_t* emu, void* method, void* args, void** exc)
{
    if (exc)
        return my->mono_runtime_exec_main(method, args, exc);
    void* exception = NULL;
    int ret = my->mono_runtime_exec_main(method, args, &exception);
    if (exception) {
        rd_finish_invoke(emu, NULL, exception);
        return 1;
    }
    return ret;
}

/*
 * Signal ownership (P5). Mono installs its SIGSEGV/SIGBUS/SIGILL/SIGABRT... handlers during JIT init and
 * replaces Box64's. It keeps the previous handler only with signal chaining on before init, and hands a
 * fault outside JIT code to it only with crash chaining off. Unity sets both itself, so its arguments are
 * overridden. RIMDROID_MONO_NO_SIGNAL_CHAINING=1 passes Unity's values through.
 */
static int rd_signal_override(void)
{
    const char* off = getenv("RIMDROID_MONO_NO_SIGNAL_CHAINING");
    return !(off && off[0] == '1');
}

EXPORT void my_mono_set_signal_chaining(x64emu_t* emu, int chain)
{
    (void)emu;
    int value = rd_signal_override() ? 1 : chain;
    printf_log(LOG_NONE, "[RD-MONO] mono_set_signal_chaining(%d) -> %d\n", chain, value);
    my->mono_set_signal_chaining(value);
}

EXPORT void my_mono_set_crash_chaining(x64emu_t* emu, int chain)
{
    (void)emu;
    int value = rd_signal_override() ? 0 : chain;
    printf_log(LOG_NONE, "[RD-MONO] mono_set_crash_chaining(%d) -> %d\n", chain, value);
    my->mono_set_crash_chaining(value);
}

/*
 * Native libraries for P/Invoke.
 *
 * Managed code asks for libraries the game ships as x86_64 files: etc/mono/config maps System.Native to
 * $mono_libdir/libmono-native.so (MonoBleedingEdge/x86_64), and Steamworks.NET wants steam_api, which
 * Unity's own dl fallback (ignored above: it hands out x86 handles) would find in Plugins. Native Mono
 * cannot dlopen those, and RimWorld quits when steam_api is missing. Mono calls dl fallback handlers after
 * its own dlopen fails, so this one looks for a file of the same name next to the native Mono runtime:
 * the ARM64 libmono-native.so, a libsteam_api.so stub, and whatever else gets an ARM64 build later.
 */
static char rd_native_lib_dir[1024];

static void* rd_dl_try(const char* file)
{
    char path[1280];
    snprintf(path, sizeof(path), "%s/%s", rd_native_lib_dir, file);
    if (access(path, R_OK))
        return NULL;
    void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    printf_log(LOG_NONE, "[RD-MONO] native library %s -> %s%s%s\n", file, path,
        handle ? "" : " FAILED: ", handle ? "" : dlerror());
    return handle;
}

/*
 * glibc system libraries. Managed code written for desktop Linux P/Invokes "libc" (etc/mono/config maps it
 * to libc.so.6) and friends; under the x86 Mono Box64 answered with its wrapped glibc. Android has none of
 * these sonames: hand out the bionic library instead. Harmony depends on it: MonoMod detects the OS and
 * CPU with uname() and allocates its detour memory with mmap/mprotect/sysconf from "libc"; without it
 * PlatformDetection fails and every patch throws NotImplementedException.
 * The few symbols whose glibc ABI differs from bionic are translated in rd_dl_fallback_symbol.
 */
static void* rd_glibc_libc_handle;
static void* rd_glibc_libdl_handle;

/* The bionic soname for a glibc system library name, or NULL when it is not one. */
static const char* rd_glibc_bionic_name(const char* base)
{
    static const struct { const char* glibc; const char* bionic; } map[] = {
        { "libc", "libc.so" }, { "libc.so", "libc.so" }, { "libc.so.6", "libc.so" },
        { "libpthread.so.0", "libc.so" }, { "librt.so.1", "libc.so" },
        { "libdl", "libdl.so" }, { "libdl.so.2", "libdl.so" },
        { "libm", "libm.so" }, { "libm.so.6", "libm.so" },
    };
    for (size_t i = 0; i < sizeof(map) / sizeof(map[0]); ++i)
        if (!strcmp(base, map[i].glibc))
            return map[i].bionic;
    return NULL;
}

static void* rd_dl_try_glibc(const char* base)
{
    const char* bionic = rd_glibc_bionic_name(base);
    if (!bionic)
        return NULL;
    void* handle = dlopen(bionic, RTLD_NOW | RTLD_LOCAL);
    printf_log(LOG_NONE, "[RD-MONO] native library %s -> Android %s%s%s\n", base, bionic,
        handle ? "" : " FAILED: ", handle ? "" : dlerror());
    if (handle && !strcmp(bionic, "libc.so"))
        rd_glibc_libc_handle = handle;
    if (handle && !strcmp(bionic, "libdl.so"))
        rd_glibc_libdl_handle = handle;
    return handle;
}

/* dlopen as handed to managed code. MonoMod's DynDll (BepInEx, mods) opens libraries itself with
 * glibc names — dlopen("libc.so.6"), then "libc" — which do not exist on Android. Map them like the
 * P/Invoke fallback does; glibc and bionic agree on the RTLD_* flag values on arm64. */
static void* rd_glibc_dlopen(const char* file, int flags)
{
    if (file) {
        const char* slash = strrchr(file, '/');
        const char* bionic = rd_glibc_bionic_name(slash ? slash + 1 : file);
        if (bionic) {
            printf_log(LOG_NONE, "[RD-MONO] managed dlopen(%s) -> Android %s\n", file, bionic);
            file = bionic;
        }
    }
    return dlopen(file, flags);
}

/* glibc's errno accessor; bionic calls it __errno. */
static int* rd_glibc_errno_location(void)
{
    return &errno;
}

/* glibc numbers sysconf names differently from bionic; translate the ones desktop code asks for. */
static long rd_glibc_sysconf(int name)
{
    switch (name) {
        case 0: return sysconf(_SC_ARG_MAX);
        case 2: return sysconf(_SC_CLK_TCK);
        case 4: return sysconf(_SC_OPEN_MAX);
        case 30: return sysconf(_SC_PAGESIZE);
        case 31: return sysconf(_SC_RTSIG_MAX);
        case 83: return sysconf(_SC_NPROCESSORS_CONF);
        case 84: return sysconf(_SC_NPROCESSORS_ONLN);
        case 85: return sysconf(_SC_PHYS_PAGES);
        case 86: return sysconf(_SC_AVPHYS_PAGES);
    }
    printf_log(LOG_NONE, "[RD-MONO] libc sysconf(%d): glibc name not translated, returning -1\n", name);
    errno = EINVAL;
    return -1;
}

/* MonoMod declares the offset as a 32-bit int; make sure the upper half of the 64-bit off_t is clean. */
static void* rd_glibc_mmap(void* addr, size_t length, int prot, int flags, int fd, int offset)
{
    return mmap(addr, length, prot, flags, fd, (off_t)offset);
}

static void* rd_dl_fallback_load(const char* name, int flags, char** err, void* user_data)
{
    (void)flags; (void)user_data;
    // err stays NULL: Mono frees it with its own allocator, which Unity replaces.
    if (err) *err = NULL;
    if (!name || !*name || !rd_native_lib_dir[0])
        return NULL;
    const char* base = strrchr(name, '/');
    base = base ? base + 1 : name;
    if (!*base || strlen(base) > 200)
        return NULL;
    /* Mono's etc/mono/config maps these to libmono-native, but Unity parses that file after
     * mono_jit_init_version, and BepInEx (started there) already needs System.Native for its first
     * File.Exists. Same mapping, available from the start. */
    if (!strcmp(base, "System.Native") || !strcmp(base, "System.Net.Security.Native"))
        base = "libmono-native.so";
    void* handle = rd_dl_try(base);
    if (!handle && !strstr(base, ".so")) {
        char file[256];
        snprintf(file, sizeof(file), "%s%s.so", strncmp(base, "lib", 3) ? "lib" : "", base);
        handle = rd_dl_try(file);
    }
    if (!handle)
        handle = rd_dl_try_glibc(base);
    return handle;
}

/*
 * Harmony (MonoMod) refuses to run on Android: its PlatformDetection reports OSKind.Android when both
 * /data and /system/build.prop exist, and PlatformTriple throws NotImplementedException for that OS.
 * Box64 already hides this one path from the emulated world (wrappedlibc.c), so under the x86 Mono
 * MonoMod sees plain Linux. Native Mono reaches the file system through libmono-native instead, so the
 * same path is hidden in the two stat entry points File.Exists goes through. Every P/Invoke symbol of
 * a library loaded by this fallback is resolved by rd_dl_fallback_symbol, which is where they are
 * swapped. MonoMod then picks Linux + Arm64, which Harmony 2.4 and later support.
 */
static int (*rd_real_stat2)(const char*, void*);
static int (*rd_real_lstat2)(const char*, void*);

static int rd_path_is_hidden(const char* path)
{
    static int reported;
    if (!path || strcmp(path, "/system/build.prop"))
        return 0;
    if (!reported) {
        reported = 1;
        printf_log(LOG_NONE, "[RD-MONO] hiding /system/build.prop from managed code (MonoMod platform detection)\n");
    }
    return 1;
}

static int rd_SystemNative_Stat2(const char* path, void* output)
{
    if (rd_path_is_hidden(path)) {
        errno = ENOENT;
        return -1;
    }
    return rd_real_stat2(path, output);
}

static int rd_SystemNative_LStat2(const char* path, void* output)
{
    if (rd_path_is_hidden(path)) {
        errno = ENOENT;
        return -1;
    }
    return rd_real_lstat2(path, output);
}

static void* rd_dl_fallback_symbol(void* handle, const char* name, char** err, void* user_data)
{
    (void)user_data;
    if (err) *err = NULL;
    if (handle && handle == rd_glibc_libc_handle && name) {
        void* translated = NULL;
        if (!strcmp(name, "__errno_location"))
            translated = (void*)rd_glibc_errno_location;
        else if (!strcmp(name, "sysconf"))
            translated = (void*)rd_glibc_sysconf;
        else if (!strcmp(name, "mmap"))
            translated = (void*)rd_glibc_mmap;
        else if (!strcmp(name, "dlopen"))   // glibc 2.34+ exports it from libc
            translated = (void*)rd_glibc_dlopen;
        void* plain = translated ? NULL : dlsym(handle, name);
        printf_log(LOG_NONE, "[RD-MONO] libc symbol %s -> %s\n", name,
            translated ? "glibc ABI shim" : (plain ? "bionic" : "MISSING"));
        return translated ? translated : plain;
    }
    if (handle && handle == rd_glibc_libdl_handle && name && !strcmp(name, "dlopen")) {
        printf_log(LOG_NONE, "[RD-MONO] libdl symbol dlopen -> glibc-name shim\n");
        return (void*)rd_glibc_dlopen;
    }
    void* symbol = dlsym(handle, name);
    if (symbol && name) {
        if (!strcmp(name, "SystemNative_Stat2")) {
            rd_real_stat2 = symbol;
            return rd_SystemNative_Stat2;
        }
        if (!strcmp(name, "SystemNative_LStat2")) {
            rd_real_lstat2 = symbol;
            return rd_SystemNative_LStat2;
        }
    }
    return symbol;
}

static void* rd_dl_fallback_close(void* handle, void* user_data)
{
    (void)user_data;
    dlclose(handle);
    return NULL;
}

static void rd_dl_install_native_fallback(void)
{
    static int installed;
    if (installed)
        return;
    installed = 1;
    const char* path = getenv("RIMDROID_NATIVE_MONO_PATH");
    const char* slash = path ? strrchr(path, '/') : NULL;
    if (!slash || (size_t)(slash - path) >= sizeof(rd_native_lib_dir))
        return;
    memcpy(rd_native_lib_dir, path, slash - path);
    rd_native_lib_dir[slash - path] = 0;
    my->mono_dl_fallback_register(rd_dl_fallback_load, rd_dl_fallback_symbol, rd_dl_fallback_close, NULL);
    printf_log(LOG_NONE, "[RD-MONO] native P/Invoke libraries are taken from %s\n", rd_native_lib_dir);
}

/* ValDroid: BepInEx on the native ARM64 Mono. BepInEx normally comes in through Doorstop, a
 * preloaded library that hooks mono_jit_init_version; on native Mono we own that call already, so
 * we do Doorstop's job here: once the root domain exists, load BepInEx.Preloader.dll and run
 * Doorstop.Entrypoint.Start(). Off unless VALDROID_BEPINEX_PRELOADER names the preloader DLL.
 * BepInEx reads the DOORSTOP_* variables Doorstop would have set; fill in the ones not given. */
static void rd_bepinex_setenv_default(const char* name, const char* value)
{
    if (!getenv(name))
        setenv(name, value, 1);
}

static void rd_bepinex_boot(void* domain)
{
    const char* dll = getenv("VALDROID_BEPINEX_PRELOADER");
    if (!dll || !dll[0])
        return;
    const char* exe = (my_context && my_context->fullpath) ? my_context->fullpath : NULL;
    if (exe) {
        /* Unity's data folder is <executable name without extension>_Data next to the executable. */
        char managed[4096];
        const char* slash = strrchr(exe, '/');
        const char* base = slash ? slash + 1 : exe;
        const char* dot = strrchr(base, '.');
        size_t dirlen = (size_t)(base - exe);
        size_t stemlen = dot ? (size_t)(dot - base) : strlen(base);
        if (dirlen + stemlen + sizeof("_Data/Managed") < sizeof(managed)) {
            memcpy(managed, exe, dirlen);
            memcpy(managed + dirlen, base, stemlen);
            strcpy(managed + dirlen + stemlen, "_Data/Managed");
            rd_bepinex_setenv_default("DOORSTOP_MANAGED_FOLDER_DIR", managed);
        }
        rd_bepinex_setenv_default("DOORSTOP_PROCESS_PATH", exe);
    }
    rd_bepinex_setenv_default("DOORSTOP_INVOKE_DLL_PATH", dll);
    rd_bepinex_setenv_default("DOORSTOP_INITIALIZED", "TRUE");

    /* Plain GO entries are not in `my`: take the native Mono functions straight from the library. */
    void* (*assembly_open)(void*, const char*) = rd_native_sym("mono_domain_assembly_open");
    void* (*get_image)(void*) = rd_native_sym("mono_assembly_get_image");
    void* (*class_from_name)(void*, const char*, const char*) = rd_native_sym("mono_class_from_name");
    void* (*method_from_name)(void*, const char*, int) = rd_native_sym("mono_class_get_method_from_name");
    void* (*object_get_class)(void*) = rd_native_sym("mono_object_get_class");
    const char* (*class_get_namespace)(void*) = rd_native_sym("mono_class_get_namespace");
    const char* (*class_get_name)(void*) = rd_native_sym("mono_class_get_name");
    if (!assembly_open || !get_image || !class_from_name || !method_from_name) {
        printf_log(LOG_NONE, "[RD-MONO] BepInEx: native Mono lacks the embedding API, skipped\n");
        return;
    }

    /* Unity gives the domain its base directory and config file only after mono_jit_init_version;
     * BepInEx needs them sooner (System.Configuration via Trace.Listeners throws "ExeConfigFilename
     * cannot be null"). Doorstop sets them here, to <exe dir> and <exe>.config; so do we. */
    void (*domain_set_config)(void*, const char*, const char*) = rd_native_sym("mono_domain_set_config");
    if (domain_set_config && exe) {
        char base_dir[4096], config[4096];
        const char* slash = strrchr(exe, '/');
        size_t dirlen = slash ? (size_t)(slash - exe) : 0;
        if (dirlen < sizeof(base_dir) && strlen(exe) + sizeof(".config") < sizeof(config)) {
            memcpy(base_dir, exe, dirlen);
            base_dir[dirlen] = 0;
            snprintf(config, sizeof(config), "%s.config", exe);
            domain_set_config(domain, base_dir, config);
            printf_log(LOG_NONE, "[RD-MONO] BepInEx: domain config %s (base %s)\n", config, base_dir);
        }
    }

    printf_log(LOG_NONE, "[RD-MONO] BepInEx: loading %s\n", dll);
    void* assembly = assembly_open(domain, dll);
    if (!assembly) {
        printf_log(LOG_NONE, "[RD-MONO] BepInEx: cannot open the preloader assembly\n");
        return;
    }
    void* image = get_image(assembly);
    void* klass = image ? class_from_name(image, "Doorstop", "Entrypoint") : NULL;
    void* method = klass ? method_from_name(klass, "Start", 0) : NULL;
    if (!method) {
        printf_log(LOG_NONE, "[RD-MONO] BepInEx: Doorstop.Entrypoint.Start() not found\n");
        return;
    }
    void* exception = NULL;
    my->mono_runtime_invoke(method, NULL, NULL, &exception);
    if (exception) {
        void* eklass = object_get_class ? object_get_class(exception) : NULL;
        printf_log(LOG_NONE, "[RD-MONO] BepInEx: Start() threw %s.%s\n",
            (eklass && class_get_namespace) ? class_get_namespace(eklass) : "?",
            (eklass && class_get_name) ? class_get_name(eklass) : "?");
        /* The type name alone says little (a TypeInitializationException hides the real failure in
         * its InnerException), so log Exception.ToString(): message, inner exceptions, stack. */
        void* (*object_to_string)(void*, void**) = rd_native_sym("mono_object_to_string");
        char* (*string_to_utf8)(void*) = rd_native_sym("mono_string_to_utf8");
        void (*mono_free_fn)(void*) = rd_native_sym("mono_free");
        if (object_to_string && string_to_utf8) {
            void* inner_exc = NULL;
            void* text = object_to_string(exception, &inner_exc);
            char* utf8 = (text && !inner_exc) ? string_to_utf8(text) : NULL;
            if (utf8) {
                printf_log(LOG_NONE, "[RD-MONO] BepInEx: %s\n", utf8);
                if (mono_free_fn) mono_free_fn(utf8);
            }
        }
        return;
    }
    printf_log(LOG_NONE, "[RD-MONO] BepInEx: preloader finished\n");
}

EXPORT void* my_mono_jit_init_version(x64emu_t* emu, const char* domain_name, const char* runtime_version)
{
    rd_dl_install_native_fallback();
    if (rd_signal_override()) {
        my->mono_set_signal_chaining(1);
        my->mono_set_crash_chaining(0);
    }
    printf_log(LOG_NONE, "[RD-MONO] mono_jit_init_version(%s, %s) on native ARM64 Mono\n",
        domain_name ? domain_name : "(null)", runtime_version ? runtime_version : "(null)");
    void* domain = my->mono_jit_init_version((void*)domain_name, (void*)runtime_version);
    if (domain) {
        rd_set_pending_exception = rd_native_sym("mono_runtime_set_pending_exception");
        if (!rd_set_pending_exception)
            printf_log(LOG_NONE, "[RD-MONO] mono_runtime_set_pending_exception missing: exceptions from x86 internal calls will corrupt the guest\n");
        rd_gc_install(emu);
        rd_stats_init();
        rd_bepinex_boot(domain);
    }
    printf_log(LOG_NONE, "[RD-MONO] mono_jit_init_version -> domain %p\n", domain);
    return domain;
}

EXPORT void my_mono_unity_jit_cleanup(x64emu_t* emu, void* domain)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] jit cleanup: %lu collections scanned guest roots (last %lu stack bytes), %lu internal calls bound, %lu missing\n",
        rd_gc_collections, rd_gc_bytes_last, rd_icall_bound, rd_icall_missing);
    my->mono_unity_jit_cleanup(domain);
}

EXPORT void* my_mono_thread_attach(x64emu_t* emu, void* domain)
{
    void* thread = my->mono_thread_attach(domain);
    rd_gc_register_emu(emu);
    return thread;
}

/*
 * Callbacks from native Mono into x86 code.
 *
 * Synchronous iterators (foreach, stack walks) keep the guest function in a thread-local that is saved and
 * restored around the call, so nesting and concurrent threads stay correct. Callbacks that Mono stores and
 * calls later use a single static slot each: Unity installs each of them once.
 */
#define RD_GUEST_OR_NATIVE(fct) (GetNativeFnc((uintptr_t)(fct)))

static __thread uintptr_t rd_cb_gfunc;
static void rd_cb_gfunc_thunk(void* data, void* user_data)
{
    RunFunctionFmt(rd_cb_gfunc, "pp", data, user_data);
}

static __thread uintptr_t rd_cb_stackwalk;
static int rd_cb_stackwalk_thunk(void* method, int32_t native_offset, int32_t il_offset, int managed, void* data)
{
    return (int)RunFunctionFmt(rd_cb_stackwalk, "piiip", method, native_offset, il_offset, managed, data);
}

#define RD_WITH_GUEST(slot, fct, thunk, native_call, bridged_call) \
    do { \
        void* rd_native = (fct) ? RD_GUEST_OR_NATIVE(fct) : NULL; \
        if (!(fct) || rd_native) { native_call; break; } \
        uintptr_t rd_saved = slot; \
        slot = (uintptr_t)(fct); \
        bridged_call; \
        slot = rd_saved; \
    } while (0)

EXPORT void my_mono_stack_walk(x64emu_t* emu, void* func, void* user_data)
{
    (void)emu;
    RD_WITH_GUEST(rd_cb_stackwalk, func, rd_cb_stackwalk_thunk,
        my->mono_stack_walk(rd_native ? rd_native : func, user_data),
        my->mono_stack_walk(rd_cb_stackwalk_thunk, user_data));
}

EXPORT void my_mono_stack_walk_no_il(x64emu_t* emu, void* func, void* user_data)
{
    (void)emu;
    RD_WITH_GUEST(rd_cb_stackwalk, func, rd_cb_stackwalk_thunk,
        my->mono_stack_walk_no_il(rd_native ? rd_native : func, user_data),
        my->mono_stack_walk_no_il(rd_cb_stackwalk_thunk, user_data));
}

#define RD_GFUNC_2(NAME) \
EXPORT void my_##NAME(x64emu_t* emu, void* func, void* user_data) \
{ \
    (void)emu; \
    RD_WITH_GUEST(rd_cb_gfunc, func, rd_cb_gfunc_thunk, \
        my->NAME(rd_native ? rd_native : func, user_data), \
        my->NAME(rd_cb_gfunc_thunk, user_data)); \
}

#define RD_GFUNC_3(NAME) \
EXPORT void my_##NAME(x64emu_t* emu, void* first, void* func, void* user_data) \
{ \
    (void)emu; \
    RD_WITH_GUEST(rd_cb_gfunc, func, rd_cb_gfunc_thunk, \
        my->NAME(first, rd_native ? rd_native : func, user_data), \
        my->NAME(first, rd_cb_gfunc_thunk, user_data)); \
}

// GFunc, MonoFunc and ClassReportFunc all take (pointer, user_data).
RD_GFUNC_2(mono_assembly_foreach)
RD_GFUNC_2(mono_unity_gc_handles_foreach_get_target)
RD_GFUNC_2(mono_unity_gc_heap_foreach)
RD_GFUNC_2(mono_unity_image_set_mempool_chunk_foreach)
RD_GFUNC_2(mono_unity_root_domain_mempool_chunk_foreach)
RD_GFUNC_2(mono_unity_class_for_each)
RD_GFUNC_3(mono_unity_assembly_mempool_chunk_foreach)
RD_GFUNC_3(mono_unity_domain_mempool_chunk_foreach)
RD_GFUNC_3(mono_unity_type_get_name_full_chunked)

#undef RD_GFUNC_2
#undef RD_GFUNC_3

// MonoLogCallback: (log_domain, log_level, message, fatal, user_data)
static uintptr_t rd_cb_log;
static void rd_cb_log_thunk(const char* domain, const char* level, const char* message, int fatal, void* user_data)
{
    RunFunctionFmt(rd_cb_log, "pppip", domain, level, message, fatal, user_data);
}

EXPORT void my_mono_trace_set_log_handler(x64emu_t* emu, void* callback, void* user_data)
{
    (void)emu;
    void* native = callback ? GetNativeFnc((uintptr_t)callback) : NULL;
    if (callback && !native) {
        rd_cb_log = (uintptr_t)callback;
        native = rd_cb_log_thunk;
    }
    my->mono_trace_set_log_handler(native, user_data);
}

// UnityFindPluginCallback: const char* (const char*)
static uintptr_t rd_cb_find_plugin;
static const char* rd_cb_find_plugin_thunk(const char* name)
{
    return (const char*)RunFunctionFmt(rd_cb_find_plugin, "p", name);
}

EXPORT void my_mono_set_find_plugin_callback(x64emu_t* emu, void* find)
{
    (void)emu;
    void* native = find ? GetNativeFnc((uintptr_t)find) : NULL;
    if (find && !native) {
        rd_cb_find_plugin = (uintptr_t)find;
        native = rd_cb_find_plugin_thunk;
    }
    my->mono_set_find_plugin_callback(native);
}

/*
 * vprintf_func receives a native va_list that x86 code cannot read. Mono's own output is formatted here
 * and written to the Box64 log instead of Unity's handler.
 */
static int rd_mono_vprintf(const char* message, va_list args)
{
    char buffer[4096];
    int len = vsnprintf(buffer, sizeof(buffer), message, args);
    size_t end = strlen(buffer);
    if (end && buffer[end - 1] == '\n')
        buffer[end - 1] = 0;
    printf_log(LOG_NONE, "[RD-MONO] mono: %s\n", buffer);
    return len;
}

EXPORT void my_mono_unity_set_vprintf_func(x64emu_t* emu, void* func)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_unity_set_vprintf_func(%p): Mono output goes to the Box64 log\n", func);
    my->mono_unity_set_vprintf_func(func ? rd_mono_vprintf : NULL);
}

/*
 * The dl fallback resolves P/Invoke libraries and symbols through x86 callbacks, which would hand native
 * Mono x86 handles and x86 code addresses. Not registered: native Mono loads native libraries itself.
 */
EXPORT void* my_mono_dl_fallback_register(x64emu_t* emu, void* load_func, void* symbol_func, void* close_func, void* user_data)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_dl_fallback_register(load=%p symbol=%p close=%p data=%p) ignored\n",
        load_func, symbol_func, close_func, user_data);
    return NULL;
}

EXPORT void my_mono_dl_fallback_unregister(x64emu_t* emu, void* handler)
{
    (void)emu;
    if (handler)
        my->mono_dl_fallback_unregister(handler);
}

// Legacy profiler: only the shutdown callback is bridged, it runs once on the shutting-down thread.
static uintptr_t rd_cb_profiler_shutdown;
static void rd_cb_profiler_shutdown_thunk(void* prof)
{
    RunFunctionFmt(rd_cb_profiler_shutdown, "p", prof);
}

EXPORT void my_mono_profiler_install(x64emu_t* emu, void* prof, void* callback)
{
    (void)emu;
    void* native = callback ? GetNativeFnc((uintptr_t)callback) : NULL;
    if (callback && !native) {
        rd_cb_profiler_shutdown = (uintptr_t)callback;
        native = rd_cb_profiler_shutdown_thunk;
    }
    my->mono_profiler_install(prof, native);
}

/*
 * GC event callbacks can run while the world is stopped. Running x86 code then can block forever on a
 * Box64 lock owned by a stopped thread, so they are not installed.
 */
EXPORT void my_mono_profiler_install_gc(x64emu_t* emu, void* callback, void* heap_resize_callback)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_profiler_install_gc(%p, %p) ignored\n", callback, heap_resize_callback);
}

EXPORT int my_mono_profiler_get_all_coverage_data(x64emu_t* emu, void* handle, void* callback)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_profiler_get_all_coverage_data(%p, %p) ignored\n", handle, callback);
    return 0;
}

EXPORT int my_mono_profiler_get_coverage_data(x64emu_t* emu, void* handle, void* method, void* callback)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_profiler_get_coverage_data(%p, %p, %p) ignored\n", handle, method, callback);
    return 0;
}

EXPORT void my_mono_profiler_set_coverage_filter_callback(x64emu_t* emu, void* handle, void* callback)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_profiler_set_coverage_filter_callback(%p, %p) ignored\n", handle, callback);
}

/*
 * Liveness (Unity's memory profiler): the callbacks are stored in the state and called synchronously by
 * the calculation functions. One state is used at a time.
 */
static uintptr_t rd_cb_liveness_register;

static void rd_cb_liveness_register_thunk(void** array, int size, void* user_data)
{
    RunFunctionFmt(rd_cb_liveness_register, "pip", array, size, user_data);
}

// Mono uses this callback only for its own 8 KB liveness blocks, which it frees again through the same
// callback with size 0; the engine never sees them. Unity 6's x86 allocator behind the guest callback tried to
// reserve ~252 GB of address space from Mono's worker threads, failed on the 39-bit Android address space and
// returned NULL, which Mono writes through (block_array_push_back). A native allocator keeps the engine out of
// this path entirely and avoids an emulated call per block.
static void* rd_liveness_realloc_native(void* ptr, int size, void* user_data)
{
    (void)user_data;
    if (size <= 0) {
        free(ptr);
        return NULL;
    }
    void* r = realloc(ptr, (size_t)size);
    if (!r) printf_log(LOG_NONE, "[RD-MONO] liveness block allocation of %d bytes failed\n", size);
    return r;
}

EXPORT void* my_mono_unity_liveness_allocate_struct(x64emu_t* emu, void* filter, uint32_t max_count,
    void* callback, void* user_data, void* reallocate)
{
    (void)emu;
    void* native_callback = callback ? GetNativeFnc((uintptr_t)callback) : NULL;
    if (callback && !native_callback) {
        rd_cb_liveness_register = (uintptr_t)callback;
        native_callback = rd_cb_liveness_register_thunk;
    }
    (void)reallocate;   // the engine's allocator is not needed for Mono-internal blocks, see above
    return my->mono_unity_liveness_allocate_struct(filter, max_count, native_callback, user_data,
                                                   rd_liveness_realloc_native);
}

// unitytls is a table of x86 function pointers used by Mono's TLS provider; not needed to reach the menu.
// Unity 6 hands Mono its allocator (malloc/calloc/realloc/free and friends) as x86 function pointers. Calling
// them from native Mono would run emulated code on every allocation, including with the world stopped, so
// Mono keeps its own allocator.
EXPORT void my_mono_unity_install_memory_callbacks(x64emu_t* emu, void* callbacks)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_unity_install_memory_callbacks(%p) ignored\n", callbacks);
}

EXPORT void my_mono_unity_install_unitytls_interface(x64emu_t* emu, void* callbacks)
{
    (void)emu;
    printf_log(LOG_NONE, "[RD-MONO] mono_unity_install_unitytls_interface(%p) ignored\n", callbacks);
}

#ifndef STATICBUILD
#define PRE_INIT \
    if (1) { \
        const char* path = getenv("RIMDROID_NATIVE_MONO_PATH"); \
        if (!path || !*path) return -1; \
        lib->w.lib = dlopen(path, RTLD_NOW | RTLD_GLOBAL); \
        if (!lib->w.lib) { \
            printf_log(LOG_NONE, "[RD-MONO] cannot load native Mono %s: %s\n", path, dlerror()); \
            return -1; \
        } \
        printf_log(LOG_NONE, "[RD-MONO] using native ARM64 Mono %s\n", path); \
    } else
#endif

#include "wrappedlib_init.h"

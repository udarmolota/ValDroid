#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

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

const char* rdmonoprobeName = "librdmonoprobe.so";
#define LIBNAME rdmonoprobe

#include "generated/wrappedrdmonoprobetypes.h"
#include "wrappercallback.h"

#define REVERSE_ICALL_SLOTS() \
    GO(0) \
    GO(1) \
    GO(2) \
    GO(3)

#define GO(A) \
static uintptr_t reverse_icall_iFii_fct_##A = 0; \
static int reverse_icall_iFii_##A(int left, int right) \
{ \
    printf_log(LOG_NONE, "RIMDROID P2 reverse icall slot=%d left=0x%x right=0x%x\n", A, left, right); \
    return (int)RunFunctionFmt(reverse_icall_iFii_fct_##A, "ii", left, right); \
}
REVERSE_ICALL_SLOTS()
#undef GO

static void* find_reverse_icall_iFii(void* fct)
{
    if (!fct) return NULL;
    if (GetNativeFnc((uintptr_t)fct)) return GetNativeFnc((uintptr_t)fct);
#define GO(A) if (reverse_icall_iFii_fct_##A == (uintptr_t)fct) return reverse_icall_iFii_##A;
    REVERSE_ICALL_SLOTS()
#undef GO
#define GO(A) if (!reverse_icall_iFii_fct_##A) { reverse_icall_iFii_fct_##A = (uintptr_t)fct; return reverse_icall_iFii_##A; }
    REVERSE_ICALL_SLOTS()
#undef GO
    printf_log(LOG_NONE, "Warning, no free P2 reverse icall slot\n");
    return NULL;
}

#undef REVERSE_ICALL_SLOTS

static uintptr_t reverse_icall_IFII_fct;
static int64_t reverse_icall_IFII(int64_t left, int64_t right)
{
    return (int64_t)RunFunctionFmt(reverse_icall_IFII_fct, "II", left, right);
}

static uintptr_t reverse_icall_pFp_fct;
static void* reverse_icall_pFp(void* value)
{
    return (void*)RunFunctionFmt(reverse_icall_pFp_fct, "p", value);
}

static uintptr_t reverse_icall_dFdd_fct;
static double reverse_icall_dFdd(double left, double right)
{
    return RunFunctionFmtD(reverse_icall_dFdd_fct, "dd", left, right);
}

static uintptr_t reverse_icall_dFff_fct;
static double reverse_icall_dFff(float left, float right)
{
    return RunFunctionFmtD(reverse_icall_dFff_fct, "ff", left, right);
}

static uintptr_t reverse_icall_IFIIIIIIIII_fct;
static int64_t reverse_icall_IFIIIIIIIII(
    int64_t a, int64_t b, int64_t c, int64_t d, int64_t e,
    int64_t f, int64_t g, int64_t h, int64_t i)
{
    return (int64_t)RunFunctionFmt(
        reverse_icall_IFIIIIIIIII_fct, "IIIIIIIII",
        a, b, c, d, e, f, g, h, i);
}

#define NARROW_IDENTITY(NAME, CTYPE, FMT) \
    static uintptr_t reverse_icall_##NAME##_fct; \
    static CTYPE reverse_icall_##NAME(CTYPE value) \
    { \
        return (CTYPE)RunFunctionFmt(reverse_icall_##NAME##_fct, FMT, value); \
    }

NARROW_IDENTITY(cFc, int8_t, "c")
NARROW_IDENTITY(CFC, uint8_t, "C")
NARROW_IDENTITY(wFw, int16_t, "w")
NARROW_IDENTITY(WFW, uint16_t, "W")

#undef NARROW_IDENTITY

static uintptr_t reverse_icall_vFp_fct;
static void reverse_icall_vFp(void* value)
{
    RunFunctionFmt(reverse_icall_vFp_fct, "p", value);
}

static uintptr_t reverse_icall_vFv_fct[2];
static void reverse_icall_vFv_0(void)
{
    RunFunctionFmt(reverse_icall_vFv_fct[0], "");
}
static void reverse_icall_vFv_1(void)
{
    RunFunctionFmt(reverse_icall_vFv_fct[1], "");
}

/*
 * P4: managed exceptions raised by x86 internal calls.
 *
 * A native mono_raise_exception unwinds straight to the managed catch block using Mono's LMF chain.
 * Everything in between is dropped without running: this thunk, RunFunctionFmt, DynaCall and the
 * dynarec frames. DynaCall never restores the guest registers, the guest stack pointer is left inside
 * the dead x86 frames, and the emulator state is left mid-call. It works once and corrupts the guest.
 *
 * The bridge never lets that unwind cross Box64. When the guest raises inside a reverse internal call,
 * the exception becomes Mono's pending exception and the emulator returns from this call early:
 * emu->quit makes the dynarec exit right after the mono_raise_exception bridge, DynaCall then restores
 * RBX/RDI/RSI/RBP/RSP/RIP, and this thunk restores R12-R15, which DynaCall does not save. The internal
 * call returns normally, and Mono's wrapper for foreign internal calls throws the pending exception at
 * its interruption checkpoint, inside managed code, where unwinding is native-only again.
 *
 * RIMDROID_P4_NO_EXCEPTION_BRIDGE=1 restores the raw native raise so one build can show both outcomes.
 * Real Unity needs this in every reverse thunk, not only the probe's.
 */
static __thread int rd_p4_reverse_depth;
static __thread int rd_p4_raised;
static int (*rd_p4_set_pending_exception)(void*, int);
static int rd_p4_bridge_disabled = -1;

static int rd_p4_bridge_enabled(void)
{
    if (rd_p4_bridge_disabled < 0) {
        const char* off = getenv("RIMDROID_P4_NO_EXCEPTION_BRIDGE");
        rd_p4_bridge_disabled = (off && off[0] == '1') ? 1 : 0;
    }
    return !rd_p4_bridge_disabled;
}

static uintptr_t reverse_icall_throw_iFi_fct;
static int reverse_icall_throw_iFi(int marker)
{
    x64emu_t* emu = thread_get_emu();
    uint64_t saved_r12 = emu->regs[_R12].q[0];
    uint64_t saved_r13 = emu->regs[_R13].q[0];
    uint64_t saved_r14 = emu->regs[_R14].q[0];
    uint64_t saved_r15 = emu->regs[_R15].q[0];
    int outer_raised = rd_p4_raised;
    rd_p4_raised = 0;
    rd_p4_reverse_depth++;
    int ret = (int)RunFunctionFmt(reverse_icall_throw_iFi_fct, "i", marker);
    rd_p4_reverse_depth--;
    if (rd_p4_raised) {
        emu->regs[_R12].q[0] = saved_r12;
        emu->regs[_R13].q[0] = saved_r13;
        emu->regs[_R14].q[0] = saved_r14;
        emu->regs[_R15].q[0] = saved_r15;
        ret = 0;   /* discarded: the wrapper throws the pending exception */
    }
    rd_p4_raised = outer_raised;
    return ret;
}

EXPORT void my_rdprobe_mono_raise_exception(x64emu_t* emu, void* exception)
{
    if (rd_p4_bridge_enabled() && rd_p4_reverse_depth > 0 && rd_p4_set_pending_exception) {
        rd_p4_set_pending_exception(exception, 1);
        rd_p4_raised = 1;
        emu->quit = 1;
        return;
    }
    my->mono_raise_exception(exception);
}

static uintptr_t reverse_icall_smc_iFi_fct;
static int reverse_icall_smc_iFi(int round)
{
    return (int)RunFunctionFmt(reverse_icall_smc_iFi_fct, "i", round);
}

/* Transition cost benchmark thunks: dedicated slots with no logging on the hot path. */
static uintptr_t reverse_icall_bench_iFi_fct;
static int reverse_icall_bench_iFi(int value)
{
    return (int)RunFunctionFmt(reverse_icall_bench_iFi_fct, "i", value);
}

static uintptr_t reverse_icall_now_IFv_fct;
static int64_t reverse_icall_now_IFv(void)
{
    return (int64_t)RunFunctionFmt(reverse_icall_now_IFv_fct, "");
}

static uintptr_t reverse_icall_report_vFIIi_fct;
static void reverse_icall_report_vFIIi(int64_t reverse_icall_ns, int64_t managed_call_ns, int calls)
{
    RunFunctionFmt(reverse_icall_report_vFIIi_fct, "IIi", reverse_icall_ns, managed_call_ns, calls);
}

static void* select_reverse_icall(const char* name, void* method)
{
    void* native = GetNativeFnc((uintptr_t)method);
    if (native) return native;

    if (strstr(name, "::NativeAddLong")) {
        reverse_icall_IFII_fct = (uintptr_t)method;
        return reverse_icall_IFII;
    }
    if (strstr(name, "::NativePointerIdentity")) {
        reverse_icall_pFp_fct = (uintptr_t)method;
        return reverse_icall_pFp;
    }
    if (strstr(name, "::NativeAddDouble")) {
        reverse_icall_dFdd_fct = (uintptr_t)method;
        return reverse_icall_dFdd;
    }
    if (strstr(name, "::NativeAddFloatArgs")) {
        reverse_icall_dFff_fct = (uintptr_t)method;
        return reverse_icall_dFff;
    }
    if (strstr(name, "::NativeSumNine")) {
        reverse_icall_IFIIIIIIIII_fct = (uintptr_t)method;
        return reverse_icall_IFIIIIIIIII;
    }
    if (strstr(name, "::NativeSByteIdentity")) {
        reverse_icall_cFc_fct = (uintptr_t)method;
        return reverse_icall_cFc;
    }
    if (strstr(name, "::NativeByteIdentity")) {
        reverse_icall_CFC_fct = (uintptr_t)method;
        return reverse_icall_CFC;
    }
    if (strstr(name, "::NativeInt16Identity")) {
        reverse_icall_wFw_fct = (uintptr_t)method;
        return reverse_icall_wFw;
    }
    if (strstr(name, "::NativeUInt16Identity")) {
        reverse_icall_WFW_fct = (uintptr_t)method;
        return reverse_icall_WFW;
    }
    if (strstr(name, "::NativePublishObject")) {
        reverse_icall_vFp_fct = (uintptr_t)method;
        return reverse_icall_vFp;
    }
    if (strstr(name, "::NativeInstallGuestRoot")) {
        reverse_icall_vFv_fct[0] = (uintptr_t)method;
        return reverse_icall_vFv_0;
    }
    if (strstr(name, "::NativeSmcRound")) {
        reverse_icall_smc_iFi_fct = (uintptr_t)method;
        return reverse_icall_smc_iFi;
    }
    if (strstr(name, "::NativeBenchIdentity")) {
        reverse_icall_bench_iFi_fct = (uintptr_t)method;
        return reverse_icall_bench_iFi;
    }
    if (strstr(name, "::NativeNowNs")) {
        reverse_icall_now_IFv_fct = (uintptr_t)method;
        return reverse_icall_now_IFv;
    }
    if (strstr(name, "::NativeReportBench")) {
        reverse_icall_report_vFIIi_fct = (uintptr_t)method;
        return reverse_icall_report_vFIIi;
    }
    if (strstr(name, "::NativeThrowFromGuest")) {
        reverse_icall_throw_iFi_fct = (uintptr_t)method;
        return reverse_icall_throw_iFi;
    }
    if (strstr(name, "::NativeClearGuestRoot")) {
        reverse_icall_vFv_fct[1] = (uintptr_t)method;
        return reverse_icall_vFv_1;
    }
    return find_reverse_icall_iFii(method);
}

/*
 * P3: foreign GC roots.
 *
 * ARM64 Mono's Boehm GC scans native thread stacks, but an x86 guest keeps object references in Box64
 * state it cannot see: the emulated registers and a separately mapped guest stack. The baseline probe
 * proved the loss (-201: an object referenced only from a guest stack slot was collected).
 *
 * Mono already chains the root hook (boehm-gc.c keeps the previous GC_push_other_roots and calls it), so
 * after mono_jit_init_version we chain once more on top of Mono's callback instead of replacing it, and
 * push the guest registers and the live part of the guest stack.
 *
 * Scope is deliberately the probe's single guest thread. Real Unity needs a per-thread registry with
 * thread-exit handling. The callback runs with the world stopped and the GC allocation lock held: it must
 * not lock, allocate or print, because a suspended thread may own whatever it would wait on.
 */
typedef void (*rd_gc_push_other_roots_t)(void);
static rd_gc_push_other_roots_t rd_gc_prev_push_other_roots;
static void (*rd_gc_push_all)(void*, void*);
static void* (*rd_gc_call_with_alloc_lock)(void* (*)(void*), void*);
static x64emu_t* volatile rd_gc_guest_emu;
static volatile unsigned long rd_gc_guest_pushes;

static void rd_gc_push_guest_roots(void)
{
    if (rd_gc_prev_push_other_roots)
        rd_gc_prev_push_other_roots();
    x64emu_t* emu = rd_gc_guest_emu;
    if (!emu || !rd_gc_push_all)
        return;
    rd_gc_guest_pushes++;
    rd_gc_push_all(&emu->regs[0], &emu->regs[16]);
    rd_gc_push_all(&emu->xmm[0], &emu->xmm[16]);
    uintptr_t bottom = (uintptr_t)emu->init_stack;
    uintptr_t top = bottom + emu->size_stack;
    uintptr_t sp = (uintptr_t)emu->regs[_SP].q[0] - 128;   /* SysV red zone below RSP */
    uintptr_t lo = (sp >= bottom && sp < top) ? sp : bottom;
    if (lo < top)
        rd_gc_push_all((void*)lo, (void*)top);
}

static void* rd_gc_clear_guest_emu_locked(void* unused)
{
    (void)unused;
    rd_gc_guest_emu = NULL;
    return NULL;
}

static void rd_gc_install_guest_roots(x64emu_t* emu)
{
    const char* off = getenv("RIMDROID_P3_NO_GUEST_ROOTS");
    if (off && off[0] == '1') {
        printf_log(LOG_NONE, "RIMDROID P3 guest GC roots DISABLED (RIMDROID_P3_NO_GUEST_ROOTS=1)\n");
        return;
    }
    const char* path = getenv("RIMDROID_NATIVE_MONO_PATH");
    void* h = (path && *path) ? dlopen(path, RTLD_NOW | RTLD_NOLOAD) : NULL;
    rd_gc_push_other_roots_t (*get_push)(void) = h ? dlsym(h, "GC_get_push_other_roots") : NULL;
    void (*set_push)(rd_gc_push_other_roots_t) = h ? dlsym(h, "GC_set_push_other_roots") : NULL;
    rd_gc_push_all = h ? dlsym(h, "GC_push_all") : NULL;
    rd_gc_call_with_alloc_lock = h ? dlsym(h, "GC_call_with_alloc_lock") : NULL;
    if (!get_push || !set_push || !rd_gc_push_all || !rd_gc_call_with_alloc_lock) {
        printf_log(LOG_NONE, "RIMDROID P3 guest GC roots NOT installed: missing GC hooks (h=%p get=%p set=%p push=%p lock=%p)\n",
            h, get_push, set_push, rd_gc_push_all, rd_gc_call_with_alloc_lock);
        return;
    }
    rd_gc_guest_emu = emu;
    rd_gc_prev_push_other_roots = get_push();
    set_push(rd_gc_push_guest_roots);
    printf_log(LOG_NONE, "RIMDROID P3 guest GC roots installed prev=%p emu=%p stack=[%p,%p)\n",
        rd_gc_prev_push_other_roots, emu, emu->init_stack,
        (void*)((uintptr_t)emu->init_stack + emu->size_stack));
}

/*
 * P5: signal ownership. Mono installs its SIGSEGV/SIGBUS/SIGILL/SIGABRT handlers during JIT init,
 * replacing the ones Box64 installed at startup, and Box64 depends on SIGSEGV (write-protected
 * translated code, guest faults). Mono only keeps the previous handler when signal chaining is on
 * before init, and only hands a fault outside JIT code to it when crash chaining is OFF; with crash
 * chaining on it reports a native crash first. So force chaining on and crash chaining off before
 * init, whatever the embedder would pass. RIMDROID_P5_NO_SIGNAL_CHAINING=1 skips this for the baseline.
 */
static void rd_p5_configure_signal_chaining(void)
{
    const char* off = getenv("RIMDROID_P5_NO_SIGNAL_CHAINING");
    if (off && off[0] == '1') {
        printf_log(LOG_NONE, "RIMDROID P5 signal chaining NOT configured (RIMDROID_P5_NO_SIGNAL_CHAINING=1)\n");
        return;
    }
    const char* path = getenv("RIMDROID_NATIVE_MONO_PATH");
    void* h = (path && *path) ? dlopen(path, RTLD_NOW | RTLD_NOLOAD) : NULL;
    void (*set_signal_chaining)(int) = h ? dlsym(h, "mono_set_signal_chaining") : NULL;
    void (*set_crash_chaining)(int) = h ? dlsym(h, "mono_set_crash_chaining") : NULL;
    if (set_signal_chaining)
        set_signal_chaining(1);
    if (set_crash_chaining)
        set_crash_chaining(0);
    printf_log(LOG_NONE, "RIMDROID P5 signal chaining on, crash chaining off (set=%p crash=%p)\n",
        set_signal_chaining, set_crash_chaining);
}

EXPORT void* my_rdprobe_mono_jit_init_version(x64emu_t* emu, const char* domain_name, const char* runtime_version)
{
    rd_p5_configure_signal_chaining();
    void* domain = my->mono_jit_init_version((void*)domain_name, (void*)runtime_version);
    if (domain) {
        const char* path = getenv("RIMDROID_NATIVE_MONO_PATH");
        void* h = (path && *path) ? dlopen(path, RTLD_NOW | RTLD_NOLOAD) : NULL;
        rd_p4_set_pending_exception = h ? dlsym(h, "mono_runtime_set_pending_exception") : NULL;
        printf_log(LOG_NONE, "RIMDROID P4 exception bridge %s (set_pending=%p)\n",
            rd_p4_bridge_enabled() ? "enabled" : "DISABLED (RIMDROID_P4_NO_EXCEPTION_BRIDGE=1)",
            rd_p4_set_pending_exception);
        rd_gc_install_guest_roots(emu);
    }
    return domain;
}

EXPORT void my_rdprobe_mono_jit_cleanup(x64emu_t* emu, void* domain)
{
    (void)emu;
    if (rd_gc_guest_emu && rd_gc_call_with_alloc_lock)
        rd_gc_call_with_alloc_lock(rd_gc_clear_guest_emu_locked, NULL);
    printf_log(LOG_NONE, "RIMDROID P3 guest GC roots pushed %lu time(s)\n", rd_gc_guest_pushes);
    my->mono_jit_cleanup(domain);
}

EXPORT void my_rdprobe_mono_add_internal_call(x64emu_t* emu, const char* name, void* method)
{
    (void)emu;
    void* host_method = select_reverse_icall(name, method);
    printf_log(LOG_NONE, "RIMDROID P2 register icall name=%s guest=%p host=%p\n",
        name ? name : "(null)", method, host_method);
    my->mono_add_internal_call((void*)name, host_method);
}

/*
 * This wrapper exists solely for the ARM64 Mono feasibility probe.  Loading the
 * real Unity guest library by its pathname would make Box64 choose emulation,
 * so the probe asks for this synthetic name and explicitly supplies the native
 * library through RIMDROID_NATIVE_MONO_PATH.
 */
// The libmonobdwgc wrapper defines the my_mono_* versions of these functions; the probe's live
// under their own prefix.
#define ALTMY my_rdprobe_

#ifndef STATICBUILD
#define PRE_INIT \
    if (1) { \
        const char* path = getenv("RIMDROID_NATIVE_MONO_PATH"); \
        if (!path || !*path) return -1; \
        lib->w.lib = dlopen(path, RTLD_NOW | RTLD_GLOBAL); \
        if (!lib->w.lib) return -1; \
    } else
#endif

#include "wrappedlib_init.h"

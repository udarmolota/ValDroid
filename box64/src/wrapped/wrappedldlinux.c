#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define _GNU_SOURCE         /* See feature_test_macros(7) */
#include <dlfcn.h>

#include "wrappedlibs.h"

#include "debug.h"
#include "wrapper.h"
#include "bridge.h"
#include "librarian/library_private.h"
#include "x64emu.h"
#include "emu/x64emu_private.h"
#include "elfloader.h"
#include "box64context.h"
#include "x64tls.h"

typedef struct my_tls_s {
    unsigned long int   i;
    unsigned long int   o;
} my_tls_t;

EXPORT void* my___tls_get_addr(x64emu_t* emu, void* p)
{
    my_tls_t *t = (my_tls_t*)p;
    // RimDroid: this wrapper races AddElfHeader/RemoveElfHeader — they publish elfs[]/elfsize
    // from a dlopen'ing thread while other guest threads resolve TLS here. Acquire-load the
    // size, the array and the slot (paired with AddElfHeader's release stores): with the old
    // plain reads an ARM reader could see the bumped elfsize but a still-NULL slot, and since
    // GetTLSBase(NULL)==0 the guest got a wrong-but-mapped TLS address = silent thread-local
    // corruption (Poco X5 SEGV in this wrapper; suspected root of downstream GC/driver crashes).
    // A garbage descriptor from the guest (the older crash family) is still guarded too.
    // Capped, always-on (LOG_NONE).
    int p_readable = ((uintptr_t)p >= 0x10000) && !((uintptr_t)p & 0x7);
    elfheader_t* h = NULL;
    if (p_readable && t->i < (unsigned long)__atomic_load_n(&my_context->elfsize, __ATOMIC_ACQUIRE)) {
        elfheader_t** elfs = __atomic_load_n(&my_context->elfs, __ATOMIC_ACQUIRE);
        h = __atomic_load_n(&elfs[t->i], __ATOMIC_ACQUIRE);
    }
    if (!p_readable || !h) {
        static int rd_tlsbad_n = 0;
        if (rd_tlsbad_n < 16) {
            rd_tlsbad_n++;
            printf_log(LOG_NONE, "RIMDROID TLSBAD p=%p i=0x%lx o=0x%lx elfsize=%d rip=%p(%s)\n",
                p, p_readable ? t->i : 0, p_readable ? t->o : 0,
                my_context->elfsize, (void*)R_RIP, getAddrFunctionName(R_RIP));
            fflush(NULL);
        }
        // Out-of-range index or a NULL slot (mid-add race window / dlclose'd module): keep the
        // process alive on a zeroed scratch slot instead of handing out a wrong TLS address.
        // An unreadable p still falls through to the original crash (nothing to salvage; the
        // log above is the diagnostic).
        if (p_readable) {
            static __thread char rd_tls_scratch[512];
            memset(rd_tls_scratch, 0, sizeof(rd_tls_scratch));
            return rd_tls_scratch;
        }
    }
    tlsdatasize_t* ptr = emu->tlsdata;
    // Refresh a STALE per-thread block, not only a missing one: after a dlopen grew the TLS
    // area this thread's block predates the new module, so data + (its negative tlsbase) + o
    // would land OUTSIDE the block. refreshTLSData resizes when context->tlssize changed.
    if (!ptr || ptr->tlssize != my_context->tlssize) {
        refreshTLSData(emu);
        ptr = emu->tlsdata;
    }
    return ptr->data+GetTLSBase(h)+t->o;
}

EXPORT void* my___libc_stack_end;
void stSetup(box64context_t* context)
{
    my___libc_stack_end = context->stack + context->stacksz;
}

#ifdef STATICBUILD
#include <link.h>
extern void* __libc_enable_secure;
#ifndef PPC64LE
extern void* __stack_chk_guard;
#endif
//extern void* __pointer_chk_guard;
//extern void* _rtld_global;
//extern void* _rtld_global_ro;
#endif

// don't try to load the actual ld-linux (because name is variable), just use box64 itself, as it's linked to ld-linux
const char* ldlinuxName = "ld-linux.so.2";
#define LIBNAME ldlinux

#ifndef STATICBUILD
#define PRE_INIT\
    if(1)                                                           \
        lib->w.lib = dlopen(NULL, RTLD_LAZY | RTLD_GLOBAL);    \
    else
#endif

#define CUSTOM_INIT         \
    stSetup(box64);         \

// define all standard library functions
#include "wrappedlib_init.h"


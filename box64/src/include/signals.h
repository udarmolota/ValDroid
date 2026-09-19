#ifndef __SIGNALS_H__
#define __SIGNALS_H__
#include <stdint.h>

#include "x64_signals.h"
#include "box64context.h"

typedef void (*sighandler_t)(int);

#ifdef ANDROID
// `act`/`oldact` point at the GUEST's (x86_64 Linux glibc) sigaction struct,
// whose ABI is FIXED regardless of host: handler at offset 0 (8 bytes), then
// __sigset_t sa_mask (128 bytes) at offset 8, then sa_flags (4 bytes) at offset
// 136, then sa_restorer at offset 144.  The previous Android definition mirrored
// bionic's layout (sa_flags at offset 0), so box64 read handler and flags from
// swapped offsets — registering a garbage handler (e.g. 0x20000000 taken from
// sa_mask bytes) and storming on every guest SIGSEGV (Mono uses SIGSEGV for null
// checks).  Use an explicit 128-byte mask so offsets match x86_64 even on bionic
// (whose own sigset_t is only 8 bytes).
typedef struct x64_sigaction_s {
	union {
	  sighandler_t _sa_handler;
	  void (*_sa_sigaction)(int, siginfo_t *, void *);
	} _u;                          // offset 0   (8 bytes)
	unsigned long sa_mask[16];     // offset 8   (128 bytes = x86_64 __sigset_t)
	uint32_t sa_flags;             // offset 136
	void (*sa_restorer)(void);     // offset 144
} x64_sigaction_t;
#else
typedef struct x64_sigaction_s {
	union {
	  sighandler_t _sa_handler;
	  void (*_sa_sigaction)(int, siginfo_t *, void *);
	} _u;
	sigset_t sa_mask;
	uint32_t sa_flags;
	void (*sa_restorer)(void);
} x64_sigaction_t;
#endif

typedef struct x64_sigaction_restorer_s {
	union {
	  sighandler_t _sa_handler;
	  void (*_sa_sigaction)(int, siginfo_t *, void *);
	} _u;
	uint32_t sa_flags;
	void (*sa_restorer)(void);
	sigset_t sa_mask;
} x64_sigaction_restorer_t;

#ifdef BOX32
typedef struct __attribute__((packed)) i386_sigaction_s {
	union {
	  ptr_t _sa_handler;	// sighandler_t
	  ptr_t _sa_sigaction; //void (*_sa_sigaction)(int, siginfo_t *, void *);
	} _u;
	sigset_t sa_mask;
	uint32_t sa_flags;
	ptr_t sa_restorer; //void (*sa_restorer)(void);
} i386_sigaction_t;

typedef struct __attribute__((packed)) i386_sigaction_restorer_s {
	union {
	  ptr_t _sa_handler;	//sighandler_t
	  ptr_t _sa_sigaction; //void (*_sa_sigaction)(int, siginfo_t *, void *);
	} _u;
	uint32_t sa_flags;
	ptr_t sa_restorer; //void (*sa_restorer)(void);
	sigset_t sa_mask;
} i386_sigaction_restorer_t;

#endif

sighandler_t my_signal(x64emu_t* emu, int signum, sighandler_t handler);
sighandler_t my___sysv_signal(x64emu_t* emu, int signum, sighandler_t handler);
sighandler_t my_sysv_signal(x64emu_t* emu, int signum, sighandler_t handler);

int my_sigaction(x64emu_t* emu, int signum, const x64_sigaction_t *act, x64_sigaction_t *oldact);
int my___sigaction(x64emu_t* emu, int signum, const x64_sigaction_t *act, x64_sigaction_t *oldact);

int my_syscall_rt_sigaction(x64emu_t* emu, int signum, const x64_sigaction_restorer_t *act, x64_sigaction_restorer_t *oldact, int sigsetsize);

void enter_critical_section();
void leave_critical_section();
int defer_signal(x64emu_t* emu, int signum, siginfo_t* info);
void cancel_deferred_signal_processing(x64emu_t* emu);

void init_signal_helper(box64context_t* context);
void fini_signal_helper(void);

#endif //__SIGNALS_H__

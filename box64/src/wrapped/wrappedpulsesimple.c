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
#include "callback.h"
#include "box64context.h"
#include "librarian.h"
#include "myalign.h"

const char* pulsesimpleName = "libpulse-simple.so.0";
#define ALTNAME "libpulse-simple.so"

#define LIBNAME pulsesimple

#define PRE_INIT          \
    if(BOX64ENV(nopulse)) \
        return -1;

// RimDroid: the upstream wrapper pulls in the full native libpulse.so.0 (pa_simple is normally
// implemented on top of pa_context). On Android there is no native PulseAudio; instead we provide a
// standalone native libpulse-simple.so.0 shim (→ AAudio) that implements the pa_simple_* funcs
// directly, with no libpulse dependency. So drop NEEDED_LIBS — requiring libpulse.so.0 would make the
// wrap fail and the game stay silent.
// (was: #define NEEDED_LIBS "libpulse.so.0")

#include "wrappedlib_init.h"

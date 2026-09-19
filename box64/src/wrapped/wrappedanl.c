#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define _GNU_SOURCE         /* See feature_test_macros(7) */
#include <dlfcn.h>

#include "wrappedlibs.h"

#include "wrapper.h"
#include "bridge.h"
#include "librarian/library_private.h"
#include "x64emu.h"

const char* anlName = "libanl.so.1";

#define LIBNAME anl

// Android has no libanl.so.1. Unity 6's UnityPlayer lists it in DT_NEEDED but imports
// nothing from it, so resolve against the already-loaded process like wrappedlibrt.c does.
#define PRE_INIT\
    if(1)                                                   \
        lib->w.lib = dlopen(NULL, RTLD_LAZY | RTLD_GLOBAL); \
    else

#include "wrappedlib_init.h"

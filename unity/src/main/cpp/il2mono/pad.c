// Virtual gamepad state shared between the Java overlay (writer, JNI) and the managed
// ValDroid.VirtualPad (reader, internal call returning the pointer). See ValDroidBridge/VirtualPad.cs.
#include <jni.h>
#include <stdatomic.h>

#include "il2mono.h"

// Must match VirtualPad.NativeState.
typedef struct
{
    _Atomic uint32_t sequence;
    uint32_t buttons; // bit = UnityEngine.InputSystem.LowLevel.GamepadButton
    float left_x, left_y, right_x, right_y, left_trigger, right_trigger;
} ValDroidPadState;

static ValDroidPadState g_pad;

static void* pad_get_state_pointer(void)
{
    return &g_pad;
}

void il2mono_register_pad_icalls(void)
{
    p_mono_add_internal_call("ValDroid.VirtualPad::GetStatePointer", (const void*)pad_get_state_pointer);
}

// com.valdroid.nativeunity.ValDroidPad.nativeSetState(...) — called on the UI thread for every change.
// The reader only needs a consistent-enough snapshot per frame; the sequence bump comes last.
IL2MONO_API void JNICALL Java_com_valdroid_nativeunity_ValDroidPad_nativeSetState(
    JNIEnv* env, jclass cls, jint buttons, jfloat lx, jfloat ly, jfloat rx, jfloat ry, jfloat lt, jfloat rt)
{
    g_pad.buttons = (uint32_t)buttons;
    g_pad.left_x = lx;
    g_pad.left_y = ly;
    g_pad.right_x = rx;
    g_pad.right_y = ry;
    g_pad.left_trigger = lt;
    g_pad.right_trigger = rt;
    atomic_fetch_add_explicit(&g_pad.sequence, 1, memory_order_release);
}

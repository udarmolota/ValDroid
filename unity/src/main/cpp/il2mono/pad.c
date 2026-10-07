// Input shared between ValDroid's on-screen controls (Java, writer over JNI: com.valdroid.game.NativeInput)
// and the managed ValDroidBridge (reader, internal calls): a virtual gamepad, keyboard and mouse.
// The bridge writes back a little game state (frame counter, cursor lock/visibility) for the overlay.
// See tools/unity-native/bridge/*.cs.
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>

#include "il2mono.h"

// ------------------------------------------------------------------ gamepad

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

// ------------------------------------------------------------------ keyboard and mouse

#define KEY_WORDS 12 // bits for GLFW key codes 0..383

// Must match VirtualKeyboardMouse.NativeState.
typedef struct
{
    uint32_t sequence;          // bumped on every change
    uint32_t keys[KEY_WORDS];   // bit = GLFW key code
    uint32_t mouse_buttons;     // bit 0 left, 1 right, 2 middle
    float mouse_x, mouse_y;     // fraction of the screen, top-left origin
    float delta_x, delta_y;     // mouse look since the last take, fraction of the screen
    float scroll;               // wheel notches since the last take, + = up
} ValDroidKbmState;

static ValDroidKbmState g_kbm;
static pthread_mutex_t g_kbm_lock = PTHREAD_MUTEX_INITIALIZER;

// Keys and mouse buttons pressed since the last take. A tap (the touchpad's click holds the button for
// 50 ms) can start and end between two Input System updates when a frame takes longer than that, as on
// a weak phone at 27 fps: it would never reach the game. A latched press is taken as held for one update.
static uint32_t g_keys_pressed[KEY_WORDS];
static uint32_t g_mouse_pressed;

// ValDroid.VirtualKeyboardMouse::Take (returns a MonoBoolean, one byte) — copies the state when it changed since the last take and
// clears the accumulated mouse delta and wheel. Called once per Input System update.
static uint8_t kbm_take(ValDroidKbmState* out, uint32_t last_sequence)
{
    pthread_mutex_lock(&g_kbm_lock);
    bool changed = g_kbm.sequence != last_sequence;
    if (changed)
    {
        *out = g_kbm;
        bool released_early = (g_mouse_pressed & ~g_kbm.mouse_buttons) != 0;
        out->mouse_buttons |= g_mouse_pressed;
        g_mouse_pressed = 0;
        for (int w = 0; w < KEY_WORDS; w++)
        {
            released_early |= (g_keys_pressed[w] & ~g_kbm.keys[w]) != 0;
            out->keys[w] |= g_keys_pressed[w];
            g_keys_pressed[w] = 0;
        }
        // The release is still to be sent: the next take must see a change.
        if (released_early)
            g_kbm.sequence++;
        g_kbm.delta_x = g_kbm.delta_y = 0;
        g_kbm.scroll = 0;
    }
    pthread_mutex_unlock(&g_kbm_lock);
    return changed;
}

// ------------------------------------------------------------------ game state

// Must match Bridge.GameState. Written by the bridge every frame, read by the overlay.
typedef struct
{
    _Atomic uint32_t frames;        // rendered frames
    _Atomic int32_t cursor_locked;  // Cursor.lockState != None; -1 until the first frame
    _Atomic int32_t cursor_visible; // Cursor.visible
} ValDroidGameState;

static ValDroidGameState g_game = { 0, -1, 1 };

static void* game_get_state_pointer(void)
{
    return &g_game;
}

void il2mono_register_input_icalls(void)
{
    p_mono_add_internal_call("ValDroid.VirtualPad::GetStatePointer", (const void*)pad_get_state_pointer);
    p_mono_add_internal_call("ValDroid.VirtualKeyboardMouse::Take", (const void*)kbm_take);
    p_mono_add_internal_call("ValDroid.Bridge::GetGameStatePointer", (const void*)game_get_state_pointer);
}

// ------------------------------------------------------------------ JNI (com.valdroid.game.NativeInput)

// Called on the UI thread for every change. The reader only needs a consistent-enough snapshot per
// frame; the sequence bump comes last.
IL2MONO_API void JNICALL Java_com_valdroid_game_NativeInput_nativeSetPad(
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

IL2MONO_API void JNICALL Java_com_valdroid_game_NativeInput_nativeKey(JNIEnv* env, jclass cls, jint key, jboolean down)
{
    if (key < 0 || key >= KEY_WORDS * 32)
        return;
    uint32_t bit = 1u << (key & 31);
    pthread_mutex_lock(&g_kbm_lock);
    if (down)
    {
        g_kbm.keys[key >> 5] |= bit;
        g_keys_pressed[key >> 5] |= bit;
    }
    else
        g_kbm.keys[key >> 5] &= ~bit;
    g_kbm.sequence++;
    pthread_mutex_unlock(&g_kbm_lock);
}

IL2MONO_API void JNICALL Java_com_valdroid_game_NativeInput_nativeMouseButton(
    JNIEnv* env, jclass cls, jint button, jboolean down)
{
    if (button < 0 || button > 31)
        return;
    pthread_mutex_lock(&g_kbm_lock);
    if (down)
    {
        g_kbm.mouse_buttons |= 1u << button;
        g_mouse_pressed |= 1u << button;
    }
    else
        g_kbm.mouse_buttons &= ~(1u << button);
    g_kbm.sequence++;
    pthread_mutex_unlock(&g_kbm_lock);
}

IL2MONO_API void JNICALL Java_com_valdroid_game_NativeInput_nativeMousePosition(JNIEnv* env, jclass cls, jfloat x, jfloat y)
{
    pthread_mutex_lock(&g_kbm_lock);
    g_kbm.mouse_x = x;
    g_kbm.mouse_y = y;
    g_kbm.sequence++;
    pthread_mutex_unlock(&g_kbm_lock);
}

IL2MONO_API void JNICALL Java_com_valdroid_game_NativeInput_nativeMouseDelta(JNIEnv* env, jclass cls, jfloat dx, jfloat dy)
{
    pthread_mutex_lock(&g_kbm_lock);
    g_kbm.delta_x += dx;
    g_kbm.delta_y += dy;
    g_kbm.sequence++;
    pthread_mutex_unlock(&g_kbm_lock);
}

IL2MONO_API void JNICALL Java_com_valdroid_game_NativeInput_nativeMouseScroll(JNIEnv* env, jclass cls, jfloat notches)
{
    pthread_mutex_lock(&g_kbm_lock);
    g_kbm.scroll += notches;
    g_kbm.sequence++;
    pthread_mutex_unlock(&g_kbm_lock);
}

IL2MONO_API jlong JNICALL Java_com_valdroid_game_NativeInput_nativeGetFrameCount(JNIEnv* env, jclass cls)
{
    return (jlong)atomic_load_explicit(&g_game.frames, memory_order_relaxed);
}

// -1 = the game has not reported yet; else bit 0 = cursor locked, bit 1 = cursor visible.
IL2MONO_API jint JNICALL Java_com_valdroid_game_NativeInput_nativeGetCursorState(JNIEnv* env, jclass cls)
{
    int32_t locked = atomic_load_explicit(&g_game.cursor_locked, memory_order_relaxed);
    if (locked < 0)
        return -1;
    int32_t visible = atomic_load_explicit(&g_game.cursor_visible, memory_order_relaxed);
    return (locked ? 1 : 0) | (visible ? 2 : 0);
}

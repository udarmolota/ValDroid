// Input shared between ValDroid's on-screen controls (Java, writer over JNI: com.valdroid.game.NativeInput)
// and the managed ValDroidBridge (reader, internal calls): a virtual gamepad, keyboard and mouse.
// The bridge writes back a little game state (frame counter, cursor lock/visibility) for the overlay.
// See tools/unity-native/bridge/*.cs.
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

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

// g_kbm.keys / mouse_buttons are what the game has been given so far. Presses and releases do not go
// there directly: the game samples the state once per Input System update, and whatever happened
// between two updates would collapse (a tap holds the button for 50 ms, a frame on a weak phone takes
// longer: the press and the release, or a release and the next press, would fall into one update and
// the game would see one of them or neither). They queue as edges instead, and a take applies them in
// order, changing any one key or button at most once per update: the rest wait for the next take.
static ValDroidKbmState g_kbm;
static pthread_mutex_t g_kbm_lock = PTHREAD_MUTEX_INITIALIZER;

typedef struct
{
    uint16_t code;   // GLFW key code, or the mouse button
    uint8_t mouse;   // 1 = mouse button, 0 = key
    uint8_t down;
} KbmEdge;

#define EDGE_CAPACITY 256
static KbmEdge g_edges[EDGE_CAPACITY];
static unsigned g_edge_head, g_edge_count;

// VALDROID_DIAG=1: every mouse edge and every take that holds edges back goes to the log.
static int g_kbm_diag = -1;

static bool kbm_diag(void)
{
    if (g_kbm_diag < 0)
    {
        const char* v = getenv("VALDROID_DIAG");
        g_kbm_diag = v && v[0] == '1';
    }
    return g_kbm_diag;
}

static long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

// Under the lock.
static void kbm_push_edge(uint16_t code, bool mouse, bool down)
{
    if (g_edge_count == EDGE_CAPACITY)
    {
        LOGW("kbm: edge queue full, %s %u %s dropped", mouse ? "mouse" : "key", code, down ? "down" : "up");
        return;
    }
    KbmEdge* e = &g_edges[(g_edge_head + g_edge_count) % EDGE_CAPACITY];
    e->code = code;
    e->mouse = mouse;
    e->down = down;
    g_edge_count++;
    g_kbm.sequence++;
    if (kbm_diag())
        LOGI("kbm: %s %u %s queued at %ld ms, %u edges waiting", mouse ? "mouse" : "key", code, down ? "down" : "up",
             now_ms(), g_edge_count);
}

// ValDroid.VirtualKeyboardMouse::Take (returns a MonoBoolean, one byte) — copies the state when it changed since the last take and
// clears the accumulated mouse delta and wheel. Called once per Input System update.
static uint8_t kbm_take(ValDroidKbmState* out, uint32_t last_sequence)
{
    pthread_mutex_lock(&g_kbm_lock);
    bool changed = g_kbm.sequence != last_sequence;
    if (changed)
    {
        // Apply queued edges in order until one would flip a bit this take already flipped.
        uint32_t flipped_keys[KEY_WORDS];
        uint32_t flipped_mouse = 0;
        memset(flipped_keys, 0, sizeof(flipped_keys));
        unsigned applied = 0;
        while (g_edge_count > 0)
        {
            KbmEdge* e = &g_edges[g_edge_head];
            uint32_t bit = 1u << (e->code & 31);
            uint32_t* state = e->mouse ? &g_kbm.mouse_buttons : &g_kbm.keys[e->code >> 5];
            uint32_t* flipped = e->mouse ? &flipped_mouse : &flipped_keys[e->code >> 5];
            bool flips = ((*state & bit) != 0) != e->down;
            if (flips && (*flipped & bit))
                break;
            if (e->down)
                *state |= bit;
            else
                *state &= ~bit;
            if (flips)
                *flipped |= bit;
            g_edge_head = (g_edge_head + 1) % EDGE_CAPACITY;
            g_edge_count--;
            applied++;
        }
        *out = g_kbm;
        // Edges held back: the next take must see a change.
        if (g_edge_count > 0)
        {
            g_kbm.sequence++;
            if (kbm_diag())
                LOGI("kbm: take applied %u edges, %u held back for the next update", applied, g_edge_count);
        }
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
    pthread_mutex_lock(&g_kbm_lock);
    kbm_push_edge((uint16_t)key, false, down);
    pthread_mutex_unlock(&g_kbm_lock);
}

IL2MONO_API void JNICALL Java_com_valdroid_game_NativeInput_nativeMouseButton(
    JNIEnv* env, jclass cls, jint button, jboolean down)
{
    if (button < 0 || button > 31)
        return;
    pthread_mutex_lock(&g_kbm_lock);
    kbm_push_edge((uint16_t)button, true, down);
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

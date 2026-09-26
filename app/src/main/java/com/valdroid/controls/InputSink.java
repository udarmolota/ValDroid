package com.valdroid.controls;

import android.util.Log;

import com.valdroid.input.VirtualGamepad;
import com.valdroid.xserver.Pointer;
import com.valdroid.xserver.XKeycode;
import com.valdroid.xserver.XServer;
import com.valdroid.xserver.XServerRunner;

/**
 * Where the on-screen controls deliver their input. Same static API as Zomdroid's
 * InputNativeInterface, so the ported elements call it unchanged, but implemented in Java for
 * ValDroid's two real sinks:
 * <ul>
 *   <li>keyboard and mouse go to the in-process X server — Valheim's SDL only reads core X events
 *       (the SDL event ring behind GameActivity.native* is dead for it);</li>
 *   <li>the gamepad goes to {@link VirtualGamepad}, the evdev Xbox 360 pad the guest's SDL opens.</li>
 * </ul>
 * Everything is a no-op while the X server or the native pad is not up (e.g. in the editor).
 */
public final class InputSink {
    private static final String TAG = "ValDroid/InputSink";

    private InputSink() {}

    // ------------------------------------------------------------------ geometry

    // Zomdroid's elements send the cursor as "view px * render scale", because there the view WAS
    // the game surface. ValDroid's overlay covers the whole screen while the game may sit in a
    // letterboxed rect, so the rect's offset is subtracted here, in the one place that knows it.
    private static float renderScale = 1f;
    private static int gameLeft, gameTop, gameW, gameH;   // screen px; gameW <= 0 = full view

    /** Called by InputControlsView whenever the render scale or the game rect changes. */
    static void setGeometry(float scale, int left, int top, int w, int h) {
        renderScale = scale > 0f ? scale : 1f;
        gameLeft = left; gameTop = top; gameW = w; gameH = h;
    }

    /** View px * renderScale (Zomdroid convention) -> game-buffer px, clamped to the buffer. */
    static int toGameX(double scaledViewX) {
        if (gameW <= 0) return (int) Math.round(scaledViewX);
        int max = Math.max(1, Math.round(gameW * renderScale)) - 1;
        int v = (int) Math.round(scaledViewX - gameLeft * renderScale);
        return v < 0 ? 0 : (v > max ? max : v);
    }

    static int toGameY(double scaledViewY) {
        if (gameH <= 0) return (int) Math.round(scaledViewY);
        int max = Math.max(1, Math.round(gameH * renderScale)) - 1;
        int v = (int) Math.round(scaledViewY - gameTop * renderScale);
        return v < 0 ? 0 : (v > max ? max : v);
    }

    // ------------------------------------------------------------------ keyboard / mouse

    public static void sendKeyboard(int key, boolean isPressed) {
        XServer xs = XServerRunner.getXServer();
        if (xs == null) return;
        XKeycode xk = xKeycodeForGlfw(key);
        if (xk == null) {
            Log.w(TAG, "no X keycode for GLFW key " + key);
            return;
        }
        // Keysym 0: the X keymap (Keyboard.createKeyboard) already gives every keycode its keysym,
        // so SDL makes the right key and text from the keycode alone, as with a hardware keyboard.
        if (isPressed) xs.injectKeyPress(xk, 0);
        else xs.injectKeyRelease(xk);
    }

    public static void sendCursorPos(double x, double y) {
        XServer xs = XServerRunner.getXServer();
        if (xs == null) return;
        xs.injectPointerMove(toGameX(x), toGameY(y));
    }

    /**
     * GLFW button 0/1/2 = left/right/middle. Pressed where the X pointer already is: every cursor
     * move is injected as it happens, and re-sending the position here would drag a pointer the
     * game has warped (mouse look) back to the overlay cursor. GLFW buttons 4-8 have no X button.
     */
    public static void sendMouseButton(int button, boolean isPressed) {
        XServer xs = XServerRunner.getXServer();
        if (xs == null) return;
        Pointer.Button b;
        switch (button) {
            case 0: b = Pointer.Button.BUTTON_LEFT; break;
            case 1: b = Pointer.Button.BUTTON_RIGHT; break;
            case 2: b = Pointer.Button.BUTTON_MIDDLE; break;
            default: return;
        }
        if (isPressed) xs.injectPointerButtonPress(b);
        else xs.injectPointerButtonRelease(b);
    }

    /** X has no wheel axis: each notch is a press+release of button 4 (up) or 5 (down). */
    public static void sendMouseScroll(double xoffset, double yoffset) {
        XServer xs = XServerRunner.getXServer();
        if (xs == null || yoffset == 0) return;
        Pointer.Button b = yoffset > 0 ? Pointer.Button.BUTTON_SCROLL_UP : Pointer.Button.BUTTON_SCROLL_DOWN;
        int n = (int) Math.min(5, Math.max(1, Math.round(Math.abs(yoffset))));
        for (int i = 0; i < n; i++) {
            xs.injectPointerButtonPress(b);
            xs.injectPointerButtonRelease(b);
        }
    }

    /**
     * Deliberately a no-op. Zomdroid's GLFW path needed a separate char event for text fields;
     * here the X key press already carries a keysym and SDL turns it into text itself, so
     * sending the character again would type it twice.
     */
    public static void sendChar(int codepoint) {}

    // ------------------------------------------------------------------ gamepad

    /** GLFW axis 0..3 = LX, LY, RX, RY in -1..1 (down = +y, as evdev); 4/5 = LT/RT in 0..1. */
    public static void sendJoystickAxis(int axis, float state) {
        try {
            switch (axis) {
                case 0: VirtualGamepad.stick(VirtualGamepad.ABS_X, state); break;
                case 1: VirtualGamepad.stick(VirtualGamepad.ABS_Y, state); break;
                case 2: VirtualGamepad.stick(VirtualGamepad.ABS_RX, state); break;
                case 3: VirtualGamepad.stick(VirtualGamepad.ABS_RY, state); break;
                case 4: VirtualGamepad.trigger(VirtualGamepad.ABS_Z, state); break;
                case 5: VirtualGamepad.trigger(VirtualGamepad.ABS_RZ, state); break;
                default: return;
            }
            VirtualGamepad.sync();
        } catch (UnsatisfiedLinkError ignored) {}
    }

    /** GLFW hat mask: 1 up, 2 right, 4 down, 8 left -> HAT0X/HAT0Y. */
    public static void sendJoystickDpad(int dpad, char state) {
        try {
            VirtualGamepad.axis(VirtualGamepad.ABS_HAT0X, ((state & 2) != 0 ? 1 : 0) - ((state & 8) != 0 ? 1 : 0));
            VirtualGamepad.axis(VirtualGamepad.ABS_HAT0Y, ((state & 4) != 0 ? 1 : 0) - ((state & 1) != 0 ? 1 : 0));
            VirtualGamepad.sync();
        } catch (UnsatisfiedLinkError ignored) {}
    }

    /** GLFW gamepad button order (A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3) -> evdev codes. */
    public static void sendJoystickButton(int button, boolean isPressed) {
        int code;
        switch (button) {
            case 0: code = VirtualGamepad.BTN_A; break;
            case 1: code = VirtualGamepad.BTN_B; break;
            case 2: code = VirtualGamepad.BTN_X; break;
            case 3: code = VirtualGamepad.BTN_Y; break;
            case 4: code = VirtualGamepad.BTN_TL; break;
            case 5: code = VirtualGamepad.BTN_TR; break;
            case 6: code = VirtualGamepad.BTN_SELECT; break;
            case 7: code = VirtualGamepad.BTN_START; break;
            case 8: code = VirtualGamepad.BTN_MODE; break;
            case 9: code = VirtualGamepad.BTN_THUMBL; break;
            case 10: code = VirtualGamepad.BTN_THUMBR; break;
            default: return;
        }
        try {
            VirtualGamepad.button(code, isPressed);
            VirtualGamepad.sync();
        } catch (UnsatisfiedLinkError ignored) {}
    }

    /** The virtual pad exists from launch; nothing to announce (kept for API parity). */
    public static void sendJoystickConnected() {}

    // ------------------------------------------------------------------ key table

    /** GLFW key code -> X keycode of ValDroid's keymap. The one table every on-screen key uses. */
    static XKeycode xKeycodeForGlfw(int key) {
        if (key >= 65 && key <= 90) return LETTERS[key - 65];     // A..Z
        if (key >= 48 && key <= 57) return DIGITS[key - 48];      // 0..9
        if (key >= 290 && key <= 301) return FKEYS[key - 290];    // F1..F12
        if (key >= 320 && key <= 329) return KEYPAD[key - 320];   // KP_0..KP_9
        switch (key) {
            case 32:  return XKeycode.KEY_SPACE;
            case 39:  return XKeycode.KEY_APOSTROPHE;
            case 44:  return XKeycode.KEY_COMMA;
            case 45:  return XKeycode.KEY_MINUS;
            case 46:  return XKeycode.KEY_PERIOD;
            case 47:  return XKeycode.KEY_SLASH;
            case 59:  return XKeycode.KEY_SEMICOLON;
            case 61:  return XKeycode.KEY_EQUAL;
            case 91:  return XKeycode.KEY_BRACKET_LEFT;
            case 92:  return XKeycode.KEY_BACKSLASH;
            case 93:  return XKeycode.KEY_BRACKET_RIGHT;
            case 96:  return XKeycode.KEY_GRAVE;
            case 256: return XKeycode.KEY_ESC;
            case 257: return XKeycode.KEY_ENTER;
            case 258: return XKeycode.KEY_TAB;
            case 259: return XKeycode.KEY_BKSP;
            case 260: return XKeycode.KEY_INSERT;
            case 261: return XKeycode.KEY_DEL;
            case 262: return XKeycode.KEY_RIGHT;
            case 263: return XKeycode.KEY_LEFT;
            case 264: return XKeycode.KEY_DOWN;
            case 265: return XKeycode.KEY_UP;
            case 266: return XKeycode.KEY_PRIOR;          // Page Up
            case 267: return XKeycode.KEY_NEXT;           // Page Down
            case 268: return XKeycode.KEY_HOME;
            case 269: return XKeycode.KEY_END;
            case 123: return XKeycode.KEY_END;            // GLFWBinding.KEYCODE_MOVE_END
            case 280: return XKeycode.KEY_CAPS_LOCK;
            case 281: return XKeycode.KEY_SCROLL_LOCK;
            case 282: return XKeycode.KEY_NUM_LOCK;
            case 283: return XKeycode.KEY_PRTSCN;
            case 330: return XKeycode.KEY_KP_DEL;         // KP_DECIMAL
            case 331: return XKeycode.KEY_KP_DIVIDE;
            case 332: return XKeycode.KEY_KP_MULTIPLY;
            case 333: return XKeycode.KEY_KP_SUBTRACT;
            case 334: return XKeycode.KEY_KP_ADD;
            case 335: return XKeycode.KEY_KP_ENTER;
            case 336: return XKeycode.KEY_EQUAL;          // KP_EQUAL: no keypad '=' in the keymap
            case 340: return XKeycode.KEY_SHIFT_L;
            case 341: return XKeycode.KEY_CTRL_L;
            case 342: return XKeycode.KEY_ALT_L;
            case 344: return XKeycode.KEY_SHIFT_R;
            case 345: return XKeycode.KEY_CTRL_R;
            case 346: return XKeycode.KEY_ALT_R;
            // Pause, Super/Win and the WORLD keys have no keycode in the X keymap.
            default:  return null;
        }
    }

    private static final XKeycode[] LETTERS = {
            XKeycode.KEY_A, XKeycode.KEY_B, XKeycode.KEY_C, XKeycode.KEY_D, XKeycode.KEY_E,
            XKeycode.KEY_F, XKeycode.KEY_G, XKeycode.KEY_H, XKeycode.KEY_I, XKeycode.KEY_J,
            XKeycode.KEY_K, XKeycode.KEY_L, XKeycode.KEY_M, XKeycode.KEY_N, XKeycode.KEY_O,
            XKeycode.KEY_P, XKeycode.KEY_Q, XKeycode.KEY_R, XKeycode.KEY_S, XKeycode.KEY_T,
            XKeycode.KEY_U, XKeycode.KEY_V, XKeycode.KEY_W, XKeycode.KEY_X, XKeycode.KEY_Y,
            XKeycode.KEY_Z };
    private static final XKeycode[] DIGITS = {
            XKeycode.KEY_0, XKeycode.KEY_1, XKeycode.KEY_2, XKeycode.KEY_3, XKeycode.KEY_4,
            XKeycode.KEY_5, XKeycode.KEY_6, XKeycode.KEY_7, XKeycode.KEY_8, XKeycode.KEY_9 };
    private static final XKeycode[] FKEYS = {
            XKeycode.KEY_F1, XKeycode.KEY_F2, XKeycode.KEY_F3, XKeycode.KEY_F4, XKeycode.KEY_F5,
            XKeycode.KEY_F6, XKeycode.KEY_F7, XKeycode.KEY_F8, XKeycode.KEY_F9, XKeycode.KEY_F10,
            XKeycode.KEY_F11, XKeycode.KEY_F12 };
    private static final XKeycode[] KEYPAD = {
            XKeycode.KEY_KP_0, XKeycode.KEY_KP_1, XKeycode.KEY_KP_2, XKeycode.KEY_KP_3,
            XKeycode.KEY_KP_4, XKeycode.KEY_KP_5, XKeycode.KEY_KP_6, XKeycode.KEY_KP_7,
            XKeycode.KEY_KP_8, XKeycode.KEY_KP_9 };
}

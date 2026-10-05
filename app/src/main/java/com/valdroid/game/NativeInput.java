package com.valdroid.game;

import com.valdroid.controls.InputSink;

/**
 * The on-screen controls' input for the native engine (":unity" process only). Everything goes over
 * JNI to il2mono (libil2cpp.so), where the managed ValDroidBridge picks it up once per Input System
 * update and feeds a virtual gamepad, keyboard and mouse; Valheim reads all three through the Input
 * System. The bridge reports back the rendered frame count and the game's cursor state.
 */
public final class NativeInput implements InputSink.Backend {
    static {
        System.loadLibrary("il2cpp"); // already loaded by the player; this binds the JNI methods
    }

    private static native void nativeSetPad(int buttons, float lx, float ly, float rx, float ry, float lt, float rt);
    private static native void nativeKey(int glfwKey, boolean down);
    private static native void nativeMouseButton(int button, boolean down);
    private static native void nativeMousePosition(float x, float y);
    private static native void nativeMouseDelta(float dx, float dy);
    private static native void nativeMouseScroll(float notches);
    private static native long nativeGetFrameCount();
    private static native int nativeGetCursorState();

    // UnityEngine.InputSystem.LowLevel.GamepadButton bit numbers.
    private static final int DPAD_UP = 0, DPAD_DOWN = 1, DPAD_LEFT = 2, DPAD_RIGHT = 3;
    private static final int NORTH = 4, EAST = 5, SOUTH = 6, WEST = 7;
    private static final int L3 = 8, R3 = 9, LB = 10, RB = 11, START = 12, SELECT = 13;
    private static final int DPAD_BITS = (1 << DPAD_UP) | (1 << DPAD_DOWN) | (1 << DPAD_LEFT) | (1 << DPAD_RIGHT);
    /** GLFW gamepad button order (A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3) -> bit; -1 = none. */
    private static final int[] GLFW_BUTTON_BITS = { SOUTH, EAST, WEST, NORTH, LB, RB, SELECT, START, -1, L3, R3 };

    private int padButtons;
    private float leftX, leftY, rightX, rightY, leftTrigger, rightTrigger;

    /** Rendered frames so far (the FPS counter's source). */
    public static long frameCount() {
        return nativeGetFrameCount();
    }

    @Override public void key(int glfwKey, boolean pressed) { nativeKey(glfwKey, pressed); }

    @Override public void mouseButton(int glfwButton, boolean pressed) { nativeMouseButton(glfwButton, pressed); }

    @Override public void mousePosition(float x, float y) { nativeMousePosition(x, y); }

    @Override public void mouseDelta(float dx, float dy) { nativeMouseDelta(dx, dy); }

    @Override public void mouseScroll(float notches) { nativeMouseScroll(notches); }

    // The Input System's stick y points up; GLFW's (and the controls') points down.
    @Override public synchronized void gamepadAxis(int axis, float value) {
        switch (axis) {
            case 0: leftX = value; break;
            case 1: leftY = -value; break;
            case 2: rightX = value; break;
            case 3: rightY = -value; break;
            case 4: leftTrigger = value; break;
            case 5: rightTrigger = value; break;
            default: return;
        }
        pushPad();
    }

    @Override public synchronized void gamepadDpad(int mask) {
        int bits = 0;
        if ((mask & 1) != 0) bits |= 1 << DPAD_UP;
        if ((mask & 2) != 0) bits |= 1 << DPAD_RIGHT;
        if ((mask & 4) != 0) bits |= 1 << DPAD_DOWN;
        if ((mask & 8) != 0) bits |= 1 << DPAD_LEFT;
        padButtons = (padButtons & ~DPAD_BITS) | bits;
        pushPad();
    }

    @Override public synchronized void gamepadButton(int button, boolean pressed) {
        if (button < 0 || button >= GLFW_BUTTON_BITS.length || GLFW_BUTTON_BITS[button] < 0) return;
        int bit = 1 << GLFW_BUTTON_BITS[button];
        padButtons = pressed ? padButtons | bit : padButtons & ~bit;
        pushPad();
    }

    private void pushPad() {
        nativeSetPad(padButtons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger);
    }

    @Override public int mouseLocked() {
        int s = nativeGetCursorState();
        return s < 0 ? -1 : (s & 1);
    }

    @Override public int cursorHidden() {
        int s = nativeGetCursorState();
        return s < 0 ? -1 : ((s & 2) != 0 ? 0 : 1);
    }
}

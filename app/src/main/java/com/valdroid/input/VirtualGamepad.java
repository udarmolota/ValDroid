package com.valdroid.input;

/**
 * The virtual evdev gamepad the guest's SDL sees (native side: valdroid_pad.c). It presents itself
 * as an Xbox 360 pad, so Valheim switches to its own controller UI and bindings. Feed it evdev
 * codes: buttons via {@link #button}, axes via {@link #axis}, then {@link #sync} to deliver the
 * batch as one report.
 */
import com.valdroid.controls.InputSink;

public final class VirtualGamepad {
    public static final String DEVICE_PATH = "/dev/input/event-valdroid";

    // linux/input-event-codes.h
    public static final int BTN_A = 0x130, BTN_B = 0x131, BTN_X = 0x133, BTN_Y = 0x134;
    public static final int BTN_TL = 0x136, BTN_TR = 0x137;
    public static final int BTN_SELECT = 0x13a, BTN_START = 0x13b, BTN_MODE = 0x13c;
    public static final int BTN_THUMBL = 0x13d, BTN_THUMBR = 0x13e;
    public static final int ABS_X = 0x00, ABS_Y = 0x01, ABS_Z = 0x02;      // left stick, left trigger
    public static final int ABS_RX = 0x03, ABS_RY = 0x04, ABS_RZ = 0x05;   // right stick, right trigger
    public static final int ABS_HAT0X = 0x10, ABS_HAT0Y = 0x11;            // d-pad

    public static final int STICK_MAX = 32767;
    public static final int TRIGGER_MAX = 255;

    private VirtualGamepad() {}

    // In the native engine (InputSink has a Backend) there is no evdev pad: the physical controller goes
    // to the game's virtual Input System gamepad instead, the one the on-screen gamepad drives.
    private static int hatX, hatY;

    public static void button(int code, boolean down) {
        if (InputSink.hasBackend()) {
            int b = glfwButton(code);
            if (b >= 0) InputSink.sendJoystickButton(b, down);
            return;
        }
        nativeButton(code, down);
    }

    /** Raw evdev value in the axis's own range (see the constants above). */
    public static void axis(int code, int value) {
        if (InputSink.hasBackend()) {
            if (code == ABS_HAT0X) hatX = value;
            else if (code == ABS_HAT0Y) hatY = value;
            else return;
            int mask = (hatY < 0 ? 1 : 0) | (hatX > 0 ? 2 : 0) | (hatY > 0 ? 4 : 0) | (hatX < 0 ? 8 : 0);
            InputSink.sendJoystickDpad(0, (char) mask);
            return;
        }
        nativeAxis(code, value);
    }

    /** Stick axis from a -1..1 float. */
    public static void stick(int code, float v) {
        if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
        if (InputSink.hasBackend()) {
            int axis = code == ABS_X ? 0 : code == ABS_Y ? 1 : code == ABS_RX ? 2 : code == ABS_RY ? 3 : -1;
            if (axis >= 0) InputSink.sendJoystickAxis(axis, v);
            return;
        }
        nativeAxis(code, Math.round(v * STICK_MAX));
    }

    /** Trigger axis from a 0..1 float. */
    public static void trigger(int code, float v) {
        if (v > 1f) v = 1f; else if (v < 0f) v = 0f;
        if (InputSink.hasBackend()) {
            if (code == ABS_Z) InputSink.sendJoystickAxis(4, v);
            else if (code == ABS_RZ) InputSink.sendJoystickAxis(5, v);
            return;
        }
        nativeAxis(code, Math.round(v * TRIGGER_MAX));
    }

    /** Flush pending changes as one SYN_REPORT-terminated packet. */
    public static void sync() {
        if (!InputSink.hasBackend()) nativeSync();
    }

    /** evdev button -> GLFW gamepad button (A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3); -1 = none. */
    private static int glfwButton(int code) {
        switch (code) {
            case BTN_A: return 0;
            case BTN_B: return 1;
            case BTN_X: return 2;
            case BTN_Y: return 3;
            case BTN_TL: return 4;
            case BTN_TR: return 5;
            case BTN_SELECT: return 6;
            case BTN_START: return 7;
            case BTN_MODE: return 8;
            case BTN_THUMBL: return 9;
            case BTN_THUMBR: return 10;
            default: return -1;
        }
    }

    private static native void nativeButton(int code, boolean down);
    private static native void nativeAxis(int code, int value);
    private static native void nativeSync();
}

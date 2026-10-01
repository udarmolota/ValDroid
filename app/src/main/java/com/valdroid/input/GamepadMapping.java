// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Physical→logical gamepad button mapping (ported from Zomdroid's GamepadManager idea, simplified).
 *
 * RimWorld is mouse+keyboard, so {@link GamepadHandler} maps LOGICAL buttons (A/B/X/Y/…) to fixed
 * MNK actions (A=left click, etc.). This class sits in front of that: it maps each PHYSICAL Android
 * keycode to a logical button index, so a controller with swapped/“inverted” buttons can be fixed
 * without touching the action layer — the user just records which physical button should be each
 * logical one (see {@code GamepadMapperActivity}).
 *
 * Default is identity (logical A = KEYCODE_BUTTON_A, …). A custom mapping is stored as a CSV of
 * Android keycodes (index = logical button) in SharedPreferences.
 *
 * LT/RT are stored separately (one int each, Zomdroid's sentinel encoding TYPE << 24 | VALUE):
 * either an analog AXIS (VALUE = the Android MotionEvent axis the user moved, e.g. AXIS_Z) or a
 * BUTTON (VALUE = Android keycode). 0 means "auto": GamepadHandler picks the trigger axes from the
 * device's motion ranges. They live under their own keys rather than as extra CSV columns so a
 * mapping saved by an older build (exactly COUNT columns) still loads, and so an older build reading
 * a newer save does not reject the whole CSV and lose the button remap.
 */
public final class GamepadMapping {

    // Logical button indices (order matches the mapper wizard; GUIDE kept for index parity, unused).
    public static final int L_A = 0, L_B = 1, L_X = 2, L_Y = 3, L_LB = 4, L_RB = 5,
            L_SELECT = 6, L_START = 7, L_GUIDE = 8, L_L3 = 9, L_R3 = 10;
    public static final int COUNT = 11;

    /** Steps shown in the wizard, in order (GUIDE skipped — no useful action bound to it). */
    public static final int[] WIZARD_ORDER = { L_A, L_B, L_X, L_Y, L_LB, L_RB, L_SELECT, L_START, L_L3, L_R3 };

    // Trigger binding encoding (Zomdroid's sentinel scheme). TRIGGER_AUTO = nothing recorded.
    public static final int TRIGGER_AUTO = 0;
    public static final int TYPE_AXIS = 0x01;     // VALUE = Android MotionEvent.AXIS_* constant
    public static final int TYPE_BUTTON = 0x02;   // VALUE = Android KeyEvent.KEYCODE_* constant
    private static final int TYPE_SHIFT = 24;
    private static final int VALUE_MASK = 0x00FFFFFF;

    private static final int[] DEFAULT = {
            KeyEvent.KEYCODE_BUTTON_A,      // A
            KeyEvent.KEYCODE_BUTTON_B,      // B
            KeyEvent.KEYCODE_BUTTON_X,      // X
            KeyEvent.KEYCODE_BUTTON_Y,      // Y
            KeyEvent.KEYCODE_BUTTON_L1,     // LB
            KeyEvent.KEYCODE_BUTTON_R1,     // RB
            KeyEvent.KEYCODE_BUTTON_SELECT, // SELECT
            KeyEvent.KEYCODE_BUTTON_START,  // START
            KeyEvent.KEYCODE_BUTTON_MODE,   // GUIDE
            KeyEvent.KEYCODE_BUTTON_THUMBL, // L3
            KeyEvent.KEYCODE_BUTTON_THUMBR  // R3
    };

    private static final String PREFS = "rimdroid_gamepad";
    private static final String KEY_MAPPING = "button_mapping";
    private static final String KEY_LT = "trigger_lt";
    private static final String KEY_RT = "trigger_rt";

    private static int[] current = DEFAULT.clone();
    private static int lt = TRIGGER_AUTO, rt = TRIGGER_AUTO;

    private GamepadMapping() {}

    /** Load the saved mapping into the in-memory state. Call once before using the gamepad. */
    public static void load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String csv = p.getString(KEY_MAPPING, null);
        current = parseOrDefault(csv);
        lt = sanitizeTrigger(p.getInt(KEY_LT, TRIGGER_AUTO));
        rt = sanitizeTrigger(p.getInt(KEY_RT, TRIGGER_AUTO));
    }

    private static int[] parseOrDefault(String csv) {
        if (csv == null || csv.isEmpty()) return DEFAULT.clone();
        String[] parts = csv.split(",");
        if (parts.length != COUNT) return DEFAULT.clone();
        int[] m = new int[COUNT];
        try {
            for (int i = 0; i < COUNT; i++) m[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return DEFAULT.clone();
        }
        return m;
    }

    /** Current mapping (logical index → physical Android keycode). Never null, length {@link #COUNT}. */
    public static int[] get() {
        return current;
    }

    public static int[] getDefault() {
        return DEFAULT.clone();
    }

    /** Persist + apply a custom mapping (logical index → physical keycode). */
    public static void save(Context ctx, int[] mapping) {
        if (mapping == null || mapping.length != COUNT) return;
        current = mapping.clone();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < COUNT; i++) {
            if (i > 0) sb.append(',');
            sb.append(mapping[i]);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MAPPING, sb.toString()).apply();
    }

    /** Persist + apply the LT/RT bindings (encoded with {@link #encodeAxis} / {@link #encodeButton}). */
    public static void saveTriggers(Context ctx, int ltCode, int rtCode) {
        lt = sanitizeTrigger(ltCode);
        rt = sanitizeTrigger(rtCode);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LT, lt).putInt(KEY_RT, rt).apply();
    }

    /** Reset buttons to the identity default and both triggers to auto. */
    public static void reset(Context ctx) {
        current = DEFAULT.clone();
        lt = rt = TRIGGER_AUTO;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_MAPPING).remove(KEY_LT).remove(KEY_RT).apply();
    }

    /** @return logical button index for a physical keycode, or -1 if it isn't a mapped button. */
    public static int toLogical(int keyCode) {
        for (int i = 0; i < COUNT; i++) if (current[i] == keyCode) return i;
        return -1;
    }

    // ----- triggers -----

    public static int encodeAxis(int axis)      { return (TYPE_AXIS << TYPE_SHIFT) | (axis & VALUE_MASK); }
    public static int encodeButton(int keyCode) { return (TYPE_BUTTON << TYPE_SHIFT) | (keyCode & VALUE_MASK); }
    public static int typeOf(int code)          { return (code >>> TYPE_SHIFT) & 0xFF; }
    public static int valueOf(int code)         { return code & VALUE_MASK; }

    /** Raw LT/RT binding: {@link #TRIGGER_AUTO} or an encoded AXIS/BUTTON. */
    public static int getTrigger(boolean left) {
        return left ? lt : rt;
    }

    /** Anything that is not a well-formed AXIS/BUTTON code is treated as auto, never as a binding. */
    private static int sanitizeTrigger(int code) {
        int t = typeOf(code);
        return (t == TYPE_AXIS || t == TYPE_BUTTON) ? code : TRIGGER_AUTO;
    }

    /** "auto", "AXIS_Z" or "button KEYCODE_BUTTON_L2" — for logs and the bug report. */
    public static String describeTrigger(int code) {
        switch (typeOf(code)) {
            case TYPE_AXIS:   return MotionEvent.axisToString(valueOf(code));
            case TYPE_BUTTON: return "button " + KeyEvent.keyCodeToString(valueOf(code));
            default:          return "auto";
        }
    }
}

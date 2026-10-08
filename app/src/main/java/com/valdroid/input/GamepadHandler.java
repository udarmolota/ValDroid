// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.input;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.valdroid.controls.InputControlsView;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Physical gamepad support. RimWorld is a mouse+keyboard game (Unity, no native controller
 * support), so a gamepad cannot send joystick events the game would understand. Instead we map
 * the controller onto ValDroid's existing MNK injection: the same {@link InputControlsView} cursor
 * + {@link Binding} actions used by the on-screen controls.
 *
 * Default LOGICAL→action mapping (the physical→logical remap lives in GamepadMapping / the mapper UI):
 *   - Right stick    -> move the cursor (analog, per-frame)        [matches the on-screen layout]
 *   - Left stick     -> camera pan (held W/A/S/D)
 *   - RB / LB        -> zoom in / out (mouse wheel, pulsed while held)
 *   - RT             -> left click      LT -> right click  (analog triggers as buttons)
 *   - A -> Enter      B -> Escape        X -> Tab (Architect)      Y -> Space (pause)
 *   - Start/Select -> Escape             L3 -> Q (rotate)          R3 -> Shift
 *   - D-pad          -> time controls: Left=speed1, Up=speed2, Right=speed3, Down=pause (Space)
 *
 * RimWorld's speed keys 1/2/3 may be unbound by default — set them in Options -> Keyboard if needed.
 *
 * Analog inputs need a per-frame loop (a held stick must keep moving the cursor / panning, but
 * Android only delivers a MotionEvent on change), so everything is driven from a Choreographer
 * frame callback that reads the latest state. To let several physical sources drive the same action
 * without fighting (e.g. left click from RT or the R2 keycode; D-pad as HAT axes OR keycodes), the
 * frame loop is the single source of truth: onKey/onMotion only record raw state, and applyFrame()
 * OR-combines it into edge-triggered presses.
 */
public class GamepadHandler {

    private static final String TAG = "ValDroid/Gamepad";

    // --- tunables ---
    private static final float DEADZONE      = 0.15f;  // stick deadzone (fraction)
    private static final float CAM_THRESHOLD = 0.50f;  // left-stick deflection to start a camera arrow
    private static final float TRIG_THRESH   = 0.30f;  // analog trigger press to count as a click
    private static final float CURSOR_PX_PER_SEC = 1400f;  // cursor speed at full deflection (screen px/s)
    private static final float ZOOM_INTERVAL = 0.09f;  // seconds between zoom notches while a bumper is held

    private final Activity activity;
    private final InputControlsView controls;

    // latest analog state (written by onMotion, read by the frame loop)
    private float rsX, rsY;     // right stick -> cursor
    private float lsX, lsY;     // left stick  -> camera
    private float lt, rt;       // triggers
    private float hatX, hatY;   // D-pad as HAT axes

    // raw button state (written by onKey)
    private boolean bA, bB, bX, bY, bL1, bR1, bL2, bR2, bStart, bSelect, bL3, bR3;
    private boolean dUp, dDown, dLeft, dRight;   // D-pad as keycodes

    private final EnumSet<Binding> held = EnumSet.noneOf(Binding.class);
    private float zoomAccum;
    // rising-edge memory for tap actions (pause / speeds / tab)
    private boolean ePause, eSpeed1, eSpeed2, eSpeed3;

    private boolean running;
    private long lastFrameNs;

    // Passthrough trigger state. A trigger can be driven by an axis AND a key at once (pads that
    // send L2/R2 keycodes alongside LTRIGGER), so the virtual trigger is the max of both. Without
    // this, every stick MotionEvent re-wrote the trigger from its (0) axis and released a trigger
    // that was being held as a key.
    private boolean ltKeyHeld, rtKeyHeld;
    private float ltAxis, rtAxis;

    // Per-device controller layout (which Android axes are the right stick / the triggers),
    // decided once from the device's motion ranges; dropped when the device changes.
    private final SparseArray<Layout> layouts = new SparseArray<>();
    private final SparseBooleanArray logged = new SparseBooleanArray();

    public GamepadHandler(Activity activity, InputControlsView controls) {
        this.activity = activity;
        this.controls = controls;
        GamepadMapping.load(activity);   // physical→logical button remap (from the mapper UI), identity by default
    }

    // ============================= lifecycle =============================

    /** Start the per-frame analog loop. Call from Activity.onResume (UI thread). */
    public void start() {
        if (running) return;
        running = true;
        lastFrameNs = 0;
        layouts.clear();   // device changes while paused were not heard (listener unregistered)
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    /** Stop the loop and release anything currently held. Call from Activity.onPause. */
    public void stop() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        for (Binding b : EnumSet.copyOf(held)) setHeld(b, false);   // unstick everything
        rsX = rsY = lsX = lsY = lt = rt = hatX = hatY = 0;
        bA = bB = bX = bY = bL1 = bR1 = bL2 = bR2 = bStart = bSelect = bL3 = bR3 = false;
        dUp = dDown = dLeft = dRight = false;
        ePause = eSpeed1 = eSpeed2 = eSpeed3 = false;
        ltKeyHeld = rtKeyHeld = false;
        ltAxis = rtAxis = 0;
    }

    /** A gamepad was added or changed: forget its cached layout and log the new one. */
    public void onInputDeviceChanged(int deviceId, String why) {
        layouts.remove(deviceId);
        logged.delete(deviceId);
        try {
            InputDevice d = InputDevice.getDevice(deviceId);
            if (isPadDevice(d)) logDevice(d, why);
        } catch (Throwable t) {
            Log.w(TAG, "device " + deviceId + " query failed", t);
        }
    }

    public void onInputDeviceRemoved(int deviceId) {
        layouts.remove(deviceId);
        logged.delete(deviceId);
    }

    /** Log every connected gamepad not logged yet by this handler (one block per device). */
    public void logConnectedGamepads(String why) {
        for (int id : InputDevice.getDeviceIds()) {
            if (logged.get(id)) continue;
            try {
                InputDevice d = InputDevice.getDevice(id);
                if (isPadDevice(d)) logDevice(d, why);
            } catch (Throwable t) {
                Log.w(TAG, "device " + id + " query failed", t);
            }
        }
    }

    private void logDevice(InputDevice d, String why) {
        logged.put(d.getId(), true);
        Log.i(TAG, describeDevice(d, why));
    }

    // ============================= input events =============================

    /** @return true if this key was a gamepad button we consumed. */
    // Valheim gets a real controller: the guest's SDL sees the virtual evdev gamepad (VirtualGamepad /
    // valdroid_pad.c), so the physical pad is passed through 1:1 and the game uses its own gamepad
    // UI and bindings. The MNK mapping below is kept for games without controller support.
    private static final boolean PASSTHROUGH = true;

    private static int logicalToButton(int logical) {
        switch (logical) {
            case GamepadMapping.L_A:      return VirtualGamepad.BTN_A;
            case GamepadMapping.L_B:      return VirtualGamepad.BTN_B;
            case GamepadMapping.L_X:      return VirtualGamepad.BTN_X;
            case GamepadMapping.L_Y:      return VirtualGamepad.BTN_Y;
            case GamepadMapping.L_LB:     return VirtualGamepad.BTN_TL;
            case GamepadMapping.L_RB:     return VirtualGamepad.BTN_TR;
            case GamepadMapping.L_SELECT: return VirtualGamepad.BTN_SELECT;
            case GamepadMapping.L_START:  return VirtualGamepad.BTN_START;
            case GamepadMapping.L_GUIDE:  return VirtualGamepad.BTN_MODE;
            case GamepadMapping.L_L3:     return VirtualGamepad.BTN_THUMBL;
            case GamepadMapping.L_R3:     return VirtualGamepad.BTN_THUMBR;
            default: return -1;
        }
    }

    /** Physical button/D-pad -> virtual evdev gamepad. Always consumes gamepad-sourced keys. */
    private boolean passthroughKey(KeyEvent e) {
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        // Triggers first: a trigger mapped as a BUTTON in the mapper may use any keycode, and it
        // must not also act as the button that keycode would otherwise be.
        if (isTriggerKey(e.getKeyCode(), true)) {
            ltKeyHeld = down;
            VirtualGamepad.trigger(VirtualGamepad.ABS_Z, Math.max(ltAxis, ltKeyHeld ? 1f : 0f));
            VirtualGamepad.sync();
            return true;
        }
        if (isTriggerKey(e.getKeyCode(), false)) {
            rtKeyHeld = down;
            VirtualGamepad.trigger(VirtualGamepad.ABS_RZ, Math.max(rtAxis, rtKeyHeld ? 1f : 0f));
            VirtualGamepad.sync();
            return true;
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:    VirtualGamepad.axis(VirtualGamepad.ABS_HAT0Y, down ? -1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  VirtualGamepad.axis(VirtualGamepad.ABS_HAT0Y, down ?  1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  VirtualGamepad.axis(VirtualGamepad.ABS_HAT0X, down ? -1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: VirtualGamepad.axis(VirtualGamepad.ABS_HAT0X, down ?  1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: return true;
            case KeyEvent.KEYCODE_BACK:      VirtualGamepad.button(VirtualGamepad.BTN_B, down); break;   // some pads' B
            default: {
                int btn = logicalToButton(GamepadMapping.toLogical(e.getKeyCode()));
                if (btn < 0) return true;   // unknown gamepad button: swallow, never let it become Back
                VirtualGamepad.button(btn, down);
            }
        }
        VirtualGamepad.sync();
        return true;
    }

    /** Physical sticks/triggers/hat -> virtual evdev gamepad axes. */
    private boolean passthroughMotion(MotionEvent e) {
        Layout l = layoutFor(e);
        ltAxis = readTrigger(e, l.lt);
        rtAxis = readTrigger(e, l.rt);
        VirtualGamepad.stick(VirtualGamepad.ABS_X,  e.getAxisValue(MotionEvent.AXIS_X));
        VirtualGamepad.stick(VirtualGamepad.ABS_Y,  e.getAxisValue(MotionEvent.AXIS_Y));
        VirtualGamepad.stick(VirtualGamepad.ABS_RX, e.getAxisValue(l.rsX));
        VirtualGamepad.stick(VirtualGamepad.ABS_RY, e.getAxisValue(l.rsY));
        VirtualGamepad.trigger(VirtualGamepad.ABS_Z,  Math.max(ltAxis, ltKeyHeld ? 1f : 0f));
        VirtualGamepad.trigger(VirtualGamepad.ABS_RZ, Math.max(rtAxis, rtKeyHeld ? 1f : 0f));
        VirtualGamepad.axis(VirtualGamepad.ABS_HAT0X, Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X)));
        VirtualGamepad.axis(VirtualGamepad.ABS_HAT0Y, Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y)));
        VirtualGamepad.sync();
        return true;
    }

    public boolean onKey(KeyEvent e) {
        if (!isFromGamepad(e.getSource())) return false;
        if (e.getRepeatCount() > 0) return true;   // ignore auto-repeat; hold is managed by raw state
        if (PASSTHROUGH) return passthroughKey(e);
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        final int kc = e.getKeyCode();
        // Triggers-as-keys (L2/R2, or the key recorded in the mapper) come first, then the D-pad;
        // neither is part of the remappable logical button set.
        if (isTriggerKey(kc, true))  { bL2 = down; return true; }   // some pads send triggers as keys
        if (isTriggerKey(kc, false)) { bR2 = down; return true; }
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:    dUp = down;    return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  dDown = down;  return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  dLeft = down;  return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dRight = down; return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: return true;
            case KeyEvent.KEYCODE_BACK:       bB = down;     return true;   // treat as Escape (some pads' B)
        }
        // Face / shoulder / menu / thumb buttons go through the user's physical→logical remap
        // (GamepadMapping), so swapped/“inverted” controllers can be fixed in the mapper UI.
        switch (GamepadMapping.toLogical(kc)) {
            case GamepadMapping.L_A:      bA = down;      return true;   // -> Enter / Accept
            case GamepadMapping.L_B:      bB = down;      return true;   // -> Escape
            case GamepadMapping.L_X:      bX = down;      return true;   // -> Tab / Architect
            case GamepadMapping.L_Y:      bY = down;      return true;   // -> Space / Pause
            case GamepadMapping.L_LB:     bL1 = down;     return true;   // -> zoom out
            case GamepadMapping.L_RB:     bR1 = down;     return true;   // -> zoom in
            case GamepadMapping.L_SELECT: bSelect = down; return true;   // -> Escape
            case GamepadMapping.L_START:  bStart = down;  return true;   // -> Escape
            case GamepadMapping.L_L3:     bL3 = down;     return true;   // -> Q (rotate)
            case GamepadMapping.L_R3:     bR3 = down;     return true;   // -> Shift
            default:
                return true;   // swallow other gamepad buttons so they don't trigger focus/back nav
        }
    }

    /** @return true if this was a joystick/gamepad analog motion we consumed. */
    public boolean onMotion(MotionEvent e) {
        if (!isFromGamepad(e.getSource())) return false;
        if (e.getAction() != MotionEvent.ACTION_MOVE) return false;
        if (PASSTHROUGH) return passthroughMotion(e);
        Layout l = layoutFor(e);
        rsX  = e.getAxisValue(l.rsX);    // right stick
        rsY  = e.getAxisValue(l.rsY);
        lsX  = e.getAxisValue(MotionEvent.AXIS_X);    // left stick
        lsY  = e.getAxisValue(MotionEvent.AXIS_Y);
        hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        lt = readTrigger(e, l.lt);
        rt = readTrigger(e, l.rt);
        return true;
    }

    /** True if this keycode drives LT/RT: the key recorded in the mapper, else L2/R2 (unless the
     *  trigger was recorded as an analog axis and L2/R2 is free to keep its default role anyway). */
    private static boolean isTriggerKey(int keyCode, boolean left) {
        int code = GamepadMapping.getTrigger(left);
        if (GamepadMapping.typeOf(code) == GamepadMapping.TYPE_BUTTON)
            return GamepadMapping.valueOf(code) == keyCode;
        return keyCode == (left ? KeyEvent.KEYCODE_BUTTON_L2 : KeyEvent.KEYCODE_BUTTON_R2);
    }

    /**
     * One trigger's value in 0..1: the largest of its source axes. Negative readings are clamped to
     * 0, not mirrored (Zomdroid's fix): the old {@code Math.abs} turned a trigger resting at -1 into
     * a fully held one. The only axis read as (v+1)/2 is one the user recorded in the mapper whose
     * range is -1..1 — there we KNOW it is a trigger resting at -1 (the mapper only accepts an axis
     * that rested at the bottom of its range), so the rescale gives it its full travel; clamping it
     * would lose the first half of the press. Auto-detected axes are never rescaled: a guess that
     * turned out to be a stick would read as a half-held trigger at rest.
     */
    private static float readTrigger(MotionEvent e, Trigger t) {
        float best = 0f;
        for (int ax : t.axes) {
            float v = e.getAxisValue(ax);
            if (t.rescale) v = (v + 1f) * 0.5f;
            if (v > best) best = v;
        }
        return best > 1f ? 1f : best;
    }

    // ============================= controller layout =============================

    /** Where one trigger comes from on this device. Empty axes = key-driven only (or none). */
    private static final class Trigger {
        int[] axes = new int[0];
        boolean rescale;       // a mapped -1..1 axis read as (v+1)/2, see readTrigger
        String source = "";    // for the log: "auto", "mapped", ...
    }

    /** Which Android axes feed the virtual pad's right stick and triggers on one device. */
    private static final class Layout {
        int rsX = MotionEvent.AXIS_Z, rsY = MotionEvent.AXIS_RZ;
        String stickWhy = "";
        final Trigger lt = new Trigger(), rt = new Trigger();

        String describe() {
            return "right stick = " + axisName(rsX) + "/" + axisName(rsY) + " (" + stickWhy + "), "
                    + "LT = " + describeTrigger(lt, true) + ", RT = " + describeTrigger(rt, false);
        }

        private static String describeTrigger(Trigger t, boolean left) {
            StringBuilder sb = new StringBuilder();
            for (int ax : t.axes) {
                if (sb.length() > 0) sb.append('|');
                sb.append(axisName(ax));
            }
            int code = GamepadMapping.getTrigger(left);
            if (GamepadMapping.typeOf(code) == GamepadMapping.TYPE_BUTTON) {
                sb.append(KeyEvent.keyCodeToString(GamepadMapping.valueOf(code)));
            } else {
                if (sb.length() == 0) sb.append("none");
                sb.append(left ? " + KEYCODE_BUTTON_L2" : " + KEYCODE_BUTTON_R2");
            }
            if (t.rescale) sb.append(", -1..1 rescaled");
            return sb + " (" + t.source + ")";
        }
    }

    // Today's (pre-layout) trigger axes. Z/RZ/RX/RY are added only by the range rules below.
    private static final int[] AUTO_LT = { MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_GENERIC_1, MotionEvent.AXIS_GENERIC_3 };
    private static final int[] AUTO_RT = { MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_GENERIC_2, MotionEvent.AXIS_GENERIC_4 };

    private Layout layoutFor(MotionEvent e) {
        int id = e.getDeviceId();
        Layout l = layouts.get(id);
        if (l == null) {
            l = buildLayout(e.getDevice());
            layouts.put(id, l);
        }
        return l;
    }

    /**
     * Decide the layout from the device's motion ranges.
     *
     * Why: Android has no fixed home for the right stick and the analog triggers. Most pads (with a
     * key layout file) report the right stick on Z/RZ and the triggers on LTRIGGER/RTRIGGER (or
     * BRAKE/GAS); others report the evdev layout raw — right stick on RX/RY, triggers on Z/RZ — and
     * then hard-wiring Z/RZ as the stick turns a trigger press into a camera tilt (Retroid Pocket
     * Flip 2 report: the triggers "lower the camera and raise it on release").
     *
     *  - Right stick: the pair among (Z,RZ) and (RX,RY) whose ranges are both bipolar (min < -0.5);
     *    (Z,RZ) when both pairs qualify (today's choice, right for most pads) or when neither does
     *    (ranges missing/odd: today's mapping).
     *  - Triggers: LTRIGGER/BRAKE/GENERIC_1/GENERIC_3 and RTRIGGER/GAS/GENERIC_2/GENERIC_4 when the
     *    device has them, plus any axis of the OTHER pair whose range is unipolar (min >= -0.01,
     *    max > 0.5): Z or RX -> LT, RZ or RY -> RT.
     *  - A trigger recorded in the mapper wins: AXIS -> only that axis (and it is taken out of the
     *    right stick, which then uses the other pair); BUTTON -> no axis, the key drives it.
     */
    private static Layout buildLayout(InputDevice d) {
        Layout l = new Layout();
        int ltCode = GamepadMapping.getTrigger(true), rtCode = GamepadMapping.getTrigger(false);
        int ltMapped = GamepadMapping.typeOf(ltCode) == GamepadMapping.TYPE_AXIS ? GamepadMapping.valueOf(ltCode) : -1;
        int rtMapped = GamepadMapping.typeOf(rtCode) == GamepadMapping.TYPE_AXIS ? GamepadMapping.valueOf(rtCode) : -1;

        // --- right stick from ranges ---
        boolean zPair = d != null && isBipolar(d, MotionEvent.AXIS_Z) && isBipolar(d, MotionEvent.AXIS_RZ);
        boolean rPair = d != null && isBipolar(d, MotionEvent.AXIS_RX) && isBipolar(d, MotionEvent.AXIS_RY);
        boolean useR = !zPair && rPair;
        if (d == null) l.stickWhy = "no device info, default";
        else if (zPair) l.stickWhy = rPair ? "both pairs bipolar, Z/RZ preferred" : "Z/RZ bipolar";
        else if (rPair) l.stickWhy = "RX/RY bipolar, Z/RZ not";
        else l.stickWhy = "no bipolar pair, default";

        // --- a mapped trigger axis cannot also be the stick ---
        boolean zTaken = isOneOf(ltMapped, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
                || isOneOf(rtMapped, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ);
        boolean rTaken = isOneOf(ltMapped, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
                || isOneOf(rtMapped, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY);
        if (!useR && zTaken && !rTaken) { useR = true;  l.stickWhy = "Z/RZ mapped as a trigger"; }
        else if (useR && rTaken && !zTaken) { useR = false; l.stickWhy = "RX/RY mapped as a trigger"; }
        else if (zTaken && rTaken) l.stickWhy += "; WARNING both pairs hold a mapped trigger";
        if (useR) { l.rsX = MotionEvent.AXIS_RX; l.rsY = MotionEvent.AXIS_RY; }

        // --- triggers ---
        int otherL = useR ? MotionEvent.AXIS_Z : MotionEvent.AXIS_RX;   // the non-stick pair
        int otherR = useR ? MotionEvent.AXIS_RZ : MotionEvent.AXIS_RY;
        fillTrigger(l.lt, d, ltCode, ltMapped, rtMapped, AUTO_LT, otherL);
        fillTrigger(l.rt, d, rtCode, rtMapped, ltMapped, AUTO_RT, otherR);
        return l;
    }

    private static void fillTrigger(Trigger t, InputDevice d, int code, int mappedAxis,
                                    int otherMappedAxis, int[] autoAxes, int otherPairAxis) {
        if (GamepadMapping.typeOf(code) == GamepadMapping.TYPE_BUTTON) {
            t.source = "mapped button";
            return;
        }
        if (mappedAxis >= 0) {
            t.axes = new int[]{ mappedAxis };
            InputDevice.MotionRange r = d != null ? range(d, mappedAxis) : null;
            t.rescale = r != null && r.getMin() < -0.5f;
            t.source = "mapped";
            return;
        }
        List<Integer> axes = new ArrayList<>();
        for (int ax : autoAxes) {
            // No device info: read them all, as before (getAxisValue is 0 for missing axes).
            if (ax != otherMappedAxis && (d == null || range(d, ax) != null)) axes.add(ax);
        }
        boolean extra = d != null && otherPairAxis != otherMappedAxis && isUnipolar(d, otherPairAxis);
        if (extra) axes.add(otherPairAxis);
        t.axes = new int[axes.size()];
        for (int i = 0; i < t.axes.length; i++) t.axes[i] = axes.get(i);
        t.source = extra ? "auto, unipolar " + axisName(otherPairAxis) : "auto";
    }

    /** The axis's range on the joystick source, or any source as a backup (Zomdroid's check). */
    private static InputDevice.MotionRange range(InputDevice d, int axis) {
        InputDevice.MotionRange r = d.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        return r != null ? r : d.getMotionRange(axis);
    }

    private static boolean isBipolar(InputDevice d, int axis) {
        InputDevice.MotionRange r = range(d, axis);
        return r != null && r.getMin() < -0.5f;
    }

    private static boolean isUnipolar(InputDevice d, int axis) {
        InputDevice.MotionRange r = range(d, axis);
        return r != null && r.getMin() >= -0.01f && r.getMax() > 0.5f;
    }

    private static boolean isOneOf(int v, int a, int b) {
        return v >= 0 && (v == a || v == b);
    }

    private static String axisName(int axis) {
        String s = MotionEvent.axisToString(axis);
        return s.startsWith("AXIS_") ? s.substring(5) : s;
    }

    // ============================= diagnostics =============================

    /** One log block: identity, sources, every motion range, and the decided layout. */
    private static String describeDevice(InputDevice d, String why) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("gamepad (").append(why).append("): \"").append(d.getName()).append("\" id=").append(d.getId())
          .append(String.format(Locale.US, " vendor=0x%04x product=0x%04x", d.getVendorId(), d.getProductId()))
          .append(" sources=").append(describeSources(d.getSources()));
        for (InputDevice.MotionRange r : d.getMotionRanges()) {
            sb.append(String.format(Locale.US, "\n  %-16s %6.2f..%5.2f flat %.3f fuzz %.3f",
                    MotionEvent.axisToString(r.getAxis()), r.getMin(), r.getMax(), r.getFlat(), r.getFuzz()));
            if (r.getSource() != InputDevice.SOURCE_JOYSTICK)
                sb.append(" [").append(describeSources(r.getSource())).append(']');
        }
        sb.append("\n  layout: ").append(buildLayout(d).describe());
        return sb.toString();
    }

    private static String describeSources(int s) {
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "0x%08x", s));
        if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)   sb.append(" gamepad");
        if ((s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) sb.append(" joystick");
        if ((s & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD)         sb.append(" dpad");
        if ((s & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) sb.append(" keyboard");
        if ((s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)       sb.append(" mouse");
        if ((s & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) sb.append(" touchpad");
        return sb.toString();
    }

    /**
     * Short "gamepad" line for the bug report: every connected pad with ids and decided layout, or
     * "(none connected)". Loads the saved mapping so a mapped trigger shows as it would in game.
     */
    public static String describeConnected(Context ctx) {
        try {
            GamepadMapping.load(ctx);
            StringBuilder sb = new StringBuilder();
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (!isPadDevice(d)) continue;
                if (sb.length() > 0) sb.append(" ; ");
                sb.append('"').append(d.getName()).append('"')
                  .append(String.format(Locale.US, " %04x:%04x, ", d.getVendorId(), d.getProductId()))
                  .append(buildLayout(d).describe());
            }
            return sb.length() == 0 ? "(none connected)" : sb.toString();
        } catch (Throwable t) {
            return "probe failed: " + t;
        }
    }

    // ============================= per-frame loop =============================

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            float dt = (lastFrameNs == 0) ? (1f / 60f) : (frameTimeNanos - lastFrameNs) / 1e9f;
            lastFrameNs = frameTimeNanos;
            if (dt > 0.1f) dt = 0.1f;   // clamp after a stall
            applyFrame(dt);
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void applyFrame(float dt) {
        // right stick -> cursor
        float cx = curve(rsX), cy = curve(rsY);
        if (cx != 0f || cy != 0f)
            controls.moveCursorBy(cx * CURSOR_PX_PER_SEC * dt, cy * CURSOR_PX_PER_SEC * dt);

        // left stick -> camera pan, matching RimWorld's controller profile
        setHeld(Binding.KEY_D, lsX >  CAM_THRESHOLD);
        setHeld(Binding.KEY_A, lsX < -CAM_THRESHOLD);
        setHeld(Binding.KEY_S, lsY >  CAM_THRESHOLD);
        setHeld(Binding.KEY_W, lsY < -CAM_THRESHOLD);

        // clicks live on the triggers only (RT=left, LT=right).
        setHeld(Binding.MOUSE_LEFT,  rt > TRIG_THRESH || bR2);
        setHeld(Binding.MOUSE_RIGHT, lt > TRIG_THRESH || bL2);

        // Face buttons follow the labels drawn by RimWorld's controller UI.
        setHeld(Binding.KEY_ENTER, bA);                        // A -> Accept / first command
        setHeld(Binding.KEY_ESCAPE, bB || bStart || bSelect); // B/Menu -> Back
        setHeld(Binding.KEY_TAB, bX);                          // X -> Architect
        setHeld(Binding.KEY_SPACE, bY);                        // Y -> Pause/resume
        setHeld(Binding.KEY_Q, bL3);                           // L3 -> rotate blueprint
        setHeld(Binding.KEY_LSHIFT, bR3);                      // R3 -> queue/multi-select modifier

        // bumpers -> pulsed zoom (mouse wheel). SCROLL bindings act once per inject.
        zoomAccum += dt;
        if (zoomAccum >= ZOOM_INTERVAL) {
            if (bR1)      { controls.inject(Binding.SCROLL_UP,   true); zoomAccum = 0f; }
            else if (bL1) { controls.inject(Binding.SCROLL_DOWN, true); zoomAccum = 0f; }
            else            zoomAccum = ZOOM_INTERVAL;   // cap so the next press fires promptly
        }

        // D-pad -> time controls (tap on rising edge), via keycodes OR HAT axes.
        boolean dpUp    = dUp    || hatY < -0.5f;
        boolean dpDown  = dDown  || hatY >  0.5f;
        boolean dpLeft  = dLeft  || hatX < -0.5f;
        boolean dpRight = dRight || hatX >  0.5f;
        eSpeed1 = edgeTap(Binding.KEY_1, dpLeft,  eSpeed1);          // D-pad Left  -> speed 1
        eSpeed2 = edgeTap(Binding.KEY_2, dpUp,    eSpeed2);          // D-pad Up    -> speed 2
        eSpeed3 = edgeTap(Binding.KEY_3, dpRight, eSpeed3);          // D-pad Right -> speed 3
        ePause  = edgeTap(Binding.KEY_SPACE, dpDown, ePause);       // D-pad Down  -> pause
    }

    // ============================= helpers =============================

    /** Edge-triggered hold: inject press/release only when the desired state changes. */
    private void setHeld(Binding b, boolean want) {
        boolean cur = held.contains(b);
        if (want && !cur) { held.add(b); controls.inject(b, true); }
        else if (!want && cur) { held.remove(b); controls.inject(b, false); }
    }

    /** Fire a quick tap when {@code want} rises from false to true. Returns the new edge state. */
    private boolean edgeTap(Binding b, boolean want, boolean prev) {
        if (want && !prev) controls.tapCursor(b);
        return want;
    }

    /** Deadzone + light response curve (squared) for precise slow cursor moves. */
    private static float curve(float v) {
        float a = Math.abs(v);
        if (a < DEADZONE) return 0f;
        float n = (a - DEADZONE) / (1f - DEADZONE);   // 0..1 past the deadzone
        return Math.signum(v) * n * n;
    }

    private static boolean isFromGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_DPAD)     == InputDevice.SOURCE_DPAD;
    }

    /** Shared launch/activity probe. Require a real analog-capable device so keyboards and
     *  Android TV remotes that merely advertise DPAD do not enable controller UI. */
    public static boolean hasConnectedGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            try {
                if (isPadDevice(InputDevice.getDevice(id))) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * A physical keyboard with letters is connected (Zomdroid's rule: then the on-screen controls go).
     * Only an external, non-virtual alphabetic keyboard counts: volume keys, a fingerprint sensor and
     * other built-in "keyboards" a phone reports must not hide the controls.
     */
    public static boolean hasExternalKeyboard() {
        for (int id : InputDevice.getDeviceIds()) {
            try {
                InputDevice d = InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                if (d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) continue;
                if (!d.supportsSource(InputDevice.SOURCE_KEYBOARD)) continue;
                if (android.os.Build.VERSION.SDK_INT >= 29 && !d.isExternal()) continue;
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** A real (non-virtual) gamepad/joystick with analog axes — see {@link #hasConnectedGamepad}. */
    private static boolean isPadDevice(InputDevice device) {
        if (device == null || device.isVirtual()) return false;
        int sources = device.getSources();
        boolean gamepad =
               (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        boolean hasMotion = device.getMotionRanges() != null
                && !device.getMotionRanges().isEmpty();
        return gamepad && hasMotion;
    }
}

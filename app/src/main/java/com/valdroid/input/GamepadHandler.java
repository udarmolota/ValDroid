package com.valdroid.input;

import android.app.Activity;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.EnumSet;

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
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:    VirtualGamepad.axis(VirtualGamepad.ABS_HAT0Y, down ? -1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  VirtualGamepad.axis(VirtualGamepad.ABS_HAT0Y, down ?  1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  VirtualGamepad.axis(VirtualGamepad.ABS_HAT0X, down ? -1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: VirtualGamepad.axis(VirtualGamepad.ABS_HAT0X, down ?  1 : 0); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: return true;
            case KeyEvent.KEYCODE_BUTTON_L2:  VirtualGamepad.trigger(VirtualGamepad.ABS_Z,  down ? 1f : 0f); break;
            case KeyEvent.KEYCODE_BUTTON_R2:  VirtualGamepad.trigger(VirtualGamepad.ABS_RZ, down ? 1f : 0f); break;
            case KeyEvent.KEYCODE_BACK:       VirtualGamepad.button(VirtualGamepad.BTN_B, down); break;   // some pads' B
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
        VirtualGamepad.stick(VirtualGamepad.ABS_X,  e.getAxisValue(MotionEvent.AXIS_X));
        VirtualGamepad.stick(VirtualGamepad.ABS_Y,  e.getAxisValue(MotionEvent.AXIS_Y));
        VirtualGamepad.stick(VirtualGamepad.ABS_RX, e.getAxisValue(MotionEvent.AXIS_Z));
        VirtualGamepad.stick(VirtualGamepad.ABS_RY, e.getAxisValue(MotionEvent.AXIS_RZ));
        VirtualGamepad.trigger(VirtualGamepad.ABS_Z,  readTrigger(e, true));
        VirtualGamepad.trigger(VirtualGamepad.ABS_RZ, readTrigger(e, false));
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
        // D-pad and triggers-as-keys are handled directly (not part of the remappable logical set).
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:    dUp = down;    return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  dDown = down;  return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  dLeft = down;  return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dRight = down; return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: return true;
            case KeyEvent.KEYCODE_BUTTON_L2:  bL2 = down;    return true;   // some pads send triggers as keys
            case KeyEvent.KEYCODE_BUTTON_R2:  bR2 = down;    return true;
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
        rsX  = e.getAxisValue(MotionEvent.AXIS_Z);    // right stick
        rsY  = e.getAxisValue(MotionEvent.AXIS_RZ);
        lsX  = e.getAxisValue(MotionEvent.AXIS_X);    // left stick
        lsY  = e.getAxisValue(MotionEvent.AXIS_Y);
        hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        lt = readTrigger(e, true);
        rt = readTrigger(e, false);
        return true;
    }

    /** Read an analog trigger across the axes different controllers use, checking capability first
     *  (getAxisValue returns 0 for unsupported axes). Z/RZ are intentionally excluded — we use them
     *  for the right stick; controllers that put triggers there will need the (later) remap. */
    private static float readTrigger(MotionEvent e, boolean left) {
        android.view.InputDevice d = e.getDevice();
        final int[] axes = left
            ? new int[]{ MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GENERIC_1, MotionEvent.AXIS_GENERIC_3 }
            : new int[]{ MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS,   MotionEvent.AXIS_GENERIC_2, MotionEvent.AXIS_GENERIC_4 };
        final int src = e.getSource();
        for (int ax : axes) {
            if (d == null || d.getMotionRange(ax, src) != null || d.getMotionRange(ax) != null) {
                float v = Math.abs(e.getAxisValue(ax));
                if (v > 1f) v = 1f;
                if (v > 0f) return v;
            }
        }
        return 0f;
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
                InputDevice device = InputDevice.getDevice(id);
                if (device == null || device.isVirtual()) continue;
                int sources = device.getSources();
                boolean gamepad =
                       (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
                boolean hasMotion = device.getMotionRanges() != null
                        && !device.getMotionRanges().isEmpty();
                if (gamepad && hasMotion) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}

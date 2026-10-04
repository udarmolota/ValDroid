package com.valdroid.nativeunity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

/**
 * On-screen gamepad over the native Unity player (PoC). Every change is sent to il2mono over JNI
 * (nativeSetState); the managed ValDroid.VirtualPad queues it to an Input System Gamepad, so Valheim
 * sees an ordinary controller. Touches that hit no control fall through to Unity (menus by touch).
 * The "PAD" button hides/shows the controls.
 */
public class ValDroidPad extends View {
    static {
        System.loadLibrary("il2cpp"); // already loaded by libmain; this binds the JNI method
    }

    private static native void nativeSetState(int buttons, float lx, float ly, float rx, float ry, float lt, float rt);

    // UnityEngine.InputSystem.LowLevel.GamepadButton bit numbers.
    private static final int DPAD_UP = 0, DPAD_DOWN = 1, DPAD_LEFT = 2, DPAD_RIGHT = 3;
    private static final int NORTH = 4, EAST = 5, SOUTH = 6, WEST = 7;
    private static final int L3 = 8, R3 = 9, LB = 10, RB = 11, START = 12, SELECT = 13;
    private static final int TRIGGER_L = 100, TRIGGER_R = 101, TOGGLE = 102; // not GamepadButton bits

    private static final class Button {
        final String label;
        final int id;
        final float fx, fy; // centre as a fraction of width / height
        float x, y, r;
        Button(String label, int id, float fx, float fy) {
            this.label = label; this.id = id; this.fx = fx; this.fy = fy;
        }
    }

    private final Button[] buttons = {
        new Button("Y", NORTH, 0.86f, 0.52f), new Button("A", SOUTH, 0.86f, 0.86f),
        new Button("X", WEST, 0.79f, 0.69f), new Button("B", EAST, 0.93f, 0.69f),
        new Button("LT", TRIGGER_L, 0.05f, 0.17f), new Button("LB", LB, 0.05f, 0.36f),
        new Button("RT", TRIGGER_R, 0.95f, 0.17f), new Button("RB", RB, 0.95f, 0.36f),
        new Button("L3", L3, 0.25f, 0.52f), new Button("R3", R3, 0.70f, 0.86f),
        new Button("↑", DPAD_UP, 0.30f, 0.70f), new Button("↓", DPAD_DOWN, 0.30f, 0.92f),
        new Button("←", DPAD_LEFT, 0.25f, 0.81f), new Button("→", DPAD_RIGHT, 0.35f, 0.81f),
        new Button("SEL", SELECT, 0.40f, 0.07f), new Button("☰", START, 0.60f, 0.07f),
        new Button("PAD", TOGGLE, 0.50f, 0.07f),
    };

    // What each active pointer controls: a Button, or one of the two sticks.
    private static final Object LEFT_STICK = new Object(), RIGHT_STICK = new Object();
    private final SparseArray<Object> pointers = new SparseArray<>();
    private float leftCx, leftCy, stickR;          // fixed left stick
    private float rightOx, rightOy;                 // floating right stick origin
    private float lx, ly, rx, ry;
    private boolean visible = true;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ValDroidPad(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.argb(170, 255, 255, 255));
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float unit = h;
        for (Button b : buttons) {
            b.x = b.fx * w; b.y = b.fy * h;
            b.r = (b.id == TOGGLE || b.id == START || b.id == SELECT) ? unit * 0.045f
                : (b.id >= DPAD_UP && b.id <= DPAD_RIGHT) ? unit * 0.05f : unit * 0.065f;
        }
        stickR = unit * 0.16f;
        leftCx = w * 0.13f; leftCy = h * 0.72f;
        ring.setStrokeWidth(unit * 0.005f);
        text.setTextSize(unit * 0.035f);
    }

    private Button hit(float x, float y) {
        for (Button b : buttons) {
            if (!visible && b.id != TOGGLE) continue;
            float dx = x - b.x, dy = y - b.y;
            if (dx * dx + dy * dy <= b.r * b.r * 1.4f) return b;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                Object target = hit(x, y);
                if (target == null && visible) {
                    float dx = x - leftCx, dy = y - leftCy;
                    if (dx * dx + dy * dy <= stickR * stickR * 2.2f) target = LEFT_STICK;
                    else if (x > getWidth() * 0.5f && y > getHeight() * 0.18f) {
                        target = RIGHT_STICK; rightOx = x; rightOy = y;
                    }
                }
                if (target == null) return action == MotionEvent.ACTION_POINTER_DOWN; // let Unity have it
                if (target instanceof Button && ((Button) target).id == TOGGLE) {
                    visible = !visible;
                    pointers.clear(); lx = ly = rx = ry = 0;
                } else {
                    pointers.put(e.getPointerId(idx), target);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Object t = pointers.get(e.getPointerId(i));
                    if (t == LEFT_STICK) {
                        float[] v = stick(e.getX(i) - leftCx, e.getY(i) - leftCy);
                        lx = v[0]; ly = v[1];
                    } else if (t == RIGHT_STICK) {
                        float[] v = stick(e.getX(i) - rightOx, e.getY(i) - rightOy);
                        rx = v[0]; ry = v[1];
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (action == MotionEvent.ACTION_CANCEL) pointers.clear();
                else {
                    Object t = pointers.get(e.getPointerId(idx));
                    if (t == LEFT_STICK) { lx = ly = 0; }
                    if (t == RIGHT_STICK) { rx = ry = 0; }
                    pointers.remove(e.getPointerId(idx));
                }
                if (action == MotionEvent.ACTION_CANCEL) lx = ly = rx = ry = 0;
                break;
            }
            default:
                return true;
        }
        send();
        invalidate();
        return true;
    }

    private float[] stick(float dx, float dy) {
        float nx = dx / stickR, ny = -dy / stickR;  // screen y grows down, stick y grows up
        float len = (float) Math.sqrt(nx * nx + ny * ny);
        if (len > 1f) { nx /= len; ny /= len; }
        return new float[] { nx, ny };
    }

    private void send() {
        int bits = 0;
        float lt = 0, rt = 0;
        for (int i = 0; i < pointers.size(); i++) {
            Object t = pointers.valueAt(i);
            if (!(t instanceof Button)) continue;
            int id = ((Button) t).id;
            if (id == TRIGGER_L) lt = 1f;
            else if (id == TRIGGER_R) rt = 1f;
            else if (id < 32) bits |= 1 << id;
        }
        nativeSetState(bits, lx, ly, rx, ry, lt, rt);
    }

    @Override protected void onDraw(Canvas c) {
        for (Button b : buttons) {
            if (!visible && b.id != TOGGLE) continue;
            boolean down = false;
            for (int i = 0; i < pointers.size(); i++) if (pointers.valueAt(i) == b) down = true;
            fill.setColor(down ? Color.argb(150, 255, 160, 40) : Color.argb(90, 0, 0, 0));
            c.drawCircle(b.x, b.y, b.r, fill);
            c.drawCircle(b.x, b.y, b.r, ring);
            c.drawText(b.label, b.x, b.y + text.getTextSize() * 0.35f, text);
        }
        if (!visible) return;
        fill.setColor(Color.argb(70, 0, 0, 0));
        c.drawCircle(leftCx, leftCy, stickR, fill);
        c.drawCircle(leftCx, leftCy, stickR, ring);
        fill.setColor(Color.argb(140, 255, 255, 255));
        c.drawCircle(leftCx + lx * stickR, leftCy - ly * stickR, stickR * 0.4f, fill);
        if (pointers.indexOfValue(RIGHT_STICK) >= 0) {
            c.drawCircle(rightOx, rightOy, stickR, ring);
            c.drawCircle(rightOx + rx * stickR, rightOy - ry * stickR, stickR * 0.4f, fill);
        }
    }
}

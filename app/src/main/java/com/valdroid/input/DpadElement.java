package com.valdroid.input;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * The virtual gamepad's d-pad as one element: the touch position inside the cross picks one of
 * eight directions (diagonals press two arms), sent as the pad's hat through
 * {@link InputControlsView#injectDpad}. Ported from Zomdroid's DpadControlElement.
 */
public class DpadElement extends ControlElement {

    private static final float OUTER_DP = 56f;
    private static final float ARM_DP   = 19f;    // half-width of an arm
    private static final float DEAD = 0.22f;      // of the outer radius
    private static final int[] BITS = { 1, 2, 4, 8 };   // up, right, down, left (Binding.GAMEPAD_DPAD_*.code)

    private int pointerId = -1;
    private int mask = 0;

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public DpadElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        fill.setStyle(Paint.Style.FILL);
        active.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
    }

    private float outerR() { return dp(OUTER_DP) * scale; }

    @Override public boolean isGamepadElement() { return true; }

    @Override public boolean isPointOver(float x, float y) {
        float dx = x - centerX(), dy = y - centerY(); float r = outerR();
        return dx * dx + dy * dy <= r * r;
    }

    @Override public boolean handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (isPointOver(e.getX(idx), e.getY(idx))
                        && (pointerId < 0 || e.findPointerIndex(pointerId) < 0)) {
                    pointerId = pid;
                    view.maybeHaptic();
                    update(e.getX(idx), e.getY(idx));
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (pointerId < 0) return false;
                int i = e.findPointerIndex(pointerId);
                if (i >= 0) update(e.getX(i), e.getY(i));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = (action == MotionEvent.ACTION_CANCEL);
                if (cancel || pid == pointerId) {
                    setMask(0); pointerId = -1;
                    return !cancel;
                }
                return false;
            }
        }
        return false;
    }

    private void update(float x, float y) {
        float r = outerR();
        float nx = (x - centerX()) / r, ny = (y - centerY()) / r;
        int m = 0;
        if (nx * nx + ny * ny >= DEAD * DEAD) {
            // 8-way: an arm is pressed when the touch lies within 67.5 degrees of its axis.
            double a = Math.atan2(ny, nx);                 // 0 = right, +90 = down (screen y grows downwards)
            double t = Math.toRadians(67.5);
            if (Math.abs(angleDiff(a, -Math.PI / 2)) < t) m |= BITS[0];
            if (Math.abs(angleDiff(a, 0)) < t)            m |= BITS[1];
            if (Math.abs(angleDiff(a, Math.PI / 2)) < t)  m |= BITS[2];
            if (Math.abs(angleDiff(a, Math.PI)) < t)      m |= BITS[3];
        }
        setMask(m);
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    private void setMask(int m) {
        if (m == mask) return;
        view.injectDpad(mask, m);
        mask = m;
        view.invalidate();
    }

    @Override public void reset() { setMask(0); pointerId = -1; }

    @Override public void draw(Canvas c) {
        float cx = centerX(), cy = centerY(), r = outerR(), arm = dp(ARM_DP) * scale;
        fill.setColor(0x00FFFFFF | ((int) (alpha * 0.30f) << 24));
        active.setColor(0x00FFFFFF | (Math.min(255, alpha + 60) << 24));
        stroke.setColor(0x00FFFFFF | (Math.min(255, alpha + 30) << 24));
        stroke.setStrokeWidth(dp(2));
        if (highlighted) { stroke.setColor(0xFF33C0FF); stroke.setStrokeWidth(dp(3)); }
        float rr = dp(6) * scale;
        // up, right, down, left arms
        float[][] arms = {
                { cx - arm, cy - r,   cx + arm, cy - arm },
                { cx + arm, cy - arm, cx + r,   cy + arm },
                { cx - arm, cy + arm, cx + arm, cy + r   },
                { cx - r,   cy - arm, cx - arm, cy + arm },
        };
        for (int i = 0; i < 4; i++) {
            rect.set(arms[i][0], arms[i][1], arms[i][2], arms[i][3]);
            c.drawRoundRect(rect, rr, rr, (mask & BITS[i]) != 0 ? active : fill);
            c.drawRoundRect(rect, rr, rr, stroke);
        }
        rect.set(cx - arm, cy - arm, cx + arm, cy + arm);
        c.drawRect(rect, fill);
    }

    @Override public ControlElementDescription describe() {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "DPAD";
        return baseDescribe(d);
    }

    @Override public String editorLabel() { return "Gamepad D-pad"; }
}

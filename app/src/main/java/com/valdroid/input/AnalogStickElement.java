package com.valdroid.input;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Analog stick of the virtual gamepad ({@link VirtualGamepad}): the knob offset goes out as a
 * continuous -1..1 pair on the left (ABS_X/ABS_Y) or right (ABS_RX/ABS_RY) stick, so the game sees
 * walk/run and camera speed the way a real pad gives them. Ported from Zomdroid's
 * StickControlElement; the touch handling is the same as {@link WasdStickElement}.
 */
public class AnalogStickElement extends ControlElement {

    private static final float OUTER_DP = 58f;
    private static final float KNOB_DP  = 26f;

    private Binding stick;          // LEFT_JOYSTICK | RIGHT_JOYSTICK
    private int pointerId = -1;
    private float knobX, knobY;

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AnalogStickElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        Binding b = (d.bindings != null && d.bindings.length > 0)
                ? Binding.fromName(d.bindings[0], Binding.LEFT_JOYSTICK) : Binding.LEFT_JOYSTICK;
        this.stick = (b == Binding.RIGHT_JOYSTICK) ? Binding.RIGHT_JOYSTICK : Binding.LEFT_JOYSTICK;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float outerR() { return dp(OUTER_DP) * scale; }
    private float knobR()  { return dp(KNOB_DP) * scale; }

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
                // Same ghost-pointer rule as WasdStickElement: re-claim only when the owned pointer is gone.
                if (isPointOver(e.getX(idx), e.getY(idx))
                        && (pointerId < 0 || e.findPointerIndex(pointerId) < 0)) {
                    pointerId = pid;
                    update(e.getX(idx), e.getY(idx));
                    view.invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE: {
                if (pointerId < 0) return false;
                int i = e.findPointerIndex(pointerId);
                if (i >= 0) { update(e.getX(i), e.getY(i)); view.invalidate(); }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = (action == MotionEvent.ACTION_CANCEL);
                if (cancel || pid == pointerId) {
                    center(); pointerId = -1; view.invalidate();
                    return !cancel;
                }
                return false;
            }
        }
        return false;
    }

    private void update(float x, float y) {
        float dx = x - centerX(), dy = y - centerY();
        float max = outerR() - knobR();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > max && len > 0.001f) { float k = max / len; dx *= k; dy *= k; }
        knobX = dx; knobY = dy;
        view.injectStick(stick, max > 0 ? dx / max : 0f, max > 0 ? dy / max : 0f);
    }

    private void center() {
        if (knobX != 0f || knobY != 0f || pointerId >= 0) view.injectStick(stick, 0f, 0f);
        knobX = knobY = 0f;
    }

    @Override public void reset() { center(); pointerId = -1; }

    @Override public void draw(Canvas c) {
        float cx = centerX(), cy = centerY();
        fill.setColor(0x00FFFFFF | ((int) (alpha * 0.20f) << 24));
        stroke.setColor(0x00FFFFFF | (Math.min(255, alpha + 30) << 24));
        stroke.setStrokeWidth(dp(2));
        if (highlighted) { stroke.setColor(0xFF33C0FF); stroke.setStrokeWidth(dp(3)); }
        c.drawCircle(cx, cy, outerR(), fill);
        c.drawCircle(cx, cy, outerR(), stroke);
        c.drawCircle(cx + knobX, cy + knobY, knobR(), fill);
        c.drawCircle(cx + knobX, cy + knobY, knobR(), stroke);
        textPaint.setColor(0x00FFFFFF | (Math.min(255, alpha + 75) << 24));
        textPaint.setTextSize(knobR() * 0.9f);
        float ty = cy + knobY - (textPaint.descent() + textPaint.ascent()) / 2f;
        c.drawText(stick == Binding.RIGHT_JOYSTICK ? "R" : "L", cx + knobX, ty, textPaint);
    }

    @Override public ControlElementDescription describe() {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "STICK";
        d.bindings = new String[]{ stick.name() };
        return baseDescribe(d);
    }

    @Override public String editorLabel() {
        return stick == Binding.RIGHT_JOYSTICK ? "Gamepad right stick" : "Gamepad left stick";
    }

    public boolean isRightStick() { return stick == Binding.RIGHT_JOYSTICK; }
    public void setRightStick(boolean right) {
        reset();
        stick = right ? Binding.RIGHT_JOYSTICK : Binding.LEFT_JOYSTICK;
    }
}

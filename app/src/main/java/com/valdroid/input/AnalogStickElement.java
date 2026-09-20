package com.valdroid.input;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Analog stick of the virtual gamepad ({@link VirtualGamepad}): the knob offset goes out as a
 * continuous -1..1 pair on the left (ABS_X/ABS_Y) or right (ABS_RX/ABS_RY) stick, so the game sees
 * walk/run and camera speed the way a real pad gives them. Ported from Zomdroid's
 * StickControlElement with its geometry and look (sizes in units of view width / 2560, outlined
 * ring, filled knob); the touch handling is the same as {@link WasdStickElement}.
 */
public class AnalogStickElement extends ControlElement {

    private static final float OUTER_R = 160f, INNER_R = 90f;      // Zomdroid StickControlDrawable
    private static final int   DEFAULT_COLOR = 0xFFCCCCCC, OUTLINE_COLOR = 0x00282828, OUTLINE_ALPHA = 70;

    /** Output multiplier applied AFTER the stick is normalised: 70% means the game never sees more
     *  than 70% of a real stick's deflection, i.e. a 30% slower camera at full push. The right stick
     *  turns the camera, where a thumb on glass overshoots easily. */
    public static final float MIN_SENS = 0.25f, MAX_SENS = 2.0f, DEFAULT_LEFT = 1.0f, DEFAULT_RIGHT = 0.7f;

    private Binding stick;          // LEFT_JOYSTICK | RIGHT_JOYSTICK
    private float sensitivity;
    private final int color;
    private int pointerId = -1;
    private float knobX, knobY;

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AnalogStickElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        Binding b = (d.bindings != null && d.bindings.length > 0)
                ? Binding.fromName(d.bindings[0], Binding.LEFT_JOYSTICK) : Binding.LEFT_JOYSTICK;
        this.stick = (b == Binding.RIGHT_JOYSTICK) ? Binding.RIGHT_JOYSTICK : Binding.LEFT_JOYSTICK;
        this.color = d.color != 0 ? d.color : DEFAULT_COLOR;
        this.sensitivity = d.stickSensitivity > 0f ? clamp(d.stickSensitivity, MIN_SENS, MAX_SENS)
                : (stick == Binding.RIGHT_JOYSTICK ? DEFAULT_RIGHT : DEFAULT_LEFT);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
    }

    private float outerR() { return OUTER_R * view.pixelScale() * scale; }
    private float knobR()  { return INNER_R * view.pixelScale() * scale; }

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
        // Zomdroid: the knob travels to the ring, and an axis is full at r / sqrt(2), so a diagonal
        // push reaches (1, 1) like a real stick's square gate.
        float dx = x - centerX(), dy = y - centerY();
        float max = outerR();
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > max && len > 0.001f) { float k = max / len; dx *= k; dy *= k; }
        knobX = dx; knobY = dy;
        // Normalise first (the sqrt(2) gate is what makes a diagonal push reach (1, 1)), clamp, and
        // only then scale: scaling before the clamp would just saturate earlier and change nothing
        // above ~71%.
        float k = max > 0 ? (float) Math.sqrt(2) / max : 0f;
        view.injectStick(stick, clamp(dx * k, -1f, 1f) * sensitivity,
                                clamp(dy * k, -1f, 1f) * sensitivity);
    }

    private void center() {
        if (knobX != 0f || knobY != 0f || pointerId >= 0) view.injectStick(stick, 0f, 0f);
        knobX = knobY = 0f;
    }

    @Override public void reset() { center(); pointerId = -1; }

    @Override public void draw(Canvas c) {
        float cx = centerX(), cy = centerY(), ps = view.pixelScale();
        int rgb = color & 0x00FFFFFF;
        float sw = 3f * (float) Math.sqrt(scale);
        // ring: dark under-outline, then the coloured contour
        stroke.setColor(OUTLINE_COLOR | (OUTLINE_ALPHA << 24));
        stroke.setStrokeWidth(sw + 5f * ps);
        c.drawCircle(cx, cy, outerR(), stroke);
        stroke.setColor(highlighted ? 0xFF33C0FF : (rgb | (alpha << 24)));
        stroke.setStrokeWidth(highlighted ? sw * 1.7f : sw);
        c.drawCircle(cx, cy, outerR(), stroke);
        // knob: dark outline, then the fill
        stroke.setColor(OUTLINE_COLOR | (OUTLINE_ALPHA << 24));
        stroke.setStrokeWidth(2f * ps);
        c.drawCircle(cx + knobX, cy + knobY, knobR(), stroke);
        fill.setColor(rgb | (alpha << 24));
        c.drawCircle(cx + knobX, cy + knobY, knobR(), fill);
    }

    @Override public ControlElementDescription describe() {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "STICK";
        d.bindings = new String[]{ stick.name() };
        d.color = color;
        d.stickSensitivity = sensitivity;
        return baseDescribe(d);
    }

    @Override public String editorLabel() {
        return stick == Binding.RIGHT_JOYSTICK ? "Gamepad right stick" : "Gamepad left stick";
    }

    public float getSensitivity() { return sensitivity; }
    public void setSensitivity(float s) { sensitivity = clamp(s, MIN_SENS, MAX_SENS); }

    public boolean isRightStick() { return stick == Binding.RIGHT_JOYSTICK; }
    public void setRightStick(boolean right) {
        reset();
        stick = right ? Binding.RIGHT_JOYSTICK : Binding.LEFT_JOYSTICK;
    }
}

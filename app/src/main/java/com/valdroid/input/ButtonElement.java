package com.valdroid.input;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * A tappable on-screen button bound to a single {@link Binding} (mouse button, scroll,
 * or key). Hold mode (default): injects press on touch-down, release on touch-up.
 * Toggle mode: first tap latches the binding pressed, second tap releases it.
 * Mouse-button bindings act at the shared cursor (set by {@link MouseStickElement}).
 */
public class ButtonElement extends ControlElement {

    public enum Shape { RECT, CIRCLE }

    private static final float RECT_W_DP = 120f, RECT_H_DP = 66f;
    private static final float CIRCLE_D_DP = 92f;
    // Zomdroid look (ButtonControlDrawable): sizes in units of view width / 2560, outlined shape.
    private static final float Z_CIRCLE_D = 160f, Z_RECT_W = 240f, Z_RECT_H = 120f;
    private static final float Z_STROKE = 4f, Z_OUTLINE_EXTRA = 1.25f, Z_TEXT_OUTLINE = 2.5f;
    private static final int   Z_OUTLINE_COLOR = 0xFF282828, Z_OUTLINE_ALPHA = 70, Z_TOGGLE_ON = 0xFFFFA726;

    private Shape shape;
    private String text;
    private boolean isToggle;
    private Binding binding;
    private int color;            // 0 = ValDroid look; otherwise Zomdroid look in this colour
    private String icon;          // Zomdroid icon name or null
    private android.graphics.drawable.Drawable iconDrawable;

    private int pointerId = -1;
    private boolean toggledOn = false;

    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ButtonElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        this.shape = "CIRCLE".equalsIgnoreCase(d.shape) ? Shape.CIRCLE : Shape.RECT;
        this.text = d.text != null ? d.text : "";
        this.isToggle = d.isToggle;
        this.binding = (d.bindings != null && d.bindings.length > 0)
                ? Binding.fromName(d.bindings[0], Binding.MOUSE_LEFT) : Binding.MOUSE_LEFT;
        this.color = d.color;
        this.icon = d.icon;
        int iconRes = "GAMEPAD_BACK_ICON".equals(icon) ? com.valdroid.R.drawable.mt_icon_stack
                    : "GAMEPAD_START_ICON".equals(icon) ? com.valdroid.R.drawable.mt_icon_menu : 0;
        if (iconRes != 0) {
            iconDrawable = androidx.core.content.ContextCompat.getDrawable(view.getContext(), iconRes);
            if (iconDrawable != null) iconDrawable = iconDrawable.mutate();
        }
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private boolean zomLook() { return color != 0; }

    private float halfW() {
        if (zomLook()) return (shape == Shape.RECT ? Z_RECT_W : Z_CIRCLE_D) / 2f * view.pixelScale() * scale;
        return dp((shape == Shape.RECT ? RECT_W_DP : CIRCLE_D_DP) / 2f) * scale;
    }
    private float halfH() {
        if (zomLook()) return (shape == Shape.RECT ? Z_RECT_H : Z_CIRCLE_D) / 2f * view.pixelScale() * scale;
        return dp((shape == Shape.RECT ? RECT_H_DP : CIRCLE_D_DP) / 2f) * scale;
    }

    // Touch-slop padding around the visible button: a finger landing just outside still counts as a
    // hit, so the overlay claims the touch (instead of it falling through to the map-pan gesture,
    // which made buttons feel hard to press). Affects hit-testing only, not drawing.
    private static final float TOUCH_SLOP_DP = 12f;

    @Override public boolean isPointOver(float x, float y) {
        float cx = centerX(), cy = centerY();
        float slop = dp(TOUCH_SLOP_DP);
        if (shape == Shape.CIRCLE) {
            float r = halfW() + slop; float dx = x - cx, dy = y - cy;
            return dx * dx + dy * dy <= r * r;
        }
        return Math.abs(x - cx) <= halfW() + slop && Math.abs(y - cy) <= halfH() + slop;
    }

    @Override public boolean handleTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerId < 0 && isPointOver(e.getX(idx), e.getY(idx))) {
                    pointerId = pid;
                    view.maybeHaptic();   // light tick on press, only if the user enabled haptics
                    if (isToggle) {
                        toggledOn = !toggledOn;
                        view.inject(binding, toggledOn);
                    } else {
                        view.inject(binding, true);
                    }
                    view.invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = (action == MotionEvent.ACTION_CANCEL);
                if (cancel || pid == pointerId) {
                    if (!isToggle) view.inject(binding, false);
                    pointerId = -1;
                    view.invalidate();
                    return !cancel;
                }
                return false;
            }
        }
        return false;
    }

    @Override public void reset() {
        if (pointerId >= 0 && !isToggle) view.inject(binding, false);
        if (isToggle && toggledOn) { view.inject(binding, false); toggledOn = false; }
        pointerId = -1;
    }

    /** Clear a stale HOLD press but keep an intentional toggle latch (it should survive across taps). */
    @Override public void clearStalePointer() {
        if (pointerId >= 0 && !isToggle) view.inject(binding, false);
        pointerId = -1;
    }

    private void drawShape(Canvas c, float cx, float cy, float hw, float hh, Paint p) {
        if (shape == Shape.CIRCLE) { c.drawCircle(cx, cy, hw, p); return; }
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float r = 20f * view.pixelScale() * scale;
        c.drawRoundRect(rect, r, r, p);
    }

    /** Zomdroid's ButtonControlDrawable: dark under-outline, coloured contour, text or icon fitted inside. */
    private void drawZomdroid(Canvas c) {
        float cx = centerX(), cy = centerY(), hw = halfW(), hh = halfH(), ps = view.pixelScale();
        boolean on = isToggle && toggledOn;
        int rgb = color & 0x00FFFFFF;
        float sw = Z_STROKE * ps * (float) Math.sqrt(scale);
        if (pointerId >= 0) {   // pressed: a light fill, so a touch is visible
            fill.setColor(rgb | ((alpha / 2) << 24));
            drawShape(c, cx, cy, hw, hh, fill);
        }
        stroke.setColor((Z_OUTLINE_COLOR & 0x00FFFFFF) | (Math.min(alpha, Z_OUTLINE_ALPHA) << 24));
        stroke.setStrokeWidth(sw + Z_OUTLINE_EXTRA * ps);
        drawShape(c, cx, cy, hw, hh, stroke);
        stroke.setColor(on ? Z_TOGGLE_ON : (rgb | (alpha << 24)));
        stroke.setStrokeWidth(on ? sw * 1.7f : sw);
        if (highlighted) { stroke.setColor(0xFF33C0FF); stroke.setStrokeWidth(sw * 1.7f); }
        drawShape(c, cx, cy, hw, hh, stroke);

        // content box: 0.8 of the rect, or the square inscribed in 0.8 of the circle
        float bw = (shape == Shape.RECT ? hw * 0.8f : hw * 0.8f / (float) Math.sqrt(2));
        float bh = (shape == Shape.RECT ? hh * 0.8f : hw * 0.8f / (float) Math.sqrt(2));
        if (iconDrawable != null) {
            float ia = (float) iconDrawable.getIntrinsicWidth() / Math.max(1, iconDrawable.getIntrinsicHeight());
            float w = bw, h = bw / ia;
            if (h > bh) { h = bh; w = bh * ia; }
            iconDrawable.setBounds(Math.round(cx - w), Math.round(cy - h), Math.round(cx + w), Math.round(cy + h));
            iconDrawable.setTint(on ? Z_TOGGLE_ON : (rgb | 0xFF000000));
            iconDrawable.setAlpha(alpha);
            iconDrawable.draw(c);
        } else if (!text.isEmpty()) {
            textPaint.setTextSize(100f);
            android.graphics.Rect tb = new android.graphics.Rect();
            textPaint.getTextBounds(text, 0, text.length(), tb);
            float k = Math.min(2f * bw / Math.max(1, tb.width()), 2f * bh / Math.max(1, tb.height()));
            textPaint.setTextSize(100f * k);
            textPaint.getTextBounds(text, 0, text.length(), tb);
            float ty = cy - tb.exactCenterY(), o = Z_TEXT_OUTLINE * ps;
            textPaint.setColor((Z_OUTLINE_COLOR & 0x00FFFFFF) | (Math.min(alpha, Z_OUTLINE_ALPHA) << 24));
            c.drawText(text, cx - o, ty, textPaint); c.drawText(text, cx + o, ty, textPaint);
            c.drawText(text, cx, ty - o, textPaint); c.drawText(text, cx, ty + o, textPaint);
            textPaint.setColor(on ? Z_TOGGLE_ON : (rgb | (alpha << 24)));
            c.drawText(text, cx, ty, textPaint);
        }
    }

    @Override public void draw(Canvas c) {
        if (zomLook()) { drawZomdroid(c); return; }
        float cx = centerX(), cy = centerY(), hw = halfW(), hh = halfH();
        boolean active = (pointerId >= 0) || (isToggle && toggledOn);
        int baseA = alpha;
        fill.setColor(0x00FFFFFF | ((active ? Math.min(255, baseA + 60) : (int)(baseA * 0.35f)) << 24));
        stroke.setColor(0x00FFFFFF | (Math.min(255, baseA + 40) << 24));
        stroke.setStrokeWidth(dp(2));
        if (highlighted) { stroke.setColor(0xFF33C0FF); stroke.setStrokeWidth(dp(3)); }

        if (shape == Shape.CIRCLE) {
            c.drawCircle(cx, cy, hw, fill);
            c.drawCircle(cx, cy, hw, stroke);
        } else {
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            float r = dp(10) * scale;
            c.drawRoundRect(rect, r, r, fill);
            c.drawRoundRect(rect, r, r, stroke);
        }
        if (!text.isEmpty()) {
            textPaint.setColor(0x00FFFFFF | (Math.min(255, baseA + 75) << 24));
            float ts = Math.min(hh, hw) * 0.7f;
            textPaint.setTextSize(ts);
            // Shrink to fit the button width so multi-char labels (e.g. "RBC") stay inside.
            float maxW = (shape == Shape.CIRCLE ? hw * 1.4f : hw * 1.7f);
            float tw = textPaint.measureText(text);
            if (tw > maxW && tw > 0) { ts *= maxW / tw; textPaint.setTextSize(ts); }
            float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            c.drawText(text, cx, ty, textPaint);
        }
    }

    @Override public ControlElementDescription describe() {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "BUTTON";
        d.shape = shape.name();
        d.text = text;
        d.isToggle = isToggle;
        d.bindings = new String[]{ binding.name() };
        d.color = color;
        d.icon = icon;
        return baseDescribe(d);
    }

    @Override public boolean isGamepadElement() { return binding.isGamepad(); }

    @Override public String editorLabel() { return "Button: " + (text.isEmpty() ? binding.label : text); }

    // --- editor setters ---
    public Binding getBinding() { return binding; }
    public void setBinding(Binding b) { reset(); this.binding = b; }
    public boolean isToggle() { return isToggle; }
    public void setToggle(boolean t) { reset(); this.isToggle = t; }
    public String getText() { return text; }
    public void setText(String t) { this.text = t != null ? t : ""; }
    public Shape getShape() { return shape; }
    public void setShape(Shape s) { this.shape = s; }
}

package com.valdroid.input;

import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;

/**
 * The virtual gamepad's d-pad as one element, ported from Zomdroid's DpadControlElement with its
 * geometry and look: a 340-unit square (units of view width / 2560) holding four outlined arrow
 * arms; the touch position inside the square presses the arms whose axis is past the dead zone
 * (diagonals press two). The result goes out as the pad's hat through
 * {@link InputControlsView#injectDpad}.
 */
public class DpadElement extends ControlElement {

    private static final float SIZE = 340f;
    private static final float DEAD = 0.3f;
    private static final int   DEFAULT_COLOR = 0xFFCCCCCC, OUTLINE_COLOR = 0x00282828, OUTLINE_ALPHA = 70;

    private final int color;
    private int pointerId = -1;
    private int mask = 0;          // up 1, right 2, down 4, left 8

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public DpadElement(InputControlsView view, ControlElementDescription d) {
        super(view, d);
        this.color = d.color != 0 ? d.color : DEFAULT_COLOR;
        paint.setStyle(Paint.Style.STROKE);
    }

    private float half() { return SIZE * view.pixelScale() * scale / 2f; }

    @Override public boolean isGamepadElement() { return true; }

    @Override public boolean isPointOver(float x, float y) {
        float h = half();
        return Math.abs(x - centerX()) <= h && Math.abs(y - centerY()) <= h;
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
        float h = half();
        float nx = clamp((x - centerX()) / h, -1f, 1f), ny = clamp((y - centerY()) / h, -1f, 1f);
        int m = 0;
        if (ny < -DEAD) m |= 1;
        if (nx >  DEAD) m |= 2;
        if (ny >  DEAD) m |= 4;
        if (nx < -DEAD) m |= 8;
        setMask(m);
    }

    private void setMask(int m) {
        if (m == mask) return;
        view.injectDpad(mask, m);
        mask = m;
        view.invalidate();
    }

    @Override public void reset() { setMask(0); pointerId = -1; }

    /** Zomdroid's four arrow arms (DpadControlDrawable.calculatePath). */
    private void buildPath() {
        float cx = centerX(), cy = centerY(), size = half() * 2f;
        float x0 = cx - size / 2f, y0 = cy - size / 2f;
        float hw = size / 6f, hh = size / 4f, off = size / 12f;
        path.reset();
        path.moveTo(cx, cy - off); path.lineTo(cx - hw, cy - hh); path.lineTo(cx - hw, y0);
        path.lineTo(cx + hw, y0); path.lineTo(cx + hw, cy - hh); path.close();
        path.moveTo(cx - off, cy); path.lineTo(cx - hh, cy - hw); path.lineTo(x0, cy - hw);
        path.lineTo(x0, cy + hw); path.lineTo(cx - hh, cy + hw); path.close();
        path.moveTo(cx, cy + off); path.lineTo(cx - hw, cy + hh); path.lineTo(cx - hw, y0 + size);
        path.lineTo(cx + hw, y0 + size); path.lineTo(cx + hw, cy + hh); path.close();
        path.moveTo(cx + off, cy); path.lineTo(cx + hh, cy - hw); path.lineTo(x0 + size, cy - hw);
        path.lineTo(x0 + size, cy + hw); path.lineTo(cx + hh, cy + hw); path.close();
    }

    @Override public void draw(Canvas c) {
        buildPath();
        float sw = 4f * (float) Math.sqrt(scale);
        paint.setPathEffect(new CornerPathEffect(15f * scale));
        paint.setColor(OUTLINE_COLOR | (Math.min(alpha, OUTLINE_ALPHA) << 24));
        paint.setStrokeWidth(sw + 1.25f * view.pixelScale());
        c.drawPath(path, paint);
        paint.setColor(highlighted ? 0xFF33C0FF : ((color & 0x00FFFFFF) | (alpha << 24)));
        paint.setStrokeWidth(highlighted ? sw * 1.7f : sw);
        c.drawPath(path, paint);
    }

    @Override public ControlElementDescription describe() {
        ControlElementDescription d = new ControlElementDescription();
        d.type = "DPAD";
        d.color = color;
        return baseDescribe(d);
    }

    @Override public String editorLabel() { return "Gamepad D-pad"; }
}

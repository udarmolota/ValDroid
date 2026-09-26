// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.controls;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import android.graphics.Path;

public class TouchpadControlElement extends AbstractControlElement {

    private static final String TAG = "ValDroid/Touchpad";

    private static final float BASE_WIDTH  = 560f;
    private static final float BASE_HEIGHT = 370f;
    private static final float TAP_SLOP    = 12f;
    private static final long  TAP_MAX_MS  = 250;

    private float sensitivity;
    // See ControlElementDescription.tapDisabled - true turns the pad into a pure cursor mover.
    private boolean tapDisabled;

    private final TouchpadDrawable drawable;
    private int    pointerId = -1;
    private float  lastX, lastY;
    private float  downX, downY;
    private long   downTime;
    // The cursor lives in InputControlsView, shared with the mouse stick and a physical mouse.

    public TouchpadControlElement(InputControlsView parentView,
                                  ControlElementDescription description) {
        super(parentView, description);
        this.sensitivity = (description.sensitivity > 0f) ? description.sensitivity : ControlElementDescription.DEFAULT_SENSITIVITY;
        this.tapDisabled = description.tapDisabled;
        this.drawable = new TouchpadDrawable(parentView, description);
    }

    @Override
    public void setInputType(InputType inputType) {
        this.inputType = inputType;
    }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action   = e.getActionMasked();
        int actIndex = e.getActionIndex();
        int pid      = e.getPointerId(actIndex);

        // Log every event so we can confirm delivery
        /*Log.v(TAG, "handleMotionEvent action=" + action
                + " pid=" + pid + " trackedPid=" + pointerId
                + " xy=(" + e.getX(actIndex) + "," + e.getY(actIndex) + ")"
                + " rect=" + drawable.rect);
        */
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pointerId >= 0) {
                    return false;   // already tracking a finger
                }
                float x = e.getX(actIndex);
                float y = e.getY(actIndex);
                boolean over = drawable.isPointOver(x, y);
                //Log.d(TAG, "DOWN isPointOver=" + over
                //        + " touch=(" + x + "," + y + ")"
                //        + " rect=" + drawable.rect);
                if (!over) return false;

                pointerId = pid;
                lastX = x;  lastY = y;
                downX = x;  downY = y;
                downTime = System.currentTimeMillis();

                // No move on touch-down: re-sending an unchanged position would still drag a
                // pointer the game has warped (mouse look) back to the overlay cursor.
                parentView.invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (pointerId < 0) return false;
                int idx = e.findPointerIndex(pointerId);
                if (idx < 0) { pointerId = -1; return false; }

                float x = e.getX(idx);
                float y = e.getY(idx);
                float dx = (x - lastX) * sensitivity;
                float dy = (y - lastY) * sensitivity;
                lastX = x;  lastY = y;

                if (dx != 0f || dy != 0f) parentView.moveCursorBy(dx, dy);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (pid != pointerId) return false;
                pointerId = -1;

                float totalDist = dist(e.getX(actIndex), e.getY(actIndex), downX, downY);
                long elapsed    = System.currentTimeMillis() - downTime;
                boolean isTap   = totalDist < TAP_SLOP && elapsed < TAP_MAX_MS && !tapDisabled;
                //Log.d(TAG, "UP dist=" + totalDist + " elapsed=" + elapsed
                //        + "ms isTap=" + isTap);

                if (isTap) {
                    parentView.clickCursor(GLFWBinding.MOUSE_BUTTON_LEFT);
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                //Log.d(TAG, "CANCEL");
                pointerId = -1;
                return true;
            }
        }
        return false;
    }

    @Override
    public void draw(Canvas canvas) {
        drawable.draw(canvas);
    }

    @Override
    public void releasePointer() {
        pointerId = -1;
    }

    @Override
    public float getCenterY() {
        return drawable.centerY;
    }

    @Override public boolean isPointOver(float x, float y)      { return drawable.isPointOver(x, y); }
    @Override public void setHighlighted(boolean h)             { drawable.setColorFilter(h ? HIGHLIGHT_COLOR_FILTER : null); parentView.invalidate(); }
    @Override public void setAlpha(int alpha)                   { drawable.setAlpha(alpha); parentView.invalidate(); }
    @Override public int getAlpha()                             { return drawable.alpha; }
    @Override public void setScale(float scale)                 { drawable.setScale(clamp(scale, MIN_SCALE, MAX_SCALE)); parentView.invalidate(); }
    @Override public float getScale()                           { return drawable.scale; }
    @Override public void setCenterPosition(float x, float y)  { drawable.setCenterPosition(x, y); parentView.invalidate(); }
    @Override public void moveCenterPosition(float dx, float dy){ drawable.moveCenterPosition(dx, dy); parentView.invalidate(); }
    @Override public float getCenterX()                         { return drawable.centerX; }

    public void setSensitivity(float s) {
        this.sensitivity = clamp(s, ControlElementDescription.MIN_SENSITIVITY, ControlElementDescription.MAX_SENSITIVITY);
    }

    public float getSensitivity() {
        return sensitivity;
    }

    public void setTapDisabled(boolean tapDisabled) {
        this.tapDisabled = tapDisabled;
    }

    public boolean isTapDisabled() {
        return tapDisabled;
    }

    @Override
    public ControlElementDescription describe() {
        return new ControlElementDescription(
                drawable.centerX / parentView.getWidth(),
                drawable.centerY / parentView.getHeight(),
                drawable.scale, Type.TOUCHPAD,
                new GLFWBinding[0], null,
                drawable.color, drawable.alpha,
                InputType.MNK,
                ControlElementDescription.Icon.NO_ICON, false,
                sensitivity, ControlElementDescription.DEFAULT_STYLE, null, false,
                tapDisabled);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1-x2, dy = y1-y2;
        return (float) Math.sqrt(dx*dx + dy*dy);
    }

    // -------------------------------------------------------------------------

    static class TouchpadDrawable {
        private static final int   CORNER_RADIUS_DP = 16;
        private static final float STROKE_WIDTH_DP  = 2f;

        int   color, alpha;
        float scale, centerX, centerY;
        private float halfW, halfH;
        final RectF rect = new RectF();

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final InputControlsView parentView;

        TouchpadDrawable(InputControlsView parentView, ControlElementDescription desc) {
            this.parentView = parentView;
            fillPaint.setStyle(Paint.Style.FILL);
            rimPaint .setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            setColor(desc.color);
            setAlpha(desc.alpha);
            setScale(desc.scale);
            setCenterPosition(desc.centerXRelative * parentView.getWidth(),
                    desc.centerYRelative * parentView.getHeight());
        }

        void draw(Canvas canvas) {
            float cr = CORNER_RADIUS_DP * parentView.pixelScale * scale;
            rimPaint.setColor(Color.rgb(30, 30, 30));
            rimPaint.setAlpha(80);
            rimPaint.setStrokeWidth((STROKE_WIDTH_DP + 3f) * parentView.pixelScale);
            canvas.drawRoundRect(rect, cr, cr, rimPaint);
            canvas.drawRoundRect(rect, cr, cr, fillPaint);
            rimPaint.setColor(color);
            rimPaint.setAlpha(alpha);
            rimPaint.setStrokeWidth(STROKE_WIDTH_DP * parentView.pixelScale);
            canvas.drawRoundRect(rect, cr, cr, rimPaint);
            float textSize = 28f * parentView.pixelScale * scale;
            textPaint.setTextSize(textSize);
            textPaint.setAlpha(Math.min(255, alpha + 60));
            canvas.drawText("TOUCH", centerX, centerY + textSize * 0.35f, textPaint);
        }

        void setColor(int c) { color=c; fillPaint.setColor(c); rimPaint.setColor(c); textPaint.setColor(c); }
        void setAlpha(int a) { alpha=a; fillPaint.setAlpha(a/3); rimPaint.setAlpha(a); textPaint.setAlpha(Math.min(255,a+60)); }
        void setColorFilter(@Nullable ColorFilter cf) { fillPaint.setColorFilter(cf); rimPaint.setColorFilter(cf); }
        void setScale(float s) { scale=s; updateDimensions(); }
        void setCenterPosition(float x, float y) { centerX=x; centerY=y; updateBounds(); }
        void moveCenterPosition(float dx, float dy) { setCenterPosition(centerX+dx, centerY+dy); }
        boolean isPointOver(float x, float y) { return rect.contains(x, y); }

        private void updateDimensions() {
            halfW = BASE_WIDTH  * parentView.pixelScale * scale / 2f;
            halfH = BASE_HEIGHT * parentView.pixelScale * scale / 2f;
            updateBounds();
        }
        private void updateBounds() {
            rect.set(centerX-halfW, centerY-halfH, centerX+halfW, centerY+halfH);
        }
    }
}

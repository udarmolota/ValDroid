package com.valdroid.controls;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

/**
 * Drawing for {@link ControlElementDescription.Style#CONSOLE}, shared by the buttons, the sticks
 * and the d-pad so they read as one set: a dark see-through body, a thin ring in the element's
 * colour with a soft glow around it, and a white label (the look of console streaming apps).
 *
 * Every alpha below is a fraction of the element's own alpha, so the opacity slider keeps working
 * and 0 still draws nothing (the editor then shows its dashed marker as for any style).
 *
 * The glow is two wider, fainter strokes under the thin ring rather than a BlurMaskFilter: the
 * overlay view is hardware accelerated, where mask filters are not supported before API 28 and
 * cost a software pass after it. A stroke is a single cheap draw call on any GPU.
 *
 * One painter per element; paints are allocated once, so nothing is created while drawing.
 */
final class ConsolePainter {
    // Body: black, at this share of the element alpha (more while pressed).
    private static final float FILL_K = 0.6f;
    private static final float FILL_PRESSED_K = 0.8f;
    // Pressed: a wash of the element colour over the dark body, so a tap is visible under the thumb.
    private static final float PRESS_TINT_K = 0.25f;
    // Ring and glow. The ring is brighter than the element alpha so the default 50% opacity still
    // gives a crisp outline; the label is brighter still, like the white letters in the reference.
    private static final float RING_K = 1.4f;
    private static final float RING_PRESSED_K = 1.9f;
    private static final float GLOW_WIDE_K = 0.10f;
    private static final float GLOW_MID_K = 0.22f;
    private static final float GLOW_PRESSED_BOOST = 1.8f;
    private static final float GLOW_WIDE_WIDTH = 5f;   // multiples of the ring width
    private static final float GLOW_MID_WIDTH = 2.5f;
    static final float LABEL_K = 1.6f;
    // Ring width at pixelScale 1 and element scale 1 (the view is laid out for 2560 px wide).
    private static final float RING_WIDTH = 2.5f;

    private static final int SHAPE_CIRCLE = 0;
    private static final int SHAPE_ROUND_RECT = 1;
    private static final int SHAPE_PATH = 2;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float labelCenterOffset;   // baseline shift that centres the label vertically
    private PathEffect pathEffect;

    private int shape = SHAPE_CIRCLE;
    private float cx, cy, radius;
    private RectF rect;
    private float corner;
    private Path path;

    ConsolePainter() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        label.setStyle(Paint.Style.FILL);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /** Element alpha times a factor, kept in 0..255. */
    static int scaleAlpha(int alpha, float k) {
        int a = Math.round(alpha * k);
        return a < 0 ? 0 : (a > 255 ? 255 : a);
    }

    /** Thin ring width in px; follows the element's size the same way the other styles do. */
    static float ringWidth(float pixelScale, float scale) {
        return Math.max(1f, RING_WIDTH * pixelScale * (float) Math.sqrt(scale));
    }

    /** Dark text on a light fill, white otherwise, so a white stick knob keeps a readable label. */
    static int labelColorOn(int color) {
        float lum = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color);
        return lum > 160f ? Color.rgb(30, 30, 30) : Color.WHITE;
    }

    /** The editor's selection highlight: tints everything this painter draws. */
    void setColorFilter(@Nullable ColorFilter cf) {
        fill.setColorFilter(cf);
        stroke.setColorFilter(cf);
        label.setColorFilter(cf);
    }

    /** Rounded corners for path shapes (the composite d-pad), to match its stroked look. */
    void setPathEffect(@Nullable PathEffect effect) {
        pathEffect = effect;
        fill.setPathEffect(effect);
        stroke.setPathEffect(effect);
    }

    void setLabelSize(float px) {
        label.setTextSize(px);
        // ascent is negative: half of (ascent + descent) above the centre puts the glyphs in the middle.
        labelCenterOffset = -(label.ascent() + label.descent()) / 2f;
    }

    // ---- Shape of the next draw call ----

    void circle(float cx, float cy, float radius) {
        this.shape = SHAPE_CIRCLE;
        this.cx = cx;
        this.cy = cy;
        this.radius = radius;
    }

    void roundRect(RectF rect, float corner) {
        this.shape = SHAPE_ROUND_RECT;
        this.rect = rect;
        this.corner = corner;
    }

    void path(Path path) {
        this.shape = SHAPE_PATH;
        this.path = path;
    }

    private void drawShape(Canvas c, Paint p) {
        switch (shape) {
            case SHAPE_ROUND_RECT: c.drawRoundRect(rect, corner, corner, p); break;
            case SHAPE_PATH: c.drawPath(path, p); break;
            default: c.drawCircle(cx, cy, radius, p); break;
        }
    }

    private void strokeShape(Canvas c, int color, int alpha, float width) {
        if (alpha <= 0) return;
        stroke.setColor(color);
        stroke.setAlpha(alpha);
        stroke.setStrokeWidth(width);
        drawShape(c, stroke);
    }

    private void fillShape(Canvas c, int color, int alpha) {
        if (alpha <= 0) return;
        fill.setColor(color);
        fill.setAlpha(alpha);
        drawShape(c, fill);
    }

    /** Button / d-pad body: dark translucent fill, colour wash while pressed. */
    void drawBody(Canvas c, int color, int alpha, boolean pressed) {
        fillShape(c, Color.BLACK, scaleAlpha(alpha, pressed ? FILL_PRESSED_K : FILL_K));
        if (pressed) fillShape(c, color, scaleAlpha(alpha, PRESS_TINT_K));
    }

    /** Thin ring in the given colour over two faint wider strokes that read as a glow. */
    void drawRing(Canvas c, int color, int alpha, boolean pressed, float ringWidth) {
        float boost = pressed ? GLOW_PRESSED_BOOST : 1f;
        strokeShape(c, color, scaleAlpha(alpha, GLOW_WIDE_K * boost), ringWidth * GLOW_WIDE_WIDTH);
        strokeShape(c, color, scaleAlpha(alpha, GLOW_MID_K * boost), ringWidth * GLOW_MID_WIDTH);
        strokeShape(c, color, scaleAlpha(alpha, pressed ? RING_PRESSED_K : RING_K), ringWidth);
    }

    /** Stick base: the element colour at medium alpha, a soft colour glow and a light rim. */
    void drawStickBase(Canvas c, int color, int alpha, boolean pressed, float ringWidth) {
        fillShape(c, color, scaleAlpha(alpha, pressed ? 0.45f : 0.35f));
        strokeShape(c, color, scaleAlpha(alpha, pressed ? 0.30f : 0.18f), ringWidth * 4f);
        strokeShape(c, Color.WHITE, scaleAlpha(alpha, 0.7f), ringWidth);
    }

    /** Stick knob: filled with the element colour, white rim. */
    void drawStickKnob(Canvas c, int color, int alpha, float ringWidth) {
        fillShape(c, color, scaleAlpha(alpha, 0.8f));
        strokeShape(c, Color.WHITE, scaleAlpha(alpha, 1.2f), ringWidth * 1.3f);
    }

    /**
     * Solid fill of an arbitrary path in one colour (the d-pad arrows). Drawn without the corner
     * effect: the d-pad's corner radius would round a small arrow into a blob.
     */
    void fillPath(Canvas c, Path p, int color, int alpha) {
        if (alpha <= 0) return;
        fill.setColor(color);
        fill.setAlpha(alpha);
        fill.setPathEffect(null);
        c.drawPath(p, fill);
        fill.setPathEffect(pathEffect);
    }

    /** Label centred on (x, y), in the size given to {@link #setLabelSize}. */
    void drawLabel(Canvas c, String text, float x, float y, int color, int alpha) {
        if (text == null || alpha <= 0) return;
        label.setColor(color);
        label.setAlpha(alpha);
        c.drawText(text, x, y + labelCenterOffset, label);
    }

    /**
     * A filled triangle pointing along (dirX, dirY) from the centre, added to {@code out}. Used for
     * the d-pad arrows; built when the element moves or resizes, never while drawing.
     */
    static void addArrow(Path out, float cx, float cy, float dirX, float dirY, float length, float width) {
        // Apex a bit past the centre, base a bit behind it, so the triangle looks centred by area.
        float ax = cx + dirX * length * 0.55f, ay = cy + dirY * length * 0.55f;
        float bx = cx - dirX * length * 0.45f, by = cy - dirY * length * 0.45f;
        float px = -dirY * width / 2f, py = dirX * width / 2f;
        out.moveTo(ax, ay);
        out.lineTo(bx + px, by + py);
        out.lineTo(bx - px, by - py);
        out.close();
    }
}

// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.controls;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import java.io.File;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.graphics.drawable.shapes.RectShape;
import android.text.TextPaint;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;


import java.util.Arrays;

public class ButtonControlElement extends AbstractControlElement {
    private final ButtonControlDrawable drawable;
    private int pointerId = -1;
    private boolean isToggledOn = false;
    // Attention-color indicator shown while a toggle button is switched ON.
    private static final int TOGGLE_ON_COLOR = android.graphics.Color.parseColor("#FFA726");
    // The same ON colour for an icon in the CONSOLE style, made once instead of on every frame.
    private static final ColorFilter TOGGLE_ON_ICON_FILTER =
            new PorterDuffColorFilter(TOGGLE_ON_COLOR, PorterDuff.Mode.SRC_ATOP);

    // ---- Drag to look (see ControlElementDescription.dragLook) ----
    // Finger travel before looking starts, so a plain tap (a thumb always wobbles a little) never
    // nudges the camera.
    private static final float LOOK_SLOP_DP = 8f;
    // Virtual-stick radius at DEFAULT_SENSITIVITY: full deflection after this much finger travel.
    // Higher sensitivity shrinks it (faster turning), lower grows it.
    private static final float LOOK_STICK_RADIUS_DP = 64f;
    private static final float LOOK_STICK_MIN_RADIUS_DP = 12f;
    // Small radial dead zone so a resting thumb near the touch-down point doesn't drift the camera.
    private static final float LOOK_STICK_DEAD_ZONE = 0.08f;
    // MOUSE mode: cursor speed at full deflection (view dp per second, before the element's
    // sensitivity, which works through the radius above exactly as in the gamepad mode).
    private static final float LOOK_MOUSE_SPEED_DP_PER_S = 700f;

    // TOUCHPAD: the mouse moves by the finger's own travel (ControlElementDescription.dragLookTouchpad).
    private enum LookMode { NONE, MOUSE, STICK, TOUCHPAD }

    private boolean dragLook;
    private boolean dragLookTouchpad;
    private float lookLastX, lookLastY;         // TOUCHPAD mode: the finger at the previous move
    private float sensitivity;
    private LookMode lookMode = LookMode.NONE;   // decided on touch-down from the bindings
    private boolean lookActive;                 // the finger has left the slop circle
    private boolean lookStickSent;              // right-stick axes were written and must be zeroed
    private float lookDownX, lookDownY;         // touch-down point = virtual stick centre
    private float lookNx, lookNy;               // MOUSE mode: current deflection, -1..1 per axis
    private boolean lookTicking;                // MOUSE mode: lookTick is scheduled
    private long lookLastTickNs;

    public ButtonControlElement(InputControlsView parentView, ControlElementDescription elementDescription) {
        super(parentView, elementDescription);
        this.drawable = new ButtonControlDrawable(parentView, elementDescription);
        this.bindings.addAll(Arrays.asList(elementDescription.bindings));
        this.dragLook = elementDescription.dragLook;
        this.dragLookTouchpad = elementDescription.dragLookTouchpad;
        // Layouts from before buttons kept a sensitivity carry 0 or the default; both mean default.
        this.sensitivity = elementDescription.sensitivity > 0f
                ? clamp(elementDescription.sensitivity, ControlElementDescription.MIN_SENSITIVITY,
                        ControlElementDescription.MAX_SENSITIVITY)
                : ControlElementDescription.DEFAULT_SENSITIVITY;
    }

    public boolean isDragLook() {
        return dragLook;
    }

    public void setDragLook(boolean dragLook) {
        if (!dragLook) stopLook();
        this.dragLook = dragLook;
    }

    public boolean isDragLookTouchpad() {
        return dragLookTouchpad;
    }

    public void setDragLookTouchpad(boolean touchpad) {
        stopLook();
        this.dragLookTouchpad = touchpad;
    }

    public float getSensitivity() {
        return sensitivity;
    }

    public void setSensitivity(float s) {
        this.sensitivity = clamp(s, ControlElementDescription.MIN_SENSITIVITY, ControlElementDescription.MAX_SENSITIVITY);
    }

    /**
     * How a drag on this button turns the camera, from what the button actually sends: the input
     * type picks the dispatch path in dispatchEvent, the bindings say whether it sends anything.
     * A gamepad button must never move the mouse - Valheim switches its UI between mouse and
     * gamepad prompts by the last input, and mixing the two makes the UI flicker. UI actions
     * (overlay / keyboard toggles) never look. The touchpad style is the exception the player picks
     * knowingly: it is mouse movement whatever the button sends (the editor says the prompts may switch).
     */
    private LookMode resolveLookMode() {
        if (!dragLook) return LookMode.NONE;
        boolean mnk = false, pad = false;
        for (GLFWBinding b : bindings) {
            if (b == GLFWBinding.UI_TOGGLE_OVERLAY || b == GLFWBinding.UI_TOGGLE_KEYBOARD) return LookMode.NONE;
            String n = b.name();
            if (n.startsWith("MOUSE_") || n.startsWith("KEY_")) mnk = true;
            else if (n.startsWith("GAMEPAD_")) pad = true;
        }
        if (dragLookTouchpad) return (mnk || pad) ? LookMode.TOUCHPAD : LookMode.NONE;
        if (this.inputType == InputType.GAMEPAD) return pad ? LookMode.STICK : LookMode.NONE;
        if (this.inputType == InputType.MNK) return mnk ? LookMode.MOUSE : LookMode.NONE;
        return LookMode.NONE;
    }

    private float density() {
        return parentView.getResources().getDisplayMetrics().density;
    }

    private void startLook(float x, float y) {
        stopLook();   // a re-press by another finger re-centres; never leave the stick deflected
        lookMode = resolveLookMode();
        lookActive = false;
        lookDownX = x;
        lookDownY = y;
    }

    private void updateLook(float x, float y) {
        if (lookMode == LookMode.NONE) return;
        if (!lookActive) {
            float slop = LOOK_SLOP_DP * density();
            float ddx = x - lookDownX, ddy = y - lookDownY;
            if (ddx * ddx + ddy * ddy < slop * slop) return;
            lookActive = true;
            // The touchpad starts from here: the slop itself is not turned into a jump.
            lookLastX = x;
            lookLastY = y;
            if (lookMode == LookMode.TOUCHPAD) return;
        }
        if (lookMode == LookMode.TOUCHPAD) {
            // Like the touchpad element: finger travel times the sensitivity (the same scale).
            float dx = (x - lookLastX) * sensitivity, dy = (y - lookLastY) * sensitivity;
            lookLastX = x;
            lookLastY = y;
            if (dx != 0f || dy != 0f) parentView.moveCursorBy(dx, dy);
            return;
        }
        // Both modes are a virtual stick centred on the touch-down point: holding the finger off
        // centre keeps turning, like a right stick. A plain finger-travel mouse (the first version)
        // felt awful on a small button: the thumb runs out of room and turning comes in jerks.
        float radius = Math.max(LOOK_STICK_MIN_RADIUS_DP,
                LOOK_STICK_RADIUS_DP * ControlElementDescription.DEFAULT_SENSITIVITY / sensitivity) * density();
        float nx = (x - lookDownX) / radius;
        float ny = (y - lookDownY) / radius;
        float mag = (float) Math.sqrt(nx * nx + ny * ny);
        if (mag < LOOK_STICK_DEAD_ZONE) {
            nx = ny = 0f;
        } else {
            // Rescale past the dead zone so the output starts from 0, not with a step.
            float k = (mag - LOOK_STICK_DEAD_ZONE) / (1f - LOOK_STICK_DEAD_ZONE) / mag;
            nx = clamp(nx * k, -1f, 1f);
            ny = clamp(ny * k, -1f, 1f);
        }
        if (lookMode == LookMode.MOUSE) {
            // Mouse bindings: the deflection becomes a mouse speed, fed every frame by lookTick
            // (the game still sees a mouse, so its UI stays in mouse mode).
            lookNx = nx;
            lookNy = ny;
            if (!lookTicking) {
                lookTicking = true;
                lookLastTickNs = System.nanoTime();
                parentView.postOnAnimation(lookTick);
            }
        } else {
            InputSink.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RX.code, nx);
            InputSink.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RY.code, ny);
            lookStickSent = true;
        }
    }

    /** Per-frame mouse feed for the MOUSE look mode: deflection x speed x frame time. Goes through
     *  moveCursorBy, so it is mouse look while the game holds the mouse and a moving cursor in menus. */
    private final Runnable lookTick = new Runnable() {
        @Override public void run() {
            if (!lookTicking) return;
            long now = System.nanoTime();
            // Clamp the step: after a hitch, a long gap must not turn into one big camera jump.
            float dt = Math.min((now - lookLastTickNs) / 1e9f, 0.05f);
            lookLastTickNs = now;
            float speed = LOOK_MOUSE_SPEED_DP_PER_S * density();
            if (lookNx != 0f || lookNy != 0f)
                parentView.moveCursorBy(lookNx * speed * dt, lookNy * speed * dt);
            parentView.postOnAnimation(this);
        }
    };

    /** End a look gesture: the virtual stick springs back to centre and the mouse feed stops. */
    private void stopLook() {
        if (lookStickSent) {
            InputSink.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RX.code, 0f);
            InputSink.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RY.code, 0f);
            lookStickSent = false;
        }
        if (lookTicking) {
            lookTicking = false;
            parentView.removeCallbacks(lookTick);
        }
        lookNx = lookNy = 0f;
        lookActive = false;
        lookMode = LookMode.NONE;
    }

    @Override
    public void setInputType(InputType inputType) {
        if (inputType == null || inputType == this.inputType) return;
        clearBindings();
        this.inputType = inputType;
    }

    private void dispatchEvent(boolean isPressed) {
        for (GLFWBinding binding : bindings) {
            if (binding == GLFWBinding.UI_TOGGLE_OVERLAY) {
                if (isPressed) {
                    parentView.toggleOverlayVisibility();
                }
                return; // UI action: send nothing to the game, MNK or GAMEPAD
            }
            if (binding == GLFWBinding.UI_TOGGLE_KEYBOARD) {
                // Always Android's own keyboard (GameActivity.toggleSoftKeyboard); Zomdroid's
                // mini keyboard for physical-keyboard users is not ported.
                if (isPressed) parentView.showTextInputOverlay();
                return;
            }
        }

        switch (this.inputType) {
            case MNK:
                for (GLFWBinding binding : bindings) {
                    handleMNKBinding(binding, isPressed);
                }
                break;
            case GAMEPAD:
                for (GLFWBinding binding : bindings) {
                    handleGamepadBinding(binding, isPressed);
                }
                break;
        }
    }


    @Override
    public boolean handleMotionEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int actionIndex = e.getActionIndex();
        int pointerId = e.getPointerId(actionIndex);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(actionIndex);
                float y = e.getY(actionIndex);
                if (!this.drawable.isPointOver(x, y)) return false;
                this.pointerId = pointerId;
                startLook(x, y);
                maybeHaptic();

                if (getToggle()) {
                    if (isToggledOn) {
                        this.dispatchEvent(false);
                        isToggledOn = false;
                    } else {
                        this.dispatchEvent(true);
                        isToggledOn = true;
                    }
                } else {
                    this.dispatchEvent(true);
                }

                this.parentView.invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                // MOVE is broadcast to every element; follow only our own finger, which may by now
                // be far outside the button - that is the point of dragging to look.
                if (this.pointerId == -1 || lookMode == LookMode.NONE) return false;
                int pointerIndex = e.findPointerIndex(this.pointerId);
                if (pointerIndex < 0) return false;
                updateLook(e.getX(pointerIndex), e.getY(pointerIndex));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (pointerId != this.pointerId) return false;
                this.pointerId = -1;
                stopLook();
                if (!getToggle()) {
                    this.dispatchEvent(false);
                }
                // The CONSOLE style draws a pressed state, which has to go away on release.
                this.parentView.invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL: {
                if (this.pointerId != -1) {
                    this.pointerId = -1;
                    stopLook();
                    this.dispatchEvent(false);
                    // A system touch-cancel must also clear the toggle ON state, otherwise the
                    // orange ON indicator stays lit even though the key was released.
                    isToggledOn = false;
                    this.parentView.invalidate();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    @Override
    public void releasePointer() {
        if (this.pointerId == -1) return;
        this.pointerId = -1;
        stopLook();
        if (!getToggle()) this.dispatchEvent(false);
        this.parentView.invalidate();
    }

    @Override
    public void reset() {
        releasePointer();
        if (isToggledOn) {
            isToggledOn = false;
            this.dispatchEvent(false);
            this.parentView.invalidate();
        }
    }

    @Override
    public float getCenterX() {
        return this.drawable.centerX;
    }

    @Override
    public float getCenterY() {
        return this.drawable.centerY;
    }

    @Override
    public void draw(Canvas canvas) {
        this.drawable.draw(canvas);
    }

    @Override
    public boolean isPointOver(float x, float y) {
        return this.drawable.isPointOver(x, y);
    }

    @Override
    public void setHighlighted(boolean highlighted) {
        if (highlighted) {
            this.drawable.setColorFilter(this.HIGHLIGHT_COLOR_FILTER);
        } else {
            this.drawable.setColorFilter(null);
        }
        this.parentView.invalidate();
    }

    @Override
    public void setScale(float scale) {
        scale = clamp(scale, MIN_SCALE, MAX_SCALE);
        this.drawable.setScale(scale);
        this.parentView.invalidate();
    }

    @Override
    public float getScale() {
        return this.drawable.scale;
    }

    @Override
    public void setAlpha(int alpha) {
        this.drawable.setAlpha(alpha);
        this.parentView.invalidate();
    }

    @Override
    public int getAlpha() {
        return this.drawable.alpha;
    }

    @Override
    public void setCenterPosition(float x, float y) {
        this.drawable.setCenterPosition(x, y);
        this.parentView.invalidate();
    }

    @Override
    public void moveCenterPosition(float dx, float dy) {
        this.drawable.moveCenterPosition(dx, dy);
        this.parentView.invalidate();
    }

    @Override
    public void setText(String text) {
        this.drawable.setText(text);
        this.parentView.invalidate();
    }

    @Override
    public String getText() {
        return this.drawable.text;
    }

    @Override
    public void setIcon(ControlElementDescription.Icon icon) {
        this.drawable.setIcon(icon);
        this.parentView.invalidate();
    }

    @Override
    public ControlElementDescription.Icon getIcon() {
        return this.drawable.icon;
    }

    @Override
    public ControlElementDescription.Style[] getStyleChoices() {
        return ControlElementDescription.Style.values();
    }

    @Override
    public void setStyle(ControlElementDescription.Style style) {
        this.drawable.setStyle(style);
        this.parentView.invalidate();
    }

    @Override
    public ControlElementDescription.Style getStyle() {
        return this.drawable.getStyle();
    }

    public void setCustomIcon(String fileName, boolean noTint) {
        this.drawable.setCustomIcon(fileName, noTint);
        this.parentView.invalidate();
    }

    public String getIconFile() {
        return this.drawable.iconFile;
    }

    public boolean isNoTint() {
        return this.drawable.noTint;
    }

    public void setNoTint(boolean noTint) {
        if (this.drawable.iconFile != null) {
            this.drawable.setCustomIcon(this.drawable.iconFile, noTint);
        } else {
            this.drawable.noTint = noTint;
        }
        this.parentView.invalidate();
    }

    @Override
    public void addBinding(GLFWBinding binding) {
        this.bindings.add(binding);
    }

    @Override
    public void setBinding(int index, GLFWBinding binding) {
        this.bindings.set(index, binding);
    }

    @Override
    public void removeBinding(int index) {
        this.bindings.remove(index);
    }

    @Override
    public ControlElementDescription describe() {
        return new ControlElementDescription(
                this.drawable.centerX / this.parentView.getWidth(),
                this.drawable.centerY / this.parentView.getHeight(),
                // The element's own type, not one guessed from the shape: the guess turned the
                // split DPAD_* buttons into plain circles on save, and NPE'd on a shapeless one.
                this.drawable.scale, this.type,
                this.bindings.toArray(new GLFWBinding[0]), this.drawable.text, this.drawable.color,
                this.drawable.alpha,
                this.inputType,
                this.drawable.icon,
                this.isToggle,
                this.sensitivity,   // drag-to-look speed
                this.drawable.style,
                this.drawable.iconFile,
                this.drawable.noTint,
                false,
                this.dragLook,
                false,
                0f,
                this.dragLookTouchpad);
    }

    private static String defaultArrow(Type t) {
        switch (t) {
            case DPAD_UP: return "↑";
            case DPAD_RIGHT: return "→";
            case DPAD_DOWN: return "↓";
            case DPAD_LEFT: return "←";
            default: return null;
        }
    }

    public class ButtonControlDrawable {
        private static final int PAINT_STROKE_WIDTH = 4;
        private static final int OUTLINE_ALPHA = 70;          // 0..255
        //private static final float OUTLINE_EXTRA_PX = 5f;
        private static final float OUTLINE_EXTRA_PX = 1.25f; // or 1.5f
        private static final float TEXT_OUTLINE_PX = 2.5f; //4f;
        private static final float BUTTON_CIRCLE_DIAMETER = 160.f;
        private static final float BUTTON_RECT_WIDTH = 240.f;
        private static final float BUTTON_RECT_HEIGHT = 120.f;
        // CONSOLE style: rectangles become rounded ones, corner radius as a share of the height.
        private static final float CONSOLE_CORNER_K = 0.28f;

        private final Type type;
        private int color;
        private int alpha;
        private ColorFilter colorFilter;
        private float scale;
        private float width;
        private float height;
        private float centerX;
        private float centerY;
        private float x;
        private float y;
        private String text;
        private final TextPaint textPaint = new TextPaint();
        private float textY;
        private final ShapeDrawable shapeDrawable = new ShapeDrawable();
        private ControlElementDescription.Icon icon;
        private Drawable iconDrawable;
        private ControlElementDescription.Style style;
        private String iconFile;      // user image filename in controls/icons, or null
        private boolean noTint;       // keep custom image original colors when true
        // CONSOLE style geometry, updated with the bounds so drawing allocates nothing.
        private final ConsolePainter console = new ConsolePainter();
        private final RectF consoleRect = new RectF();
        private final Path consoleArrow = new Path();
        private float consoleRingWidth;

        public ButtonControlDrawable(InputControlsView parent, ControlElementDescription description) {
            this.type = description.type;

            setColor(description.color);
            setAlpha(description.alpha);

            this.colorFilter = null;
            this.style = (description.style != null) ? description.style : ControlElementDescription.DEFAULT_STYLE;
            applyStyleToText();

            // Split d-pad buttons (DPAD_UP...) are drawn as round buttons; Zomdroid gave them no
            // shape and no size, so they were invisible and could not be touched.
            if (description.type == Type.BUTTON_RECT) shapeDrawable.setShape(new RectShape());
            else shapeDrawable.setShape(new OvalShape());
            this.shapeDrawable.getPaint().setStyle(Paint.Style.STROKE);

            setScale(description.scale);
            setCenterPosition(description.centerXRelative * parentView.getWidth(),
                    description.centerYRelative * parentView.getHeight());

            this.textPaint.setStyle(Paint.Style.FILL);
            this.textPaint.setTextAlign(Paint.Align.CENTER);

            this.text = description.text;
            if (this.text == null) this.text = defaultArrow(description.type);
            setTextSizeToFit();

            setIcon(description.icon);
            if (description.iconFile != null && !description.iconFile.isEmpty()) {
                setCustomIcon(description.iconFile, description.noTint);
            }
        }

        public void draw(@NonNull Canvas canvas) {
            if (this.style == ControlElementDescription.Style.CONSOLE) {
                drawConsole(canvas);
                return;
            }
            // --- Outline pass (black, a bit thicker) ---
            Paint p = this.shapeDrawable.getPaint();

            // Capture the base state from the drawable's own fields, NOT from the paint:
            // the paint is mutated across passes (and the toggle-ON contour leaves it orange),
            // so reading p.getColor() here would leak the ON color into the next frame and keep
            // the contour permanently highlighted.
            int oldColor = this.color;
            int oldAlpha = this.alpha;
            float oldStroke = p.getStrokeWidth();
            ColorFilter oldFilter = this.colorFilter;

            // --- Fill pass (for FILLED / GLASS styles) ---
            if (this.style == ControlElementDescription.Style.FILLED
                    || this.style == ControlElementDescription.Style.GLASS) {
                Paint.Style oldPaintStyle = p.getStyle();
                p.setStyle(Paint.Style.FILL);
                p.setColor(oldColor);
                p.setAlpha(this.style == ControlElementDescription.Style.GLASS
                        ? Math.round(oldAlpha / 3f) : oldAlpha);
                p.setColorFilter(oldFilter);
                this.shapeDrawable.draw(canvas);
                // restore for the outline/normal stroke passes below
                p.setStyle(oldPaintStyle);
                p.setColor(oldColor);
                p.setAlpha(oldAlpha);
                p.setColorFilter(oldFilter);
            }

            boolean toggledOn = getToggle() && ButtonControlElement.this.isToggledOn && !parentView.isEditMode();

            // --- Toggle-ON FILL: only for filled styles. OUTLINE buttons get an orange contour below
            //     (so the ON indicator matches the button's drawing style). ---
            if (toggledOn && (this.style == ControlElementDescription.Style.FILLED
                    || this.style == ControlElementDescription.Style.GLASS)) {
                Paint.Style onStyle = p.getStyle();
                p.setStyle(Paint.Style.FILL);
                p.setColor(TOGGLE_ON_COLOR);
                p.setAlpha(200);
                p.setColorFilter(null);
                this.shapeDrawable.draw(canvas);
                p.setStyle(onStyle);
                p.setColor(oldColor);
                p.setAlpha(oldAlpha);
                p.setColorFilter(oldFilter);
            }

            p.setColor(android.graphics.Color.rgb(40, 40, 40));
            //p.setAlpha(OUTLINE_ALPHA);
            p.setAlpha(Math.min(oldAlpha, OUTLINE_ALPHA));
            p.setStrokeWidth(oldStroke + OUTLINE_EXTRA_PX * parentView.pixelScale);
            p.setColorFilter(null);
            this.shapeDrawable.draw(canvas);

            // --- Normal pass (contour). When toggled ON, draw the contour in the attention
            //     color and a bit thicker — this is the ON indicator for OUTLINE style. ---
            p.setColor(toggledOn ? TOGGLE_ON_COLOR : oldColor);
            p.setAlpha(oldAlpha);
            p.setStrokeWidth(toggledOn ? oldStroke * 1.7f : oldStroke);
            p.setColorFilter(toggledOn ? null : oldFilter);
            this.shapeDrawable.draw(canvas);
            // Restore the paint to the base state so nothing leaks into the next frame.
            p.setStrokeWidth(oldStroke);
            p.setColor(oldColor);
            p.setAlpha(oldAlpha);
            p.setColorFilter(oldFilter);

            if (this.iconDrawable != null) {
                if (toggledOn) {
                    // Tint the inner image/letter with the ON color too (not just the contour).
                    this.iconDrawable.setColorFilter(new android.graphics.PorterDuffColorFilter(
                            TOGGLE_ON_COLOR, android.graphics.PorterDuff.Mode.SRC_ATOP));
                    this.iconDrawable.draw(canvas);
                    this.iconDrawable.setColorFilter(this.colorFilter); // restore
                } else {
                    this.iconDrawable.draw(canvas);
                }
            } else if (this.text != null) {
                float o = TEXT_OUTLINE_PX * parentView.pixelScale;

                // save the state (from the fields, not from the mutated paint, see above)
                int oldTextColor = this.color;
                int oldTextAlpha = this.alpha;
                ColorFilter oldTextFilter = this.colorFilter;

                // outline pass: dark, no filter
                //this.textPaint.setColor(android.graphics.Color.BLACK);
                this.textPaint.setColor(android.graphics.Color.rgb(40, 40, 40));
                //this.textPaint.setAlpha(OUTLINE_ALPHA);
                this.textPaint.setAlpha(Math.min(oldAlpha, OUTLINE_ALPHA));
                this.textPaint.setColorFilter(null);

                canvas.drawText(this.text, this.centerX - o, this.textY, this.textPaint);
                canvas.drawText(this.text, this.centerX + o, this.textY, this.textPaint);
                canvas.drawText(this.text, this.centerX, this.textY - o, this.textPaint);
                canvas.drawText(this.text, this.centerX, this.textY + o, this.textPaint);

                // normal pass: restore (orange while a toggle is ON, like the contour)
                this.textPaint.setColor(toggledOn ? TOGGLE_ON_COLOR : oldTextColor);
                this.textPaint.setAlpha(oldTextAlpha);
                this.textPaint.setColorFilter(toggledOn ? null : oldTextFilter);
                canvas.drawText(this.text, this.centerX, this.textY, this.textPaint);
            }
        }

        /**
         * CONSOLE style: dark see-through body, thin ring in the button colour with a soft glow,
         * white label. Rectangles are drawn rounded. Pressed: darker body with a colour wash and a
         * brighter ring. Toggle ON: the ring, glow and label turn the ON colour, as in OUTLINE.
         */
        private void drawConsole(@NonNull Canvas canvas) {
            boolean editMode = parentView.isEditMode();
            boolean toggledOn = getToggle() && ButtonControlElement.this.isToggledOn && !editMode;
            boolean pressed = ButtonControlElement.this.pointerId != -1 && !editMode;
            int ringColor = toggledOn ? TOGGLE_ON_COLOR : this.color;

            if (this.type == Type.BUTTON_RECT) {
                this.console.roundRect(this.consoleRect, this.height * CONSOLE_CORNER_K);
            } else {
                this.console.circle(this.centerX, this.centerY, this.width / 2f);
            }
            this.console.drawBody(canvas, ringColor, this.alpha, pressed);
            this.console.drawRing(canvas, ringColor, this.alpha, pressed, this.consoleRingWidth);

            int labelAlpha = ConsolePainter.scaleAlpha(this.alpha, ConsolePainter.LABEL_K);
            if (this.iconDrawable != null) {
                this.iconDrawable.setAlpha(labelAlpha);
                if (toggledOn) {
                    this.iconDrawable.setColorFilter(TOGGLE_ON_ICON_FILTER);
                    this.iconDrawable.draw(canvas);
                    this.iconDrawable.setColorFilter(this.colorFilter);
                } else {
                    this.iconDrawable.draw(canvas);
                }
                this.iconDrawable.setAlpha(this.alpha);
            } else if (isDefaultArrow()) {
                // Split d-pad buttons: a white triangle instead of the arrow glyph.
                this.console.fillPath(canvas, this.consoleArrow,
                        toggledOn ? TOGGLE_ON_COLOR : android.graphics.Color.WHITE, labelAlpha);
            } else if (this.text != null) {
                this.textPaint.setColor(toggledOn ? TOGGLE_ON_COLOR : android.graphics.Color.WHITE);
                this.textPaint.setAlpha(labelAlpha);
                this.textPaint.setColorFilter(toggledOn ? null : this.colorFilter);
                canvas.drawText(this.text, this.centerX, this.textY, this.textPaint);
                this.textPaint.setColor(this.color);
                this.textPaint.setAlpha(this.alpha);
                this.textPaint.setColorFilter(this.colorFilter);
            }
        }

        /** A split d-pad button still showing its default arrow glyph (no own text or icon). */
        private boolean isDefaultArrow() {
            String arrow = defaultArrow(this.type);
            return arrow != null && arrow.equals(this.text);
        }

        /** The white triangle of a split d-pad button; empty for any other button. */
        private void updateConsoleArrow() {
            this.consoleArrow.reset();
            float dx = 0f, dy = 0f;
            switch (this.type) {
                case DPAD_UP: dy = -1f; break;
                case DPAD_RIGHT: dx = 1f; break;
                case DPAD_DOWN: dy = 1f; break;
                case DPAD_LEFT: dx = -1f; break;
                default: return;
            }
            ConsolePainter.addArrow(this.consoleArrow, this.centerX, this.centerY, dx, dy,
                    this.width * 0.28f, this.width * 0.32f);
        }

        /** Bold label in CONSOLE (as on a console pad), the regular one otherwise. */
        private void applyStyleToText() {
            this.textPaint.setTypeface(this.style == ControlElementDescription.Style.CONSOLE
                    ? Typeface.DEFAULT_BOLD : null);
        }

        /** CONSOLE labels are white, so built-in icons are too; other styles tint by the colour. */
        private int iconTint() {
            return this.style == ControlElementDescription.Style.CONSOLE
                    ? android.graphics.Color.WHITE : this.color;
        }

        public boolean isPointOver(float x, float y) {
            return x >= this.x && x <= this.x + this.width && y >= this.y && y <= this.y + this.height;
        }

        public void setColor(int color) {
            this.color = color;
            this.shapeDrawable.getPaint().setColor(this.color);
            this.textPaint.setColor(this.color);
            if (this.iconDrawable != null && shouldTintIcon())
                iconDrawable.setTint(iconTint());
        }

        private boolean shouldTintIcon() {
            // Built-in icons are always tinted by the button color; custom images
            // are tinted unless the user asked to keep their original colors.
            return this.iconFile == null || !this.noTint;
        }

        public void setAlpha(int alpha) {
            this.alpha = alpha;
            this.shapeDrawable.getPaint().setAlpha(this.alpha);
            this.textPaint.setAlpha(this.alpha);
            if (this.iconDrawable != null)
                iconDrawable.setAlpha(this.alpha);
        }

        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            this.shapeDrawable.getPaint().setColorFilter(this.colorFilter);
            this.textPaint.setColorFilter(this.colorFilter);
            this.console.setColorFilter(this.colorFilter);
            if (this.iconDrawable != null)
                this.iconDrawable.setColorFilter(this.colorFilter);
        }

        public void setStyle(ControlElementDescription.Style style) {
            this.style = (style != null) ? style : ControlElementDescription.DEFAULT_STYLE;
            // The label's weight and box and the icon's tint and box depend on the style.
            applyStyleToText();
            if (this.iconDrawable != null && shouldTintIcon()) this.iconDrawable.setTint(iconTint());
            setTextSizeToFit();
            updateIconDrawable();
        }

        public ControlElementDescription.Style getStyle() {
            return this.style;
        }

        public void setScale(float scale) {
            this.scale = scale;
            updateDimensions();
        }

        public void setCenterPosition(float x, float y) {
            this.centerX = x;
            this.centerY = y;
            updateBounds();
        }

        public void moveCenterPosition(float dx, float dy) {
            setCenterPosition(this.centerX + dx, this.centerY + dy);
        }

        public void setText(String text) {
            if (text == null || text.isEmpty()) text = null;
            this.text = text;
            setTextSizeToFit();
        }

        public void setIcon(ControlElementDescription.Icon icon) {
            if (icon == null) icon = ControlElementDescription.Icon.NO_ICON;
            this.icon = icon;
            // Choosing a built-in icon clears any custom image.
            this.iconFile = null;
            if (this.icon == null || this.icon == ControlElementDescription.Icon.NO_ICON) {
                this.iconDrawable = null;
            } else {
                Drawable shared = AppCompatResources.getDrawable(parentView.getContext(), icon.resId);
                if (shared == null) {
                    this.iconDrawable = null;
                } else {
                    this.iconDrawable = shared.mutate();
                    this.iconDrawable.setTint(iconTint());
                    this.iconDrawable.setAlpha(this.alpha);
                    this.iconDrawable.setColorFilter(this.colorFilter);
                    updateIconDrawable();
                }
            }
        }

        // Load a user-supplied image from controls/icons/<fileName> as the button icon.
        // Falls back to the built-in icon if the file is missing/undecodable.
        public void setCustomIcon(String fileName, boolean noTint) {
            this.noTint = noTint;
            if (fileName == null || fileName.isEmpty()) {
                this.iconFile = null;
                setIcon(this.icon);
                return;
            }
            File dir = parentView.getControlsIconsDir();
            File f = (dir != null) ? new File(dir, fileName) : null;
            Bitmap bmp = (f != null && f.isFile()) ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            if (bmp == null) {
                this.iconFile = null;
                setIcon(this.icon);
                return;
            }
            this.iconFile = fileName;
            BitmapDrawable bd = new BitmapDrawable(parentView.getResources(), bmp);
            if (shouldTintIcon()) {
                bd.setTint(iconTint());
            } else {
                bd.setTintList(null);
            }
            bd.setAlpha(this.alpha);
            bd.setColorFilter(this.colorFilter);
            this.iconDrawable = bd;
            updateIconDrawable();
        }

        private void updateDimensions() {
            if (this.type == Type.BUTTON_RECT) {
                this.width = BUTTON_RECT_WIDTH * parentView.pixelScale * this.scale;
                this.height = BUTTON_RECT_HEIGHT * parentView.pixelScale * this.scale;
            } else {
                this.width = this.height = BUTTON_CIRCLE_DIAMETER * parentView.pixelScale * this.scale;
            }
            updateBounds();
        }

        private void updateBounds() {
            this.x = this.centerX - this.width / 2;
            this.y = this.centerY - this.height / 2;

            this.shapeDrawable.setBounds(Math.round(this.x), Math.round(this.y),
                    Math.round(this.x + this.width), Math.round(this.y + this.height));
            this.shapeDrawable.getPaint().setStrokeWidth(PAINT_STROKE_WIDTH * parentView.pixelScale
                    * (float) Math.sqrt(this.scale));

            this.consoleRect.set(this.x, this.y, this.x + this.width, this.y + this.height);
            this.consoleRingWidth = ConsolePainter.ringWidth(parentView.pixelScale, this.scale);
            updateConsoleArrow();

            setTextSizeToFit();
            updateIconDrawable();
        }

        private void setTextSizeToFit() {
            if (this.text == null) return;

            RectF contentBounds = getContentBounds();

            float textSize = 5f;
            this.textPaint.setTextSize(textSize);

            Rect textBounds = new Rect();
            this.textPaint.getTextBounds(this.text, 0, this.text.length(), textBounds);

            while (textBounds.width() <= contentBounds.width() && textBounds.height() <= contentBounds.height()) {
                textSize += 1f;
                this.textPaint.setTextSize(textSize);
                this.textPaint.getTextBounds(this.text, 0, this.text.length(), textBounds);
            }

            this.textY = this.centerY - textBounds.exactCenterY();
        }

        private void updateIconDrawable() {
            if (this.iconDrawable == null) return;
            RectF bounds = getIconContentBounds();

            float iconAspect = (float) this.iconDrawable.getIntrinsicWidth() / this.iconDrawable.getIntrinsicHeight();
            float boundsAspect = bounds.width() / bounds.height();

            float scaledWidth, scaledHeight;

            if (iconAspect > boundsAspect) {
                scaledWidth = bounds.width();
                scaledHeight = bounds.width() / iconAspect;
            } else {
                scaledHeight = bounds.height();
                scaledWidth = bounds.height() * iconAspect;
            }

            int left = (int) (bounds.left + (bounds.width() - scaledWidth) / 2);
            int top = (int) (bounds.top + (bounds.height() - scaledHeight) / 2);

            this.iconDrawable.setBounds(left, top, (int) (left + scaledWidth), (int) (top + scaledHeight));
        }

        private RectF getContentBounds() {
            final float contentScale = 0.8f;
            RectF bounds = new RectF();
            float contentW = 0;
            float contentH = 0;
            if (this.style == ControlElementDescription.Style.CONSOLE) {
                // Console pads keep the label well inside the ring: the letter is about a third
                // of the button's height, not as large as the shape allows.
                if (this.type == Type.BUTTON_RECT) {
                    contentW = this.width * contentScale;
                    contentH = this.height * 0.42f;
                } else {
                    contentW = this.width * 0.62f;
                    contentH = this.width * 0.36f;
                }
            } else if (this.type == Type.BUTTON_RECT) {
                contentW = this.width * contentScale;
                contentH = this.height * contentScale;
            } else {
                contentW = this.width * contentScale / (float) Math.sqrt(2);
                contentH = this.width * contentScale / (float) Math.sqrt(2);
            }
            bounds.set((this.width - contentW) / 2,
                    (this.height - contentH) / 2,
                    this.width - (this.width - contentW) / 2,
                    this.height - (this.height - contentH) / 2);
            bounds.offset(this.x, this.y);
            return bounds;
        }

        // Content area for the icon. Custom images get a larger area so they nearly fill the
        // button (built-in icons keep the smaller, padded area that suits their internal margins).
        private RectF getIconContentBounds() {
            if (this.iconFile == null) {
                return getContentBounds();
            }
            // Shape-aware icon box — fix suggested by user Willing-Run-8987: a rectangle can hold
            // a near-full image, but on a circle the icon box must stay inside the round outline —
            // a 0.92*diameter square pokes its corners past the circle. ~0.70 inscribes a full
            // square in the circle. (Imported images are alpha-trimmed on import, so this box maps
            // directly to the visible content.)
            final float contentScale = (this.type == Type.BUTTON_RECT) ? 0.92f : 0.70f;
            float contentW = this.width * contentScale;
            float contentH = this.height * contentScale;
            RectF bounds = new RectF();
            bounds.set((this.width - contentW) / 2,
                    (this.height - contentH) / 2,
                    this.width - (this.width - contentW) / 2,
                    this.height - (this.height - contentH) / 2);
            bounds.offset(this.x, this.y);
            return bounds;
        }
    }
}
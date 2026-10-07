// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.controls;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import java.io.File;
import java.util.Arrays;

public class DpadControlElement extends AbstractControlElement {
    private final DpadControlDrawable drawable;
    private int pointerId = -1;
    private int lastState = 0;   // this element's bits in the shared d-pad hat
    // Directions held right now, in either input mode: the CONSOLE style lights those arms.
    private int pressedMask = 0;
    // OUTLINE (the original look) or CONSOLE; see ControlElementDescription.STICK_STYLES.
    private ControlElementDescription.Style style;
    private static final float DPAD_DEAD_ZONE = 0.3f;
    private final AbstractControlElement.Type type;
    private static final int OUTLINE_ALPHA = 70; // 120;
    //private static final float OUTLINE_EXTRA_PX = 5f;
    private static final float OUTLINE_EXTRA_PX = 1.25f; // or 1.5f

    public DpadControlElement(InputControlsView parentView, ControlElementDescription elementDescription) {
        super(parentView, elementDescription);
        this.drawable = new DpadControlDrawable(parentView, elementDescription);
        this.bindings.addAll(Arrays.asList(elementDescription.bindings));
        this.type = elementDescription.type;
        this.style = ControlElementDescription.stickStyle(elementDescription.style);

        if (this.type != Type.DPAD) {
          throw new IllegalArgumentException("DpadControlElement must be created with Type.DPAD");
        }

        setInputType(elementDescription.inputType);
    }

    @Override
    public void setInputType(InputType inputType) {
      this.inputType = inputType;

      if (this.inputType == InputType.MNK) {
        // A composite d-pad in MNK needs 4 bindings (LEFT, UP, RIGHT, DOWN);
        // anything else falls back to WASD.
        if (this.bindings.size() != 4) {
          clearBindings();
          this.bindings.add(GLFWBinding.KEY_A); // LEFT
          this.bindings.add(GLFWBinding.KEY_W); // UP
          this.bindings.add(GLFWBinding.KEY_D); // RIGHT
          this.bindings.add(GLFWBinding.KEY_S); // DOWN
        }
      }
    }


    private void dispatchEvent(float x, float y, boolean isPress) {
        int state = 0;
        if (isPress) {
            float dx = x - this.drawable.centerX;
            float dy = y - this.drawable.centerY;
            float r = this.drawable.size / 2;
            float nx = clamp(dx / r, -1.f, 1.f);
            float ny = clamp(dy / r, -1.f, 1.f);
            if (ny < -DPAD_DEAD_ZONE) state |= 0x1;
            if (nx > DPAD_DEAD_ZONE) state |= 0x2;
            if (ny > DPAD_DEAD_ZONE) state |= 0x4;
            if (nx < -DPAD_DEAD_ZONE) state |= 0x8;
        }
        if (state != pressedMask) {
            pressedMask = state;
            // Only the CONSOLE style shows the held arms; the others need no redraw here.
            if (style == ControlElementDescription.Style.CONSOLE) this.parentView.invalidate();
        }

        if (this.inputType == InputType.GAMEPAD) {
            // Through the shared hat, so a button bound to a d-pad direction held at the same
            // time is not cleared by this element's update.
            updateDpadMask(lastState, state);
            lastState = state;
        }
        else if (this.inputType == InputType.MNK) {
            handleMNKBinding(getBindingUp(), (state & 0x1) != 0);
            handleMNKBinding(getBindingRight(), (state & 0x2) != 0);
            handleMNKBinding(getBindingDown(), (state & 0x4) != 0);
            handleMNKBinding(getBindingLeft(), (state & 0x8) != 0);
        }
    }

    @Override
    public boolean handleMotionEvent(MotionEvent e) {
      int action = e.getActionMasked();
      int actionIndex = e.getActionIndex();
      int pid = e.getPointerId(actionIndex);

      switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN: {
          float x = e.getX(actionIndex);
          float y = e.getY(actionIndex);
          if (!this.drawable.isPointOver(x, y)) return false;
          this.pointerId = pid;
          maybeHaptic();
          this.dispatchEvent(x, y, true);
          return true;
        }
        case MotionEvent.ACTION_MOVE: {
          if (this.pointerId < 0) return false;
          int idx = e.findPointerIndex(this.pointerId);
          if (idx < 0) { this.pointerId = -1; return false; }
          float x = e.getX(idx);
          float y = e.getY(idx);
          this.dispatchEvent(x, y, true);
          return true;
        }
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP: {
          if (pid != this.pointerId) return false;
          this.pointerId = -1;
          this.dispatchEvent(0, 0, false);
          return true;
        }
        case MotionEvent.ACTION_CANCEL: {
          if (this.pointerId != -1) {
            this.pointerId = -1;
            this.dispatchEvent(0, 0, false);
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
        this.dispatchEvent(0, 0, false);
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
    public void setAlpha(int alpha) {
        this.drawable.setAlpha(alpha);
        this.parentView.invalidate();
    }

    @Override
    public int getAlpha() {
        return this.drawable.alpha;
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
    public void setBindingLeft(GLFWBinding binding) {
        this.bindings.set(0, binding);
    }

    @Override
    public GLFWBinding getBindingLeft() {
        return this.bindings.get(0);
    }

    @Override
    public void setBindingUp(GLFWBinding binding) {
        this.bindings.set(1, binding);
    }

    @Override
    public GLFWBinding getBindingUp() {
        return this.bindings.get(1);
    }

    @Override
    public void setBindingRight(GLFWBinding binding) {
        this.bindings.set(2, binding);
    }

    @Override
    public GLFWBinding getBindingRight() {
        return this.bindings.get(2);
    }

    @Override
    public void setBindingDown(GLFWBinding binding) {
        this.bindings.set(3, binding);
    }

    @Override
    public GLFWBinding getBindingDown() {
        return this.bindings.get(3);
    }

    @Override
    public void setHighlighted(boolean highlighted) {
        if (highlighted) {
            this.drawable.setColorFilter(HIGHLIGHT_COLOR_FILTER);
        } else {
            this.drawable.setColorFilter(null);
        }
        this.parentView.invalidate();
    }

    @Override
    public ControlElementDescription.Style[] getStyleChoices() {
        return ControlElementDescription.STICK_STYLES;
    }

    @Override
    public ControlElementDescription.Style getStyle() {
        return style;
    }

    @Override
    public void setStyle(ControlElementDescription.Style style) {
        this.style = ControlElementDescription.stickStyle(style);
        this.parentView.invalidate();
    }

    public ControlElementDescription describe() {
        return new ControlElementDescription(
                this.drawable.centerX / this.parentView.getWidth(),
                this.drawable.centerY / this.parentView.getHeight(),
                this.drawable.scale,
                //Type.DPAD,
                this.type,
                this.bindings.toArray(new GLFWBinding[0]), null, this.drawable.color,
                this.drawable.alpha,
                this.inputType, ControlElementDescription.Icon.NO_ICON, false,
                ControlElementDescription.DEFAULT_SENSITIVITY, this.style,
                this.drawable.iconFile, this.drawable.noTint);
    }

    /** Replace the drawn cross with a user-supplied image from controls/icons (null clears it). */
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

    public class DpadControlDrawable {
        private static final int PAINT_STROKE_WIDTH = 4;
        private static final float DPAD_SIZE = 340.f;

        private int color;
        private int alpha;
        private float scale;
        private float size;
        private float centerX;
        private float centerY;
        private float x;
        private float y;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private String iconFile;        // user image filename in controls/icons, or null for the drawn cross
        private boolean noTint;         // keep the custom image's original colors when true
        private Drawable iconDrawable;  // decoded custom image, drawn instead of the cross
        private ColorFilter colorFilter;
        // CONSOLE style: each arm as its own path (index = bit 0x1 up, 0x2 right, 0x4 down,
        // 0x8 left) so a held direction can be lit alone, plus the four white arrows. Rebuilt
        // with the bounds, never while drawing.
        private final ConsolePainter console = new ConsolePainter();
        private final Path[] consoleArms = { new Path(), new Path(), new Path(), new Path() };
        private final Path consoleArrows = new Path();
        private float consoleRingWidth;

        public DpadControlDrawable(InputControlsView parentView, ControlElementDescription description) {
            setColor(description.color);
            setAlpha(description.alpha);

            setScale(description.scale);
            setCenterPosition(description.centerXRelative * parentView.getWidth(),
                    description.centerYRelative * parentView.getHeight());

            this.paint.setStyle(Paint.Style.STROKE);

            if (description.iconFile != null && !description.iconFile.isEmpty()) {
                setCustomIcon(description.iconFile, description.noTint);
            } else {
                this.noTint = description.noTint;
            }
        }

        // Load a user-supplied image from controls/icons/<fileName> to draw in place of the cross.
        // Falls back to the drawn cross if the file is missing/undecodable.
        public void setCustomIcon(String fileName, boolean noTint) {
            this.noTint = noTint;
            if (fileName == null || fileName.isEmpty()) {
                this.iconFile = null;
                this.iconDrawable = null;
                return;
            }
            File dir = parentView.getControlsIconsDir();
            File f = (dir != null) ? new File(dir, fileName) : null;
            Bitmap bmp = (f != null && f.isFile()) ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            if (bmp == null) {
                this.iconFile = null;
                this.iconDrawable = null;
                return;
            }
            this.iconFile = fileName;
            BitmapDrawable bd = new BitmapDrawable(parentView.getResources(), bmp);
            if (this.noTint) {
                bd.setTintList(null);
            } else {
                bd.setTint(this.color);
            }
            bd.setAlpha(this.alpha);
            bd.setColorFilter(this.colorFilter);
            this.iconDrawable = bd;
            updateIconBounds();
        }

        // The image fills the d-pad's square footprint, so a cross-shaped picture lines up with
        // the touch areas it replaces.
        private void updateIconBounds() {
            if (this.iconDrawable == null) return;
            this.iconDrawable.setBounds(Math.round(this.x), Math.round(this.y),
                    Math.round(this.x + this.size), Math.round(this.y + this.size));
        }

        private void calculatePath() {
            float halfWidth = this.size / 6;
            float halfHeight = this.size / 4;
            float offset = this.size / 12;
            this.path.reset();

            this.path.moveTo(this.centerX, this.centerY - offset);
            this.path.lineTo(this.centerX - halfWidth, this.centerY - halfHeight);
            this.path.lineTo(this.centerX - halfWidth, this.y);
            this.path.lineTo(this.centerX + halfWidth, this.y);
            this.path.lineTo(this.centerX + halfWidth, this.centerY - halfHeight);
            this.path.close();

            this.path.moveTo(this.centerX - offset, this.centerY);
            this.path.lineTo(this.centerX - halfHeight, this.centerY - halfWidth);
            this.path.lineTo(this.x, this.centerY - halfWidth);
            this.path.lineTo(this.x, this.centerY + halfWidth);
            this.path.lineTo(this.centerX - halfHeight, this.centerY + halfWidth);
            this.path.close();

            this.path.moveTo(this.centerX, this.centerY + offset);
            this.path.lineTo(this.centerX - halfWidth, this.centerY + halfHeight);
            this.path.lineTo(this.centerX - halfWidth, this.y + this.size);
            this.path.lineTo(this.centerX + halfWidth, this.y + this.size);
            this.path.lineTo(this.centerX + halfWidth, this.centerY + halfHeight);
            this.path.close();

            this.path.moveTo(this.centerX + offset, this.centerY);
            this.path.lineTo(this.centerX + halfHeight, this.centerY - halfWidth);
            this.path.lineTo(this.x + this.size, this.centerY - halfWidth);
            this.path.lineTo(this.x + this.size, this.centerY + halfWidth);
            this.path.lineTo(this.centerX + halfHeight, this.centerY + halfWidth);
            this.path.close();

            calculateConsolePaths(halfWidth, halfHeight, offset);
        }

        /** The same four arms as {@link #path}, one path each, and an arrow near each arm's end. */
        private void calculateConsolePaths(float halfWidth, float halfHeight, float offset) {
            Path up = this.consoleArms[0], right = this.consoleArms[1];
            Path down = this.consoleArms[2], left = this.consoleArms[3];
            for (Path arm : this.consoleArms) arm.reset();

            up.moveTo(this.centerX, this.centerY - offset);
            up.lineTo(this.centerX - halfWidth, this.centerY - halfHeight);
            up.lineTo(this.centerX - halfWidth, this.y);
            up.lineTo(this.centerX + halfWidth, this.y);
            up.lineTo(this.centerX + halfWidth, this.centerY - halfHeight);
            up.close();

            right.moveTo(this.centerX + offset, this.centerY);
            right.lineTo(this.centerX + halfHeight, this.centerY - halfWidth);
            right.lineTo(this.x + this.size, this.centerY - halfWidth);
            right.lineTo(this.x + this.size, this.centerY + halfWidth);
            right.lineTo(this.centerX + halfHeight, this.centerY + halfWidth);
            right.close();

            down.moveTo(this.centerX, this.centerY + offset);
            down.lineTo(this.centerX - halfWidth, this.centerY + halfHeight);
            down.lineTo(this.centerX - halfWidth, this.y + this.size);
            down.lineTo(this.centerX + halfWidth, this.y + this.size);
            down.lineTo(this.centerX + halfWidth, this.centerY + halfHeight);
            down.close();

            left.moveTo(this.centerX - offset, this.centerY);
            left.lineTo(this.centerX - halfHeight, this.centerY - halfWidth);
            left.lineTo(this.x, this.centerY - halfWidth);
            left.lineTo(this.x, this.centerY + halfWidth);
            left.lineTo(this.centerX - halfHeight, this.centerY + halfWidth);
            left.close();

            // Arrow centre about two thirds of the way out along each arm.
            float d = this.size * 0.33f, len = this.size * 0.09f, w = this.size * 0.11f;
            this.consoleArrows.reset();
            ConsolePainter.addArrow(this.consoleArrows, this.centerX, this.centerY - d, 0f, -1f, len, w);
            ConsolePainter.addArrow(this.consoleArrows, this.centerX + d, this.centerY, 1f, 0f, len, w);
            ConsolePainter.addArrow(this.consoleArrows, this.centerX, this.centerY + d, 0f, 1f, len, w);
            ConsolePainter.addArrow(this.consoleArrows, this.centerX - d, this.centerY, -1f, 0f, len, w);
        }

        /**
         * CONSOLE style: every arm gets the button look (dark body, thin glowing ring in the
         * d-pad colour) and a white arrow; a held direction is drawn pressed.
         */
        private void drawConsole(@NonNull Canvas canvas) {
            boolean editMode = parentView.isEditMode();
            for (int i = 0; i < 4; i++) {
                boolean pressed = !editMode && (pressedMask & (1 << i)) != 0;
                this.console.path(this.consoleArms[i]);
                this.console.drawBody(canvas, this.color, this.alpha, pressed);
                this.console.drawRing(canvas, this.color, this.alpha, pressed, this.consoleRingWidth);
            }
            this.console.fillPath(canvas, this.consoleArrows, android.graphics.Color.WHITE,
                    ConsolePainter.scaleAlpha(this.alpha, ConsolePainter.LABEL_K));
        }

        public void draw(@NonNull Canvas canvas) {
            // A custom image replaces the drawn cross entirely.
            if (this.iconDrawable != null) {
                this.iconDrawable.draw(canvas);
                return;
            }
            if (style == ControlElementDescription.Style.CONSOLE) {
                drawConsole(canvas);
                return;
            }

            // Outline
            int oldColor = this.paint.getColor();
            int oldAlpha = this.paint.getAlpha();
            float oldStroke = this.paint.getStrokeWidth();
        
            //this.paint.setColor(android.graphics.Color.BLACK);
            this.paint.setColor(android.graphics.Color.rgb(40, 40, 40));
            //this.paint.setAlpha(OUTLINE_ALPHA);
            this.paint.setAlpha(Math.min(oldAlpha, OUTLINE_ALPHA));
            this.paint.setStrokeWidth(oldStroke + OUTLINE_EXTRA_PX * parentView.pixelScale);
            canvas.drawPath(this.path, this.paint);
        
            // Normal
            this.paint.setColor(oldColor);
            this.paint.setAlpha(oldAlpha);
            this.paint.setStrokeWidth(oldStroke);
            canvas.drawPath(this.path, this.paint);
        }

        public void setColor(int color) {
            this.color = color;
            this.paint.setColor(this.color);
            if (this.iconDrawable != null && !this.noTint) this.iconDrawable.setTint(this.color);
        }

        public void setAlpha(int alpha) {
            this.alpha = alpha;
            this.paint.setAlpha(this.alpha);
            if (this.iconDrawable != null) this.iconDrawable.setAlpha(this.alpha);
        }

        public void setScale(float scale) {
            this.scale = scale;
            this.paint.setStrokeWidth(PAINT_STROKE_WIDTH * (float) Math.sqrt(this.scale));
            CornerPathEffect corners = new CornerPathEffect(15f * this.scale);
            this.paint.setPathEffect(corners);
            // The CONSOLE arms are filled too, so the fill needs the same rounded corners.
            this.console.setPathEffect(corners);
            this.consoleRingWidth = ConsolePainter.ringWidth(parentView.pixelScale, this.scale);
            updateDimensions();
        }

        private void updateDimensions() {
            this.size = DPAD_SIZE * parentView.pixelScale * this.scale;
            updateBounds();
        }

        private void updateBounds() {
            this.x = this.centerX - this.size / 2;
            this.y = this.centerY - this.size / 2;
            calculatePath();
            updateIconBounds();
        }

        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            this.colorFilter = colorFilter;
            this.paint.setColorFilter(colorFilter);
            this.console.setColorFilter(colorFilter);
            if (this.iconDrawable != null) this.iconDrawable.setColorFilter(colorFilter);
        }

        public boolean isPointOver(float x, float y) {
            return x >= this.x && x <= this.x + this.size && y >= this.y && y <= this.y + this.size;
        }

        public void setCenterPosition(float x, float y) {
            this.centerX = x;
            this.centerY = y;
            updateBounds();
        }

        public void moveCenterPosition(float dx, float dy) {
            setCenterPosition(this.centerX + dx, this.centerY + dy);
        }
    }
}

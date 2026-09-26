// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.controls;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.MotionEvent;
import android.content.Context;

import java.util.ArrayList;

public abstract class AbstractControlElement {
    private static final String LOG_TAG = AbstractControlElement.class.getName();
    protected static final float MIN_SCALE = 0.5f;
    protected static final float MAX_SCALE = 2.0f;
    protected final ArrayList<GLFWBinding> bindings = new ArrayList<>();
    protected final InputControlsView parentView;
    protected static final ColorFilter HIGHLIGHT_COLOR_FILTER = new PorterDuffColorFilter(Color.CYAN, PorterDuff.Mode.SRC_ATOP);
    protected final Type type;
    protected InputType inputType;
    protected Context context;
    private static int sDpadMask = 0;
    protected boolean isToggle = false;

    AbstractControlElement(InputControlsView parentView, ControlElementDescription description) {
        this.parentView = parentView;
        this.type = description.type;
        this.inputType = description.inputType;
        this.context = parentView.getContext();
        this.isToggle = description.isToggle;
    }

    public static AbstractControlElement fromDescription(InputControlsView parentView, ControlElementDescription description) {
        switch (description.type) {
            case BUTTON_CIRCLE:
            case BUTTON_RECT:
                return new ButtonControlElement(parentView, description);
            case DPAD:
                return new DpadControlElement(parentView, description);
            case DPAD_UP:
            case DPAD_RIGHT:
            case DPAD_DOWN:
            case DPAD_LEFT:
                return new ButtonControlElement(parentView, description);
            case STICK:
                return new StickControlElement(parentView, description);
            case STICK_WASD:
                return new WasdStickControlElement(parentView, description);
            case STICK_MOUSE:
                return new MouseStickControlElement(parentView, description);
            case TOUCHPAD:
                return new TouchpadControlElement(parentView, description);
            case SCROLL_BAR:
                return new ScrollBarControlElement(parentView, description);
            case RADIAL_MENU:
                return new RadialMenuControlElement(parentView, description);
            default:
                throw new IllegalArgumentException("Unrecognized type " + description.type);
        }
    }

    public Type getType() {
        return this.type;
    }

    public InputType getInputType() {
        return this.inputType;
    }

    public abstract void setInputType(InputType inputType);

    public float getCenterX() {
        throw new UnsupportedOperationException();
    }

    /** Centre Y in view px; the editor's grid snapping needs both coordinates. */
    public float getCenterY() {
        return describe().centerYRelative * parentView.getHeight();
    }

    public abstract boolean handleMotionEvent(MotionEvent e);

    public abstract void draw(Canvas canvas);

    public abstract boolean isPointOver(float x, float y);

    public abstract void setHighlighted(boolean highlighted);

    public abstract void setAlpha(int alpha);

    public abstract int getAlpha();

    public abstract void setScale(float value);

    public abstract float getScale();

    public void setText(String text) {
        throw new UnsupportedOperationException();
    }

    public String getText() {
        throw new UnsupportedOperationException();
    }

    public void setIcon(ControlElementDescription.Icon icon) {
        throw new UnsupportedOperationException();
    }

    public ControlElementDescription.Icon getIcon() {
        throw new UnsupportedOperationException();
    }

    public void setCenterPosition(float x, float y) {
        throw new UnsupportedOperationException();
    }

    public void moveCenterPosition(float dx, float dy) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding[] getBindings() {
        return this.bindings.toArray(new GLFWBinding[0]);
    }

    public void clearBindings() {
        this.bindings.clear();
    }

    public void addBinding(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public void setBinding(int index, GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public void removeBinding(int index) {
        throw new UnsupportedOperationException();
    }

    public void setBindingLeft(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingLeft() {
        throw new UnsupportedOperationException();
    }

    public void setBindingUp(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingUp() {
        throw new UnsupportedOperationException();
    }

    public void setBindingRight(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingRight() {
        throw new UnsupportedOperationException();
    }

    public void setBindingDown(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingDown() {
        throw new UnsupportedOperationException();
    }

    public void setBindingStick(GLFWBinding binding) {
        throw new UnsupportedOperationException();
    }

    public GLFWBinding getBindingStick() {
        throw new UnsupportedOperationException();
    }

    public void setToggle(boolean isToggle) {
        this.isToggle = isToggle;
    }

    public boolean getToggle() {
        return isToggle;
    }

    public abstract ControlElementDescription describe();

    /**
     * Forget the tracked finger as if the system had cancelled it, releasing whatever it held.
     * Called at the start of every new gesture: a pointer whose UP never reached us (the element
     * was hidden mid-touch, a dialog took the window) would otherwise make the element ignore the
     * next DOWN, and a held key would stay down. A latched toggle stays latched.
     */
    public void releasePointer() {}

    /** Release everything, latched toggles included (element hidden, game paused). */
    public void reset() {
        releasePointer();
    }

    /** Light haptic tick on a press, when the player enabled it; see InputControlsView.maybeHaptic. */
    protected void maybeHaptic() {
        this.parentView.maybeHaptic();
    }

    void handleGamepadBinding(GLFWBinding binding, boolean isPressed) {
        if (isDpadBinding(binding)) {
            int bit = dpadBit(binding);
            updateDpadMask(bit, isPressed ? bit : 0);
            return;
        }

        if (binding == GLFWBinding.GAMEPAD_LTRIGGER || binding == GLFWBinding.GAMEPAD_AXIS_LT) {
            InputSink.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_LT.code, isPressed ? 1f : 0f);
            return;
        }
        if (binding == GLFWBinding.GAMEPAD_RTRIGGER || binding == GLFWBinding.GAMEPAD_AXIS_RT) {
            InputSink.sendJoystickAxis(GLFWBinding.GAMEPAD_AXIS_RT.code, isPressed ? 1f : 0f);
            return;
        }
        // A button bound to a stick axis pushes that axis fully while held. Without this the
        // axis code (0..3) went out as a button code and pressed A/B/X/Y instead.
        if (binding == GLFWBinding.GAMEPAD_AXIS_LX || binding == GLFWBinding.GAMEPAD_AXIS_LY
                || binding == GLFWBinding.GAMEPAD_AXIS_RX || binding == GLFWBinding.GAMEPAD_AXIS_RY) {
            InputSink.sendJoystickAxis(binding.code, isPressed ? 1f : 0f);
            return;
        }
        if (binding.code < 0) return;   // LEFT_JOYSTICK / RIGHT_JOYSTICK are not buttons

        InputSink.sendJoystickButton(binding.code, isPressed);
    }

    /** Replace one source's bits of the shared d-pad hat (buttons bound to GAMEPAD_DPAD_* and the
     *  d-pad element), so two sources held together don't clear each other. */
    static void updateDpadMask(int clearBits, int setBits) {
        sDpadMask = (sDpadMask & ~clearBits) | setBits;
        InputSink.sendJoystickDpad(0, (char) sDpadMask);
    }

    public static void handleMNKBinding(GLFWBinding binding, boolean isPressed) {
        // Mouse buttons
        if (binding.name().startsWith("MOUSE_BUTTON_")) {
            InputSink.sendMouseButton(binding.code, isPressed);
            return;
        }

        // Mouse wheel: only on press, as impulse event
        if (binding == GLFWBinding.MOUSE_WHEEL_UP) {
            if (isPressed) {
                InputSink.sendMouseScroll(0.0, 1.0);
            }
            return;
        }

        if (binding == GLFWBinding.MOUSE_WHEEL_DOWN) {
            if (isPressed) {
                InputSink.sendMouseScroll(0.0, -1.0);
            }
            return;
        }

        // UI actions are handled by the view, never sent to the game.
        if (binding == GLFWBinding.UI_TOGGLE_OVERLAY || binding == GLFWBinding.UI_TOGGLE_KEYBOARD) return;

        // Keyboard
        InputSink.sendKeyboard(binding.code, isPressed);

        // Char event for text fields (a no-op in ValDroid, see InputSink.sendChar)
        if (isPressed) {
            int unicode = glfwBindingToUnicode(binding);
            if (unicode > 0) {
                InputSink.sendChar(unicode);
            }
        }
    }

    private static int glfwBindingToUnicode(GLFWBinding binding) {
        // Letters A-Z → a-z (97-122)
        if (binding.ordinal() >= GLFWBinding.KEY_A.ordinal()
                && binding.ordinal() <= GLFWBinding.KEY_Z.ordinal()) {
            return binding.code + 32; // GLFW A=65 → 'a'=97
        }
        // Numbers 0-9
        if (binding.ordinal() >= GLFWBinding.KEY_0.ordinal()
                && binding.ordinal() <= GLFWBinding.KEY_9.ordinal()) {
            return binding.code; // same as ASCII
        }
        // Signs typed into an IP address
        switch (binding) {
            case KEY_PERIOD:    return '.';
            case KEY_MINUS:     return '-';
            case KEY_SLASH:     return '/';
            case KEY_SPACE:     return ' ';
            default:            return 0; // Enter, Escape etc. produce no char
        }
    }

    protected static float clamp(float v, float lo, float hi) {
        return (v < lo) ? lo : (v > hi ? hi : v);
    }

    public enum Type {
        STICK,
        STICK_WASD,
        STICK_MOUSE,
        DPAD,
        DPAD_UP,
        DPAD_RIGHT,
        DPAD_DOWN,
        DPAD_LEFT,
        BUTTON_RECT,
        BUTTON_CIRCLE,
        TOUCHPAD,
        SCROLL_BAR,
        RADIAL_MENU
    }

    public enum InputType {
        MNK,
        GAMEPAD
    }
    private boolean visible = true;

    public void setVisible(boolean value) {
      this.visible = value;
    }

    public boolean isVisible() {
      return visible;
    }

    private boolean touchable = true;

    public void setTouchable(boolean touchable) {
      this.touchable = touchable;
    }

    public boolean isTouchable() {
      return touchable;
    }

    private static boolean isDpadBinding(GLFWBinding b) {
      return b == GLFWBinding.GAMEPAD_DPAD_UP
        || b == GLFWBinding.GAMEPAD_DPAD_RIGHT
        || b == GLFWBinding.GAMEPAD_DPAD_DOWN
        || b == GLFWBinding.GAMEPAD_DPAD_LEFT;
    }

    private static int dpadBit(GLFWBinding b) {
      switch (b) {
        case GAMEPAD_DPAD_UP:    return 0x1;
        case GAMEPAD_DPAD_RIGHT: return 0x2;
        case GAMEPAD_DPAD_DOWN:  return 0x4;
        case GAMEPAD_DPAD_LEFT:  return 0x8;
        default: return 0;
      }
    }
}

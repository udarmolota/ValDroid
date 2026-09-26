// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.controls;

import android.graphics.Color;

import androidx.annotation.NonNull;

import com.valdroid.R;

public class ControlElementDescription {
    public enum Icon {
        NO_ICON(R.drawable.ic_void),
        GAMEPAD_BACK_ICON(R.drawable.mt_icon_stack),
        GAMEPAD_START_ICON(R.drawable.mt_icon_menu);
        public final int resId;

        Icon(int resId) {
            this.resId = resId;
        }
    }

    public enum Style {
        OUTLINE,
        FILLED,
        GLASS
    }

    public final float centerXRelative;
    public final float centerYRelative;
    public final float scale;
    public final AbstractControlElement.Type type;
    public final GLFWBinding[] bindings;
    public final String text;
    public final int color;
    public final int alpha;
    public final AbstractControlElement.InputType inputType;
    public final Icon icon;
    public final boolean isToggle;
    public final float sensitivity;
    public final Style style;
    /** Filename of a user-supplied icon image stored in the instance's controls/icons folder; null = use built-in {@link #icon}. */
    public final String iconFile;
    /** When true a custom iconFile is drawn with its original colors (not tinted by the button color). */
    public final boolean noTint;
    /**
     * Touchpad only: when true a short tap does NOT send a left click - the pad just moves the
     * cursor and clicking is done deliberately with the on-screen mouse buttons. Asked for by
     * server admins: while dragging the cursor an accidental tap clicks whatever is underneath,
     * which on an admin account fires real admin actions. Inverted (default false = tap clicks,
     * today's behaviour) so layouts saved before this field existed deserialize unchanged - Gson
     * fills absent booleans with false.
     */
    public final boolean tapDisabled;

    public static final float DEFAULT_SENSITIVITY = 2.0f;
    public static final Style DEFAULT_STYLE = Style.OUTLINE;
    public static final float MIN_SENSITIVITY = 0.25f;
    public static final float MAX_SENSITIVITY = 8.0f;

    public ControlElementDescription(float centerXRelative, float centerYRelative, float scale,
                                     @NonNull AbstractControlElement.Type type, @NonNull GLFWBinding[] bindings,
                                     String text, int color, int alpha,
                                     AbstractControlElement.InputType inputType, @NonNull Icon icon, boolean isToggle) {
        this(centerXRelative, centerYRelative, scale, type, bindings, text, color, alpha,
                inputType, icon, isToggle, DEFAULT_SENSITIVITY);
    }

    public ControlElementDescription(float centerXRelative, float centerYRelative, float scale,
                                     @NonNull AbstractControlElement.Type type, @NonNull GLFWBinding[] bindings,
                                     String text, int color, int alpha,
                                     AbstractControlElement.InputType inputType, @NonNull Icon icon,
                                     boolean isToggle, float sensitivity) {
        this(centerXRelative, centerYRelative, scale, type, bindings, text, color, alpha,
                inputType, icon, isToggle, sensitivity, DEFAULT_STYLE);
    }

    public ControlElementDescription(float centerXRelative, float centerYRelative, float scale,
                                     @NonNull AbstractControlElement.Type type, @NonNull GLFWBinding[] bindings,
                                     String text, int color, int alpha,
                                     AbstractControlElement.InputType inputType, @NonNull Icon icon,
                                     boolean isToggle, float sensitivity, Style style) {
        this(centerXRelative, centerYRelative, scale, type, bindings, text, color, alpha,
                inputType, icon, isToggle, sensitivity, style, null, false, false);
    }

    public ControlElementDescription(float centerXRelative, float centerYRelative, float scale,
                                     @NonNull AbstractControlElement.Type type, @NonNull GLFWBinding[] bindings,
                                     String text, int color, int alpha,
                                     AbstractControlElement.InputType inputType, @NonNull Icon icon,
                                     boolean isToggle, float sensitivity, Style style,
                                     String iconFile, boolean noTint) {
        this(centerXRelative, centerYRelative, scale, type, bindings, text, color, alpha,
                inputType, icon, isToggle, sensitivity, style, iconFile, noTint, false);
    }

    public ControlElementDescription(float centerXRelative, float centerYRelative, float scale,
                                     @NonNull AbstractControlElement.Type type, @NonNull GLFWBinding[] bindings,
                                     String text, int color, int alpha,
                                     AbstractControlElement.InputType inputType, @NonNull Icon icon,
                                     boolean isToggle, float sensitivity, Style style,
                                     String iconFile, boolean noTint, boolean tapDisabled) {
        // Clamped rather than rejected: describe() runs on every save, and an element dragged to
        // the very edge (or a hand-edited file) must not make saving throw and lose the layout.
        this.centerXRelative = clampF(centerXRelative, 0.001f, 0.999f);
        this.centerYRelative = clampF(centerYRelative, 0.001f, 0.999f);
        this.scale = clampF(scale, AbstractControlElement.MIN_SCALE, AbstractControlElement.MAX_SCALE);
        this.type = type;
        this.bindings = bindings;
        this.text = text;
        this.color = color;
        this.alpha = Math.max(0, Math.min(255, alpha));
        this.inputType = inputType;
        this.icon = icon;
        this.isToggle = isToggle;
        this.sensitivity = sensitivity;
        this.style = (style != null) ? style : DEFAULT_STYLE;
        this.iconFile = iconFile;
        this.noTint = noTint;
        this.tapDisabled = tapDisabled;
        validate();
    }

    public static ControlElementDescription getDefaultForType(AbstractControlElement.Type type) {
        switch (type) {
            case BUTTON_CIRCLE:
            case BUTTON_RECT:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.GAMEPAD_BUTTON_A}, "A", Color.WHITE, 150,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON, false);
            case DPAD:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{}, null, Color.WHITE, 150,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON, false);

            case DPAD_UP:
                return new ControlElementDescription(
                        0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{ GLFWBinding.GAMEPAD_DPAD_UP },
                        null, Color.WHITE, 150,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON, false);

            case DPAD_RIGHT:
                return new ControlElementDescription(
                        0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{ GLFWBinding.GAMEPAD_DPAD_RIGHT },
                        null, Color.WHITE, 150,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON, false);

            case DPAD_DOWN:
                return new ControlElementDescription(
                        0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{ GLFWBinding.GAMEPAD_DPAD_DOWN },
                        null, Color.WHITE, 150,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON, false);

            case DPAD_LEFT:
                return new ControlElementDescription(
                        0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{ GLFWBinding.GAMEPAD_DPAD_LEFT },
                        null, Color.WHITE, 150,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON, false);

            case STICK:
                return new ControlElementDescription(0.5f, 0.5f, 1.f, type,
                        new GLFWBinding[]{GLFWBinding.LEFT_JOYSTICK}, null, Color.WHITE, 255,
                        AbstractControlElement.InputType.GAMEPAD, Icon.NO_ICON, false);
            case STICK_WASD:
                return new ControlElementDescription(
                        0.14f, 0.70f, 1.f, type,
                        new GLFWBinding[0], null, Color.WHITE, 255,
                        AbstractControlElement.InputType.MNK, Icon.NO_ICON, false);

            case STICK_MOUSE:
                return new ControlElementDescription(
                        0.86f, 0.70f, 1.f, type,
                        new GLFWBinding[0], null, Color.WHITE, 255,
                        AbstractControlElement.InputType.MNK, Icon.NO_ICON, false);
            case TOUCHPAD:
                return new ControlElementDescription(
                        0.5f, 0.75f, 1.0f, type,
                        new GLFWBinding[0],
                        null, Color.WHITE, 128,
                        AbstractControlElement.InputType.MNK,
                        Icon.NO_ICON, false);
            case SCROLL_BAR:
                return new ControlElementDescription(
                        0.92f, 0.5f, 1.0f, type,
                        new GLFWBinding[0],
                        null, Color.WHITE, 150,
                        AbstractControlElement.InputType.MNK,
                        Icon.NO_ICON, false, DEFAULT_SENSITIVITY);
            case RADIAL_MENU:
                return new ControlElementDescription(
                        0.5f, 0.5f, 1.0f, type,
                        new GLFWBinding[0],
                        RadialMenuControlElement.DEFAULT_TITLE, Color.WHITE, 200,
                        AbstractControlElement.InputType.GAMEPAD,
                        Icon.NO_ICON, false, DEFAULT_SENSITIVITY);
            default:
                throw new IllegalArgumentException("Unrecognized type " + type.name());
        }
    }

    private static float clampF(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Gson fills objects without running a constructor, so a layout file with a missing field
     * (no "bindings", no "icon", a 0 scale...) would reach the elements as nulls. Re-build every
     * loaded entry through the validating constructor, with defaults for what is missing.
     * Returns null for an entry that still cannot describe a valid element.
     */
    public static ControlElementDescription sanitize(ControlElementDescription d) {
        if (d == null || d.type == null) return null;
        try {
            GLFWBinding[] b = d.bindings;
            if (b == null) b = new GLFWBinding[0];
            java.util.ArrayList<GLFWBinding> nonNull = new java.util.ArrayList<>();
            for (GLFWBinding x : b) if (x != null) nonNull.add(x);
            AbstractControlElement.InputType it = d.inputType != null ? d.inputType
                    : AbstractControlElement.InputType.MNK;
            return new ControlElementDescription(d.centerXRelative, d.centerYRelative,
                    d.scale > 0f ? d.scale : 1f, d.type, nonNull.toArray(new GLFWBinding[0]),
                    d.text, d.color, d.alpha, it, d.icon != null ? d.icon : Icon.NO_ICON,
                    d.isToggle, d.sensitivity, d.style, d.iconFile, d.noTint, d.tapDisabled);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void validate() {
        // scale
        if (this.scale < AbstractControlElement.MIN_SCALE || this.scale > AbstractControlElement.MAX_SCALE) {
            throw new IllegalStateException("Scale must be in [" +
                    AbstractControlElement.MIN_SCALE + ", " + AbstractControlElement.MAX_SCALE + "]");
        }

        // center (exclusive 0..1)
        if (this.centerXRelative <= 0.f || this.centerXRelative >= 1.f
                || this.centerYRelative <= 0.f || this.centerYRelative >= 1.f) {
            throw new IllegalStateException("Relative center must be in (0,1), got x=" +
                    this.centerXRelative + " y=" + this.centerYRelative);
        }

        // alpha
        if (this.alpha < 0 || this.alpha > 255) {
            throw new IllegalStateException("Alpha must be in [0,255], got " + this.alpha);
        }

        // bindings not null
        for (GLFWBinding b : this.bindings) {
            if (b == null) throw new IllegalStateException("Null binding is not allowed");
        }

        final boolean isMNK = (this.inputType == AbstractControlElement.InputType.MNK);
        final boolean isGAMEPAD = (this.inputType == AbstractControlElement.InputType.GAMEPAD);

        // --- hard rules for fixed MNK sticks ---
        if (this.type == AbstractControlElement.Type.STICK_WASD
                || this.type == AbstractControlElement.Type.STICK_MOUSE) {

            if (this.inputType != AbstractControlElement.InputType.MNK) {
                throw new IllegalStateException(this.type.name() + " must have MNK inputType");
            }

            int count = (this.bindings == null) ? 0 : this.bindings.length;

            if (count != 0) {
                throw new IllegalStateException(this.type.name()
                        + " must have 0 bindings, got " + count);
            }
        }

        // --- MNK rules ---
        if (isMNK) {

            // TOUCHPAD needs no bindings: skip the binding checks
            if (this.type != AbstractControlElement.Type.TOUCHPAD) {

                // A composite DPAD and a STICK in MNK use 4 bindings (WASD/arrows)
                if (this.type == AbstractControlElement.Type.DPAD || this.type == AbstractControlElement.Type.STICK) {
                    if (this.bindings.length != 4) {
                        throw new IllegalStateException("DPAD/STICK with MNK input must have exactly 4 bindings, got " +
                                this.bindings.length);
                    }
                }

                // Split DPAD (UP/RIGHT/DOWN/LEFT) in MNK: exactly 1 binding per element
                if (this.type == AbstractControlElement.Type.DPAD_UP
                        || this.type == AbstractControlElement.Type.DPAD_RIGHT
                        || this.type == AbstractControlElement.Type.DPAD_DOWN
                        || this.type == AbstractControlElement.Type.DPAD_LEFT) {
                    if (this.bindings.length != 1) {
                        throw new IllegalStateException("Split-DPAD with MNK input must have exactly 1 binding, got " +
                                this.bindings.length);
                    }
                }
            }
        }

        // --- GAMEPAD rules ---
        if (isGAMEPAD) {

            if (this.type == AbstractControlElement.Type.STICK) {
                if (this.bindings.length != 1) {
                    throw new IllegalStateException("STICK with GAMEPAD input must have exactly 1 binding, got " +
                            this.bindings.length);
                }
                if (this.bindings[0] != GLFWBinding.LEFT_JOYSTICK
                        && this.bindings[0] != GLFWBinding.RIGHT_JOYSTICK) {
                    throw new IllegalStateException("STICK with GAMEPAD input must bind to LEFT_JOYSTICK or RIGHT_JOYSTICK, got " +
                            this.bindings[0]);
                }
            }
        }
    }
}

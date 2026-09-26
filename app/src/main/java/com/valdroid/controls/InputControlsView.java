// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.valdroid.GameActivity;
import com.valdroid.InstanceSettings;
import com.valdroid.LauncherPreferences;
import com.valdroid.R;
import com.valdroid.input.Binding;
import com.valdroid.input.VirtualGamepad;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen controls overlay. In play mode it routes multi-touch to the elements, which
 * send their input through {@link InputSink}; in edit mode (ControlsEditorActivity) taps select
 * an element and drags move it. It also owns the one mouse cursor every mouse-like source shares
 * (touchpad, mouse stick, physical mouse, taps on the bare game) and draws its arrow.
 *
 * The view covers the whole screen; the game may sit in a letterboxed rect inside it (see
 * {@link #setGameRect}). Element positions are relative to the view, the cursor is kept inside
 * the game rect.
 */
public class InputControlsView extends View {
    private static final String LOG_TAG = "ValDroid/Controls";
    private final ArrayList<AbstractControlElement> controlElements = new ArrayList<>();
    boolean isEditMode = false;
    boolean showPressFeedback = true;
    AbstractControlElement selectedElement;
    AbstractControlElement pointerOverElement;
    /** Zomdroid's size unit: element dimensions are given for a 2560 px wide view. */
    public float pixelScale = 1.f;
    GestureDetector gestureDetector;
    private String instanceName = null;
    /** Light haptic tick on presses, per-instance setting (default off). */
    private boolean hapticEnabled = false;
    private InputMode currentInputMode = InputMode.ALL;
    private boolean overlayHidden = false;
    private boolean isKeyboardConnected = false;
    private KeyboardToggleListener keyboardToggleListener;
    private float renderScale = 1f;

    private ElementSettingsController elementSettingsController;

    // Shared cursor, view px. Starts in the centre of the game rect.
    private float curX = -1, curY = -1;
    // Letterboxed game rect in view px; gameW <= 0 = the whole view (the editor).
    private int gameLeft, gameTop, gameW, gameH;
    private final Paint curFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Editor grid snapping (from RimDroid): while on, a dragged element's CENTRE lands on the
    // nearest grid node so same-size buttons line up. The step is in dp, so the grid has the same
    // physical size on every screen. dragRawX/Y follow the finger unsnapped — snapping from the
    // element's own (already snapped) position would stick it to one node on a slow drag.
    private static final float GRID_STEP_DP = 24f;
    private boolean snapToGrid = false;
    private float dragRawX, dragRawY;
    private final Paint gridPaint = new Paint();

    public InputControlsView(Context context) {
        this(context, null);
    }

    public InputControlsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        curFill.setStyle(Paint.Style.FILL); curFill.setColor(0xFFFFFFFF); curFill.setAlpha(150);
        curStroke.setStyle(Paint.Style.STROKE); curStroke.setStrokeWidth(1.5f * density); curStroke.setColor(0xC0000000);
        setFocusable(false);

        this.gestureDetector = new GestureDetector(context, new GestureDetector.OnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public void onShowPress(@NonNull MotionEvent e) {
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (pointerOverElement == null) return false;
                selectElement(pointerOverElement);
                return true;
            }

            @Override
            public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
                if (pointerOverElement == null) return false;
                dragRawX -= distanceX;
                dragRawY -= distanceY;
                if (snapToGrid) {
                    float step = gridStepPx();
                    pointerOverElement.setCenterPosition(Math.round(dragRawX / step) * step,
                            Math.round(dragRawY / step) * step);
                } else {
                    pointerOverElement.moveCenterPosition(-distanceX, -distanceY);
                }
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                showAddElementDialog();
            }

            @Override
            public boolean onFling(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                return false;
            }
        });
    }

    public void showAddElementDialog() {
        List<AbstractControlElement.Type> palette = new ArrayList<>();
        for (AbstractControlElement.Type t : AbstractControlElement.Type.values()) {
            if (isCreatable(t)) palette.add(t);
        }

        ArrayAdapter<AbstractControlElement.Type> adapter =
                new ArrayAdapter<AbstractControlElement.Type>(this.getContext(),
                        R.layout.spinner_item,
                        palette) {
                    @NonNull
                    @Override
                    public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        ((android.widget.TextView) view).setText(ControlLabels.type(getContext(), getItem(position)));
                        view.setPadding(
                                (int) (16 * getContext().getResources().getDisplayMetrics().density),
                                view.getPaddingTop(),
                                view.getPaddingRight(),
                                view.getPaddingBottom()
                        );
                        return view;
                    }
                };

        new MaterialAlertDialogBuilder(this.getContext())
                .setTitle(this.getContext().getString(R.string.controls_editor_add_element))
                .setAdapter(adapter, (dialog, i) -> {
                    AbstractControlElement.Type type = adapter.getItem(i);
                    if (type == null) return;
                    ControlElementDescription description = ControlElementDescription.getDefaultForType(type);
                    controlElements.add(AbstractControlElement.fromDescription(this, description));
                    invalidate();
                })
                .create()
                .show();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        this.pixelScale = (float) w / 2560;

        if (this.controlElements.isEmpty()) {
            loadControlElementsFromDisk();
        } else {
            // recreate all elements (released first: the new ones start with nothing held)
            resetAll();
            for (int i = 0; i < this.controlElements.size(); i++) {
                AbstractControlElement controlElement = this.controlElements.get(i);
                ControlElementDescription description = controlElement.describe();
                controlElement = AbstractControlElement.fromDescription(this, description);
                this.controlElements.set(i, controlElement);
            }
        }
        if (currentInputMode != null) {
            applyInputMode(currentInputMode);
        }
        if (curX < 0) centreCursor();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (isEditMode && snapToGrid) drawGrid(canvas);
        for (AbstractControlElement controlElement : controlElements) {
            if (!controlElement.isVisible()) continue;
            controlElement.draw(canvas);
        }
        if (!isEditMode && curX >= 0 && hasMouseElement()) drawCursor(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (isEditMode) {
            /* this is here and not in gesture detector because longpressEnabled should be set before
            detector processes motion event */
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (selectedElement != null) deselectElement();
                pointerOverElement = null;
                gestureDetector.setIsLongpressEnabled(true);

                float x = e.getX();
                float y = e.getY();
                for (AbstractControlElement element : controlElements) {
                    if (element.isPointOver(x, y)) {
                        pointerOverElement = element;
                        dragRawX = element.getCenterX();
                        dragRawY = element.getCenterY();
                        gestureDetector.setIsLongpressEnabled(false);
                        break;
                    }
                }
            }
            return gestureDetector.onTouchEvent(e);
        } else {
            int action = e.getActionMasked();
            // A new gesture: no finger can still be on any element, so drop pointers whose UP never
            // arrived. Otherwise an element keeps ignoring DOWNs, or keeps a key held.
            if (action == MotionEvent.ACTION_DOWN) {
                for (AbstractControlElement el : controlElements) el.releasePointer();
            }
            boolean consumed = false;
            // These events carry the state of every pointer, so every element gets them.
            boolean broadcast =
                    action == MotionEvent.ACTION_MOVE ||
                            action == MotionEvent.ACTION_UP ||
                            action == MotionEvent.ACTION_POINTER_UP ||
                            action == MotionEvent.ACTION_CANCEL;

            if (broadcast) {
                for (AbstractControlElement controlElement : controlElements) {
                    if (!controlElement.isVisible()) continue;
                    consumed |= controlElement.handleMotionEvent(e);
                }
                return consumed;
            }

            // DOWN / POINTER_DOWN: the first element under the finger takes it. false = the
            // Activity gets the touch (tap = left click, long press = right click, pinch = wheel).
            for (AbstractControlElement controlElement : controlElements) {
                if (!controlElement.isVisible()) continue;
                if (controlElement.handleMotionEvent(e)) return true;
            }
            return false;
        }
    }

    private void selectElement(@NonNull AbstractControlElement element) {
        this.selectedElement = element;
        this.selectedElement.setHighlighted(true);

        if (this.elementSettingsController != null) {
            this.elementSettingsController.fromLeft = this.selectedElement.getCenterX() > (float) this.getWidth() / 2;
            this.elementSettingsController.open();
        }
    }

    private void deselectElement() {
        if (this.selectedElement == null) return;
        if (this.elementSettingsController != null) this.elementSettingsController.close();
        this.selectedElement.setHighlighted(false);
        this.selectedElement = null;
    }

    public AbstractControlElement getSelectedElement() {
        return this.selectedElement;
    }

    public void deleteSelectedElement() {
        if (this.selectedElement == null) return;
        if (this.elementSettingsController != null) this.elementSettingsController.hide();
        this.controlElements.remove(this.selectedElement);
        this.selectedElement = null;
        invalidate();
    }

    public void setEditMode(boolean value) {
        this.isEditMode = value;
        if (value) resetAll();
        invalidate();
    }

    public boolean isEditMode() {
        return isEditMode;
    }

    public void setSnapToGrid(boolean on) {
        snapToGrid = on;
        invalidate();
    }

    private float gridStepPx() {
        return GRID_STEP_DP * getResources().getDisplayMetrics().density;
    }

    private void drawGrid(Canvas c) {
        float step = gridStepPx();
        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(1f);
        for (float x = step; x < getWidth(); x += step) c.drawLine(x, 0, x, getHeight(), gridPaint);
        for (float y = step; y < getHeight(); y += step) c.drawLine(0, y, getWidth(), y, gridPaint);
    }

    // ============================== persistence ==============================

    /** The instance's saved layout, or the bundled default when it has none. */
    public void loadControlElementsFromDisk() {
        String json = ControlsStorage.readLayoutJson(instanceName);
        if (json == null) json = ControlsStorage.readAsset(getContext(), ControlsStorage.DEFAULT_ASSET);
        if (json == null) return;
        // Loading replaces the current layout, never appends to it (Zomdroid fix: extra layout
        // passes used to stack two layouts on top of each other).
        replaceControlsFromJson(json, false);
    }

    public void saveControlElementsToDisk() {
        ArrayList<ControlElementDescription> descriptions = new ArrayList<>();
        for (AbstractControlElement element : controlElements) {
            descriptions.add(element.describe());
        }
        // An empty layout is saved too: deleting every element is a choice, and skipping the
        // save (as Zomdroid did) brought the old layout back on the next start.
        ControlsStorage.writeLayoutJson(instanceName, ControlsStorage.toJson(descriptions));
    }

    public void setElementSettingsController(ElementSettingsController elementSettingsController) {
        this.elementSettingsController = elementSettingsController;
    }

    public abstract static class ElementSettingsController {
        protected boolean fromLeft;
        protected abstract void open();
        protected abstract void close();
        protected abstract void hide();
    }

    public void replaceControlsFromJson(@Nullable String json, boolean persist) {
        if (json == null) return;

        // Clear editor state first so no UI reference points at a control about to go away.
        if (this.elementSettingsController != null) {
            this.elementSettingsController.hide();
        }
        this.selectedElement = null;
        this.pointerOverElement = null;

        List<ControlElementDescription> saved = ControlsStorage.parse(json);
        if (saved == null) {
            Log.w(LOG_TAG, "layout not replaced: it does not parse");
            invalidate();
            return;
        }
        resetAll();
        this.controlElements.clear();
        for (ControlElementDescription d : saved) {
            try {
                this.controlElements.add(AbstractControlElement.fromDescription(this, d));
            } catch (RuntimeException e) {
                Log.w(LOG_TAG, "skipped element " + d.type + ": " + e.getMessage());
            }
        }

        applyInputMode(currentInputMode);
        invalidate();

        if (persist) {
            saveControlElementsToDisk();
        }
    }

    public void replaceControlsFromAsset(@NonNull String assetPath, boolean persist) {
        replaceControlsFromJson(ControlsStorage.readAsset(getContext(), assetPath), persist);
    }

    // ============================== visibility ==============================

    public void applyInputMode(InputMode mode) {
        if (mode == null) return;
        this.currentInputMode = mode;
        for (AbstractControlElement element : controlElements) {
            boolean visible;
            if (isEditMode) {
                visible = true;   // the editor always shows the whole layout
            } else if (overlayHidden) {
                visible = isOverlayToggleElement(element) || isKeyboardToggleElement(element);
            } else if (isOverlayToggleElement(element) || isKeyboardToggleElement(element)) {
                visible = true;
            } else {
                switch (mode) {
                    case MNK:
                        visible = element.getInputType() == AbstractControlElement.InputType.MNK;
                        break;
                    case GAMEPAD:
                        visible = element.getInputType() == AbstractControlElement.InputType.GAMEPAD;
                        break;
                    case ALL:
                    default:
                        visible = true;
                }
            }
            // Release what a control holds before it disappears; a hidden element gets no UP.
            if (!visible && element.isVisible()) element.reset();
            element.setVisible(visible);
        }
        invalidate();
    }

    /**
     * A physical gamepad feeds the same virtual pad, so while one is connected only the
     * keyboard/mouse elements stay on screen (Zomdroid's MNK mode); ALL again when it goes.
     */
    public void setGamepadConnected(boolean connected) {
        applyInputMode(connected ? InputMode.MNK : InputMode.ALL);
    }

    /** Remembered for later: the overlay deliberately stays visible with a physical keyboard. */
    public void setKeyboardConnected(boolean connected) {
        isKeyboardConnected = connected;
    }

    public boolean isPhysicalKeyboardConnected() {
        return isKeyboardConnected;
    }

    public void showTextInputOverlay() {
        if (keyboardToggleListener != null) {
            keyboardToggleListener.onToggleSystemKeyboard();
        }
    }

    public enum InputMode {
        MNK,
        GAMEPAD,
        ALL
    }

    public InputMode getCurrentInputMode() {
        return currentInputMode;
    }

    private static boolean isCreatable(AbstractControlElement.Type t) {
        switch (t) {
            case DPAD_UP:
            case DPAD_RIGHT:
            case DPAD_DOWN:
            case DPAD_LEFT:
                return false;
            default:
                return true;
        }
    }

    /** Folder where user-supplied control icons live: &lt;instance&gt;/controls/icons. */
    @Nullable
    public File getControlsIconsDir() {
        return ControlsStorage.iconsDir(instanceName);
    }

    public void toggleOverlayVisibility() {
        overlayHidden = !overlayHidden;
        applyInputMode(currentInputMode);
    }

    private boolean isOverlayToggleElement(AbstractControlElement element) {
        for (GLFWBinding binding : element.getBindings()) {
            if (binding == GLFWBinding.UI_TOGGLE_OVERLAY) return true;
        }
        return false;
    }

    private boolean isKeyboardToggleElement(AbstractControlElement element) {
        for (GLFWBinding binding : element.getBindings()) {
            if (binding == GLFWBinding.UI_TOGGLE_KEYBOARD) return true;
        }
        return false;
    }

    public interface KeyboardToggleListener {
        void onToggleSystemKeyboard();
    }

    public void setKeyboardToggleListener(KeyboardToggleListener listener) {
        this.keyboardToggleListener = listener;
    }

    /** Release every held input (leaving the game, pausing, entering the editor). */
    public void resetAll() {
        for (AbstractControlElement el : controlElements) el.reset();
    }

    public List<AbstractControlElement> getControlElements() {
        return controlElements;
    }

    /** Selects the instance whose layout, icons and haptic setting this view uses. Call before
     *  the view is laid out: the layout is read on the first size change. */
    public void setInstanceName(String instanceName) {
        this.instanceName = instanceName;
        if (instanceName != null) {
            hapticEnabled = new InstanceSettings(instanceName).isHapticFeedback();
        } else {
            LauncherPreferences lp = LauncherPreferences.getSingleton();
            hapticEnabled = lp != null && lp.isHapticFeedback();
        }
    }

    // ============================== haptics ==============================

    /** Light haptic tick on a control press, only when the player enabled it (default off).
     *  Uses the Vibrator service directly rather than View.performHapticFeedback, because the latter
     *  is silently ignored when the system-wide "touch vibration" setting is OFF (common default on
     *  tablets, e.g. Legion Y700) and modern Android offers no flag to override that global setting.
     *  A direct one-shot vibration fires regardless (needs the VIBRATE permission). */
    public void maybeHaptic() {
        if (!hapticEnabled) return;
        try {
            android.os.Vibrator vib;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager vm =
                        (android.os.VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vib = (vm != null) ? vm.getDefaultVibrator() : null;
            } else {
                vib = (android.os.Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vib == null || !vib.hasVibrator()) {
                // No motor (or none exposed) — fall back to the view-level feedback, best effort.
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
                return;
            }
            // Short, light tick. EFFECT_TICK is the subtlest predefined effect (API 29+).
            vib.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK));
        } catch (Throwable t) {
            // Never let a haptic glitch crash input handling.
            try {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
            } catch (Throwable ignored) { }
        }
    }

    // ============================== cursor ==============================

    public void setRenderScale(float rs) {
        if (rs <= 0f) rs = 1f;
        this.renderScale = rs;
        InputSink.setGeometry(renderScale, gameLeft, gameTop, gameW, gameH);
    }

    public float getRenderScale() {
        return renderScale;
    }

    /** The letterboxed game rect (view px), from GameActivity; cursor and taps map into it. */
    public void setGameRect(int left, int top, int w, int h) {
        gameLeft = left; gameTop = top; gameW = w; gameH = h;
        InputSink.setGeometry(renderScale, gameLeft, gameTop, gameW, gameH);
        centreCursor();
    }

    private void centreCursor() {
        if (gameW > 0) { curX = gameLeft + gameW / 2f; curY = gameTop + gameH / 2f; }
        else if (getWidth() > 0) { curX = getWidth() / 2f; curY = getHeight() / 2f; }
    }

    public float getCursorX() { return curX; }
    public float getCursorY() { return curY; }

    /** Move the shared cursor by a view-px delta (touchpad, mouse stick, a gamepad stick). */
    public void moveCursorBy(float dx, float dy) {
        if (curX < 0) centreCursor();
        moveCursorTo(curX + dx, curY + dy);
    }

    /** Put the shared cursor at a view position (physical mouse, a tap on the bare game). It stays
     *  inside the game rect, so it lines up with the game's own cursor and never enters the bars. */
    public void moveCursorTo(float x, float y) {
        float minX = gameW > 0 ? gameLeft : 0;
        float maxX = gameW > 0 ? gameLeft + gameW - 1 : getWidth() - 1;
        float minY = gameH > 0 ? gameTop : 0;
        float maxY = gameH > 0 ? gameTop + gameH - 1 : getHeight() - 1;
        curX = Math.max(minX, Math.min(maxX, x));
        curY = Math.max(minY, Math.min(maxY, y));
        InputSink.sendCursorPos(curX * renderScale, curY * renderScale);
        invalidate();   // the arrow is drawn here; the gamepad path has no touch to trigger a redraw
    }

    /** A quick click of a mouse button where the cursor is. */
    public void clickCursor(GLFWBinding button) {
        InputSink.sendMouseButton(button.code, true);
        postDelayed(() -> InputSink.sendMouseButton(button.code, false), 50);
    }

    /** Move the cursor to a view position and click there (taps on the bare game). */
    public void tapAt(float x, float y, GLFWBinding button) {
        moveCursorTo(x, y);
        clickCursor(button);
    }

    /** The arrow only makes sense when something on screen moves or clicks it. */
    private boolean hasMouseElement() {
        for (AbstractControlElement el : controlElements) {
            if (el instanceof TouchpadControlElement || el instanceof MouseStickControlElement) return true;
            for (GLFWBinding b : el.getBindings()) {
                if (b.name().startsWith("MOUSE_BUTTON_")) return true;
            }
        }
        return false;
    }

    private void drawCursor(Canvas c) {
        float s = getResources().getDisplayMetrics().density, w = 17 * s / 1.5f, h = 25 * s / 1.5f;
        Path p = new Path();
        p.moveTo(curX, curY);
        p.lineTo(curX + w, curY + h * 0.85f);
        p.lineTo(curX + w * 0.55f, curY + h - w * 0.28f);
        p.lineTo(curX + w * 0.15f, curY + h);
        p.close();
        c.drawPath(p, curStroke);
        c.drawPath(p, curFill);
    }

    // ============================== legacy bindings ==============================
    // GamepadHandler's keyboard/mouse fallback, MouseKeyboardHandler and GameActivity's drag-pan
    // still speak the old com.valdroid.input.Binding. Translated here so they share this cursor.

    public void inject(Binding b, boolean pressed) {
        if (b == null) return;
        switch (b.kind) {
            case SPECIAL:
                if (!pressed) return;
                if (b == Binding.TOGGLE_CONTROLS) toggleOverlayVisibility();
                else if (b == Binding.TOGGLE_KEYBOARD) showTextInputOverlay();
                return;
            case MOUSE:   // old numbering: 1 left, 2 middle, 3 right
                InputSink.sendMouseButton(b.code == 3 ? 1 : (b.code == 2 ? 2 : 0), pressed);
                return;
            case SCROLL:
                if (pressed) InputSink.sendMouseScroll(0, b.code);
                return;
            case KEY:
                GameActivity.keyInput(b.code, b.keycode, pressed ? 1 : 0);
                return;
            case GP_BUTTON:
                try { VirtualGamepad.button(b.code, pressed); VirtualGamepad.sync(); }
                catch (UnsatisfiedLinkError ignored) {}
                return;
            case GP_TRIGGER:
                try { VirtualGamepad.trigger(b.code, pressed ? 1f : 0f); VirtualGamepad.sync(); }
                catch (UnsatisfiedLinkError ignored) {}
                return;
            case GP_DPAD:
                AbstractControlElement.updateDpadMask(b.code, pressed ? b.code : 0);
                return;
            default:
        }
    }

    /** Quick press+release of a legacy binding. */
    public void tapCursor(Binding b) {
        inject(b, true);
        postDelayed(() -> inject(b, false), 50);
    }
}

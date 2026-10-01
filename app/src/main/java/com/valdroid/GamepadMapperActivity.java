// Derived from Zomdroid (MIT) — see NOTICE.
package com.valdroid;

import android.graphics.Insets;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.valdroid.input.GamepadMapping;

/**
 * Step-by-step gamepad remapper (ported from Zomdroid's GamepadMapperFragment, adapted to
 * ValDroid's Activity-based editors). The user presses the physical button they want to act as each
 * LOGICAL button (A/B/X/Y/…); {@link GamepadMapping} stores physical→logical so a controller with
 * swapped/“inverted” buttons works.
 *
 * After the buttons come two trigger steps, LT then RT (Zomdroid's addition). A trigger is recorded
 * either as the analog AXIS that moved (and, unlike Zomdroid, WHICH Android axis — that is how a pad
 * whose triggers sit on Z/RZ, where we normally expect the right stick, is fixed by hand) or as the
 * BUTTON keycode it sends. Sticks and the D-pad are not remapped.
 */
public class GamepadMapperActivity extends AppCompatActivity {

    // Trigger capture. Any of these axes may be a trigger on some pad; X/Y (left stick) and the hat
    // (D-pad) never are. Order is the preference when one press moves several (LTRIGGER + BRAKE).
    private static final int[] TRIGGER_AXES = {
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_GENERIC_1, MotionEvent.AXIS_GENERIC_2, MotionEvent.AXIS_GENERIC_3, MotionEvent.AXIS_GENERIC_4
    };
    /** Travel past the resting value, as a fraction of the axis range, that completes the step. */
    private static final float TRIGGER_TRAVEL = 0.5f;
    /** How close to the bottom of its range (fraction) an axis must rest to count as a trigger. */
    private static final float REST_NEAR_MIN = 0.25f;

    private int currentStep = 0;
    private boolean mappingActive = false;
    private int[] mapping;              // logical index → physical keycode (full length COUNT)
    private int ltCode, rtCode;         // GamepadMapping trigger codes recorded this run
    private String[] labels;           // per wizard step: the buttons, then LT, RT

    // Per trigger step: each candidate axis's value when the step began (NaN = not seen yet), from
    // which device, and a trigger key pressed but not yet released (see dispatchKeyEvent).
    private float[] rest;
    private int restDeviceId = -1;
    private int pendingKey = -1;

    private TextView stepLabel;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_gamepad_mapper);
        GamepadMapping.load(this);   // start from the saved mapping, not whatever this process last held

        // Edge-to-edge (Android 15+ draws under the system bars): pad the root by the status-bar
        // (top) and navigation-bar (bottom) insets so the toolbar/back isn't under the notification
        // bar and the buttons aren't eaten by the nav bar. Same approach as LauncherActivity.
        findViewById(R.id.mapper_root).setOnApplyWindowInsetsListener((v, wi) -> {
            Insets bars = wi.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return wi;
        });

        MaterialToolbar toolbar = findViewById(R.id.mapper_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());   // top-left back arrow → return to Settings

        stepLabel = findViewById(R.id.gamepad_mapper_step_label);
        startButton = findViewById(R.id.gamepad_mapper_start_button);
        Button resetButton = findViewById(R.id.gamepad_mapper_reset_button);

        labels = getResources().getStringArray(R.array.gamepad_mapper_labels);

        startButton.setOnClickListener(v -> beginMapping());
        resetButton.setOnClickListener(v -> {
            GamepadMapping.reset(this);   // buttons to identity, triggers back to auto
            mappingActive = false;
            startButton.setVisibility(Button.VISIBLE);
            stepLabel.setText(R.string.gamepad_mapper_instructions);
            Toast.makeText(this, R.string.gamepad_mapper_reset_done, Toast.LENGTH_SHORT).show();
        });

        stepLabel.setText(R.string.gamepad_mapper_instructions);
    }

    private static int buttonSteps() { return GamepadMapping.WIZARD_ORDER.length; }
    private static int totalSteps()  { return buttonSteps() + 2; }   // + LT, RT
    private boolean isTriggerStep()  { return currentStep >= buttonSteps() && currentStep < totalSteps(); }
    private boolean isLtStep()       { return currentStep == buttonSteps(); }

    private void beginMapping() {
        mapping = GamepadMapping.get().clone();   // keep GUIDE etc.; overwrite wizard slots as we go
        ltCode = rtCode = GamepadMapping.TRIGGER_AUTO;
        currentStep = 0;
        mappingActive = true;
        startButton.setVisibility(Button.GONE);
        showStep();
    }

    private void showStep() {
        if (currentStep < totalSteps()) {
            String label = currentStep < labels.length ? labels[currentStep]
                    : (isLtStep() ? "LT" : "RT");   // only if a locale's array were short
            stepLabel.setText(getString(R.string.gamepad_mapper_step, label));
        }
        rest = null;
        restDeviceId = -1;
        pendingKey = -1;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mappingActive && isFromGamepad(event.getSource()) && event.getRepeatCount() == 0) {
            if (isTriggerStep()) {
                // A trigger key is taken on RELEASE, not on press: many pads send L2/R2 together
                // with an analog axis, and the axis (seen in between) is the better binding.
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (isUsableTriggerKey(event.getKeyCode())) pendingKey = event.getKeyCode();
                    else Toast.makeText(this, R.string.gamepad_mapper_dup, Toast.LENGTH_SHORT).show();
                } else if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == pendingKey) {
                    assignTrigger(GamepadMapping.encodeButton(pendingKey));
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                handlePress(event.getKeyCode());
                return true;   // consume so it isn't treated as navigation/back
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mappingActive && isTriggerStep() && isFromGamepad(event.getSource())
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            handleTriggerMotion(event);
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    private void handlePress(int keyCode) {
        // reject a keycode already assigned to an earlier step this run
        for (int i = 0; i < currentStep; i++) {
            if (mapping[GamepadMapping.WIZARD_ORDER[i]] == keyCode) {
                Toast.makeText(this, R.string.gamepad_mapper_dup, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mapping[GamepadMapping.WIZARD_ORDER[currentStep]] = keyCode;
        currentStep++;
        if (currentStep == buttonSteps()) {
            // Save the buttons now, before the trigger steps: if a trigger cannot be recorded and
            // the user backs out, the button remap is not lost with it.
            GamepadMapping.save(this, mapping);
        }
        showStep();
    }

    /** A key may be a trigger unless it is the D-pad or already used by a button / LT this run. */
    private boolean isUsableTriggerKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return false;
        }
        for (int logical : GamepadMapping.WIZARD_ORDER) if (mapping[logical] == keyCode) return false;
        return isLtStep() || ltCode != GamepadMapping.encodeButton(keyCode);
    }

    /**
     * Record the trigger axis the user pressed. The hard part is not accepting a STICK: Z/RZ/RX/RY
     * are the right stick on one pad and the triggers on another, so they must be candidates here,
     * and nudging the right stick must still not complete the step (Zomdroid's wizard closed its LT
     * step on a stick nudge, then ran to the end on a garbage binding). What separates them is where
     * the axis rests: a trigger rests at the BOTTOM of its range (0 on 0..1, -1 on the -1..1 that
     * Android gives Z/RZ/RX/RY), a stick rests in the MIDDLE. So the step takes the first event's
     * values as the resting values and only accepts an axis that rested near its minimum and then
     * travelled at least half its range upward.
     */
    private void handleTriggerMotion(MotionEvent ev) {
        InputDevice d = ev.getDevice();
        if (d == null) return;
        if (rest == null || restDeviceId != ev.getDeviceId()) {
            rest = new float[TRIGGER_AXES.length];
            for (int i = 0; i < TRIGGER_AXES.length; i++) rest[i] = ev.getAxisValue(TRIGGER_AXES[i]);
            restDeviceId = ev.getDeviceId();
            return;
        }
        for (int i = 0; i < TRIGGER_AXES.length; i++) {
            int ax = TRIGGER_AXES[i];
            InputDevice.MotionRange r = d.getMotionRange(ax, InputDevice.SOURCE_JOYSTICK);
            if (r == null) r = d.getMotionRange(ax);
            if (r == null || r.getRange() <= 0f) continue;
            if (rest[i] - r.getMin() > REST_NEAR_MIN * r.getRange()) continue;   // rests mid-range: a stick
            if (ev.getAxisValue(ax) - rest[i] < TRIGGER_TRAVEL * r.getRange()) continue;
            int code = GamepadMapping.encodeAxis(ax);
            if (!isLtStep() && code == ltCode) {   // the LT axis again (pressed both triggers?)
                Toast.makeText(this, R.string.gamepad_mapper_dup, Toast.LENGTH_SHORT).show();
                rest = null;   // re-read the resting values before trying again
                return;
            }
            assignTrigger(code);
            return;
        }
    }

    private void assignTrigger(int code) {
        if (isLtStep()) ltCode = code; else rtCode = code;
        currentStep++;
        if (currentStep >= totalSteps()) {
            GamepadMapping.saveTriggers(this, ltCode, rtCode);
            mappingActive = false;
            startButton.setVisibility(Button.VISIBLE);
            stepLabel.setText(R.string.gamepad_mapper_done);
            Toast.makeText(this, R.string.gamepad_mapper_done, Toast.LENGTH_SHORT).show();
        } else {
            showStep();
        }
    }

    private static boolean isFromGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_DPAD)     == InputDevice.SOURCE_DPAD;
    }
}

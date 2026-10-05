package com.valdroid;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.valdroid.controls.InputControlsView;
import com.valdroid.controls.InputSink;
import com.valdroid.game.NativeEngine;
import com.valdroid.game.NativeInput;

/**
 * What the native engine shows over Unity's player (NativeUnityActivity creates it by name, in the
 * ":unity" process): the instance's on-screen controls, the soft keyboard and the in-game HUD, as in
 * GameActivity. The controls' input goes to {@link NativeInput} instead of the X server. Touches no
 * control takes fall through to the player, so menus still work by touch.
 */
public class NativeGameOverlay extends FrameLayout {
    private final InputControlsView controls;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // HUD, global setting (Settings → Frame rate): off, the classic FPS counter or the full bar.
    private TextView fpsText;
    private long fpsLastCount, fpsLastTimeMs;
    private PerfOverlayView perfView;
    private PerfSampler perfSampler;
    private HandlerThread perfThread;
    private Handler perfHandler;

    public NativeGameOverlay(Context context) {
        super(context);
        String instanceName = context instanceof Activity
                ? ((Activity) context).getIntent().getStringExtra(NativeEngine.EXTRA_INSTANCE) : null;
        // The game's own log for "Report a bug": first, so it has as much of the start as possible.
        AppStorage storage = AppStorage.getSingleton();
        if (storage != null && instanceName != null)
            com.valdroid.game.NativeLog.start(storage.getInstanceDir(instanceName));
        if (context instanceof Activity) applyWindowSettings((Activity) context, instanceName);

        InputSink.setBackend(new NativeInput());
        controls = new InputControlsView(context);
        controls.setInstanceName(instanceName);   // before layout: the layout is read on the first size change
        controls.setRenderScale(1f);              // the cursor is sent as a fraction of the screen
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        KeyboardCatcher keyboardCatcher = new KeyboardCatcher(context);
        addView(keyboardCatcher, new LayoutParams(1, 1));   // 1px, invisible
        controls.setKeyboardToggleListener(keyboardCatcher::toggle);

        int hud = LauncherPreferences.getSingleton() != null
                ? LauncherPreferences.getSingleton().getHudMode() : LauncherPreferences.HUD_OFF;
        if (hud == LauncherPreferences.HUD_FPS) addFpsText(context);
        if (hud == LauncherPreferences.HUD_FULL) addPerfBar(context);
    }

    /**
     * As GameActivity: one fixed landscape (mirrored on request, for gamepad cradles that hold the
     * phone the other way round) and the display mode the frame-rate mode picked.
     */
    private static void applyWindowSettings(Activity activity, String instanceName) {
        boolean reverse = instanceName != null && new InstanceSettings(instanceName).isReverseLandscape();
        activity.setRequestedOrientation(reverse
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        int modeId = activity.getIntent().getIntExtra(NativeEngine.EXTRA_DISPLAY_MODE, 0);
        if (modeId != 0) {
            android.view.WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.preferredDisplayModeId = modeId;
            activity.getWindow().setAttributes(lp);
        }
    }

    /** Classic counter ("FPS: XX", top-left), as in GameActivity. */
    private void addFpsText(Context context) {
        fpsText = new TextView(context);
        fpsText.setText("FPS: --");
        fpsText.setTextColor(0xFF00FF66);                 // green, readable over any scene
        fpsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fpsText.setShadowLayer(4f, 0f, 0f, 0xFF000000);   // outline so it reads on light scenes
        fpsText.setPadding(0, 0, 0, 0);
        final int m = Math.round(8 * getResources().getDisplayMetrics().density);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(m, m, 0, 0);
        addView(fpsText, lp);
        // Shift it RIGHT by its own width once laid out, so it clears the game's top-left HUD.
        fpsText.post(() -> {
            LayoutParams p = (LayoutParams) fpsText.getLayoutParams();
            p.leftMargin = m + fpsText.getWidth();
            fpsText.setLayoutParams(p);
        });
    }

    /** Full performance bar, top-centre; the native engine always renders with Vulkan. */
    private void addPerfBar(Context context) {
        perfView = new PerfOverlayView(context, "VK");
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, Math.round(4 * getResources().getDisplayMetrics().density), 0, 0);
        addView(perfView, lp);
        perfSampler = new PerfSampler(context);
        perfThread = new HandlerThread("PerfOverlay");
        perfThread.start();
        perfHandler = new Handler(perfThread.getLooper());
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        controls.setGameRect(0, 0, w, h);   // the player fills the screen
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) startHud();
        else {
            stopHud();
            controls.resetAll();   // nothing stays held while the game is in the background
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopHud();
        controls.resetAll();
        if (perfThread != null) perfThread.quitSafely();
        super.onDetachedFromWindow();
    }

    private void startHud() {
        if (fpsText != null) {
            fpsLastTimeMs = 0;                          // the first interval would be skewed
            ui.removeCallbacks(fpsTextTick);
            ui.postDelayed(fpsTextTick, 1000);
        }
        if (perfHandler != null) {
            perfHandler.removeCallbacks(perfTick);
            perfHandler.post(perfTick);                 // first sample only primes the deltas
        }
    }

    private void stopHud() {
        ui.removeCallbacks(fpsTextTick);
        if (perfHandler != null) perfHandler.removeCallbacks(perfTick);
    }

    // Frames are counted by the bridge (one per rendered frame), so this is the game's real rate.
    private static long frameCount() {
        try {
            return NativeInput.frameCount();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    private final Runnable fpsTextTick = new Runnable() {
        @Override public void run() {
            if (fpsText == null) return;
            long now = SystemClock.elapsedRealtime();
            long count = frameCount();
            if (count < 0) return;
            if (fpsLastTimeMs != 0) {
                long dFrames = count - fpsLastCount;
                long dMs = now - fpsLastTimeMs;
                if (dMs > 0) fpsText.setText("FPS: " + Math.round(dFrames * 1000.0 / dMs));
            }
            fpsLastCount = count;
            fpsLastTimeMs = now;
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable perfTick = new Runnable() {
        @Override public void run() {
            if (perfView == null || perfSampler == null || perfHandler == null) return;
            final PerfSampler.Stats stats = perfSampler.sample(frameCount());
            ui.post(() -> { if (perfView != null) perfView.setStats(stats); });
            perfHandler.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ soft keyboard

    /**
     * Invisible view that owns the IME connection. It is a "dummy" editor (BaseInputConnection without
     * a full editor): Android turns committed text into key events itself, single mappable characters
     * as ordinary key presses and anything else as one ACTION_MULTIPLE event carrying the characters.
     * They reach the player like a hardware keyboard's (NativeUnityActivity forwards ACTION_MULTIPLE),
     * and the player makes the text for input fields, the same path as "adb shell input text".
     */
    private static final class KeyboardCatcher extends View {
        private boolean accepting;

        KeyboardCatcher(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        /** Show the keyboard if hidden, hide it if shown. Bound to the on-screen TOGGLE_KEYBOARD button. */
        void toggle() {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            accepting = !accepting;
            requestFocus();
            imm.restartInput(this);
            if (accepting) imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
            else imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }

        @Override public boolean onCheckIsTextEditor() { return accepting; }

        @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (!accepting) return null;
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new BaseInputConnection(this, false) {
                // The dummy editor holds no text, so a delete would remove nothing: send Backspace.
                @Override public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < before; i++) {
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                        sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                    }
                    return true;
                }
            };
        }
    }
}

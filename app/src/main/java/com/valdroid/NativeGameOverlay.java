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
public class NativeGameOverlay extends FrameLayout implements View.OnKeyListener, View.OnGenericMotionListener {
    private final InputControlsView controls;
    // A physical controller, as in GameActivity: its buttons and sticks drive the game's virtual gamepad
    // (VirtualGamepad routes to InputSink's backend here). NativeUnityActivity offers us its events.
    private final com.valdroid.input.GamepadHandler gamepad;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private java.io.File snapshotDir;   // the instance folder: native_engine.log and the snapshots
    private int snapshotCount;

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
        if (storage != null && instanceName != null) {
            snapshotDir = storage.getInstanceDir(instanceName);
            com.valdroid.game.NativeLog.start(snapshotDir);
        }
        if (context instanceof Activity) applyWindowSettings((Activity) context, instanceName);

        InputSink.setBackend(new NativeInput());
        controls = new InputControlsView(context);
        controls.setInstanceName(instanceName);   // before layout: the layout is read on the first size change
        controls.setRenderScale(1f);              // the cursor is sent as a fraction of the screen
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        KeyboardCatcher keyboardCatcher = new KeyboardCatcher(context);
        addView(keyboardCatcher, new LayoutParams(1, 1));   // 1px, invisible
        controls.setKeyboardToggleListener(keyboardCatcher::toggle);

        gamepad = context instanceof Activity ? new com.valdroid.input.GamepadHandler((Activity) context, controls) : null;

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
        if (visibility == VISIBLE) {
            startHud();
            if (gamepad != null) gamepad.start();
            startDeviceWatch();
        } else {
            stopHud();
            stopDeviceWatch();
            if (gamepad != null) gamepad.stop();   // releases whatever the controller held
            controls.resetAll();   // nothing stays held while the game is in the background
        }
    }

    // ------------------------------------------------------------------ connected devices

    // As GameActivity: a physical gamepad hides the on-screen gamepad elements, a physical keyboard all
    // of them; both come back when the device goes.
    private android.hardware.input.InputManager inputManager;
    private boolean lastPadConnected;
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) {
                    if (gamepad != null) gamepad.onInputDeviceChanged(id, "connected");
                    refreshDevices();
                }
                @Override public void onInputDeviceRemoved(int id) {
                    if (gamepad != null) gamepad.onInputDeviceRemoved(id);
                    refreshDevices();
                }
                @Override public void onInputDeviceChanged(int id) {
                    if (gamepad != null) gamepad.onInputDeviceChanged(id, "changed");
                    refreshDevices();
                }
            };

    private void startDeviceWatch() {
        if (inputManager == null)
            inputManager = (android.hardware.input.InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(deviceListener, null);
        lastPadConnected = !com.valdroid.input.GamepadHandler.hasConnectedGamepad();   // force the first apply
        refreshDevices();
        if (gamepad != null) gamepad.logConnectedGamepads("game start");
    }

    private void stopDeviceWatch() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
    }

    private void refreshDevices() {
        controls.setKeyboardConnected(com.valdroid.input.GamepadHandler.hasExternalKeyboard());
        boolean pad = com.valdroid.input.GamepadHandler.hasConnectedGamepad();
        if (pad == lastPadConnected) return;   // only on a change: never undo the manual hide toggle
        lastPadConnected = pad;
        controls.setGamepadConnected(pad);
    }

    @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
        return gamepad != null && gamepad.onKey(event);
    }

    @Override public boolean onGenericMotion(View v, android.view.MotionEvent event) {
        return gamepad != null && gamepad.onMotion(event);
    }

    @Override protected void onDetachedFromWindow() {
        stopDeviceWatch();
        if (gamepad != null) gamepad.stop();
        stopHud();
        controls.resetAll();
        if (perfThread != null) perfThread.quitSafely();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ snapshots

    /** Files the snapshots go to, in turn; "Report a bug" attaches them (LogExporter). */
    public static final String[] SNAPSHOT_FILES = { "native_snapshot_1.jpg", "native_snapshot_2.jpg", "native_snapshot_3.jpg" };
    private static final long SNAPSHOT_PERIOD_MS = 10_000;
    private static final int SNAPSHOT_WIDTH = 480;

    /**
     * Every few seconds a small copy of what is in Unity's own surface right now (PixelCopy), kept as
     * the last three JPEGs in the instance folder, with a line in the log. For a phone where the screen
     * shows something else than the game: the copy tells whether Unity's surface still holds that
     * picture or the game moved on and the picture never reached the screen.
     */
    private final Runnable snapshotTick = new Runnable() {
        @Override public void run() {
            takeSnapshot();
            ui.postDelayed(this, SNAPSHOT_PERIOD_MS);
        }
    };

    private void takeSnapshot() {
        if (snapshotDir == null) return;
        android.view.SurfaceView surface = findSurfaceView(getRootView());
        if (surface == null || surface.getWidth() <= 0 || !surface.getHolder().getSurface().isValid()) {
            android.util.Log.w("ValDroid/Snapshot", "snapshot: no usable SurfaceView ("
                    + (surface == null ? "none in the window" : surface.getWidth() + "x" + surface.getHeight()) + ")");
            return;
        }
        int h = Math.max(1, Math.round((float) SNAPSHOT_WIDTH * surface.getHeight() / surface.getWidth()));
        final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(SNAPSHOT_WIDTH, h, android.graphics.Bitmap.Config.ARGB_8888);
        final int n = snapshotCount++;
        try {
            android.view.PixelCopy.request(surface, bmp, result -> saveSnapshot(n, bmp, result), ui);
        } catch (RuntimeException e) {
            android.util.Log.w("ValDroid/Snapshot", "snapshot " + n + " failed: " + e);
        }
    }

    /** Unity's SurfaceView, wherever the player put it in the window. */
    private static android.view.SurfaceView findSurfaceView(View v) {
        if (v instanceof android.view.SurfaceView) return (android.view.SurfaceView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.SurfaceView found = findSurfaceView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void saveSnapshot(int n, android.graphics.Bitmap bmp, int result) {
        if (result != android.view.PixelCopy.SUCCESS) {
            android.util.Log.w("ValDroid/Snapshot", "snapshot " + n + ": PixelCopy result " + result);
            return;
        }
        // A cheap fingerprint, so the log alone shows whether the picture in the surface changes.
        long sum = 0;
        for (int y = 0; y < bmp.getHeight(); y += 8)
            for (int x = 0; x < bmp.getWidth(); x += 8) sum = sum * 31 + bmp.getPixel(x, y);
        java.io.File f = new java.io.File(snapshotDir, SNAPSHOT_FILES[n % SNAPSHOT_FILES.length]);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
        } catch (java.io.IOException e) {
            android.util.Log.w("ValDroid/Snapshot", "snapshot " + n + ": " + e);
            return;
        }
        android.util.Log.i("ValDroid/Snapshot", "snapshot " + n + " -> " + f.getName() + ", fingerprint "
                + Long.toHexString(sum));
    }

    private void startHud() {
        ui.removeCallbacks(snapshotTick);
        // Snapshots only for diagnosis (VALDROID_DIAG=1 in the environment field): copying a frame back
        // from the GPU and compressing it can show as a hitch on a weak phone.
        if ("1".equals(System.getenv("VALDROID_DIAG"))) ui.postDelayed(snapshotTick, SNAPSHOT_PERIOD_MS);
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
        ui.removeCallbacks(snapshotTick);
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

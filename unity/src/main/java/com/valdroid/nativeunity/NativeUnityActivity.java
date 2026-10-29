package com.valdroid.nativeunity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.unity3d.player.IUnityPermissionRequestSupport;
import com.unity3d.player.IUnityPlayerLifecycleEvents;
import com.unity3d.player.IUnityPlayerSupport;
import com.unity3d.player.PermissionRequest;
import com.unity3d.player.UnityPlayerForActivityOrService;

/**
 * Hosts Unity's native Android player in its own process (":unity"). The game's C# runs on ValDroid's
 * ARM64 Mono through il2mono (libil2cpp.so). Every lifecycle, focus and input callback is forwarded to
 * the player, as Unity's player activity contract requires.
 */
public class NativeUnityActivity extends Activity
        implements IUnityPlayerLifecycleEvents, IUnityPermissionRequestSupport, IUnityPlayerSupport {

    // Read by Unity's native code under this exact name.
    protected UnityPlayerForActivityOrService mUnityPlayer;

    @Override protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        // Before the player loads libmain/libunity/il2mono: il2mono reads its paths from the environment.
        applyEnvironment(getIntent().getStringArrayExtra(EXTRA_ENV));
        // Always, crossplay or not: hosting a crossplay world without the sign-in still makes the game
        // load Party, and Party without its Context aborts the process (it then just fails to log in).
        loadParty();
        backgroundText = getIntent().getStringExtra(EXTRA_BACKGROUND_TEXT);
        // The player reads its command line from the "unity" extra.
        getIntent().putExtra("unity", getIntent().getStringExtra("unity"));

        mUnityPlayer = new UnityPlayerForActivityOrService(this, this);
        // The player's own layout goes inside ours: a captured mouse (mouse look) is dispatched down the
        // focus path, to the player's view, and only a parent of it can offer those events to the overlay.
        FrameLayout player = mUnityPlayer.getFrameLayout();
        FrameLayout root = new FrameLayout(this) {
            @Override public boolean dispatchCapturedPointerEvent(MotionEvent event) {
                if (overlay instanceof View.OnCapturedPointerListener
                        && ((View.OnCapturedPointerListener) overlay).onCapturedPointer(overlay, event)) return true;
                return super.dispatchCapturedPointerEvent(event);
            }
        };
        root.addView(player, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        player.requestFocus();
        overlay = createOverlay(getIntent().getStringExtra(EXTRA_OVERLAY));
        if (overlay != null) {
            root.addView(overlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        keepScreenOn();
    }

    /** Intent extra: zip (stored) with assets/bin/Data/... the player mounts instead of this APK. */
    public static final String EXTRA_DATA_ARCHIVE = "valdroid.dataArchive";
    /** Intent extra: String[] of "KEY=VALUE" set in this process's environment (il2mono's paths). */
    public static final String EXTRA_ENV = "valdroid.env";
    /**
     * Intent extra: class name of a View (public constructor taking a Context) laid over the player:
     * the launcher's on-screen controls and HUD. By name, because the launcher depends on this module
     * and not the other way round. Touches the overlay does not take reach the player.
     */
    public static final String EXTRA_OVERLAY = "valdroid.overlay";
    /**
     * Intent extra: the instance keeps running off screen ("Keep running in the background"); the text
     * is the notification shown meanwhile, in the launcher's language. Absent = pause as usual.
     */
    public static final String EXTRA_BACKGROUND_TEXT = "valdroid.backgroundText";

    // The overlay, and through it the launcher's controller handling: a physical gamepad's buttons and
    // sticks are offered to it first (View.OnKeyListener / OnGenericMotionListener), because the game's
    // Input System, built for Linux, has no Android controller support.
    private View overlay;

    private View createOverlay(String className) {
        if (className == null) return null;
        try {
            return (View) Class.forName(className).getConstructor(Context.class).newInstance(this);
        } catch (ReflectiveOperationException | ClassCastException e) {
            android.util.Log.e("NativeUnity", "overlay " + className + " failed", e);
            return null;
        }
    }

    /**
     * Crossplay: PlayFab Party, the network library Valheim's PlayFabParty.dll calls, loaded from Java
     * before the game asks Mono for it. Its JNI_OnLoad keeps the Java VM and finds its Java classes
     * (HTTP, audio devices) through this class loader, which a dlopen from Mono would not give it; Mono
     * then gets the already loaded library (dllmap libParty.so -> libparty.so, NativeEngine). Its Java
     * side wants the app Context, which the library's own init provider would set in the main process
     * only. Its audio service is not declared: Valheim sends data only, and the service would switch
     * the phone's audio mode for voice chat, so binding it just fails.
     */
    private void loadParty() {
        try {
            System.loadLibrary("party");
            Context app = getApplicationContext();
            for (String cls : new String[]{ "com.microsoft.playfab.party.AudioDeviceChangeMonitoringService",
                    "com.microsoft.playfab.utilities.party.EventTracerHelperParty" })
                Class.forName(cls).getMethod("setContext", Context.class).invoke(null, app);
            android.util.Log.i("NativeUnity", "PlayFab Party loaded");
        } catch (Throwable t) {
            android.util.Log.e("NativeUnity", "PlayFab Party not loaded: crossplay cannot connect", t);
        }
    }

    private static void applyEnvironment(String[] env) {
        if (env == null) return;
        for (String entry : env) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            try {
                android.system.Os.setenv(entry.substring(0, eq), entry.substring(eq + 1), true);
            } catch (android.system.ErrnoException e) {
                android.util.Log.w("NativeUnity", "setenv " + entry.substring(0, eq) + " failed", e);
            }
        }
    }

    // Unity mounts the archive at getPackageCodePath() as its data source (assets/bin/Data). Pointing it
    // at an archive the launcher builds from the game instance keeps every game file out of the APK.
    // Native libraries are loaded from nativeLibraryDir and are not affected.
    @Override public String getPackageCodePath() {
        Intent intent = getIntent();
        String archive = intent != null ? intent.getStringExtra(EXTRA_DATA_ARCHIVE) : null;
        if (archive != null && new java.io.File(archive).isFile()) return archive;
        return super.getPackageCodePath();
    }

    // Desktop games never ask for this; il2mono also maps Screen.sleepTimeout to NeverSleep.
    private void keepScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override public UnityPlayerForActivityOrService getUnityPlayerConnection() { return mUnityPlayer; }

    @Override public void onUnityPlayerUnloaded() { moveTaskToBack(true); }

    @Override public void onUnityPlayerQuitted() { }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mUnityPlayer.newIntent(intent);
    }

    @Override protected void onDestroy() {
        BackgroundRunService.stop(this);
        mUnityPlayer.destroy();
        super.onDestroy();
    }

    // "Keep running in the background" (EXTRA_BACKGROUND_TEXT set): leaving the screen does not pause
    // the player, so a hosted world goes on; BackgroundRunService holds the process meanwhile. Unity's
    // own pause/resume pair is skipped as a whole, so the player never sees half of it.
    private boolean inBackground;
    private String backgroundText;   // read once: a later intent (the notification's) has no extras

    @Override protected void onStart() { super.onStart(); if (!inBackground) mUnityPlayer.onStart(); }
    @Override protected void onStop() { super.onStop(); if (!inBackground) mUnityPlayer.onStop(); }

    @Override protected void onPause() {
        super.onPause();
        if (backgroundText != null && !isFinishing()) {
            inBackground = true;
            BackgroundRunService.start(this, backgroundText);
            return;
        }
        mUnityPlayer.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (inBackground) {
            inBackground = false;
            BackgroundRunService.stop(this);
            return;
        }
        mUnityPlayer.onResume();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        mUnityPlayer.onTrimMemory(UnityPlayerForActivityOrService.MemoryUsage.Critical);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == TRIM_MEMORY_RUNNING_MODERATE)
            mUnityPlayer.onTrimMemory(UnityPlayerForActivityOrService.MemoryUsage.Medium);
        else if (level == TRIM_MEMORY_RUNNING_LOW)
            mUnityPlayer.onTrimMemory(UnityPlayerForActivityOrService.MemoryUsage.High);
        else if (level == TRIM_MEMORY_RUNNING_CRITICAL)
            mUnityPlayer.onTrimMemory(UnityPlayerForActivityOrService.MemoryUsage.Critical);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mUnityPlayer.configurationChanged(newConfig);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        mUnityPlayer.windowFocusChanged(hasFocus);
        if (hasFocus) keepScreenOn(); // the player resets the flag on start
    }

    // The NDK does not deliver ACTION_MULTIPLE key events; inject them.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_MULTIPLE) return mUnityPlayer.injectEvent(event);
        if (overlay instanceof View.OnKeyListener
                && ((View.OnKeyListener) overlay).onKey(overlay, event.getKeyCode(), event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (overlay instanceof View.OnGenericMotionListener
                && ((View.OnGenericMotionListener) overlay).onGenericMotion(overlay, event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public void requestPermissions(PermissionRequest request) { mUnityPlayer.addPermissionRequest(request); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        mUnityPlayer.permissionResponse(this, requestCode, permissions, grantResults);
    }

    // Events no view handled go straight to the player.
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return mUnityPlayer.getFrameLayout().onKeyUp(keyCode, event); }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) { return mUnityPlayer.getFrameLayout().onKeyDown(keyCode, event); }
    @Override public boolean onTouchEvent(MotionEvent event) { return mUnityPlayer.getFrameLayout().onTouchEvent(event); }
    @Override public boolean onGenericMotionEvent(MotionEvent event) { return mUnityPlayer.getFrameLayout().onGenericMotionEvent(event); }
}

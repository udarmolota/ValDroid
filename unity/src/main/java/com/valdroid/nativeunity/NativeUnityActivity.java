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
        // The player reads its command line from the "unity" extra.
        getIntent().putExtra("unity", getIntent().getStringExtra("unity"));

        mUnityPlayer = new UnityPlayerForActivityOrService(this, this);
        FrameLayout root = mUnityPlayer.getFrameLayout();
        setContentView(root);
        root.requestFocus();
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
        mUnityPlayer.destroy();
        super.onDestroy();
    }

    @Override protected void onStart() { super.onStart(); mUnityPlayer.onStart(); }
    @Override protected void onStop() { super.onStop(); mUnityPlayer.onStop(); }
    @Override protected void onPause() { super.onPause(); mUnityPlayer.onPause(); }
    @Override protected void onResume() { super.onResume(); mUnityPlayer.onResume(); }

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

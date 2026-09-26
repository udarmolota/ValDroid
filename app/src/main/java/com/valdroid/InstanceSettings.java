package com.valdroid;

import android.content.SharedPreferences;

import com.valdroid.LauncherPreferences.Renderer;
import com.valdroid.LauncherPreferences.VulkanDriverOption;

import java.util.List;

/**
 * Per-instance launch settings: renderer, Vulkan driver, debug, interpreter mode. Each instance
 * keeps its own (e.g. one instance on the System driver, another on Turnip; debug only on a test
 * instance). Stored in the shared prefs under an {@code inst:<name>:} prefix.
 *
 * Defaults fall back to the corresponding GLOBAL {@link LauncherPreferences} value, so existing
 * single-instance users keep their current setup on first run after the per-instance migration, and
 * brand-new instances start from the same sane defaults (ZINK_ZFA + System driver + debug off).
 *
 * Render scale and the on-screen controls layout remain GLOBAL (read by GameActivity / the controls
 * editor, which aren't instance-scoped) — by design.
 */
public class InstanceSettings {

    private final SharedPreferences p;
    private final String pfx;
    private final LauncherPreferences global;
    private final String instanceName;

    public InstanceSettings(String instanceName) {
        this.instanceName = instanceName;
        global = LauncherPreferences.requireSingleton();
        p = global.getSharedPrefs();
        pfx = "inst:" + instanceName + ":";
    }

    // --- Renderer ---
    public Renderer getRenderer() {
        String def = global.getRenderer().name();
        try { return Renderer.valueOf(p.getString(pfx + "renderer", def)); }
        catch (Exception e) { return Renderer.MOBILEGLUES; }
    }

    public void setRenderer(Renderer r) {
        p.edit().putString(pfx + "renderer", r.name()).apply();
    }

    // --- Vulkan driver (.so file name; "" = System / phone driver) ---
    public String getVulkanDriverSo() {
        return p.getString(pfx + "driver_so", global.getVulkanDriverSo());
    }

    public void setVulkanDriverSo(String soName) {
        p.edit().putString(pfx + "driver_so", soName).apply();
    }

    /** True if this instance has its OWN driver choice stored (vs. inheriting the global default). */
    public boolean hasExplicitDriver() {
        return p.contains(pfx + "driver_so");
    }

    /** Index of the selected driver in {@link LauncherPreferences#VULKAN_DRIVERS} (0 if unknown). */
    public int getVulkanDriverIndex() {
        String so = getVulkanDriverSo();
        List<VulkanDriverOption> drivers = LauncherPreferences.VULKAN_DRIVERS;
        for (int i = 0; i < drivers.size(); i++) {
            if (drivers.get(i).soName.equals(so)) return i;
        }
        return 0;
    }

    // --- Debug ---
    // Debug is a throwaway diagnostic (extra box64 logging), NOT something to inherit: new
    // instances always start with it OFF, regardless of the (now-vestigial) global debug flag.
    // Inheriting global.isDebug() used to turn debug ON for every new instance when the global
    // flag was left enabled from the old global-settings design — which slowed launches.
    public boolean isDebug() {
        return p.getBoolean(pfx + "debug", false);
    }

    public void setDebug(boolean v) {
        p.edit().putBoolean(pfx + "debug", v).apply();
    }

    // --- Drag-to-pan (move the camera by dragging the map with a finger). Default OFF (changed
    // 2026-07-23): with it on, a stray drag on the bare map moves the camera when the user meant to
    // tap; opt-in for those who want it. ---
    /**
     * Always off since 2026-09-21, and gone from the settings: drag-to-pan is RimWorld's map
     * camera (a finger drag presses the arrow keys). Valheim has no such camera, and a swipe
     * should not press keys.
     */
    public boolean isDragPan() {
        return false;
    }

    public void setDragPan(boolean v) {
        p.edit().putBoolean(pfx + "drag_pan", v).apply();
    }

    // --- Mirrored (180°-rotated) landscape. Still a FIXED orientation, just the opposite one:
    // a USB-C gamepad cradle holds the phone in one physical pose, which may be the reverse of ours,
    // making the game unplayable for those users. Deliberately NOT sensor-based — free rotation
    // re-exposes the flip-induced resolution drift (each flip fires surfaceChanged and could leave
    // the game stuck in a stretched menu), which is why GameActivity pins a single landscape. ---
    public boolean isReverseLandscape() {
        return p.getBoolean(pfx + "reverse_landscape", false);
    }

    public void setReverseLandscape(boolean v) {
        p.edit().putBoolean(pfx + "reverse_landscape", v).apply();
    }

    // --- A fixed monitor resolution instead of filling the screen, letterboxed with black margins.
    // Asked for by players coming from PC emulators, who wanted 720p specifically. Both modes keep
    // 720 lines (our readability floor) and differ only in shape: 16:9 suits ordinary phones, 4:3
    // suits near-square foldables, where 16:9 would waste a third of the screen. Fewer pixels than
    // any device-relative preset, and the margins give the on-screen buttons somewhere to sit that
    // isn't on top of the map. Overrides the render-scale setting while on. Default OFF. ---
    public static final int FIXED_NONE = 0, FIXED_720_16_9 = 1, FIXED_720_4_3 = 2;

    public int getFixedResMode() {
        return p.getInt(pfx + "fixed_res", FIXED_NONE);
    }

    public void setFixedResMode(int mode) {
        p.edit().putInt(pfx + "fixed_res", mode).apply();
    }

    // --- Frame-rate cap (0 = uncapped, else 30/60…). RimWorld is CPU-bound under emulation, so
    // capping the render rate evens out the FPS swings AND frees CPU for the simulation → steadier,
    // often higher TPS. Default 0 (uncapped). ---
    /**
     * Frame-rate MODE (FpsPlanner.OFF / ECONOMY / BALANCED / SMOOTH); the concrete number is picked
     * per screen at launch by FpsPlanner. Default OFF: testers first want to see their maximum.
     * An older fixed cap maps over: 30 -> Economy, 60 -> Smooth.
     */
    public int getFpsMode() {
        if (p.contains(pfx + "fps_mode")) return p.getInt(pfx + "fps_mode", FpsPlanner.OFF);
        int old = p.getInt(pfx + "fps_cap", 0);
        return old == 30 ? FpsPlanner.ECONOMY : old == 60 ? FpsPlanner.SMOOTH : FpsPlanner.OFF;
    }

    public void setFpsMode(int mode) {
        p.edit().putInt(pfx + "fps_mode", mode).apply();
    }

    /** Old fixed cap (0 / 30 / 60), still read by getFpsMode() for installs that set it. */
    public int getFpsCap() {
        return p.getInt(pfx + "fps_cap", 0);
    }

    public void setFpsCap(int fps) {
        p.edit().putInt(pfx + "fps_cap", fps).apply();
    }

    // --- Texture compression tier. The box64 GL shim drops top mip level(s) of big mipped 2D
    // textures — no recompression, the game already ships every smaller mip. Measured on a 5-DLC
    // 1.6 colony (peak of simultaneously live textures, ~500 MB unshrunk):
    //   NONE  — every big texture halved. The baseline everyone gets; visually indistinguishable.
    //   LOW   — plus quarter for >=4096 (the transient bake giants): ~349 MB saved with only 6
    //           textures deep-shifted; item/plant atlases stay at half.
    //   ULTRA — quarter from >=2048 too (36 textures, ~398 MB): the last ~50 MB and slightly more
    //           FPS (those atlases are sampled every frame), at the cost of blurry vegetation.
    // Pawn/animal atlases are FBO render targets — never touched at any tier. New pref key: the
    // old "tex_shrink" was a boolean, reading it as an int would throw. ---
    public static final int TEX_NONE = 0, TEX_LOW = 1, TEX_ULTRA = 2;

    /**
     * Always ULTRA since 2026-09-21: the choice was taken out of the UI. Switching tiers made no
     * difference anyone could feel in play, and one fixed value is one less thing to get wrong. The
     * stored value is ignored rather than migrated, so bringing the chooser back is a one-line
     * change.
     */
    public int getTexTier() {
        return TEX_ULTRA;
    }

    public void setTexTier(int tier) {
        p.edit().putInt(pfx + "tex_tier", tier).apply();
    }

    /**
     * Graphics preset written into the game's own settings before launch (see
     * ValheimInstanceSetup.applyGraphicsPreset). Valheim's own presets are built for a PC: even
     * "Very low" leaves on what costs the most under emulation (tessellation, shadows, draw
     * distance). KEEP = do not touch the game's settings at all.
     */
    public static final int GFX_KEEP = 0, GFX_LOW = 1, GFX_ULTRA = 2;

    /**
     * The graphics profile to write into the game on the NEXT launch, once — GFX_KEEP when there is
     * nothing to write. This replaced "stamp the preset on every launch", which silently undid
     * whatever the player changed in game.
     *
     * With no stored value: an instance whose game has never saved graphics settings gets
     * GFX_ULTRA, so it starts on the emulation-tuned profile without anyone opening the settings.
     * One whose game has saved them gets GFX_KEEP — it was played before. "Has saved them" is
     * decided by the GraphicsQualityMode key, NOT by the settings file existing: our own install
     * step creates that file, which made every new instance look played (see
     * ValheimInstanceSetup.hasGameGraphicsSettings).
     */
    public int getGraphicsPending() {
        if (p.contains(pfx + "gfx_pending")) return p.getInt(pfx + "gfx_pending", GFX_KEEP);
        java.io.File dir = com.valdroid.AppStorage.requireSingleton().getInstanceDir(instanceName);
        return com.valdroid.ValheimInstanceSetup.hasGameGraphicsSettings(dir) ? GFX_KEEP : GFX_ULTRA;
    }

    public void setGraphicsPending(int preset) {
        p.edit().putInt(pfx + "gfx_pending", preset).apply();
    }

    // --- ETC2 compression on the GL path (MobileGlues). Default ON. ---
    public boolean isEtc2Enabled() {
        return p.getBoolean(pfx + "etc2", true);
    }

    public void setEtc2Enabled(boolean on) {
        p.edit().putBoolean(pfx + "etc2", on).apply();
    }

    // --- Program binary cache on the GL path (MobileGlues). Default ON. ---
    // Linked shader programs are kept on disk (box64 RIMDROID_GLT_PROGCACHE), so a new effect
    // freezes the game only the first time it is ever seen, not once per session.
    public boolean isShaderCache() {
        // Off by default on Mali: there cached programs turned the water black (Mali-G57 tester,
        // 2026-09-26), while Adreno runs the cache for hours. The player can still turn it on.
        return p.getBoolean(pfx + "shader_cache", !GpuInfo.isMali());
    }

    public void setShaderCache(boolean on) {
        p.edit().putBoolean(pfx + "shader_cache", on).apply();
    }

    // --- Haptic feedback: light vibration tick on on-screen button presses. Default OFF. ---
    public boolean isHapticFeedback() {
        return p.getBoolean(pfx + "haptic", global.isHapticFeedback());
    }

    public void setHapticFeedback(boolean v) {
        p.edit().putBoolean(pfx + "haptic", v).apply();
    }

    // --- Interpreter mode (BOX64_DYNAREC=0 diagnostic; pref key kept for back-compat as "interpreter") ---
    public boolean isInterpreter() {
        return p.getBoolean(pfx + "interpreter", global.isStrictBarriers());
    }

    public void setInterpreter(boolean v) {
        p.edit().putBoolean(pfx + "interpreter", v).apply();
    }

    // --- Compatibility mode: box64 dynarec tuning that dodges the deep "won't launch past the loading
    // dots / black screen" bug on affected devices (Adreno 610/725, weak-Vulkan Mali). Discovered via a
    // tester: sets BOX64_DYNAREC_WEAKBARRIER=2 + BOX64_DYNAREC_X87DOUBLE=1 in GameLauncher (reshapes the
    // FP/barrier codegen so the bad pattern is avoided). Default OFF — devices that already launch keep the
    // safer/faster defaults; turn ON only if the game won't start. (A workaround, not the root fix.)
    public boolean isCompatibilityMode() {
        return p.getBoolean(pfx + "compat_mode", false);
    }

    public void setCompatibilityMode(boolean v) {
        p.edit().putBoolean(pfx + "compat_mode", v).apply();
    }

    // --- Native ARM64 Mono (experimental, RimWorld 1.6 only): the game's C# code runs on a native ARM64
    // build of Unity's Mono instead of the emulated x86_64 one (see com.valdroid.game.NativeMono and
    // box64 wrappedlibmonobdwgc.c). Default ON: the value is only stored once the player flips the
    // switch, so everyone who never touched it gets the native runtime. The switch is only shown (and
    // the setting only used) for a 1.6 instance when the runtime is packaged in this APK.
    public boolean isNativeMono() {
        return p.getBoolean(pfx + "native_mono", true);
    }

    public void setNativeMono(boolean v) {
        p.edit().putBoolean(pfx + "native_mono", v).apply();
    }

    // Mod support (BepInEx), the master switch on the Mods screen. Off by default: the loader is new,
    // and a player without mods should start exactly as before. Each mod's own on/off lives in the
    // file system (BepInEx/plugins vs plugins_off, see ModManager), not here.
    public boolean isModSupport() {
        return p.getBoolean(pfx + "mod_support", false);
    }

    public void setModSupport(boolean v) {
        p.edit().putBoolean(pfx + "mod_support", v).apply();
    }

    // Safety net for the native runtime (NativeMono.settlePreviousLaunch): when a launch with it started,
    // and how many launches in a row crashed early. Written with commit(): the process may die right after.
    public long getNativeMonoLaunchTime() {
        return p.getLong(pfx + "native_mono_launch_ms", 0L);
    }

    public void setNativeMonoLaunchTime(long millis) {
        if (millis <= 0) p.edit().remove(pfx + "native_mono_launch_ms").commit();
        else p.edit().putLong(pfx + "native_mono_launch_ms", millis).commit();
    }

    public int getNativeMonoFailures() {
        return p.getInt(pfx + "native_mono_failures", 0);
    }

    public void setNativeMonoFailures(int count) {
        if (count <= 0) p.edit().remove(pfx + "native_mono_failures").commit();
        else p.edit().putInt(pfx + "native_mono_failures", count).commit();
    }

    // --- Extra env vars (KEY=VALUE, space-separated). Per-instance, falls back to the global value. ---
    // Power-user / diagnostic knob applied last in GameLauncher, so it can OVERRIDE the box64 defaults.
    // E.g. "BOX64_DYNAREC_ALIGNED_ATOMICS=1" (Mali/Cortex save-corruption test) or
    // "BOX64_DYNAREC_STRONGMEM=2" (FPS A/B). Lets us test box64 knobs without a rebuild per variant.
    public String getEnvVars() {
        return p.getString(pfx + "env_vars", global.getEnvVars());
    }

    public void setEnvVars(String v) {
        if (v == null || v.trim().isEmpty()) p.edit().remove(pfx + "env_vars").apply();
        else p.edit().putString(pfx + "env_vars", v.trim()).apply();
    }

    // --- Render scale (per-instance; the device floor stays a global static) ---
    public int getRenderScalePercent() {
        int v = p.getInt(pfx + "render_scale_pct", global.getRenderScalePercent());
        return Math.max(LauncherPreferences.RENDER_SCALE_ABS_MIN, Math.min(100, v));
    }

    public void setRenderScalePercent(int pct) {
        p.edit().putInt(pfx + "render_scale_pct",
                Math.max(LauncherPreferences.RENDER_SCALE_ABS_MIN, Math.min(100, pct))).apply();
    }

    /**
     * For logs and bug reports: the scale actually applied on this display, e.g. "50% (1170x540)".
     * The stored value alone can mislead — the default is a "lowest possible" marker (25%) that is
     * raised to the device floor when applied.
     */
    public String describeRenderScale() {
        android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
        int sLong = Math.max(dm.widthPixels, dm.heightPixels), sShort = Math.min(dm.widthPixels, dm.heightPixels);
        int pct = LauncherPreferences.effectiveRenderScalePercent(getRenderScalePercent(), sLong, sShort);
        return pct + "% (" + Math.round(sLong * pct / 100f) + "x" + Math.round(sShort * pct / 100f)
                + ", stored " + getRenderScalePercent() + "%)";
    }

    /** Stored scale clamped to [per-device floor, 72%], as a 0..1 fraction. */
    public float getEffectiveRenderScale(int surfaceW, int surfaceH) {
        return LauncherPreferences.effectiveRenderScalePercent(getRenderScalePercent(), surfaceW, surfaceH) / 100f;
    }

    // --- On-screen controls layout (per-instance JSON) ---
    public String getControlsJson() {
        return p.getString(pfx + "controls", global.getControlsJson());
    }

    public void setControlsJson(String json) {
        p.edit().putString(pfx + "controls", json).apply();
    }

    public void clearControlsJson() {
        p.edit().remove(pfx + "controls").apply();
    }

    /** Remove all stored keys for an instance (call when the instance is deleted). */
    public static void delete(String instanceName) {
        SharedPreferences p = LauncherPreferences.requireSingleton().getSharedPrefs();
        String pfx = "inst:" + instanceName + ":";
        p.edit()
                .remove(pfx + "renderer")
                .remove(pfx + "driver_so")
                .remove(pfx + "debug")
                .remove(pfx + "interpreter")
                .remove(pfx + "compat_mode")
                .remove(pfx + "native_mono")
                .remove(pfx + "native_mono_launch_ms")
                .remove(pfx + "native_mono_failures")
                .remove(pfx + "mod_support")
                .remove(pfx + "env_vars")
                .remove(pfx + "haptic")
                .remove(pfx + "reverse_landscape")
                .remove(pfx + "fixed_res")
                .remove(pfx + "fps_cap")
                .remove(pfx + "fps_mode")
                .remove(pfx + "render_scale_pct")
                .remove(pfx + "controls")
                // A re-created instance of the same name must start over on the first-launch
                // profile, which only happens while gfx_pending is absent.
                .remove(pfx + "gfx_pending")
                .remove(pfx + "gfx_preset")
                .remove(pfx + "tex_tier")
                .remove(pfx + "etc2")
                .remove(pfx + "shader_cache")
                .apply();
    }
}


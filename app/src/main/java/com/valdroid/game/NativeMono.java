package com.valdroid.game;

import com.valdroid.AppStorage;

import java.io.File;

/**
 * Native ARM64 Mono (experimental): the x86_64 UnityPlayer keeps running under box64, but RimWorld's
 * managed code runs on a native ARM64 build of Unity's Mono. box64 routes the game's
 * libmonobdwgc-2.0.so to its wrapper when RIMDROID_NATIVE_MONO_PATH is set.
 *
 * The runtime is packaged as ordinary APK native libraries from app/src/main/jniLibs/arm64-v8a, so Android
 * extracts it next to libvaldroid.so: libmonobdwgc-2.0.so (Unity's Mono fork, commit c11bc9adba, built for
 * Android ARM64 and stripped), libmono-native.so from the same build, and libsteam_api.so, a stub whose
 * Steamworks functions all return 0. An APK built without them simply does not offer the switch.
 */
public final class NativeMono {
    private static final String RUNTIME_LIB = "libmonobdwgc-2.0.so";

    private NativeMono() {}

    /** Absolute path of the packaged ARM64 Mono, or null when this APK was built without it. */
    public static String runtimePath() {
        File lib = new File(AppStorage.requireSingleton().getLibraryPath(), RUNTIME_LIB);
        return lib.isFile() ? lib.getAbsolutePath() : null;
    }

    /**
     * Only RimWorld 1.6 (Unity 2022.3, the "rd_x11" runtime) is supported: the bridge's internal call
     * thunks are generated for that Unity version, and 1.5's Unity 2019 Mono is a different runtime.
     */
    public static boolean isSupported(GameInstance instance) {
        // Valheim (Unity 6000.0.75f1) ships the same Mono fork build as RimWorld 1.6, so the same runtime
        // serves it; its internal-call thunks are the ones generated into the box64 wrapper.
        return instance != null
                && runtimePath() != null
                && (new File(instance.getGamePath(), GameDescriptor.VALHEIM.executable()).isFile()
                    || new File(instance.getGamePath(), "rd_x11").exists());
    }

    // What box64 really loaded, written by the wrapper (rd_native_mono_status): "loaded", or
    // "failed: <dlerror>" when it fell back to the game's emulated x86 Mono without telling anyone.
    private static final String STATUS_FILE = ".native_mono_status";
    private static final String STATUS_SHOWN = ".native_mono_status_shown";

    public static File statusFile(GameInstance gi) { return new File(gi.getGamePath(), STATUS_FILE); }

    /** "loaded", "failed: ...", or null when unknown (the switch was off, or no launch since). */
    public static String readStatus(GameInstance gi) {
        File f = statusFile(gi);
        if (!f.isFile() || f.length() > 4096) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * The failure reason once, the first time it is asked for after a fallback, so the launcher can
     * tell the player; null otherwise. Marks it shown, so the notice does not repeat every visit.
     */
    public static String takeUnshownFailure(GameInstance gi) {
        String s = readStatus(gi);
        if (s == null || !s.startsWith("failed")) return null;
        File shown = new File(gi.getGamePath(), STATUS_SHOWN);
        if (shown.isFile() && shown.lastModified() >= statusFile(gi).lastModified()) return null;
        try { new java.io.FileOutputStream(shown).close(); } catch (java.io.IOException ignored) {}
        return s;
    }
}

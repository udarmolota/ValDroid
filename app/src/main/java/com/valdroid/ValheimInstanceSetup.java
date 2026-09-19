package com.valdroid;

import android.util.Log;

import com.valdroid.game.GameDescriptor;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Consumer;

/**
 * Post-install setup for Valheim instances, applied on install and re-applied (idempotently) for
 * existing instances at app start. Valheim refuses to run without a successful SteamAPI_Init(): with
 * no Steam client the Valve libsteam_api.so fails Init and FejdStartup quits. The instance therefore
 * gets the Goldberg Steam emulator (gbe_fork, LGPL-3.0) in place of Valve's library, configured for
 * offline single-player, plus a PlayerPrefs flag that stops the PlayFab auto-login from hammering the
 * network on every start. The emulator is downloaded from its GitHub release (pinned tag + SHA-256),
 * never bundled in the APK.
 */
public final class ValheimInstanceSetup {
    private static final String TAG = "ValDroid/ValheimSetup";

    // gbe_fork release the shim is taken from. Bump both together.
    private static final String GBE_TAG = "release-2026_09_16_2";
    private static final String GBE_URL =
            "https://github.com/Detanup01/gbe_fork/releases/download/" + GBE_TAG + "/emu-linux-release.tar.bz2";
    private static final String GBE_ENTRY = "release/regular/x64/libsteam_api.so";
    private static final String GBE_SHA256 =
            "874817c98a2f6adbfbb78576ed56ae2bf9acf83379a0eb0843b73ca6d7e53b3c";

    private static final String STEAM_LIB = "libsteam_api.so";
    /** Valve's original, kept OUTSIDE Plugins/: Unity dlopens every .so in that folder. */
    private static final String STEAM_LIB_BACKUP = "libsteam_api.so.valve";
    private static final String SHIM_CACHE_DIR = "steam_shim";

    private ValheimInstanceSetup() {}

    /**
     * Full setup for one instance. Never throws: a missing shim is reported through {@code progress}
     * and the instance is left playable-once-fixed rather than half-installed.
     * @return null on success, otherwise a warning for the user
     */
    public static String apply(File instanceDir, Consumer<String> progress) {
        String warning = null;
        try {
            installSteamShim(instanceDir, progress);
        } catch (Exception e) {
            Log.w(TAG, "Steam shim install failed", e);
            warning = "Steam shim not installed (" + e.getMessage()
                    + "). Valheim quits at startup without it; reopen the app online to retry.";
        }
        try {
            writeSteamSettings(instanceDir);
        } catch (IOException e) {
            Log.w(TAG, "steam_settings write failed", e);
        }
        try {
            setPlayerPrefInt(instanceDir, "ShouldTryAutoLogin", 0);
        } catch (IOException e) {
            Log.w(TAG, "PlayerPrefs write failed", e);
        }
        return warning;
    }

    /** Re-apply to every installed Valheim instance (app start, off the UI thread). */
    public static void reconcileExistingInstances(File instancesDir) {
        File[] dirs = instancesDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (File dir : dirs) {
            if (!new File(dir, GameDescriptor.VALHEIM.executable()).isFile()) continue;
            String warning = apply(dir, msg -> Log.i(TAG, dir.getName() + ": " + msg));
            if (warning != null) Log.w(TAG, dir.getName() + ": " + warning);
        }
    }

    // --- Steam shim ---------------------------------------------------------------------------

    private static void installSteamShim(File instanceDir, Consumer<String> progress) throws Exception {
        File plugins = new File(instanceDir, GameDescriptor.VALHEIM.dataDirectory() + "/Plugins");
        File target = new File(plugins, STEAM_LIB);
        File shim = ensureShimCached(progress);
        if (target.isFile() && sha256(target).equals(GBE_SHA256)) return;   // already in place

        // Keep Valve's library once, outside Plugins/. A stray backup inside Plugins/ would be
        // loaded by Unity as a second copy of the same library.
        File backup = new File(instanceDir, STEAM_LIB_BACKUP);
        if (target.isFile() && !backup.isFile()) copy(target, backup);
        File stale = new File(plugins, "libsteam_api.so.rdorig");
        if (stale.isFile()) { if (!backup.isFile()) copy(stale, backup); stale.delete(); }

        plugins.mkdirs();
        copy(shim, target);
        Log.i(TAG, "Steam shim installed into " + target);
    }

    /** Download the gbe_fork release once and keep only the x64 libsteam_api.so under files/. */
    private static File ensureShimCached(Consumer<String> progress) throws Exception {
        File dir = new File(AppStorage.requireSingleton().getHomePath(), SHIM_CACHE_DIR);
        File cached = new File(dir, STEAM_LIB);
        if (cached.isFile() && sha256(cached).equals(GBE_SHA256)) return cached;
        dir.mkdirs();
        progress.accept("Downloading Steam shim (gbe_fork " + GBE_TAG + ")...");
        File tmp = new File(dir, STEAM_LIB + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(GBE_URL).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " from GitHub");
        boolean found = false;
        try (InputStream raw = new BufferedInputStream(c.getInputStream(), 1 << 16);
             BZip2CompressorInputStream bz = new BZip2CompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(new BufferedInputStream(bz, 1 << 20))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(GBE_ENTRY)) continue;
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = tar.read(buf)) != -1) out.write(buf, 0, n);
                }
                found = true;
                break;
            }
        } finally {
            c.disconnect();
        }
        if (!found) throw new IOException(GBE_ENTRY + " not in the release archive");
        String got = sha256(tmp);
        if (!got.equals(GBE_SHA256)) {
            tmp.delete();
            throw new IOException("Steam shim checksum mismatch: " + got);
        }
        if (!tmp.renameTo(cached)) throw new IOException("cannot move " + tmp + " into place");
        return cached;
    }

    // --- steam_settings ----------------------------------------------------------------------

    private static void writeSteamSettings(File instanceDir) throws IOException {
        File dir = new File(instanceDir, GameDescriptor.VALHEIM.dataDirectory() + "/Plugins/steam_settings");
        dir.mkdirs();
        writeIfMissing(new File(dir, "steam_appid.txt"), GameDescriptor.VALHEIM.steamAppId() + "\n");
        // No sockets, no LAN broadcast, Steam "offline": the game is single-player here. steam_deck makes
        // the emulator answer IsSteamRunningOnSteamDeck() = true, which is what Valheim keys its Steam
        // Deck platform config on (the ARM64 stub for native Mono answers the same). Added to an
        // existing ini too, since instances set up before this key existed keep their file.
        File mainIni = new File(dir, "configs.main.ini");
        writeIfMissing(mainIni,
                "[main::connectivity]\ndisable_networking=1\ndisable_lan_only=1\noffline=1\n");
        String ini = new String(java.nio.file.Files.readAllBytes(mainIni.toPath()), StandardCharsets.UTF_8);
        if (!ini.contains("steam_deck=")) {
            java.nio.file.Files.write(mainIni.toPath(),
                    (ini + (ini.endsWith("\n") ? "" : "\n") + "[main::general]\nsteam_deck=1\n")
                            .getBytes(StandardCharsets.UTF_8));
        }
        writeIfMissing(new File(dir, "configs.user.ini"),
                "[user::general]\naccount_name=Viking\nlanguage=english\n");
    }

    // --- PlayerPrefs -------------------------------------------------------------------------

    /**
     * Unity's Linux PlayerPrefs live in {@code $XDG_CONFIG_HOME/unity3d/<Company>/<Product>/prefs}
     * (the launcher points XDG_CONFIG_HOME at the instance) as a small XML file. Under box64 Unity 6
     * resolves company/product as "unknown/unknown" (seen on device: Screenmanager keys land there),
     * so both that path and the nominal IronGate/Valheim one are written. Set one int key, creating
     * the file if the game has not written one yet.
     */
    static void setPlayerPrefInt(File instanceDir, String key, int value) throws IOException {
        writePlayerPrefInt(new File(instanceDir, "unity3d/unknown/unknown/prefs"), key, value);
        writePlayerPrefInt(new File(instanceDir, GameDescriptor.VALHEIM.userDataDirectory() + "/prefs"), key, value);
    }

    private static void writePlayerPrefInt(File prefs, String key, int value) throws IOException {
        String entry = "\t<pref name=\"" + key + "\" type=\"int\">" + value + "</pref>\n";
        String xml;
        if (prefs.isFile()) {
            xml = new String(java.nio.file.Files.readAllBytes(prefs.toPath()), StandardCharsets.UTF_8);
            String existing = "<pref name=\"" + key + "\" type=\"int\">" + value + "</pref>";
            if (xml.contains(existing)) return;
            String replaced = xml.replaceAll("[ \\t]*<pref name=\"" + key + "\" type=\"int\">[^<]*</pref>\\n?", "");
            int end = replaced.lastIndexOf("</unity_prefs>");
            if (end < 0) throw new IOException("unexpected prefs format in " + prefs);
            xml = replaced.substring(0, end) + entry + replaced.substring(end);
        } else {
            prefs.getParentFile().mkdirs();
            xml = "<unity_prefs version_major=\"1\" version_minor=\"1\">\n" + entry + "</unity_prefs>\n";
        }
        try (FileOutputStream out = new FileOutputStream(prefs)) {
            out.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "PlayerPrefs " + key + "=" + value + " (" + prefs + ")");
    }

    // --- helpers -----------------------------------------------------------------------------

    private static void writeIfMissing(File f, String content) throws IOException {
        if (f.isFile()) return;
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}

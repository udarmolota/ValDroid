package com.valdroid;

import com.valdroid.game.GameInstance;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Collects an instance's diagnostic logs into a single zip for sharing/support.
 *
 * <p>In our in-process setup box64 has no separate file — its output folds into Unity's
 * Player.log — so the most useful artifacts are Player.log + Player-prev.log (the
 * previous run, which often holds the crash). We also include box64.log / rimdroid.log
 * if present and the game's PlayerPrefs (its graphics settings — the first thing to check on a
 * "slow"/"looks wrong" report), plus
 * exit_info.txt — the system's record of WHY our previous processes died (ANR / native
 * crash / LMK / user swipe), with the stored ANR thread dump when one exists. Missing
 * files are skipped silently.
 */
public final class LogExporter {

    /** Ship only this much of the end of a sigsegv_fault log (a fault storm can grow it to tens of MB). */
    private static final long SIGSEGV_TAIL_BYTES = 256 * 1024;

    private LogExporter() {}

    private static void put(java.util.Map<String, File> m, String entryName, File f) {
        if (f != null && f.isFile()) m.put(entryName, f);
    }

    public static final class Result {
        public final List<String> items = new ArrayList<>();
        public long bytes;
        public String error;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    public static Result export(android.content.Context ctx, GameInstance gi, OutputStream rawOut) {
        Result r = new Result();
        if (gi == null) { r.error = "No game instance selected."; return r; }

        File gamePath = new File(gi.getGamePath());
        File userDir  = gi.getUserDataDir();

        // Global (not instance-scoped) uncaught-crash log — e.g. an in-app Steam download that
        // hard-crashed the app. Lives in the app's private files dir.
        File crashLog = new File(AppStorage.requireSingleton().getHomePath(),
                ValDroidApplication.CRASH_LOG);

        // Zip entry name -> file. Names are explicit because two of the files are both called
        // "prefs" (Unity writes one set under the game's company/product and, under box64, one under
        // "unknown/unknown" — which is the set the game actually reads here).
        java.util.LinkedHashMap<String, File> candidates = new java.util.LinkedHashMap<>();
        {
                put(candidates, "Player.log", new File(userDir, "Player.log"));
                put(candidates, "Player-prev.log", new File(userDir, "Player-prev.log"));
                put(candidates, "box64.log", new File(gamePath, "box64.log"));
                put(candidates, "rimdroid.log", new File(gamePath, "rimdroid.log"));
                // box64 appends one line per SIGSEGV here (raw write(), so it survives a hard crash)
                // with the guest RIP/RSP, the native pc and the tid — often the only crash locator we
                // get, since rimdroid.log can lose its tail and a non-root app cannot read the system
                // tombstone. Written to $HOME = the instance dir. GameLauncher rotates it per launch
                // (.prev = the run before), and the copy below ships only the tail: the GC/dynarec
                // hotpage dance can repeat one fault endlessly (91 MB in a field report), and for a
                // crash locator only the end of the file matters.
                put(candidates, "sigsegv_fault.log", new File(gamePath, "sigsegv_fault.log"));
                put(candidates, "sigsegv_fault.prev.log", new File(gamePath, "sigsegv_fault.prev.log"));
                // The game's own settings: graphics preset, resolution, VSync, FPS limit, tessellation…
                put(candidates, "prefs-game.xml", new File(userDir, "prefs"));
                put(candidates, "prefs-unknown.xml", new File(gamePath, "unity3d/unknown/unknown/prefs"));
                put(candidates, ValDroidApplication.CRASH_LOG, crashLog);
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[65536];
            for (java.util.Map.Entry<String, File> e : candidates.entrySet()) {
                File f = e.getValue();
                if (f == null || !f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(e.getKey()));
                try (FileInputStream in = new FileInputStream(f)) {
                    // Tail cap for the per-fault SIGSEGV logs (see the candidates note above).
                    if (e.getKey().startsWith("sigsegv_fault") && f.length() > SIGSEGV_TAIL_BYTES) {
                        long skip = f.length() - SIGSEGV_TAIL_BYTES;
                        while (skip > 0) {
                            long s = in.skip(skip);
                            if (s <= 0) break;
                            skip -= s;
                        }
                    }
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        zos.write(buf, 0, n);
                        r.bytes += n;
                    }
                }
                zos.closeEntry();
                r.items.add(e.getKey());
            }
            addInstanceSettings(zos, gi, r);
            addLogcat(zos, buf, r);
            if (ctx != null) addExitInfo(ctx, zos, buf, r);
        } catch (Exception e) {
            r.error = e.getMessage();
            return r;
        }
        if (r.items.isEmpty()) r.error = "No logs found yet (run the game first).";
        return r;
    }

    /**
     * Adds the system's ApplicationExitInfo history (API 30+ == our minSdk): timestamp, process,
     * decoded reason (ANR / CRASH_NATIVE / LOW_MEMORY / USER_REQUESTED / ...), signal, and memory
     * at death for the last dozen deaths of our package's processes. For entries where the system
     * stored a trace (ANR thread dumps, some native tombstones) the trace is appended, capped, so
     * a "game froze then closed" report carries the actual stack of the hang — the missing piece
     * in the map-generation-freeze reports, where Player.log ends mid-flight with no cause at all.
     * Capture failure is recorded inside the entry and never blocks the rest of the export.
     */
    private static void addExitInfo(android.content.Context ctx, ZipOutputStream zos, byte[] buf,
                                    Result r) throws java.io.IOException {
        final String name = "exit_info.txt";
        long written = 0;
        zos.putNextEntry(new ZipEntry(name));
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 12);
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            StringBuilder sb = new StringBuilder(1024);
            sb.append("Process exit history (newest first) for ").append(ctx.getPackageName())
              .append(" — Android ").append(android.os.Build.VERSION.RELEASE)
              .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n').append('\n');
            if (exits.isEmpty()) sb.append("(none recorded)\n");
            for (android.app.ApplicationExitInfo e : exits) {
                sb.append(fmt.format(new java.util.Date(e.getTimestamp())))
                  .append("  proc=").append(e.getProcessName())
                  .append("  reason=").append(exitReasonName(e.getReason()))
                  .append("  status=").append(e.getStatus()).append(signalName(e))
                  .append("  pss=").append(e.getPss()).append("kB rss=").append(e.getRss())
                  .append("kB\n");
                String d = e.getDescription();
                if (d != null && !d.isEmpty()) sb.append("    desc: ").append(d).append('\n');
            }
            byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
            zos.write(head);
            written += head.length;
            int traces = 0;
            for (android.app.ApplicationExitInfo e : exits) {
                if (traces >= 2) break;   // the two most recent stored traces are plenty
                try (InputStream in = e.getTraceInputStream()) {
                    if (in == null) continue;
                    byte[] hdr = ("\n===== stored trace: "
                            + fmt.format(new java.util.Date(e.getTimestamp())) + " "
                            + exitReasonName(e.getReason()) + " =====\n")
                            .getBytes(StandardCharsets.UTF_8);
                    zos.write(hdr);
                    written += hdr.length;
                    long cap = 262144;   // 256 kB per trace keeps the zip mailable
                    int n;
                    while ((n = in.read(buf)) > 0 && cap > 0) {
                        int w = (int)Math.min(n, cap);
                        zos.write(buf, 0, w);
                        written += w;
                        cap -= w;
                    }
                    traces++;
                } catch (Exception ignored) { /* per-entry trace is best-effort */ }
            }
        } catch (Exception e) {
            byte[] msg = ("exit-info capture failed: " + e + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            zos.write(msg);
            written += msg.length;
        } finally {
            zos.closeEntry();
        }
        r.bytes += written;
        r.items.add(name);
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case android.app.ApplicationExitInfo.REASON_ANR: return "ANR (hang)";
            case android.app.ApplicationExitInfo.REASON_CRASH: return "CRASH (java)";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case android.app.ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case android.app.ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (LMK)";
            case android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED (swipe/force-stop)";
            case android.app.ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case android.app.ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    /** Human name for the kill signal, appended after the raw status where it applies. */
    private static String signalName(android.app.ApplicationExitInfo e) {
        if (e.getReason() != android.app.ApplicationExitInfo.REASON_SIGNALED
                && e.getReason() != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) return "";
        switch (e.getStatus()) {
            case 3:  return " (SIGQUIT)";
            case 6:  return " (SIGABRT)";
            case 9:  return " (SIGKILL)";
            case 11: return " (SIGSEGV)";
            default: return "";
        }
    }

    /**
     * Adds recent logcat lines visible to this app UID. Android normally hides other apps' logs,
     * but Java, native and box64 output from ValDroid remains available. A capture failure is
     * recorded inside the entry instead of preventing the regular log files from being exported.
     */
    /**
     * What the LAUNCHER was told to do for this instance: renderer and driver, render scale, texture
     * tier, the toggles and the Extra env field. The game's own settings ship as prefs-*.xml; together
     * they answer most of "why is it slow / why does it look like that" without another round trip.
     */
    private static void addInstanceSettings(ZipOutputStream zos, GameInstance gi, Result r)
            throws java.io.IOException {
        final String name = "instance-settings.txt";
        com.valdroid.InstanceSettings s = gi.settings();
        StringBuilder sb = new StringBuilder();
        line(sb, "instance", gi.getName());
        line(sb, "app version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE
                + (BuildConfig.DEBUG ? ", debug)" : ")"));
        line(sb, "device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + ", Android " + android.os.Build.VERSION.RELEASE);
        line(sb, "renderer", String.valueOf(s.getRenderer()));
        line(sb, "vulkan driver", String.valueOf(s.getVulkanDriverSo()));
        line(sb, "render scale", s.getRenderScalePercent() + "%");
        line(sb, "fixed res mode", String.valueOf(s.getFixedResMode()));
        line(sb, "fps cap", String.valueOf(s.getFpsCap()));
        line(sb, "texture tier", s.getTexTier() + "   (0 none, 1 low, 2 ultra low)");
        line(sb, "native mono", String.valueOf(s.isNativeMono()));
        line(sb, "compat mode", String.valueOf(s.isCompatibilityMode()));
        line(sb, "interpreter", String.valueOf(s.isInterpreter()));
        line(sb, "drag pan", String.valueOf(s.isDragPan()));
        line(sb, "extra env", s.getEnvVars() == null ? "" : s.getEnvVars());
        byte[] out = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        zos.putNextEntry(new ZipEntry(name));
        zos.write(out);
        zos.closeEntry();
        r.bytes += out.length;
        r.items.add(name);
    }

    /** One "key : value" line, padded, newline-terminated. */
    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key);
        for (int i = key.length(); i < 15; i++) sb.append(' ');
        sb.append(": ").append(value).append((char) 10);
    }

    private static void addLogcat(ZipOutputStream zos, byte[] buf, Result r)
            throws java.io.IOException {
        final String name = "logcat.txt";
        Process process = null;
        long written = 0;
        zos.putNextEntry(new ZipEntry(name));
        try {
            process = new ProcessBuilder(
                    "logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "8000")
                    .redirectErrorStream(true)
                    .start();
            try (InputStream in = process.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    zos.write(buf, 0, n);
                    written += n;
                }
            }
            int exitCode = process.waitFor();
            if (written == 0) {
                byte[] message = ("logcat returned no accessible entries (exit "
                        + exitCode + ").\n").getBytes(StandardCharsets.UTF_8);
                zos.write(message);
                written += message.length;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            byte[] message = "logcat capture interrupted.\n".getBytes(StandardCharsets.UTF_8);
            zos.write(message);
            written += message.length;
        } catch (Exception e) {
            byte[] message = ("logcat capture failed: " + e + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            zos.write(message);
            written += message.length;
        } finally {
            if (process != null) process.destroy();
            zos.closeEntry();
        }
        r.bytes += written;
        r.items.add(name);
    }
}

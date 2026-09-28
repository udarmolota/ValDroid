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
 *
 * <p>The first entry is report.txt ({@link ReportInfo}: device, build id, install location,
 * RAM, instance state), followed by the launcher's own session logs ({@link LauncherLog}), so a
 * launcher that crashed before the game ever started still produces a useful zip — with or
 * without a game instance. Files are added by name only; directories are never swept.
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

    /** launcher.log copies: the head (the device header) plus this much of the end. */
    private static final long LAUNCHER_TAIL_BYTES = 2L * 1024 * 1024;
    private static final long LAUNCHER_HEAD_BYTES = 32 * 1024;

    /**
     * Build the report zip. {@code gi} may be null — no instance yet, or the player has none — and
     * the zip is still worth sending then: report.txt and launcher.log are exactly what a launcher
     * that crashed before the game started leaves behind. Instance-scoped files are skipped then.
     */
    public static Result export(android.content.Context ctx, @androidx.annotation.Nullable GameInstance gi,
                                OutputStream rawOut) {
        Result r = new Result();

        File home = new File(AppStorage.requireSingleton().getHomePath());
        // Global (not instance-scoped) uncaught-crash log — e.g. an in-app Steam download that
        // hard-crashed the app. Lives in the app's private files dir.
        File crashLog = new File(home, ValDroidApplication.CRASH_LOG);

        // Zip entry name -> file. Names are explicit because two of the files are both called
        // "prefs" (Unity writes one set under the game's company/product and, under box64, one under
        // "unknown/unknown" — which is the set the game actually reads here).
        java.util.LinkedHashMap<String, File> candidates = new java.util.LinkedHashMap<>();
        // Entry name -> {head bytes, tail bytes} for files shipped only in part.
        java.util.HashMap<String, long[]> caps = new java.util.HashMap<>();
        // The launcher's session logs (LauncherLog): header + logcat from app start + launch
        // milestones. The only record of a launcher that died before the game started. The .prev
        // generations matter because players relaunch the app after a crash before reporting it.
        for (String n : new String[] { LauncherLog.FILE, LauncherLog.PREV, LauncherLog.PREV2 }) {
            put(candidates, n, new File(home, n));
            caps.put(n, new long[] { LAUNCHER_HEAD_BYTES, LAUNCHER_TAIL_BYTES });
        }
        if (gi != null) {
                File gamePath = new File(gi.getGamePath());
                File userDir  = gi.getUserDataDir();
                put(candidates, "Player.log", new File(userDir, "Player.log"));
                put(candidates, "Player-prev.log", new File(userDir, "Player-prev.log"));
                put(candidates, "box64.log", new File(gamePath, "box64.log"));
                put(candidates, "rimdroid.log", new File(gamePath, "rimdroid.log"));
                // The previous run's copies, rotated by GameLauncher.launch(): a player who
                // relaunches to reproduce a crash no longer overwrites the crashed run's logs.
                put(candidates, "box64.prev.log", new File(gamePath, "box64.prev.log"));
                put(candidates, "rimdroid.prev.log", new File(gamePath, "rimdroid.prev.log"));
                // Standalone-exec path's stderr (unused today, kept so a switch back is covered).
                put(candidates, "rimdroid_game.log", new File(gamePath, "rimdroid_game.log"));
                // box64 emulate() milestones (core.c, raw write(), survives SIGKILL): shows how far
                // the emulated start got. Reset per launch; capped in case a build appends in a loop.
                put(candidates, "emulate_trace.log", new File(gamePath, "emulate_trace.log"));
                caps.put("emulate_trace.log", new long[] { 0, SIGSEGV_TAIL_BYTES });
                // box64 appends one line per SIGSEGV here (raw write(), so it survives a hard crash)
                // with the guest RIP/RSP, the native pc and the tid — often the only crash locator we
                // get, since rimdroid.log can lose its tail and a non-root app cannot read the system
                // tombstone. Written to $HOME = the instance dir. GameLauncher rotates it per launch
                // (.prev = the run before), and the copy below ships only the tail: the GC/dynarec
                // hotpage dance can repeat one fault endlessly (91 MB in a field report), and for a
                // crash locator only the end of the file matters.
                put(candidates, "sigsegv_fault.log", new File(gamePath, "sigsegv_fault.log"));
                put(candidates, "sigsegv_fault.prev.log", new File(gamePath, "sigsegv_fault.prev.log"));
                caps.put("sigsegv_fault.log", new long[] { 0, SIGSEGV_TAIL_BYTES });
                caps.put("sigsegv_fault.prev.log", new long[] { 0, SIGSEGV_TAIL_BYTES });
                // The game's own settings: graphics preset, resolution, VSync, FPS limit, tessellation…
                put(candidates, "prefs-game.xml", new File(userDir, "prefs"));
                put(candidates, "prefs-unknown.xml", new File(gamePath, "unity3d/unknown/unknown/prefs"));
                // RIMDROID_STUTTER_DIAG=1: long frames, Mono collections and slow shader compiles,
                // all with wall-clock times, to see what each stutter was.
                put(candidates, "stutter_diag.log", new File(gamePath, "stutter_diag.log"));
        }
        {
                // Appended forever (every uncaught exception of every session): the newest are at the end.
                put(candidates, ValDroidApplication.CRASH_LOG, crashLog);
                caps.put(ValDroidApplication.CRASH_LOG, new long[] { 0, SIGSEGV_TAIL_BYTES });
                // MobileGlues' own log and the config we wrote for it (MG_DIR_PATH = the app cache
                // dir, see GameLauncher). The log is where a translator failure actually shows:
                // "Failed to get OpenGL function <name>" for every entry point the game asks for
                // and MG does not have, and the glslang output for every shader that does not
                // translate. On a GPU we have never run on, that is the first file to read.
                String mgDir = AppStorage.requireSingleton().getCachePath();
                put(candidates, "mobileglues.log", new File(mgDir, "latest.log"));
                put(candidates, "mobileglues-config.json", new File(mgDir, "config.json"));
                // The game's own GLSL for every shader, as our GL layer hands it to MobileGlues
                // (rd_glShaderSource writes it to RIMDROID_CACHE_DIR = the same cache dir). Needed
                // when one material renders wrong on one GPU (black terrain on Mali): the source
                // tells which features that shader uses. Plain text, compresses well in the zip.
                put(candidates, "rd_shaders.txt", new File(mgDir, "rd_shaders.txt"));
                // MobileGlues reports a shader that fails to translate every time it is used.
                caps.put("mobileglues.log", new long[] { 0, SIGSEGV_TAIL_BYTES });
        }

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[65536];
            // report.txt FIRST: device, build id, install location (adoptable storage!), X socket
            // length, RAM, free space, instance state and log ages — the facts every report used to
            // need a round trip for. Built here, on the export worker thread (it probes the GPU).
            if (ctx != null) addReport(ctx, zos, gi, r);
            for (java.util.Map.Entry<String, File> e : candidates.entrySet()) {
                File f = e.getValue();
                if (f == null || !f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(e.getKey()));
                try (FileInputStream in = new FileInputStream(f)) {
                    long[] cap = caps.get(e.getKey());
                    long len = f.length();
                    if (cap != null && len > cap[0] + cap[1]) {
                        // Head (e.g. launcher.log's device header) + marker + tail.
                        r.bytes += copy(in, zos, buf, cap[0]);
                        long skip = len - cap[1] - cap[0];
                        byte[] mark = ("\n... [" + skip + " bytes skipped by the report] ...\n")
                                .getBytes(StandardCharsets.UTF_8);
                        if (cap[0] > 0) { zos.write(mark); r.bytes += mark.length; }
                        while (skip > 0) {
                            long s = in.skip(skip);
                            if (s <= 0) break;
                            skip -= s;
                        }
                    }
                    r.bytes += copy(in, zos, buf, Long.MAX_VALUE);
                }
                zos.closeEntry();
                r.items.add(e.getKey());
            }
            if (gi != null) addInstanceSettings(zos, gi, r);
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
        line(sb, "app version", ReportInfo.buildId());
        line(sb, "device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + ", Android " + android.os.Build.VERSION.RELEASE);
        line(sb, "renderer", String.valueOf(s.getRenderer()));
        line(sb, "vulkan driver", String.valueOf(s.getVulkanDriverSo()));
        line(sb, "render scale", s.describeRenderScale());
        line(sb, "fixed res mode", String.valueOf(s.getFixedResMode()));
        line(sb, "fps mode", s.getFpsMode() + "   (0 off, 1 economy ~30, 2 balanced ~40, 3 smooth ~60)");
        line(sb, "texture tier", s.getTexTier() + "   (0 none, 1 low, 2 ultra low)");
        // The setting AND what box64 really loaded in the last launch: a failed native load falls back
        // to the emulated x86 Mono silently, and only this line would show it.
        String monoStatus = com.valdroid.game.NativeMono.readStatus(gi);
        line(sb, "native mono", s.isNativeMono()
                + (monoStatus != null ? " (last launch: " + monoStatus + ")" : ""));
        // Whether the game ran modded, and with what — the first thing to know about a bug report.
        StringBuilder mods = new StringBuilder(String.valueOf(s.isModSupport()));
        for (ModManager.Mod m : ModManager.list(new java.io.File(gi.getGamePath()))) {
            mods.append(mods.indexOf(":") < 0 ? ": " : ", ").append(m.name);
            if (m.version != null) mods.append(' ').append(m.version);
            if (!m.enabled) mods.append(" (off)");
        }
        line(sb, "mod support", mods.toString());
        line(sb, "compat mode", String.valueOf(s.isCompatibilityMode()));
        line(sb, "drag pan", String.valueOf(s.isDragPan()));
        // "the game kept running with the screen off" vs "it froze when I came back" starts here
        line(sb, "keep running in bg", String.valueOf(s.isKeepRunningInBackground()));
        line(sb, "shader cache", String.valueOf(s.isShaderCache()));
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

    /** Copy at most {@code max} bytes of {@code in} into the zip; returns the bytes written. */
    private static long copy(InputStream in, ZipOutputStream zos, byte[] buf, long max)
            throws java.io.IOException {
        long done = 0;
        while (done < max) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, max - done));
            if (n <= 0) break;
            zos.write(buf, 0, n);
            done += n;
        }
        return done;
    }

    /** report.txt: the ReportInfo header. A failing probe is written into the entry, never thrown. */
    private static void addReport(android.content.Context ctx, ZipOutputStream zos, GameInstance gi,
                                  Result r) throws java.io.IOException {
        final String name = "report.txt";
        String text;
        try {
            text = ReportInfo.header(ctx, gi);
        } catch (Throwable t) {
            text = "report header failed: " + android.util.Log.getStackTraceString(t);
        }
        byte[] out = text.getBytes(StandardCharsets.UTF_8);
        zos.putNextEntry(new ZipEntry(name));
        zos.write(out);
        zos.closeEntry();
        r.bytes += out.length;
        r.items.add(name);
    }

    /** A logcat snapshot this few lines long means the command was refused or the ROM hides the buffers. */
    private static final int LOGCAT_MIN_LINES = 100;

    /**
     * Export-time logcat snapshot. "-b all" is refused outright on some strict ROMs (it asks for
     * buffers that need READ_LOGS) or returns next to nothing; then the default buffer set is tried,
     * which logcat filters to what this UID may read. The command that produced the entry is its
     * first line, so an empty-looking snapshot says why. The live session record is launcher.log.
     */
    private static void addLogcat(ZipOutputStream zos, byte[] buf, Result r)
            throws java.io.IOException {
        final String name = "logcat.txt";
        String[][] commands = {
                { "logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "8000" },
                { "logcat", "-d", "-v", "threadtime", "-t", "8000" },
        };
        StringBuilder notes = new StringBuilder();
        byte[] best = null;
        String bestCmd = null;
        int bestLines = -1;
        for (String[] cmd : commands) {
            String cmdStr = String.join(" ", cmd);
            Process process = null;
            try {
                process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 20);
                try (InputStream in = process.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                int exitCode = process.waitFor();
                byte[] data = bo.toByteArray();
                int lines = 0;
                for (byte b : data) if (b == '\n') lines++;
                notes.append("# ").append(cmdStr).append(" -> exit ").append(exitCode)
                     .append(", ").append(lines).append(" lines\n");
                if (lines > bestLines) { best = data; bestCmd = cmdStr; bestLines = lines; }
                if (exitCode == 0 && lines >= LOGCAT_MIN_LINES) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                notes.append("# ").append(cmdStr).append(" -> interrupted\n");
                break;
            } catch (Exception e) {
                notes.append("# ").append(cmdStr).append(" -> failed: ").append(e).append('\n');
            } finally {
                if (process != null) process.destroy();
            }
        }
        long written = 0;
        zos.putNextEntry(new ZipEntry(name));
        try {
            byte[] head = ("# command: " + (bestCmd != null ? bestCmd : "(none succeeded)") + "\n"
                    + notes).getBytes(StandardCharsets.UTF_8);
            zos.write(head);
            written += head.length;
            if (best != null && best.length > 0) {
                zos.write(best);
                written += best.length;
            } else {
                byte[] msg = "logcat returned no accessible entries.\n".getBytes(StandardCharsets.UTF_8);
                zos.write(msg);
                written += msg.length;
            }
        } finally {
            zos.closeEntry();
        }
        r.bytes += written;
        r.items.add(name);
    }
}

package com.valdroid;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * launcher.log: the launcher's own session log, written from app start — not from game start.
 *
 * <p>Why it exists: rimdroid.log, box64.log and Player.log are all opened by the game side, so a
 * launcher that dies BEFORE the game starts (the adoptable-storage X socket crash, 2026-09-27)
 * leaves nothing behind but crash_uncaught.log and an export-time logcat snapshot, which on many
 * ROMs no longer holds the lines from the crashed process. This file is opened in
 * Application.onCreate and receives, flushed line by line so it survives a hard kill:
 * <ul>
 *   <li>a header (time, pid, then the {@link ReportInfo} device/build/storage block);</li>
 *   <li>a live stream of this app's logcat ({@code logcat -v threadtime *:I}), which carries the
 *       Java, native and box64 lines of the whole session including a fatal exception;</li>
 *   <li>explicit launcher milestones via {@link #line}, written synchronously, so they land even
 *       when the ROM refuses the logcat stream.</li>
 * </ul>
 * Two previous sessions are kept (launcher.prev.log, launcher.prev2.log): a player usually
 * relaunches the app after a crash before reporting it, which rotates the crashed session away.
 *
 * <p>Main process only: the ":fmoddec" audio-decoder process also runs Application.onCreate and
 * would otherwise rotate the main process's live log out from under it.
 */
public final class LauncherLog {

    private static final String TAG = "ValDroid/LauncherLog";

    public static final String FILE = "launcher.log";
    public static final String PREV = "launcher.prev.log";
    public static final String PREV2 = "launcher.prev2.log";

    /** Size cap for one session's file. Past it the file restarts with the header re-emitted. */
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static File file;
    private static OutputStream out;
    private static long size;
    /** Everything written before the first logcat line; re-emitted when the size cap restarts the file. */
    private static String headerText = "";
    private static volatile String status = "not started";

    private LauncherLog() {}

    /** One line for report.txt: is the stream alive, and with which command. */
    public static String status() { return status; }

    public static void init(Context ctx) {
        final Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (file != null) return;
            File dir = app.getFilesDir();
            File f = new File(dir, FILE);
            rotate(dir);
            file = f;
            String quick = "=== ValDroid launcher log ===\n"
                    + "started       : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                            .format(new Date()) + "\n"
                    + "pid           : " + android.os.Process.myPid() + "\n"
                    + "valdroid      : " + ReportInfo.buildId() + "\n";
            headerText = quick;
            openFresh(quick);
            status = "header written, logcat stream starting";
        }
        // The full header probes the GPU (a throwaway EGL context) and reads /proc — keep all of it
        // off the main thread, and let the logcat stream follow on the same thread.
        Thread t = new Thread(() -> {
            String full;
            try {
                full = ReportInfo.header(app, null);
            } catch (Throwable e) {
                full = "(report header failed: " + e + ")\n";
            }
            synchronized (LOCK) {
                headerText = headerText + full + "--- logcat (this app, threadtime, *:I) ---\n";
                writeRawLocked(full + "--- logcat (this app, threadtime, *:I) ---\n");
            }
            streamLogcat();
        }, "vd-launcher-log");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Append one launcher milestone, timestamped, synchronously. Safe from any thread; a no-op when
     * the log is not open (secondary process, or before init).
     */
    public static void line(String msg) {
        if (msg == null) return;
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (LOCK) {
            if (out == null) return;
            writeRawLocked(ts + " [launcher] " + msg + "\n");
        }
    }

    /** launcher.log → .prev → .prev2, oldest dropped. */
    private static void rotate(File dir) {
        File cur = new File(dir, FILE), prev = new File(dir, PREV), prev2 = new File(dir, PREV2);
        if (prev.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            prev2.delete();
            if (!prev.renameTo(prev2)) //noinspection ResultOfMethodCallIgnored
                prev.delete();
        }
        if (cur.isFile()) {
            if (!cur.renameTo(prev)) //noinspection ResultOfMethodCallIgnored
                cur.delete();
        }
    }

    /** (Re)create the file, truncating it, and write {@code first}. Caller holds LOCK. */
    private static void openFresh(String first) {
        try {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
            out = new FileOutputStream(file, false);
            size = 0;
            writeRawLocked(first);
        } catch (Throwable e) {
            out = null;
            status = "cannot open " + file + ": " + e;
            Log.w(TAG, "launcher.log open failed", e);
        }
    }

    /** Write and flush; enforces the size cap. Caller holds LOCK. */
    private static void writeRawLocked(String s) {
        if (out == null) return;
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (size + b.length > MAX_BYTES) {
            // One runaway session (a log storm) must not fill the disk or make the report unmailable.
            // Restarting keeps the device block at the top and the most recent lines, which is what
            // a report after a crash needs.
            openFresh("(launcher.log reached " + (MAX_BYTES >> 20) + " MB at "
                    + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "; earlier lines of this session were dropped)\n" + headerText);
            if (out == null) return;
        }
        try {
            out.write(b);
            out.flush();
            size += b.length;
        } catch (Throwable e) {
            // Disk full or the file vanished: stop writing rather than throwing into callers.
            try { out.close(); } catch (Throwable ignored) {}
            out = null;
            status = "write failed: " + e;
        }
    }

    /**
     * Stream this app's logcat into the file until the process dies. Buffers are not named in the
     * first form: "-b crash/system" needs READ_LOGS on strict ROMs, which then dump what they have
     * and exit. If a form ends early it is retried as "-b main". No "logcat -c": the buffer still
     * holds the previous process's last lines, and after a crash those are the evidence.
     */
    private static void streamLogcat() {
        String[][] commands = {
                { "logcat", "-v", "threadtime", "*:I" },
                { "logcat", "-b", "main", "-v", "threadtime", "*:I" },
        };
        for (int attempt = 0; attempt < commands.length; attempt++) {
            String cmd = String.join(" ", commands[attempt]);
            long lines = 0;
            long started = System.currentTimeMillis();
            Process p = null;
            try {
                p = new ProcessBuilder(commands[attempt]).redirectErrorStream(true).start();
                status = "streaming: " + cmd;
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        synchronized (LOCK) {
                            writeRawLocked(l + "\n");
                        }
                        lines++;
                    }
                }
            } catch (Throwable e) {
                line("logcat '" + cmd + "' failed: " + e);
            } finally {
                if (p != null) p.destroy();
            }
            long ranMs = System.currentTimeMillis() - started;
            // logcat is meant to stream until we die, so any end is a failure. A long, busy stream
            // that ended was killed by the ROM and a retry gains nothing; a short one was refused.
            line("logcat '" + cmd + "' ended after " + lines + " lines, " + (ranMs / 1000) + " s");
            if (lines > 200 && ranMs > 5000) {
                status = "logcat stream ended after " + lines + " lines (" + cmd + ")";
                return;
            }
        }
        status = "logcat stream unavailable — only [launcher] lines are recorded";
    }
}

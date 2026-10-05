package com.valdroid.game;

import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The native engine's own log: the ":unity" process streams this app's logcat into
 * {@code <instance>/native_engine.log} from its own start, so a report still has the game's log
 * (il2mono, Unity, the bridge, managed exceptions, a native crash's backtrace) when the logcat
 * buffer has moved on or the launcher process was dropped meanwhile. The previous run is kept as
 * native_engine.prev.log, the same way as the launcher's log (LauncherLog). Not attached to the
 * launcher: it runs in the game's process only.
 */
public final class NativeLog {
    public static final String FILE = "native_engine.log";
    public static final String PREV = "native_engine.prev.log";
    /** Past this the file starts over (marked), so a spamming run cannot fill the storage. */
    private static final long MAX_BYTES = 16L * 1024 * 1024;

    private static boolean started;

    private NativeLog() {}

    /** Starts streaming into dir (once per process). Never throws: no log is no reason to stop the game. */
    public static synchronized void start(File dir) {
        if (started || dir == null || !dir.isDirectory()) return;
        started = true;
        Thread t = new Thread(() -> stream(dir), "NativeLog");
        t.setDaemon(true);
        t.start();
    }

    private static void stream(File dir) {
        File log = new File(dir, FILE);
        File prev = new File(dir, PREV);
        if (log.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            prev.delete();
            //noinspection ResultOfMethodCallIgnored
            log.renameTo(prev);
        }
        // From this process's start, not the whole buffer: earlier lines belong to other runs.
        long startWall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(startWall));
        String header = "--- native engine, pid " + Process.myPid() + ", logcat -v threadtime -T '" + since
                + "' *:I ---\n";
        java.lang.Process logcat = null;
        try {
            logcat = new ProcessBuilder("logcat", "-v", "threadtime", "-T", since, "*:I")
                    .redirectErrorStream(true).start();
            OutputStream out = new FileOutputStream(log);
            out.write(header.getBytes(StandardCharsets.UTF_8));
            long written = header.length();
            byte[] buf = new byte[16384];
            try (InputStream in = logcat.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (written + n > MAX_BYTES) {
                        out.close();
                        out = new FileOutputStream(log);
                        byte[] mark = (header + "... [restarted: the log reached " + (MAX_BYTES >> 20)
                                + " MB] ...\n").getBytes(StandardCharsets.UTF_8);
                        out.write(mark);
                        written = mark.length;
                    }
                    out.write(buf, 0, n);
                    written += n;
                    out.flush();
                }
            } finally {
                out.close();
            }
        } catch (IOException e) {
            android.util.Log.w("ValDroid/NativeLog", "no native engine log: " + e);
        } finally {
            if (logcat != null) logcat.destroy();
        }
    }
}

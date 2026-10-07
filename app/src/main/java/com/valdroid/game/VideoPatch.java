package com.valdroid.game;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Puts back the game's video clips that a test build (2026-10-07) made unreadable.
 *
 * <p>That build turned the cinematics off on Mali by overwriting the first 4 bytes of each WebM clip in
 * the game's bundles (the EBML magic 1A 45 DF A3) with "VDNV", and listed the places in
 * {@value #STATE} next to the game. It did not help (entering the cinematic, not the video, lost the
 * GPU; the bridge's NoCinematics now keeps the game from starting one) and the search took tens of
 * seconds, so it is gone; this restores the bytes on the next launch, native or box64, and drops the
 * list. Nothing happens when the list is not there.
 */
public final class VideoPatch {
    private static final String TAG = "ValDroid/VideoPatch";
    private static final String STATE = "video_patch.txt";
    private static final byte[] EBML = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    private static final byte[] MARK = {'V', 'D', 'N', 'V'};

    private VideoPatch() {}

    public static void restore(File gameDir) {
        File state = new File(gameDir, STATE);
        if (!state.isFile()) return;
        int restored = 0;
        boolean complete = true;
        try {
            // One line per data file: relative path, size, offsets (comma-separated).
            for (String line : Files.readAllLines(state.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length < 3 || p[2].isEmpty()) continue;
                File f = new File(gameDir, p[0]);
                if (!f.isFile() || f.length() != Long.parseLong(p[1])) continue;
                try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                    byte[] cur = new byte[4];
                    for (String o : p[2].split(",")) {
                        long off = Long.parseLong(o);
                        raf.seek(off);
                        raf.readFully(cur);
                        if (!java.util.Arrays.equals(cur, MARK)) continue;
                        raf.seek(off);
                        raf.write(EBML);
                        restored++;
                    }
                } catch (IOException e) {
                    complete = false;
                    Log.w(TAG, "restore " + f + ": " + e);
                }
            }
        } catch (IOException | RuntimeException e) {
            complete = false;
            Log.w(TAG, "state " + state + ": " + e);
        }
        if (complete && !state.delete()) Log.w(TAG, "cannot delete " + state);
        com.valdroid.LauncherLog.line("video clips restored: " + restored);
    }
}

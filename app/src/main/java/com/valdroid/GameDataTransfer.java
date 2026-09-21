package com.valdroid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup / restore of a Valheim instance's saves and settings, as a zip.
 *
 * SAVES: {@code worlds_local/} (each world: .fwl + .db, or the newer folder layout) and
 * {@code characters_local/} (.fch), from the game's user-data dir
 * ({@code <instance>/unity3d/IronGate/Valheim/}). In the zip they sit under those same folder names —
 * the names the PC version uses too (in {@code %USERPROFILE%/AppData/LocalLow/IronGate/Valheim/}), so a
 * player can zip the two folders on a PC and import them here. The PC's Steam Cloud folders
 * {@code worlds/} and {@code characters/} are accepted on import as well and land in the *_local ones.
 *
 * SETTINGS: the game's PlayerPrefs file — graphics, controls, audio. Under box64 Unity reads it from
 * {@code <instance>/unity3d/unknown/unknown/prefs}; the copy under the user-data dir is exported too.
 * Phone to phone only: the PC version keeps its settings in the Windows registry.
 *
 * Saves and settings stay SEPARABLE — callers pass {@link #SAVES} and/or {@link #CONFIG} — so moving
 * saves to another device does not drag this device's graphics settings along.
 *
 * (This class was written for RimWorld's {@code Saves/} and {@code Config/} folders; Valheim has
 * neither, so until 2026-09-21 every export here failed with "Nothing to export".)
 */
public final class GameDataTransfer {

    public static final String SAVES  = "Saves";     // part names, as passed by the menu
    public static final String CONFIG = "Config";
    private static final String[] ALL_PARTS = { SAVES, CONFIG };

    private static final String USER_DIR = "unity3d/IronGate/Valheim";
    private static final String UNITY_PREFS = "unity3d/unknown/unknown/prefs";

    /** Zip folder names of the saves, and where they live on disk (relative to the instance dir). */
    private static final String[][] SAVE_DIRS = {
            { "worlds_local",     USER_DIR + "/worlds_local" },
            { "characters_local", USER_DIR + "/characters_local" },
    };
    /** Accepted on import only: the PC's Steam Cloud folder names, mapped onto the local ones. */
    private static final String[][] SAVE_ALIASES = {
            { "worlds",     USER_DIR + "/worlds_local" },
            { "characters", USER_DIR + "/characters_local" },
    };
    /** Zip file names of the settings, and where they live on disk. */
    private static final String[][] SETTINGS_FILES = {
            { "settings/prefs",          UNITY_PREFS },
            { "settings/prefs-irongate", USER_DIR + "/prefs" },
    };

    public static final class Result {
        public final List<String> items = new ArrayList<>(); // which parts were handled
        public String error;
        public long bytes;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    private GameDataTransfer() {}

    /** Zip the requested parts of the instance at {@code instanceDir} into {@code rawOut}. */
    public static Result export(File instanceDir, OutputStream rawOut, String... parts) {
        Set<String> use = parts(parts);
        Result r = new Result();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[1 << 16];
            if (use.contains(SAVES)) {
                boolean any = false;
                for (String[] d : SAVE_DIRS) {
                    File dir = new File(instanceDir, d[1]);
                    if (!dir.isDirectory()) continue;
                    zipDir(dir, d[0], zos, buf, r);
                    any = true;
                }
                if (any) r.items.add("saves");
            }
            if (use.contains(CONFIG)) {
                boolean any = false;
                for (String[] f : SETTINGS_FILES) {
                    File file = new File(instanceDir, f[1]);
                    if (!file.isFile()) continue;
                    zipFile(file, f[0], zos, buf, r);
                    any = true;
                }
                if (any) r.items.add("settings");
            }
            if (r.items.isEmpty())
                r.error = "Nothing to export yet — play the game once so it writes its saves and settings.";
        } catch (IOException e) {
            r.error = msg(e);
        }
        return r;
    }

    /** Restore the requested parts from a backup zip into the instance at {@code instanceDir}. */
    public static Result importZip(File zipFile, File instanceDir, String... allowedParts) {
        Set<String> use = parts(allowedParts);
        Result r = new Result();
        final String rootCanon;
        try { rootCanon = instanceDir.getCanonicalPath(); } catch (IOException e) { r.error = msg(e); return r; }

        Set<String> done = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                String part = null;
                File out = null;
                if (use.contains(SAVES)) {
                    out = mapFolder(instanceDir, name, SAVE_DIRS);
                    if (out == null) out = mapFolder(instanceDir, name, SAVE_ALIASES);
                    if (out != null) part = "saves";
                }
                if (out == null && use.contains(CONFIG)) {
                    for (String[] f : SETTINGS_FILES)
                        if (name.equals(f[0])) { out = new File(instanceDir, f[1]); part = "settings"; break; }
                }
                if (out == null) { zis.closeEntry(); continue; }   // not ours: ignore

                String oc = out.getCanonicalPath();
                if (!oc.startsWith(rootCanon + File.separator)) { zis.closeEntry(); continue; }   // zip-slip guard
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = zis.read(buf)) > 0) { os.write(buf, 0, n); r.bytes += n; }
                    }
                }
                done.add(part);
                zis.closeEntry();
            }
            r.items.addAll(done);
            if (done.isEmpty())
                r.error = use.contains(SAVES)
                        ? "No worlds_local / characters_local folders found in this zip."
                        : "No ValDroid settings found in this zip.";
        } catch (IOException ex) {
            r.error = msg(ex);
        }
        return r;
    }

    /**
     * Where a zip entry lands if it is inside one of {@code map}'s folders. The folder may sit at the
     * top of the zip or deeper (a zip of "Valheim/worlds_local/..." made on a PC works too); the
     * LAST path segment matching a folder name decides.
     */
    private static File mapFolder(File instanceDir, String entry, String[][] map) {
        String[] seg = entry.split("/");
        for (int i = seg.length - 1; i >= 0; i--) {
            for (String[] m : map) {
                if (!seg[i].equals(m[0])) continue;
                StringBuilder rest = new StringBuilder();
                for (int j = i + 1; j < seg.length; j++) { if (rest.length() > 0) rest.append('/'); rest.append(seg[j]); }
                File base = new File(instanceDir, m[1]);
                return rest.length() == 0 ? base : new File(base, rest.toString());
            }
        }
        return null;
    }

    private static Set<String> parts(String[] parts) {
        return new LinkedHashSet<>(java.util.Arrays.asList(
                (parts == null || parts.length == 0) ? ALL_PARTS : parts));
    }

    private static void zipFile(File f, String name, ZipOutputStream zos, byte[] buf, Result r) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            int n;
            while ((n = in.read(buf)) > 0) { zos.write(buf, 0, n); r.bytes += n; }
        }
        zos.closeEntry();
    }

    private static void zipDir(File dir, String prefix, ZipOutputStream zos, byte[] buf, Result r)
            throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        if (kids.length == 0) {                       // preserve empty dirs
            zos.putNextEntry(new ZipEntry(prefix + "/"));
            zos.closeEntry();
            return;
        }
        for (File f : kids) {
            String name = prefix + "/" + f.getName();
            if (f.isDirectory()) zipDir(f, name, zos, buf, r);
            else zipFile(f, name, zos, buf, r);
        }
    }

    private static String msg(Exception e) { return e.getMessage() != null ? e.getMessage() : "I/O error"; }
}

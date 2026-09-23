package com.valdroid;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * BepInEx mods for one game instance.
 *
 * <p>BepInEx itself ships inside the APK (assets/bepinex) and is copied into the instance's
 * {@code BepInEx/core} when missing or older ({@link #ensureBepInEx}). Its root folder is where it
 * looks for everything else, so plugins, configs and caches stay per instance.
 *
 * <p>A mod is one entry of {@code BepInEx/plugins}: a folder, or a loose .dll. Turning a mod off moves
 * that entry to {@code BepInEx/plugins_off}, where BepInEx does not look. The list is always read from
 * the file system, so mods a player drops in by hand show up too.
 *
 * <p>{@link #install} lays a Thunderstore-style zip out the way r2modman does for Valheim. Game files a
 * mod overwrites (e.g. {@code valheim_Data/Managed/...}) are backed up first and restored when the mod
 * is deleted.
 */
public final class ModManager {
    private static final String TAG = "ValDroid/Mods";

    /** The bundled BepInExPack Valheim release; bump together with the asset file name. */
    public static final String BEPINEX_VERSION = "5.4.2350";
    private static final String BEPINEX_ASSET = "bepinex/BepInEx-" + BEPINEX_VERSION + ".zip";
    private static final String VERSION_MARKER = ".valdroid_bepinex_version";

    private static final String PLUGINS = "plugins";
    private static final String PLUGINS_OFF = "plugins_off";
    private static final String BACKUP_DIR = ".valdroid_backup";
    /** Inside a mod folder: game-relative paths the mod replaced, one per line. */
    private static final String GAME_FILES_LIST = ".valdroid_game_files";

    private ModManager() {}

    /** One installed mod as the Mods screen shows it. */
    public static final class Mod {
        public final String name;
        public final String version;   // null when unknown
        public final boolean enabled;
        final File location;           // folder or .dll, under plugins or plugins_off

        Mod(String name, String version, boolean enabled, File location) {
            this.name = name; this.version = version; this.enabled = enabled; this.location = location;
        }
    }

    public static File bepinexDir(File gameDir) { return new File(gameDir, "BepInEx"); }

    public static File preloader(File gameDir) {
        return new File(bepinexDir(gameDir), "core/BepInEx.Preloader.dll");
    }

    // ---- BepInEx itself ------------------------------------------------------------------------

    /**
     * Makes sure the instance has the bundled BepInEx: copies {@code core/} from the APK when the
     * version marker is missing or different, and the default {@code config/BepInEx.cfg} only when
     * there is none (a player's own settings stay). Cheap when up to date: one small file read.
     */
    public static void ensureBepInEx(Context ctx, File gameDir) throws IOException {
        File root = bepinexDir(gameDir);
        File core = new File(root, "core");
        File marker = new File(core, VERSION_MARKER);
        mkdirs(new File(root, PLUGINS));
        mkdirs(new File(root, PLUGINS_OFF));
        if (BEPINEX_VERSION.equals(readSmall(marker)) && preloader(gameDir).isFile()) return;

        Log.i(TAG, "Installing BepInEx " + BEPINEX_VERSION + " into " + root);
        deleteRecursive(core);
        mkdirs(core);
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(ctx.getAssets().open(BEPINEX_ASSET)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (!isSafeRelative(name)) continue;
                File out = new File(root, name);
                if (name.startsWith("config/") && out.exists()) continue;
                mkdirs(out.getParentFile());
                copy(zis, out);
            }
        }
        writeSmall(marker, BEPINEX_VERSION);
    }

    // ---- listing and switching ---------------------------------------------------------------

    /** Every mod in plugins (enabled) and plugins_off (disabled), sorted by name. */
    public static List<Mod> list(File gameDir) {
        List<Mod> mods = new ArrayList<>();
        File root = bepinexDir(gameDir);
        addMods(mods, new File(root, PLUGINS), true);
        addMods(mods, new File(root, PLUGINS_OFF), false);
        Collections.sort(mods, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return mods;
    }

    public static int countEnabled(File gameDir) {
        int n = 0;
        for (Mod m : list(gameDir)) if (m.enabled) n++;
        return n;
    }

    private static void addMods(List<Mod> into, File dir, boolean enabled) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) {
                String[] nv = readManifest(new File(f, "manifest.json"));
                into.add(new Mod(nv[0] != null ? nv[0] : f.getName(), nv[1], enabled, f));
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".dll")) {
                String n = f.getName();
                into.add(new Mod(n.substring(0, n.length() - 4), null, enabled, f));
            }
        }
    }

    /** Moves the mod between plugins and plugins_off. */
    public static boolean setEnabled(File gameDir, Mod mod, boolean enabled) {
        if (mod.enabled == enabled) return true;
        File target = new File(new File(bepinexDir(gameDir), enabled ? PLUGINS : PLUGINS_OFF), mod.location.getName());
        mkdirs(target.getParentFile());
        if (target.exists()) deleteRecursive(target);
        boolean ok = mod.location.renameTo(target);
        if (!ok) Log.w(TAG, "Could not move " + mod.location + " to " + target);
        return ok;
    }

    /** Deletes the mod and puts back the game files it replaced. */
    public static void delete(File gameDir, Mod mod) {
        restoreGameFiles(gameDir, mod.location);
        deleteRecursive(mod.location);
    }

    // ---- installing a zip ----------------------------------------------------------------------

    /**
     * Installs a mod zip into the instance, enabled, and returns the mod's display name. Layout rules
     * (after dropping a single wrapper folder the whole zip may sit in):
     * <ul>
     *   <li>{@code manifest.json} → kept in the mod folder (name and version for the list);
     *       {@code icon.png}, README/CHANGELOG at the root → skipped;</li>
     *   <li>{@code BepInEx/plugins/…}, {@code plugins/…}, loose files at the root → {@code BepInEx/plugins/<mod>/…};</li>
     *   <li>{@code BepInEx/patchers/…}, {@code patchers/…} → {@code BepInEx/patchers/<mod>/…};</li>
     *   <li>{@code BepInEx/config/…}, {@code config/…} → {@code BepInEx/config/…}, never over an existing file;</li>
     *   <li>{@code BepInEx/core/…} → skipped (ours is bundled); other {@code BepInEx/…} → as is;</li>
     *   <li>any other folder (e.g. {@code valheim_Data/…}) → the game folder, replaced files backed up;</li>
     *   <li>native Windows DLLs anywhere → skipped (a .NET assembly also ends in .dll and is kept).</li>
     * </ul>
     */
    public static String install(Context ctx, Uri zip, File gameDir, String fallbackName) throws IOException {
        byte[] data = readAll(ctx, zip);
        List<String> names = entryNames(data);
        String wrapper = commonWrapper(names);

        String modName = null, version = null;
        byte[] manifest = extractOne(data, wrapper + "manifest.json");
        if (manifest != null) {
            String[] nv = parseManifest(new String(manifest, StandardCharsets.UTF_8));
            modName = nv[0]; version = nv[1];
        }
        if (modName == null || modName.isEmpty()) modName = fallbackName;
        modName = sanitize(modName);
        if (modName.isEmpty()) modName = "mod";

        File root = bepinexDir(gameDir);
        ensureDirs(root);
        File modDir = new File(new File(root, PLUGINS), modName);
        File offDir = new File(new File(root, PLUGINS_OFF), modName);
        // An update replaces the previous copy wherever it was, and restores what it had replaced.
        if (offDir.exists()) { restoreGameFiles(gameDir, offDir); deleteRecursive(offDir); }
        if (modDir.exists()) { restoreGameFiles(gameDir, modDir); deleteRecursive(modDir); }
        mkdirs(modDir);
        File patchersDir = new File(new File(root, "patchers"), modName);
        List<String> gameFiles = new ArrayList<>();
        int written = 0;

        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String path = e.getName().replace('\\', '/');
                if (!path.startsWith(wrapper)) continue;
                path = path.substring(wrapper.length());
                if (path.isEmpty() || !isSafeRelative(path)) continue;
                byte[] body = readEntry(zis);
                if (isNativeWindowsBinary(path, body)) continue;

                String lower = path.toLowerCase(Locale.ROOT);
                int slash = path.indexOf('/');
                String first = slash < 0 ? "" : lower.substring(0, slash);
                String rest = slash < 0 ? path : path.substring(slash + 1);
                File out;
                if (slash < 0) {
                    if (lower.equals("manifest.json")) out = new File(modDir, "manifest.json");
                    else if (lower.equals("icon.png") || lower.startsWith("readme") || lower.startsWith("changelog")) continue;
                    else out = new File(modDir, path);
                } else if (first.equals("bepinex")) {
                    int s2 = rest.indexOf('/');
                    String sub = s2 < 0 ? "" : rest.substring(0, s2).toLowerCase(Locale.ROOT);
                    String tail = s2 < 0 ? rest : rest.substring(s2 + 1);
                    if (sub.equals("plugins")) out = new File(modDir, tail);
                    else if (sub.equals("patchers")) out = new File(patchersDir, tail);
                    else if (sub.equals("config")) { out = new File(new File(root, "config"), tail); if (out.exists()) continue; }
                    else if (sub.equals("core")) continue;
                    else out = new File(root, rest);
                } else if (first.equals("plugins")) {
                    out = new File(modDir, rest);
                } else if (first.equals("patchers")) {
                    out = new File(patchersDir, rest);
                } else if (first.equals("config")) {
                    out = new File(new File(root, "config"), rest);
                    if (out.exists()) continue;
                } else {
                    // A game folder such as valheim_Data/Managed: back up what is there first.
                    out = new File(gameDir, path);
                    if (out.isFile()) backupGameFile(gameDir, modName, path, out);
                    gameFiles.add(path);
                }
                mkdirs(out.getParentFile());
                try (OutputStream os = new FileOutputStream(out)) { os.write(body); }
                written++;
            }
        }
        if (!gameFiles.isEmpty()) writeSmall(new File(modDir, GAME_FILES_LIST), String.join("\n", gameFiles));
        if (written == 0) {
            deleteRecursive(modDir);
            throw new IOException("Nothing to install in this archive");
        }
        Log.i(TAG, "Installed " + modName + (version != null ? " " + version : "") + ": " + written + " files");
        return modName;
    }

    // ---- game file backups ---------------------------------------------------------------------

    private static void backupGameFile(File gameDir, String modName, String rel, File current) throws IOException {
        File backup = new File(new File(new File(bepinexDir(gameDir), BACKUP_DIR), modName), rel);
        if (backup.exists()) return;   // keep the original from the first install, not a mod's copy
        mkdirs(backup.getParentFile());
        try (InputStream in = new FileInputStream(current)) { copy(in, backup); }
    }

    private static void restoreGameFiles(File gameDir, File modLocation) {
        if (!modLocation.isDirectory()) return;
        String list = readSmall(new File(modLocation, GAME_FILES_LIST));
        if (list == null) return;
        File backupRoot = new File(new File(bepinexDir(gameDir), BACKUP_DIR), modLocation.getName());
        for (String rel : list.split("\n")) {
            rel = rel.trim();
            if (rel.isEmpty() || !isSafeRelative(rel)) continue;
            File target = new File(gameDir, rel);
            File backup = new File(backupRoot, rel);
            if (backup.isFile()) {
                mkdirs(target.getParentFile());
                if (target.exists()) target.delete();
                if (!backup.renameTo(target)) Log.w(TAG, "Could not restore " + target);
            } else if (target.isFile()) {
                target.delete();   // the mod added this file; nothing to put back
            }
        }
        deleteRecursive(backupRoot);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * A Windows PE image without a .NET (CLR) header: native code that cannot run here. A .NET
     * assembly is also a PE file but carries the CLR data directory, and those are what mods are.
     */
    static boolean isNativeWindowsBinary(String path, byte[] b) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".dll") && !lower.endsWith(".exe")) return false;
        if (b.length < 0x40 || b[0] != 'M' || b[1] != 'Z') return false;
        int pe = le32(b, 0x3c);
        if (pe < 0 || pe + 24 > b.length || b[pe] != 'P' || b[pe + 1] != 'E') return false;
        int opt = pe + 24;
        int magic = le16(b, opt);
        int dirs = magic == 0x20b ? opt + 112 : opt + 96;   // PE32+ vs PE32
        int clr = dirs + 14 * 8;                            // data directory 14 = CLR runtime header
        if (clr + 8 > b.length) return true;
        return le32(b, clr) == 0 && le32(b, clr + 4) == 0;
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    /** "Name With Spaces" → a folder name: no separators, no leading dots. */
    private static String sanitize(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        while (out.startsWith(".")) out = out.substring(1);
        return out.length() > 80 ? out.substring(0, 80) : out;
    }

    private static boolean isSafeRelative(String p) {
        if (p.startsWith("/") || p.contains(":")) return false;
        for (String part : p.split("/")) if (part.equals("..")) return false;
        return true;
    }

    /** A single top-level folder every entry sits in (e.g. "MyMod-1.0/"), or "" when there is none. */
    private static String commonWrapper(List<String> names) {
        String top = null;
        for (String n : names) {
            int s = n.indexOf('/');
            if (s < 0) return "";
            String t = n.substring(0, s + 1);
            if (top == null) top = t; else if (!top.equals(t)) return "";
        }
        if (top == null) return "";
        String t = top.toLowerCase(Locale.ROOT);
        // A zip holding only BepInEx/ or plugins/ is a layout, not a wrapper.
        if (t.equals("bepinex/") || t.equals("plugins/") || t.equals("patchers/") || t.equals("config/")) return "";
        return top;
    }

    private static List<String> entryNames(byte[] data) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) if (!e.isDirectory()) names.add(e.getName().replace('\\', '/'));
        }
        if (names.isEmpty()) throw new IOException("Not a zip archive, or an empty one");
        return names;
    }

    private static byte[] extractOne(byte[] data, String name) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null)
                if (e.getName().replace('\\', '/').equalsIgnoreCase(name)) return readEntry(zis);
        }
        return null;
    }

    private static String[] readManifest(File f) {
        String s = readSmall(f);
        return s == null ? new String[2] : parseManifest(s);
    }

    /** {name, version_number} from a Thunderstore manifest.json; nulls when absent. */
    private static String[] parseManifest(String json) {
        try {
            if (json.startsWith("﻿")) json = json.substring(1);
            JSONObject o = new JSONObject(json);
            String n = o.optString("name", "").replace('_', ' ').trim();
            String v = o.optString("version_number", "").trim();
            return new String[]{ n.isEmpty() ? null : n, v.isEmpty() ? null : v };
        } catch (Exception e) {
            return new String[2];
        }
    }

    private static byte[] readAll(Context ctx, Uri uri) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 512L * 1024 * 1024) throw new IOException("Archive too large for a mod");
            }
            return bos.toByteArray();
        }
    }

    private static byte[] readEntry(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static void ensureDirs(File root) {
        mkdirs(new File(root, PLUGINS));
        mkdirs(new File(root, PLUGINS_OFF));
    }

    private static void mkdirs(File d) {
        if (d != null && !d.isDirectory() && !d.mkdirs()) Log.w(TAG, "Could not create " + d);
    }

    private static String readSmall(File f) {
        if (!f.isFile() || f.length() > 1 << 20) return null;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeSmall(File f, String s) throws IOException {
        mkdirs(f.getParentFile());
        try (OutputStream os = new FileOutputStream(f)) { os.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteRecursive(k);
        if (!f.delete()) Log.w(TAG, "Could not delete " + f);
    }
}

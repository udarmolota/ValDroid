package com.valdroid.controls;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.valdroid.AppStorage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Where an instance's on-screen controls live, and their import/export.
 *
 * Layout: {@code <instance dir>/controls/controls.json}, custom icons in
 * {@code <instance dir>/controls/icons/} — the same shape as Zomdroid, so a Zomdroid controls
 * zip and a ValDroid one are the same kind of file. Without an instance (the smoke test) the
 * layout sits in {@code <home>/controls}. No file = the bundled default layout.
 */
public final class ControlsStorage {
    private static final String TAG = "ValDroid/Controls";

    /** Keyboard + touchpad layout: the default for a new instance. */
    public static final String ASSET_VKBD = "controls/valheim_vkbd.json";
    /** On-screen gamepad layout (drives the virtual Xbox 360 pad). */
    public static final String ASSET_GAMEPAD = "controls/valheim_gamepad.json";
    public static final String DEFAULT_ASSET = ASSET_VKBD;

    public static final String FILE_NAME = "controls.json";
    public static final String ICONS_DIR = "icons";

    private static final Gson GSON = new Gson();

    private ControlsStorage() {}

    @Nullable
    public static File controlsDir(@Nullable String instanceName) {
        AppStorage st = AppStorage.getSingleton();
        if (st == null) return null;
        if (instanceName == null || instanceName.isEmpty()) return new File(st.getHomePath(), "controls");
        return new File(st.getInstanceDir(instanceName), "controls");
    }

    @Nullable
    public static File layoutFile(@Nullable String instanceName) {
        File d = controlsDir(instanceName);
        return d == null ? null : new File(d, FILE_NAME);
    }

    @Nullable
    public static File iconsDir(@Nullable String instanceName) {
        File d = controlsDir(instanceName);
        return d == null ? null : new File(d, ICONS_DIR);
    }

    /** The saved layout, or null when the instance has none yet. */
    @Nullable
    public static String readLayoutJson(@Nullable String instanceName) {
        File f = layoutFile(instanceName);
        if (f == null || !f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (IOException e) {
            Log.w(TAG, "read " + f + " failed: " + e);
            return null;
        }
    }

    public static boolean writeLayoutJson(@Nullable String instanceName, @NonNull String json) {
        File f = layoutFile(instanceName);
        if (f == null) return false;
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        // Write a sibling first and rename over: a crash mid-write must not leave half a layout.
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp, false)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "write " + tmp + " failed: " + e);
            return false;
        }
        return tmp.renameTo(f) || copyOver(tmp, f);
    }

    @Nullable
    public static String readAsset(@NonNull Context c, @NonNull String asset) {
        try (InputStream in = c.getAssets().open(asset)) {
            return readAll(in);
        } catch (IOException e) {
            Log.e(TAG, "layout asset " + asset + " read failed: " + e);
            return null;
        }
    }

    /** Parse a layout; null when it is not one. Entries that cannot form an element are dropped. */
    @Nullable
    public static List<ControlElementDescription> parse(@Nullable String json) {
        if (json == null) return null;
        try {
            Type t = new TypeToken<ArrayList<ControlElementDescription>>() {}.getType();
            List<ControlElementDescription> raw = GSON.fromJson(json, t);
            if (raw == null) return null;
            List<ControlElementDescription> out = new ArrayList<>();
            for (ControlElementDescription d : raw) {
                ControlElementDescription ok = ControlElementDescription.sanitize(d);
                if (ok != null) out.add(ok);
            }
            return out;
        } catch (RuntimeException e) {
            Log.w(TAG, "parse layout failed: " + e.getMessage());
            return null;
        }
    }

    public static String toJson(List<ControlElementDescription> list) {
        return GSON.toJson(list);
    }

    // ------------------------------------------------------------------ export / import

    /**
     * Zip the instance's controls folder (controls.json + icons/). An instance still on the
     * bundled default gets that default as its controls.json, so export always yields a layout.
     */
    public static void exportZip(@NonNull Context c, @Nullable String instanceName,
                                 @NonNull OutputStream os) throws IOException {
        String json = readLayoutJson(instanceName);
        if (json == null) json = readAsset(c, DEFAULT_ASSET);
        if (json == null) throw new IOException("no layout to export");
        try (ZipOutputStream zos = new ZipOutputStream(os)) {
            zos.putNextEntry(new ZipEntry(FILE_NAME));
            zos.write(json.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            File icons = iconsDir(instanceName);
            File[] files = icons != null ? icons.listFiles() : null;
            if (files != null) {
                byte[] buf = new byte[64 * 1024];
                for (File f : files) {
                    if (!f.isFile()) continue;
                    zos.putNextEntry(new ZipEntry(ICONS_DIR + "/" + f.getName()));
                    try (InputStream in = new FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Install a layout into the instance: a zip holding controls.json (at any depth, as Zomdroid
     * accepts) plus optional icons/, or a bare controls .json. The layout is checked to be a
     * layout before anything is written. Throws with a readable message otherwise.
     */
    public static void importLayout(@NonNull InputStream in, @Nullable String instanceName) throws IOException {
        byte[] data = readAllBytes(in);
        boolean isZip = data.length >= 4 && data[0] == 'P' && data[1] == 'K';
        String json = null;
        List<String> iconNames = new ArrayList<>();
        List<byte[]> iconData = new ArrayList<>();
        if (isZip) {
            try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory() || e.getName() == null) continue;
                    String name = e.getName().replace('\\', '/');
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if (lower.endsWith(FILE_NAME)) {
                        json = new String(readAllBytes(zis), StandardCharsets.UTF_8);
                    } else if (lower.contains(ICONS_DIR + "/")) {
                        // Keep only the file name: an entry path must never leave icons/ (zip slip).
                        String base = name.substring(name.lastIndexOf('/') + 1);
                        if (!base.isEmpty() && !base.contains("..")) {
                            iconNames.add(base);
                            iconData.add(readAllBytes(zis));
                        }
                    }
                }
            }
            if (json == null) throw new IOException("controls.json not found in the zip");
        } else {
            json = new String(data, StandardCharsets.UTF_8);
        }
        List<ControlElementDescription> list = parse(json);
        if (list == null || list.isEmpty()) throw new IOException("not a ValDroid controls layout");

        File icons = iconsDir(instanceName);
        if (!iconNames.isEmpty() && icons != null) {
            if (!icons.isDirectory() && !icons.mkdirs()) throw new IOException("cannot create " + icons);
            for (int i = 0; i < iconNames.size(); i++) {
                try (OutputStream out = new FileOutputStream(new File(icons, iconNames.get(i)), false)) {
                    out.write(iconData.get(i));
                }
            }
        }
        if (!writeLayoutJson(instanceName, json)) throw new IOException("cannot write the layout");
    }

    // ------------------------------------------------------------------ io helpers

    private static String readAll(InputStream in) throws IOException {
        return new String(readAllBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[16 * 1024];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    private static boolean copyOver(File from, File to) {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to, false)) {
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } catch (IOException e) {
            Log.w(TAG, "copy " + from + " -> " + to + " failed: " + e);
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        from.delete();
        return true;
    }
}

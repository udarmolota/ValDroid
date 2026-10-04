package com.valdroid.game;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Native engine (experimental): runs the game on Unity's own ARM64 Android player instead of the Linux
 * player under box64, with the game's C# on ValDroid's ARM64 Mono through il2mono. Only present when
 * the APK was built with the ":unity" module (tools/unity-native/prepare_unity_module.sh).
 *
 * Preparing an instance builds nothing big: the player's data (globalgamemanagers, levels, assets) goes
 * into one stored zip the player mounts in place of its APK; asset bundles, saves and the game's
 * managed assemblies are read straight from the instance. Research and plan:
 * docs/research/unity-native-integration-plan.md.
 */
public final class NativeEngine {
    private static final String TAG = "ValDroid/NativeEngine";
    private static final String ACTIVITY = "com.valdroid.nativeunity.NativeUnityActivity";
    private static final String EXTRA_DATA_ARCHIVE = "valdroid.dataArchive";
    private static final String EXTRA_ENV = "valdroid.env";

    /** Player files packaged in the APK; they win over the game's copies of the same names. */
    private static final String PLAYER_ASSETS = "bin/Data";
    /** Android builds of engine/package assemblies that replace the game's Linux copies. */
    private static final String MANAGED_OVERRIDES_ASSETS = "valdroid_native/Managed";

    /** Unity BuildTarget: the Android player only loads serialized files tagged as Android. */
    private static final int TARGET_ANDROID = 13;
    private static final int TARGET_LINUX64 = 24;

    private NativeEngine() {}

    /** Whether this APK carries the native player module. */
    public static boolean isAvailable() {
        try {
            Class.forName(ACTIVITY);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Prepares the instance (only what changed) and returns the intent that starts the player. */
    public static Intent prepare(Context context, GameInstance instance) throws IOException {
        File gameDir = new File(instance.getGamePath());
        File dataDir = new File(gameDir, instance.getDataDirectoryName());
        if (!new File(dataDir, "globalgamemanagers").isFile())
            throw new IOException("game data not found in " + dataDir);
        File workDir = new File(gameDir, "native_engine");
        if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("cannot create " + workDir);

        long apkStamp = apkStamp(context);
        File overrides = extractManagedOverrides(context, apkStamp);
        File archive = buildDataArchive(context, dataDir, workDir, apkStamp);
        File etc = prepareMonoConfig(dataDir, workDir);

        List<String> env = new ArrayList<>();
        env.add("VALDROID_IL2MONO_MANAGED=" + overrides.getAbsolutePath() + ":" + new File(dataDir, "Managed").getAbsolutePath());
        env.add("VALDROID_IL2MONO_ETC=" + etc.getAbsolutePath());
        env.add("VALDROID_IL2MONO_CWD=" + gameDir.getAbsolutePath()); // steam_appid.txt lives here
        env.add("VALDROID_IL2MONO_STREAMING_ASSETS=" + new File(dataDir, "StreamingAssets").getAbsolutePath());
        env.add("VALDROID_IL2MONO_PERSISTENT_DATA=" + instance.getUserDataDir().getAbsolutePath());

        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), ACTIVITY);
        intent.putExtra(EXTRA_DATA_ARCHIVE, archive.getAbsolutePath());
        intent.putExtra(EXTRA_ENV, env.toArray(new String[0]));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static long apkStamp(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- managed overrides: copied out of the APK once per install ---------------------------

    private static File extractManagedOverrides(Context context, long apkStamp) throws IOException {
        File dir = new File(context.getFilesDir(), "native_engine/Managed");
        File stamp = new File(dir, ".stamp");
        if (readStamp(stamp) == apkStamp && apkStamp != 0) return dir;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        AssetManager assets = context.getAssets();
        String[] names = assets.list(MANAGED_OVERRIDES_ASSETS);
        if (names == null || names.length == 0) throw new IOException("no managed overrides in the APK");
        for (String name : names) {
            try (InputStream in = assets.open(MANAGED_OVERRIDES_ASSETS + "/" + name);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                copy(in, out);
            }
        }
        writeStamp(stamp, apkStamp);
        Log.i(TAG, "extracted " + names.length + " managed overrides");
        return dir;
    }

    // ---- data archive -------------------------------------------------------------------------

    // Folders and files of the Linux data dir the player must not see (or that come from the APK).
    private static boolean isExcluded(String relative) {
        return relative.startsWith("Managed/") || relative.startsWith("MonoBleedingEdge/")
                || relative.startsWith("Plugins/") || relative.startsWith("StreamingAssets/")
                || relative.equals("boot.config") || relative.equals("Resources/unity default resources")
                || relative.endsWith(".png");
    }

    private static File buildDataArchive(Context context, File dataDir, File workDir, long apkStamp) throws IOException {
        File archive = new File(workDir, "data.zip");
        File stampFile = new File(workDir, "data.zip.stamp");
        List<String> gameFiles = new ArrayList<>();
        listFiles(dataDir, "", gameFiles);
        long stamp = apkStamp;
        for (String rel : gameFiles) {
            if (isExcluded(rel)) continue;
            File f = new File(dataDir, rel);
            stamp = stamp * 31 + f.length() * 7 + f.lastModified();
        }
        if (archive.isFile() && readStamp(stampFile) == stamp) return archive;

        File tmp = new File(workDir, "data.zip.tmp");
        AssetManager assets = context.getAssets();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(tmp))) {
            List<String> playerFiles = new ArrayList<>();
            listAssets(assets, PLAYER_ASSETS, "", playerFiles);
            for (String rel : playerFiles) {
                byte[] bytes;
                try (InputStream in = assets.open(PLAYER_ASSETS + "/" + rel)) {
                    bytes = readAll(in);
                }
                putStored(zip, "assets/bin/Data/" + rel, bytes);
            }
            for (String rel : gameFiles) {
                if (isExcluded(rel)) continue;
                File f = new File(dataDir, rel);
                if (rel.equals("ScriptingAssemblies.json")) {
                    putStored(zip, "assets/bin/Data/" + rel, addBridgeAssembly(readFile(f)));
                } else if (rel.equals("RuntimeInitializeOnLoads.json")) {
                    putStored(zip, "assets/bin/Data/" + rel, addBridgeInitializer(readFile(f)));
                } else {
                    putStoredFile(zip, "assets/bin/Data/" + rel, f);
                }
            }
        }
        if (archive.exists() && !archive.delete()) throw new IOException("cannot replace " + archive);
        if (!tmp.renameTo(archive)) throw new IOException("cannot rename " + tmp);
        writeStamp(stampFile, stamp);
        Log.i(TAG, "built " + archive + " (" + archive.length() / (1024 * 1024) + " MB)");
        return archive;
    }

    // The virtual gamepad assembly (ValDroidBridge.dll) is not part of the game: list it and its
    // [RuntimeInitializeOnLoadMethod] so the player loads and starts it.
    private static byte[] addBridgeAssembly(byte[] json) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(json, StandardCharsets.UTF_8));
            JSONArray names = root.getJSONArray("names");
            JSONArray types = root.getJSONArray("types");
            for (int i = 0; i < names.length(); i++)
                if ("ValDroidBridge.dll".equals(names.getString(i))) return json;
            names.put("ValDroidBridge.dll");
            types.put(16);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("ScriptingAssemblies.json: " + e.getMessage(), e);
        }
    }

    private static byte[] addBridgeInitializer(byte[] json) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(json, StandardCharsets.UTF_8));
            JSONArray list = root.getJSONArray("root");
            for (int i = 0; i < list.length(); i++)
                if ("ValDroidBridge".equals(list.getJSONObject(i).optString("assemblyName"))) return json;
            JSONObject entry = new JSONObject();
            entry.put("assemblyName", "ValDroidBridge");
            entry.put("nameSpace", "ValDroid");
            entry.put("className", "VirtualPad");
            entry.put("methodName", "Initialize");
            entry.put("loadTypes", 1); // BeforeSceneLoad
            entry.put("isUnityClass", false);
            list.put(entry);
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IOException("RuntimeInitializeOnLoads.json: " + e.getMessage(), e);
        }
    }

    /**
     * Offset of the target-platform int in a Unity SerializedFile header (format v22+), or -1 when the
     * file is not one: 48-byte header, then the unityVersion C string, then int32 target.
     */
    static int targetPlatformOffset(byte[] head, int length) {
        if (length < 64) return -1;
        int version = ((head[8] & 0xff) << 24) | ((head[9] & 0xff) << 16) | ((head[10] & 0xff) << 8) | (head[11] & 0xff);
        if (version < 22 || version > 64) return -1;
        int end = 48;
        while (end < length && end < 120 && head[end] != 0) {
            if (head[end] < 0x20 || head[end] > 0x7e) return -1; // printable, e.g. "6000.0.75f1"
            end++;
        }
        if (end == 48 || end + 5 > length || head[end] != 0) return -1;
        boolean littleEndian = head[16] == 0;
        int off = end + 1;
        int target = littleEndian
                ? (head[off] & 0xff) | ((head[off + 1] & 0xff) << 8) | ((head[off + 2] & 0xff) << 16) | ((head[off + 3] & 0xff) << 24)
                : ((head[off] & 0xff) << 24) | ((head[off + 1] & 0xff) << 16) | ((head[off + 2] & 0xff) << 8) | (head[off + 3] & 0xff);
        return target == TARGET_LINUX64 ? off : -1;
    }

    // Writes a file as a STORED entry; a Linux-tagged serialized file gets its target set to Android
    // on the way (the Android player refuses any other target, "created for another build target").
    private static void putStoredFile(ZipOutputStream zip, String name, File file) throws IOException {
        byte[] head = new byte[256];
        int headLen;
        try (InputStream in = new FileInputStream(file)) {
            headLen = Math.max(0, in.read(head));
        }
        int targetOff = targetPlatformOffset(head, headLen);
        if (targetOff >= 0) {
            boolean le = head[16] == 0;
            head[targetOff] = (byte) (le ? TARGET_ANDROID : 0);
            head[targetOff + 1] = 0;
            head[targetOff + 2] = 0;
            head[targetOff + 3] = (byte) (le ? 0 : TARGET_ANDROID);
        }
        // STORED needs size and CRC before the data: one pass to checksum, one to write.
        CRC32 crc = new CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(file)) {
            long pos = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                patchHead(buf, n, pos, head, headLen);
                crc.update(buf, 0, n);
                pos += n;
            }
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(file.length());
        entry.setCompressedSize(file.length());
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        try (InputStream in = new FileInputStream(file)) {
            long pos = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                patchHead(buf, n, pos, head, headLen);
                zip.write(buf, 0, n);
                pos += n;
            }
        }
        zip.closeEntry();
    }

    private static void patchHead(byte[] buf, int n, long pos, byte[] head, int headLen) {
        for (int i = 0; i < n && pos + i < headLen; i++) buf[i] = head[(int) (pos + i)];
    }

    private static void putStored(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    // ---- Mono config ----------------------------------------------------------------------------

    // The game's etc/mono, with System.Native mapped to the libmono-native.so in the app's own
    // native libs instead of the Linux layout's $mono_libdir.
    private static File prepareMonoConfig(File dataDir, File workDir) throws IOException {
        File src = new File(dataDir, "MonoBleedingEdge/etc");
        File dst = new File(workDir, "etc");
        copyTree(src, dst);
        File config = new File(dst, "mono/config");
        if (config.isFile()) {
            String text = new String(readFile(config), StandardCharsets.UTF_8)
                    .replace("$mono_libdir/libmono-native.so", "libmono-native.so");
            try (OutputStream out = new FileOutputStream(config)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
        return dst;
    }

    // ---- small file helpers -------------------------------------------------------------------

    private static void listFiles(File dir, String prefix, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String rel = prefix + k.getName();
            if (k.isDirectory()) listFiles(k, rel + "/", out);
            else out.add(rel);
        }
    }

    private static void listAssets(AssetManager assets, String root, String prefix, List<String> out) throws IOException {
        String path = prefix.isEmpty() ? root : root + "/" + prefix.substring(0, prefix.length() - 1);
        String[] names = assets.list(path);
        if (names == null) return;
        for (String name : names) {
            String rel = prefix + name;
            String[] sub = assets.list(root + "/" + rel);
            if (sub != null && sub.length > 0) listAssets(assets, root, rel + "/", out);
            else out.add(rel);
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("cannot create " + dst);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else if (src.isFile()) {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                copy(in, out);
            }
        }
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out);
        return out.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static long readStamp(File f) {
        try {
            return Long.parseLong(new String(readFile(f), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private static void writeStamp(File f, long value) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(Long.toString(value).getBytes(StandardCharsets.UTF_8));
        }
    }
}

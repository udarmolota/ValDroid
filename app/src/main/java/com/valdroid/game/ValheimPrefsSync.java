package com.valdroid.game;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps Valheim's settings (PlayerPrefs) the same for both ways of running an instance. The box64
 * launch runs the Linux player, which keeps them in {@code <instance>/unity3d/unknown/unknown/prefs}
 * (XML, strings in base64); the native engine runs the Android player, which keeps them in this app's
 * SharedPreferences file {@code <package>.v2.playerprefs.xml} (names and strings URL-encoded). Before
 * either launch the newer of the two is copied over the other; the copy takes the source's time, so
 * the two then count as equal until the game changes one of them.
 *
 * Unity's own keys stay with their player: the screen size (Screenmanager*, the box64 surface is not
 * the phone's) and the unity.* / unity_connect.* session ids.
 */
public final class ValheimPrefsSync {
    private static final String TAG = "ValDroid/PrefsSync";

    private ValheimPrefsSync() {}

    /** Typed value of one pref: Integer, Float or String. */
    private static final class Pref {
        final String type;   // "int", "float", "string"
        final String value;  // as text, strings decoded
        Pref(String type, String value) { this.type = type; this.value = value; }
    }

    private static boolean isPlayerOwn(String key) {
        return key.startsWith("Screenmanager") || key.startsWith("unity.") || key.startsWith("unity_connect.");
    }

    static File linuxFile(File instanceDir) {
        return new File(instanceDir, "unity3d/unknown/unknown/prefs");
    }

    static File androidFile(Context context) {
        return new File(context.getDataDir(), "shared_prefs/" + context.getPackageName() + ".v2.playerprefs.xml");
    }

    /** Before a native launch: the Linux prefs, when newer, become the Android ones. */
    public static void toNative(Context context, File instanceDir) {
        if (isNativeEngineRunning(context)) {
            Log.i(TAG, "native engine still running: its settings stay as they are");
            return;
        }
        sync(linuxFile(instanceDir), androidFile(context), true);
    }

    /** Before a box64 launch: the Android prefs, when newer, become the Linux ones. */
    public static void toLinux(Context context, File instanceDir) {
        sync(androidFile(context), linuxFile(instanceDir), false);
    }

    private static void sync(File src, File dst, boolean toAndroid) {
        try {
            if (!src.isFile()) return;
            if (dst.isFile() && dst.lastModified() >= src.lastModified()) return;
            Map<String, Pref> prefs = toAndroid ? readLinux(src) : readAndroid(src);
            Map<String, Pref> merged = new LinkedHashMap<>();
            if (dst.isFile()) {   // the destination player's own keys survive
                for (Map.Entry<String, Pref> e : (toAndroid ? readAndroid(dst) : readLinux(dst)).entrySet())
                    if (isPlayerOwn(e.getKey())) merged.put(e.getKey(), e.getValue());
            }
            int copied = 0;
            for (Map.Entry<String, Pref> e : prefs.entrySet()) {
                if (isPlayerOwn(e.getKey())) continue;
                merged.put(e.getKey(), e.getValue());
                copied++;
            }
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
            File tmp = new File(dst.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write((toAndroid ? writeAndroid(merged) : writeLinux(merged)).getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(dst)) throw new IOException("cannot replace " + dst);
            dst.setLastModified(src.lastModified());
            Log.i(TAG, copied + " settings " + (toAndroid ? "box64 -> native" : "native -> box64"));
        } catch (Exception e) {
            Log.w(TAG, "settings not synced " + src + " -> " + dst, e);
        }
    }

    private static boolean isNativeEngineRunning(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> procs = am != null ? am.getRunningAppProcesses() : null;
        if (procs == null) return false;
        String name = context.getPackageName() + ":unity";
        for (ActivityManager.RunningAppProcessInfo p : procs)
            if (name.equals(p.processName)) return true;
        return false;
    }

    // ------------------------------------------------------------------ Linux: <pref name type>value</pref>

    private static Map<String, Pref> readLinux(File f) throws Exception {
        Map<String, Pref> out = new LinkedHashMap<>();
        try (InputStream in = new FileInputStream(f)) {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev != XmlPullParser.START_TAG || !"pref".equals(x.getName())) continue;
                String name = x.getAttributeValue(null, "name");
                String type = x.getAttributeValue(null, "type");
                String text = x.nextText().trim();
                if (name == null || type == null) continue;
                if ("string".equals(type))
                    text = new String(Base64.decode(text, Base64.DEFAULT), StandardCharsets.UTF_8);
                out.put(name, new Pref(type, text));
            }
        }
        return out;
    }

    private static String writeLinux(Map<String, Pref> prefs) {
        StringBuilder sb = new StringBuilder("<unity_prefs version_major=\"1\" version_minor=\"1\">\n");
        for (Map.Entry<String, Pref> e : prefs.entrySet()) {
            Pref p = e.getValue();
            String value = "string".equals(p.type)
                    ? Base64.encodeToString(p.value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)
                    : p.value;
            sb.append("\t<pref name=\"").append(escape(e.getKey())).append("\" type=\"").append(p.type)
                    .append("\">").append(value).append("</pref>\n");
        }
        return sb.append("</unity_prefs>\n").toString();
    }

    // ------------------------------------------------------------------ Android: SharedPreferences XML

    private static Map<String, Pref> readAndroid(File f) throws Exception {
        Map<String, Pref> out = new LinkedHashMap<>();
        try (InputStream in = new FileInputStream(f)) {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(in, "UTF-8");
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                String tag = x.getName();
                String name = x.getAttributeValue(null, "name");
                if (name == null) continue;
                name = Uri.decode(name);
                if ("int".equals(tag) || "float".equals(tag)) {
                    out.put(name, new Pref(tag, x.getAttributeValue(null, "value")));
                } else if ("string".equals(tag)) {
                    out.put(name, new Pref("string", Uri.decode(x.nextText())));
                }
                // PlayerPrefs only has int, float and string; anything else is not the game's.
            }
        }
        return out;
    }

    private static String writeAndroid(Map<String, Pref> prefs) {
        StringBuilder sb = new StringBuilder("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n");
        for (Map.Entry<String, Pref> e : prefs.entrySet()) {
            Pref p = e.getValue();
            String name = escape(Uri.encode(e.getKey()));
            if ("string".equals(p.type)) {
                sb.append("    <string name=\"").append(name).append("\">")
                        .append(escape(Uri.encode(p.value))).append("</string>\n");
            } else {
                sb.append("    <").append(p.type).append(" name=\"").append(name)
                        .append("\" value=\"").append(escape(p.value)).append("\" />\n");
            }
        }
        return sb.append("</map>\n").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

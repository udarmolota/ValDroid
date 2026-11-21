package com.valdroid;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The catalog of Valheim mods: Thunderstore's package listing for mod managers (what r2modman reads),
 * kept on the device. The listing is an index of a dozen gzip parts (about 15 MB together, 2026-11)
 * on Thunderstore's CDN, each named by the hash of its contents. A part is downloaded once, read as a
 * stream and stored as a small file of one line per package (its latest version only); on a refresh
 * only parts with a new name are fetched, and parts no longer in the index are dropped.
 *
 * <p>Not on the main thread: everything here reads the network or the disk.
 */
public final class ThunderstoreIndex {
    private static final String INDEX_URL = "https://thunderstore.io/c/valheim/api/v1/package-listing-index/";
    private static final String DIR = "thunderstore_index";
    private static final String STAMP = "checked";   // time of the last successful refresh
    private static final int TIMEOUT_MS = 60_000;
    /** Refresh at most this often on its own. */
    public static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    private ThunderstoreIndex() {}

    /** One package as the catalog shows it: its latest version. */
    public static final class Entry {
        public final String namespace, name, version, description, icon, updated;
        public final long downloads, size;
        public final int rating;
        public final boolean deprecated, nsfw;
        public final List<String> categories, dependencies;

        Entry(String namespace, String name, String version, String description, String icon, String updated,
              long downloads, long size, int rating, boolean deprecated, boolean nsfw,
              List<String> categories, List<String> dependencies) {
            this.namespace = namespace; this.name = name; this.version = version;
            this.description = description; this.icon = icon; this.updated = updated;
            this.downloads = downloads; this.size = size; this.rating = rating;
            this.deprecated = deprecated; this.nsfw = nsfw;
            this.categories = categories; this.dependencies = dependencies;
        }

        public String key() { return namespace + "-" + name; }

        /** The name as people read it: Thunderstore names use '_' for spaces. */
        public String title() { return name.replace('_', ' '); }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("n", namespace).put("m", name).put("v", version).put("d", description).put("i", icon)
                    .put("u", updated).put("dl", downloads).put("s", size).put("r", rating)
                    .put("x", deprecated).put("nsfw", nsfw)
                    .put("c", new JSONArray(categories)).put("dep", new JSONArray(dependencies));
            return o;
        }

        static Entry fromJson(JSONObject o) {
            return new Entry(o.optString("n"), o.optString("m"), o.optString("v"), o.optString("d"),
                    o.optString("i"), o.optString("u"), o.optLong("dl"), o.optLong("s"), o.optInt("r"),
                    o.optBoolean("x"), o.optBoolean("nsfw"), strings(o.optJSONArray("c")), strings(o.optJSONArray("dep")));
        }
    }

    /** Progress of a refresh: parts done of parts to fetch. */
    public interface Progress { void onPart(int done, int total); }

    public static File dir(Context c) { return new File(c.getFilesDir(), DIR); }

    /** True when a catalog has been stored before (it can be shown without the network). */
    public static boolean hasCache(Context c) {
        File[] parts = dir(c).listFiles((d, n) -> n.endsWith(".part"));
        return parts != null && parts.length > 0;
    }

    /** Milliseconds since the last successful refresh; Long.MAX_VALUE when never. */
    public static long age(Context c) {
        File f = new File(dir(c), STAMP);
        return f.isFile() ? Math.max(0, System.currentTimeMillis() - f.lastModified()) : Long.MAX_VALUE;
    }

    /** Brings the stored catalog up to date with Thunderstore's index; only new parts are downloaded. */
    public static void refresh(Context c, @Nullable Progress progress) throws IOException {
        File dir = dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        List<String> urls = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(new String(maybeGunzip(get(INDEX_URL, 1 << 20)), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) urls.add(a.getString(i));
        } catch (org.json.JSONException e) {
            throw new IOException("Thunderstore: unexpected catalog index (" + e.getMessage() + ")");
        }
        if (urls.isEmpty()) throw new IOException("Thunderstore: empty catalog index");
        Set<String> keep = new HashSet<>();
        List<String> missing = new ArrayList<>();
        for (String u : urls) {
            String name = partName(u);
            keep.add(name);
            if (!new File(dir, name).isFile()) missing.add(u);
        }
        for (int i = 0; i < missing.size(); i++) {
            if (progress != null) progress.onPart(i, missing.size());
            String u = missing.get(i);
            File part = new File(dir, partName(u));
            File tmp = new File(dir, part.getName() + ".tmp");
            HttpURLConnection conn = open(u);
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024)) {
                convert(gunzipIfNeeded(in), tmp);
            } finally {
                conn.disconnect();
            }
            if (!tmp.renameTo(part)) throw new IOException("Cannot save " + part);
        }
        if (progress != null) progress.onPart(missing.size(), missing.size());
        File[] old = dir.listFiles((d, n) -> n.endsWith(".part") && !keep.contains(n));
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File stamp = new File(dir, STAMP);
        try (FileOutputStream os = new FileOutputStream(stamp)) { os.write('1'); }
        //noinspection ResultOfMethodCallIgnored
        stamp.setLastModified(System.currentTimeMillis());
    }

    /** Every stored package, any order. */
    public static List<Entry> load(Context c) throws IOException {
        List<Entry> out = new ArrayList<>(16_000);
        File[] parts = dir(c).listFiles((d, n) -> n.endsWith(".part"));
        if (parts == null) return out;
        for (File f : parts) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    try { out.add(Entry.fromJson(new JSONObject(line))); }
                    catch (org.json.JSONException ignored) { /* a damaged line: skip it */ }
                }
            }
        }
        return out;
    }

    /** By key ("Namespace-Name"). */
    public static Map<String, Entry> byKey(List<Entry> all) {
        Map<String, Entry> m = new HashMap<>(all.size() * 2);
        for (Entry e : all) m.put(e.key(), e);
        return m;
    }

    // ------------------------------------------------------------------ reading a part

    /**
     * Streams one listing part (a JSON array of packages, each with all its versions, newest first)
     * into a file of one line per package. Only the newest version is kept; downloads are summed
     * over all versions, as Thunderstore shows them.
     */
    private static void convert(InputStream in, File out) throws IOException {
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            r.beginArray();
            while (r.hasNext()) {
                Entry e = readPackage(r);
                if (e == null) continue;
                try { w.write(e.toJson().toString()); }
                catch (org.json.JSONException ex) { continue; }
                w.write('\n');
            }
            r.endArray();
        }
    }

    @Nullable
    private static Entry readPackage(JsonReader r) throws IOException {
        String owner = null, name = null, updated = "";
        int rating = 0;
        boolean deprecated = false, nsfw = false;
        List<String> categories = new ArrayList<>();
        String version = null, description = "", icon = "";
        long downloads = 0, size = 0;
        List<String> deps = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue; }
            switch (k) {
                case "owner": owner = r.nextString(); break;
                case "name": name = r.nextString(); break;
                case "date_updated": updated = r.nextString(); break;
                case "rating_score": rating = r.nextInt(); break;
                case "is_deprecated": deprecated = r.nextBoolean(); break;
                case "has_nsfw_content": nsfw = r.nextBoolean(); break;
                case "categories": readStrings(r, categories); break;
                case "versions":
                    r.beginArray();
                    boolean first = true;
                    while (r.hasNext()) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String vk = r.nextName();
                            if (r.peek() == JsonToken.NULL) { r.skipValue(); continue; }
                            if (vk.equals("downloads")) downloads += r.nextLong();
                            else if (!first) r.skipValue();
                            else if (vk.equals("version_number")) version = r.nextString();
                            else if (vk.equals("description")) description = r.nextString();
                            else if (vk.equals("icon")) icon = r.nextString();
                            else if (vk.equals("file_size")) size = r.nextLong();
                            else if (vk.equals("dependencies")) readStrings(r, deps);
                            else r.skipValue();
                        }
                        r.endObject();
                        first = false;
                    }
                    r.endArray();
                    break;
                default: r.skipValue();
            }
        }
        r.endObject();
        if (owner == null || name == null || version == null) return null;
        return new Entry(owner, name, version, description, icon, updated, downloads, size, rating,
                deprecated, nsfw, categories, deps);
    }

    private static void readStrings(JsonReader r, List<String> into) throws IOException {
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() == JsonToken.STRING) into.add(r.nextString());
            else r.skipValue();
        }
        r.endArray();
    }

    // ------------------------------------------------------------------ helpers

    /** The part's file name: the CDN names a blob by the hash of its contents. */
    private static String partName(String url) {
        String last = url.replaceAll("/+$", "");
        last = last.substring(last.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (last.length() > 120) last = last.substring(last.length() - 120);
        return last + ".part";
    }

    private static InputStream gunzipIfNeeded(InputStream in) throws IOException {
        in.mark(2);
        int a = in.read(), b = in.read();
        in.reset();
        return a == 0x1f && b == 0x8b ? new GZIPInputStream(in, 64 * 1024) : in;
    }

    private static byte[] maybeGunzip(byte[] data) throws IOException {
        if (data.length < 2 || (data[0] & 0xff) != 0x1f || (data[1] & 0xff) != 0x8b) return data;
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > max) throw new IOException("Answer too large: " + url);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);   // the index redirects to its blob on the CDN
        c.setRequestProperty("User-Agent", "ValDroid/" + BuildConfig.VERSION_NAME);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("Thunderstore answered " + code + " for " + url);
        }
        return c;
    }

    private static List<String> strings(@Nullable JSONArray a) {
        if (a == null || a.length() == 0) return Collections.emptyList();
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}

package com.valdroid;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Valheim mods from Thunderstore (thunderstore.io), the site r2modman installs from. Nothing needs a
 * sign-in: a package's metadata comes from its public API and the zip from its download link.
 *
 * <p>A package is "Namespace-Name"; a version of it "Namespace-Name-1.2.3", which is also how its
 * dependencies are written. {@link #resolve} walks the dependencies of the packages asked for, keeps
 * the newest version wanted of each, and leaves out BepInEx (bundled with ValDroid) and what the
 * instance already has at that version or newer. Network calls: not on the main thread.
 */
public final class ThunderstoreClient {
    private static final String API = "https://thunderstore.io/api/experimental/package/";
    private static final String DOWNLOAD = "https://thunderstore.io/package/download/";
    private static final int TIMEOUT_MS = 30_000;
    /** More than any Valheim mod; a guard against a page that keeps sending. */
    private static final long MAX_ZIP_BYTES = 1024L * 1024 * 1024;

    private static final String PART = "[A-Za-z0-9_]+";
    private static final String VERSION = "\\d+(?:\\.\\d+)+";
    /** A package page: /c/valheim/p/Namespace/Name/ (any tab under it). */
    private static final Pattern PAGE = Pattern.compile("/c/valheim/p/(" + PART + ")/(" + PART + ")(?:/|$)");
    /** Download links: /package/download/Ns/Name/1.2.3/, ror2mm://v1/install/thunderstore.io/Ns/Name/1.2.3/ */
    private static final Pattern LINK = Pattern.compile("/(?:package/download|install/thunderstore\\.io)/("
            + PART + ")/(" + PART + ")/(" + VERSION + ")/?");
    /** The CDN file a download link redirects to: .../packages/Ns-Name-1.2.3.zip */
    private static final Pattern CDN = Pattern.compile("/packages/(" + PART + ")-(" + PART + ")-(" + VERSION + ")\\.zip$");
    private static final Pattern FULL = Pattern.compile("^(" + PART + ")-(" + PART + ")-(" + VERSION + ")$");

    private ThunderstoreClient() {}

    /** A package, at a given version or (version == null) its latest. */
    public static final class Pkg {
        public final String namespace, name;
        @Nullable public final String version;

        public Pkg(String namespace, String name, @Nullable String version) {
            this.namespace = namespace; this.name = name; this.version = version;
        }

        public String key() { return namespace + "-" + name; }

        @Override public String toString() { return version != null ? key() + "-" + version : key(); }
    }

    /** A package version ready to install: what it needs is listed separately. */
    public static final class Resolved {
        public final Pkg pkg;          // version always set
        public final String downloadUrl;
        /** Bytes to download; 0 when unknown (not in the catalog). */
        public long size;
        final List<Pkg> dependencies = new ArrayList<>();

        Resolved(Pkg pkg, String downloadUrl) { this.pkg = pkg; this.downloadUrl = downloadUrl; }
    }

    /** The package a site page shows (version null: the page is about the package, not a version). */
    @Nullable
    public static Pkg fromPage(@Nullable String url) {
        if (url == null) return null;
        Matcher m = PAGE.matcher(Uri.parse(url).getPath() != null ? Uri.parse(url).getPath() : "");
        return m.find() ? new Pkg(m.group(1), m.group(2), null) : null;
    }

    /** The package version a download or "install with mod manager" link points at. */
    @Nullable
    public static Pkg fromDownloadLink(@Nullable String url) {
        if (url == null) return null;
        Matcher m = LINK.matcher(url);
        if (m.find()) return new Pkg(m.group(1), m.group(2), m.group(3));
        m = CDN.matcher(url);
        return m.find() ? new Pkg(m.group(1), m.group(2), m.group(3)) : null;
    }

    /** "Ns-Name-1.2.3" (a dependency string). */
    @Nullable
    static Pkg fromFullName(String full) {
        Matcher m = FULL.matcher(full.trim());
        return m.matches() ? new Pkg(m.group(1), m.group(2), m.group(3)) : null;
    }

    /** Thunderstore's BepInEx packs: ValDroid ships its own BepInEx. */
    static boolean isBepInEx(Pkg p) {
        return p.name.toLowerCase(Locale.ROOT).startsWith("bepinexpack");
    }

    /** The package's metadata at its version (latest when unset). */
    public static Resolved fetch(Pkg p) throws IOException {
        String url = API + p.namespace + "/" + p.name + "/" + (p.version != null ? p.version + "/" : "");
        try {
            JSONObject o = new JSONObject(new String(get(url, 1 << 20), StandardCharsets.UTF_8));
            JSONObject v = p.version != null ? o : o.getJSONObject("latest");
            Pkg exact = new Pkg(p.namespace, p.name, v.getString("version_number"));
            String download = v.optString("download_url", "");
            Resolved r = new Resolved(exact, download.isEmpty()
                    ? DOWNLOAD + p.namespace + "/" + p.name + "/" + exact.version + "/" : download);
            JSONArray deps = v.optJSONArray("dependencies");
            if (deps != null) {
                for (int i = 0; i < deps.length(); i++) {
                    Pkg d = fromFullName(deps.getString(i));
                    if (d != null) r.dependencies.add(d);
                }
            }
            return r;
        } catch (org.json.JSONException e) {
            throw new IOException("Thunderstore: unexpected answer for " + p + " (" + e.getMessage() + ")");
        }
    }

    /**
     * Everything to install for {@code wanted}: those packages and, recursively, what they depend on,
     * one version per package (the newest asked for), without BepInEx and without what the instance
     * already has at that version or newer ({@code installed}: mod name → version). The packages
     * asked for are always included, so asking again reinstalls. Order: dependencies first.
     *
     * <p>A package in the catalog ({@code index}, may be empty) is taken from it at its latest version,
     * which is at least what any dependency asks for, without a request; others are asked of the API.
     */
    public static List<Resolved> resolve(List<Pkg> wanted, Map<String, String> installed,
                                         Map<String, ThunderstoreIndex.Entry> index) throws IOException {
        Map<String, Resolved> byKey = new LinkedHashMap<>();
        java.util.Set<String> asked = new java.util.HashSet<>();
        Deque<Pkg> todo = new ArrayDeque<>();
        for (Pkg p : wanted) { todo.add(p); asked.add(p.key()); }
        while (!todo.isEmpty()) {
            Pkg p = todo.poll();
            if (isBepInEx(p)) continue;
            Resolved have = byKey.get(p.key());
            if (have != null && (p.version == null || compare(have.pkg.version, p.version) >= 0)) continue;
            if (!asked.contains(p.key()) && p.version != null) {
                String inst = installed.get(p.name.toLowerCase(Locale.ROOT));
                if (inst != null && compare(inst, p.version) >= 0) continue;
            }
            ThunderstoreIndex.Entry e = index.get(p.key());
            Resolved r;
            if (e != null && (p.version == null || compare(e.version, p.version) >= 0)) {
                r = new Resolved(new Pkg(e.namespace, e.name, e.version),
                        DOWNLOAD + e.namespace + "/" + e.name + "/" + e.version + "/");
                r.size = e.size;
                for (String d : e.dependencies) {
                    Pkg dp = fromFullName(d);
                    if (dp != null) r.dependencies.add(dp);
                }
            } else {
                r = fetch(p);
            }
            byKey.put(p.key(), r);
            todo.addAll(r.dependencies);
        }
        // Dependencies before what needs them: the reverse of discovery is close enough for a
        // tree; installing order matters only for the list the player reads.
        List<Resolved> out = new ArrayList<>(byKey.values());
        java.util.Collections.reverse(out);
        return out;
    }

    /** Downloads a package zip into {@code dir} (replacing an earlier copy) and returns the file. */
    public static File download(Resolved r, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File out = new File(dir, r.pkg + ".zip");
        File part = new File(dir, r.pkg + ".zip.part");
        HttpURLConnection c = open(r.downloadUrl);
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_ZIP_BYTES) throw new IOException("Download too large: " + r.pkg);
                os.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
        if (out.exists() && !out.delete()) throw new IOException("Cannot replace " + out);
        if (!part.renameTo(out)) throw new IOException("Cannot save " + out);
        return out;
    }

    /** Numeric version order: 1.10.0 > 1.9.3; missing parts count as 0. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
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
        c.setInstanceFollowRedirects(true);   // the download link redirects to the CDN, https to https
        c.setRequestProperty("User-Agent", "ValDroid/" + BuildConfig.VERSION_NAME);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException(code == HttpURLConnection.HTTP_NOT_FOUND
                    ? "Not found on Thunderstore: " + url : "Thunderstore answered " + code + " for " + url);
        }
        return c;
    }
}

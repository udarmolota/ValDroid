package com.valdroid;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.StatFs;
import android.os.storage.StorageManager;

import androidx.annotation.Nullable;

import com.valdroid.game.GameInstance;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "who / what / where" block at the top of every bug report (report.txt) and of launcher.log.
 *
 * <p>Motivating case (AYN Thor, 2026-09-27): the launcher crashed before the game started because
 * the app lived on an SD card formatted as internal storage (/mnt/expand/&lt;uuid&gt;/...) and the X
 * server socket path no longer fit in sun_path. The report zip said nothing about where the app was
 * installed, which build it was, or whether the game had ever started — every one of those facts
 * had to be asked for. This block answers them up front, and it works without a game instance and
 * before the first launch.
 *
 * <p>Everything here is best-effort: a failing probe prints its error in its own line and never
 * stops the rest of the header. The GPU probe creates a throwaway EGL context, so call
 * {@link #header} off the UI thread.
 */
public final class ReportInfo {

    /** sun_path is 108 bytes including the terminating NUL. */
    public static final int SUN_PATH_MAX = 107;

    private static final Pattern GAME_VERSION = Pattern.compile("\\bl-\\d+\\.\\d+\\.\\d+");

    private ReportInfo() {}

    /** Build identity: versionName (versionCode, git id, debug/release). */
    public static String buildId() {
        return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ", "
                + BuildConfig.GIT_BUILD_ID + ", " + (BuildConfig.DEBUG ? "debug" : "release") + ")";
    }

    /**
     * Device SoC fingerprint: chip maker/model (API 31+) + the always-available hardware string.
     * Together with the GL_RENDERER line (GPU) this identifies a tester's device at a glance.
     */
    public static String deviceSoc() {
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 31)
            sb.append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append(' ');
        sb.append("[hw=").append(Build.HARDWARE).append(']');
        return sb.toString().trim();
    }

    /**
     * True when the app's data lives on adopted storage (an SD card formatted as internal). Paths
     * there are much longer (/mnt/expand/&lt;36-char uuid&gt;/user/0/com.valdroid/...), which is what
     * broke the X socket, and the card is usually slower than the built-in flash.
     */
    public static boolean isAdoptable(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getApplicationInfo();
            if (ai.dataDir != null && ai.dataDir.startsWith("/mnt/expand/")) return true;
            return ai.storageUuid != null && !StorageManager.UUID_DEFAULT.equals(ai.storageUuid);
        } catch (Throwable t) {
            return false;
        }
    }

    /** "adoptable (SD card used as internal)" or "internal" — one word for logs and the email. */
    public static String storageKind(Context ctx) {
        return isAdoptable(ctx) ? "ADOPTABLE STORAGE (SD card used as internal)" : "internal";
    }

    /** Host path of the X server socket, exactly as GameLauncher creates it (filesDir-based). */
    public static String x11SocketPath(Context ctx) {
        return new File(ctx.getFilesDir(), "tmp/.X11-unix/X0").getPath();
    }

    /** "N bytes (limit 107)" plus a TOO LONG flag — the check XServerRunner will make at launch. */
    public static String describeSocketPath(String path) {
        int n = path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return path + "  [" + n + " bytes, limit " + SUN_PATH_MAX
                + (n > SUN_PATH_MAX ? " — TOO LONG, the X server cannot bind" : "") + "]";
    }

    /** One-line GPU name from the GLES probe; never throws. Creates an EGL context: not on the UI thread. */
    public static String gpu() {
        try {
            GpuInfo g = GpuInfo.query();
            return g.displayName() + (g.vendor != null ? " / " + g.vendor : "");
        } catch (Throwable t) {
            return "probe failed: " + t;
        }
    }

    /** The full header, "key : value" lines, newline-terminated. */
    /** The Vulkan version the phone's driver declares (what the native engine renders with). */
    static String vulkanVersion(Context ctx) {
        int best = 0;
        for (android.content.pm.FeatureInfo f : ctx.getPackageManager().getSystemAvailableFeatures()) {
            if (android.content.pm.PackageManager.FEATURE_VULKAN_HARDWARE_VERSION.equals(f.name))
                best = Math.max(best, f.version);
        }
        if (best == 0) return "none declared";
        return (best >> 22) + "." + ((best >> 12) & 0x3ff) + "." + (best & 0xfff);
    }

    public static String header(Context ctx, @Nullable GameInstance gi) {
        StringBuilder sb = new StringBuilder(4096);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
        sb.append("=== ValDroid report ===\n");
        line(sb, "generated", fmt.format(new Date()));

        // --- Device ---
        line(sb, "device", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line(sb, "soc", deviceSoc());
        line(sb, "gpu", gpu());
        line(sb, "vulkan", vulkanVersion(ctx));
        String pageSize;
        try {
            pageSize = String.valueOf(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE));
        } catch (Throwable t) { pageSize = "?"; }
        line(sb, "android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), abis "
                + String.join(",", Build.SUPPORTED_ABIS) + ", page size " + pageSize);
        line(sb, "ram", ramSummary(ctx));
        // Pads disagree on which axes are the right stick and the triggers; the full ranges are
        // logged by GamepadHandler when the game starts or a pad connects.
        line(sb, "gamepad", com.valdroid.input.GamepadHandler.describeConnected(ctx));

        // --- Build ---
        line(sb, "valdroid", buildId());
        appendPackageInfo(sb, ctx, fmt);

        // --- Install location ---
        try {
            ApplicationInfo ai = ctx.getApplicationInfo();
            line(sb, "storage", storageKind(ctx)
                    + (ai.storageUuid != null ? ", uuid " + ai.storageUuid : ""));
            line(sb, "source dir", String.valueOf(ai.sourceDir));
            line(sb, "data dir", String.valueOf(ai.dataDir));
            line(sb, "native libs", String.valueOf(ai.nativeLibraryDir));
        } catch (Throwable t) {
            line(sb, "storage", "unavailable: " + t);
        }
        line(sb, "files dir", ctx.getFilesDir().getAbsolutePath());
        line(sb, "cache dir", ctx.getCacheDir().getAbsolutePath());
        line(sb, "x11 socket", describeSocketPath(x11SocketPath(ctx)));
        line(sb, "free space", freeSpace(ctx.getFilesDir()));
        line(sb, "launcher log", LauncherLog.status());

        // --- Instance ---
        if (gi == null) {
            line(sb, "instance", "(none)" + instanceList());
        } else {
            line(sb, "instance", gi.getName());
            line(sb, "game path", gi.getGamePath());
            line(sb, "installed", String.valueOf(gi.isInstalled()));
            String missing;
            try {
                List<String> m = gi.missingCoreFiles();
                missing = m.isEmpty() ? "(none)" : String.join(", ", m);
            } catch (Throwable t) { missing = "check failed: " + t; }
            line(sb, "missing files", missing);
            line(sb, "download", gi.isDownloadUnfinished() ? "UNFINISHED (download marker present)" : "(none)");
            line(sb, "game version", gameVersion(gi));
            line(sb, "box64", box64Banner(gi));
        }

        // --- Log ages: a stale file (from a run days ago) is obvious at a glance ---
        File files = ctx.getFilesDir();
        line(sb, "launcher.log", age(new File(files, LauncherLog.FILE), fmt));
        if (gi != null) {
            File game = new File(gi.getGamePath());
            line(sb, "rimdroid.log", age(new File(game, "rimdroid.log"), fmt));
            line(sb, "box64.log", age(new File(game, "box64.log"), fmt));
            line(sb, "Player.log", age(new File(gi.getUserDataDir(), "Player.log"), fmt));
        }
        sb.append("=======================\n");
        return sb.toString();
    }

    private static void appendPackageInfo(StringBuilder sb, Context ctx, SimpleDateFormat fmt) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String installer = null;
            try {
                installer = pm.getInstallSourceInfo(ctx.getPackageName()).getInstallingPackageName();
            } catch (Throwable ignored) {}
            line(sb, "installer", installer != null ? installer : "(none / sideloaded)");
            PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            line(sb, "app installed", fmt.format(new Date(pi.firstInstallTime))
                    + ", updated " + fmt.format(new Date(pi.lastUpdateTime)));
        } catch (Throwable t) {
            line(sb, "installer", "unavailable: " + t);
        }
    }

    /** Names of the instance directories, so a report without a chosen instance still shows them. */
    private static String instanceList() {
        try {
            AppStorage st = AppStorage.getSingleton();
            if (st == null) return "";
            String[] names = st.getInstancesDir().list();
            if (names == null || names.length == 0) return ", no instances on disk";
            java.util.Arrays.sort(names);
            return ", on disk: " + String.join(", ", names);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * RAM as the kernel sees it plus the framework's lowMemory flag: what separates "killed for
     * memory" from "crashed on its own". MemTotal is what the kernel manages, so an 8 GB device
     * reports ~7.4 GB.
     */
    static String ramSummary(Context ctx) {
        long total = -1, avail = -1, swapTotal = -1, swapFree = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal:")) total = meminfoKb(l);
                else if (l.startsWith("MemAvailable:")) avail = meminfoKb(l);
                else if (l.startsWith("SwapTotal:")) swapTotal = meminfoKb(l);
                else if (l.startsWith("SwapFree:")) swapFree = meminfoKb(l);
            }
        } catch (Throwable ignored) {}
        StringBuilder s = new StringBuilder();
        if (total < 0) s.append("meminfo unavailable");
        else {
            s.append(gbKb(total)).append(" total");
            if (avail >= 0) s.append(", ").append(gbKb(avail)).append(" available");
            if (swapTotal >= 0) s.append(", swap ").append(gbKb(swapFree >= 0 ? swapFree : 0))
                    .append(" free of ").append(gbKb(swapTotal));
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            s.append(", lowMemory=").append(mi.lowMemory)
             .append(" (threshold ").append(gb(mi.threshold)).append(')');
        } catch (Throwable ignored) {}
        return s.toString();
    }

    private static long meminfoKb(String line) {
        try { return Long.parseLong(line.trim().split("\\s+")[1]); } catch (Exception e) { return -1; }
    }

    private static String gbKb(long kb) { return String.format(Locale.US, "%.1f GB", kb / 1048576.0); }

    private static String gb(long bytes) { return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0); }

    /** Free/total space of the volume holding the app's files (where instances are installed). */
    static String freeSpace(File dir) {
        try {
            StatFs st = new StatFs(dir.getAbsolutePath());
            return gb(st.getAvailableBytes()) + " free of " + gb(st.getTotalBytes());
        } catch (Throwable t) {
            return "unavailable: " + t;
        }
    }

    /**
     * Valheim's build string ("l-1.0.15") as Unity's Player.log prints it near the top of a run.
     * The previous run's log is the fallback: Unity replaces Player.log at the start of each run,
     * so a run that died early may have left a log without the version line.
     */
    static String gameVersion(GameInstance gi) {
        File dir = gi.getUserDataDir();
        boolean anyLog = false;
        for (String name : new String[] { "Player.log", "Player-prev.log" }) {
            File f = new File(dir, name);
            anyLog |= f.isFile();
            // The line comes after Unity's start-up output (hundreds of "BC7 ... decompressing"
            // warnings on GLES), ~700-900 lines in on real reports — a 300-line window always
            // missed it. Stop at the first match, so this stays cheap.
            String v = grepFirst(f, GAME_VERSION, 20000);
            if (v != null) return v + " (from " + name + ")";
        }
        return anyLog ? "unknown (no version line in Player.log)" : "unknown (no Player.log yet)";
    }

    /** box64's own banner line ("Box64 arm64 v0.x.y <hash> with Dynarec built on ...") from its log. */
    static String box64Banner(GameInstance gi) {
        Pattern p = Pattern.compile("Box64.*built on.*");
        for (String name : new String[] { "box64.log", "rimdroid.log" }) {
            String v = grepFirst(new File(gi.getGamePath(), name), p, 300);
            if (v != null) return v.trim() + " (from " + name + ")";
        }
        return "unknown (no box64 log yet)";
    }

    @Nullable
    private static String grepFirst(File f, Pattern p, int maxLines) {
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            for (int i = 0; i < maxLines && (l = r.readLine()) != null; i++) {
                Matcher m = p.matcher(l);
                if (m.find()) return m.group();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String age(File f, SimpleDateFormat fmt) {
        if (!f.isFile()) return "(absent)";
        return "modified " + fmt.format(new Date(f.lastModified())) + ", " + (f.length() / 1024) + " KB";
    }

    /** One "key : value" line, padded, newline-terminated. */
    static void line(StringBuilder sb, String key, String value) {
        sb.append(key);
        for (int i = key.length(); i < 14; i++) sb.append(' ');
        sb.append(": ").append(value).append('\n');
    }
}

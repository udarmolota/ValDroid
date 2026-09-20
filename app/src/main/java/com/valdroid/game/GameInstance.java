package com.valdroid.game;

import com.valdroid.AppStorage;

import java.io.File;
import java.util.ArrayList;

public class GameInstance {

    private static final GameDescriptor GAME = GameDescriptor.VALHEIM;

    private final String name;

    public GameInstance(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public String getExecutableName() { return GAME.executable(); }

    public String getDataDirectoryName() { return GAME.dataDirectory(); }

    /** Per-instance launch settings (renderer, Vulkan driver, debug, interpreter). */
    public com.valdroid.InstanceSettings settings() {
        return new com.valdroid.InstanceSettings(name);
    }

    public String getGamePath() {
        return AppStorage.requireSingleton().getInstanceDir(name).getAbsolutePath();
    }

    /** Unity persistentDataPath inside this instance (saves, configuration, and logs). */
    public File getUserDataDir() {
        return new File(getGamePath(), GAME.userDataDirectory());
    }

    /**
     * x86_64 library search path for box64 (BOX64_LD_LIBRARY_PATH).
     * Contains ONLY x86_64 libraries — game libs and Linux system libs.
     * ARM64 renderer libs do NOT belong here.
     */
    public String getLdLibraryPathForEmulation() {
        AppStorage storage = AppStorage.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();

        String gameDir = getGamePath();
        // Unity data dir is always named {Executable}_Data for Linux builds
        String dataDir = gameDir + "/" + GAME.dataDirectory();

        // Game root dir (top-level .so files, if any)
        paths.add(gameDir);

        // Mono runtime — libmonobdwgc-2.0.so, libMonoPosixHelper.so
        paths.add(dataDir + "/MonoBleedingEdge/x86_64");

        // Game plugins (x86_64) — ScreenSelector.so etc.
        paths.add(dataDir + "/Plugins/x86_64");

        // Game plugins (no arch suffix) — libsteam_api.so, libCSteamworks.so etc.
        paths.add(dataDir + "/Plugins");

        // x86_64 system libs — libgcc_s.so.1 etc.
        paths.add(storage.getLibsLinuxX86Path());

        return join(paths, ":");
    }

    /**
     * ARM64 native library path — passed to Android linker for loading
     * our ARM64 .so files (renderer, APK native libs).
     */
    public String getNativeLibraryPath() {
        AppStorage storage = AppStorage.requireSingleton();

        ArrayList<String> paths = new ArrayList<>();

        // APK native libs (libvaldroid.so, libvaldroidlinker.so etc.)
        paths.add(storage.getLibraryPath());
        paths.add("/system/lib64");

        // ARM64 renderer libs — per this instance's renderer choice
        switch (settings().getRenderer()) {
            case GL4ES:
            case MOBILEGLUES:   // same deps dir; the launcher maps it onto the GL4ES plumbing
                paths.add(storage.getGl4esLibsPath());
                break;
            case ZINK_ZFA:
            case ZINK_OSMESA:
                paths.add(storage.getZinkLibsPath());
                break;
            case SOFTPIPE:
                // libOSMesa.so (softpipe CPU renderer) lives in the deps dir alongside libzfa.so.
                // This dir MUST be in the search path or rimdroid_ns can't resolve "libOSMesa.so"
                // by soname → rimdroid_init_osmesa()'s namespace dlopen returns NULL.
                paths.add(storage.getGl4esLibsPath());
                paths.add(storage.getZinkLibsPath());   // same deps dir; harmless if duplicate
                break;
        }

        return join(paths, ":");
    }

    /**
     * DEAD on the launch path in use: the standalone exec builds the command line natively (see
     * valdroid.c), where the screen size and the VALDROID_GAME_ARGS / VALDROID_JOB_WORKERS switches
     * are appended. Kept as an empty stub for the legacy in-process JNI path only.
     */
    public String[] getArgs() {
        return new String[0];
    }

    public boolean isInstalled() {
        return new File(getGamePath(), GAME.executable()).isFile();
    }

    /** Required Valheim runtime files that are absent from this instance. */
    public java.util.List<String> missingCoreFiles() {
        File root = new File(getGamePath());
        java.util.List<String> missing = new ArrayList<>();
        for (String rel : GAME.requiredFiles())
            if (!new File(root, rel).isFile()) missing.add(rel);
        // Existence alone is not enough: an interrupted download (the Steam downloader can die
        // mid-way on low-memory phones) leaves a truncated or empty RimWorldLinux that passes
        // isFile(). box64 then reports only "is not an executable file", which reads like an app
        // bug. Verify it really is an x86-64 ELF of plausible size, so the launcher can say
        // "incomplete, download again" instead.
        if (missing.isEmpty() && !isX86_64Elf(new File(root, GAME.executable())))
            missing.add(GAME.executable() + " (incomplete or corrupted)");
        return missing;
    }

    /**
     * True if {@code f} is a complete x86-64 ELF executable: right magic/class/machine, and the
     * header and section tables it declares actually fit inside the file. Do NOT gate this on a
     * minimum size; the ELF structure itself is the reliable truncation signal.
     */
    private static boolean isX86_64Elf(File f) {
        final long len = f.length();
        if (!f.isFile() || len < 64) return false;             // smaller than an ELF64 header
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] h = new byte[64];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) return false;
                n += r;
            }
            if (!(h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F')) return false;
            if (h[4] != 2 || h[5] != 1) return false;          // ELFCLASS64, little-endian
            if (le16(h, 18) != 0x3e) return false;             // e_machine = EM_X86_64
            // Truncation check: a half-downloaded file keeps a valid header but loses the tail the
            // header points at, which is exactly what box64 reports as "not an executable file".
            long phEnd = le64(h, 32) + (long) le16(h, 54) * le16(h, 56);   // e_phoff + e_phentsize*e_phnum
            long shEnd = le64(h, 40) + (long) le16(h, 58) * le16(h, 60);   // e_shoff + e_shentsize*e_shnum
            return phEnd <= len && shEnd <= len;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xffL);
        return v;
    }

    /** True if the required Valheim runtime layout is present. */
    public boolean isComplete() { return missingCoreFiles().isEmpty(); }

    private static String join(ArrayList<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}

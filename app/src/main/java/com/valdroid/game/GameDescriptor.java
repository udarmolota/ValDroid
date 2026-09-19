package com.valdroid.game;

/**
 * Immutable description of the Linux game layout understood by the launcher.
 *
 * Keep game-specific paths here instead of scattering them through import,
 * validation, and launch code.  The first ValDroid milestone supports one game,
 * but using a descriptor keeps the inherited RimWorld assumptions out of the
 * new Valheim launch path.
 */
public final class GameDescriptor {
    public static final GameDescriptor VALHEIM = new GameDescriptor(
            "valheim.x86_64",
            "valheim_Data",
            "unity3d/IronGate/Valheim",
            "892970",
            new String[] {
                    "valheim.x86_64",
                    "UnityPlayer.so",
                    "steam_appid.txt",
                    "valheim_Data/globalgamemanagers",
                    "valheim_Data/Managed/Assembly-CSharp.dll",
                    "valheim_Data/Managed/assembly_valheim.dll",
                    "valheim_Data/Managed/mscorlib.dll",
                    "valheim_Data/MonoBleedingEdge/x86_64/libmonobdwgc-2.0.so",
                    "valheim_Data/MonoBleedingEdge/x86_64/libmono-native.so",
                    "valheim_Data/MonoBleedingEdge/x86_64/libMonoPosixHelper.so",
                    "valheim_Data/Plugins/lib_burst_generated.so"
            });

    private final String executable;
    private final String dataDirectory;
    private final String userDataDirectory;
    private final String steamAppId;
    private final String[] requiredFiles;

    private GameDescriptor(String executable,
                           String dataDirectory,
                           String userDataDirectory,
                           String steamAppId,
                           String[] requiredFiles) {
        this.executable = executable;
        this.dataDirectory = dataDirectory;
        this.userDataDirectory = userDataDirectory;
        this.steamAppId = steamAppId;
        this.requiredFiles = requiredFiles.clone();
    }

    public String executable() {
        return executable;
    }

    public String dataDirectory() {
        return dataDirectory;
    }

    public String userDataDirectory() {
        return userDataDirectory;
    }

    public String steamAppId() {
        return steamAppId;
    }

    public String[] requiredFiles() {
        return requiredFiles.clone();
    }
}

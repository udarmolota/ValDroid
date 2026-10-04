#!/bin/sh
# Fill unity/prepared/ (git-ignored) with the Unity files the native player module needs.
# Nothing of Unity is committed to this repository; run this once per machine / Unity version.
#
# usage: prepare_unity_module.sh <android-support.pkg> <unity-gradle-export> <player-scripts>
#   <android-support.pkg>   UnitySetup-Android-Support-for-Editor-6000.0.75f1.pkg (macOS package,
#                           https://download.unity3d.com/download_unity/26349cd2a5c8/MacEditorTargetInstaller/)
#   <unity-gradle-export>   a Gradle export of an empty Unity 6000.0.75f1 project (IL2CPP, ARM64, Vulkan),
#                           e.g. Desktop/VD/unity-poc/Export/gradle; provides boot.config,
#                           "unity default resources" and libswappywrapper.so.
#   <player-scripts>        Android player builds of Unity.InputSystem(.ForUI).dll and ValDroidBridge.dll:
#                           output of PocBuild.CompileAndroidPlayerScripts in Desktop/VD/unity-poc
#                           (Export/player_scripts; packages com.unity.inputsystem 1.19.0 + com.unity.ugui,
#                           bridge source in tools/unity-native/bridge).
#
# Result:
#   unity/prepared/jniLibs/arm64-v8a/libunity.so   full (unstripped) engine, build-target check patched
#   unity/prepared/jniLibs/arm64-v8a/libmain.so
#   unity/prepared/jniLibs/arm64-v8a/libswappywrapper.so   (optional, frame pacing)
#   unity/prepared/libs/unity-classes.jar
#   unity/prepared/assets/bin/Data/boot.config, unity_app_guid, Resources/unity default resources,
#                                                   Managed/Resources
#   unity/prepared/assets/valdroid_native/Managed/  Android builds that replace the game's Linux copies:
#                                                   UnityEngine*.dll (Unity's Android Mono variation),
#                                                   Unity.InputSystem(.ForUI).dll, ValDroidBridge.dll
set -e
USAGE="usage: prepare_unity_module.sh <android-support.pkg> <unity-gradle-export> <player-scripts>"
PKG=${1:?$USAGE}
EXPORT=${2:?$USAGE}
SCRIPTS=${3:?$USAGE}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$ROOT/unity/prepared
PY=${PYTHON:-python}
# The package payload is a gzip'ed cpio archive: needs bsdtar (Windows' own tar.exe is bsdtar;
# GNU tar from Git Bash cannot read cpio).
if [ -z "$TAR" ]; then
    if [ -x /c/Windows/System32/tar.exe ]; then TAR=/c/Windows/System32/tar.exe
    elif command -v bsdtar >/dev/null 2>&1; then TAR=bsdtar
    else TAR=tar; fi
fi
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

VARIATION=Variations/il2cpp/Release
echo "extracting the Android player from $PKG"
"$PY" "$HERE/xar_extract.py" "$PKG" get TargetSupport.pkg.tmp/Payload "$WORK/Payload"
(cd "$WORK" && "$TAR" -xf Payload "./$VARIATION/Libs/arm64-v8a/libunity.so" "./$VARIATION/Libs/arm64-v8a/libmain.so" \
    "./$VARIATION/Classes/classes.jar" "./Variations/mono/Managed/*")

rm -rf "$OUT"
mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/libs" "$OUT/assets/bin"
"$PY" "$HERE/patch_libunity_target.py" "$WORK/$VARIATION/Libs/arm64-v8a/libunity.so" "$OUT/jniLibs/arm64-v8a/libunity.so"
cp "$WORK/$VARIATION/Libs/arm64-v8a/libmain.so" "$OUT/jniLibs/arm64-v8a/"
cp "$WORK/$VARIATION/Classes/classes.jar" "$OUT/libs/unity-classes.jar"

SWAPPY=$(find "$EXPORT" -path '*jniLibs/arm64-v8a/libswappywrapper.so' -o -path '*arm64-v8a/libswappywrapper.so' 2>/dev/null | head -1)
[ -n "$SWAPPY" ] && cp "$SWAPPY" "$OUT/jniLibs/arm64-v8a/" || echo "note: no libswappywrapper.so in the export (frame pacing off)"

DATA="$EXPORT/unityLibrary/src/main/assets/bin/Data"
[ -d "$DATA" ] || { echo "no player data at $DATA" >&2; exit 1; }
# Only the player's own files go into the APK. The game data (globalgamemanagers, levels, assets,
# *.json) comes from an OBB the launcher builds from the instance: the engine mounts the APK first,
# so any game file left here would shadow the OBB's.
mkdir -p "$OUT/assets/bin/Data/Resources"
cp "$DATA/boot.config" "$OUT/assets/bin/Data/"
# Read by GetAppGuid() during UnityInitApplication; the player does not start without it.
cp "$DATA/unity_app_guid" "$OUT/assets/bin/Data/"
# The player extracts Managed/Resources to its files dir on first start and reports a missing source
# as "Not enough storage space to install required resources". IL2CPP metadata is not needed.
mkdir -p "$OUT/assets/bin/Data/Managed/Resources"
cp "$DATA/Managed/Resources/"* "$OUT/assets/bin/Data/Managed/Resources/"
cp "$DATA/Resources/unity default resources" "$OUT/assets/bin/Data/Resources/"
# Managed assemblies that replace the game's Linux copies on the Android player (il2mono searches this
# dir before the game's Managed folder).
MANAGED="$OUT/assets/valdroid_native/Managed"
mkdir -p "$MANAGED"
cp "$WORK/Variations/mono/Managed/"UnityEngine*.dll "$MANAGED/"
for dll in Unity.InputSystem.dll Unity.InputSystem.ForUI.dll ValDroidBridge.dll; do
    [ -f "$SCRIPTS/$dll" ] || { echo "missing $SCRIPTS/$dll" >&2; exit 1; }
    cp "$SCRIPTS/$dll" "$MANAGED/"
done
echo "prepared $OUT ($(ls "$MANAGED" | wc -l) managed overrides)"

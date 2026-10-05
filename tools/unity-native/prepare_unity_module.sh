#!/bin/sh
# Fill unity/prepared/ (git-ignored) with the Unity files the native player module needs.
# Nothing of Unity is committed to this repository: everything comes from Unity's own Android Support
# package, the rest is generated or built from source here. Run once per machine / Unity version; CI
# runs it too (.github/workflows/build.yml). The module is built only with nativeEngine=true
# (settings.gradle.kts).
#
# usage: prepare_unity_module.sh <android-support.pkg>
#   <android-support.pkg>   UnitySetup-Android-Support-for-Editor-6000.0.75f1.pkg (macOS package,
#                           https://download.unity3d.com/download_unity/26349cd2a5c8/MacEditorTargetInstaller/)
# environment: CSC and MONO_API for building the bridge (see build_bridge.sh); PYTHON, TAR optional.
#
# Result:
#   unity/prepared/jniLibs/arm64-v8a/libunity.so   full (unstripped) engine, build-target check patched
#   unity/prepared/jniLibs/arm64-v8a/libmain.so
#   unity/prepared/libs/unity-classes.jar
#   unity/prepared/swappy/                         Unity's Swappy wrapper source (frame pacing), built
#                                                  by the module's CMake
#   unity/prepared/assets/bin/Data/                boot.config and unity_app_guid (generated),
#                                                  Resources/unity default resources, and an empty
#                                                  Managed/Resources/mscorlib.dll-resources.dat: the
#                                                  player only needs it to exist (IL2CPP's data;
#                                                  il2mono runs Mono)
#   unity/prepared/assets/valdroid_native/Managed/  Android builds that replace the game's Linux copies:
#                                                  UnityEngine*.dll (Unity's Android Mono variation) and
#                                                  ValDroidBridge.dll (built from tools/unity-native/bridge).
#                                                  The game keeps its own Unity.InputSystem.
set -e
USAGE="usage: prepare_unity_module.sh <android-support.pkg>"
PKG=${1:?$USAGE}
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
    "./$VARIATION/Classes/classes.jar" "./Variations/mono/Managed/*" "./Data/Resources/unity default resources" \
    "./Source/FramePacing/*")

rm -rf "$OUT"
mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/libs" "$OUT/swappy"
"$PY" "$HERE/patch_libunity_target.py" "$WORK/$VARIATION/Libs/arm64-v8a/libunity.so" "$OUT/jniLibs/arm64-v8a/libunity.so"
cp "$WORK/$VARIATION/Libs/arm64-v8a/libmain.so" "$OUT/jniLibs/arm64-v8a/"
cp "$WORK/$VARIATION/Classes/classes.jar" "$OUT/libs/unity-classes.jar"
cp "$WORK/Source/FramePacing/UnitySwappyWrapper.cpp" "$WORK/Source/FramePacing/UnitySwappyWrapper.h" "$OUT/swappy/"

# Only the player's own files go into the APK: the game data comes from the archive the launcher
# builds from the instance (NativeEngine), and a game file left here would shadow it.
DATA="$OUT/assets/bin/Data"
mkdir -p "$DATA/Resources" "$DATA/Managed/Resources"
cp "$WORK/Data/Resources/unity default resources" "$DATA/Resources/"
# What a Unity 6000.0 Android player build writes (Vulkan, multithreaded rendering, full screen).
# The ids are ours: the player reads unity_app_guid during start-up and does not start without it.
cat > "$DATA/boot.config" <<'EOF'
gfx-threading-mode=4
wait-for-native-debugger=0
hdr-display-enabled=0
gc-max-time-slice=3
androidStartInFullscreen=1
androidRenderOutsideSafeArea=1
build-guid=6f3c2d9a8b1e4c57a0d4e9b2c7f15a38
EOF
printf '%s' "0b7e5a1c-3d92-4f6e-8a41-c5d2e9f07b63" > "$DATA/unity_app_guid"
# The player extracts Managed/Resources on first start and reports a missing source as "Not enough
# storage space to install required resources"; the content is IL2CPP's and unused by il2mono.
: > "$DATA/Managed/Resources/mscorlib.dll-resources.dat"

# Managed assemblies that replace the game's Linux copies on the Android player (il2mono searches this
# dir before the game's Managed folder).
MANAGED="$OUT/assets/valdroid_native/Managed"
mkdir -p "$MANAGED"
cp "$WORK/Variations/mono/Managed/"UnityEngine*.dll "$MANAGED/"
sh "$HERE/build_bridge.sh" "$WORK/Variations/mono/Managed" "$MANAGED"
echo "prepared $OUT ($(ls "$MANAGED" | wc -l) managed overrides)"

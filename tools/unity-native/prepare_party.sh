#!/bin/sh
# PlayFab Party for the native engine's crossplay: the network library Valheim's PlayFabParty.dll calls
# ("libParty.so"; Valheim ships the Linux build, the Android ARM64 build of the same version exports
# every function it uses). Taken from Microsoft's own release (MIT, github.com/PlayFab/PlayFabParty),
# pinned by version and SHA-256; nothing of it is committed to this repository.
#
# usage: prepare_party.sh [PlayFabParty.Android.Release.zip]
#   Without the argument the zip is downloaded. prepare_unity_module.sh runs this at its end.
#
# Result (git-ignored, picked up by unity/build.gradle):
#   unity/prepared/jniLibs/arm64-v8a/libparty.so   the library (AGP strips its symbols when packaging)
#   unity/prepared/libs/playfab-party.jar          its Java side (HTTP through okhttp, which the APK
#                                                  already has from JavaSteam; audio-device helpers).
#                                                  NativeUnityActivity loads both when crossplay is on.
# The AAR's manifest (its audio service, init provider, microphone/Bluetooth permissions) is left out
# on purpose: Valheim only sends data over Party.
set -e
VERSION=1.10.17   # Valheim's own PlayFabParty.dll is built against 1.10.17
URL="https://github.com/PlayFab/PlayFabParty/releases/download/v$VERSION/PlayFabParty.Android.Release.zip"
SHA256=ac29b4d9cca67c2d31c09437ec7e89cc27e7f78cba798afe97b372c368d7cc68
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$ROOT/unity/prepared
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ZIP=${1:-}
if [ -z "$ZIP" ]; then
    ZIP="$WORK/party.zip"
    curl -fL --retry 3 -o "$ZIP" "$URL"
fi
echo "$SHA256  $ZIP" | sha256sum -c -

unzip -q -o "$ZIP" libparty-full-release.aar -d "$WORK"
unzip -q -o "$WORK/libparty-full-release.aar" jni/arm64-v8a/libparty.so classes.jar -d "$WORK/aar"
mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/libs"
cp "$WORK/aar/jni/arm64-v8a/libparty.so" "$OUT/jniLibs/arm64-v8a/libparty.so"
cp "$WORK/aar/classes.jar" "$OUT/libs/playfab-party.jar"
echo "prepared PlayFab Party $VERSION in $OUT"

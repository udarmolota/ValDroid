#!/bin/sh
# Build ValDroidBridge.dll (tools/unity-native/bridge) without the Unity Editor.
#
# The bridge only needs Unity.InputSystem to compile against; at run time it binds to the game's own
# copy (Valheim ships Input System 1.19.0). So a reference build of the same package version is made
# from its public sources (Unity's package registry), with the UI module left out: that part needs
# uGUI, which only comes with the Editor. Neither that build nor anything else from Unity is kept.
#
# usage: build_bridge.sh <unity-managed-dir> <out-dir>
#   <unity-managed-dir>  UnityEngine*.dll of the player (Variations/mono/Managed of the Android
#                        Support package, as extracted by prepare_unity_module.sh)
#   <out-dir>            receives ValDroidBridge.dll
# environment:
#   CSC        C# compiler command: "csc" (Mono, Linux CI) or "<dotnet> <csc.dll>" (the Roslyn that
#              ships with the Unity Editor: Editor/Data/NetCoreRuntime/dotnet + DotNetSdkRoslyn/csc.dll)
#   MONO_API   Mono's framework assemblies to compile against: tools/unity-native/monoref/4.5 (the
#              4.5 profile of Unity's Mono, MIT, as in the Editor's Data/MonoBleedingEdge/lib/mono/4.5)
#   INPUTSYSTEM_TGZ  optional local copy of com.unity.inputsystem-1.19.0.tgz (else downloaded)
set -e
USAGE="usage: build_bridge.sh <unity-managed-dir> <out-dir>"
UNITY=${1:?$USAGE}
OUT=${2:?$USAGE}
: "${CSC:?set CSC to the C# compiler command}"
: "${MONO_API:?set MONO_API to Mono's framework assemblies (tools/unity-native/monoref/4.5)}"
HERE=$(cd "$(dirname "$0")" && pwd)
INPUTSYSTEM_VERSION=1.19.0
INPUTSYSTEM_URL=https://download.packages.unity.com/com.unity.inputsystem/-/com.unity.inputsystem-$INPUTSYSTEM_VERSION.tgz
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

TGZ=${INPUTSYSTEM_TGZ:-$WORK/inputsystem.tgz}
[ -f "$TGZ" ] || curl -fsSL --retry 3 -o "$TGZ" "$INPUTSYSTEM_URL"
mkdir -p "$WORK/inputsystem"
gzip -dc "$TGZ" | tar -xf - -C "$WORK/inputsystem"
SRC="$WORK/inputsystem/package/InputSystem"

# Reference assemblies: Mono's framework profile (what the player's Mono runs) and the engine modules.
REFS="$WORK/refs.rsp"
: > "$REFS"
for f in "$MONO_API/mscorlib.dll" "$MONO_API/System.dll" "$MONO_API/System.Core.dll" "$MONO_API"/Facades/*.dll \
         "$UNITY"/UnityEngine*.dll; do
    printf '%s\n' "-r:\"$f\"" >> "$REFS"
done

COMMON="-nologo -noconfig -nostdlib+ -target:library -langversion:9.0 -unsafe+ -optimize+ -deterministic -warn:0"

# Unity.InputSystem: every source of the package's runtime assembly (its folder minus the InputForUI
# plugin, which is an assembly of its own). Defines as the Editor sets them for a player build, minus
# the UI module and the platform.
FILES="$WORK/inputsystem-files.rsp"
find "$SRC" -name '*.cs' ! -path '*/Plugins/InputForUI/*' | sed 's/.*/"&"/' > "$FILES"
DEFINES=$(grep -v '^#' "$HERE/bridge/inputsystem-defines.txt" | grep -v '^$' | sed 's/^/-define:/' | tr '\n' ' ')
# shellcheck disable=SC2086
$CSC $COMMON $DEFINES -out:"$WORK/Unity.InputSystem.dll" @"$REFS" @"$FILES"

mkdir -p "$OUT"
# shellcheck disable=SC2086
$CSC $COMMON -r:"$WORK/Unity.InputSystem.dll" -out:"$OUT/ValDroidBridge.dll" @"$REFS" "$HERE"/bridge/*.cs
echo "built $OUT/ValDroidBridge.dll"

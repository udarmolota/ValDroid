# ValDroid research log

This file records decisions that are backed by static inspection. It is not a
claim that the Android build has run successfully.

## Current milestone

Prove that the unmodified Linux x86_64 Valheim runtime can reach a playable
single-player world through Box64. Native ARM64 Mono, mods, Steam multiplayer,
and PlayFab crossplay are later milestones.

## Small-task queue

1. Materialize both pinned `rimdroid-box64` submodules in the ValDroid working
   tree. The initial repository import used `git archive`, which preserves the
   gitlinks but not submodule contents.
2. Finish the PlayFab Party managed call graph and determine whether
   `libparty.so` is loaded at process startup or only when crossplay is used.
3. Add a neutral Vulkan compatibility profile before the first Valheim run.
4. Replace the inherited RimWorld executable and data-layout assumptions in
   the import and launch path, one boundary at a time.
5. Add a static launch-contract check. Do not build or install an APK until
   that check is clean and build permission is explicit.

## PlayFab Party: evidence so far

The shipped `PlayFabParty.dll` is a managed wrapper whose native import name is
`libParty.so`. The game archive contains `libparty.so`; this case difference is
a Linux risk and must be tested rather than assumed harmless.

Microsoft documents the Unity Party plugin as a C# wrapper over a native C++
Party library. The native API requires an explicit `PartyManager::Initialize`
before normal Party operations. That supports treating Party as a separable
crossplay dependency, but does not by itself prove that Valheim defers loading
it: the game's managed call sites and Unity's plugin preload metadata still
decide the actual startup behavior.

Unity 6 can preload a native plugin at startup or load it on demand. The
`libParty.so` name is not one of Unity's documented default preload prefixes,
so an early load would have to come from build metadata or a managed P/Invoke.

Valheim does initialize Party early when PlayFab auto-login is enabled:
`FejdStartup.AwakePlayFab()` creates `PlayFabManager`; its `Start()` login path
creates `PlayFabMultiplayerManager`, whose `Start()` reaches
`PartyCSharpSDK.SDK.PartyInitialize()` and the `DllImport("libParty.so")` call.
There is no local catch around that call. However, Valheim's log handler
explicitly recognizes the resulting Party `DllNotFoundException`, describes it
as preventing crossplay, and queues a dismissible warning. Closed single-player
does not open a PlayFab socket or wait for PlayFab login. Static conclusion:
missing Party disables crossplay and produces an early warning, but should not
block a closed single-player world. Runtime confirmation remains required.

## Vulkan: neutral profile requirements

RimDroid's current `wrappedvulkan.c` is not a neutral compatibility layer. It
contains RimWorld-specific diagnostics and behavior changes. The following
features must default to OFF for Valheim:

- descriptor-bind skipping;
- a synthetic/fake memory heap size or budget;
- hiding `VK_EXT_memory_budget`;
- forcing an identity surface transform;
- RimWorld/Verse Mono probes and hard-coded UnityPlayer BSS scans;
- synthetic presentation calls.

Suggested explicit experiment flags:

- `VALDROID_VK_DIAG_SKIP_DESCRIPTOR_BINDS=0`
- `VALDROID_VK_FAKE_MEMORY_8GB=0`
- `VALDROID_VK_HIDE_MEMORY_BUDGET=0`
- `VALDROID_VK_FORCE_IDENTITY_TRANSFORM=0`
- `VALDROID_VK_TRACE_LEVEL=0`

The Xlib/Xcb-to-Android WSI translation remains required and should be kept as
one atomic compatibility feature. Presentation-support emulation must require
a non-null Android native window, and extension enumeration must avoid adding
duplicate Xlib/Xcb entries.

The Vulkan specification requires each reported `heapBudget` to be no greater
than its corresponding heap size. A neutral profile must therefore preserve
the driver's memory properties and budget values unchanged.

## Implemented source boundary

`GameDescriptor.VALHEIM` now owns the executable name, Unity data directory,
Unity persistent-data path, Steam app id, and minimum required-file list.
`GameInstance` uses that descriptor for user-data paths, Box64 library paths,
installation detection, and completeness validation.

The importer and native launcher still contain RimWorld-specific assumptions.
They are deliberately left for separate, reviewable tasks.

## Launch contract implemented

- Java passes `valheim.x86_64` to native code through `VALDROID_EXECUTABLE`.
- Native `argv[0]` is `<instance>/valheim.x86_64`.
- Mono and plugin search paths are derived from `valheim_Data`.
- Valheim starts the in-process X server without requiring a RimWorld marker.
- The inherited `-force-gfx-direct`, `-force-vulkan-onscreen-swapchain`, and
  `-popupwindow` defaults are not applied to Valheim.
- The RimWorld SDL jump-table remap and Mono GC heap tuning are disabled for
  Valheim.
- ZIP import validates and re-roots around `valheim.x86_64`; RimWorld GOG and
  post-install binary patching are bypassed.

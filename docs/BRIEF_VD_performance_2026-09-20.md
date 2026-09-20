# ValDroid brief — performance, state as of 2026-09-20

For Codex. Device under test: Galaxy S25 (Snapdragon 8 Elite, Adreno 830), Turnip v25, Valheim
(Unity 6000.0.75f1, Linux x86_64) under box64, managed code on **native ARM64 Mono**, direct Vulkan
route (`-force-vulkan`, X11 WSI mapped to the Android surface in `wrappedvulkan.c`).

Local `main` is 12 commits ahead of `origin/main` (pushed head: `6e127e2`). Nothing below is pushed.

## What changed since the native-Mono bring-up

| Commit | What | Result |
|---|---|---|
| `11cf926` | Stripped the RimWorld-era telemetry from the Vulkan hot path: 19 per-frame entry points back to plain `GO`, all probes/synthetic present/survival hack removed, SUBOPTIMAL flag cached, X11 poll trace opt-in | +2–4 FPS |
| `3876493` | box64 `-O2` for runtime and dynarec, only `dynarec_arm64_00.c` at `-O0` | no measurable change; stable |
| `fed2e6a` | **Dynarec knobs at box64 defaults** (was RimDroid's `STRONGMEM=4 BIGBLOCK=0 FASTNAN=0 FASTROUND=0`), and the release-build clamp that re-pinned them after the Extra env field is gone | **the big one: 27–34 → 45–70 FPS** on "Very low". 20 min of play (combat, building) without a crash |
| `bdf22db`, `5851c2a` | Texture shrink on the Vulkan route (`RIMDROID_TEX_SHRINK` / `RIMDROID_TEX_DEEP_MIN`, the launcher's Low / Ultra low tiers): `vkCreateImage` creates sampled mipped 2D upload targets ≥1024 one or two mips smaller; mip indices are shifted in buffer→image copies, views, barriers (1 and 2), blits, copies, clears | Verified in `box64.log`: ≥49 textures shrunk, ~290 MB (RGBA8-equivalent) not allocated. **No FPS change** on this phone; it is a memory lever for 6–8 GB devices |
| `7f3f956` | `IsSteamRunningOnSteamDeck()` = true in the ARM64 Steam stub (unless `SteamDeck=0`), `steam_deck=1` in the Goldberg ini. Valheim keys its Deck platform on the Steamworks call, not on the env var | UI/preset only, no FPS effect expected. The gbe_fork key name was written from memory — please verify |
| `5f88d03` | Launcher passes `-screen-width/-screen-height` = X screen size; `WindowManager.configureWindow` denies (no notify) a resize of a screen-sized window | Fixes (a) black bars from a saved 16:9 mode, (b) **SIGSEGV inside Turnip** on swapchain recreate when the game (or a changed launcher mode + stale saved resolution) resized the window. Side effect: the in-game resolution menu shows "0x0" because our size is not in Unity's list |
| `ab19daf`, `4c0be40`, `1ee98a7` | Render-scale presets down to 960x540; Valheim palette; logo pieces | cosmetic |

Note for anyone reproducing numbers: the APK on the phone right now is a **debug** build (box64 `-O0`
everywhere), installed to get `run-as` log access. Release numbers are higher. Release and debug share
the debug signing key, so they install over each other.

## Where we are stuck

**FPS is CPU-bound in the emulated engine and swings with the phone's skin temperature.**

Measurements, standing still, "Low" preset, debug build (~23 FPS):

- `MainValheimThread` 70–78 %, `UnityGfxDeviceWorker` 22–26 %, 8 job workers 4–22 % each, two
  `valheim.x86_64` threads 7–22 %. Nothing at 100 %.
- GPU 62–71 % busy — that is just the Adreno governor's target, i.e. the GPU has headroom.
- Clock ceilings are **dynamic**: CPU `scaling_max_freq` 2.23/2.25 GHz of 3.53/4.47, GPU
  `max_gpuclk` 525–607 MHz of 1200. While the game was loading (GPU idle) they rose to 2.75/2.65 GHz
  and 734 MHz, then dropped again in-world.
- Cause: Samsung thermal policy on the **SKIN** sensor. `dumpsys thermalservice`: SKIN 40.0–40.3 °C,
  `mStatus=1`; thresholds `[38, 40, 42, 45, 47, 60, 90]`. Battery 34–39 °C, AP 47–52 °C, global
  Thermal Status 0. Performance profile is Standard, `low_power=0`. `restricted_device_performance`
  reads `1, 1` (meaning unknown). Game mode for `com.valdroid`: `standard` (performance is available;
  manifest already has `appCategory="game"` + `game_mode_config` with `supportsPerformanceGameMode`).
- Consequence: "Very low" gave 70 FPS yesterday on a cool phone, today 56 near water and 35 in open
  terrain. "Low" gives ~25 regardless. Very low → Low is a 3x drop, which does not look like fill
  rate: Low adds passes and objects (shadows = a second scene traversal, LOD/draw distance,
  vegetation, point lights), and every draw call costs emulated `UnityPlayer` code plus a box64
  bridge crossing.

Native Mono, `-O2` and the texture shrink each changed FPS by ~0. Only the dynarec knobs did. So the
critical path is dynarec-translated `UnityPlayer.so` code on the main thread.

## Ideas not tried yet (ranked by expected size)

1. **Fewer/cheaper draw calls from the emulated engine.** Unity args to test: `-job-worker-count 3..4`
   (8 workers spinning on emulated code mostly make heat), `-force-gfx-jobs native` vs the default
   threaded mode, `-force-gfx-direct` as a control. A launcher-written graphics preset that turns on
   GPU-only effects (AA, bloom, SSAO, sun shafts, soft particles) and keeps the draw-call multipliers
   off (shadows, distant shadows, point-light shadows, LOD bias, clutter). PlatformPrefs keys seen in
   `assembly_valheim.dll`: `GraphicsQualityMode, ShadowQuality, DistantShadows, PointLights,
   PointLightShadows, Lights, LodBias, ClutterQuality, Tesselation, SSAO_2, Bloom, SunShafts,
   AntiAliasing, MotionBlur, DOF, ChromaticAberration, Target3DResolutionVertical,
   UpscalingAlgorithm, ClothQuality, SimulationDistance`. Preset values live in game assets, not in
   the DLL — not extracted yet.
2. **Burst.** Under native Mono we pass `--burst-disable-compilation` because
   `lib_burst_generated.so` is x86_64 and ARM64 JIT code cannot call it. Valheim does use Burst jobs;
   they now run as plain managed code. Options: bridge Burst entry points back into box64 (call x86
   from native), or check which jobs are hot. Unknown cost/benefit — needs a profile.
3. **A real profile of the main thread.** `simpleperf` on the debug build to split the main thread
   between dynarec blocks, box64 helpers (which ones), Turnip, libmono. Nobody has looked yet; all
   conclusions above are from `top -H` and sysfs.
4. **Heat → clocks.** ADPF `PerformanceHintManager` session on the main/gfx tids, asking the user to
   set Game Booster "performance priority", an FPS cap default (steady 40 beats 70→25 waves),
   fewer busy-waiting threads.
5. box64 dynarec options beyond defaults: `BOX64_DYNAREC_CALLRET=1`, `BOX64_DYNAREC_FORWARD`,
   `BOX64_DYNAREC_NATIVEFLAGS`, `BOX64_DYNAREC_DIRTY` (rejected on RimWorld because of Mono JIT SMC —
   no emulated JIT any more, worth retesting).

## Open bugs / loose ends

- In-game resolution menu shows "0x0" (our size is not among Unity's modes). Cosmetic.
- Audio crackles under load (intro); fine in menus. Not investigated.
- On-screen controls are keyboard/mouse only. Approved, not started: port Zomdroid's on-screen
  gamepad layout (analog sticks, d-pad, A/B/X/Y, LB/RB, LT/RT, Back/Start, L3/R3) onto the existing
  virtual Xbox 360 evdev pad (`VirtualGamepad` / `valdroid_pad.c`), make it the default, keep the
  current layout as the VKBD alternative, allow both at once. Needs: gamepad `Binding` kinds, an
  analog stick and a d-pad element, an `inputType` field in the JSON, a layouts switch in the editor,
  and hiding only the gamepad elements when a physical pad is connected.
- Release builds are not debuggable and the documents provider refuses adb, so release logs are only
  reachable through the launcher's log export.
- `rebuild_wrappers.py` rewrites files under `box64/src/wrapped/generated/` and `src/emu/x64printer.c`
  on every local run; those diffs are deliberately left uncommitted.

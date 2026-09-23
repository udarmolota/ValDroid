# ValDroid TODO

Things that are known and wanted, but not needed for the first testers. Important work goes
first; this list is for later.

## Performance experiments (MobileGlues path)

- **FSR1 — re-check later.** Without `RIMDROID_GLT_EGLTRACK=1` the picture freezes on
  the first frame: MobileGlues upscales inside its own present (`presentSurface`) and we present
  ourselves. With EGLTRACK it runs (S25, `FSR=2`), but the RCAS sharpening is far too strong for
  Valheim and there is no fps gain — we are CPU-bound on one thread (the overlay showed the
  busiest thread at 97-98%), and FSR only relieves the GPU. Still to try: `FSR=1` (less
  upscaling, possibly milder sharpening), and in water, where the GPU is the limit.
- **Frame generation** — the one technique that sidesteps a CPU-bound main thread (it adds shown
  frames without running the game loop), wanted by the user. Options, see the 2026-09-21 notes:
  own image-based interpolation on our GL present path (we own the swap, the GPU has headroom;
  weeks of work, +1 frame of latency, artifacts on HUD and fast motion); lsfg-vk (Vulkan-only,
  needs the user's own Lossless Scaling files — cannot ship); FSR3-style FG needs motion vectors
  the game does not hand us. Research spike, after the tester wave.
- **`enableNoError`** in the MobileGlues config.json (hard-coded `0` in GameLauncher). Read what
  the setting really does in the shipped MobileGlues 2.0.0 before testing; it hides GL errors, so
  only after those are cleared.
- **Job worker count.** `VALDROID_JOB_WORKERS=N` already becomes `-job-worker-count N`. Compare
  default vs 3 vs 4: frame time and heat after ~10 minutes.
- **Mip clamp default.** `RIMDROID_GLT_NOMIP=0` on Adreno 750: no fps change, no artifacts. Decide
  whether to switch the RimWorld-era clamp off by default (the old red-patch bug may still exist
  on other GPUs; `=tex` brings the clamp back).
- **Native ARM64 Mono doubles fps where the CPU is the limit — keep it on by default.** Measured
  2026-09-23 on the S25, same spawn point: native Mono 120 (vsync-capped), emulated x86 Mono 42-75,
  emulated Mono + BepInEx ~60. BepInEx itself costs little; the emulated runtime is what costs. The
  entry below ("barely changes fps") was measured where fps was capped and is wrong. Consequences:
  mods must run on native Mono (BepInEx + HarmonyX on ARM64 — untested), and telling a player to turn
  native Mono off halves their fps. The emulated path is also broken for saving: box64 cannot link
  `MonoBleedingEdge/x86_64/libMonoPosixHelper.so` (missing glibc symbols: `gethostid`, `getfsent`,
  `setusershell`, `fgetpwent`, …), so `GZipStream` throws `DllNotFoundException` in
  `Minimap.SaveMapData` and the save aborts. Low priority now that the emulated path is not the way
  forward.
- **Save location depends on the native-Mono switch.** Native Mono uses our ARM64 Steam stub (cloud
  off → `worlds_local`); emulated Mono uses gbe_fork, which reports Steam Cloud as available, so new
  worlds go to `.local/share/GSE Saves/892970/remote/worlds/`. Flipping the switch makes a player's
  worlds "disappear". Make both paths use the same folder (e.g. cloud off in gbe_fork, or point its
  remote folder at worlds_local).
- **Native ARM64 Mono barely changes fps** (her test, 2026-09-21: emulated Mono runs the same).
  Consistent with -O2, Burst and native Mono all measuring ~0: the main thread is spent in
  UnityPlayer.so (engine C++ under the dynarec — rendering, culling, physics, animation), not in the
  game's C#. That is why CALLRET helped and Mono did not. To re-verify (same spot, same heat) and to
  weigh: loading time and RAM with each (native Mono may still load faster), and stability. Emulated
  Mono has one real advantage — BepInEx: HarmonyX/MonoMod patch methods with native trampolines
  built for x86 Mono; on native ARM64 Mono those would have to be made for ARM64. If fps is equal,
  mods may be much easier on emulated Mono.
- **box64 dynarec knobs — tested 2026-09-23, closed.** `CALLRET=1` is done and on by default
  (98-117 fps on Very low on the S25). The rest gave nothing: `SAFEFLAGS=0 BIGBLOCK=3 FORWARD=512`
  together, measured in her base on the S25 at 50% render scale, moved neither fps nor CPU —
  2.22 vs 2.21 cores total, main thread 79% in both, GPU ~80% in both. Consistent with the profile
  below: box64's own code is only ~6% of the main thread, the rest is translated engine code, and
  no single function dominates. `WEAKBARRIER=2` is untested and not worth the silent-corruption
  risk. Don't reopen without a new reason.
- **Profile of the main thread (simpleperf + `BOX64_DYNAREC_PERFMAP=1`, 2026-09-22/23).** Main
  thread: 75% translated `UnityPlayer.so`, 11% Mono-JIT'd game C#, 13% native libs (libc, TLS,
  memcpy), ~6% box64 itself. Flat: the hottest engine function is 6.6%, the top 30 about a third.
  Render thread: 43% Adreno driver, 40% translated GL code, 4% MobileGlues. Job workers spend about
  a third of their time spinning in Unity's work-stealing loop (`UnityPlayer.so+0x129fe20`), but
  `VALDROID_JOB_WORKERS=2` only saved ~9% of total CPU at unchanged fps and was not felt. The
  bottleneck is scene-dependent: open Meadows on the S25 is vsync-bound at 120, her base is
  GPU-bound (97% at 60% render scale, 80% at 50%), and dense forest on weaker phones is CPU-bound.
  Burst never ran: only `burst.initialize*` was ever translated, in two sessions with cloth and
  combat, so porting the macOS ARM64 Burst plugin would gain nothing.
- **box64 DynaCache — tested 2026-09-22, no gain.** The cache is written and read back (`Loaded
  DynaCache for …UnityPlayer.so`), but time to the main menu went 32 s → 36 s and world load stayed
  at 21 s, with no felt difference. Default is `BOX64_DYNACACHE=2` (read-only), i.e. off; leave it.
- **Is MobileGlues' GLSL cache reused across launches?** Its file is `cache/glsl_cache.tmp`; if it is
  not picked up on the next start, every session re-translates every shader on first use (the
  stutter when a new effect appears).
- **`BOX64_DYNAREC_ALIGNED_ATOMICS=1`** is forced in DEBUG builds only (a leftover RimWorld
  experiment marked "remove afterwards"). It is the faster mode with a SIGBUS risk on cores
  without LSE2. Decide: remove, or measure what it is worth and keep it where it is safe.

## Features

- **Delta game updates.** Re-downloading a new Steam build fetches the whole ~4 GB again: the
  resume list is per build. Compare each file's SHA-1 from the manifest with what is on disk and
  fetch only what changed.
- **"New game version available"** on the instance card, from the stored Steam build id. Owners
  only — which is the point.
- **Make the "Balanced" frame-rate mode (~40) the default** — once versions are more stable. Not
  now: testers first want to measure their maximum fps and would switch it off (her call,
  2026-09-21). Sustained 40 beats a hot phone sagging from 60 to 35 with stutters.
- **Mods (BepInEx).** Our box64 Mono shim already intercepts `mono_jit_init_version`, the point
  doorstop hooks. A project of its own. Once it exists, a "mobile profile" plugin is the natural
  first one: cut what costs CPU — `QualitySettings.lodBias`, shadow distance and cascades, grass and
  clutter distance/density, `terrain.pixelError` — and keep textures and lighting. Valheim's menu
  exposes none of these, and we are CPU-bound.
- **Multiplayer — ideas for later, none verified.** Today ValDroid is single-player only: native
  Mono uses our offline ARM64 Steam stub, the box64 path uses gbe_fork, and neither gives real Steam
  networking. Candidates: (1) LAN between phones through gbe_fork's own discovery of other emulator
  instances on the same Wi-Fi; (2) crossplay through PlayFab — the game ships `libparty.so` (x86_64,
  so under box64), needs a PlayFab sign-in, a research project of its own; (3) a Valheim dedicated
  server on a PC, joined by IP — depends on which transport the game uses for a direct-IP join.
- **Research: run Valheim on Unity's Android player** (`libunity.so`, Android Build Support for
  6000.0.75f1) instead of the Linux UnityPlayer under box64 — the engine would run natively and the
  main bottleneck would disappear. Likely blocker, check first: Unity ships Mono for Android only on
  32-bit ARMv7 and needs IL2CPP for ARM64, and new cores (8 Gen 3, 8 Elite) have no 32-bit mode at
  all. Also: desktop shader variants in the Linux data, native plugin stubs (Steam, PlayFab), and a
  much greyer legal position than emulating the developer's own Linux build. First step: look for
  a Mono arm64 variation in that version's AndroidPlayer.
- **Extract a zip install straight from the picked file.** NewInstanceFragment first copies the
  whole archive (~4 GB) into the app's cache and only then extracts it: twice the time, and ~4 GB
  of free space on top of the game itself. InstallerService needs random access only for the
  pre-checks (zipContainsEntry) — those could run over a ZipInputStream on the content URI too.
- **Real CPU/GPU temperatures in the performance bar.** GameHub's overlay shows CPU and GPU temps
  (seen on a POCO F5: 55/54 °C); ours shows the battery's. Try the /sys/class/thermal zones where a
  device opens them to apps (common on Xiaomi), keep the battery temperature as the fallback.
- **Multithreaded ETC2 encoder** (Zomdroid has one). Only the first launch would gain; the cache
  covers the rest.

## Bugs to look at

- **Process killed by signal 34 after a normal quit.** S23+ tester, 2026-09-21/22: Player.log ends
  with "Shutting down", audio closed, window unmapped — then ~8 s later ApplicationExitInfo records
  SIGNALED status=34 (three times), where a clean exit shows EXIT_SELF. Something in our shutdown
  path (a real-time signal left without a handler?) terminates the process. Harmless for the player
  today, but anything still writing at exit could be cut off.
- **Native-Mono Steam stub: `BeginFileWriteBatch` returns false.** `app/src/main/cpp/steam_api_stub.c`
  returns 0 from `ISteamRemoteStorage_BeginFileWriteBatch` / `EndFileWriteBatch`, which the game
  reads as "a batch is already started". 1.0.15 only warns ("Batch already started! This can happen
  when Valheim crashes…") and goes on. Older builds (1.0.7, seen 2026-09-22 on a POCO X6 Pro tester)
  treat it as fatal: `Mounting failed` → `InvalidOperationException: Failed to mount!` in
  `FejdStartup.OnNewCharacterDone`, so the character-creation "Done" button does nothing. FIXED
  2026-09-23 in `tools/steam-stub-arm64/gen_steam_stub.py` (both return 1; cloud stays disabled,
  saves stay local) — to verify on the POCO X6 Pro tester's 1.0.7. The box64 path (gbe_fork) is
  not affected.
- **Low profile vs. snow and fog.** "Significant lag in snow and fog" (same tester, Mountains): the
  Low profile keeps soft particles on (SoftPart=1), and every soft-particle pixel reads depth —
  costly overdraw on mobile GPUs, on top of bloom and sun shafts. Consider SoftPart=0 in Low.

- **Black terrain on Mali** (POCO X6 Pro, Mali-G615, MobileGlues, 2026-09-23). Only the ground is
  black; rocks, trees, grass and the player render. No shader error for it in Player.log (only the
  tessellated particles and ShieldDomePass fail). Suspects: our ETC2 transcode on Mali (Valheim's
  terrain textures are a texture array) or MobileGlues' translation of the terrain shader for the
  Mali driver. First test: the tester turns off "Enable ETC2 compression".
- **Right stick seems to keep turning the camera after the finger leaves** (her report, 2026-09-23).
  Our side looks clean: `AnalogStickElement` sends the axes to 0 on UP/POINTER_UP/CANCEL. So it is
  either Valheim's own gamepad smoothing (the game eases the look axis, so the camera coasts after
  the stick centres) or latency on our path (touch → VirtualGamepad → X server → SDL → game), where
  the zero arrives late. To tell them apart: flick the stick, lift the finger without recentring,
  and compare the timestamp of the zero event in the logs with when the camera actually stops.
  Valheim's smoothing could only be changed from a mod.

## Texts and polish

- **Wiki names the Steam menu item wrong.** It says "Загрузка игры (Steam)" / "Game download
  (Steam)"; the menu item is `nav_download_game` — "Загрузки из Steam" / "Steam Downloads".
- **The generic "valheim.x86_64 not found" install error** is still English-only (the Windows-build
  case is localized; this fallback is not).
- **Env-var name check.** Warn in the app about unknown `RIMDROID_* / VALDROID_* / BOX64_*` names,
  with a "did you mean" suggestion — typos silently void a test.
- **ETC2-UNCOMP "~saved" log counter is 4x too low** (the formula lost a factor).
- **Dead code and strings** from RimWorld: `RIMDROID_GLT_THREADED` (nothing reads it), the
  "Install mod / DLC" screen, the RimWorld workshop mod downloader, `renderer_info` ("only Zink
  ZFA"), the drag-pan and texture-tier strings, `show_fps_label` (replaced by the hud_* chooser).
- **Stop writing RimWorld's `Config/Prefs.xml`** into Valheim's user-data dir: the launcher still
  does it on every launch (157 bytes, `unity3d/IronGate/Valheim/Config/Prefs.xml`); Valheim never
  reads it.
- **Wiki, saves section:** say that saves from a PC import directly — zip `worlds_local` and
  `characters_local` from `%USERPROFILE%/AppData/LocalLow/IronGate/Valheim/` (the PC's `worlds` /
  `characters` cloud folders work too). Wiki, mods section: the "Install mod / DLC" screen it
  mentions is now hidden from the menu.
- **Tester post and wiki wording** — explanations can be tightened once testers' questions show
  what is actually unclear.

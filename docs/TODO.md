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
- **box64 dynarec knobs** not at their fastest by default: `BOX64_DYNAREC_CALLRET=1` first, then
  `SAFEFLAGS=0`, `BIGBLOCK=3 FORWARD=512`, `WEAKBARRIER=2`. The last two can corrupt silently —
  test on a throwaway world.
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
  doorstop hooks. A project of its own.
- **Extract a zip install straight from the picked file.** NewInstanceFragment first copies the
  whole archive (~4 GB) into the app's cache and only then extracts it: twice the time, and ~4 GB
  of free space on top of the game itself. InstallerService needs random access only for the
  pre-checks (zipContainsEntry) — those could run over a ZipInputStream on the content URI too.
- **Multithreaded ETC2 encoder** (Zomdroid has one). Only the first launch would gain; the cache
  covers the rest.

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

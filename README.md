# ValDroid

**ValDroid** is an unofficial, community-developed launcher that runs
[Valheim](https://www.valheimgame.com) on ARM64 Android phones and tablets, with touch controls,
gamepad support and mods. Since 0.1.3 the game runs **natively**: Unity's own Android engine, no
emulation. The box64 engines that ValDroid started with are still there as a fallback.

> [!NOTE]
> ValDroid does not include the game. It runs **your own copy** of Valheim's Linux build. You can
> download it inside the app with your Steam account\*, or add it as a `.zip` you made yourself.
>
> \* Downloading from Steam needs the **Steam mobile app with Steam Guard turned on**. You enter your
> Steam login in ValDroid, it goes straight to Steam's servers, and you **approve the sign-in in the
> Steam mobile app** — the authorisation itself happens there. ValDroid only receives Steam's
> permission to download the game and to use Steam Cloud; the permission is kept in memory for the
> session.

> [!TIP]
> ValDroid works best with **Valheim 1.0.15 or newer**. Older builds may show problems that are
> already fixed on the current version.

> [!WARNING]
> **ValDroid is in beta.** It plays well on the phones we and our testers have, but not every phone
> has been seen yet, and the native engine is still marked experimental in the app. Back up your
> saves before you play (the drawer has **Import / export saves** for that).

## Features

- ✔️ **Native engine**: the Unity Android player runs the game and the game's own C# code runs on an
  ARM64 build of Unity's Mono. Faster loading, higher and steadier FPS and much less battery than
  emulation. New instances start on it.
- ✔️ **Two box64 engines** as a fallback: *box64 + native Mono* (the engine is emulated, the game's code
  is native) and *full emulation*. Switch any time in **Settings → Engine**; saves, settings, controls
  and mods are shared.
- ✔️ **Snapdragon and Mali**: the native engine runs on Adreno and Mali GPUs. The box64 engines render
  through MobileGlues (OpenGL → OpenGL ES) or Vulkan through Turnip.
- ✔️ **On-screen controls with a full editor**: buttons (with styles and your own pictures), sticks,
  d-pad, touchpad, scroll bar and a radial menu. Two ready-made layouts — gamepad (the default) and
  keyboard with touchpad — mouse look with the touchpad or a physical mouse, and camera turning on the
  combat buttons. Physical gamepads and keyboards work too; a keyboard hides the on-screen controls.
- ✔️ **Icon packs**: pictures for all on-screen buttons at once. Two built-in packs (*ValDroid Viking*
  and *Figures by jurises99*) and your own zips.
- ✔️ **Steam inside the app**\*: download the game, and move saves between your phone and PC through
  Steam Cloud
- ✔️ **Mods (experimental)**: BepInEx is built in; install mods from a `.zip` and switch them on and off
- ✔️ **Graphics presets** (Low, Very low and Minimal), written into the game once, before it starts
- ✔️ **Frame-rate modes** (Economy ~30, Balanced ~40, Smooth ~60, or no limit), plus an on-screen
  performance bar with FPS, CPU, RAM, power and temperature
- ✔️ **Pause in the background**: leave the app and the game freezes and goes quiet
- ✔️ **Save, settings and control-layout import & export**
- ✔️ Haptics, night mode, and Russian, Spanish and Portuguese translations

## Project status & what to expect

ValDroid is built by **one person** and is in active development. It grew out of
[RimDroid](https://github.com/udarmolota/RimDroid) and [Zomdroid](https://github.com/udarmolota/zomdroid),
and shares their approach.

A few honest notes so expectations land right:

- **Single-player only for now.** Multiplayer, dedicated servers and crossplay are not supported yet;
  multiplayer is the next big thing on the list.
- **Mods are experimental.** BepInEx is built in and many mods work, but not all of them. On the native
  engine, mods that bring their own x86 libraries (`.so` files) cannot load.
- **Valheim is demanding.** On the native engine the GPU is usually the limit: a base full of fires
  and torches is heavy, open meadows are light. On the box64 engines the CPU is the limit instead.
- **Phones get hot.** With no frame limit a phone runs flat out and throttles after a while. A
  frame-rate mode is much kinder to your hands and your battery.
- **The first launch is slow.** Shaders (and on the box64 engines, textures) are prepared once and
  cached, and every launch after that is noticeably faster.

## Device compatibility

- **Adreno (Snapdragon):** the most mature path. Recent flagships play smoothly on the native engine
  on the Low preset.
- **Mali:** the native engine runs on Mali too (tested on the G57, G615, G720 and G925). The
  Mali-G615 (Dimensity 8300) needs a workaround that the launcher applies by itself; if on another
  phone the picture freezes on the logo or in the menu while the game goes on, set
  **Vulkan compatibility** (card Engine) to **On**. Weaker Mali GPUs start on the Minimal preset.
- **Other GPUs** (for example Xclipse in recent Exynos chips) are untested.

## System requirements

- **Minimum:** Android 11+, ARM64, 8 GB RAM, about 6 GB of free storage (the game is ~4 GB, plus
  space to unpack it), and a copy of Valheim you own.
- **Recommended:** a recent flagship-class chip and 12 GB+ RAM. The closer to a current flagship,
  the better the experience.

## Getting the best performance

- **Use the native engine** (Settings → Engine). It is the default for new instances; an instance
  from an earlier version keeps what it ran with until you switch it.
- **Turn on your phone's game booster at its highest-performance profile**: Samsung *Game Booster*,
  Xiaomi/POCO *Game Turbo*, Realme/OPPO/OnePlus *Game Space*, vivo/iQOO *Ultra Game Mode*.
  ValDroid registers itself as a game, so these tools should pick it up automatically.
- **Pick a graphics preset.** The most FPS: **Minimal** on the native engine, **Very low** on the box64
  engines. Lower the render resolution if the GPU struggles.
- **Pick a frame-rate mode** instead of no limit for long sessions. You'll get steadier FPS and less heat.
- **Close background apps** so the game gets the RAM and CPU to itself.

## How it works

Valheim officially ships for x86_64 only. ValDroid runs it on ARM64 in one of two ways:

- **Native engine:** Unity's own Android player (the same engine version the game was built with)
  loads the game's data, and the game's C# code runs on a native ARM64 build of Unity's Mono through
  *il2mono*, ValDroid's bridge between the player and the Mono runtime. Nothing is emulated. A small
  managed bridge inside the game drives the on-screen controls and applies the launcher's settings; a
  Vulkan shim between the engine and the driver takes care of the phones that need it.
- **box64 engines:** [box64](https://github.com/ptitSeb/box64) emulates the x86_64 Linux build of the
  Unity engine in-process, the game's C# code runs on the native ARM64 Mono, graphics go to the GPU
  through MobileGlues or Zink, and touch and gamepad input is injected through an X server.

## Build

- Android Studio (its bundled JBR), Android SDK and NDK
- `box64/` is our own fork of box64, built into the app from this repository
- The native engine module (`unity/`) is built when `NATIVE_ENGINE` is set: the Unity Android player
  is downloaded from Unity's servers at build time (it is not stored in this repository) and
  patched by `tools/unity-native/`; the managed bridge is compiled by `tools/unity-native/build_bridge.sh`
- `libmobileglues.so` ships unmodified from [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues)
  in the bundled libraries

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report issues or request features via
[GitHub Issues](https://github.com/udarmolota/ValDroid/issues), or join us on
[r/ValDr0id](https://www.reddit.com/r/ValDr0id/). The quickest way to report a problem is
**Report a bug** in the in-app menu: pick your email app, add a short description of what happened,
and send. The logs are attached for you.

## Credits & Third-Party Sources

- [Unity](https://unity.com) Android player and the Unity Input System: the native engine's runtime,
  downloaded at build time and used under Unity's licence (see `NOTICE`)
- [Mono (Unity fork)](https://github.com/Unity-Technologies/mono): the native ARM64 runtime for the
  game's code, and its class libraries as build-time references
- [box64](https://github.com/ptitSeb/box64): x86_64 → ARM64 emulation for the box64 engines
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) by [MobileGL-Dev](https://github.com/MobileGL-Dev):
  OpenGL → OpenGL ES renderer
- [Mesa / Zink / Turnip](https://gitlab.freedesktop.org/mesa/mesa): Vulkan rendering and the Adreno
  Vulkan driver
- [Swappy](https://github.com/android/games-sdk) (Android Games SDK frame pacing), through Unity's
  wrapper
- [BepInEx](https://github.com/BepInEx/BepInEx): mod loading
- [gbe_fork](https://github.com/Detanup01/gbe_fork) (LGPL-3.0): offline Steam API for the game
- [JavaSteam](https://github.com/Longi94/JavaSteam) and [Bouncy Castle](https://www.bouncycastle.org/):
  Steam sign-in, downloads and Steam Cloud
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass): Android linker namespace access
- *Figures by jurises99*: an icon pack contributed by jurises99
- [RimDroid](https://github.com/udarmolota/RimDroid) and [Zomdroid](https://github.com/udarmolota/zomdroid):
  the launchers ValDroid grew out of

Valheim is a trademark of Iron Gate AB. ValDroid is not affiliated with or endorsed by Iron Gate or
Coffee Stain.

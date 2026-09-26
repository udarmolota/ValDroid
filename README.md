# ValDroid

**ValDroid** is an unofficial, community-developed launcher that runs the native Linux x86_64 build
of [Valheim](https://www.valheimgame.com) on ARM64 Android devices, with GPU rendering, touch
controls and gamepad support.

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
> ValDroid was tested with **Valheim 1.0.15**, and that is where it works best — especially on Mali
> GPUs. Older builds may show problems that are already fixed on the current version.

> [!WARNING]
> **ValDroid is an early alpha.** It launches and plays on the devices we've tested, but behaviour
> still varies from phone to phone. Back up your saves before you play.

## Features

- ✔️ Runs **Valheim 1.0** (the native Linux x86_64 build, Unity 6) on ARM64 phones and tablets
- ✔️ **Native ARM64 Mono**: the game's own code runs directly on your phone's CPU and only the Unity
  engine is emulated. That roughly doubles FPS wherever the CPU is the limit.
- ✔️ **Two renderers**: MobileGlues (OpenGL → OpenGL ES, the default) and Vulkan through Turnip
- ✔️ **On-screen controls with a full editor**: buttons (with styles and your own pictures), sticks,
  d-pad, touchpad, scroll bar and a radial menu. Two ready-made layouts — gamepad (the default) and
  keyboard with touchpad — and mouse look with the touchpad or a physical mouse. Physical gamepads and
  keyboards work too.
- ✔️ **Steam inside the app**\*: download the game, and move saves between your phone and PC through
  Steam Cloud
- ✔️ **Mods (experimental)**: BepInEx is built in; install mods from a `.zip` and switch them on and off
- ✔️ **Graphics presets** (Low and Very low), tuned for emulation rather than for a PC and written into
  the game before it starts
- ✔️ **Frame-rate modes** (Economy ~30, Balanced ~40, Smooth ~60, or no limit), plus an on-screen
  performance bar with FPS, CPU, RAM, power and temperature
- ✔️ **Texture compression** with a disk cache, so later launches load faster
- ✔️ **Save, settings and control-layout import & export**
- ✔️ Haptics, night mode, and Russian, Spanish and Portuguese translations

## Project status & what to expect

ValDroid is young: a few weeks old, built by **one person**, and in active development. It grew out
of [RimDroid](https://github.com/udarmolota/RimDroid) and [Zomdroid](https://github.com/udarmolota/zomdroid),
and shares their approach.

A few honest notes so expectations land right:

- **Single-player only for now.** Multiplayer, dedicated servers and crossplay are not supported yet.
- **Mods are experimental.** BepInEx is built in and many mods work, but not all of them: very old
  mods may load and do nothing.
- **Valheim is demanding.** Most of the engine runs under emulation, so the CPU is often the limit.
  What slows you down depends on where you are: open meadows are light, dense forest is heavy on the
  CPU, and a base full of fires and torches is heavy on the GPU.
- **Phones get hot.** With no frame limit a phone runs flat out and throttles after a while. The
  Balanced mode (~40 FPS) is much kinder to your hands and your battery.
- **The first launch is slow.** Textures and shaders are prepared once and cached, and every launch
  after that is noticeably faster.

## Device compatibility

- **Adreno (Snapdragon)** has the most mature path. Recent flagships play smoothly on the Low preset.
- **Mali:** support is in development.
- **Other GPUs** (for example Xclipse in recent Exynos chips) are untested.

## System requirements

- **Minimum:** Android 11+, ARM64, 8 GB RAM, about 10 GB of free storage (the game is ~4 GB, plus
  space to unpack it), and a copy of Valheim you own.
- **Recommended:** a recent flagship-class chip and 12 GB+ RAM. The closer to a current flagship,
  the better the experience.

## Getting the best performance

- **Turn on your phone's game booster at its highest-performance profile**: Samsung *Game Booster*,
  Xiaomi/POCO *Game Turbo*, Realme/OPPO/OnePlus *Game Space*, vivo/iQOO *Ultra Game Mode*.
  ValDroid registers itself as a game, so these tools should pick it up automatically.
- **Keep Native ARM64 Mono on** (Settings). Turning it off roughly halves your FPS.
- **Use the Very low preset** on weaker phones, and lower the resolution if the GPU struggles.
- **Pick a frame-rate mode** instead of no limit for long sessions. You'll get steadier FPS and less heat.
- **Close background apps** so the game gets the RAM and CPU to itself.

## How it works

Valheim officially ships for x86_64 only. ValDroid runs the **native Linux build** on ARM64:
[box64](https://github.com/ptitSeb/box64) emulates the x86_64 Unity engine in-process, the game's
C# code runs on a native ARM64 build of Unity's Mono, graphics go to your phone's real GPU, and
Android touch and gamepad input is injected straight into the game.

## Build

- Android Studio (its bundled JBR), Android SDK and NDK
- `box64/` is our own fork of box64, built into the app from this repository
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

- [box64](https://github.com/ptitSeb/box64): x86_64 → ARM64 emulation
- [Mono (Unity fork)](https://github.com/Unity-Technologies/mono): the native ARM64 runtime for the
  game's code
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) by [MobileGL-Dev](https://github.com/MobileGL-Dev):
  OpenGL → OpenGL ES renderer
- [Mesa / Zink / Turnip](https://gitlab.freedesktop.org/mesa/mesa): Vulkan rendering and the Adreno
  Vulkan driver
- [gbe_fork](https://github.com/Detanup01/gbe_fork) (LGPL-3.0): offline Steam API for the emulated
  runtime
- [JavaSteam](https://github.com/Longi94/JavaSteam) and [Bouncy Castle](https://www.bouncycastle.org/):
  Steam sign-in, downloads and Steam Cloud
- [liblinkernsbypass](https://github.com/bylaws/liblinkernsbypass): Android linker namespace access
- [RimDroid](https://github.com/udarmolota/RimDroid) and [Zomdroid](https://github.com/udarmolota/zomdroid):
  the launchers ValDroid grew out of

Valheim is a trademark of Iron Gate AB. ValDroid is not affiliated with or endorsed by Iron Gate or
Coffee Stain.

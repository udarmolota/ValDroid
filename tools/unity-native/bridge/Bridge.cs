using System;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using UnityEngine;

namespace ValDroid
{
    // Entry point of ValDroidBridge, started by the engine through RuntimeInitializeOnLoads.json (an entry
    // the launcher adds for this assembly). Applies the launcher's graphics settings, adds the virtual
    // devices the on-screen controls drive and
    // reports a little game state back to the overlay: rendered frames for the FPS counter, and whether
    // the game holds the mouse (mouse look) or shows its cursor (menus), which the floating sticks use.
    public static class Bridge
    {
        // Must match ValDroidGameState in il2mono/pad.c.
        [StructLayout(LayoutKind.Sequential)]
        struct GameState
        {
            public uint frames;
            public int cursorLocked;
            public int cursorVisible;
        }

        [MethodImpl(MethodImplOptions.InternalCall)]
        static extern IntPtr GetGameStatePointer();

        static IntPtr s_State;

        static int s_MipmapLimit = -1;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void Initialize()
        {
            // A managed stack walk for every line the game logs costs time on a weak phone and fills
            // the log: plain lines and warnings go out bare, errors and exceptions keep their trace.
            Application.SetStackTraceLogType(LogType.Log, StackTraceLogType.None);
            Application.SetStackTraceLogType(LogType.Warning, StackTraceLogType.None);
            ApplyLauncherSettings();
            LowGpu.Initialize();
            NoCinematics.Initialize();
            VirtualPad.Initialize();
            VirtualKeyboardMouse.Initialize();
            try
            {
                s_State = GetGameStatePointer();
            }
            catch (Exception e)
            {
                Debug.Log("VALDROID bridge: game state unavailable (" + e.GetType().Name + ")");
                return;
            }
            if (s_State != IntPtr.Zero)
                Application.onBeforeRender += OnFrame;
        }

        // The launcher's graphics settings, passed in the environment (game.NativeEngine):
        //   VALDROID_TEXTURE_MIPMAP_LIMIT  top mip levels every texture drops (reduced textures);
        //   VALDROID_SCREEN_WIDTH/HEIGHT   the render resolution preset.
        static void ApplyLauncherSettings()
        {
            s_MipmapLimit = EnvInt("VALDROID_TEXTURE_MIPMAP_LIMIT");
            if (s_MipmapLimit >= 0)
            {
                QualitySettings.globalTextureMipmapLimit = s_MipmapLimit;
                // Every quality level carries its own limit: put ours back when the game switches.
                QualitySettings.activeQualityLevelChanged += (previous, current) =>
                    QualitySettings.globalTextureMipmapLimit = s_MipmapLimit;
                Debug.Log("VALDROID bridge: texture mipmap limit " + s_MipmapLimit);
            }
            int width = EnvInt("VALDROID_SCREEN_WIDTH"), height = EnvInt("VALDROID_SCREEN_HEIGHT");
            if (width > 0 && height > 0)
            {
                Screen.SetResolution(width, height, FullScreenMode.FullScreenWindow);
                Debug.Log("VALDROID bridge: resolution " + width + "x" + height);
            }
        }

        static int EnvInt(string name)
        {
            int value;
            return int.TryParse(Environment.GetEnvironmentVariable(name), out value) ? value : -1;
        }

        static unsafe void OnFrame()
        {
            var state = (GameState*)s_State;
            state->frames++;
            state->cursorVisible = Cursor.visible ? 1 : 0;
            state->cursorLocked = Cursor.lockState != CursorLockMode.None ? 1 : 0;
            LowGpu.Tick();
            NoCinematics.Tick();
            Diagnostics.Tick();
        }
    }
}

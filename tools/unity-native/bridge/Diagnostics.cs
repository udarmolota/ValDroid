using System;
using System.Text;
using UnityEngine;
using UnityEngine.Video;

namespace ValDroid
{
    // A line of scene state every few seconds, for bug reports from phones we cannot reach: the frame
    // number, the screen size, the cameras that render, the root canvases that are on (with their
    // visible children and group alpha) and every VideoPlayer. On a phone where the picture stays on
    // the loading screen this tells a loading canvas the game never hid from a picture that never
    // reached the screen. Called from Bridge's per-frame hook; cheap except once per period.
    internal static class Diagnostics
    {
        const float PeriodSeconds = 5f;
        const int MaxChildren = 8;
        static float s_Next;

        // Off unless VALDROID_DIAG=1: looking through the whole scene costs a hitch on a weak phone.
        static readonly bool s_On = System.Environment.GetEnvironmentVariable("VALDROID_DIAG") == "1";
        internal static bool On => s_On;

        internal static void Tick()
        {
            if (!s_On)
                return;
            float now = Time.realtimeSinceStartup;
            if (now < s_Next)
                return;
            s_Next = now + PeriodSeconds;
            try
            {
                Log();
            }
            catch (Exception e)
            {
                Write("VALDROID diag failed: " + e.GetType().Name + ": " + e.Message);
            }
        }

        static void Log()
        {
            var sb = new StringBuilder("VALDROID diag: frame ");
            sb.Append(Time.frameCount).Append(", screen ").Append(Screen.width).Append('x').Append(Screen.height);

            sb.Append(" | cameras:");
            foreach (var camera in Camera.allCameras)
            {
                sb.Append(' ').Append(camera.name).Append("(depth ").Append(camera.depth);
                if (camera.targetTexture != null)
                    sb.Append(", to texture");
                sb.Append(')');
            }

            Write(sb.ToString());

            // One line per canvas, so a long scene list is not cut off by the log.
            foreach (var canvas in UnityEngine.Object.FindObjectsByType<Canvas>(FindObjectsSortMode.None))
            {
                if (!canvas.isRootCanvas || !canvas.isActiveAndEnabled)
                    continue;
                sb.Length = 0;
                sb.Append("VALDROID diag canvas: ").Append(canvas.name).Append("(order ").Append(canvas.sortingOrder)
                  .Append(", ").Append(canvas.renderMode);
                var group = canvas.GetComponent<CanvasGroup>();
                if (group != null)
                    sb.Append(", alpha ").Append(group.alpha.ToString("0.##"));
                AppendScaling(sb, canvas);
                int shown = 0;
                var t = canvas.transform;
                for (int i = 0; i < t.childCount; i++)
                {
                    var child = t.GetChild(i);
                    if (!child.gameObject.activeInHierarchy)
                        continue;
                    sb.Append(shown == 0 ? ": " : ", ");
                    if (shown++ == MaxChildren)
                    {
                        sb.Append("...");
                        break;
                    }
                    sb.Append(child.name);
                }
                sb.Append(')');
                Write(sb.ToString());
            }

            LogHudBars();

            sb.Length = 0;
            sb.Append("VALDROID diag video:");
            foreach (var video in UnityEngine.Object.FindObjectsByType<VideoPlayer>(FindObjectsSortMode.None))
            {
                sb.Append(' ').Append(video.name).Append('(').Append(video.isActiveAndEnabled ? "on" : "off")
                  .Append(", ").Append(video.isPlaying ? "playing" : "stopped")
                  .Append(", ").Append(video.renderMode).Append(')');
            }
            Write(sb.ToString());
        }

        // How a canvas is scaled: its final scale factor, the CanvasScaler mode and reference size, and
        // whether the game's GuiScaler (the in-game interface scale slider) drives it. uGUI is not
        // available at build time, so the scaler is read by name and reflection.
        static void AppendScaling(StringBuilder sb, Canvas canvas)
        {
            sb.Append(", scale ").Append(canvas.scaleFactor.ToString("0.###"));
            var scaler = canvas.GetComponent("CanvasScaler");
            if (scaler != null)
            {
                var type = scaler.GetType();
                sb.Append(", scaler ").Append(type.GetProperty("uiScaleMode")?.GetValue(scaler, null));
                var reference = type.GetProperty("referenceResolution")?.GetValue(scaler, null);
                if (reference != null)
                    sb.Append(' ').Append(reference);
            }
            else
                sb.Append(", no scaler");
            sb.Append(canvas.GetComponent("GuiScaler") != null ? ", GuiScaler" : ", no GuiScaler");
        }

        // Where the HUD's health and stamina bars sit: their canvas, its scale, and their own scale and
        // on-screen size, to tell which part of the HUD the interface scale reaches.
        static void LogHudBars()
        {
            var hudType = Type.GetType("Hud, assembly_valheim");
            var instanceField = hudType?.GetField("m_instance", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Static);
            var hud = instanceField?.GetValue(null);
            if (hud == null)
                return;
            foreach (var name in new[] { "m_healthBarRoot", "m_staminaBar2Root", "m_eitrBarRoot" })
            {
                var rect = hudType.GetField(name)?.GetValue(hud) as RectTransform;
                if (rect == null)
                    continue;
                var canvas = rect.GetComponentInParent<Canvas>();
                var root = canvas != null ? canvas.rootCanvas : null;
                var corners = new Vector3[4];
                rect.GetWorldCorners(corners);
                var sb = new StringBuilder("VALDROID diag hud: ").Append(name)
                    .Append(" in ").Append(root != null ? root.name : "no canvas")
                    .Append(" (scale ").Append(root != null ? root.scaleFactor.ToString("0.###") : "-").Append(')')
                    .Append(", own scale ").Append(rect.lossyScale.x.ToString("0.###"))
                    .Append(", on screen ").Append((corners[2].x - corners[0].x).ToString("0")).Append('x')
                    .Append((corners[2].y - corners[0].y).ToString("0")).Append(" px");
                Write(sb.ToString());
            }
        }

        static void Write(string line)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "{0}", line);
        }
    }
}

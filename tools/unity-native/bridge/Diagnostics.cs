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

        static void Write(string line)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "{0}", line);
        }
    }
}

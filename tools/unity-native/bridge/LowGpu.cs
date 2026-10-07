using UnityEngine;

namespace ValDroid
{
    // VALDROID_LOW_GPU=1 (the instance's environment field, a test switch for weak Mali GPUs): engine
    // quality settings below what Valheim's own lowest graphics offer. Realtime shadows off, no per-pixel
    // lights, distant objects simplified sooner. Valheim sets quality settings itself (on start and when
    // its graphics options change), so they are checked again every second and put back when changed.
    internal static class LowGpu
    {
        const float LodBias = 0.5f;
        static bool s_On;
        static float s_Next;
        static int s_Restored;

        internal static void Initialize()
        {
            int value;
            s_On = int.TryParse(System.Environment.GetEnvironmentVariable("VALDROID_LOW_GPU"), out value) && value == 1;
            if (s_On)
                Enforce(true);
        }

        internal static void Tick()
        {
            if (!s_On)
                return;
            float now = Time.realtimeSinceStartup;
            if (now < s_Next)
                return;
            s_Next = now + 1f;
            Enforce(false);
        }

        static void Enforce(bool first)
        {
            bool changed = QualitySettings.shadows != ShadowQuality.Disable || QualitySettings.shadowDistance != 0f
                           || QualitySettings.pixelLightCount != 0 || QualitySettings.lodBias > LodBias;
            if (!changed && !first)
                return;
            QualitySettings.shadows = ShadowQuality.Disable;
            QualitySettings.shadowDistance = 0f;
            QualitySettings.pixelLightCount = 0;
            if (QualitySettings.lodBias > LodBias)
                QualitySettings.lodBias = LodBias;
            // The first time and the first few times the game changes them back; then quietly.
            if (first || s_Restored++ < 5)
                Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null,
                    "VALDROID low GPU: shadows off, pixel lights 0, LOD bias {0}{1}", QualitySettings.lodBias,
                    first ? "" : " (restored after the game changed them)");
        }
    }
}

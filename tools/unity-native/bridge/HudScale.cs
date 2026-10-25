using System;
using System.Collections.Generic;
using System.Reflection;
using UnityEngine;

namespace ValDroid
{
    // The launcher's "In-game interface size" (VALDROID_HUD_SCALE, percent): the screens seen while
    // playing grow beyond the game's own interface scale slider, which stops at 115% (a Retroid player
    // could barely see the stamina bar, 2026-10-23). Menus, inventory and crafting keep the slider's
    // size, so they still fit the screen.
    //
    // Each of Valheim's interface canvases has a GuiScaler that sets its CanvasScaler every frame to
    // min(width / 1920, height / 1080) x the slider (GuiScaler.m_largeGuiScale). For the canvases below
    // that GuiScaler is switched off and the same scale, times ours, is set here instead, so the
    // CanvasScaler applies one value and nothing fights over it. The slider still works: its value is
    // read every time.
    internal static class HudScale
    {
        static readonly string[] s_Canvases =
        {
            "HUD", "EnemyHud", "HudMessage", "TopLeftMessage", "Chat", "TextViewer", "Tutorial", "DamageText",
        };
        const float PeriodSeconds = 0.25f;

        static float s_Factor = 1f;
        static float s_Next;
        static FieldInfo s_SliderScale;
        static PropertyInfo s_ScaleFactor;
        static readonly List<Component> s_Scalers = new List<Component>();
        static float s_NextLookup;

        internal static void Initialize()
        {
            int percent;
            if (!int.TryParse(Environment.GetEnvironmentVariable("VALDROID_HUD_SCALE"), out percent) || percent <= 100)
                return;
            s_Factor = Mathf.Min(percent, 300) / 100f;
            var guiScaler = Type.GetType("GuiScaler, assembly_guiutils");
            s_SliderScale = guiScaler?.GetField("m_largeGuiScale", BindingFlags.NonPublic | BindingFlags.Static);
            if (s_SliderScale == null)
            {
                Debug.Log("VALDROID hud scale: GuiScaler not found, interface size left as is");
                s_Factor = 1f;
                return;
            }
            Debug.Log("VALDROID hud scale: in-game interface at " + percent + "% of the game's own scale");
        }

        // From Bridge's per-frame hook.
        internal static void Tick()
        {
            if (s_Factor == 1f)
                return;
            float now = Time.realtimeSinceStartup;
            if (now < s_Next)
                return;
            s_Next = now + PeriodSeconds;
            try
            {
                if (now >= s_NextLookup)
                {
                    s_NextLookup = now + 2f;   // the canvases come and go with the scenes
                    Lookup();
                }
                float slider = (float)s_SliderScale.GetValue(null);
                float scale = Mathf.Min(Screen.width / 1920f, Screen.height / 1080f) * slider * s_Factor;
                foreach (var scaler in s_Scalers)
                {
                    if (scaler != null)
                        s_ScaleFactor.SetValue(scaler, scale, null);
                }
            }
            catch (Exception e)
            {
                Debug.Log("VALDROID hud scale: failed, interface size left as is (" + e.GetType().Name + ": " + e.Message + ")");
                s_Factor = 1f;
            }
        }

        // The CanvasScalers of the canvases above, their GuiScalers switched off.
        static void Lookup()
        {
            s_Scalers.Clear();
            foreach (var canvas in UnityEngine.Object.FindObjectsByType<Canvas>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                if (!canvas.isRootCanvas || Array.IndexOf(s_Canvases, canvas.name) < 0)
                    continue;
                var scaler = canvas.GetComponent("CanvasScaler");
                if (scaler == null)
                    continue;
                if (s_ScaleFactor == null)
                    s_ScaleFactor = scaler.GetType().GetProperty("scaleFactor");
                var guiScaler = canvas.GetComponent("GuiScaler") as Behaviour;
                if (guiScaler != null && guiScaler.enabled)
                    guiScaler.enabled = false;
                s_Scalers.Add(scaler);
            }
        }
    }
}

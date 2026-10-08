using System;
using System.Collections.Generic;
using System.Reflection;
using System.Text;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;

namespace ValDroid
{
    // Diagnosis of UI clicks the game ignores (VALDROID_DIAG=1 only): after a sleep or a death, mouse
    // clicks on Valheim's menus stop working while hovering still highlights and the game itself still
    // takes the buttons. The launcher's log shows the clicks reaching the virtual mouse; this follows
    // them further: what the UI module and the EventSystem see at each left-button edge, which buttons
    // actually run their click handler, and once a second the game's own input mode (ZInput).
    // uGUI and the Input System's UI module are not available at build time (see build_bridge.sh), so
    // everything from them is reached by reflection and failures are logged, never thrown.
    internal static class UiClickDiag
    {
        const float PeriodSeconds = 1f;
        static float s_Next;
        static bool s_LastPressed;
        static bool s_ClickHooked;
        static readonly HashSet<int> s_HookedButtons = new HashSet<int>();

        static Type s_EventSystem, s_PointerEventData, s_RaycastResult, s_Selectable, s_Button, s_ZInput;
        static MethodInfo s_RaycastAll, s_IsInteractable;
        static readonly List<MethodInfo> s_ZInputFlags = new List<MethodInfo>();

        internal static void Initialize()
        {
            if (!Diagnostics.On)
                return;
            try
            {
                s_EventSystem = Type.GetType("UnityEngine.EventSystems.EventSystem, UnityEngine.UI");
                s_PointerEventData = Type.GetType("UnityEngine.EventSystems.PointerEventData, UnityEngine.UI");
                s_RaycastResult = Type.GetType("UnityEngine.EventSystems.RaycastResult, UnityEngine.UI");
                s_Selectable = Type.GetType("UnityEngine.UI.Selectable, UnityEngine.UI");
                s_Button = Type.GetType("UnityEngine.UI.Button, UnityEngine.UI");
                if (s_EventSystem != null && s_PointerEventData != null && s_RaycastResult != null)
                    s_RaycastAll = s_EventSystem.GetMethod("RaycastAll", new[] { s_PointerEventData, typeof(List<>).MakeGenericType(s_RaycastResult) });
                if (s_Selectable != null)
                    s_IsInteractable = s_Selectable.GetMethod("IsInteractable", Type.EmptyTypes);
                s_ZInput = Type.GetType("ZInput, assembly_valheim");
                if (s_ZInput != null)
                {
                    foreach (var m in s_ZInput.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static))
                    {
                        if (m.GetParameters().Length != 0 || m.ReturnType != typeof(bool))
                            continue;
                        string n = m.Name;
                        if (n.Contains("Gamepad") || n.Contains("Mouse") || n.Contains("Keyboard"))
                            s_ZInputFlags.Add(m);
                        if (s_ZInputFlags.Count >= 8)
                            break;
                    }
                }
                InputSystem.onAfterUpdate += AfterUpdate;
                Write("VALDROID uidiag: on; EventSystem " + (s_EventSystem != null) + ", RaycastAll " + (s_RaycastAll != null)
                      + ", Button " + (s_Button != null) + ", ZInput " + (s_ZInput != null) + " flags " + s_ZInputFlags.Count);
            }
            catch (Exception e)
            {
                Write("VALDROID uidiag: init failed: " + e.GetType().Name + ": " + e.Message);
            }
        }

        // From Bridge's per-frame hook.
        internal static void Tick()
        {
            if (!Diagnostics.On || s_EventSystem == null)
                return;
            float now = Time.realtimeSinceStartup;
            if (now < s_Next)
                return;
            s_Next = now + PeriodSeconds;
            try
            {
                LogState();
                HookButtons();
                HookClickAction();
            }
            catch (Exception e)
            {
                Write("VALDROID uidiag: tick failed: " + e.GetType().Name + ": " + e.Message);
            }
        }

        // ------------------------------------------------------------------ once a second

        static void LogState()
        {
            var sb = new StringBuilder("VALDROID uidiag: frame ").Append(Time.frameCount);
            object es = CurrentEventSystem();
            if (es == null)
                sb.Append(" | no EventSystem");
            else
            {
                object module = Prop(es, "currentInputModule");
                sb.Append(" | module ").Append(module != null ? module.GetType().Name : "none");
                var selected = Prop(es, "currentSelectedGameObject") as GameObject;
                sb.Append(", selected ").Append(selected != null ? Path(selected) : "none");
                try { sb.Append(", overUI ").Append(s_EventSystem.GetMethod("IsPointerOverGameObject", Type.EmptyTypes).Invoke(es, null)); }
                catch (Exception) { }
            }
            sb.Append(" | cursor ").Append(Cursor.lockState).Append(Cursor.visible ? " visible" : " hidden")
              .Append(", timeScale ").Append(Time.timeScale);
            foreach (var m in s_ZInputFlags)
            {
                try { sb.Append(", ").Append(m.Name).Append('=').Append(m.Invoke(null, null)); }
                catch (Exception) { }
            }
            var mouse = Mouse.current;
            var keyboard = Keyboard.current;
            var pad = Gamepad.current;
            sb.Append(" | mouse ").Append(mouse != null ? mouse.name + " @" + mouse.lastUpdateTime.ToString("F2") : "none")
              .Append(", keyboard ").Append(keyboard != null ? keyboard.name + " @" + keyboard.lastUpdateTime.ToString("F2") : "none")
              .Append(", gamepad ").Append(pad != null ? pad.name + " @" + pad.lastUpdateTime.ToString("F2") : "none")
              .Append(", now ").Append(Time.realtimeSinceStartupAsDouble.ToString("F2"));
            if (mouse != null)
                sb.Append(", left ").Append(mouse.leftButton.isPressed ? "down" : "up")
                  .Append(" at ").Append(mouse.position.ReadValue());
            Write(sb.ToString());
        }

        // Every active Button runs a line through the log when its click handler fires: the proof the
        // click reached the end of the chain.
        static void HookButtons()
        {
            if (s_Button == null)
                return;
            foreach (var o in UnityEngine.Object.FindObjectsByType(s_Button, FindObjectsSortMode.None))
            {
                var c = o as Component;
                if (c == null || !s_HookedButtons.Add(c.GetInstanceID()))
                    continue;
                var ev = Prop(c, "onClick") as UnityEngine.Events.UnityEvent;
                if (ev == null)
                    continue;
                string path = Path(c.gameObject);
                ev.AddListener(() => Write("VALDROID uidiag: CLICK HANDLED " + path + ", frame " + Time.frameCount));
            }
        }

        // The Input System UI module's left-click action, if that is the module: is it enabled, what is
        // it bound to, and does it fire.
        static void HookClickAction()
        {
            if (s_ClickHooked)
                return;
            object es = CurrentEventSystem();
            object module = es != null ? Prop(es, "currentInputModule") : null;
            if (module == null)
                return;
            s_ClickHooked = true;
            var reference = Prop(module, "leftClick") as InputActionReference;
            var action = reference != null ? reference.action : null;
            if (action == null)
            {
                var input = Prop(module, "input");
                Write("VALDROID uidiag: module " + module.GetType().FullName + " has no leftClick action; input "
                      + (input != null ? input.GetType().FullName : "none"));
                return;
            }
            var sb = new StringBuilder("VALDROID uidiag: leftClick action ").Append(action.name)
                .Append(action.enabled ? " enabled" : " DISABLED").Append(", map ").Append(action.actionMap != null ? action.actionMap.name : "none")
                .Append(action.actionMap != null && action.actionMap.enabled ? " enabled" : " disabled").Append(", controls:");
            foreach (var control in action.controls)
                sb.Append(' ').Append(control.path);
            Write(sb.ToString());
            action.started += ctx => Write("VALDROID uidiag: leftClick started from " + ctx.control.path + ", frame " + Time.frameCount);
            action.performed += ctx => Write("VALDROID uidiag: leftClick performed from " + ctx.control.path + ", frame " + Time.frameCount);
            action.canceled += ctx => Write("VALDROID uidiag: leftClick canceled, frame " + Time.frameCount);
        }

        // ------------------------------------------------------------------ each left-button edge

        static void AfterUpdate()
        {
            var mouse = Mouse.current;
            if (mouse == null)
                return;
            bool pressed = mouse.leftButton.isPressed;
            if (pressed == s_LastPressed)
                return;
            s_LastPressed = pressed;
            try
            {
                LogEdge(pressed, mouse);
            }
            catch (Exception e)
            {
                Write("VALDROID uidiag: edge failed: " + e.GetType().Name + ": " + e.Message);
            }
        }

        static void LogEdge(bool pressed, Mouse mouse)
        {
            Vector2 pos = mouse.position.ReadValue();
            var sb = new StringBuilder("VALDROID uidiag: left ").Append(pressed ? "DOWN" : "UP").Append(" at ").Append(pos)
                .Append(", frame ").Append(Time.frameCount).Append(", ").Append(InputState.currentUpdateType)
                .Append(", wasPressedThisFrame ").Append(mouse.leftButton.wasPressedThisFrame);
            object es = CurrentEventSystem();
            if (es != null && s_RaycastAll != null)
            {
                var data = Activator.CreateInstance(s_PointerEventData, es);
                s_PointerEventData.GetProperty("position").SetValue(data, pos, null);
                var list = (System.Collections.IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(s_RaycastResult));
                s_RaycastAll.Invoke(es, new[] { data, list });
                sb.Append(" | hits ").Append(list.Count);
                if (list.Count > 0)
                {
                    var top = Prop(list[0], "gameObject") as GameObject;
                    sb.Append(", top ").Append(top != null ? Path(top) : "?");
                    if (top != null)
                    {
                        var selectable = s_Selectable != null ? top.GetComponentInParent(s_Selectable) : null;
                        if (selectable != null)
                        {
                            sb.Append(", selectable ").Append(selectable.GetType().Name).Append(' ').Append(selectable.name);
                            if (s_IsInteractable != null)
                                sb.Append(s_IsInteractable.Invoke(selectable, null) is bool b && b ? " interactable" : " NOT interactable");
                        }
                        else
                            sb.Append(", no selectable above it");
                        foreach (var group in top.GetComponentsInParent<CanvasGroup>(true))
                        {
                            if (!group.interactable || !group.blocksRaycasts)
                                sb.Append(", CanvasGroup ").Append(group.name).Append(group.interactable ? "" : " not interactable")
                                  .Append(group.blocksRaycasts ? "" : " no raycasts");
                        }
                    }
                }
                var selected = Prop(es, "currentSelectedGameObject") as GameObject;
                sb.Append(", selected ").Append(selected != null ? Path(selected) : "none");
            }
            Write(sb.ToString());
        }

        // ------------------------------------------------------------------ helpers

        static object CurrentEventSystem()
        {
            return s_EventSystem != null ? s_EventSystem.GetProperty("current", BindingFlags.Public | BindingFlags.Static).GetValue(null, null) : null;
        }

        static object Prop(object o, string name)
        {
            if (o == null)
                return null;
            var p = o.GetType().GetProperty(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
            if (p != null)
                return p.GetValue(o, null);
            var f = o.GetType().GetField(name, BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
            return f != null ? f.GetValue(o) : null;
        }

        static string Path(GameObject go)
        {
            var sb = new StringBuilder(go.name);
            var t = go.transform.parent;
            int depth = 0;
            while (t != null && depth++ < 4)
            {
                sb.Insert(0, t.name + "/");
                t = t.parent;
            }
            return sb.ToString();
        }

        static void Write(string line)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "{0}", line);
        }
    }
}

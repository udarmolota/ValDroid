using System;
using System.Reflection;
using UnityEngine;

namespace ValDroid
{
    // Typing into Valheim's console (F5, or L1 + L2 + Start on a gamepad) with the on-screen keyboard.
    //
    // A Retroid player opened the console with his gamepad, brought up the keyboard with our KB button
    // and nothing he typed reached it (2026-11, reproduced on the S25 2026-11-21). The console makes its
    // line active once, when it opens (Console.Update -> m_input.ActivateInputField); any tap or button
    // that moves the UI selection afterwards takes that focus away, and Valheim gives it back only on
    // the next open. On a PC the line keeps focus because nothing else is clicked; with a gamepad the
    // game expects Steam Big Picture's own keyboard instead (GuiInputField.VirtualKeyboardAvailable),
    // which a phone does not have.
    //
    // So while the console is open its line is kept active: when it loses focus, it is activated
    // again. Each change is logged once ("VALDROID console: ..."), so a report shows whether the line
    // had focus and whether typed text arrived.
    internal static class ConsoleInput
    {
        const float RetrySeconds = 0.25f;

        static bool s_Ready;
        static MethodInfo s_IsVisible;
        static FieldInfo s_Input;
        static PropertyInfo s_Instance;
        static FieldInfo s_InstanceField;
        static PropertyInfo s_IsFocused;
        static PropertyInfo s_Text;
        static MethodInfo s_Activate;

        static bool s_WasOpen;
        static bool s_WasFocused;
        static int s_LastLength = -1;
        static float s_NextRetry;
        static int s_Refocused;

        internal static void Initialize()
        {
            try
            {
                var console = Type.GetType("Console, assembly_valheim");
                var terminal = Type.GetType("Terminal, assembly_valheim");
                if (console == null || terminal == null)
                {
                    Debug.Log("VALDROID console: Console/Terminal not found, left as is");
                    return;
                }
                s_IsVisible = console.GetMethod("IsVisible", BindingFlags.Public | BindingFlags.Static, null, Type.EmptyTypes, null);
                s_Instance = console.GetProperty("instance", BindingFlags.Public | BindingFlags.Static);
                s_InstanceField = console.GetField("m_instance", BindingFlags.NonPublic | BindingFlags.Static);
                s_Input = terminal.GetField("m_input", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
                s_Ready = s_IsVisible != null && s_Input != null && (s_Instance != null || s_InstanceField != null);
                if (!s_Ready)
                    Debug.Log("VALDROID console: members not found, left as is");
            }
            catch (Exception e)
            {
                Debug.Log("VALDROID console: setup failed (" + e.GetType().Name + ": " + e.Message + ")");
            }
        }

        // From Bridge's per-frame hook: two cheap calls while the console is closed.
        internal static void Tick()
        {
            if (!s_Ready)
                return;
            try
            {
                bool open = (bool)s_IsVisible.Invoke(null, null);
                if (!open)
                {
                    if (s_WasOpen)
                        Debug.Log("VALDROID console: closed (focus given back " + s_Refocused + " times)");
                    s_WasOpen = false;
                    return;
                }
                object console = s_Instance != null ? s_Instance.GetValue(null, null) : s_InstanceField.GetValue(null);
                var input = console != null ? s_Input.GetValue(console) as Component : null;
                if (input == null)
                    return;
                if (s_IsFocused == null)
                {
                    var type = input.GetType();
                    s_IsFocused = type.GetProperty("isFocused", BindingFlags.Public | BindingFlags.Instance);
                    s_Text = type.GetProperty("text", BindingFlags.Public | BindingFlags.Instance);
                    // GuiInputField's own ActivateInputField: it hides TMP_InputField's and, without Big
                    // Picture, calls it.
                    s_Activate = type.GetMethod("ActivateInputField", BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly, null, Type.EmptyTypes, null)
                        ?? type.GetMethod("ActivateInputField", BindingFlags.Public | BindingFlags.Instance, null, Type.EmptyTypes, null);
                    if (s_IsFocused == null || s_Activate == null)
                    {
                        Debug.Log("VALDROID console: input field members not found (" + type.FullName + "), left as is");
                        s_Ready = false;
                        return;
                    }
                }
                bool focused = (bool)s_IsFocused.GetValue(input, null);
                string text = s_Text != null ? s_Text.GetValue(input, null) as string : null;
                int length = text != null ? text.Length : 0;

                if (!s_WasOpen)
                {
                    s_WasOpen = true;
                    s_Refocused = 0;
                    s_WasFocused = focused;
                    s_LastLength = length;
                    Debug.Log("VALDROID console: open, line " + (focused ? "active" : "NOT active") + ", selected " + Selected());
                }
                else if (focused != s_WasFocused)
                {
                    s_WasFocused = focused;
                    Debug.Log("VALDROID console: line " + (focused ? "active again" : "lost focus, selected " + Selected()));
                }
                if (length != s_LastLength)
                {
                    s_LastLength = length;
                    Debug.Log("VALDROID console: line now " + length + " characters");
                }

                if (!focused && Time.realtimeSinceStartup >= s_NextRetry)
                {
                    s_NextRetry = Time.realtimeSinceStartup + RetrySeconds;
                    s_Activate.Invoke(input, null);
                    s_Refocused++;
                }
            }
            catch (Exception e)
            {
                Debug.Log("VALDROID console: failed, left as is (" + e.GetType().Name + ": " + e.Message + ")");
                s_Ready = false;
            }
        }

        static PropertyInfo s_Current;
        static PropertyInfo s_SelectedObject;

        // The UI's selected object, by reflection: the bridge is built without UnityEngine.UI.
        static string Selected()
        {
            try
            {
                if (s_Current == null)
                {
                    var eventSystem = Type.GetType("UnityEngine.EventSystems.EventSystem, UnityEngine.UI");
                    s_Current = eventSystem?.GetProperty("current", BindingFlags.Public | BindingFlags.Static);
                    s_SelectedObject = eventSystem?.GetProperty("currentSelectedGameObject", BindingFlags.Public | BindingFlags.Instance);
                    if (s_Current == null || s_SelectedObject == null)
                        return "?";
                }
                object system = s_Current.GetValue(null, null);
                var go = system != null ? s_SelectedObject.GetValue(system, null) as GameObject : null;
                return go != null ? "'" + go.name + "'" : "nothing";
            }
            catch (Exception)
            {
                return "?";
            }
        }
    }
}

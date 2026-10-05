using System;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;

namespace ValDroid
{
    // A virtual keyboard and mouse the on-screen controls drive. Valheim reads both through the Input
    // System (<Keyboard>/..., <Mouse>/delta, <Mouse>/position, <Mouse>/scroll/y). The native side
    // (il2mono) keeps the keys held (by GLFW code, the controls' own numbering), the mouse buttons, the
    // cursor position and the mouse look and wheel accumulated since the last update; before every
    // Input System update whatever changed is queued to the two devices.
    // Text for input fields does not come this way: the soft keyboard sends Android key events.
    // Started by Bridge.Initialize.
    public static class VirtualKeyboardMouse
    {
        const int KeyWords = 12;

        // Must match ValDroidKbmState in il2mono/pad.c.
        [StructLayout(LayoutKind.Sequential)]
        unsafe struct NativeState
        {
            public uint sequence;
            public fixed uint keys[KeyWords]; // bit = GLFW key code
            public uint mouseButtons;         // bit 0 left, 1 right, 2 middle
            public float mouseX, mouseY;      // fraction of the screen, top-left origin
            public float deltaX, deltaY;      // fraction of the screen, + = right / down
            public float scroll;              // wheel notches, + = up
        }

        // False when nothing changed since lastSequence; otherwise copies the state and clears the
        // native mouse delta and wheel.
        [MethodImpl(MethodImplOptions.InternalCall)]
        static extern unsafe bool Take(NativeState* state, uint lastSequence);

        static Keyboard s_Keyboard;
        static Mouse s_Mouse;
        static uint s_LastSequence = uint.MaxValue;
        static readonly uint[] s_LastKeys = new uint[KeyWords];
        static readonly Key[] s_GlfwToKey = BuildKeyTable();

        internal static unsafe void Initialize()
        {
            try
            {
                NativeState probe;
                Take(&probe, s_LastSequence);
            }
            catch (Exception e)
            {
                Debug.Log("VALDROID keyboard/mouse: native state unavailable (" + e.GetType().Name + ")");
                return;
            }
            s_Keyboard = InputSystem.AddDevice<Keyboard>("ValDroid Virtual Keyboard");
            s_Mouse = InputSystem.AddDevice<Mouse>("ValDroid Virtual Mouse");
            InputSystem.onBeforeUpdate += Push;
            Debug.Log("VALDROID keyboard/mouse: virtual devices added, ids " + s_Keyboard.deviceId + ", " + s_Mouse.deviceId);
        }

        static unsafe void Push()
        {
            if (s_Keyboard == null || !s_Keyboard.added || s_Mouse == null || !s_Mouse.added)
                return;
            NativeState native;
            if (!Take(&native, s_LastSequence))
                return;
            s_LastSequence = native.sequence;

            bool keysChanged = false;
            for (int w = 0; w < KeyWords; w++)
            {
                if (native.keys[w] != s_LastKeys[w])
                {
                    keysChanged = true;
                    s_LastKeys[w] = native.keys[w];
                }
            }
            if (keysChanged)
            {
                var keyboard = new KeyboardState();
                for (int code = 0; code < s_GlfwToKey.Length; code++)
                {
                    if ((native.keys[code >> 5] & (1u << (code & 31))) != 0 && s_GlfwToKey[code] != Key.None)
                        keyboard.Set(s_GlfwToKey[code], true);
                }
                InputSystem.QueueStateEvent(s_Keyboard, keyboard);
            }

            // The mouse event carries everything; the Input System accumulates delta and scroll within
            // an update and zeroes them at the next one.
            float width = Screen.width, height = Screen.height;
            var mouse = new MouseState
            {
                position = new Vector2(native.mouseX * width, (1f - native.mouseY) * height),
                delta = new Vector2(native.deltaX * width, -native.deltaY * height),
                scroll = new Vector2(0f, native.scroll),
            }
            .WithButton(MouseButton.Left, (native.mouseButtons & 1) != 0)
            .WithButton(MouseButton.Right, (native.mouseButtons & 2) != 0)
            .WithButton(MouseButton.Middle, (native.mouseButtons & 4) != 0);
            InputSystem.QueueStateEvent(s_Mouse, mouse);
        }

        // GLFW key code (what the on-screen controls send) -> Input System key.
        static Key[] BuildKeyTable()
        {
            var t = new Key[KeyWords * 32];
            for (int i = 0; i < 26; i++) t[65 + i] = Key.A + i;          // A..Z
            t[48] = Key.Digit0;
            for (int i = 1; i <= 9; i++) t[48 + i] = Key.Digit1 + (i - 1); // 1..9
            for (int i = 0; i < 12; i++) t[290 + i] = Key.F1 + i;         // F1..F12
            for (int i = 0; i < 10; i++) t[320 + i] = Key.Numpad0 + i;    // KP_0..KP_9
            t[32] = Key.Space;
            t[39] = Key.Quote;
            t[44] = Key.Comma;
            t[45] = Key.Minus;
            t[46] = Key.Period;
            t[47] = Key.Slash;
            t[59] = Key.Semicolon;
            t[61] = Key.Equals;
            t[91] = Key.LeftBracket;
            t[92] = Key.Backslash;
            t[93] = Key.RightBracket;
            t[96] = Key.Backquote;
            t[123] = Key.End;           // GLFWBinding.KEYCODE_MOVE_END
            t[256] = Key.Escape;
            t[257] = Key.Enter;
            t[258] = Key.Tab;
            t[259] = Key.Backspace;
            t[260] = Key.Insert;
            t[261] = Key.Delete;
            t[262] = Key.RightArrow;
            t[263] = Key.LeftArrow;
            t[264] = Key.DownArrow;
            t[265] = Key.UpArrow;
            t[266] = Key.PageUp;
            t[267] = Key.PageDown;
            t[268] = Key.Home;
            t[269] = Key.End;
            t[280] = Key.CapsLock;
            t[281] = Key.ScrollLock;
            t[282] = Key.NumLock;
            t[283] = Key.PrintScreen;
            t[284] = Key.Pause;
            t[330] = Key.NumpadPeriod;
            t[331] = Key.NumpadDivide;
            t[332] = Key.NumpadMultiply;
            t[333] = Key.NumpadMinus;
            t[334] = Key.NumpadPlus;
            t[335] = Key.NumpadEnter;
            t[336] = Key.NumpadEquals;
            t[340] = Key.LeftShift;
            t[341] = Key.LeftCtrl;
            t[342] = Key.LeftAlt;
            t[343] = Key.LeftMeta;
            t[344] = Key.RightShift;
            t[345] = Key.RightCtrl;
            t[346] = Key.RightAlt;
            t[347] = Key.RightMeta;
            t[348] = Key.ContextMenu;
            return t;
        }
    }
}

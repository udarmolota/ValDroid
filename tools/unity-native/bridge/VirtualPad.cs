using System;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;

namespace ValDroid
{
    // A virtual gamepad the on-screen controls drive. The native side (il2mono) owns a small state
    // block written from Java over JNI; before every Input System update the current state is queued
    // to a Gamepad device, so the game sees an ordinary controller.
    // Started by the engine through RuntimeInitializeOnLoads.json (an entry added for this assembly).
    public static class VirtualPad
    {
        // Must match struct ValDroidPadState in il2mono/pad.c.
        [StructLayout(LayoutKind.Sequential)]
        struct NativeState
        {
            public uint sequence;
            public uint buttons; // bit = (int)GamepadButton
            public float leftX, leftY, rightX, rightY, leftTrigger, rightTrigger;
        }

        [MethodImpl(MethodImplOptions.InternalCall)]
        static extern IntPtr GetStatePointer();

        static Gamepad s_Pad;
        static IntPtr s_State;
        static uint s_LastSequence = uint.MaxValue;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void Initialize()
        {
            try
            {
                s_State = GetStatePointer();
            }
            catch (Exception e)
            {
                Debug.Log("VALDROID pad: native state unavailable (" + e.GetType().Name + ")");
                return;
            }
            if (s_State == IntPtr.Zero)
                return;
            s_Pad = InputSystem.AddDevice<Gamepad>("ValDroid Virtual Pad");
            InputSystem.onBeforeUpdate += Push;
            Debug.Log("VALDROID pad: virtual gamepad added, id " + s_Pad.deviceId);
        }

        static unsafe void Push()
        {
            if (s_Pad == null || !s_Pad.added)
                return;
            var native = (NativeState*)s_State;
            uint sequence = native->sequence;
            if (sequence == s_LastSequence)
                return;
            s_LastSequence = sequence;
            var state = new GamepadState
            {
                buttons = native->buttons,
                leftStick = new Vector2(native->leftX, native->leftY),
                rightStick = new Vector2(native->rightX, native->rightY),
                leftTrigger = native->leftTrigger,
                rightTrigger = native->rightTrigger,
            };
            InputSystem.QueueStateEvent(s_Pad, state);
        }
    }
}

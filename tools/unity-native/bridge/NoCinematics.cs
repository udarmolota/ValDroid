using System;
using System.Collections;
using System.Reflection;
using UnityEngine;
using UnityEngine.SceneManagement;
using UnityEngine.Video;

namespace ValDroid
{
    // VALDROID_SKIP_VIDEO=1 (the launcher sets it on Mali GPUs): the game's cinematics never start.
    // On a Mali-G615 the GPU was lost (every vkQueueSubmit VK_ERROR_DEVICE_LOST, the picture frozen on the
    // loading logo while the game went on) half a second after the intro started, even with the clip made
    // unreadable so that nothing was decoded (2026-10-07): it is entering the cinematic, not the video, that
    // the driver does not survive. Valheim's CinematicsManager.Play(VideoEntry) turns the main camera and
    // its 3D resolution scaler off, hides the UI and draws the video instead, but first it returns false
    // when the entry has no clip. So the clips are removed from the game's own list of cinematics as soon as
    // the manager exists: every Play then does nothing. A cinematic started before that (from the start
    // scene's Awake) is stopped through the game's own CinematicsManager.Stop() in the same frame, before
    // anything of it is drawn. Reflection, because the bridge is built without the game's assemblies.
    internal static class NoCinematics
    {
        static bool s_On;
        static bool s_Resolved;
        static Type s_Manager;
        static FieldInfo s_Instance, s_Videos, s_Playing;
        static MethodInfo s_Stop;
        static object s_Stripped;   // the manager whose list was emptied

        internal static void Initialize()
        {
            s_On = Environment.GetEnvironmentVariable("VALDROID_SKIP_VIDEO") == "1";
            if (!s_On)
                return;
            SceneManager.sceneLoaded += (scene, mode) => Tick();
            Write("VALDROID no cinematics: the game's cinematics are turned off");
        }

        // Every frame (Bridge.OnFrame) and on every scene load: two field reads when there is nothing to do.
        internal static void Tick()
        {
            if (!s_On || !Resolve())
                return;
            try
            {
                object manager = s_Instance.GetValue(null);
                if (manager != null && !ReferenceEquals(manager, s_Stripped))
                {
                    s_Stripped = manager;
                    Write("VALDROID no cinematics: " + Strip(manager) + " clip(s) removed");
                }
                if (s_Playing != null && s_Stop != null && (bool)s_Playing.GetValue(null))
                {
                    s_Stop.Invoke(null, null);
                    Write("VALDROID no cinematics: a cinematic that had started was stopped");
                }
            }
            catch (Exception e)
            {
                s_On = false;
                Write("VALDROID no cinematics failed: " + e.GetType().Name + ": " + e.Message);
            }
        }

        static bool Resolve()
        {
            if (s_Resolved)
                return s_Manager != null;
            foreach (var assembly in AppDomain.CurrentDomain.GetAssemblies())
            {
                if (assembly.GetName().Name != "assembly_valheim")
                    continue;
                s_Resolved = true;
                s_Manager = assembly.GetType("CinematicsManager");
                if (s_Manager == null)
                {
                    Write("VALDROID no cinematics: this game has no CinematicsManager");
                    return false;
                }
                const BindingFlags any = BindingFlags.Public | BindingFlags.NonPublic;
                s_Instance = s_Manager.GetField("s_instance", any | BindingFlags.Static);
                s_Videos = s_Manager.GetField("m_videos", any | BindingFlags.Instance);
                s_Playing = s_Manager.GetField("m_playing", any | BindingFlags.Static);
                s_Stop = s_Manager.GetMethod("Stop", any | BindingFlags.Static, null, Type.EmptyTypes, null);
                if (s_Instance == null || s_Videos == null)
                {
                    Write("VALDROID no cinematics: CinematicsManager has no s_instance/m_videos here");
                    s_Manager = null;
                    return false;
                }
                return true;
            }
            return false;   // assembly_valheim not loaded yet: try again next frame
        }

        // Every VideoClip field of every entry (m_videoClip, m_videoClipLow in 1.0.16) set to null.
        static int Strip(object manager)
        {
            int removed = 0;
            var videos = s_Videos.GetValue(manager) as IList;
            if (videos == null)
                return 0;
            foreach (object entry in videos)
            {
                if (entry == null)
                    continue;
                foreach (var field in entry.GetType().GetFields(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance))
                {
                    if (field.FieldType != typeof(VideoClip) || field.GetValue(entry) == null)
                        continue;
                    field.SetValue(entry, null);
                    removed++;
                }
            }
            return removed;
        }

        static void Write(string line)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "{0}", line);
        }
    }
}

// A test switch in front of Unity's Swappy wrapper (frame pacing). Unity's UnitySwappyWrapper.cpp is
// compiled with its entry point renamed (see CMakeLists.txt); this file exports the name libunity looks
// up and, with VALDROID_NO_SWAPPY=1 in the environment (the instance's environment field), reports
// Swappy as unavailable, so the player presents its frames itself. For phones where frames are
// rendered but never reach the screen.
#include <stdlib.h>
#include <android/log.h>

#include "UnitySwappyWrapper.h"

UNITY_SWAPPYWRAPPER_EXPORT bool UnitySwappyWrapperInitUnity(Unity::SwappyCommonFunctions* commonfn,
                                                            Unity::SwappyGLFunctions* glfn,
                                                            Unity::SwappyVKFunctions* vkfn);

UNITY_SWAPPYWRAPPER_EXPORT bool UnitySwappyWrapperInit(Unity::SwappyCommonFunctions* commonfn,
                                                       Unity::SwappyGLFunctions* glfn,
                                                       Unity::SwappyVKFunctions* vkfn)
{
    const char* off = getenv("VALDROID_NO_SWAPPY");
    if (off && off[0] == '1')
    {
        // __android_log_print is wrapped to fatal-only for Swappy (Unity's link options); write directly.
        __android_log_write(ANDROID_LOG_INFO, "ValDroid", "Swappy frame pacing off (VALDROID_NO_SWAPPY=1)");
        return false;
    }
    return UnitySwappyWrapperInitUnity(commonfn, glfn, vkfn);
}

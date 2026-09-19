// ValDroid — a "null GLX" libGL for the direct-Vulkan route.
//
// Unity 6's Linux player creates its main window with SDL_WINDOW_OPENGL set for every graphics
// API, so SDL calls SDL_GL_LoadLibrary("libGL.so.1") before any Vulkan code runs. On the
// direct-Vulkan route the ZFA libGL is deliberately not loaded (it would bind its own swapchain
// to the ANativeWindow that Unity's Vulkan owns), so that load failed and the window was never
// created. Unity then fell back to Vulkan and crashed on the missing window.
//
// SDL only needs the library to load, export the core glX entry points and report GLX as
// present. glXCreateContext returns NULL, so SDL's extension probe finds no extensions, and the
// window visual comes from SDL_VIDEO_X11_VISUALID. box64's wrappedlibgl opens this file through BOX64_LIBGL and,
// with no ZFA/EGL bridge active, forwards the guest's glX calls here. Nothing renders through it.

#include <stddef.h>

__attribute__((visibility("default"))) void* glXGetProcAddress(const unsigned char* name) { (void)name; return NULL; }
__attribute__((visibility("default"))) void* glXGetProcAddressARB(const unsigned char* name) { (void)name; return NULL; }
__attribute__((visibility("default"))) void* glXChooseVisual(void* dpy, int screen, int* attribs) { (void)dpy; (void)screen; (void)attribs; return NULL; }
__attribute__((visibility("default"))) void* glXCreateContext(void* dpy, void* vis, void* share, int direct) { (void)dpy; (void)vis; (void)share; (void)direct; return NULL; }
__attribute__((visibility("default"))) void glXDestroyContext(void* dpy, void* ctx) { (void)dpy; (void)ctx; }
__attribute__((visibility("default"))) int glXMakeCurrent(void* dpy, unsigned long drawable, void* ctx) { (void)dpy; (void)drawable; (void)ctx; return 0; }
__attribute__((visibility("default"))) void glXSwapBuffers(void* dpy, unsigned long drawable) { (void)dpy; (void)drawable; }
__attribute__((visibility("default"))) void glXQueryDrawable(void* dpy, unsigned long drawable, int attribute, unsigned int* value) { (void)dpy; (void)drawable; (void)attribute; if (value) *value = 0; }
// SDL rejects the library ("GLX is not supported") unless glXQueryExtension reports GLX present.
__attribute__((visibility("default"))) int glXQueryExtension(void* dpy, int* errorBase, int* eventBase) { (void)dpy; if (errorBase) *errorBase = 0; if (eventBase) *eventBase = 0; return 1; }
__attribute__((visibility("default"))) int glXQueryVersion(void* dpy, int* major, int* minor) { (void)dpy; if (major) *major = 1; if (minor) *minor = 4; return 1; }
__attribute__((visibility("default"))) const char* glXQueryExtensionsString(void* dpy, int screen) { (void)dpy; (void)screen; return ""; }
__attribute__((visibility("default"))) const char* glXGetClientString(void* dpy, int name) { (void)dpy; (void)name; return ""; }
__attribute__((visibility("default"))) void* glXGetCurrentContext(void) { return NULL; }

// ValDroid — a "no devices" libudev.so.1 for SDL2's joystick/haptic init.
//
// Unity's SDL_Init(VIDEO|JOYSTICK|HAPTIC|GAMECONTROLLER) opens libudev for the joystick and
// haptic subsystems. Android has no libudev, so that dlopen failed, haptic init returned "Could
// not initialize UDEV", and SDL_Init failed as a whole — tearing down the video subsystem it had
// already brought up. Unity then cached a display count of 0 and dereferenced NULL when creating
// its main window.
//
// Built with DT_SONAME "libudev.so.1" and loaded by name from GameLauncher, so box64's udev
// wrapper dlopen("libudev.so.1") resolves to this library. Every call succeeds and reports an
// empty device set; the monitor fd is a pipe that never becomes readable.

#include <stddef.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>

#define EXP __attribute__((visibility("default")))

struct udev { int refs; };
struct udev_monitor { int fds[2]; };
struct udev_enumerate { int dummy; };

static struct udev g_udev;
static struct udev_enumerate g_enum;

EXP struct udev* udev_new(void) { return &g_udev; }
EXP struct udev* udev_ref(struct udev* u) { return u; }
EXP struct udev* udev_unref(struct udev* u) { (void)u; return NULL; }

EXP struct udev_enumerate* udev_enumerate_new(struct udev* u) { (void)u; return &g_enum; }
EXP struct udev_enumerate* udev_enumerate_unref(struct udev_enumerate* e) { (void)e; return NULL; }
EXP int udev_enumerate_add_match_subsystem(struct udev_enumerate* e, const char* s) { (void)e; (void)s; return 0; }
EXP int udev_enumerate_add_match_property(struct udev_enumerate* e, const char* k, const char* v) { (void)e; (void)k; (void)v; return 0; }
EXP int udev_enumerate_scan_devices(struct udev_enumerate* e) { (void)e; return 0; }
EXP void* udev_enumerate_get_list_entry(struct udev_enumerate* e) { (void)e; return NULL; }

EXP void* udev_list_entry_get_next(void* le) { (void)le; return NULL; }
EXP const char* udev_list_entry_get_name(void* le) { (void)le; return NULL; }

EXP void* udev_device_new_from_syspath(struct udev* u, const char* p) { (void)u; (void)p; return NULL; }
EXP void* udev_device_new_from_devnum(struct udev* u, char t, unsigned long n) { (void)u; (void)t; (void)n; return NULL; }
EXP void* udev_device_unref(void* d) { (void)d; return NULL; }
EXP const char* udev_device_get_action(void* d) { (void)d; return NULL; }
EXP const char* udev_device_get_devnode(void* d) { (void)d; return NULL; }
EXP unsigned long udev_device_get_devnum(void* d) { (void)d; return 0; }
EXP void* udev_device_get_parent_with_subsystem_devtype(void* d, const char* s, const char* t) { (void)d; (void)s; (void)t; return NULL; }
EXP const char* udev_device_get_property_value(void* d, const char* k) { (void)d; (void)k; return NULL; }
EXP const char* udev_device_get_subsystem(void* d) { (void)d; return NULL; }
EXP const char* udev_device_get_sysattr_value(void* d, const char* k) { (void)d; (void)k; return NULL; }

EXP struct udev_monitor* udev_monitor_new_from_netlink(struct udev* u, const char* name) {
    (void)u; (void)name;
    struct udev_monitor* m = calloc(1, sizeof(*m));
    if (!m) return NULL;
    if (pipe(m->fds) != 0) { free(m); return NULL; }
    fcntl(m->fds[0], F_SETFL, O_NONBLOCK);
    return m;
}
EXP struct udev_monitor* udev_monitor_unref(struct udev_monitor* m) {
    if (m) { close(m->fds[0]); close(m->fds[1]); free(m); }
    return NULL;
}
EXP int udev_monitor_enable_receiving(struct udev_monitor* m) { (void)m; return 0; }
EXP int udev_monitor_get_fd(struct udev_monitor* m) { return m ? m->fds[0] : -1; }
EXP void* udev_monitor_receive_device(struct udev_monitor* m) { (void)m; return NULL; }
EXP int udev_monitor_filter_add_match_subsystem_devtype(struct udev_monitor* m, const char* s, const char* t) { (void)m; (void)s; (void)t; return 0; }

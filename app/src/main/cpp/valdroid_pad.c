// ValDroid — virtual evdev gamepad for the guest's SDL2.
//
// Valheim only switches to its gamepad UI and bindings when SDL (statically linked into
// UnityPlayer) finds a joystick. SDL's Linux backend enumerates /dev/input through udev, which
// Android does not have, but it also honours SDL_JOYSTICK_DEVICE: every path listed there is
// open()ed and probed with the evdev ioctls. So the launcher points that hint at
// VD_PAD_PATH, box64's open()/stat()/ioctl() wrappers route that path to this file, and the guest
// reads ordinary `struct input_event` records from a socket whose other end Java feeds from the
// physical controller or the on-screen overlay.
//
// The device identifies as a Microsoft X-Box 360 pad (bus USB, 045e:028e), which SDL's built-in
// controller database maps to a full SDL_GameController without any extra configuration.
//
// Layout of the guest side (x86_64 Linux): struct input_event = { timeval(16) u16 type u16 code
// s32 value } = 24 bytes. The socket is SOCK_SEQPACKET so a batch of events plus its SYN_REPORT
// is delivered whole to one read().

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#include "logger.h"

#define VD_PAD_PATH "/dev/input/event-valdroid"

// evdev constants (linux/input-event-codes.h), spelled out so this file needs no kernel headers
#define EV_SYN 0x00
#define EV_KEY 0x01
#define EV_ABS 0x03
#define SYN_REPORT 0
#define BTN_A 0x130
#define BTN_B 0x131
#define BTN_X 0x133
#define BTN_Y 0x134
#define BTN_TL 0x136
#define BTN_TR 0x137
#define BTN_SELECT 0x13a
#define BTN_START 0x13b
#define BTN_MODE 0x13c
#define BTN_THUMBL 0x13d
#define BTN_THUMBR 0x13e
#define ABS_X 0x00
#define ABS_Y 0x01
#define ABS_Z 0x02
#define ABS_RX 0x03
#define ABS_RY 0x04
#define ABS_RZ 0x05
#define ABS_HAT0X 0x10
#define ABS_HAT0Y 0x11

static const uint16_t kButtons[] = { BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_SELECT,
                                     BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR };
static const uint16_t kAxes[]    = { ABS_X, ABS_Y, ABS_Z, ABS_RX, ABS_RY, ABS_RZ, ABS_HAT0X, ABS_HAT0Y };

static pthread_mutex_t g_mx = PTHREAD_MUTEX_INITIALIZER;
static int g_host = -1, g_guest = -1;   // socketpair ends: Java writes to host, SDL reads guest
static ino_t g_guest_ino;

// Pending batch (Java side calls axis/button, then sync flushes it as one packet)
#define VD_PAD_BATCH 32
static unsigned char g_batch[VD_PAD_BATCH * 24];
static int g_batch_n = 0;
static int g_last_abs[0x40];   // dedupe unchanged axis values

static int ensure_pair(void) {
    if (g_host >= 0) return 0;
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sv) != 0) {
        LOGE("pad: socketpair failed: %s", strerror(errno));
        return -1;
    }
    g_host = sv[0]; g_guest = sv[1];
    struct stat st;
    g_guest_ino = fstat(g_guest, &st) == 0 ? st.st_ino : 0;
    memset(g_last_abs, 0x7f, sizeof(g_last_abs));
    LOGI("pad: virtual evdev gamepad ready (%s)", VD_PAD_PATH);
    return 0;
}

// ---- guest side (called from box64's libc wrappers) ----------------------------------------

const char* rd_pad_path(void) { return VD_PAD_PATH; }

/** open(VD_PAD_PATH): a fresh descriptor on the guest end. SDL opens the device to probe it,
 *  closes it, and opens it again for use; each open gets its own fd on the same socket. */
int rd_pad_open(int flags) {
    pthread_mutex_lock(&g_mx);
    int r = ensure_pair() == 0 ? fcntl(g_guest, (flags & O_CLOEXEC) ? F_DUPFD_CLOEXEC : F_DUPFD, 0) : -1;
    pthread_mutex_unlock(&g_mx);
    if (r >= 0 && (flags & O_NONBLOCK)) fcntl(r, F_SETFL, O_NONBLOCK);
    return r;
}

int rd_pad_is_fd(int fd) {
    if (g_guest < 0 || fd < 0) return 0;
    struct stat st;
    return fstat(fd, &st) == 0 && S_ISSOCK(st.st_mode) && st.st_ino == g_guest_ino;
}

static void set_bit(unsigned char* bits, size_t len, unsigned n) {
    if (n / 8 < len) bits[n / 8] |= (unsigned char)(1u << (n % 8));
}

/** The evdev ioctls SDL issues while probing and using a joystick. Anything else: EINVAL. */
int rd_pad_ioctl(int fd, unsigned long req, void* arg) {
    (void)fd;
    unsigned type = (req >> 8) & 0xff, nr = req & 0xff, size = (req >> 16) & 0x3fff;
    if (type != 'E') { errno = EINVAL; return -1; }   // e.g. JSIOCGNAME ('j'): not a /dev/input/js device
    if (!arg) { errno = EFAULT; return -1; }
    unsigned char* out = (unsigned char*)arg;
    switch (nr) {
        case 0x01: { *(int32_t*)arg = 0x010001; return 0; }                       // EVIOCGVERSION
        case 0x02: {                                                             // EVIOCGID
            uint16_t id[4] = { 0x0003 /*BUS_USB*/, 0x045e, 0x028e, 0x0114 };
            memcpy(arg, id, sizeof(id)); return 0;
        }
        case 0x03: { ((int32_t*)arg)[0] = 0; ((int32_t*)arg)[1] = 0; return 0; }  // EVIOCGREP
        case 0x06: {                                                             // EVIOCGNAME(len)
            const char* name = "Microsoft X-Box 360 pad";
            size_t n = strlen(name) + 1; if (n > size) n = size;
            memcpy(arg, name, n); return (int)n;
        }
        case 0x07: case 0x08: { errno = ENOENT; return -1; }                     // EVIOCGPHYS / EVIOCGUNIQ
        case 0x09: case 0x18: case 0x19: case 0x1a: case 0x1b:                   // GPROP, GKEY, GLED, GSND, GSW
            memset(out, 0, size); return (int)size;
        case 0x84: { *(int32_t*)arg = 0; return 0; }                              // EVIOCGEFFECTS: no rumble
        case 0x90: case 0x91: return 0;                                          // EVIOCGRAB / EVIOCREVOKE
        default: break;
    }
    if (nr >= 0x20 && nr < 0x40) {                                               // EVIOCGBIT(ev, len)
        unsigned ev = nr - 0x20;
        memset(out, 0, size);
        if (ev == 0) { set_bit(out, size, EV_SYN); set_bit(out, size, EV_KEY); set_bit(out, size, EV_ABS); }
        else if (ev == EV_KEY) { for (size_t i = 0; i < sizeof(kButtons) / 2; i++) set_bit(out, size, kButtons[i]); }
        else if (ev == EV_ABS) { for (size_t i = 0; i < sizeof(kAxes) / 2; i++) set_bit(out, size, kAxes[i]); }
        return (int)size;
    }
    if (nr >= 0x40 && nr < 0x80) {                                               // EVIOCGABS(abs)
        unsigned abs = nr - 0x40;
        int32_t info[6] = { 0, -32768, 32767, 16, 128, 0 };                       // value,min,max,fuzz,flat,res
        if (abs == ABS_Z || abs == ABS_RZ) { info[1] = 0; info[2] = 255; info[3] = 0; info[4] = 0; }
        else if (abs == ABS_HAT0X || abs == ABS_HAT0Y) { info[1] = -1; info[2] = 1; info[3] = 0; info[4] = 0; }
        else if (abs > ABS_RZ) { errno = EINVAL; return -1; }
        if (abs < 0x40 && g_last_abs[abs] != 0x7f7f7f7f) info[0] = g_last_abs[abs];
        memcpy(arg, info, sizeof(info)); return 0;
    }
    if (nr >= 0xc0) return 0;                                                    // EVIOCSABS
    errno = EINVAL; return -1;
}

// ---- host side (JNI) ---------------------------------------------------------------------

static void batch_put(uint16_t type, uint16_t code, int32_t value) {
    if (g_batch_n >= VD_PAD_BATCH - 1) return;   // keep room for the SYN
    unsigned char* e = g_batch + g_batch_n * 24;
    struct timeval tv; gettimeofday(&tv, NULL);
    int64_t sec = tv.tv_sec, usec = tv.tv_usec;
    memcpy(e, &sec, 8); memcpy(e + 8, &usec, 8);
    memcpy(e + 16, &type, 2); memcpy(e + 18, &code, 2); memcpy(e + 20, &value, 4);
    g_batch_n++;
}

void rd_pad_button(int code, int down) {
    pthread_mutex_lock(&g_mx);
    if (ensure_pair() == 0) batch_put(EV_KEY, (uint16_t)code, down ? 1 : 0);
    pthread_mutex_unlock(&g_mx);
}

void rd_pad_axis(int code, int value) {
    pthread_mutex_lock(&g_mx);
    if (ensure_pair() == 0 && code >= 0 && code < 0x40 && g_last_abs[code] != value) {
        g_last_abs[code] = value;
        batch_put(EV_ABS, (uint16_t)code, value);
    }
    pthread_mutex_unlock(&g_mx);
}

/** Flush the pending events as one packet terminated by SYN_REPORT. */
void rd_pad_sync(void) {
    pthread_mutex_lock(&g_mx);
    if (g_host >= 0 && g_batch_n > 0) {
        batch_put(EV_SYN, SYN_REPORT, 0);
        // Non-blocking: if the guest is not reading (no joystick open yet) the packet is dropped
        // rather than stalling the UI thread; the socket buffer absorbs normal bursts.
        ssize_t r = send(g_host, g_batch, (size_t)g_batch_n * 24, MSG_DONTWAIT | MSG_NOSIGNAL);
        (void)r;
        g_batch_n = 0;
    }
    pthread_mutex_unlock(&g_mx);
}

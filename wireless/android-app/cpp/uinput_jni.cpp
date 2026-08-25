// Minimal virtual Xbox-style gamepad for RC-N1C Flight Bridge.
// Runs inside a Shizuku user-service process (shell UID) so /dev/uinput can be opened
// on devices whose SELinux policy permits it. No root is required when that path works.

#include <jni.h>
#include <android/log.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <cerrno>
#include <cstring>

#define LOG_TAG "rcn1c_uinput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int g_fd = -1;
static int g_last_buttons = 0;

static int emit_event(uint16_t type, uint16_t code, int32_t value) {
    if (g_fd < 0) return -1;
    input_event ev{};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(g_fd, &ev, sizeof(ev)) == (ssize_t) sizeof(ev) ? 0 : -1;
}

static bool set_abs(int fd, int code, int min, int max, int flat) {
    if (ioctl(fd, UI_SET_ABSBIT, code) < 0) return false;
    uinput_abs_setup setup{};
    setup.code = code;
    setup.absinfo.minimum = min;
    setup.absinfo.maximum = max;
    setup.absinfo.flat = flat;
    if (ioctl(fd, UI_ABS_SETUP, &setup) < 0) {
        LOGE("UI_ABS_SETUP %d failed: %s", code, strerror(errno));
        return false;
    }
    return true;
}

static void destroy_device() {
    if (g_fd >= 0) {
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
    }
    g_last_buttons = 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drone_rcn1cbridge_uinput_UInputNative_canOpen(JNIEnv*, jclass) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGI("/dev/uinput unavailable: %s", strerror(errno));
        return JNI_FALSE;
    }
    close(fd);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_drone_rcn1cbridge_uinput_UInputNative_createDevice(JNIEnv*, jclass) {
    destroy_device();

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("open /dev/uinput failed: %s", strerror(errno));
        return JNI_FALSE;
    }

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0 ||
        ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) {
        LOGE("failed to enable event classes: %s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    const int keys[] = {
        BTN_A, BTN_B, BTN_X, BTN_Y,
        BTN_TL, BTN_TR, BTN_SELECT, BTN_START,
        BTN_MODE, BTN_THUMBL, BTN_THUMBR
    };
    for (int key : keys) {
        if (ioctl(fd, UI_SET_KEYBIT, key) < 0) {
            LOGE("UI_SET_KEYBIT %d failed: %s", key, strerror(errno));
            close(fd);
            return JNI_FALSE;
        }
    }

    // Match a conventional Xbox 360 style report. A tiny deadzone is intentionally not
    // applied here; RC calibration/rates belong in Flight Bridge or in the simulator.
    if (!set_abs(fd, ABS_X, -32768, 32767, 0) ||
        !set_abs(fd, ABS_Y, -32768, 32767, 0) ||
        !set_abs(fd, ABS_RX, -32768, 32767, 0) ||
        !set_abs(fd, ABS_RY, -32768, 32767, 0) ||
        !set_abs(fd, ABS_Z, 0, 255, 0) ||
        !set_abs(fd, ABS_RZ, 0, 255, 0) ||
        !set_abs(fd, ABS_HAT0X, -1, 1, 0) ||
        !set_abs(fd, ABS_HAT0Y, -1, 1, 0)) {
        close(fd);
        return JNI_FALSE;
    }

    uinput_setup setup{};
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x045E;
    setup.id.product = 0x028E;
    setup.id.version = 0x0114;
    strncpy(setup.name, "RC-N1C Flight Bridge Virtual Pad", UINPUT_MAX_NAME_SIZE - 1);

    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("UI_DEV_CREATE failed: %s", strerror(errno));
        close(fd);
        return JNI_FALSE;
    }

    g_fd = fd;
    g_last_buttons = 0;
    LOGI("virtual gamepad created");
    return JNI_TRUE;
}

static void emit_button_edge(int buttons, int bit, int linux_key) {
    const bool now = (buttons & bit) != 0;
    const bool before = (g_last_buttons & bit) != 0;
    if (now != before) emit_event(EV_KEY, linux_key, now ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drone_rcn1cbridge_uinput_UInputNative_sendFrame(
        JNIEnv*, jclass,
        jint buttons,
        jint leftX, jint leftY,
        jint rightX, jint rightY,
        jint leftTrigger, jint rightTrigger,
        jint dpadX, jint dpadY) {
    if (g_fd < 0) return;

    // Bit order intentionally matches a conventional Xbox descriptor.
    emit_button_edge(buttons, 1 << 0, BTN_A);
    emit_button_edge(buttons, 1 << 1, BTN_B);
    emit_button_edge(buttons, 1 << 2, BTN_X);
    emit_button_edge(buttons, 1 << 3, BTN_Y);
    emit_button_edge(buttons, 1 << 4, BTN_TL);
    emit_button_edge(buttons, 1 << 5, BTN_TR);
    emit_button_edge(buttons, 1 << 6, BTN_SELECT);
    emit_button_edge(buttons, 1 << 7, BTN_START);
    emit_button_edge(buttons, 1 << 8, BTN_MODE);
    emit_button_edge(buttons, 1 << 9, BTN_THUMBL);
    emit_button_edge(buttons, 1 << 10, BTN_THUMBR);
    g_last_buttons = buttons;

    emit_event(EV_ABS, ABS_X, leftX);
    emit_event(EV_ABS, ABS_Y, leftY);
    emit_event(EV_ABS, ABS_RX, rightX);
    emit_event(EV_ABS, ABS_RY, rightY);
    emit_event(EV_ABS, ABS_Z, leftTrigger);
    emit_event(EV_ABS, ABS_RZ, rightTrigger);
    emit_event(EV_ABS, ABS_HAT0X, dpadX);
    emit_event(EV_ABS, ABS_HAT0Y, dpadY);
    emit_event(EV_SYN, SYN_REPORT, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_drone_rcn1cbridge_uinput_UInputNative_destroy(JNIEnv*, jclass) {
    destroy_device();
    LOGI("virtual gamepad destroyed");
}

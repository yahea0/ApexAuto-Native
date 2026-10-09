#include "input_injector.hpp"
#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>
#include <dirent.h>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <chrono>
#include <android/log.h>

#define LOG_TAG "Apex_Input"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

InputInjector::InputInjector() : event_fd_(-1), use_evdev_(false) {}

InputInjector::~InputInjector() {
    if (event_fd_ >= 0) {
        close(event_fd_);
    }
}

std::string InputInjector::findTouchScreenDevice() {
    DIR* dir = opendir("/dev/input");
    if (!dir) return "";

    struct dirent* ent;
    char name[256];
    while ((ent = readdir(dir)) != nullptr) {
        if (strncmp(ent->d_name, "event", 5) == 0) {
            std::string path = std::string("/dev/input/") + ent->d_name;
            int fd = open(path.c_str(), O_RDONLY);
            if (fd >= 0) {
                if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0) {
                    std::string dev_name = name;
                    if (dev_name.find("touch") != std::string::npos ||
                        dev_name.find("Touch") != std::string::npos ||
                        dev_name.find("ts") != std::string::npos) {
                        close(fd);
                        closedir(dir);
                        return path;
                    }
                }
                close(fd);
            }
        }
    }
    closedir(dir);
    return "";
}

bool InputInjector::init() {
    device_path_ = findTouchScreenDevice();
    if (!device_path_.empty()) {
        event_fd_ = open(device_path_.c_str(), O_RDWR | O_NONBLOCK);
        if (event_fd_ >= 0) {
            use_evdev_ = true;
            LOGI("Direct Touchscreen /dev/input Injection Active: %s", device_path_.c_str());
            return true;
        }
    }
    LOGI("Using Android Shell Input mode");
    return true;
}

void InputInjector::sendEvent(int fd, uint16_t type, uint16_t code, int32_t value) {
    struct input_event ev {};
    ev.type = type;
    ev.code = code;
    ev.value = value;
    gettimeofday(&ev.time, nullptr);
    write(fd, &ev, sizeof(ev));
}

void InputInjector::injectTap(int x, int y) {
    if (use_evdev_ && event_fd_ >= 0) {
        sendEvent(event_fd_, EV_ABS, ABS_MT_TRACKING_ID, 1);
        sendEvent(event_fd_, EV_ABS, ABS_MT_POSITION_X, x);
        sendEvent(event_fd_, EV_ABS, ABS_MT_POSITION_Y, y);
        sendEvent(event_fd_, EV_KEY, BTN_TOUCH, 1);
        sendEvent(event_fd_, EV_SYN, SYN_REPORT, 0);

        std::this_thread::sleep_for(std::chrono::milliseconds(15));

        sendEvent(event_fd_, EV_ABS, ABS_MT_TRACKING_ID, -1);
        sendEvent(event_fd_, EV_KEY, BTN_TOUCH, 0);
        sendEvent(event_fd_, EV_SYN, SYN_REPORT, 0);
    } else {
        char cmd[128];
        snprintf(cmd, sizeof(cmd), "input tap %d %d", x, y);
        system(cmd);
    }
}

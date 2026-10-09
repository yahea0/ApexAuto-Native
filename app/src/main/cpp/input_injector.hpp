#pragma once

#include <string>

class InputInjector {
public:
    InputInjector();
    ~InputInjector();

    bool init();
    void injectTap(int x, int y);

private:
    int event_fd_;
    std::string device_path_;
    bool use_evdev_;

    void sendEvent(int fd, uint16_t type, uint16_t code, int32_t value);
    std::string findTouchScreenDevice();
};

#include <iostream>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <android/log.h>
#include "yolo12_engine.hpp"
#include "input_injector.hpp"

#define LOG_TAG "Apex_Daemon"
#define SOCKET_PATH "/data/local/tmp/apex_daemon.sock"

int main(int argc, char** argv) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Starting Apex Native Shell Daemon (UID: %d)", getuid());

    InputInjector injector;
    injector.init();

    std::string model_path = (argc > 1) ? argv[1] : "/data/local/tmp/yolov12n.onnx";
    YOLOv12Engine yolo(model_path, 0.40f, 0.45f);

    unlink(SOCKET_PATH);
    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) return 1;

    sockaddr_un addr {};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) return 1;
    listen(server_fd, 5);

    bool running = true;
    while (running) {
        int client_fd = accept(server_fd, nullptr, nullptr);
        if (client_fd < 0) continue;

        char buffer[256];
        ssize_t bytes_read = read(client_fd, buffer, sizeof(buffer) - 1);
        if (bytes_read > 0) {
            buffer[bytes_read] = '\0';
            std::string cmd(buffer);

            if (cmd.find("TAP") == 0) {
                int x, y;
                if (sscanf(cmd.c_str(), "TAP %d %d", &x, &y) == 2) {
                    injector.injectTap(x, y);
                    write(client_fd, "OK\n", 3);
                }
            } else if (cmd.find("STOP") == 0) {
                running = false;
                write(client_fd, "STOPPED\n", 8);
            }
        }
        close(client_fd);
    }

    close(server_fd);
    unlink(SOCKET_PATH);
    return 0;
}

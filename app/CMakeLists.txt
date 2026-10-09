cmake_minimum_required(VERSION 3.22.1)
project(apex_native_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/third_party)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY_DIR}/onnxruntime/lib/${ANDROID_ABI}/libonnxruntime.so
    INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY_DIR}/onnxruntime/include
)

add_library(opencv_core SHARED IMPORTED)
set_target_properties(opencv_core PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY_DIR}/opencv/lib/${ANDROID_ABI}/libopencv_core.so
    INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY_DIR}/opencv/include
)

add_library(opencv_imgproc SHARED IMPORTED)
set_target_properties(opencv_imgproc PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY_DIR}/opencv/lib/${ANDROID_ABI}/libopencv_imgproc.so
)

add_library(opencv_features2d SHARED IMPORTED)
set_target_properties(opencv_features2d PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY_DIR}/opencv/lib/${ANDROID_ABI}/libopencv_features2d.so
)

include_directories(
    src/main/cpp
    ${THIRD_PARTY_DIR}/onnxruntime/include
    ${THIRD_PARTY_DIR}/opencv/include
)

set(CORE_SOURCES
    src/main/cpp/yolo12_engine.cpp
    src/main/cpp/orb_matcher.cpp
    src/main/cpp/input_injector.cpp
)

add_executable(apex_daemon
    src/main/cpp/daemon_main.cpp
    ${CORE_SOURCES}
)

target_link_libraries(apex_daemon
    onnxruntime
    opencv_core
    opencv_imgproc
    opencv_features2d
    android
    log
)

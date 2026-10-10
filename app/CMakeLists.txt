cmake_minimum_required(VERSION 3.22.1)
project(apex_vision_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/third_party)

# استيراد OpenCV
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY_DIR}/opencv/lib/${ANDROID_ABI}/libopencv_java4.so
    INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY_DIR}/opencv/include
)

# استيراد ONNX Runtime
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY_DIR}/onnxruntime/lib/${ANDROID_ABI}/libonnxruntime.so
    INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY_DIR}/onnxruntime/include
)

include_directories(
    src/main/cpp
    ${THIRD_PARTY_DIR}/opencv/include
    ${THIRD_PARTY_DIR}/onnxruntime/include
)

# بناء مكتبة الرؤية الشاملة (OpenCV + YOLOv12 + ORB)
add_library(apex_vision SHARED
    src/main/cpp/native_matcher.cpp
    src/main/cpp/yolo12_engine.cpp
    src/main/cpp/orb_matcher.cpp
)

target_link_libraries(apex_vision
    opencv_java4
    onnxruntime
    jnigraphics
    android
    log
)

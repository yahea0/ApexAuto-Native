cmake_minimum_required(VERSION 3.22.1)
project(apex_vision_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/third_party)

# استيراد مكتبة OpenCV
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES
    IMPORTED_LOCATION ${THIRD_PARTY_DIR}/opencv/lib/${ANDROID_ABI}/libopencv_java4.so
    INTERFACE_INCLUDE_DIRECTORIES ${THIRD_PARTY_DIR}/opencv/include
)

include_directories(
    src/main/cpp
    ${THIRD_PARTY_DIR}/opencv/include
)

# بناء مكتبة الرؤية المشتركة JNI
add_library(apex_vision SHARED
    src/main/cpp/native_matcher.cpp
    src/main/cpp/orb_matcher.cpp
)

target_link_libraries(apex_vision
    opencv_java4
    jnigraphics
    android
    log
)

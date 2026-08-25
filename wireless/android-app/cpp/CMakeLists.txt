cmake_minimum_required(VERSION 3.22.1)
project(rcn1c_uinput)

add_library(rcn1c_uinput SHARED uinput_jni.cpp)
find_library(log-lib log)
target_link_libraries(rcn1c_uinput ${log-lib})

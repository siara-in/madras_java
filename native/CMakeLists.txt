cmake_minimum_required(VERSION 3.16)
project(madras_jni CXX)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to madras/dv1 headers -- same layout used by madras_cli / Python bindings.
if(NOT DEFINED MADRAS_INCLUDE_DIR)
  set(MADRAS_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../include")
endif()

# JNI headers. For desktop builds, JAVA_HOME's include dirs. For Android NDK
# builds, the NDK toolchain already provides jni.h via its sysroot, so
# JNI_INCLUDE_DIRS is left empty in that case (see README's android build
# command, which doesn't set JAVA_HOME).
if(NOT ANDROID)
  find_package(JNI REQUIRED)
endif()

add_library(madras_jni SHARED
  ${CMAKE_CURRENT_SOURCE_DIR}/../jni/madras_jni.cpp
)

target_include_directories(madras_jni PRIVATE
  ${MADRAS_INCLUDE_DIR}
  ${CMAKE_CURRENT_SOURCE_DIR}/../jni
)

if(NOT ANDROID)
  target_include_directories(madras_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_compile_options(madras_jni PRIVATE -O3)

# Output naming: CMake already produces libmadras_jni.so/.dylib on
# Unix-likes and madras_jni.dll on Windows, matching what
# MadrasNative.java's resource-path lookup expects.
set_target_properties(madras_jni PROPERTIES
  OUTPUT_NAME "madras_jni"
)

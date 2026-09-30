# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
#
# Hook into the unmodified upstream Ladybird CMake project.
#
# tools/build_native.py configures ladybird/ with
#     -DCMAKE_PROJECT_ladybird_INCLUDE=<this file>
# so CMake includes this file right after upstream's `project(ladybird ...)`.
# At that point no upstream target exists yet, so the real work (LadybirdAndroidTargets.cmake)
# is deferred to the end of the top-level CMakeLists.txt, when every library and
# helper-process target has been declared.
#
# Requirement: BLD-002 (upstream is consumed as-is; our code lives outside the submodule).

set(LADYBIRD_ANDROID_NATIVE_DIR "${CMAKE_CURRENT_LIST_DIR}")

if (NOT ANDROID)
    message(FATAL_ERROR "native/LadybirdAndroid.cmake is only meant for Android (NDK) builds")
endif()

if (NOT LADYBIRD_ANDROID_STAGE_DIR)
    message(FATAL_ERROR "LADYBIRD_ANDROID_STAGE_DIR must point to the directory where the APK payload is staged")
endif()

cmake_language(DEFER CALL include "${LADYBIRD_ANDROID_NATIVE_DIR}/LadybirdAndroidTargets.cmake")

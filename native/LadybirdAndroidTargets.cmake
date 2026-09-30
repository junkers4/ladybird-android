# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
#
# Android glue for the upstream Ladybird build. LadybirdAndroid.cmake includes this file (deferred) at
# the very end of upstream's top-level CMakeLists.txt, so all upstream targets exist here. CMake does not
# allow add_subdirectory() during deferred execution, which is why this is an include file and uses
# absolute paths (the current source directory is upstream's root).
#
# Process model (requirement ARCH-001): the Android app runs Ladybird's browser process
# (LibWebView::Application) inside the app process through libladybird_android.so, and spawns
# every helper process (WebContent, RequestServer, ImageDecoder, Compositor, WebWorker,
# MediaServer) as a real executable, exactly like the Linux (Qt) port does. Android only
# allows executing files from the APK's native library directory, so the helpers are packaged
# as lib<Name>.so and exposed to LibWebView through a libexec/ directory of symlinks that the
# app creates at startup (see android/app/.../engine/EngineInstaller.kt).

set(LADYBIRD_ANDROID_HELPERS
    Compositor
    ImageDecoder
    MediaServer
    RequestServer
    WebContent
    WebWorker
)
if (ENABLE_CRANELIFT_JIT)
    list(APPEND LADYBIRD_ANDROID_HELPERS WasmCompiler)
endif()

# Upstream's Android port links JNI "service" glue from UI/Android into the helper libraries.
# We do not use those Android Services (the helpers are launched as executables), and that glue
# is not kept in sync with upstream's IPC changes, so drop it from the service libraries.
foreach (service_target IN ITEMS webcontentservice requestserverservice imagedecoderservice)
    if (TARGET ${service_target})
        get_target_property(service_sources ${service_target} SOURCES)
        list(FILTER service_sources EXCLUDE REGEX "/UI/Android/src/main/cpp/")
        set_property(TARGET ${service_target} PROPERTY SOURCES ${service_sources})
    endif()
endforeach()

add_library(ladybird_android SHARED
    "${LADYBIRD_ANDROID_NATIVE_DIR}/src/AndroidApplication.cpp"
    "${LADYBIRD_ANDROID_NATIVE_DIR}/src/AndroidWebView.cpp"
    "${LADYBIRD_ANDROID_NATIVE_DIR}/src/EngineThread.cpp"
    "${LADYBIRD_ANDROID_NATIVE_DIR}/src/JNIBridge.cpp"
    "${LADYBIRD_ANDROID_NATIVE_DIR}/src/NativeEngineJNI.cpp"
    "${LADYBIRD_ANDROID_NATIVE_DIR}/src/SerializeMenu.cpp"
)
target_include_directories(ladybird_android PRIVATE
    "${LADYBIRD_ANDROID_NATIVE_DIR}/src"
    "${LADYBIRD_SOURCE_DIR}"
    "${LADYBIRD_SOURCE_DIR}/Services"
    "${CMAKE_BINARY_DIR}"
    "${CMAKE_BINARY_DIR}/Libraries"
    "${CMAKE_BINARY_DIR}/Services"
)
target_link_libraries(ladybird_android PRIVATE
    AK
    LibCore
    LibGfx
    LibIPC
    LibMain
    LibURL
    LibWakeLock
    LibWebView
    OpenSSL::Crypto
    OpenSSL::SSL
    android
    jnigraphics
    log
)
# Only JNI_OnLoad needs to be visible; everything else is registered through RegisterNatives.
set_target_properties(ladybird_android PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
# Full RELRO and no lazy binding (requirement SEC-012). The NDK already enables
# -fstack-protector-strong and _FORTIFY_SOURCE for all code it compiles.
target_link_options(ladybird_android PRIVATE "-Wl,--no-undefined" "-Wl,-z,relro,-z,now")

# --- Staging -----------------------------------------------------------------------------------
# Everything the APK needs ends up in ${LADYBIRD_ANDROID_STAGE_DIR}:
#   jniLibs/<abi>/lib*.so     shared libraries and helper executables (renamed lib<Name>.so)
#   resources/                Ladybird's runtime resources (fonts, themes, about: pages, ...)
set(stage_jni_dir "${LADYBIRD_ANDROID_STAGE_DIR}/jniLibs/${ANDROID_ABI}")
set(stage_resources_dir "${LADYBIRD_ANDROID_STAGE_DIR}/resources")

add_custom_target(ladybird_android_stage)
add_dependencies(ladybird_android_stage ladybird_android)

set(stage_commands
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${stage_jni_dir}"
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:ladybird_android>" "${stage_jni_dir}/libladybird_android.so"
)

foreach (helper IN LISTS LADYBIRD_ANDROID_HELPERS)
    if (NOT TARGET ${helper})
        message(FATAL_ERROR "Helper process target ${helper} does not exist")
    endif()
    add_dependencies(ladybird_android_stage ${helper})
    list(APPEND stage_commands
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:${helper}>" "${stage_jni_dir}/lib${helper}.so"
    )
endforeach()

# Shared libraries the helpers link against (upstream makes the *service libraries shared on Android).
foreach (shared_target IN ITEMS webcontentservice requestserverservice imagedecoderservice)
    if (TARGET ${shared_target})
        get_target_property(shared_target_type ${shared_target} TYPE)
        if (shared_target_type STREQUAL "SHARED_LIBRARY")
            list(APPEND stage_commands
                COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:${shared_target}>" "${stage_jni_dir}/$<TARGET_FILE_NAME:${shared_target}>"
            )
        endif()
    endif()
endforeach()

# libc++_shared.so from the NDK (ANDROID_STL=c++_shared).
if (ANDROID_STL STREQUAL "c++_shared")
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(ndk_triple "aarch64-linux-android")
    elseif (ANDROID_ABI STREQUAL "x86_64")
        set(ndk_triple "x86_64-linux-android")
    else()
        message(FATAL_ERROR "Unsupported ANDROID_ABI ${ANDROID_ABI}")
    endif()
    file(GLOB libcxx_shared_candidates "${ANDROID_NDK}/toolchains/llvm/prebuilt/*/sysroot/usr/lib/${ndk_triple}/libc++_shared.so")
    if (NOT libcxx_shared_candidates)
        message(FATAL_ERROR "Could not find libc++_shared.so for ${ndk_triple} in ${ANDROID_NDK}")
    endif()
    list(GET libcxx_shared_candidates 0 libcxx_shared)
    list(APPEND stage_commands
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${libcxx_shared}" "${stage_jni_dir}/libc++_shared.so"
    )
endif()

add_custom_command(TARGET ladybird_android_stage POST_BUILD ${stage_commands} VERBATIM)

# Runtime resources, using upstream's own list of what a Ladybird install needs. The resource lists are
# variables scoped to UI/, so load them again here.
include("${LADYBIRD_SOURCE_DIR}/UI/cmake/ResourceFiles.cmake")
copy_resources_to_build("${stage_resources_dir}" ladybird_android_stage)

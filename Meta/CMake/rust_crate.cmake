# import_rust_crate(MANIFEST_PATH path/to/Cargo.toml CRATE_NAME name)
#
# Builds a Rust static library crate using cargo and creates an IMPORTED target.
# MANIFEST_PATH is relative to CMAKE_CURRENT_SOURCE_DIR.
#
# When corrosion supports dependency tracking, we can use corrosion_import_crate() instead of this function. See:
# https://github.com/corrosion-rs/corrosion/issues/206
# https://github.com/corrosion-rs/corrosion/issues/624
function(import_rust_crate)
    cmake_parse_arguments(PARSE_ARGV 0 ARG "" "MANIFEST_PATH;CRATE_NAME;FFI_OUTPUT_DIR;FFI_HEADER" "FEATURES")

    if (NOT ARG_FFI_OUTPUT_DIR)
        set(ARG_FFI_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}")
    endif()
    if (ARG_FFI_HEADER)
        set(ffi_output "${ARG_FFI_OUTPUT_DIR}/${ARG_FFI_HEADER}")
    endif()

    _rust_crate_common_setup(
        MANIFEST_PATH "${ARG_MANIFEST_PATH}"
        CRATE_NAME ${ARG_CRATE_NAME}
        FFI_OUTPUT_DIR "${ARG_FFI_OUTPUT_DIR}"
    )

    set(cargo_feature_flags "")
    if (ARG_FEATURES)
        list(JOIN ARG_FEATURES "," cargo_features)
        list(APPEND cargo_feature_flags "--features=${cargo_features}")
    endif()

    if (WIN32)
        set(output_lib "${cargo_output_dir}/${ARG_CRATE_NAME}.lib")
        set(depfile "${cargo_output_dir}/${ARG_CRATE_NAME}.d")
    else()
        set(output_lib "${cargo_output_dir}/lib${ARG_CRATE_NAME}.a")
        set(depfile "${cargo_output_dir}/lib${ARG_CRATE_NAME}.d")
    endif()

    add_custom_command(
        OUTPUT "${output_lib}" ${ffi_output}
        COMMAND
            ${CMAKE_COMMAND} -E env ${cargo_env}
            "${RUST_CARGO}"
                rustc
                --lib
                ${cargo_feature_flags}
                ${cargo_common_flags}
        COMMAND
            ${CMAKE_COMMAND}
                -DCARGO_BUILD_SCRIPT_DIR=${cargo_output_dir}/build
                -DCRATE_NAME=${ARG_CRATE_NAME}
                -DFFI_HEADER=${ARG_FFI_HEADER}
                -DFFI_OUTPUT_DIR=${ARG_FFI_OUTPUT_DIR}
                -P "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/sync_rust_ffi_header.cmake"
        DEPENDS "${manifest_path}"
            "${workspace_dir}/Cargo.lock" "${workspace_dir}/Cargo.toml"
            "${RUST_RUSTC}" "${CMAKE_SOURCE_DIR}/rust-toolchain.toml"
        DEPFILE "${depfile}"
        COMMENT "Building Rust crate ${ARG_CRATE_NAME}"
        USES_TERMINAL
        COMMAND_EXPAND_LISTS
    )

    add_custom_target(${ARG_CRATE_NAME}-build DEPENDS "${output_lib}" ${ffi_output})

    add_library(${ARG_CRATE_NAME} STATIC IMPORTED GLOBAL)
    set_target_properties(${ARG_CRATE_NAME} PROPERTIES
            IMPORTED_LOCATION "${output_lib}"
            INTERFACE_INCLUDE_DIRECTORIES "${ARG_FFI_OUTPUT_DIR}"
    )
    add_dependencies(${ARG_CRATE_NAME} ${ARG_CRATE_NAME}-build)

    # Rust staticlibs bundle the standard library, which on Windows depends on system libraries.
    if (WIN32)
        set_target_properties(${ARG_CRATE_NAME} PROPERTIES
            INTERFACE_LINK_LIBRARIES "kernel32;ntdll;Ws2_32;userenv"
        )
    endif()
endfunction()

# build_rust_binary(MANIFEST_PATH path/to/Cargo.toml CRATE_NAME name BINARY_NAME name OUTPUT_PATH_VAR var)
#
# Builds a Rust binary crate target using cargo and exposes the copied binary path through OUTPUT_PATH_VAR.
function(build_rust_binary)
    cmake_parse_arguments(PARSE_ARGV 0 ARG "" "MANIFEST_PATH;CRATE_NAME;BINARY_NAME;OUTPUT_NAME;OUTPUT_PATH_VAR;FFI_OUTPUT_DIR" "")

    if (NOT ARG_OUTPUT_NAME)
        set(ARG_OUTPUT_NAME "${ARG_BINARY_NAME}")
    endif()

    _rust_crate_common_setup(
        MANIFEST_PATH "${ARG_MANIFEST_PATH}"
        CRATE_NAME ${ARG_CRATE_NAME}
        FFI_OUTPUT_DIR "${ARG_FFI_OUTPUT_DIR}"
    )

    set(cargo_binary "${cargo_output_dir}/${ARG_BINARY_NAME}${CMAKE_EXECUTABLE_SUFFIX}")
    set(depfile "${cargo_output_dir}/${ARG_BINARY_NAME}.d")
    set(output_binary "${CMAKE_BINARY_DIR}/bin/${ARG_OUTPUT_NAME}${CMAKE_EXECUTABLE_SUFFIX}")

    add_custom_command(
        OUTPUT "${output_binary}"
        COMMAND
            ${CMAKE_COMMAND} -E env ${cargo_env}
            "${RUST_CARGO}"
                rustc
                --bin ${ARG_BINARY_NAME}
                ${cargo_common_flags}
        COMMAND ${CMAKE_COMMAND} -E copy_if_different "${cargo_binary}" "${output_binary}"
        DEPENDS "${manifest_path}"
            "${workspace_dir}/Cargo.lock" "${workspace_dir}/Cargo.toml"
            "${RUST_RUSTC}" "${CMAKE_SOURCE_DIR}/rust-toolchain.toml"
        DEPFILE "${depfile}"
        COMMENT "Building Rust binary ${ARG_BINARY_NAME}"
        USES_TERMINAL
        COMMAND_EXPAND_LISTS
    )

    add_custom_target(${ARG_BINARY_NAME}-build DEPENDS "${output_binary}")

    if (ARG_OUTPUT_PATH_VAR)
        set(${ARG_OUTPUT_PATH_VAR} "${output_binary}" PARENT_SCOPE)
    endif()
endfunction()

# Shared cargo setup for import_rust_crate() and build_rust_binary().
function(_rust_crate_common_setup)
    cmake_parse_arguments(PARSE_ARGV 0 ARG "" "MANIFEST_PATH;CRATE_NAME;FFI_OUTPUT_DIR" "")

    set(manifest_path "${CMAKE_CURRENT_SOURCE_DIR}/${ARG_MANIFEST_PATH}")

    # Find the workspace Cargo.lock to track as a dependency.
    get_filename_component(workspace_dir "${manifest_path}" DIRECTORY)
    while(NOT EXISTS "${workspace_dir}/Cargo.lock")
        get_filename_component(workspace_dir "${workspace_dir}" DIRECTORY)
    endwhile()

    # Detect the Rust toolchain.
    find_program(RUST_CARGO cargo REQUIRED)
    find_program(RUST_RUSTC rustc REQUIRED)
    if (NOT DEFINED CACHE{RUST_TARGET_TRIPLE})
        execute_process(COMMAND "${RUST_RUSTC}" -vV OUTPUT_VARIABLE rustc_verbose)
        string(REGEX MATCH "host: ([^\n]+)" _ "${rustc_verbose}")
        string(STRIP "${CMAKE_MATCH_1}" host_triple)
        if (CMAKE_SYSTEM_NAME STREQUAL "Android")
            # When cross-compiling for Android, use the Android Rust target triple
            # instead of the host triple so that Cargo builds for the correct ABI.
            if (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
                set(RUST_TARGET_TRIPLE "aarch64-linux-android" CACHE INTERNAL "Rust target triple")
            elseif (CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
                set(RUST_TARGET_TRIPLE "armv7-linux-androideabi" CACHE INTERNAL "Rust target triple")
            elseif (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
                set(RUST_TARGET_TRIPLE "x86_64-linux-android" CACHE INTERNAL "Rust target triple")
            elseif (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86")
                set(RUST_TARGET_TRIPLE "i686-linux-android" CACHE INTERNAL "Rust target triple")
            else()
                message(FATAL_ERROR "import_rust_crate: unsupported Android ABI '${CMAKE_ANDROID_ARCH_ABI}'")
            endif()
        else()
            set(RUST_TARGET_TRIPLE "${host_triple}" CACHE INTERNAL "Rust target triple")
        endif()
    endif()

    # Build the uppercased and underscored variants of the target triple.
    string(REPLACE "-" "_" target_underscore "${RUST_TARGET_TRIPLE}")
    string(TOUPPER "${target_underscore}" target_upper)

    # Determine the cargo profile and output directory name.
    string(TOUPPER "${CMAKE_BUILD_TYPE}" build_type_upper)
    if (build_type_upper STREQUAL "DEBUG")
        set(cargo_profile_flag "")
        set(cargo_profile_dir "debug")
    else()
        set(cargo_profile_flag "--release")
        set(cargo_profile_dir "release")
    endif()

    set(cargo_target_dir "${CMAKE_BINARY_DIR}/cargo/build")
    set(cargo_output_dir "${cargo_target_dir}/${RUST_TARGET_TRIPLE}/${cargo_profile_dir}")

    # Build environment variables for cargo.
    if (CMAKE_SYSTEM_NAME STREQUAL "Android")
        # For Android cross-compilation, the NDK provides per-target wrapper scripts
        # (e.g. aarch64-linux-android30-clang) that already bake in --target and
        # --sysroot, so Cargo compiles and links for the correct ABI without any
        # additional flags. CMAKE_C_COMPILER is the bare NDK clang which does not
        # include those flags on its own.
        get_filename_component(ndk_toolchain_bin "${CMAKE_C_COMPILER}" DIRECTORY)
        # The NDK wrapper for armeabi-v7a uses "armv7a" (not "armv7") in the name.
        if (CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
            set(ndk_triple_prefix "armv7a-linux-androideabi")
        else()
            set(ndk_triple_prefix "${RUST_TARGET_TRIPLE}")
        endif()
        if (DEFINED ANDROID_PLATFORM_LEVEL)
            set(android_api_level "${ANDROID_PLATFORM_LEVEL}")
        elseif (DEFINED ANDROID_PLATFORM)
            string(REGEX REPLACE "^android-" "" android_api_level "${ANDROID_PLATFORM}")
        else()
            set(android_api_level "${CMAKE_SYSTEM_VERSION}")
        endif()
        set(android_rust_cc "${ndk_toolchain_bin}/${ndk_triple_prefix}${android_api_level}-clang")
        set(cargo_env
            "CC_${target_underscore}=${android_rust_cc}"
            "CXX_${target_underscore}=${android_rust_cc}++"
            "AR_${target_underscore}=${CMAKE_AR}"
            "CARGO_TARGET_${target_upper}_LINKER=${android_rust_cc}"
            "CARGO_BUILD_RUSTC=${RUST_RUSTC}"
        )
    else()
    set(cargo_env
        "CC_${target_underscore}=${CMAKE_C_COMPILER}"
        "CXX_${target_underscore}=${CMAKE_CXX_COMPILER}"
        "CARGO_BUILD_RUSTC=${RUST_RUSTC}"
    )
    endif()

    if (ARG_FFI_OUTPUT_DIR)
        list(APPEND cargo_env "FFI_OUTPUT_DIR=${ARG_FFI_OUTPUT_DIR}")
    endif()

    # On Windows, rustc invokes the linker directly with MSVC-style flags, so we must not override it with a
    # compiler driver like clang-cl. Android already configured its linker above.
    if (NOT WIN32 AND NOT CMAKE_SYSTEM_NAME STREQUAL "Android")
        list(APPEND cargo_env
            "CARGO_TARGET_${target_upper}_LINKER=${CMAKE_C_COMPILER}"
            "AR_${target_underscore}=${CMAKE_AR}"
        )
    endif()

    if (APPLE AND CMAKE_OSX_SYSROOT)
        list(APPEND cargo_env "SDKROOT=${CMAKE_OSX_SYSROOT}")
    endif()

    set(cargo_common_flags
        "--target=${RUST_TARGET_TRIPLE}"
        --package ${ARG_CRATE_NAME}
        --manifest-path "${manifest_path}"
        --target-dir "${cargo_target_dir}"
        ${cargo_profile_flag}
        --
        -Cdefault-linker-libraries=yes
        -D warnings
        --emit=dep-info
    )

    # Populate the variable names used by the public helpers below.
    set(RUST_CARGO "${RUST_CARGO}" PARENT_SCOPE)

    set(cargo_common_flags "${cargo_common_flags}" PARENT_SCOPE)
    set(cargo_env "${cargo_env}" PARENT_SCOPE)
    set(cargo_output_dir "${cargo_output_dir}" PARENT_SCOPE)
    set(manifest_path "${manifest_path}" PARENT_SCOPE)
    set(workspace_dir "${workspace_dir}" PARENT_SCOPE)
endfunction()

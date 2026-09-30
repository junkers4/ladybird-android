#!/usr/bin/env python3
# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
"""Builds the native part of the Android app from the unmodified upstream Ladybird submodule.

Steps (each can be run on its own):

    patches     Apply patches/ladybird/*.patch to the submodule (idempotent).       BLD-003
    host-tools  Build the build-time tools LibJS must run on the build machine.     BLD-004
    vcpkg       Bootstrap the vcpkg revision pinned by upstream's vcpkg.json.       BLD-005
    configure   Configure upstream's CMake project for Android with our hook.       BLD-002
    build       Build the helper executables + libladybird_android.so and stage     BLD-006
                them (and Ladybird's resources) for Gradle.
    all         Everything above, in order (default).

The staged output lands in --stage-dir (default: build/native-stage):

    jniLibs/<abi>/*.so      packaged by Gradle as native libraries
    resources/...           packaged by Gradle as assets/ladybird-resources.zip
"""

from __future__ import annotations

import argparse
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LADYBIRD_DIR = REPO_ROOT / "ladybird"
PATCHES_DIR = REPO_ROOT / "patches" / "ladybird"
NATIVE_HOOK = REPO_ROOT / "native" / "LadybirdAndroid.cmake"

# Android ABI -> (Rust target triple, CMake/NDK processor name)
ABIS = {
    "arm64-v8a": "aarch64-linux-android",
    "x86_64": "x86_64-linux-android",
}

DEFAULT_MIN_SDK = 30

# Third-party code AK needs when it is built for the host (versions match upstream's vcpkg.json overrides).
HOST_DEPENDENCIES = {
    "fmt": ("https://github.com/fmtlib/fmt", "12.2.0"),
    "simdutf": ("https://github.com/simdutf/simdutf", "v9.1.0"),
    "fast_float": ("https://github.com/fastfloat/fast_float", "v8.2.10"),
    "mimalloc": ("https://github.com/microsoft/mimalloc", "v2.2.7"),
}


def log(message: str) -> None:
    print(f"[build_native] {message}", flush=True)


def run(arguments: list, cwd: Path | None = None, env: dict | None = None) -> None:
    log("$ " + " ".join(str(argument) for argument in arguments))
    subprocess.run([str(argument) for argument in arguments], cwd=cwd, env=env, check=True)


def rust_toolchain_channel() -> str | None:
    """The Rust channel upstream pins in rust-toolchain.toml.

    Cargo runs from the build directory, outside ladybird/, where rustup would not see that file and would fall back
    to the default toolchain (which lacks the Android std), so the channel is passed via RUSTUP_TOOLCHAIN instead.
    """
    toolchain_file = LADYBIRD_DIR / "rust-toolchain.toml"
    if not toolchain_file.exists():
        return None
    match = re.search(r'^channel\s*=\s*"([^"]+)"', toolchain_file.read_text(), re.MULTILINE)
    return match.group(1) if match else None


def build_environment() -> dict:
    env = dict(os.environ)
    channel = rust_toolchain_channel()
    if channel and "RUSTUP_TOOLCHAIN" not in env:
        env["RUSTUP_TOOLCHAIN"] = channel
    return env


def git_apply(patch: Path, *extra: str) -> bool:
    result = subprocess.run(
        ["git", "-C", str(LADYBIRD_DIR), "apply", *extra, str(patch)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def patch_files() -> list[Path]:
    return sorted(PATCHES_DIR.glob("*.patch"))


def apply_patches(_: argparse.Namespace) -> None:
    """Applies every patch exactly once. A patch that neither applies nor is already applied is an error."""
    if not (LADYBIRD_DIR / "CMakeLists.txt").exists():
        raise SystemExit("The ladybird submodule is missing. Run: git submodule update --init --depth 1")

    for patch in patch_files():
        if git_apply(patch, "--reverse", "--check"):
            log(f"already applied: {patch.name}")
            continue
        if not git_apply(patch, "--check"):
            raise SystemExit(f"{patch.name} does not apply to the current ladybird submodule; it needs to be rebased")
        run(["git", "-C", LADYBIRD_DIR, "apply", patch])
        log(f"applied: {patch.name}")


def zig(*arguments: str) -> list[str]:
    # The Zig toolchain from PyPI (`pip install ziglang`) ships clang 21 (Ladybird needs >= 20) with its
    # own libc++ and musl, and cross-compiles out of the box. That makes the build-machine tools
    # reproducible regardless of the distribution's compiler.
    return [sys.executable, "-m", "ziglang", *arguments]


def fetch_host_dependencies(build_dir: Path) -> Path:
    dependencies_dir = build_dir / "host-deps"
    dependencies_dir.mkdir(parents=True, exist_ok=True)
    for name, (url, tag) in HOST_DEPENDENCIES.items():
        checkout = dependencies_dir / name
        stamp = checkout / ".ladybird-android-tag"
        if stamp.exists() and stamp.read_text().strip() == tag:
            continue
        if checkout.exists():
            shutil.rmtree(checkout)
        run(["git", "clone", "--quiet", "--depth", "1", "--branch", tag, url, checkout])
        stamp.write_text(tag + "\n")
    return dependencies_dir


def generate_ak_headers(generated_dir: Path) -> None:
    """Mimics the configure_file() calls AK and the libraries make, for the few headers the layout generator needs."""
    ak_dir = generated_dir / "AK"
    ak_dir.mkdir(parents=True, exist_ok=True)
    for template in ("Debug.h.in", "Backtrace.h.in"):
        lines = []
        for line in (LADYBIRD_DIR / "AK" / template).read_text().splitlines():
            stripped = line.lstrip("# ").strip()
            if line.lstrip().startswith("#") and stripped.startswith("cmakedefine01 "):
                indent = line[: len(line) - len(line.lstrip())]
                hashes = line.lstrip().split("cmakedefine01")[0]
                lines.append(f"{indent}{hashes}define {stripped.split()[1]} 0")
            elif line.lstrip().startswith("#cmakedefine "):
                lines.append(f"/* {line.strip()} */")
            else:
                # @VARIABLE@ substitutions only occur in blocks that are disabled when the matching
                # #cmakedefine is off, so they can simply become empty.
                lines.append(re.sub(r"@[A-Za-z0-9_]+@", "", line))
        (ak_dir / template.removesuffix(".in")).write_text("\n".join(lines) + "\n")

    # Export headers: the host tool is a single executable, so the export macros can be empty.
    for cmake_lists in (LADYBIRD_DIR / "Libraries").glob("*/CMakeLists.txt"):
        library = cmake_lists.parent.name
        text = cmake_lists.read_text()
        marker = f"ladybird_lib({library} "
        if "EXPLICIT_SYMBOL_EXPORT" not in text or marker not in text:
            continue
        fs_name = text.split(marker, 1)[1].split()[0].rstrip(")")
        macro = fs_name.upper() + "_API"
        (generated_dir / library).mkdir(parents=True, exist_ok=True)
        (generated_dir / library / "Export.h").write_text(f"#pragma once\n#define {macro}\n")


# Linux target the layout generator is compiled for, per Android ABI. The generator prints struct
# layouts, which depend on the CPU architecture (e.g. LibGC's heap region size differs on AArch64), so
# it must be built for the *target* architecture. A static musl binary runs on any Linux kernel of that
# architecture; for foreign architectures it runs under qemu user-mode emulation.
LAYOUT_GENERATOR_TARGETS = {
    "arm64-v8a": ("aarch64-linux-musl", "aarch64"),
    "x86_64": ("x86_64-linux-musl", "x86_64"),
}


def host_tools_dir(arguments: argparse.Namespace) -> Path:
    return Path(arguments.host_tools_dir) / arguments.abi


def build_host_tools(arguments: argparse.Namespace) -> None:
    build_dir = Path(arguments.build_dir)
    tools_dir = host_tools_dir(arguments)
    tools_dir.mkdir(parents=True, exist_ok=True)

    # 1. Rust tools (generate-libjs-bytecode, flapc). Their output does not depend on the build machine,
    #    so they are simply built for it.
    cargo_target_dir = build_dir / "host-cargo"
    run(
        ["cargo", "build", "--release", "--bins", "--manifest-path", LADYBIRD_DIR / "Libraries/LibJS/Flap/Cargo.toml", "--target-dir", cargo_target_dir],
        cwd=LADYBIRD_DIR,
    )
    for binary in ("flapc", "generate-libjs-bytecode"):
        shutil.copy2(cargo_target_dir / "release" / binary, tools_dir / binary)

    # 2. The interpreter layout generator (C++ against AK), built for the target architecture.
    zig_target, architecture = LAYOUT_GENERATOR_TARGETS[arguments.abi]
    dependencies = fetch_host_dependencies(build_dir)
    generated = build_dir / "host-generated"
    generate_ak_headers(generated)
    objects_dir = build_dir / "host-objects" / architecture
    objects_dir.mkdir(parents=True, exist_ok=True)

    include_flags = [
        f"-I{LADYBIRD_DIR}",
        f"-I{LADYBIRD_DIR / 'Libraries'}",
        f"-I{generated}",
        f"-I{dependencies / 'fmt/include'}",
        f"-I{dependencies / 'simdutf/include'}",
        f"-I{dependencies / 'simdutf/src'}",
        f"-I{dependencies / 'fast_float/include'}",
        f"-I{dependencies / 'mimalloc/include'}",
    ]
    common_flags = ["-target", zig_target, "-O2", "-fno-exceptions", "-DFMT_HEADER_ONLY", "-w"]

    sources = [path for path in sorted((LADYBIRD_DIR / "AK").glob("*.cpp")) if not path.name.endswith("Windows.cpp")]
    compile_units = [(source, []) for source in sources]
    # The AVX-512 kernel does not build for a generic x86-64 target; speed is irrelevant for this tool.
    compile_units.append((dependencies / "simdutf/src/simdutf.cpp", ["-DSIMDUTF_IMPLEMENTATION_ICELAKE=0"]))
    compile_units.append((LADYBIRD_DIR / "Libraries/LibJS/Interpreter/GenerateLayout.cpp", ["-Dprivate=public", "-Dprotected=public"]))

    objects = []
    for source, extra_flags in compile_units:
        obj = objects_dir / (source.parent.name + "_" + source.name + ".o")
        run(zig("c++", "-std=c++23", *common_flags, *include_flags, *extra_flags, "-c", str(source), "-o", str(obj)))
        objects.append(str(obj))

    mimalloc_object = objects_dir / "mimalloc_static.o"
    run(zig("cc", "-target", zig_target, "-O2", "-w", f"-I{dependencies / 'mimalloc/include'}", "-c", str(dependencies / "mimalloc/src/static.c"), "-o", str(mimalloc_object)))
    objects.append(str(mimalloc_object))

    layout_generator = tools_dir / "generate_interpreter_layout"
    native_binary = tools_dir / f"generate_interpreter_layout.{architecture}"
    run(zig("c++", "-target", zig_target, "-static", *objects, "-o", str(native_binary)))

    if platform.machine().lower() in (architecture, "amd64" if architecture == "x86_64" else architecture, "arm64" if architecture == "aarch64" else architecture):
        shutil.copy2(native_binary, layout_generator)
    else:
        emulator = shutil.which(f"qemu-{architecture}-static") or shutil.which(f"qemu-{architecture}")
        if not emulator:
            raise SystemExit(f"qemu-{architecture}-static is required to run the {architecture} layout generator on this machine")
        layout_generator.write_text(f'#!/bin/sh\nexec "{emulator}" "$(dirname "$0")/{native_binary.name}" "$@"\n')
        layout_generator.chmod(0o755)

    # Sanity check: the generator must run and describe the Object layout.
    output = subprocess.run([str(layout_generator)], check=True, capture_output=True, text=True).stdout
    if "OBJECT_SHAPE" not in output:
        raise SystemExit("generate_interpreter_layout produced unexpected output")
    log(f"host tools for {arguments.abi} ready in {tools_dir}")


def ensure_vcpkg(arguments: argparse.Namespace) -> Path:
    vcpkg_root = Path(arguments.vcpkg_root)
    sys.path.insert(0, str(LADYBIRD_DIR / "Meta"))
    from Utils.build_vcpkg import build_vcpkg  # noqa: E402

    build_vcpkg(vcpkg_root)
    return vcpkg_root


def find_ndk(arguments: argparse.Namespace) -> Path:
    candidates = [arguments.ndk, os.environ.get("ANDROID_NDK_HOME"), os.environ.get("ANDROID_NDK_ROOT"), os.environ.get("ANDROID_NDK")]
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk and arguments.ndk_version:
        candidates.append(str(Path(sdk) / "ndk" / arguments.ndk_version))
    for candidate in candidates:
        if candidate and (Path(candidate) / "build/cmake/android.toolchain.cmake").exists():
            return Path(candidate)
    raise SystemExit("Android NDK not found. Pass --ndk or set ANDROID_NDK_HOME.")


def native_build_dir(arguments: argparse.Namespace) -> Path:
    return Path(arguments.build_dir) / f"android-{arguments.abi}"


def configure_arguments(arguments: argparse.Namespace, ndk: Path) -> list:
    """The CMake command line for the Android build (requirements BLD-002, SEC-003)."""
    build_dir = native_build_dir(arguments)
    stage_dir = Path(arguments.stage_dir)
    return [
        "cmake",
        "-S", LADYBIRD_DIR,
        "-B", build_dir,
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        f"-DANDROID_ABI={arguments.abi}",
        f"-DANDROID_PLATFORM=android-{arguments.min_sdk}",
        f"-DANDROID_NDK={ndk}",
        "-DANDROID_STL=c++_shared",
        "-DVCPKG_TARGET_ANDROID=ON",
        f"-DCMAKE_PROJECT_ladybird_INCLUDE={NATIVE_HOOK}",
        f"-DLADYBIRD_ANDROID_STAGE_DIR={stage_dir.resolve()}",
        f"-DLADYBIRD_HOST_TOOLS_DIR={host_tools_dir(arguments).resolve()}",
        f"-DRUST_TARGET_TRIPLE={ABIS[arguments.abi]}",
        # SEC-003: no WebAssembly JIT (Cranelift) in the Android build.
        "-DENABLE_CRANELIFT_JIT=OFF",
        "-DBUILD_TESTING=OFF",
        "-DENABLE_INSTALL_HEADERS=OFF",
        # Keep CI disks from filling up with vcpkg build trees.
        "-DVCPKG_INSTALL_OPTIONS=--clean-after-build",
        f"-DLADYBIRD_CACHE_DIR={(Path(arguments.build_dir) / 'caches').resolve()}",
    ]


def configure(arguments: argparse.Namespace) -> None:
    ndk = find_ndk(arguments)
    vcpkg_root = Path(arguments.vcpkg_root)

    env = build_environment()
    env["VCPKG_ROOT"] = str(vcpkg_root)
    env["ANDROID_NDK_HOME"] = str(ndk)
    if arguments.vcpkg_binary_cache:
        Path(arguments.vcpkg_binary_cache).mkdir(parents=True, exist_ok=True)
        env["VCPKG_BINARY_SOURCES"] = f"clear;files,{Path(arguments.vcpkg_binary_cache).resolve()},readwrite"

    run(configure_arguments(arguments, ndk), env=env)


def build(arguments: argparse.Namespace) -> None:
    build_dir = native_build_dir(arguments)
    jobs = ["-j", str(arguments.jobs)] if arguments.jobs else []
    run(["cmake", "--build", build_dir, "--target", "ladybird_android_stage", *jobs], env=build_environment())
    stage = Path(arguments.stage_dir) / "jniLibs" / arguments.abi
    staged = sorted(path.name for path in stage.glob("*.so"))
    log(f"staged {len(staged)} libraries in {stage}: {', '.join(staged)}")


REQUIRED_STAGE_LIBRARIES = (
    "libladybird_android.so",
    "libc++_shared.so",
    "libCompositor.so",
    "libImageDecoder.so",
    "libMediaServer.so",
    "libRequestServer.so",
    "libWebContent.so",
    "libWebWorker.so",
)
REQUIRED_STAGE_RESOURCES = (
    "themes/Default.ini",
    "themes/Dark.ini",
    "fonts",
    "ladybird/about-pages/settings.html",
    "ladybird/about-pages/newtab.html",
)


def stage_problems(stage_dir: Path, abis: list[str]) -> list[str]:
    """Everything that is missing from a native stage directory (requirement BLD-006)."""
    problems = []
    for abi in abis:
        if abi not in ABIS:
            problems.append(f"unsupported ABI {abi}")
            continue
        jni_dir = stage_dir / "jniLibs" / abi
        for library in REQUIRED_STAGE_LIBRARIES:
            path = jni_dir / library
            if not path.is_file() or path.stat().st_size == 0:
                problems.append(f"missing {path.relative_to(stage_dir)}")
    for resource in REQUIRED_STAGE_RESOURCES:
        if not (stage_dir / "resources" / resource).exists():
            problems.append(f"missing resources/{resource}")
    return problems


def verify_stage(arguments: argparse.Namespace) -> None:
    abis = [abi.strip() for abi in (arguments.abis or arguments.abi).split(",") if abi.strip()]
    problems = stage_problems(Path(arguments.stage_dir), abis)
    if problems:
        raise SystemExit("Incomplete native stage:\n  " + "\n  ".join(problems))
    log(f"native stage for {', '.join(abis)} is complete")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", nargs="?", default="all", choices=["patches", "host-tools", "vcpkg", "configure", "build", "verify-stage", "all"])
    parser.add_argument("--abi", default="arm64-v8a", choices=sorted(ABIS))
    parser.add_argument("--min-sdk", type=int, default=DEFAULT_MIN_SDK)
    parser.add_argument("--ndk", help="Path to the Android NDK")
    parser.add_argument("--ndk-version", default="29.0.13599879", help="NDK version to look for under $ANDROID_HOME/ndk")
    parser.add_argument("--build-dir", default=str(REPO_ROOT / "build"))
    parser.add_argument("--stage-dir", default=str(REPO_ROOT / "build" / "native-stage"))
    parser.add_argument("--host-tools-dir", default=str(REPO_ROOT / "build" / "host-tools"))
    parser.add_argument("--vcpkg-root", default=str(REPO_ROOT / "build" / "vcpkg"))
    parser.add_argument("--vcpkg-binary-cache", default=os.environ.get("LADYBIRD_VCPKG_BINARY_CACHE"))
    parser.add_argument("--jobs", type=int)
    parser.add_argument("--abis", help="comma separated ABIs for verify-stage (default: --abi)")
    arguments = parser.parse_args()

    if arguments.step != "verify-stage" and platform.system() not in ("Linux", "Darwin"):
        raise SystemExit("Building the native part is supported on Linux and macOS hosts only")

    steps = {
        "patches": apply_patches,
        "host-tools": build_host_tools,
        "vcpkg": ensure_vcpkg,
        "configure": configure,
        "build": build,
        "verify-stage": verify_stage,
    }
    if arguments.step == "all":
        for step in ("patches", "host-tools", "vcpkg", "configure", "build", "verify-stage"):
            steps[step](arguments)
    else:
        steps[arguments.step](arguments)


if __name__ == "__main__":
    main()

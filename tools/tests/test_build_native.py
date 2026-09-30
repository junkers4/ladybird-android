# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
#
# Verifies: BLD-002, BLD-003, BLD-004, BLD-005, BLD-006, SEC-003

import argparse
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

import build_native  # noqa: E402


def arguments(**overrides) -> argparse.Namespace:
    values = dict(
        abi="arm64-v8a", min_sdk=30, build_dir="/tmp/b", stage_dir="/tmp/stage", host_tools_dir="/tmp/tools",
        vcpkg_root="/tmp/vcpkg", vcpkg_binary_cache=None, jobs=None, abis=None, ndk=None, ndk_version="x",
    )
    values.update(overrides)
    return argparse.Namespace(**values)


def git(repo: Path, *args: str):
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)


class PatchTests(unittest.TestCase):
    """BLD-003: patches apply exactly once and stale patches fail loudly."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.ladybird = root / "ladybird"
        self.patches = root / "patches"
        self.ladybird.mkdir()
        self.patches.mkdir()
        git(self.ladybird, "init", "-q")
        (self.ladybird / "CMakeLists.txt").write_text("one\ntwo\nthree\n")
        git(self.ladybird, "add", ".")
        git(self.ladybird, "-c", "user.email=a@b", "-c", "user.name=t", "commit", "-qm", "base")
        (self.ladybird / "CMakeLists.txt").write_text("one\nTWO\nthree\n")
        diff = subprocess.run(["git", "-C", str(self.ladybird), "diff"], check=True, capture_output=True, text=True).stdout
        git(self.ladybird, "checkout", "--", ".")
        (self.patches / "0001-test.patch").write_text("Description\n\n" + diff)
        self.patchers = [mock.patch.object(build_native, "LADYBIRD_DIR", self.ladybird), mock.patch.object(build_native, "PATCHES_DIR", self.patches)]
        for patcher in self.patchers:
            patcher.start()

    def tearDown(self):
        for patcher in self.patchers:
            patcher.stop()
        self.temp.cleanup()

    def test_applies_once(self):
        build_native.apply_patches(arguments())
        self.assertEqual((self.ladybird / "CMakeLists.txt").read_text(), "one\nTWO\nthree\n")
        build_native.apply_patches(arguments())  # second run is a no-op
        self.assertEqual((self.ladybird / "CMakeLists.txt").read_text(), "one\nTWO\nthree\n")

    def test_stale_patch_fails(self):
        (self.ladybird / "CMakeLists.txt").write_text("completely\ndifferent\n")
        with self.assertRaises(SystemExit) as context:
            build_native.apply_patches(arguments())
        self.assertIn("needs to be rebased", str(context.exception))


class RealPatchesTests(unittest.TestCase):
    def test_every_patch_names_a_requirement_and_applies(self):
        patches = build_native.patch_files()
        self.assertGreater(len(patches), 0)
        for patch in patches:
            text = patch.read_text()
            self.assertRegex(text, r"Requirement: [A-Z]+-\d{3}", patch.name)
            applies = build_native.git_apply(patch, "--check") or build_native.git_apply(patch, "--reverse", "--check")
            self.assertTrue(applies, f"{patch.name} does not apply to the submodule")


class ConfigureTests(unittest.TestCase):
    def test_hook_and_security_options(self):
        """BLD-002 (upstream extended through the project-include hook) and SEC-003 (no JIT)."""
        command = [str(part) for part in build_native.configure_arguments(arguments(), Path("/ndk"))]
        self.assertIn(f"-DCMAKE_PROJECT_ladybird_INCLUDE={REPO / 'native/LadybirdAndroid.cmake'}", command)
        self.assertIn("-DENABLE_CRANELIFT_JIT=OFF", command)
        self.assertIn("-DRUST_TARGET_TRIPLE=aarch64-linux-android", command)
        self.assertIn("-DANDROID_STL=c++_shared", command)
        self.assertIn(str(REPO / "ladybird"), command)

    def test_hook_defers_to_the_end_of_upstreams_project(self):
        hook = (REPO / "native/LadybirdAndroid.cmake").read_text()
        self.assertIn("cmake_language(DEFER CALL include", hook)
        self.assertNotIn("add_subdirectory", hook)


class HostToolsTests(unittest.TestCase):
    """BLD-004."""

    def test_every_abi_has_a_layout_generator_target(self):
        self.assertEqual(set(build_native.ABIS), set(build_native.LAYOUT_GENERATOR_TARGETS))
        self.assertEqual(build_native.LAYOUT_GENERATOR_TARGETS["arm64-v8a"][1], "aarch64")

    def test_generated_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory)
            build_native.generate_ak_headers(generated)
            debug = (generated / "AK/Debug.h").read_text()
            self.assertNotIn("cmakedefine", debug)
            self.assertRegex(debug, r"#\s+define \w+_DEBUG 0")
            self.assertNotIn("@", (generated / "AK/Backtrace.h").read_text().split("*/", 1)[1])
            self.assertIn("#define GC_API", (generated / "LibGC/Export.h").read_text())
            self.assertIn("#define JS_API", (generated / "LibJS/Export.h").read_text())

    def test_patch_uses_host_tools_when_cross_compiling(self):
        patch = (REPO / "patches/ladybird/0001-LibJS-Support-cross-compiling-with-prebuilt-host-tools.patch").read_text()
        for tool in ("generate-libjs-bytecode", "flapc", "generate_interpreter_layout"):
            self.assertIn(f"${{LADYBIRD_HOST_TOOLS_DIR}}/{tool}", patch)


class PinnedVersionsTests(unittest.TestCase):
    """BLD-005."""

    def test_host_dependencies_match_upstream_vcpkg_overrides(self):
        overrides = {entry["name"]: entry["version"].split("#")[0] for entry in json.loads((REPO / "ladybird/vcpkg.json").read_text())["overrides"]}
        names = {"fmt": "fmt", "simdutf": "simdutf", "fast_float": "fast-float", "mimalloc": "mimalloc"}
        for ours, theirs in names.items():
            self.assertEqual(build_native.HOST_DEPENDENCIES[ours][1].lstrip("v"), overrides[theirs], ours)

    def test_ndk_version_is_the_same_everywhere(self):
        parser_default = re.search(r'"--ndk-version", default="([^"]+)"', (REPO / "tools/build_native.py").read_text()).group(1)
        gradle = re.search(r'ndkVersion = "([^"]+)"', (REPO / "android/app/build.gradle.kts").read_text()).group(1)
        workflow = re.search(r"NDK_VERSION: '([^']+)'", (REPO / ".github/workflows/native.yml").read_text()).group(1)
        self.assertEqual(parser_default, gradle)
        self.assertEqual(parser_default, workflow)


class StageTests(unittest.TestCase):
    """BLD-006."""

    def make_stage(self, root: Path, skip: str | None = None):
        jni = root / "jniLibs/arm64-v8a"
        jni.mkdir(parents=True)
        for library in build_native.REQUIRED_STAGE_LIBRARIES:
            if library != skip:
                (jni / library).write_bytes(b"\x7fELF")
        for resource in build_native.REQUIRED_STAGE_RESOURCES:
            path = root / "resources" / resource
            path.parent.mkdir(parents=True, exist_ok=True)
            if "." in path.name:
                path.write_text("x")
            else:
                path.mkdir(exist_ok=True)

    def test_complete_stage(self):
        with tempfile.TemporaryDirectory() as directory:
            self.make_stage(Path(directory))
            self.assertEqual(build_native.stage_problems(Path(directory), ["arm64-v8a"]), [])

    def test_missing_helper_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            self.make_stage(Path(directory), skip="libWebContent.so")
            problems = build_native.stage_problems(Path(directory), ["arm64-v8a"])
            self.assertEqual(problems, ["missing jniLibs/arm64-v8a/libWebContent.so"])
            self.assertIn("missing jniLibs/x86_64/libladybird_android.so", build_native.stage_problems(Path(directory), ["x86_64"]))

    def test_required_helpers_match_cmake(self):
        cmake = (REPO / "native/LadybirdAndroidTargets.cmake").read_text()
        helpers = re.search(r"set\(LADYBIRD_ANDROID_HELPERS(.*?)\)", cmake, re.S).group(1).split()
        for helper in helpers:
            self.assertIn(f"lib{helper}.so", build_native.REQUIRED_STAGE_LIBRARIES)


if __name__ == "__main__":
    unittest.main()

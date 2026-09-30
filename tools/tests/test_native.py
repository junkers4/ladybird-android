# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
#
# Verifies: ARCH-002, ARCH-003, SEC-012, SEC-019

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))

import check_elf_hardening  # noqa: E402


def compiler(language: str) -> list[str] | None:
    names = ("c++", "clang++", "g++") if language == "c++" else ("cc", "clang", "gcc")
    for name in names:
        if shutil.which(name):
            return [name]
    try:
        import ziglang  # noqa: F401
        return [sys.executable, "-m", "ziglang", language]
    except ImportError:
        return None


class PixelConversionTests(unittest.TestCase):
    """ARCH-003: the BGRA -> RGBA copy used to present frames."""

    def test_native_unit_test(self):
        cxx = compiler("c++")
        if cxx is None:
            self.skipTest("no C++ compiler")
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "pixel_test"
            subprocess.run([*cxx, "-std=c++17", "-O1", str(REPO / "native/tests/PixelConversionTest.cpp"), "-o", str(binary)], check=True)
            result = subprocess.run([str(binary)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("OK", result.stdout)

    def test_presenter_uses_the_tested_conversion(self):
        source = (REPO / "native/src/AndroidWebView.cpp").read_text()
        self.assertIn('#include "PixelConversion.h"', source)
        self.assertIn("convert_row(", source)
        self.assertIn("ANativeWindow_lock", source)


class ElfHardeningTests(unittest.TestCase):
    """SEC-012: the checker CI runs on the staged payload."""

    def build(self, directory: Path, name: str, flags: list[str]) -> Path:
        cc = compiler("cc")
        if cc is None:
            self.skipTest("no C compiler")
        source = directory / "lib.c"
        source.write_text("int answer(void) { return 42; }\n")
        output = directory / name
        subprocess.run([*cc, "-shared", "-fPIC", *flags, str(source), "-o", str(output)], check=True)
        return output

    def test_hardened_library_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            library = self.build(Path(directory), "libgood.so", ["-Wl,-z,relro,-z,now", "-Wl,-z,noexecstack"])
            self.assertEqual(check_elf_hardening.inspect(library).problems, [])
            self.assertEqual(check_elf_hardening.main([str(library)]), 0)

    def test_lazy_binding_and_executable_stack_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            library = self.build(Path(directory), "libbad.so", ["-Wl,-z,norelro", "-Wl,-z,lazy", "-Wl,-z,execstack"])
            problems = check_elf_hardening.inspect(library).problems
            self.assertIn("no PT_GNU_RELRO", problems)
            self.assertIn("no BIND_NOW (lazy binding)", problems)
            self.assertIn("executable stack", problems)
            self.assertEqual(check_elf_hardening.main([directory]), 1)

    def test_rejects_non_elf(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "x.so"
            path.write_text("not elf")
            with self.assertRaises(ValueError):
                check_elf_hardening.inspect(path)
            self.assertEqual(check_elf_hardening.main([directory]), 1)

    def test_bridge_links_with_full_relro(self):
        cmake = (REPO / "native/LadybirdAndroidTargets.cmake").read_text()
        self.assertIn("-Wl,-z,relro,-z,now", cmake)


class NativeBridgeStaticTests(unittest.TestCase):
    def test_ui_thread_entry_points_only_post_to_the_engine(self):
        """ARCH-002: every JNI entry point except the documented ones hands work to the engine thread."""
        source = (REPO / "native/src/NativeEngineJNI.cpp").read_text()
        functions = re.findall(r"^static \w+ (native_\w+)\((.*?)\n\}", source, re.S | re.M)
        self.assertGreater(len(functions), 25)
        synchronous = {"native_start_engine", "native_is_engine_running"}
        for name, body in functions:
            if name in synchronous:
                continue
            self.assertTrue("with_view(" in body or "EngineThread::post(" in body, f"{name} runs LibWebView code on the UI thread")

    def test_site_isolation_is_never_disabled(self):
        """SEC-019."""
        for path in (REPO / "native/src").glob("*"):
            text = path.read_text()
            self.assertNotIn("SiteIsolationMode::Disabled", text, path.name)
            self.assertNotIn("set_site_isolation_mode", text, path.name)
            self.assertNotIn("default_site_isolation_mode", text, path.name)


if __name__ == "__main__":
    unittest.main()

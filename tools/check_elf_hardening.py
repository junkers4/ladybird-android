#!/usr/bin/env python3
# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
"""Checks that ELF files are hardened (requirement SEC-012): full RELRO (PT_GNU_RELRO + BIND_NOW),
non-executable stack (PT_GNU_STACK without PF_X) and no text relocations. 64-bit little-endian only.

Usage: check_elf_hardening.py FILE_OR_DIRECTORY...
"""

from __future__ import annotations

import struct
import sys
from dataclasses import dataclass
from pathlib import Path

PT_DYNAMIC = 2
PT_GNU_STACK = 0x6474E551
PT_GNU_RELRO = 0x6474E552
PF_X = 1
DT_NULL = 0
DT_TEXTREL = 22
DT_FLAGS = 30
DT_FLAGS_1 = 0x6FFFFFFB
DF_TEXTREL = 0x4
DF_BIND_NOW = 0x8
DF_1_NOW = 0x1


@dataclass
class Report:
    path: Path
    relro: bool = False
    bind_now: bool = False
    stack_not_executable: bool = False
    has_gnu_stack: bool = False
    text_relocations: bool = False

    @property
    def problems(self) -> list[str]:
        problems = []
        if not self.relro:
            problems.append("no PT_GNU_RELRO")
        if not self.bind_now:
            problems.append("no BIND_NOW (lazy binding)")
        if not self.has_gnu_stack or not self.stack_not_executable:
            problems.append("executable stack")
        if self.text_relocations:
            problems.append("text relocations")
        return problems


def is_elf(path: Path) -> bool:
    with path.open("rb") as file:
        return file.read(4) == b"\x7fELF"


def inspect(path: Path) -> Report:
    data = path.read_bytes()
    if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        raise ValueError(f"{path}: not a 64-bit little-endian ELF file")
    report = Report(path)
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    dynamic = None
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, p_flags = struct.unpack_from("<II", data, offset)
        p_offset, = struct.unpack_from("<Q", data, offset + 8)
        p_filesz, = struct.unpack_from("<Q", data, offset + 32)
        if p_type == PT_GNU_RELRO:
            report.relro = True
        elif p_type == PT_GNU_STACK:
            report.has_gnu_stack = True
            report.stack_not_executable = not (p_flags & PF_X)
        elif p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)
    if dynamic:
        offset, size = dynamic
        for entry in range(offset, offset + size, 16):
            tag, value = struct.unpack_from("<qQ", data, entry)
            if tag == DT_NULL:
                break
            if tag == DT_TEXTREL:
                report.text_relocations = True
            elif tag == DT_FLAGS:
                report.bind_now |= bool(value & DF_BIND_NOW)
                report.text_relocations |= bool(value & DF_TEXTREL)
            elif tag == DT_FLAGS_1:
                report.bind_now |= bool(value & DF_1_NOW)
    return report


def collect(paths: list[str]) -> list[Path]:
    files: list[Path] = []
    for argument in paths:
        path = Path(argument)
        candidates = sorted(path.rglob("*")) if path.is_dir() else [path]
        files += [candidate for candidate in candidates if candidate.is_file() and is_elf(candidate)]
    return files


def main(argv: list[str] | None = None) -> int:
    arguments = argv if argv is not None else sys.argv[1:]
    if not arguments:
        print(__doc__, file=sys.stderr)
        return 2
    files = collect(arguments)
    if not files:
        print("no ELF files found", file=sys.stderr)
        return 1
    failed = 0
    for path in files:
        problems = inspect(path).problems
        status = "OK" if not problems else "FAIL: " + ", ".join(problems)
        print(f"{path}: {status}")
        failed += bool(problems)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

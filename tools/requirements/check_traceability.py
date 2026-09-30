#!/usr/bin/env python3
# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
"""Requirements traceability checker (requirement QA-001).

Loads requirements/*.yaml, finds the tests that reference them and fails when:
  * a requirement entry is malformed or its ID is duplicated,
  * an `implemented` or `partial` requirement is not referenced by any test,
  * a test references an ID that does not exist.

Usage:
    check_traceability.py [--repo PATH] [--matrix OUTPUT.md]
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ID_PATTERN = re.compile(r"^(ARCH|BLD|SEC|ADB|UI|QA)-\d{3}$")
REQUIRED_FIELDS = ("id", "title", "description", "priority", "status", "verification")
PRIORITIES = {"must", "should", "could"}
STATUSES = {"implemented", "partial", "planned"}
VERIFICATIONS = {"unit", "instrumented", "static", "ci"}
NEEDS_TESTS = {"implemented", "partial"}

# @Requirement("SEC-004") or @Requirement("SEC-004", "SEC-005") in Kotlin/Java.
ANNOTATION_PATTERN = re.compile(r"@Requirement\(\s*((?:\"[A-Z]+-\d{3}\"\s*,?\s*)+)\)")
# "Verifies: SEC-004, SEC-005" anywhere in a comment (Python, shell, YAML, JS, C++).
VERIFIES_PATTERN = re.compile(r"Verifies:\s*((?:[A-Z]+-\d{3}\s*,?\s*)+)")
REFERENCE_ID_PATTERN = re.compile(r"[A-Z]+-\d{3}")

TEST_GLOBS = (
    "android/**/src/test/**/*.kt",
    "android/**/src/test/**/*.java",
    "android/**/src/androidTest/**/*.kt",
    "tools/**/test_*.py",
    "tests/**/*",
    ".github/workflows/*.yml",
)


@dataclass
class Requirement:
    id: str
    title: str
    status: str
    priority: str
    verification: str
    source: Path
    tests: set[str] = field(default_factory=set)


class TraceabilityError(Exception):
    pass


def load_requirements(requirements_dir: Path) -> tuple[dict[str, Requirement], list[str]]:
    requirements: dict[str, Requirement] = {}
    errors: list[str] = []
    files = sorted(requirements_dir.glob("*.yaml"))
    if not files:
        errors.append(f"no requirement files found in {requirements_dir}")
    for path in files:
        try:
            entries = yaml.safe_load(path.read_text()) or []
        except yaml.YAMLError as error:
            errors.append(f"{path.name}: invalid YAML: {error}")
            continue
        if not isinstance(entries, list):
            errors.append(f"{path.name}: top level must be a list of requirements")
            continue
        for index, entry in enumerate(entries):
            where = f"{path.name}[{index}]"
            if not isinstance(entry, dict):
                errors.append(f"{where}: entry must be a mapping")
                continue
            missing = [name for name in REQUIRED_FIELDS if not entry.get(name)]
            if missing:
                errors.append(f"{where}: missing field(s) {', '.join(missing)}")
                continue
            requirement_id = str(entry["id"])
            if not ID_PATTERN.match(requirement_id):
                errors.append(f"{where}: malformed id {requirement_id!r}")
                continue
            if entry["priority"] not in PRIORITIES:
                errors.append(f"{requirement_id}: priority must be one of {sorted(PRIORITIES)}")
            if entry["status"] not in STATUSES:
                errors.append(f"{requirement_id}: status must be one of {sorted(STATUSES)}")
            if entry["verification"] not in VERIFICATIONS:
                errors.append(f"{requirement_id}: verification must be one of {sorted(VERIFICATIONS)}")
            if requirement_id in requirements:
                errors.append(f"{requirement_id}: duplicated (also in {requirements[requirement_id].source.name})")
                continue
            requirements[requirement_id] = Requirement(
                id=requirement_id,
                title=str(entry["title"]),
                status=str(entry["status"]),
                priority=str(entry["priority"]),
                verification=str(entry["verification"]),
                source=path,
            )
    return requirements, errors


def references_in_text(text: str) -> set[str]:
    references: set[str] = set()
    for match in ANNOTATION_PATTERN.finditer(text):
        references.update(REFERENCE_ID_PATTERN.findall(match.group(1)))
    for match in VERIFIES_PATTERN.finditer(text):
        references.update(REFERENCE_ID_PATTERN.findall(match.group(1)))
    return references


def collect_references(repo: Path) -> dict[str, set[str]]:
    """Returns requirement ID -> set of test files (relative paths) that reference it."""
    references: dict[str, set[str]] = {}
    seen: set[Path] = set()
    for pattern in TEST_GLOBS:
        for path in sorted(repo.glob(pattern)):
            if path in seen or not path.is_file() or "/build/" in path.as_posix():
                continue
            seen.add(path)
            try:
                text = path.read_text(errors="replace")
            except OSError:
                continue
            for requirement_id in references_in_text(text):
                references.setdefault(requirement_id, set()).add(path.relative_to(repo).as_posix())
    return references


def check(repo: Path) -> tuple[dict[str, Requirement], list[str]]:
    requirements, errors = load_requirements(repo / "requirements")
    references = collect_references(repo)

    for requirement_id, files in sorted(references.items()):
        if requirement_id not in requirements:
            errors.append(f"unknown requirement {requirement_id} referenced by {', '.join(sorted(files))}")
            continue
        requirements[requirement_id].tests.update(files)

    for requirement in requirements.values():
        if requirement.status in NEEDS_TESTS and not requirement.tests:
            errors.append(f"{requirement.id} ({requirement.status}) has no test: {requirement.title}")

    return requirements, errors


def render_matrix(requirements: dict[str, Requirement]) -> str:
    lines = [
        "# Requirements traceability matrix",
        "",
        "Generated by `tools/requirements/check_traceability.py`.",
        "",
        "| ID | Title | Priority | Status | Verification | Tests |",
        "|----|-------|----------|--------|--------------|-------|",
    ]
    for requirement in sorted(requirements.values(), key=lambda r: (r.id.split("-")[0], r.id)):
        tests = "<br>".join(f"`{test}`" for test in sorted(requirement.tests)) or "—"
        lines.append(
            f"| {requirement.id} | {requirement.title} | {requirement.priority} | {requirement.status} | {requirement.verification} | {tests} |"
        )
    counts: dict[str, int] = {}
    for requirement in requirements.values():
        counts[requirement.status] = counts.get(requirement.status, 0) + 1
    lines += ["", "Totals: " + ", ".join(f"{status}: {count}" for status, count in sorted(counts.items())), ""]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--matrix", help="write the traceability matrix (Markdown) to this file")
    arguments = parser.parse_args(argv)

    repo = Path(arguments.repo)
    requirements, errors = check(repo)

    if arguments.matrix:
        Path(arguments.matrix).write_text(render_matrix(requirements))

    total = len(requirements)
    covered = sum(1 for requirement in requirements.values() if requirement.tests)
    planned = sum(1 for requirement in requirements.values() if requirement.status == "planned")
    print(f"{total} requirements, {covered} covered by tests, {planned} planned")

    if errors:
        print("\nTraceability errors:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("Traceability OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

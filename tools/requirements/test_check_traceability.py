# Copyright (c) 2026, the ladybird-android contributors.
# SPDX-License-Identifier: BSD-2-Clause
#
# Verifies: QA-001

import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_traceability  # noqa: E402


# Built at runtime so that this file itself does not look like a test referencing these IDs.
ANNOTATION = "@" + "Requirement"
VERIFIES = "Verifies" + ":"


def requirement(identifier: str, status: str = "implemented") -> str:
    return textwrap.dedent(f"""\
        - id: {identifier}
          title: Title of {identifier}
          description: Something testable.
          priority: must
          status: {status}
          verification: unit
        """)


class TraceabilityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.repo = Path(self.directory.name)
        (self.repo / "requirements").mkdir()
        self.test_dir = self.repo / "android/core/src/test/kotlin"
        self.test_dir.mkdir(parents=True)

    def tearDown(self):
        self.directory.cleanup()

    def write_requirements(self, text: str, name: str = "security.yaml"):
        (self.repo / "requirements" / name).write_text(text)

    def write_test(self, name: str, text: str):
        (self.test_dir / name).write_text(text)

    def test_covered_requirement_passes(self):
        self.write_requirements(requirement("SEC-001"))
        self.write_test("ATest.kt", f'{ANNOTATION}("SEC-001")\nclass ATest')
        requirements, errors = check_traceability.check(self.repo)
        self.assertEqual(errors, [])
        self.assertEqual(requirements["SEC-001"].tests, {"android/core/src/test/kotlin/ATest.kt"})

    def test_uncovered_implemented_requirement_fails(self):
        self.write_requirements(requirement("SEC-001"))
        _, errors = check_traceability.check(self.repo)
        self.assertEqual(len(errors), 1)
        self.assertIn("SEC-001", errors[0])
        self.assertIn("has no test", errors[0])

    def test_uncovered_partial_requirement_fails(self):
        self.write_requirements(requirement("SEC-001", status="partial"))
        _, errors = check_traceability.check(self.repo)
        self.assertTrue(any("SEC-001" in error for error in errors))

    def test_planned_requirement_may_be_uncovered(self):
        self.write_requirements(requirement("SEC-001", status="planned"))
        _, errors = check_traceability.check(self.repo)
        self.assertEqual(errors, [])

    def test_unknown_reference_fails(self):
        self.write_requirements(requirement("SEC-001"))
        self.write_test("ATest.kt", f'{ANNOTATION}("SEC-001", "SEC-999")\nclass ATest')
        _, errors = check_traceability.check(self.repo)
        self.assertEqual(len(errors), 1)
        self.assertIn("unknown requirement SEC-999", errors[0])

    def test_duplicate_id_fails(self):
        self.write_requirements(requirement("SEC-001"), "a.yaml")
        self.write_requirements(requirement("SEC-001"), "b.yaml")
        self.write_test("ATest.kt", f'{ANNOTATION}("SEC-001")')
        _, errors = check_traceability.check(self.repo)
        self.assertTrue(any("duplicated" in error for error in errors))

    def test_malformed_entries_fail(self):
        self.write_requirements(textwrap.dedent("""\
            - id: SECURITY-1
              title: t
              description: d
              priority: must
              status: implemented
              verification: unit
            - id: SEC-002
              title: t
              description: d
              priority: urgent
              status: done
              verification: vibes
            - id: SEC-003
              title: missing fields
            """))
        _, errors = check_traceability.check(self.repo)
        joined = "\n".join(errors)
        self.assertIn("malformed id 'SECURITY-1'", joined)
        self.assertIn("SEC-002: priority", joined)
        self.assertIn("SEC-002: status", joined)
        self.assertIn("SEC-002: verification", joined)
        self.assertIn("missing field(s)", joined)

    def test_verifies_comments_are_references(self):
        self.write_requirements(requirement("BLD-003") + requirement("BLD-004"))
        tools = self.repo / "tools"
        tools.mkdir()
        (tools / "test_patches.py").write_text(f"# {VERIFIES} BLD-003, BLD-004\n")
        requirements, errors = check_traceability.check(self.repo)
        self.assertEqual(errors, [])
        self.assertEqual(requirements["BLD-004"].tests, {"tools/test_patches.py"})

    def test_build_directories_are_ignored(self):
        self.write_requirements(requirement("SEC-001"))
        build_dir = self.repo / "android/app/build/src/test"
        build_dir.mkdir(parents=True)
        (build_dir / "Generated.kt").write_text(f'{ANNOTATION}("SEC-001")')
        _, errors = check_traceability.check(self.repo)
        self.assertEqual(len(errors), 1)

    def test_matrix_lists_every_requirement(self):
        self.write_requirements(requirement("SEC-001") + requirement("SEC-002", status="planned"))
        self.write_test("ATest.kt", f'{ANNOTATION}("SEC-001")')
        requirements, _ = check_traceability.check(self.repo)
        matrix = check_traceability.render_matrix(requirements)
        self.assertIn("| SEC-001 |", matrix)
        self.assertIn("| SEC-002 |", matrix)
        self.assertIn("planned: 1", matrix)

    def test_real_repository_is_consistent(self):
        repo = Path(__file__).resolve().parents[2]
        requirements, errors = check_traceability.check(repo)
        self.assertGreater(len(requirements), 0)
        self.assertEqual(errors, [], "\n".join(errors))


if __name__ == "__main__":
    unittest.main()

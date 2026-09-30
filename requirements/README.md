# Requirements

Every feature of the app is a requirement with a stable ID, and every requirement that is
`implemented` must be verified by at least one automated test. `tools/requirements/check_traceability.py`
enforces this in CI (requirement **QA-001**) and prints the traceability matrix.

## Files

| File | Area | ID prefix |
|------|------|-----------|
| `architecture.yaml` | process model, engine bridge | `ARCH` |
| `build.yaml` | build system, CI, packaging | `BLD` |
| `security.yaml` | security & privacy hardening | `SEC` |
| `adblock.yaml` | ad/tracker blocking ("shields") | `ADB` |
| `ui.yaml` | user interface | `UI` |
| `quality.yaml` | process requirements | `QA` |

## Format

```yaml
- id: SEC-004                 # <PREFIX>-<3 digits>, never reused
  title: HTTPS-Only mode on by default
  description: >-
    What the app must do, testable.
  rationale: Why (optional).
  priority: must | should | could
  status: implemented | partial | planned
  verification: unit | instrumented | static | ci   # how the tests check it
```

* `implemented` and `partial` requirements must have tests. `partial` means the tested part exists and the
  description says what is still missing.
* `planned` requirements may have no tests yet; they document the roadmap.

## Linking tests to requirements

* Kotlin (JUnit, Robolectric, instrumented): annotate the test class or method with
  `@Requirement("SEC-004")` (several IDs allowed).
* Python / shell / workflows / JavaScript: a comment line `Verifies: SEC-004, SEC-005`.

The checker scans `android/**/src/test*/`, `android/**/src/androidTest/`, `tools/**/test_*.py`,
`tests/**` and `.github/workflows/*.yml`.

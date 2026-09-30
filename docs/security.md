# Security and privacy model

The reference point is GrapheneOS' Vanadium. The table lists what this browser does by default; each row names the
requirement that specifies it (see `requirements/security.yaml` and `requirements/adblock.yaml`) and is covered by
tests.

| Feature | Here | Vanadium default | Requirement |
|---------|------|------------------|-------------|
| JIT compilers | none at all (LibJS interpreter, Wasm JIT not built) | JIT off by default, can be enabled | SEC-003 |
| HTTPS-Only | on, per-host exceptions after a warning | upgrades, no strict mode by default | SEC-004 |
| Encrypted DNS | DNS-over-TLS (Quad9) by default | system DNS | SEC-007 |
| Tracking parameters | stripped on navigation and sharing | not stripped | SEC-005 |
| Global Privacy Control | on | off | SEC-006 |
| Content blocking | EasyList, EasyPrivacy, anti-adblock walls, cookie/annoyance lists, regional list, YouTube rules | EasyList-based | ADB-* |
| External apps | always asked, intents sanitized | asked | SEC-008 |
| Trust store | system CAs only | system CAs only | SEC-009 |
| Backups | disabled | disabled | SEC-010 |
| Private tabs | FLAG_SECURE, incognito keyboard | incognito keyboard | SEC-013, SEC-014 |
| Incognito keyboard in normal tabs | on | off | SEC-014 |
| Native hardening | RELRO, BIND_NOW, NX stack, no TEXTREL checked in CI | yes (hardened_malloc on GrapheneOS) | SEC-012 |
| Telemetry | none, no Google services | none | SEC-001 |
| Permissions | INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS | similar | SEC-002 |
| Renderer sandbox | planned (seccomp/Landlock patch) | Chromium sandbox | SEC-020 |

## Known gaps

* **Renderer sandbox (SEC-020)** — Ladybird's seccomp policy is not yet enabled on Android. Until then the helper
  processes run with the app's own (already sandboxed) Android identity. This is the main area where Vanadium is
  still stronger.
* **Scriptlet injection (ADB-006)** — YouTube in-player ads are handled by network/cosmetic rules and a player-response
  pruning script; document-start scriptlets need upstream support.
* Ladybird itself is pre-alpha software; memory-safety bugs in the engine are expected.

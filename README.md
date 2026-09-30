# Ladybird for Android (unofficial)

An Android browser built on the **unmodified upstream [Ladybird](https://ladybird.org) engine**, which is
included as a git submodule (`ladybird/`). This repository only adds what Android needs on top:

| Path | What it is |
|------|------------|
| `ladybird/` | Upstream Ladybird, pinned to a commit (git submodule). Never edited in place. |
| `patches/ladybird/` | The (small) set of patches applied to upstream at build time. Each one names its requirement. |
| `native/` | CMake hook + JNI bridge (`libladybird_android.so`) that runs LibWebView's browser process inside the app. |
| `tools/` | Build scripts (`build_native.py`) and the requirements traceability checker. |
| `android/` | The Gradle project: the app (UI, security features) and pure-Kotlin `core` logic. |
| `requirements/` | Every feature is a requirement with an ID; every requirement must be covered by tests. |
| `docs/` | Architecture, security model and build notes. |

> Status: early development. See `docs/` and the CI workflows for what is built and tested.

## Quick start

```bash
git clone --recurse-submodules --shallow-submodules https://github.com/junkers4/ladybird-android
cd ladybird-android
python3 tools/build_native.py all --abi arm64-v8a   # needs the Android NDK, Rust, ninja, nasm, zig (pip install ziglang)
```

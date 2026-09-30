# Building

## Requirements

* Linux x86_64 host, Python 3.10+, CMake ≥ 3.25, Ninja, nasm, autoconf/automake/libtool, `qemu-user-static`
  (for arm64 builds), `pip install ziglang`
* Rust via rustup (the channel in `ladybird/rust-toolchain.toml` plus the `aarch64-linux-android` /
  `x86_64-linux-android` targets)
* Android SDK with NDK 29.0.13599879, JDK 17

## Native payload

```bash
python3 tools/build_native.py all --abi arm64-v8a
```

`all` runs these steps, which can also be run one by one:

| Step | What it does |
|------|--------------|
| `patches` | applies `patches/ladybird/*.patch` to the submodule (idempotent) |
| `host-tools` | builds LibJS' code generators for the build machine (`build/host-tools/<abi>`) |
| `vcpkg` | bootstraps upstream's pinned vcpkg |
| `configure` | configures upstream Ladybird for Android with our CMake hook |
| `build` | builds the `ladybird_android_stage` target → `build/native-stage/jniLibs/<abi>` + `resources/` |
| `verify-stage` | checks that every required library, helper and resource was staged |

Set `LADYBIRD_VCPKG_BINARY_CACHE` to reuse vcpkg binaries between builds.

## APK

```bash
cd android
./gradlew assembleRelease -Pladybird.abis=arm64-v8a -Pladybird.nativeStage=../build/native-stage
```

`-Pladybird.skipNative=true` builds the app without the engine (used by the fast CI job for lint and unit tests).

## Tests

```bash
python3 tools/requirements/check_traceability.py          # every implemented requirement has tests
python3 -m unittest $(find tools -name 'test_*.py')
node --test tests/js/*.test.mjs
cd android && ./gradlew :core:test                       # :app tests need the Android SDK
```

CI runs all of these (`.github/workflows/ci.yml`) and builds the APK with the engine (`.github/workflows/native.yml`),
uploading it as an artifact.

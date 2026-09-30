# Architecture

## Layers

```
┌──────────────────────────── APK ────────────────────────────┐
│ android/app  (Kotlin, Android UI)                           │
│   BrowserActivity ─ toolbar, tabs, menus, dialogs, settings │
│   WebContentView  ─ SurfaceView per tab, touch/keys/IME     │
│   NativeEngine    ─ JNI declarations / NativeCallbacks      │
│ android/core (pure Kotlin, unit tested on the JVM)          │
│   URL policies, settings, engine policy JSON, adblock rules │
├─────────────────────────────────────────────────────────────┤
│ libladybird_android.so  (native/src, C++)                   │
│   EngineThread ─ Core::EventLoop on its own pthread         │
│   AndroidApplication : WebView::Application                 │
│   AndroidWebView : WebView::ViewImplementation              │
├─────────────────────────────────────────────────────────────┤
│ Upstream Ladybird libraries (LibWeb, LibJS, LibWebView, …)  │
│ Helper processes: WebContent, RequestServer, ImageDecoder,  │
│   WebWorker, Compositor, MediaServer  (packaged lib<X>.so)  │
└─────────────────────────────────────────────────────────────┘
```

## Upstream as a submodule (ARCH-001)

`ladybird/` is the unmodified upstream repository. Android-specific changes that cannot live outside it are kept as
numbered patches in `patches/ladybird/` and applied by `tools/build_native.py patches`. Our own CMake code is
injected with `-DCMAKE_PROJECT_ladybird_INCLUDE=native/LadybirdAndroid.cmake`, which defers
`native/LadybirdAndroidTargets.cmake` to the end of the upstream configure. That file adds the JNI library and the
`ladybird_android_stage` target.

## Process model (ARCH-002)

Ladybird is a multi-process browser. Android only allows executing files from the app's native library directory,
so each helper executable is packaged as `lib<Name>.so` (for example `libWebContent.so`). At first start
`EngineInstaller` creates `files/ladybird/libexec/<Name>` symlinks pointing to them, unpacks the resources
(`share/Lagom`), and builds the CA bundle. LibWebView is initialised with `binary_path=<prefix>/bin`, so it finds the
helpers exactly where it finds them on Linux. Site isolation stays enabled (SEC-019).

## Threads and frames (ARCH-003)

* The engine runs on one dedicated thread with an 8 MiB stack and a `Core::EventLoop`. The UI thread never touches
  Ladybird objects; it posts closures with `deferred_invoke` through a `WeakEventLoopReference`.
* Engine → UI notifications go through static methods of `NativeCallbacks` with a small integer event type and a
  JSON payload (`BridgeProtocol.h` ↔ `core/engine/BridgeProtocol.kt`; contract tests keep them in sync).
* Painted frames (BGRA bitmaps from WebContent) are converted to RGBA and copied into the tab's `ANativeWindow`.
  Surface replacement is guarded by a mutex so `surfaceDestroyed` can block until the engine stops drawing.

## Engine policy

All privacy settings (GPC, DNS-over-TLS, autoplay, content blocker lists, custom filters, search engine, …) are
computed in `core/privacy/EnginePolicy.kt` and sent as one JSON document to `AndroidApplication::apply_policy`,
which maps them to Ladybird's own `WebView::Settings`. The content blocker is Ladybird's built-in one (Brave's
adblock-rust engine).

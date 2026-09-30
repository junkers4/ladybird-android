/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/ByteString.h>
#include <AK/Function.h>
#include <AK/Vector.h>

namespace LadybirdAndroid {

struct EngineConfiguration {
    // Directory whose libexec/ subdirectory holds symlinks to the helper executables.
    ByteString install_prefix;
    // Ladybird's runtime resources (fonts, themes, about: pages).
    ByteString resource_root;
    // Extra command line arguments for LibWebView::Application (e.g. --certificate=...).
    Vector<ByteString> arguments;
};

// Ladybird's browser process (LibWebView::Application) runs its own Core::EventLoop on a
// dedicated thread, so that the Android UI thread never blocks on engine work (ARCH-002).
// Everything that touches LibWebView objects must run on that thread; UI-side JNI entry points
// use EngineThread::post() to get there.
class EngineThread {
public:
    static bool start(EngineConfiguration);
    static bool is_running();
    static bool is_current();

    // Thread-safe. Tasks posted before the event loop exists are queued and run once it does.
    static void post(Function<void()>);
};

}

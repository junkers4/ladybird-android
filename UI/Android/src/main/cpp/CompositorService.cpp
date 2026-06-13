/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "LadybirdServiceBase.h"
#include <AK/OwnPtr.h>
#include <Compositor/ConnectionFromClient.h>
#include <LibCore/EventLoop.h>
#include <LibCore/Socket.h>
#include <LibGfx/Font/FontDatabase.h>
#include <LibGfx/Font/PathFontProvider.h>
#include <LibGfx/SkiaBackendContext.h>
#include <LibIPC/Transport.h>
#include <LibWebView/Utilities.h>

ErrorOr<int> service_main(int ipc_socket)
{
    auto& event_loop = Core::EventLoop::initialize_for_current_thread();

    auto& font_provider = static_cast<Gfx::PathFontProvider&>(Gfx::FontDatabase::the().install_system_font_provider(make<Gfx::PathFontProvider>()));
    for (auto const& path : TRY(Gfx::FontDatabase::font_directories()))
        font_provider.load_all_fonts_from_uri(TRY(String::formatted("file://{}", path)));
    font_provider.load_all_fonts_from_uri("resource://fonts"sv);

    // FIXME: Initialize the Skia GPU backend once the compositor service has an
    //        EGL context on Android. CPU compositing is correct, just slower.
    auto skia_backend_context = Gfx::SkiaBackendContext::the_main_thread_context();

    auto socket = TRY(Core::LocalSocket::adopt_fd(ipc_socket));
    [[maybe_unused]] auto client = Compositor::ConnectionFromClient::construct(
        make<IPC::Transport>(move(socket)),
        move(skia_backend_context),
        /* async_scrolling_enabled */ true);

    return event_loop.exec();
}

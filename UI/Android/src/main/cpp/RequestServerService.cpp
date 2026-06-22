/*
 * Copyright (c) 2018-2020, Andreas Kling <andreas@ladybird.org>
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "LadybirdServiceBase.h"
#include <AK/LexicalPath.h>
#include <AK/OwnPtr.h>
#include <LibCore/ArgsParser.h>
#include <LibCore/EventLoop.h>
#include <LibCore/File.h>
#include <LibCore/LocalServer.h>
#include <LibCore/System.h>
#include <LibFileSystem/FileSystem.h>
#include <LibIPC/SingleServer.h>
#include <LibTLS/TLSv12.h>
#include <LibWebView/Utilities.h>
#include <RequestServer/ConnectionFromClient.h>
#include <RequestServer/ResourceSubstitutionMap.h>
#include <RequestServer/Resolver.h>

namespace RequestServer {

// Defined here to satisfy the linker for Android shared library builds.
// main.cpp defines this for non-Android builds, but is not compiled into requestserverservice.so.
OwnPtr<ResourceSubstitutionMap> g_resource_substitution_map;

}

// Apply the network compartment proxy chosen by the app, if any. The UI writes
// the curl proxy spec (e.g. "socks5://127.0.0.1:9050" for Tor, or
// "http://127.0.0.1:4444" for I2P) into <resource_root>/network_proxy; an empty
// or missing file means a direct connection. Request.cpp reads LADYBIRD_PROXY
// per request, so setting it here routes the whole RequestServer process.
static void apply_network_proxy_from_file()
{
    auto path = ByteString::formatted("{}/network_proxy", WebView::s_ladybird_resource_root);
    auto file = Core::File::open(path, Core::File::OpenMode::Read);
    if (file.is_error())
        return;
    auto contents = file.value()->read_until_eof();
    if (contents.is_error())
        return;
    auto spec = ByteString::copy(contents.value()).trim_whitespace();
    if (!spec.is_empty())
        setenv("LADYBIRD_PROXY", spec.characters(), 1);
}

ErrorOr<int> service_main(int ipc_socket)
{
    apply_network_proxy_from_file();
    // Ad/tracker blocking runs in the renderer (LibWeb's ContentBlocker), so
    // RequestServer doesn't load a filter engine here.

    RequestServer::set_default_certificate_path(ByteString::formatted("{}/cacert.pem", WebView::s_ladybird_resource_root));

    auto& event_loop = Core::EventLoop::initialize_for_current_thread();

    auto socket = TRY(Core::LocalSocket::adopt_fd(ipc_socket));
    RequestServer::ConnectionFromClient::ConnectionMap connections;
    Optional<HTTP::DiskCache&> disk_cache;
    [[maybe_unused]] auto client = RequestServer::ConnectionFromClient::construct(
        make<IPC::Transport>(move(socket)),
        RequestServer::ConnectionFromClient::IsPrimaryConnection::Yes,
        connections,
        disk_cache);

    return event_loop.exec();
}

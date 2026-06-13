/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/Error.h>
#include <AK/Optional.h>
#include <LibIPC/TransportHandle.h>
#include <LibImageDecoderClient/Client.h>
#include <LibRequests/RequestClient.h>
#include <LibWeb/Bindings/MainThreadVM.h>
#include <LibWebView/Forward.h>
#include <LibWebView/WebContentClient.h>
#include <LibWebView/WebWorkerClient.h>

namespace WebView {

#if defined(AK_OS_ANDROID)
namespace Android {
// Installed by the Android UI layer (JNI) before WebView::Application is
// created. Each hook receives the service-side fd of a socketpair and binds
// the corresponding Android service process.
WEBVIEW_API extern Function<void(int)> bind_compositor_service;

// Android service binding is asynchronous: the socket fd is only handed to the
// service once onServiceConnected runs on the (Java) main looper. Synchronous
// IPC to a service must therefore wait for this notification or it deadlocks
// the main thread.
WEBVIEW_API extern bool compositor_service_connected;
WEBVIEW_API extern Vector<Function<void()>> on_compositor_service_connected;
WEBVIEW_API void notify_compositor_service_connected();
}
#endif

WEBVIEW_API ErrorOr<NonnullRefPtr<WebView::WebContentClient>> launch_web_content_process(u64 initial_page_id);

WEBVIEW_API ErrorOr<NonnullRefPtr<ImageDecoderClient::Client>> launch_image_decoder_process();
WEBVIEW_API ErrorOr<NonnullRefPtr<WebView::CompositorClient>> launch_compositor_process();
WEBVIEW_API ErrorOr<NonnullRefPtr<WebView::WebWorkerClient>> launch_web_worker_process(Web::Bindings::AgentType, Web::HTML::WorkerAgentId);
WEBVIEW_API ErrorOr<NonnullRefPtr<Requests::RequestClient>> launch_request_server_process();

WEBVIEW_API ErrorOr<IPC::TransportHandle> connect_new_request_server_client();
WEBVIEW_API ErrorOr<IPC::TransportHandle> connect_new_image_decoder_client();

}

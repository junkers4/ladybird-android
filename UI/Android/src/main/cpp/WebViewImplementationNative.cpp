/*
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "WebViewImplementationNative.h"
#include "JNIHelpers.h"
#include <AK/StringBuilder.h>
#include <AK/Utf16String.h>
#include <LibCore/System.h>
#include <LibGfx/Bitmap.h>
#include <LibGfx/DecodedImageFrame.h>
#include <LibGfx/Painter.h>
#include <LibWeb/Crypto/Crypto.h>
#include <LibWebView/ConsoleOutput.h>
#include <LibWebView/ViewImplementation.h>
#include <LibWebView/Application.h>
#include <LibWebView/HelperProcess.h>
#include <LibWebView/WebContentClient.h>
#include <android/bitmap.h>
#include <jni.h>
#include <string.h>

namespace Ladybird {

// The Android port drives a single page per WebView. Upstream requires page
// ids > 0 (0 is reserved), so the one-and-only page gets id 1.
static constexpr u64 INITIAL_PAGE_ID = 1;

WebViewImplementationNative::WebViewImplementationNative(jobject thiz)
    : m_java_instance(thiz)
{
    // NOTE: m_java_instance's global ref is controlled by the JNI bindings
    initialize_client(CreateNewClient::Yes);

    on_ready_to_paint = [this]() {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, invalidate_layout_method);
    };

    on_load_start = [this](URL::URL const& url, bool is_redirect) {
        JavaEnvironment env(global_vm);
        auto url_string = env.jstring_from_ak_string(url.to_string());
        env.get()->CallVoidMethod(m_java_instance, on_load_start_method, url_string, is_redirect);
        env.get()->DeleteLocalRef(url_string);
    };

    on_load_finish = [this](URL::URL const& url) {
        JavaEnvironment env(global_vm);
        auto url_string = env.jstring_from_ak_string(url.to_string());
        env.get()->CallVoidMethod(m_java_instance, on_load_finish_method, url_string);
        env.get()->DeleteLocalRef(url_string);
    };

    on_title_change = [this](Utf16String const& title) {
        JavaEnvironment env(global_vm);
        auto title_string = env.jstring_from_ak_string(title.to_utf8());
        env.get()->CallVoidMethod(m_java_instance, on_title_change_method, title_string);
        env.get()->DeleteLocalRef(title_string);
    };

    on_url_change = [this](URL::URL const& url) {
        JavaEnvironment env(global_vm);
        auto url_string = env.jstring_from_ak_string(url.to_string());
        env.get()->CallVoidMethod(m_java_instance, on_url_change_method, url_string);
        env.get()->DeleteLocalRef(url_string);
    };

    on_find_in_page = [this](size_t current_match_index, Optional<size_t> const& total_match_count) {
        JavaEnvironment env(global_vm);
        jint current = current_match_index == 0 && !total_match_count.has_value() ? 0 : static_cast<jint>(current_match_index + 1);
        jint total = total_match_count.has_value() ? static_cast<jint>(*total_match_count) : 0;
        env.get()->CallVoidMethod(m_java_instance, on_find_in_page_method, current, total);
    };

    on_console_message = [](WebView::ConsoleOutput console_output) {
        console_output.output.visit(
            [](WebView::ConsoleLog const& log) {
                if (log.level != JS::Console::LogLevel::Error && log.level != JS::Console::LogLevel::Warn)
                    return;

                StringBuilder builder;
                bool first = true;
                for (auto const& argument : log.arguments) {
                    if (!first)
                        builder.append(" "sv);
                    argument.serialize(builder);
                    first = false;
                }
                warnln("JS console (level={}): {}", static_cast<int>(log.level), builder.string_view());
            },
            [](WebView::ConsoleError const& error) {
                warnln("JS exception: {}: {}", error.name, error.message);
                if (!error.trace.is_empty()) {
                    auto const& frame = error.trace.first();
                    warnln("  at {}:{}:{} in {}",
                        frame.file.value_or("<unknown>"_string),
                        frame.line.value_or(0),
                        frame.column.value_or(0),
                        frame.function.value_or("<anonymous>"_string));
                }
            },
            [](WebView::ConsoleTrace const& trace) {
                dbgln("JS trace: {} ({} frames)", trace.label, trace.stack.size());
            });
    };

    on_link_hover = [this](URL::URL const& url) {
        JavaEnvironment env(global_vm);
        auto url_string = env.jstring_from_ak_string(url.to_string());
        env.get()->CallVoidMethod(m_java_instance, on_link_hover_method, url_string);
        env.get()->DeleteLocalRef(url_string);
    };

    on_link_unhover = [this]() {
        JavaEnvironment env(global_vm);
        env.get()->CallVoidMethod(m_java_instance, on_link_hover_method, nullptr);
    };
}

void WebViewImplementationNative::initialize_client(WebView::ViewImplementation::CreateNewClient)
{
    m_client_state = {};

    auto new_client = bind_web_content_client();

    m_client_state.client = new_client;
    m_client_state.page_index = INITIAL_PAGE_ID;
    client().async_initialize(INITIAL_PAGE_ID);

    // Presentation flows through the Compositor process; without this link
    // WebContent never delivers frames and the view stays blank. The connect
    // request is synchronous, so it must wait until the Java side has handed
    // the socket to the service (otherwise the main thread deadlocks waiting
    // for a reply the service can never send).
    auto connect_to_compositor = [client = NonnullRefPtr(*new_client)] {
        if (auto result = WebView::Application::the().connect_web_content_to_compositor(*client); result.is_error())
            dbgln("Failed to connect WebContent to compositor: {}", result.error());
    };
    if (WebView::Android::compositor_service_connected)
        connect_to_compositor();
    else
        WebView::Android::on_compositor_service_connected.append(move(connect_to_compositor));
    on_web_content_crashed = [this] {
        warnln("WebContent crashed! Attempting to respawn the WebContent client.");
        // Re-bind a fresh WebContent service and re-emit viewport/zoom so the
        // browser tab keeps working instead of staying frozen on a dead client.
        initialize_client(WebView::ViewImplementation::CreateNewClient::Yes);
        auto serialized = m_url.serialize();
        if (!serialized.is_empty())
            load(m_url);
    };

    m_client_state.client_handle = Web::Crypto::generate_random_uuid();
    client().async_set_window_handle(INITIAL_PAGE_ID, m_client_state.client_handle);

    client().async_set_viewport(INITIAL_PAGE_ID, viewport_size(), m_device_pixel_ratio, Web::ViewportIsFullscreen::No);
    client().async_set_zoom_level(INITIAL_PAGE_ID, m_zoom_level);

    set_system_visibility_state(Web::HTML::VisibilityState::Visible);

    // FIXME: update_palette, update system fonts
}

void WebViewImplementationNative::paint_into_bitmap(void* android_bitmap_raw, AndroidBitmapInfo const& info)
{
    // Software bitmaps only for now!
    VERIFY((info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) == 0);

    RefPtr<Gfx::Bitmap> bitmap;
    Gfx::IntSize painted_size;
    if (m_client_state.has_usable_bitmap && m_client_state.front_bitmap.shared_image_buffer) {
        bitmap = m_client_state.front_bitmap.shared_image_buffer->bitmap();
        painted_size = m_client_state.front_bitmap.last_painted_size.to_type<int>();
    } else if (m_backup_shared_image_buffer) {
        bitmap = m_backup_shared_image_buffer->bitmap();
        painted_size = m_backup_bitmap_size.to_type<int>();
    }

    int const surface_w = static_cast<int>(info.width);
    int const surface_h = static_cast<int>(info.height);
    auto* dst_base = reinterpret_cast<u8*>(android_bitmap_raw);

    // Fast path: the engine paints BGRA8888, the Android surface is RGBA8888.
    // Instead of going through Gfx::Painter (a generic per-pixel compositing
    // path that measured ~150ms/frame on a 1080x2424 surface and was the single
    // biggest scroll-jank source), do a tight per-row red/blue swizzle. This is
    // an order of magnitude faster and runs comfortably inside a frame budget.
    int covered_w = 0;
    int covered_h = 0;
    if (bitmap) {
        covered_w = min(painted_size.is_empty() ? bitmap->width() : painted_size.width(), surface_w);
        covered_h = min(painted_size.is_empty() ? bitmap->height() : painted_size.height(), surface_h);
        covered_w = max(covered_w, 0);
        covered_h = max(covered_h, 0);

        bool const src_is_bgra = bitmap->format() == Gfx::BitmapFormat::BGRA8888 || bitmap->format() == Gfx::BitmapFormat::BGRx8888;
        for (int y = 0; y < covered_h; ++y) {
            auto const* src = bitmap->scanline(y);
            auto* dst = reinterpret_cast<u32*>(dst_base + static_cast<size_t>(y) * info.stride);
            if (src_is_bgra) {
                for (int x = 0; x < covered_w; ++x) {
                    u32 p = src[x];
                    // src (BGRA8888 storage) = A<<24 | R<<16 | G<<8 | B
                    // dst (RGBA8888 storage) = A<<24 | B<<16 | G<<8 | R
                    dst[x] = (p & 0xFF00FF00u) | ((p & 0x00FF0000u) >> 16) | ((p & 0x000000FFu) << 16);
                }
            } else {
                memcpy(dst, src, static_cast<size_t>(covered_w) * sizeof(u32));
            }
        }
    }

    // White-fill any uncovered margin so partial-source renders don't show the
    // gray Android window background. Common case (content covers surface) writes
    // nothing here.
    for (int y = 0; y < surface_h; ++y) {
        auto* dst = reinterpret_cast<u32*>(dst_base + static_cast<size_t>(y) * info.stride);
        int fill_start = (y < covered_h) ? covered_w : 0;
        for (int x = fill_start; x < surface_w; ++x)
            dst[x] = 0xFFFFFFFFu;
    }
}

// handle_resize() registers the page's compositor context, which performs a
// synchronous IPC request to the Compositor service. Until the Java side has
// delivered the socket to the service (onServiceConnected on the main looper),
// that request would deadlock the main thread, so defer it.
void WebViewImplementationNative::handle_resize_when_compositor_ready()
{
    if (WebView::Android::compositor_service_connected) {
        handle_resize();
        return;
    }

    // Keep WebContent's viewport current in the meantime; this part is async.
    client().async_set_viewport(page_id(), viewport_size(), m_device_pixel_ratio, Web::ViewportIsFullscreen::No);

    if (m_pending_compositor_resize)
        return;
    m_pending_compositor_resize = true;
    WebView::Android::on_compositor_service_connected.append([this] {
        m_pending_compositor_resize = false;
        // Connects WebContent to the compositor (if not already) and registers
        // this page's compositor context.
        handle_resize();
        // Frames WebContent presented before the context registration were
        // dropped by the compositor; ask it to re-register and re-present.
        client().async_compositor_process_reconnected();
    });
}

void WebViewImplementationNative::set_viewport_geometry(int w, int h)
{
    m_viewport_size = { w, h };
    handle_resize_when_compositor_ready();
}

void WebViewImplementationNative::set_device_pixel_ratio(double f)
{
    m_device_pixel_ratio = f;
    handle_resize_when_compositor_ready();
}

void WebViewImplementationNative::set_zoom_level(double f)
{
    m_zoom_level = f;
    client().async_set_zoom_level(INITIAL_PAGE_ID, m_zoom_level);
}

void WebViewImplementationNative::mouse_event(Web::MouseEvent::Type event_type, float x, float y, float raw_x, float raw_y)
{
    Gfx::IntPoint position = { x, y };
    Gfx::IntPoint screen_position = { raw_x, raw_y };
    auto button = (event_type == Web::MouseEvent::Type::MouseMove)
        ? Web::UIEvents::MouseButton::None
        : Web::UIEvents::MouseButton::Primary;
    auto buttons = (event_type == Web::MouseEvent::Type::MouseUp)
        ? Web::UIEvents::MouseButton::None
        : Web::UIEvents::MouseButton::Primary;
    auto event = Web::MouseEvent {
        event_type,
        position.to_type<Web::DevicePixels>(),
        screen_position.to_type<Web::DevicePixels>(),
        button,
        buttons,
        Web::UIEvents::KeyModifier::Mod_None,
        0,
        0,
        1,
        nullptr
    };

    enqueue_input_event(move(event));
}

void WebViewImplementationNative::wheel_event(float x, float y, float raw_x, float raw_y, int wheel_delta_x, int wheel_delta_y)
{
    Gfx::IntPoint position = { x, y };
    Gfx::IntPoint screen_position = { raw_x, raw_y };
    auto event = Web::MouseEvent {
        Web::MouseEvent::Type::MouseWheel,
        position.to_type<Web::DevicePixels>(),
        screen_position.to_type<Web::DevicePixels>(),
        Web::UIEvents::MouseButton::None,
        Web::UIEvents::MouseButton::None,
        Web::UIEvents::KeyModifier::Mod_None,
        static_cast<double>(wheel_delta_x),
        static_cast<double>(wheel_delta_y),
        0,
        nullptr
    };

    enqueue_input_event(move(event));
}

NonnullRefPtr<WebView::WebContentClient> WebViewImplementationNative::bind_web_content_client()
{
    JavaEnvironment env(global_vm);

    int socket_fds[2] {};
    MUST(Core::System::socketpair(AF_LOCAL, SOCK_STREAM, 0, socket_fds));

    int ui_fd = socket_fds[0];
    int wc_fd = socket_fds[1];

    // NOTE: The java object takes ownership of the socket fds
    env.get()->CallVoidMethod(m_java_instance, bind_webcontent_method, wc_fd);

    auto socket = MUST(Core::LocalSocket::adopt_fd(ui_fd));
    MUST(socket->set_blocking(true));

    auto new_client = make_ref_counted<WebView::WebContentClient>(make<IPC::Transport>(move(socket)), INITIAL_PAGE_ID);
    new_client->register_view(INITIAL_PAGE_ID, *this);

    return new_client;
}

ErrorOr<WebView::ViewImplementation::WorkerConnectHandles> WebViewImplementationNative::create_worker_connect_handles()
{
    JavaEnvironment env(global_vm);

    // Create socket pair for WebWorker <-> WebContent IPC
    int worker_fds[2] {};
    TRY(Core::System::socketpair(AF_LOCAL, SOCK_STREAM, 0, worker_fds));

    // Create socket pair for WebWorker <-> RequestServer IPC
    int rs_fds[2] {};
    if (auto result = Core::System::socketpair(AF_LOCAL, SOCK_STREAM, 0, rs_fds); result.is_error()) {
        close(worker_fds[0]);
        close(worker_fds[1]);
        return result.release_error();
    }

    // Create socket pair for WebWorker <-> ImageDecoder IPC
    int id_fds[2] {};
    if (auto result = Core::System::socketpair(AF_LOCAL, SOCK_STREAM, 0, id_fds); result.is_error()) {
        close(worker_fds[0]);
        close(worker_fds[1]);
        close(rs_fds[0]);
        close(rs_fds[1]);
        return result.release_error();
    }

    // Bind the three Android services with their respective service-side fds.
    // The bindings are asynchronous; the services will connect and start event loops
    // on the service-side fds once Android delivers the bound service callback.
    env.get()->CallVoidMethod(m_java_instance, bind_request_server_for_worker_method, rs_fds[1]);
    env.get()->CallVoidMethod(m_java_instance, bind_image_decoder_for_worker_method, id_fds[1]);
    env.get()->CallVoidMethod(m_java_instance, bind_web_worker_method, worker_fds[1]);

    return WorkerConnectHandles {
        .worker_fd = worker_fds[0],
        .request_server_fd = rs_fds[0],
        .image_decoder_fd = id_fds[0],
    };
}

}

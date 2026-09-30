/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "AndroidWebView.h"
#include "AndroidApplication.h"
#include "BridgeProtocol.h"
#include "JNIBridge.h"
#include "SerializeMenu.h"
#include <AK/Array.h>
#include <AK/Base64.h>
#include <AK/HashMap.h>
#include <AK/JsonArray.h>
#include <AK/JsonObject.h>
#include <AK/LexicalPath.h>
#include <LibCompositing/InputEvent.h>
#include <LibGfx/Bitmap.h>
#include <LibGfx/ImageFormats/PNGWriter.h>
#include <LibGfx/SharedImageBuffer.h>
#include <LibGfx/SystemTheme.h>
#include <LibWebCommon/HTML/ColorPickerUpdateState.h>
#include <LibWebCommon/HTML/FileFilter.h>
#include <LibWebCommon/HTML/SelectItem.h>
#include <LibWebCommon/HTML/SelectedFile.h>
#include <LibWebCommon/WebView/Utilities.h>
#include <LibWebView/Menu.h>
#include <LibWebView/WebContentClient.h>
#include <LibWebView/WebContentPage.h>
#include <android/hardware_buffer.h>

namespace LadybirdAndroid {

// --- SurfaceSlot / SurfaceRegistry ------------------------------------------------------------

SurfaceSlot::~SurfaceSlot()
{
    detach();
}

void SurfaceSlot::attach(ANativeWindow* window)
{
    pthread_mutex_lock(&m_mutex);
    if (m_window)
        ANativeWindow_release(m_window);
    // The window comes from ANativeWindow_fromSurface(), which already acquired a reference for us.
    m_window = window;
    pthread_mutex_unlock(&m_mutex);
}

void SurfaceSlot::detach()
{
    pthread_mutex_lock(&m_mutex);
    if (m_window) {
        ANativeWindow_release(m_window);
        m_window = nullptr;
    }
    pthread_mutex_unlock(&m_mutex);
}

static pthread_mutex_t s_registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static HashMap<u64, NonnullRefPtr<SurfaceSlot>>& surface_registry()
{
    static auto* registry = new HashMap<u64, NonnullRefPtr<SurfaceSlot>>;
    return *registry;
}

NonnullRefPtr<SurfaceSlot> SurfaceRegistry::ensure(u64 view_id)
{
    pthread_mutex_lock(&s_registry_mutex);
    auto slot = surface_registry().ensure(view_id, [] { return SurfaceSlot::create(); });
    pthread_mutex_unlock(&s_registry_mutex);
    return slot;
}

RefPtr<SurfaceSlot> SurfaceRegistry::find(u64 view_id)
{
    pthread_mutex_lock(&s_registry_mutex);
    RefPtr<SurfaceSlot> slot;
    if (auto it = surface_registry().find(view_id); it != surface_registry().end())
        slot = it->value;
    pthread_mutex_unlock(&s_registry_mutex);
    return slot;
}

void SurfaceRegistry::remove(u64 view_id)
{
    pthread_mutex_lock(&s_registry_mutex);
    surface_registry().remove(view_id);
    pthread_mutex_unlock(&s_registry_mutex);
}

// --- Helpers -----------------------------------------------------------------------------------

static ByteString url_to_byte_string(URL::URL const& url)
{
    return url.to_byte_string();
}

static JsonArray serialize_select_items(Vector<Web::HTML::SelectItem> const& items)
{
    JsonArray array;
    auto serialize_option = [](Web::HTML::SelectItemOption const& option) {
        JsonObject object;
        object.set("type"sv, "option"sv);
        object.set("id"sv, option.id);
        object.set("label"sv, option.label.to_utf8_but_should_be_ported_to_utf16());
        object.set("selected"sv, option.selected);
        object.set("disabled"sv, option.disabled);
        return object;
    };
    for (auto const& item : items) {
        item.visit(
            [&](Web::HTML::SelectItemOption const& option) {
                array.must_append(serialize_option(option));
            },
            [&](Web::HTML::SelectItemOptionGroup const& group) {
                JsonObject object;
                object.set("type"sv, "group"sv);
                object.set("label"sv, group.label.to_utf8_but_should_be_ported_to_utf16());
                JsonArray children;
                for (auto const& option : group.items)
                    children.must_append(serialize_option(option));
                object.set("items"sv, move(children));
                array.must_append(move(object));
            },
            [&](Web::HTML::SelectItemSeparator const&) {
                JsonObject object;
                object.set("type"sv, "separator"sv);
                array.must_append(move(object));
            });
    }
    return array;
}

// --- AndroidWebView ----------------------------------------------------------------------------

Optional<Core::AnonymousBuffer> AndroidWebView::theme_buffer(bool dark)
{
    static Optional<Core::AnonymousBuffer> s_light_theme;
    static Optional<Core::AnonymousBuffer> s_dark_theme;
    auto& cached = dark ? s_dark_theme : s_light_theme;
    if (!cached.has_value()) {
        auto path = LexicalPath::join(WebView::s_ladybird_resource_root, "themes"sv, dark ? "Dark.ini"sv : "Default.ini"sv);
        auto theme = Gfx::load_system_theme(path.string());
        if (theme.is_error()) {
            warnln("ladybird-android: unable to load theme {}: {}", path, theme.error());
            return {};
        }
        cached = theme.release_value();
    }
    return cached;
}

NonnullOwnPtr<AndroidWebView> AndroidWebView::create(u64 view_id, WebView::IsPrivate is_private)
{
    auto view = adopt_own(*new AndroidWebView(view_id, is_private));
    view->initialize_client(CreateNewClient::Yes);
    return view;
}

NonnullOwnPtr<AndroidWebView> AndroidWebView::create_child(u64 view_id, AndroidWebView& parent, WebView::WebContentClient& page_process, Compositing::PageId page_index)
{
    // A child (e.g. window.open()) shares the WebContent process hosting its page.
    auto view = adopt_own(*new AndroidWebView(view_id, parent.is_private()));
    view->m_viewport_size = parent.m_viewport_size;
    view->m_device_pixel_ratio = parent.m_device_pixel_ratio;
    view->m_dark_mode = parent.m_dark_mode;
    page_process.register_view(page_index, *view);
    view->initialize_client(CreateNewClient::No);
    return view;
}

AndroidWebView::AndroidWebView(u64 view_id, WebView::IsPrivate is_private)
    : WebView::ViewImplementation(is_private)
    , m_view_id(view_id)
    , m_surface(SurfaceRegistry::ensure(view_id))
{
    install_callbacks();
    observe_navigation_actions();
    set_system_visibility_state(Web::HTML::VisibilityState::Hidden);
}

AndroidWebView::~AndroidWebView()
{
    SurfaceRegistry::remove(m_view_id);
}

void AndroidWebView::prepare_page_for_tab(WebView::WebContentPage& page)
{
    ViewImplementation::prepare_page_for_tab(page);

    if (auto theme = theme_buffer(m_dark_mode); theme.has_value())
        page.async_update_system_theme(*theme);
    page.async_set_window_size(viewport_size());
    Array<Compositing::DevicePixelRect, 1> screen_rects { screen_rect() };
    page.async_update_screen_rects(screen_rects.span(), 0);
}

void AndroidWebView::set_viewport(int width, int height, float density)
{
    m_viewport_size = Gfx::IntSize { max(width, 1), max(height, 1) }.to_type<Compositing::DevicePixels>();
    m_device_pixel_ratio = density;

    if (!has_display_page())
        return;
    page().async_set_window_size(viewport_size());
    Array<Compositing::DevicePixelRect, 1> screen_rects { screen_rect() };
    page().async_update_screen_rects(screen_rects.span(), 0);
    handle_resize();
    present();
}

void AndroidWebView::set_visible(bool visible)
{
    set_system_visibility_state(visible ? Web::HTML::VisibilityState::Visible : Web::HTML::VisibilityState::Hidden);
    set_has_system_focus(visible);
    if (visible)
        present();
}

void AndroidWebView::set_dark_mode(bool dark)
{
    m_dark_mode = dark;
    set_preferred_color_scheme(dark ? Web::CSS::PreferredColorScheme::Dark : Web::CSS::PreferredColorScheme::Light);
    if (!has_display_page())
        return;
    if (auto theme = theme_buffer(dark); theme.has_value())
        page().async_update_system_theme(*theme);
}

// Copies the most recent frame into the ANativeWindow. Frames are BGRA in Ladybird and RGBA on
// Android, so the copy also swizzles.
void AndroidWebView::present()
{
    RefPtr<Gfx::Bitmap> bitmap;
    Gfx::IntSize painted_size;
    if (m_client_state.has_usable_bitmap && m_client_state.front_bitmap.shared_image_buffer) {
        bitmap = m_client_state.front_bitmap.shared_image_buffer->bitmap_if_present();
        painted_size = m_client_state.front_bitmap.last_painted_size.to_type<int>();
    }

    auto width = m_viewport_size.width().value();
    auto height = m_viewport_size.height().value();
    u32 background = m_dark_mode ? 0xFF121212 : 0xFFFFFFFF;

    m_surface->with_window([&](ANativeWindow& window) {
        if (ANativeWindow_setBuffersGeometry(&window, width, height, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) != 0)
            return false;

        ANativeWindow_Buffer buffer {};
        if (ANativeWindow_lock(&window, &buffer, nullptr) != 0)
            return false;

        auto* destination = static_cast<u32*>(buffer.bits);
        auto destination_width = buffer.width;
        auto destination_height = buffer.height;

        int copy_width = 0;
        int copy_height = 0;
        bool swizzle = true;
        if (bitmap) {
            copy_width = min(min(painted_size.width(), bitmap->width()), destination_width);
            copy_height = min(min(painted_size.height(), bitmap->height()), destination_height);
            auto format = bitmap->format();
            swizzle = format == Gfx::BitmapFormat::BGRA8888 || format == Gfx::BitmapFormat::BGRx8888;
        }

        for (int y = 0; y < destination_height; ++y) {
            auto* destination_row = destination + static_cast<size_t>(y) * buffer.stride;
            int x = 0;
            if (y < copy_height) {
                auto const* source_row = bitmap->scanline(y);
                if (swizzle) {
                    for (; x < copy_width; ++x) {
                        auto pixel = source_row[x];
                        // ARGB (0xAARRGGBB, BGRA in memory) -> ABGR (RGBA in memory), forced opaque.
                        destination_row[x] = 0xFF000000 | ((pixel & 0x00FF0000) >> 16) | (pixel & 0x0000FF00) | ((pixel & 0x000000FF) << 16);
                    }
                } else {
                    __builtin_memcpy(destination_row, source_row, static_cast<size_t>(copy_width) * sizeof(u32));
                    x = copy_width;
                }
            }
            for (; x < destination_width; ++x)
                destination_row[x] = background;
        }

        ANativeWindow_unlockAndPost(&window);
        return true;
    });
}

void AndroidWebView::mouse_event(int type, float x, float y, int button, int buttons, int modifiers, int click_count)
{
    Compositing::MouseEvent::Type event_type;
    switch (type) {
    case MouseEventDown:
        event_type = Compositing::MouseEvent::Type::MouseDown;
        break;
    case MouseEventUp:
        event_type = Compositing::MouseEvent::Type::MouseUp;
        break;
    case MouseEventMove:
        event_type = Compositing::MouseEvent::Type::MouseMove;
        break;
    case MouseEventLeave:
        event_type = Compositing::MouseEvent::Type::MouseLeave;
        break;
    default:
        return;
    }

    auto mouse_button = static_cast<Compositing::MouseButton>(button);
    if (mouse_button == Compositing::MouseButton::None && (event_type == Compositing::MouseEvent::Type::MouseDown || event_type == Compositing::MouseEvent::Type::MouseUp))
        return;

    auto position = Gfx::IntPoint { static_cast<int>(x), static_cast<int>(y) }.to_type<Compositing::DevicePixels>();
    enqueue_input_event(Compositing::MouseEvent {
        event_type,
        position,
        position,
        mouse_button,
        static_cast<Compositing::MouseButton>(buttons),
        static_cast<Compositing::KeyModifier>(modifiers),
        0,
        0,
        Compositing::WheelDeltaPrecision::Discrete,
        Compositing::ScrollGesturePhase::None,
        click_count,
        nullptr,
    });
}

void AndroidWebView::scroll_event(float x, float y, float delta_x, float delta_y, int phase)
{
    auto gesture_phase = Compositing::ScrollGesturePhase::None;
    switch (phase) {
    case ScrollPhaseOngoing:
        gesture_phase = Compositing::ScrollGesturePhase::Ongoing;
        break;
    case ScrollPhaseMomentum:
        gesture_phase = Compositing::ScrollGesturePhase::Momentum;
        break;
    case ScrollPhaseEnded:
        gesture_phase = Compositing::ScrollGesturePhase::Ended;
        break;
    default:
        break;
    }

    auto position = Gfx::IntPoint { static_cast<int>(x), static_cast<int>(y) }.to_type<Compositing::DevicePixels>();
    enqueue_input_event(Compositing::MouseEvent {
        Compositing::MouseEvent::Type::MouseWheel,
        position,
        position,
        Compositing::MouseButton::None,
        Compositing::MouseButton::None,
        Compositing::KeyModifier::Mod_None,
        delta_x,
        delta_y,
        Compositing::WheelDeltaPrecision::Precise,
        gesture_phase,
        0,
        nullptr,
    });
}

void AndroidWebView::pinch_event(float x, float y, float scale_delta)
{
    auto position = Gfx::IntPoint { static_cast<int>(x), static_cast<int>(y) }.to_type<Compositing::DevicePixels>();
    enqueue_input_event(Compositing::PinchEvent {
        position,
        Compositing::KeyModifier::Mod_None,
        scale_delta,
    });
}

void AndroidWebView::key_event(int type, int key_code, int modifiers, u32 code_point, bool repeat)
{
    auto event_type = type == KeyEventUp ? Compositing::KeyEvent::Type::KeyUp : Compositing::KeyEvent::Type::KeyDown;
    auto should_insert_text = event_type == Compositing::KeyEvent::Type::KeyDown && code_point != 0;
    enqueue_input_event(Compositing::KeyEvent {
        event_type,
        static_cast<Compositing::KeyCode>(key_code),
        static_cast<Compositing::KeyModifier>(modifiers),
        code_point,
        repeat,
        should_insert_text,
        nullptr,
    });
}

void AndroidWebView::dialog_closed(int kind, bool accepted, Optional<Utf16String> text)
{
    switch (kind) {
    case DialogKindAlert:
        alert_closed();
        break;
    case DialogKindConfirm:
        confirm_closed(accepted);
        break;
    case DialogKindPrompt:
        prompt_closed(accepted ? move(text) : Optional<Utf16String> {});
        break;
    default:
        break;
    }
}

void AndroidWebView::activate_context_menu_item(size_t action_index)
{
    if (!m_active_context_menu)
        return;
    auto menu = m_active_context_menu.release_nonnull();
    activate_menu_action_at(*menu, action_index);
}

void AndroidWebView::notify_navigation_state()
{
    NativeCallbacks::view_event(m_view_id, ViewEventNavigationStateChanged, {}, {}, navigate_back_action().enabled() ? 1 : 0, navigate_forward_action().enabled() ? 1 : 0);
}

void AndroidWebView::observe_navigation_actions()
{
    struct Observer final : public WebView::Action::Observer {
        explicit Observer(AndroidWebView& view)
            : view(view)
        {
        }
        virtual void on_enabled_state_changed(WebView::Action&) override { view.notify_navigation_state(); }
        AndroidWebView& view;
    };

    navigate_back_action().add_observer(make<Observer>(*this));
    navigate_forward_action().add_observer(make<Observer>(*this));
}

void AndroidWebView::install_callbacks()
{
    on_ready_to_paint = [this] {
        present();
    };

    on_load_start = [this] {
        auto url_string = url_to_byte_string(url());
        NativeCallbacks::view_event(m_view_id, ViewEventLoadStart, url_string.view());
    };

    on_load_finish = [this](URL::URL const& url) {
        auto url_string = url_to_byte_string(url);
        NativeCallbacks::view_event(m_view_id, ViewEventLoadFinish, url_string.view());
    };

    on_url_change = [this](URL::URL const& url) {
        auto url_string = url_to_byte_string(url);
        NativeCallbacks::view_event(m_view_id, ViewEventUrlChanged, url_string.view());
    };

    on_title_change = [this](Utf16String const& title) {
        NativeCallbacks::view_event_utf16(m_view_id, ViewEventTitleChanged, title.utf16_view());
    };

    on_loading_state_change = [this](bool is_loading) {
        NativeCallbacks::view_event(m_view_id, ViewEventLoadingStateChanged, {}, {}, is_loading ? 1 : 0);
    };

    on_favicon_change = [this](Optional<Gfx::Bitmap const&> favicon) {
        if (!favicon.has_value()) {
            NativeCallbacks::view_event(m_view_id, ViewEventFaviconChanged);
            return;
        }
        auto png = Gfx::PNGWriter::encode(*favicon);
        if (png.is_error())
            return;
        auto base64 = encode_base64(png.value());
        if (base64.is_error())
            return;
        NativeCallbacks::view_event(m_view_id, ViewEventFaviconChanged, base64.value().bytes_as_string_view());
    };

    on_request_alert = [this](Utf16String const& message) {
        NativeCallbacks::view_event_utf16(m_view_id, ViewEventRequestAlert, message.utf16_view());
    };

    on_request_confirm = [this](Utf16String const& message) {
        NativeCallbacks::view_event_utf16(m_view_id, ViewEventRequestConfirm, message.utf16_view());
    };

    on_request_prompt = [this](Utf16String const& message, Utf16String const& default_value) {
        auto message_utf8 = message.to_utf8_but_should_be_ported_to_utf16();
        auto default_utf8 = default_value.to_utf8_but_should_be_ported_to_utf16();
        NativeCallbacks::view_event(m_view_id, ViewEventRequestPrompt, message_utf8.bytes_as_string_view(), default_utf8.bytes_as_string_view());
    };

    on_request_accept_dialog = [this] {
        NativeCallbacks::view_event(m_view_id, ViewEventRequestAcceptDialog);
    };

    on_request_dismiss_dialog = [this] {
        NativeCallbacks::view_event(m_view_id, ViewEventRequestDismissDialog);
    };

    on_request_select_dropdown = [this](Gfx::IntPoint content_position, i32 minimum_width, Vector<Web::HTML::SelectItem> items) {
        (void)minimum_width;
        auto json = serialize_select_items(items).serialized();
        NativeCallbacks::view_event(m_view_id, ViewEventRequestSelectDropdown, json.bytes_as_string_view(), {}, content_position.x(), content_position.y());
    };

    on_request_file_picker = [this](Web::HTML::FileFilter const&, Web::HTML::AllowMultipleFiles allow_multiple_files) {
        NativeCallbacks::view_event(m_view_id, ViewEventRequestFilePicker, {}, {}, allow_multiple_files == Web::HTML::AllowMultipleFiles::Yes ? 1 : 0);
    };

    on_request_color_picker = [this](Color) {
        // FIXME: Offer a native color picker. Until then, report the picker as closed so the page does not wait forever.
        color_picker_update({}, Web::HTML::ColorPickerUpdateState::Closed);
    };

    on_find_in_page = [this](size_t current_match_index, Optional<size_t> const& total_match_count) {
        NativeCallbacks::view_event(m_view_id, ViewEventFindInPageResult, {}, {}, static_cast<i64>(current_match_index), total_match_count.has_value() ? static_cast<i64>(*total_match_count) : -1);
    };

    on_fullscreen_window = [this] {
        set_is_fullscreen(Web::ViewportIsFullscreen::Yes);
        NativeCallbacks::view_event(m_view_id, ViewEventEnterFullscreen);
    };

    on_exit_fullscreen_window = [this] {
        set_is_fullscreen(Web::ViewportIsFullscreen::No);
        NativeCallbacks::view_event(m_view_id, ViewEventExitFullscreen);
    };

    on_audio_play_state_changed = [this](Web::HTML::AudioPlayState state) {
        NativeCallbacks::view_event(m_view_id, ViewEventAudioPlayStateChanged, {}, {}, state == Web::HTML::AudioPlayState::Playing ? 1 : 0);
    };

    on_input_method_state_change = [this] {
        auto const& state = input_method_state();
        NativeCallbacks::view_event(m_view_id, ViewEventInputMethodStateChanged, {}, {}, state.is_enabled ? 1 : 0, state.cursor_position);
    };

    on_link_hover = [this](URL::URL const& url) {
        auto url_string = url_to_byte_string(url);
        NativeCallbacks::view_event(m_view_id, ViewEventLinkHover, url_string.view());
    };

    on_link_unhover = [this] {
        NativeCallbacks::view_event(m_view_id, ViewEventLinkUnhover);
    };

    on_theme_color_change = [this](Gfx::Color color) {
        NativeCallbacks::view_event(m_view_id, ViewEventThemeColorChanged, {}, {}, static_cast<i64>(color.value()));
    };

    on_web_content_crashed = [this](WebContentCrashReason reason) {
        NativeCallbacks::view_event(m_view_id, ViewEventWebContentCrashed, {}, {}, reason == WebContentCrashReason::RejectedIPC ? 1 : 0);
    };

    on_close = [this] {
        NativeCallbacks::view_event(m_view_id, ViewEventRequestClose);
    };

    on_activate_tab = [this] {
        NativeCallbacks::view_event(m_view_id, ViewEventRequestActivate);
    };

    on_new_web_view = [this](Web::HTML::ActivateTab activate_tab, Web::HTML::WebViewHints, WebView::WebContentClient& page_process, Optional<Compositing::PageId> page_index) -> String {
        auto& application = AndroidApplication::the();
        auto new_view_id = AndroidApplication::allocate_view_id();
        auto& view = page_index.has_value()
            ? application.create_child_view(new_view_id, *this, page_process, *page_index)
            : application.create_view(new_view_id, is_private());
        NativeCallbacks::view_event(new_view_id, ViewEventNewTab, {}, {}, activate_tab == Web::HTML::ActivateTab::Yes ? 1 : 0, static_cast<i64>(m_view_id));
        return view.handle();
    };

    on_request_external_url_confirmation = [this](URL::URL const& url, URL::Origin const&, WebView::ExternalURLHandler const&, Function<void(bool)> callback) {
        // External schemes (mailto:, tel:, intent:, ...) are handed to Android by the Kotlin side,
        // which asks the user first (requirement SEC-009). The engine itself never launches them.
        auto url_string = url_to_byte_string(url);
        NativeCallbacks::view_event(m_view_id, ViewEventRequestExternalUrl, url_string.view());
        callback(false);
    };

    on_finish_handling_key_event = [](Compositing::KeyEvent const&) {
        // Unhandled keys are not re-dispatched to Android views.
    };

    auto show_context_menu = [this](WebView::Menu& menu, Gfx::IntPoint position) {
        m_active_context_menu = menu;
        auto json = serialize_menu(menu);
        NativeCallbacks::view_event(m_view_id, ViewEventRequestContextMenu, json.bytes_as_string_view(), menu.title(), position.x(), position.y());
    };

    page_context_menu().on_activation = [this, show_context_menu](Gfx::IntPoint position) { show_context_menu(page_context_menu(), position); };
    link_context_menu().on_activation = [this, show_context_menu](Gfx::IntPoint position) { show_context_menu(link_context_menu(), position); };
    selected_text_link_context_menu().on_activation = [this, show_context_menu](Gfx::IntPoint position) { show_context_menu(selected_text_link_context_menu(), position); };
    image_context_menu().on_activation = [this, show_context_menu](Gfx::IntPoint position) { show_context_menu(image_context_menu(), position); };
    media_context_menu().on_activation = [this, show_context_menu](Gfx::IntPoint position) { show_context_menu(media_context_menu(), position); };
}

}

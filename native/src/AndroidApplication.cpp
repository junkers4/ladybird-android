/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "AndroidApplication.h"
#include "AndroidWebView.h"
#include "BridgeProtocol.h"
#include "JNIBridge.h"
#include "SerializeMenu.h"
#include <AK/Atomic.h>
#include <AK/JsonObject.h>
#include <AK/JsonValue.h>
#include <AK/LexicalPath.h>
#include <LibURL/Parser.h>
#include <LibWebCommon/HTML/AutoplayPolicy.h>
#include <LibWebView/Settings.h>

namespace LadybirdAndroid {

static Atomic<u64> s_next_view_id { 1 };

u64 AndroidApplication::allocate_view_id()
{
    return s_next_view_id.fetch_add(1);
}

AndroidApplication::AndroidApplication(Optional<ByteString> ladybird_binary_path)
    : WebView::Application(move(ladybird_binary_path))
{
}

AndroidApplication::~AndroidApplication()
{
    m_views.clear();
}

AndroidWebView& AndroidApplication::create_view(u64 view_id, WebView::IsPrivate is_private)
{
    auto view = AndroidWebView::create(view_id, is_private);
    auto& view_reference = *view;
    m_views.set(view_id, move(view));
    return view_reference;
}

AndroidWebView& AndroidApplication::create_child_view(u64 view_id, AndroidWebView& parent, WebView::WebContentClient& page_process, Compositing::PageId page_index)
{
    auto view = AndroidWebView::create_child(view_id, parent, page_process, page_index);
    auto& view_reference = *view;
    m_views.set(view_id, move(view));
    return view_reference;
}

void AndroidApplication::destroy_view(u64 view_id)
{
    if (m_active_view_id == view_id)
        m_active_view_id = 0;
    m_views.remove(view_id);
}

AndroidWebView* AndroidApplication::view(u64 view_id)
{
    auto it = m_views.find(view_id);
    if (it == m_views.end())
        return nullptr;
    return it->value.ptr();
}

void AndroidApplication::create_platform_options(WebView::BrowserOptions&, WebView::RequestServerOptions&, WebView::WebContentOptions& web_content_options)
{
    // The Android build presents frames by copying CPU bitmaps into an ANativeWindow (ARCH-003).
    web_content_options.force_cpu_painting = WebView::ForceCPUPainting::Yes;
}

Optional<WebView::ViewImplementation&> AndroidApplication::active_web_view() const
{
    auto it = m_views.find(m_active_view_id);
    if (it == m_views.end())
        return {};
    return const_cast<AndroidWebView&>(*it->value);
}

Vector<WebView::ViewImplementation&> AndroidApplication::active_window_web_views() const
{
    Vector<WebView::ViewImplementation&> views;
    for (auto const& it : m_views)
        views.append(const_cast<AndroidWebView&>(*it.value));
    return views;
}

Optional<WebView::ViewImplementation&> AndroidApplication::open_blank_new_tab(Web::HTML::ActivateTab activate_tab) const
{
    auto& self = const_cast<AndroidApplication&>(*this);
    auto view_id = allocate_view_id();
    auto is_private = WebView::IsPrivate::No;
    if (auto active = active_web_view(); active.has_value())
        is_private = active->is_private();
    auto& view = self.create_view(view_id, is_private);
    NativeCallbacks::view_event(view_id, ViewEventNewTab, {}, {}, activate_tab == Web::HTML::ActivateTab::Yes ? 1 : 0, static_cast<i64>(m_active_view_id));
    return view;
}

void AndroidApplication::open_url_in_new_tab(URL::URL const& url, Web::HTML::ActivateTab activate_tab) const
{
    JsonObject object;
    object.set("url"sv, url.to_string());
    object.set("activate"sv, activate_tab == Web::HTML::ActivateTab::Yes);
    object.set("private"sv, false);
    auto json = object.serialized();
    NativeCallbacks::engine_event(EngineEventOpenUrlInNewTab, json.bytes_as_string_view());
}

void AndroidApplication::open_url_in_new_window(URL::URL const& url, WebView::IsPrivate is_private)
{
    // Android has a single window; a "new window" becomes a new tab in the requested browsing mode.
    JsonObject object;
    object.set("url"sv, url.to_string());
    object.set("activate"sv, true);
    object.set("private"sv, is_private == WebView::IsPrivate::Yes);
    auto json = object.serialized();
    NativeCallbacks::engine_event(EngineEventOpenUrlInNewTab, json.bytes_as_string_view());
}

Optional<ByteString> AndroidApplication::ask_user_for_download_path(ByteString const& file) const
{
    // Downloads land in the app-private XDG_DOWNLOAD_DIR first; the Kotlin side then publishes the
    // finished file to the shared Downloads collection (display_download_confirmation_dialog).
    auto path = default_path_for_downloaded_file(file);
    if (path.is_error())
        return {};
    return path.value().string();
}

void AndroidApplication::display_download_confirmation_dialog(StringView download_name, LexicalPath const& path) const
{
    JsonObject object;
    object.set("name"sv, download_name);
    object.set("path"sv, path.string().view());
    auto json = object.serialized();
    NativeCallbacks::engine_event(EngineEventDownloadConfirmation, json.bytes_as_string_view());
}

void AndroidApplication::display_error_dialog(StringView error_message) const
{
    NativeCallbacks::engine_event(EngineEventDisplayError, error_message);
}

bool AndroidApplication::supports_clipboard_type(ClipboardType type) const
{
    return type == ClipboardType::Text;
}

Utf16String AndroidApplication::clipboard_text(ClipboardType type) const
{
    if (type != ClipboardType::Text)
        return {};
    return NativeCallbacks::clipboard_text().value_or({});
}

void AndroidApplication::set_clipboard_text(String text, ClipboardType type)
{
    if (type != ClipboardType::Text)
        return;
    NativeCallbacks::set_clipboard_text(text.bytes_as_string_view());
}

WebView::Menu* AndroidApplication::application_menu(StringView menu_name)
{
    if (menu_name == "zoom"sv)
        return &zoom_menu();
    if (menu_name == "color_scheme"sv)
        return &color_scheme_menu();
    if (menu_name == "contrast"sv)
        return &contrast_menu();
    if (menu_name == "motion"sv)
        return &motion_menu();
    if (menu_name == "bookmarks"sv)
        return &bookmarks_menu();
    if (menu_name == "history"sv)
        return &history_menu();
    if (menu_name == "inspect"sv)
        return &inspect_menu();
    if (menu_name == "debug"sv)
        return &debug_menu();
    return nullptr;
}

Optional<String> AndroidApplication::serialize_application_menu(StringView menu_name)
{
    auto* menu = application_menu(menu_name);
    if (!menu)
        return {};
    return serialize_menu(*menu);
}

void AndroidApplication::activate_application_menu_item(StringView menu_name, size_t action_index)
{
    if (auto* menu = application_menu(menu_name))
        activate_menu_action_at(*menu, action_index);
}

// Policy keys (all optional):
//   "globalPrivacyControl": bool
//   "autoplay": "allow" | "block_audio" | "block_all"
//   "dns": { "mode": "system" | "tls" | "udp", "server": "...", "port": N, "dnssec": bool }
//   "contentBlockerLists": { "<identifier>": bool, ... }
//   "customFilters": "<adblock filter list text>"
//   "filterListUpdates": bool
//   "backgroundNetworking": bool
//   "searchEngine": "<name>" | null
//   "newTabPage": "<url>"
//   "geolocation": bool
void AndroidApplication::apply_policy(StringView json)
{
    auto parsed = JsonValue::from_string(json);
    if (parsed.is_error() || !parsed.value().is_object()) {
        warnln("ladybird-android: ignoring malformed engine policy");
        return;
    }
    auto const& policy = parsed.value().as_object();
    auto& settings = WebView::Application::settings();

    if (auto gpc = policy.get_bool("globalPrivacyControl"sv); gpc.has_value())
        settings.set_global_privacy_control(*gpc ? WebView::GlobalPrivacyControl::Yes : WebView::GlobalPrivacyControl::No);

    if (auto autoplay = policy.get_string("autoplay"sv); autoplay.has_value()) {
        if (*autoplay == "allow"sv)
            settings.set_autoplay_policy(Web::HTML::AutoplayPolicy::AllowAudioAndVideo);
        else if (*autoplay == "block_audio"sv)
            settings.set_autoplay_policy(Web::HTML::AutoplayPolicy::BlockAudio);
        else if (*autoplay == "block_all"sv)
            settings.set_autoplay_policy(Web::HTML::AutoplayPolicy::BlockAudioAndVideo);
    }

    if (auto dns = policy.get_object("dns"sv); dns.has_value()) {
        auto mode = dns->get_string("mode"sv);
        auto server = dns->get_string("server"sv);
        auto port = dns->get_u16("port"sv);
        auto dnssec = dns->get_bool("dnssec"sv).value_or(false);
        if (mode.has_value() && *mode == "system"sv) {
            settings.set_dns_settings(WebView::SystemDNS {});
        } else if (mode.has_value() && server.has_value() && *mode == "tls"sv) {
            settings.set_dns_settings(WebView::DNSOverTLS { server->to_byte_string(), port.value_or(853), dnssec });
        } else if (mode.has_value() && server.has_value() && *mode == "udp"sv) {
            settings.set_dns_settings(WebView::DNSOverUDP { server->to_byte_string(), port.value_or(53), dnssec });
        }
    }

    if (auto lists = policy.get_object("contentBlockerLists"sv); lists.has_value()) {
        lists->for_each_member([&](String const& identifier, JsonValue const& enabled) {
            if (enabled.is_bool() && settings.content_blocker_list(identifier).has_value())
                settings.set_content_blocker_list_enabled(identifier, enabled.as_bool());
        });
    }

    if (auto filters = policy.get_string("customFilters"sv); filters.has_value())
        settings.set_custom_content_blocker_filters(*filters);

    if (auto updates = policy.get_bool("filterListUpdates"sv); updates.has_value())
        settings.set_filter_list_updates_enabled(*updates);

    if (auto background = policy.get_bool("backgroundNetworking"sv); background.has_value())
        settings.set_background_networking_enabled(*background);

    if (policy.has("searchEngine"sv)) {
        if (auto engine = policy.get_string("searchEngine"sv); engine.has_value())
            settings.set_search_engine(engine->bytes_as_string_view());
        else
            settings.set_search_engine({});
    }

    if (auto new_tab_page = policy.get_string("newTabPage"sv); new_tab_page.has_value()) {
        if (auto url = URL::Parser::basic_parse(*new_tab_page); url.has_value())
            settings.set_new_tab_page_url(url.release_value());
    }

    if (auto geolocation = policy.get_bool("geolocation"sv); geolocation.has_value())
        settings.set_geolocation_enabled(*geolocation);
}

}

/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/HashMap.h>
#include <AK/NonnullOwnPtr.h>
#include <LibWebView/Application.h>

namespace LadybirdAndroid {

class AndroidWebView;

// The browser process. Owns every AndroidWebView; the Kotlin side refers to them by id.
class AndroidApplication final : public WebView::Application {
    WEB_VIEW_APPLICATION(AndroidApplication)

public:
    virtual ~AndroidApplication() override;

    static u64 allocate_view_id();

    AndroidWebView& create_view(u64 view_id, WebView::IsPrivate);
    AndroidWebView& create_child_view(u64 view_id, AndroidWebView& parent, WebView::WebContentClient& page_process, Compositing::PageId page_index);
    void destroy_view(u64 view_id);
    AndroidWebView* view(u64 view_id);

    void set_active_view(u64 view_id) { m_active_view_id = view_id; }

    // Applies the privacy/security policy chosen in the Android settings (JSON, see
    // engine/EnginePolicy.kt). Unknown keys are ignored.
    void apply_policy(StringView json);

    // Serializes one of LibWebView's application menus (debug, inspect, zoom, ...) for the Kotlin UI.
    Optional<String> serialize_application_menu(StringView menu_name);
    void activate_application_menu_item(StringView menu_name, size_t action_index);

private:
    explicit AndroidApplication(Optional<ByteString> ladybird_binary_path);

    WebView::Menu* application_menu(StringView menu_name);

    virtual bool should_coordinate_browser_process() const override { return false; }
    virtual void create_platform_options(WebView::BrowserOptions&, WebView::RequestServerOptions&, WebView::WebContentOptions&) override;

    virtual Optional<WebView::ViewImplementation&> active_web_view() const override;
    virtual Vector<WebView::ViewImplementation&> active_window_web_views() const override;

    virtual Optional<WebView::ViewImplementation&> open_blank_new_tab(Web::HTML::ActivateTab) const override;
    virtual void open_url_in_new_tab(URL::URL const&, Web::HTML::ActivateTab) const override;
    virtual void open_url_in_new_window(URL::URL const&, WebView::IsPrivate) override;

    virtual Optional<ByteString> ask_user_for_download_path(ByteString const& file) const override;
    virtual void display_download_confirmation_dialog(StringView download_name, LexicalPath const& path) const override;
    virtual void display_error_dialog(StringView error_message) const override;

    virtual bool supports_clipboard_type(ClipboardType) const override;
    virtual Utf16String clipboard_text(ClipboardType) const override;
    virtual void set_clipboard_text(String, ClipboardType) override;

    virtual bool supports_private_browsing_windows() const override { return true; }

    HashMap<u64, NonnullOwnPtr<AndroidWebView>> m_views;
    u64 m_active_view_id { 0 };
};

}

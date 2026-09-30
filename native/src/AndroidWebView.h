/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/AtomicRefCounted.h>
#include <AK/kmalloc.h>
#include <AK/NonnullOwnPtr.h>
#include <AK/NonnullRefPtr.h>
#include <AK/Optional.h>
#include <LibCompositing/PageId.h>
#include <LibCompositing/PixelUnits.h>
#include <LibCore/AnonymousBuffer.h>
#include <LibWebView/ViewImplementation.h>
#include <android/native_window.h>
#include <pthread.h>

namespace LadybirdAndroid {

// The ANativeWindow a view presents into. It is attached/detached from the Android UI thread
// (SurfaceHolder callbacks) while frames are presented from the engine thread, so all access is
// serialized by a mutex. surfaceDestroyed() must not return while a frame is being written;
// detach() blocks on the same mutex, which guarantees that.
class SurfaceSlot : public AtomicRefCounted<SurfaceSlot> {
public:
    AK_ALLOC_WITH_KMALLOC;

    static NonnullRefPtr<SurfaceSlot> create() { return adopt_ref(*new SurfaceSlot); }
    ~SurfaceSlot();

    void attach(ANativeWindow*);
    void detach();

    template<typename Callback>
    bool with_window(Callback callback)
    {
        pthread_mutex_lock(&m_mutex);
        bool presented = false;
        if (m_window)
            presented = callback(*m_window);
        pthread_mutex_unlock(&m_mutex);
        return presented;
    }

private:
    SurfaceSlot() = default;

    pthread_mutex_t m_mutex = PTHREAD_MUTEX_INITIALIZER;
    ANativeWindow* m_window { nullptr };
};

// Thread-safe registry: view id -> SurfaceSlot, usable from any thread.
struct SurfaceRegistry {
    static NonnullRefPtr<SurfaceSlot> ensure(u64 view_id);
    static RefPtr<SurfaceSlot> find(u64 view_id);
    static void remove(u64 view_id);
};

class AndroidWebView final : public WebView::ViewImplementation {
public:
    AK_ALLOC_WITH_KMALLOC;

    static NonnullOwnPtr<AndroidWebView> create(u64 view_id, WebView::IsPrivate);
    static NonnullOwnPtr<AndroidWebView> create_child(u64 view_id, AndroidWebView& parent, WebView::WebContentClient& page_process, Compositing::PageId page_index);

    virtual ~AndroidWebView() override;

    u64 view_id() const { return m_view_id; }

    void set_viewport(int width, int height, float density);
    void set_visible(bool);
    void set_dark_mode(bool);
    void present();

    void mouse_event(int type, float x, float y, int button, int buttons, int modifiers, int click_count);
    void scroll_event(float x, float y, float delta_x, float delta_y, int phase);
    void pinch_event(float x, float y, float scale_delta);
    void key_event(int type, int key_code, int modifiers, u32 code_point, bool repeat);

    void dialog_closed(int kind, bool accepted, Optional<Utf16String> text);
    void activate_context_menu_item(size_t action_index);

private:
    AndroidWebView(u64 view_id, WebView::IsPrivate);

    void install_callbacks();
    void observe_navigation_actions();
    void notify_navigation_state();

    virtual void prepare_page_for_tab(WebView::WebContentPage&) override;
    virtual Compositing::DevicePixelSize viewport_size() const override { return m_viewport_size; }
    virtual Gfx::IntPoint to_content_position(Gfx::IntPoint widget_position) const override { return widget_position; }
    virtual Gfx::IntPoint to_widget_position(Gfx::IntPoint content_position) const override { return content_position; }

    static Optional<Core::AnonymousBuffer> theme_buffer(bool dark);
    Compositing::DevicePixelRect screen_rect() const { return { 0, 0, m_viewport_size.width(), m_viewport_size.height() }; }

    u64 m_view_id { 0 };
    NonnullRefPtr<SurfaceSlot> m_surface;
    Compositing::DevicePixelSize m_viewport_size { 1, 1 };
    bool m_dark_mode { false };
    Gfx::IntSize m_window_buffer_size;

    RefPtr<WebView::Menu> m_active_context_menu;
};

}

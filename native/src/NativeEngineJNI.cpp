/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

// JNI entry points of io.github.junkers4.ladybird.engine.NativeEngine.
//
// These are called on the Android UI thread. Anything touching LibWebView is posted to the engine
// thread; only surface attachment happens synchronously (see SurfaceSlot).

#include "AndroidApplication.h"
#include "AndroidWebView.h"
#include "BridgeProtocol.h"
#include "EngineThread.h"
#include "JNIBridge.h"
#include <AK/JsonObject.h>
#include <AK/StringUtils.h>
#include <LibIPC/File.h>
#include <LibURL/Parser.h>
#include <LibWebView/Menu.h>
#include <LibWebCommon/HTML/SelectedFile.h>
#include <android/native_window_jni.h>

namespace LadybirdAndroid {

template<typename Callback>
static void with_view(jlong view_id, Callback callback)
{
    EngineThread::post([view_id, callback = move(callback)]() mutable {
        if (auto* view = AndroidApplication::the().view(static_cast<u64>(view_id)))
            callback(*view);
    });
}

static jboolean native_start_engine(JNIEnv* env, jclass, jstring install_prefix, jstring resource_root, jobjectArray arguments)
{
    EngineConfiguration configuration;
    configuration.install_prefix = byte_string_from_java(env, install_prefix);
    configuration.resource_root = byte_string_from_java(env, resource_root);
    auto count = arguments ? env->GetArrayLength(arguments) : 0;
    for (jsize i = 0; i < count; ++i) {
        LocalRef argument { env, env->GetObjectArrayElement(arguments, i) };
        configuration.arguments.append(byte_string_from_java(env, argument.as_string()));
    }
    return EngineThread::start(move(configuration)) ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_is_engine_running(JNIEnv*, jclass)
{
    return EngineThread::is_running() ? JNI_TRUE : JNI_FALSE;
}

static jlong native_create_view(JNIEnv*, jclass, jboolean is_private)
{
    auto view_id = AndroidApplication::allocate_view_id();
    // Make sure the surface slot exists before the view does, so a surface can be attached right away.
    (void)SurfaceRegistry::ensure(view_id);
    EngineThread::post([view_id, is_private] {
        AndroidApplication::the().create_view(view_id, is_private ? WebView::IsPrivate::Yes : WebView::IsPrivate::No);
    });
    return static_cast<jlong>(view_id);
}

static void native_destroy_view(JNIEnv*, jclass, jlong view_id)
{
    if (auto slot = SurfaceRegistry::find(static_cast<u64>(view_id)))
        slot->detach();
    EngineThread::post([view_id] {
        AndroidApplication::the().destroy_view(static_cast<u64>(view_id));
    });
}

static void native_set_active_view(JNIEnv*, jclass, jlong view_id)
{
    EngineThread::post([view_id] {
        AndroidApplication::the().set_active_view(static_cast<u64>(view_id));
    });
}

static void native_set_surface(JNIEnv* env, jclass, jlong view_id, jobject surface)
{
    auto slot = SurfaceRegistry::ensure(static_cast<u64>(view_id));
    if (!surface) {
        // Blocks until the engine thread is done with the current frame (surfaceDestroyed contract).
        slot->detach();
        return;
    }
    if (auto* window = ANativeWindow_fromSurface(env, surface))
        slot->attach(window);
    with_view(view_id, [](AndroidWebView& view) { view.present(); });
}

static void native_set_viewport(JNIEnv*, jclass, jlong view_id, jint width, jint height, jfloat density)
{
    with_view(view_id, [width, height, density](AndroidWebView& view) { view.set_viewport(width, height, density); });
}

static void native_set_visible(JNIEnv*, jclass, jlong view_id, jboolean visible)
{
    with_view(view_id, [visible](AndroidWebView& view) { view.set_visible(visible == JNI_TRUE); });
}

static void native_set_dark_mode(JNIEnv*, jclass, jlong view_id, jboolean dark)
{
    with_view(view_id, [dark](AndroidWebView& view) { view.set_dark_mode(dark == JNI_TRUE); });
}

static void native_load_url(JNIEnv* env, jclass, jlong view_id, jstring url)
{
    auto url_string = string_from_java(env, url);
    with_view(view_id, [url_string = move(url_string)](AndroidWebView& view) {
        if (auto parsed = URL::Parser::basic_parse(url_string); parsed.has_value())
            view.load(parsed.release_value());
    });
}

static void native_load_user_input(JNIEnv* env, jclass, jlong view_id, jstring text)
{
    auto input = byte_string_from_java(env, text);
    with_view(view_id, [input = move(input)](AndroidWebView& view) { view.load_from_user_input(input.view()); });
}

static void native_reload(JNIEnv*, jclass, jlong view_id)
{
    with_view(view_id, [](AndroidWebView& view) { view.reload(); });
}

static void native_stop(JNIEnv*, jclass, jlong view_id)
{
    with_view(view_id, [](AndroidWebView& view) { view.stop_loading(); });
}

static void native_traverse_history(JNIEnv*, jclass, jlong view_id, jint delta)
{
    with_view(view_id, [delta](AndroidWebView& view) { view.traverse_the_history_by_delta(delta); });
}

static void native_set_zoom(JNIEnv*, jclass, jlong view_id, jdouble zoom_level)
{
    with_view(view_id, [zoom_level](AndroidWebView& view) {
        view.set_zoom(zoom_level);
        NativeCallbacks::view_event(view.view_id(), ViewEventZoomChanged, {}, {}, static_cast<i64>(view.zoom_level() * 100.0));
    });
}

static void native_find_in_page(JNIEnv* env, jclass, jlong view_id, jstring query, jboolean case_sensitive)
{
    auto query_string = utf16_string_from_java(env, query);
    with_view(view_id, [query_string = move(query_string), case_sensitive](AndroidWebView& view) {
        view.find_in_page(query_string, case_sensitive ? CaseSensitivity::CaseSensitive : CaseSensitivity::CaseInsensitive);
    });
}

static void native_find_next(JNIEnv*, jclass, jlong view_id, jboolean forward)
{
    with_view(view_id, [forward](AndroidWebView& view) {
        if (forward)
            view.find_in_page_next_match();
        else
            view.find_in_page_previous_match();
    });
}

static void native_mouse_event(JNIEnv*, jclass, jlong view_id, jint type, jfloat x, jfloat y, jint button, jint buttons, jint modifiers, jint click_count)
{
    with_view(view_id, [=](AndroidWebView& view) { view.mouse_event(type, x, y, button, buttons, modifiers, click_count); });
}

static void native_scroll(JNIEnv*, jclass, jlong view_id, jfloat x, jfloat y, jfloat delta_x, jfloat delta_y, jint phase)
{
    with_view(view_id, [=](AndroidWebView& view) { view.scroll_event(x, y, delta_x, delta_y, phase); });
}

static void native_pinch(JNIEnv*, jclass, jlong view_id, jfloat x, jfloat y, jfloat scale_delta)
{
    with_view(view_id, [=](AndroidWebView& view) { view.pinch_event(x, y, scale_delta); });
}

static void native_key_event(JNIEnv*, jclass, jlong view_id, jint type, jint key_code, jint modifiers, jint code_point, jboolean repeat)
{
    with_view(view_id, [=](AndroidWebView& view) { view.key_event(type, key_code, modifiers, static_cast<u32>(code_point), repeat == JNI_TRUE); });
}

static void native_ime_set_composing(JNIEnv* env, jclass, jlong view_id, jstring text)
{
    auto composing = utf16_string_from_java(env, text);
    with_view(view_id, [composing = move(composing)](AndroidWebView& view) { view.set_marked_text_from_input_method(composing); });
}

static void native_ime_commit(JNIEnv* env, jclass, jlong view_id, jstring text)
{
    auto committed = utf16_string_from_java(env, text);
    with_view(view_id, [committed = move(committed)](AndroidWebView& view) { view.commit_text_from_input_method(committed); });
}

static void native_ime_finish_composing(JNIEnv*, jclass, jlong view_id)
{
    with_view(view_id, [](AndroidWebView& view) { view.unmark_text_from_input_method(); });
}

static void native_dialog_closed(JNIEnv* env, jclass, jlong view_id, jint kind, jboolean accepted, jstring text)
{
    Optional<Utf16String> response;
    if (text)
        response = utf16_string_from_java(env, text);
    with_view(view_id, [kind, accepted, response = move(response)](AndroidWebView& view) mutable {
        view.dialog_closed(kind, accepted == JNI_TRUE, move(response));
    });
}

static void native_select_dropdown_closed(JNIEnv*, jclass, jlong view_id, jlong item_id)
{
    with_view(view_id, [item_id](AndroidWebView& view) {
        view.select_dropdown_closed(item_id < 0 ? Optional<u32> {} : Optional<u32> { static_cast<u32>(item_id) });
    });
}

static void native_context_menu_item_selected(JNIEnv*, jclass, jlong view_id, jint index)
{
    with_view(view_id, [index](AndroidWebView& view) {
        if (index >= 0)
            view.activate_context_menu_item(static_cast<size_t>(index));
    });
}

static void native_file_picker_closed(JNIEnv* env, jclass, jlong view_id, jobjectArray names, jintArray fds)
{
    struct PendingFile {
        Utf16String name;
        int fd { -1 };
    };
    Vector<PendingFile> pending_files;
    if (names && fds) {
        auto count = min(env->GetArrayLength(names), env->GetArrayLength(fds));
        auto* raw_fds = env->GetIntArrayElements(fds, nullptr);
        for (jsize i = 0; i < count; ++i) {
            LocalRef name { env, env->GetObjectArrayElement(names, i) };
            pending_files.append({ utf16_string_from_java(env, name.as_string()), raw_fds[i] });
        }
        env->ReleaseIntArrayElements(fds, raw_fds, JNI_ABORT);
    }
    with_view(view_id, [pending_files = move(pending_files)](AndroidWebView& view) mutable {
        Vector<Web::HTML::SelectedFile> files;
        for (auto& file : pending_files) {
            if (file.fd >= 0)
                files.empend(move(file.name), IPC::File::adopt_fd(file.fd));
        }
        view.file_picker_closed(move(files));
    });
}

static void native_exit_fullscreen(JNIEnv*, jclass, jlong view_id)
{
    with_view(view_id, [](AndroidWebView& view) { view.exit_fullscreen(); });
}

static void native_run_javascript(JNIEnv* env, jclass, jlong view_id, jstring script)
{
    auto source = string_from_java(env, script);
    with_view(view_id, [source = move(source)](AndroidWebView& view) { view.run_javascript(source); });
}

static void native_apply_policy(JNIEnv* env, jclass, jstring json)
{
    auto policy = byte_string_from_java(env, json);
    EngineThread::post([policy = move(policy)] {
        AndroidApplication::the().apply_policy(policy.view());
    });
}

static void native_request_application_menu(JNIEnv* env, jclass, jstring name)
{
    auto menu_name = byte_string_from_java(env, name);
    EngineThread::post([menu_name = move(menu_name)] {
        auto menu = AndroidApplication::the().serialize_application_menu(menu_name.view());
        if (!menu.has_value())
            return;
        JsonObject object;
        object.set("name"sv, menu_name.view());
        object.set("menu"sv, menu.release_value());
        auto json = object.serialized();
        NativeCallbacks::engine_event(EngineEventMenuChanged, json.bytes_as_string_view());
    });
}

static void native_activate_application_menu_item(JNIEnv* env, jclass, jstring name, jint index)
{
    auto menu_name = byte_string_from_java(env, name);
    EngineThread::post([menu_name = move(menu_name), index] {
        if (index >= 0)
            AndroidApplication::the().activate_application_menu_item(menu_name.view(), static_cast<size_t>(index));
    });
}

static void native_activate_application_action(JNIEnv* env, jclass, jstring name)
{
    auto action_name = byte_string_from_java(env, name);
    EngineThread::post([action_name = move(action_name)] {
        auto& application = AndroidApplication::the();
        auto action = [&]() -> WebView::Action* {
            if (action_name == "copy"sv)
                return &application.copy_selection_action();
            if (action_name == "cut"sv)
                return &application.cut_selection_action();
            if (action_name == "paste"sv)
                return &application.paste_action();
            if (action_name == "select_all"sv)
                return &application.select_all_action();
            if (action_name == "undo"sv)
                return &application.undo_action();
            if (action_name == "redo"sv)
                return &application.redo_action();
            if (action_name == "view_source"sv)
                return &application.view_source_action();
            if (action_name == "open_settings"sv)
                return &application.open_settings_page_action();
            if (action_name == "open_downloads"sv)
                return &application.open_downloads_page_action();
            if (action_name == "open_about"sv)
                return &application.open_about_page_action();
            return nullptr;
        }();
        if (action && action->enabled())
            action->activate();
    });
}

// JNINativeMethod uses `char*` in some jni.h versions and `char const*` in others.
#define NATIVE_METHOD(name, signature, function) { const_cast<char*>(name), const_cast<char*>(signature), reinterpret_cast<void*>(function) }

static JNINativeMethod const s_native_engine_methods[] = {
    NATIVE_METHOD("nativeStartEngine", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Z", native_start_engine),
    NATIVE_METHOD("nativeIsEngineRunning", "()Z", native_is_engine_running),
    NATIVE_METHOD("nativeCreateView", "(Z)J", native_create_view),
    NATIVE_METHOD("nativeDestroyView", "(J)V", native_destroy_view),
    NATIVE_METHOD("nativeSetActiveView", "(J)V", native_set_active_view),
    NATIVE_METHOD("nativeSetSurface", "(JLandroid/view/Surface;)V", native_set_surface),
    NATIVE_METHOD("nativeSetViewport", "(JIIF)V", native_set_viewport),
    NATIVE_METHOD("nativeSetVisible", "(JZ)V", native_set_visible),
    NATIVE_METHOD("nativeSetDarkMode", "(JZ)V", native_set_dark_mode),
    NATIVE_METHOD("nativeLoadUrl", "(JLjava/lang/String;)V", native_load_url),
    NATIVE_METHOD("nativeLoadUserInput", "(JLjava/lang/String;)V", native_load_user_input),
    NATIVE_METHOD("nativeReload", "(J)V", native_reload),
    NATIVE_METHOD("nativeStop", "(J)V", native_stop),
    NATIVE_METHOD("nativeTraverseHistory", "(JI)V", native_traverse_history),
    NATIVE_METHOD("nativeSetZoom", "(JD)V", native_set_zoom),
    NATIVE_METHOD("nativeFindInPage", "(JLjava/lang/String;Z)V", native_find_in_page),
    NATIVE_METHOD("nativeFindNext", "(JZ)V", native_find_next),
    NATIVE_METHOD("nativeMouseEvent", "(JIFFIIII)V", native_mouse_event),
    NATIVE_METHOD("nativeScroll", "(JFFFFI)V", native_scroll),
    NATIVE_METHOD("nativePinch", "(JFFF)V", native_pinch),
    NATIVE_METHOD("nativeKeyEvent", "(JIIIIZ)V", native_key_event),
    NATIVE_METHOD("nativeImeSetComposing", "(JLjava/lang/String;)V", native_ime_set_composing),
    NATIVE_METHOD("nativeImeCommit", "(JLjava/lang/String;)V", native_ime_commit),
    NATIVE_METHOD("nativeImeFinishComposing", "(J)V", native_ime_finish_composing),
    NATIVE_METHOD("nativeDialogClosed", "(JIZLjava/lang/String;)V", native_dialog_closed),
    NATIVE_METHOD("nativeSelectDropdownClosed", "(JJ)V", native_select_dropdown_closed),
    NATIVE_METHOD("nativeContextMenuItemSelected", "(JI)V", native_context_menu_item_selected),
    NATIVE_METHOD("nativeFilePickerClosed", "(J[Ljava/lang/String;[I)V", native_file_picker_closed),
    NATIVE_METHOD("nativeExitFullscreen", "(J)V", native_exit_fullscreen),
    NATIVE_METHOD("nativeRunJavaScript", "(JLjava/lang/String;)V", native_run_javascript),
    NATIVE_METHOD("nativeApplyPolicy", "(Ljava/lang/String;)V", native_apply_policy),
    NATIVE_METHOD("nativeRequestApplicationMenu", "(Ljava/lang/String;)V", native_request_application_menu),
    NATIVE_METHOD("nativeActivateApplicationMenuItem", "(Ljava/lang/String;I)V", native_activate_application_menu_item),
    NATIVE_METHOD("nativeActivateApplicationAction", "(Ljava/lang/String;)V", native_activate_application_action),
};

}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*)
{
    using namespace LadybirdAndroid;

    g_java_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    auto engine_class = env->FindClass("io/github/junkers4/ladybird/engine/NativeEngine");
    if (!engine_class)
        return JNI_ERR;
    auto method_count = static_cast<jint>(sizeof(s_native_engine_methods) / sizeof(s_native_engine_methods[0]));
    if (env->RegisterNatives(engine_class, s_native_engine_methods, method_count) != JNI_OK)
        return JNI_ERR;
    env->DeleteLocalRef(engine_class);

    NativeCallbacks::initialize(env);
    return JNI_VERSION_1_6;
}

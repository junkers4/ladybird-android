/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "JNIBridge.h"
#include <AK/Format.h>
#include <AK/Utf16View.h>
#include <AK/Utf8View.h>
#include <AK/Vector.h>

namespace LadybirdAndroid {

JavaVM* g_java_vm = nullptr;

JavaEnvironment::JavaEnvironment()
{
    VERIFY(g_java_vm);
    auto result = g_java_vm->GetEnv(reinterpret_cast<void**>(&m_env), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        JavaVMAttachArgs arguments { JNI_VERSION_1_6, const_cast<char*>("LadybirdEngine"), nullptr };
        result = attach_current_thread(g_java_vm, &m_env, &arguments);
        VERIFY(result == JNI_OK);
        // The engine thread attaches itself for its whole lifetime (see EngineThread). Any other
        // thread we attach here must detach again: ART aborts if a thread exits while attached.
        m_detach = true;
    }
    VERIFY(m_env);
}

JavaEnvironment::~JavaEnvironment()
{
    if (m_detach)
        g_java_vm->DetachCurrentThread();
}

static Vector<u16> utf16_code_units_from_utf8(StringView utf8)
{
    Vector<u16> code_units;
    code_units.ensure_capacity(utf8.length());
    for (auto code_point : Utf8View { utf8 }) {
        if (code_point < 0x10000) {
            code_units.append(static_cast<u16>(code_point));
        } else {
            code_point -= 0x10000;
            code_units.append(static_cast<u16>(0xD800 | (code_point >> 10)));
            code_units.append(static_cast<u16>(0xDC00 | (code_point & 0x3FF)));
        }
    }
    return code_units;
}

jstring java_string_from(JNIEnv* env, StringView utf8)
{
    // NewStringUTF expects *modified* UTF-8, which differs from real UTF-8 for supplementary
    // characters and NUL. Converting to UTF-16 ourselves avoids CheckJNI aborts on emoji etc.
    auto code_units = utf16_code_units_from_utf8(utf8);
    return env->NewString(reinterpret_cast<jchar const*>(code_units.data()), static_cast<jsize>(code_units.size()));
}

jstring java_string_from(JNIEnv* env, Utf16View utf16)
{
    Vector<u16> code_units;
    code_units.ensure_capacity(utf16.length_in_code_units());
    for (size_t i = 0; i < utf16.length_in_code_units(); ++i)
        code_units.append(utf16.code_unit_at(i));
    return env->NewString(reinterpret_cast<jchar const*>(code_units.data()), static_cast<jsize>(code_units.size()));
}

Utf16String utf16_string_from_java(JNIEnv* env, jstring string)
{
    if (!string)
        return {};
    auto length = env->GetStringLength(string);
    auto const* chars = env->GetStringChars(string, nullptr);
    auto result = Utf16String::from_utf16(Utf16View { reinterpret_cast<char16_t const*>(chars), static_cast<size_t>(length) });
    env->ReleaseStringChars(string, chars);
    return result;
}

String string_from_java(JNIEnv* env, jstring string)
{
    if (!string)
        return {};
    return utf16_string_from_java(env, string).to_utf8_but_should_be_ported_to_utf16();
}

ByteString byte_string_from_java(JNIEnv* env, jstring string)
{
    if (!string)
        return {};
    return string_from_java(env, string).to_byte_string();
}

bool clear_pending_exception(JNIEnv* env, StringView context)
{
    if (!env->ExceptionCheck())
        return false;
    warnln("ladybird-android: Java exception in {}", context);
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

static jclass s_callbacks_class;
static jmethodID s_on_view_event;
static jmethodID s_on_engine_event;
static jmethodID s_get_clipboard_text;
static jmethodID s_set_clipboard_text;

void NativeCallbacks::initialize(JNIEnv* env)
{
    auto local_class = env->FindClass("io/github/junkers4/ladybird/engine/NativeCallbacks");
    VERIFY(local_class);
    s_callbacks_class = static_cast<jclass>(env->NewGlobalRef(local_class));
    env->DeleteLocalRef(local_class);

    s_on_view_event = env->GetStaticMethodID(s_callbacks_class, "onViewEvent", "(JILjava/lang/String;Ljava/lang/String;JJ)V");
    s_on_engine_event = env->GetStaticMethodID(s_callbacks_class, "onEngineEvent", "(ILjava/lang/String;)V");
    s_get_clipboard_text = env->GetStaticMethodID(s_callbacks_class, "getClipboardText", "()Ljava/lang/String;");
    s_set_clipboard_text = env->GetStaticMethodID(s_callbacks_class, "setClipboardText", "(Ljava/lang/String;)V");
    VERIFY(s_on_view_event && s_on_engine_event && s_get_clipboard_text && s_set_clipboard_text);
}

void NativeCallbacks::view_event(u64 view_id, int event, Optional<StringView> text, Optional<StringView> extra, i64 i1, i64 i2)
{
    JavaEnvironment env;
    LocalRef java_text { env.get(), text.has_value() ? java_string_from(env.get(), *text) : nullptr };
    LocalRef java_extra { env.get(), extra.has_value() ? java_string_from(env.get(), *extra) : nullptr };
    env->CallStaticVoidMethod(s_callbacks_class, s_on_view_event, static_cast<jlong>(view_id), static_cast<jint>(event), java_text.as_string(), java_extra.as_string(), static_cast<jlong>(i1), static_cast<jlong>(i2));
    clear_pending_exception(env.get(), "onViewEvent"sv);
}

void NativeCallbacks::view_event_utf16(u64 view_id, int event, Utf16View text, i64 i1, i64 i2)
{
    JavaEnvironment env;
    LocalRef java_text { env.get(), java_string_from(env.get(), text) };
    env->CallStaticVoidMethod(s_callbacks_class, s_on_view_event, static_cast<jlong>(view_id), static_cast<jint>(event), java_text.as_string(), nullptr, static_cast<jlong>(i1), static_cast<jlong>(i2));
    clear_pending_exception(env.get(), "onViewEvent"sv);
}

void NativeCallbacks::engine_event(int event, Optional<StringView> text)
{
    JavaEnvironment env;
    LocalRef java_text { env.get(), text.has_value() ? java_string_from(env.get(), *text) : nullptr };
    env->CallStaticVoidMethod(s_callbacks_class, s_on_engine_event, static_cast<jint>(event), java_text.as_string());
    clear_pending_exception(env.get(), "onEngineEvent"sv);
}

Optional<Utf16String> NativeCallbacks::clipboard_text()
{
    JavaEnvironment env;
    LocalRef text { env.get(), env->CallStaticObjectMethod(s_callbacks_class, s_get_clipboard_text) };
    if (clear_pending_exception(env.get(), "getClipboardText"sv) || !text.get())
        return {};
    return utf16_string_from_java(env.get(), text.as_string());
}

void NativeCallbacks::set_clipboard_text(StringView text)
{
    JavaEnvironment env;
    LocalRef java_text { env.get(), java_string_from(env.get(), text) };
    env->CallStaticVoidMethod(s_callbacks_class, s_set_clipboard_text, java_text.as_string());
    clear_pending_exception(env.get(), "setClipboardText"sv);
}

}

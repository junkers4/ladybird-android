/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/ByteString.h>
#include <AK/Optional.h>
#include <AK/String.h>
#include <AK/StringView.h>
#include <AK/Types.h>
#include <AK/Utf16String.h>
#include <jni.h>

namespace LadybirdAndroid {

// Set once from JNI_OnLoad.
extern JavaVM* g_java_vm;

// Android's jni.h declares AttachCurrentThread(JNIEnv**, ...), the JDK's (used for host-side checks)
// declares AttachCurrentThread(void**, ...).
inline jint attach_current_thread(JavaVM* vm, JNIEnv** env, JavaVMAttachArgs* arguments)
{
#ifdef __ANDROID__
    return vm->AttachCurrentThread(env, arguments);
#else
    return vm->AttachCurrentThread(reinterpret_cast<void**>(env), arguments);
#endif
}

// Attaches the calling thread to the JVM for the lifetime of the object (if it was not attached
// already) and exposes its JNIEnv.
class JavaEnvironment {
public:
    JavaEnvironment();
    ~JavaEnvironment();

    JavaEnvironment(JavaEnvironment const&) = delete;
    JavaEnvironment& operator=(JavaEnvironment const&) = delete;

    JNIEnv* get() const { return m_env; }
    JNIEnv* operator->() const { return m_env; }

private:
    JNIEnv* m_env { nullptr };
    bool m_detach { false };
};

// A local reference that is deleted when it goes out of scope.
class LocalRef {
public:
    LocalRef(JNIEnv* env, jobject object)
        : m_env(env)
        , m_object(object)
    {
    }
    ~LocalRef()
    {
        if (m_object)
            m_env->DeleteLocalRef(m_object);
    }
    LocalRef(LocalRef const&) = delete;
    LocalRef& operator=(LocalRef const&) = delete;

    jobject get() const { return m_object; }
    jstring as_string() const { return static_cast<jstring>(m_object); }

private:
    JNIEnv* m_env;
    jobject m_object;
};

ByteString byte_string_from_java(JNIEnv*, jstring);
String string_from_java(JNIEnv*, jstring);
Utf16String utf16_string_from_java(JNIEnv*, jstring);
jstring java_string_from(JNIEnv*, StringView);
jstring java_string_from(JNIEnv*, Utf16View);

// Clears (and logs) any pending Java exception. Returns true if one was pending.
bool clear_pending_exception(JNIEnv*, StringView context);

// The Kotlin object io.github.junkers4.ladybird.engine.NativeCallbacks receives all calls from native code.
struct NativeCallbacks {
    static void initialize(JNIEnv*);

    static void view_event(u64 view_id, int event, Optional<StringView> text = {}, Optional<StringView> extra = {}, i64 i1 = 0, i64 i2 = 0);
    static void view_event_utf16(u64 view_id, int event, Utf16View text, i64 i1 = 0, i64 i2 = 0);
    static void engine_event(int event, Optional<StringView> text = {});

    static Optional<Utf16String> clipboard_text();
    static void set_clipboard_text(StringView);
};

}

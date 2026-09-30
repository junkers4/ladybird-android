/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "EngineThread.h"
#include "AndroidApplication.h"
#include "BridgeProtocol.h"
#include "JNIBridge.h"
#include <AK/Atomic.h>
#include <AK/Format.h>
#include <AK/NonnullOwnPtr.h>
#include <LibCore/EventLoop.h>
#include <LibMain/Main.h>
#include <pthread.h>

namespace LadybirdAndroid {

static pthread_mutex_t s_mutex = PTHREAD_MUTEX_INITIALIZER;
static RefPtr<Core::WeakEventLoopReference> s_event_loop;
static Vector<Function<void()>>* s_pending_tasks = nullptr;
static Atomic<bool> s_started { false };
static Atomic<bool> s_running { false };
static pthread_t s_thread;
static EngineConfiguration* s_configuration = nullptr;

static void run_engine()
{
    // Stay attached to the JVM for the lifetime of the engine thread so callbacks into Kotlin are cheap.
    JNIEnv* env = nullptr;
    JavaVMAttachArgs attach_arguments { JNI_VERSION_1_6, const_cast<char*>("LadybirdEngine"), nullptr };
    VERIFY(attach_current_thread(g_java_vm, &env, &attach_arguments) == JNI_OK);

    auto configuration = move(*s_configuration);
    delete s_configuration;
    s_configuration = nullptr;

    Vector<ByteString> argument_storage;
    argument_storage.append("ladybird"sv);
    argument_storage.extend(move(configuration.arguments));

    Vector<StringView> argument_views;
    Vector<char*> argv;
    for (auto& argument : argument_storage) {
        argument_views.append(argument.view());
        argv.append(const_cast<char*>(argument.characters()));
    }
    argv.append(nullptr);

    Main::Arguments arguments {
        .argc = static_cast<int>(argument_views.size()),
        .argv = argv.data(),
        .strings = argument_views.span(),
    };

    auto binary_path = ByteString::formatted("{}/bin", configuration.install_prefix);
    auto application = AndroidApplication::create(arguments, binary_path);
    if (application.is_error()) {
        auto message = ByteString::formatted("{}", application.error());
        warnln("ladybird-android: failed to start the engine: {}", message);
        NativeCallbacks::engine_event(EngineEventFailed, message.view());
        s_started = false;
        g_java_vm->DetachCurrentThread();
        return;
    }

    {
        pthread_mutex_lock(&s_mutex);
        s_event_loop = Core::EventLoop::current_weak();
        auto* pending = s_pending_tasks;
        s_pending_tasks = nullptr;
        pthread_mutex_unlock(&s_mutex);

        if (pending) {
            for (auto& task : *pending)
                Core::EventLoop::current().deferred_invoke(move(task));
            delete pending;
        }
    }

    s_running = true;
    NativeCallbacks::engine_event(EngineEventReady);

    auto result = application.value()->execute();
    if (result.is_error())
        warnln("ladybird-android: engine event loop failed: {}", result.error());

    s_running = false;
    pthread_mutex_lock(&s_mutex);
    s_event_loop = nullptr;
    pthread_mutex_unlock(&s_mutex);

    g_java_vm->DetachCurrentThread();
}

static void* engine_thread_entry(void*)
{
    pthread_setname_np(pthread_self(), "LadybirdEngine");
    run_engine();
    return nullptr;
}

bool EngineThread::start(EngineConfiguration configuration)
{
    if (s_started.exchange(true))
        return true;

    s_configuration = new EngineConfiguration(move(configuration));

    pthread_attr_t attributes;
    pthread_attr_init(&attributes);
    // LibJS and LibWeb recurse deeply; give the browser-process thread a desktop-sized stack.
    pthread_attr_setstacksize(&attributes, 8 * MiB);
    auto rc = pthread_create(&s_thread, &attributes, engine_thread_entry, nullptr);
    pthread_attr_destroy(&attributes);

    if (rc != 0) {
        warnln("ladybird-android: pthread_create failed: {}", rc);
        s_started = false;
        return false;
    }
    pthread_detach(s_thread);
    return true;
}

bool EngineThread::is_running()
{
    return s_running;
}

bool EngineThread::is_current()
{
    return s_started && pthread_equal(pthread_self(), s_thread);
}

void EngineThread::post(Function<void()> task)
{
    pthread_mutex_lock(&s_mutex);
    if (!s_event_loop) {
        if (!s_pending_tasks)
            s_pending_tasks = new Vector<Function<void()>>;
        s_pending_tasks->append(move(task));
        pthread_mutex_unlock(&s_mutex);
        return;
    }
    auto event_loop_weak = s_event_loop;
    pthread_mutex_unlock(&s_mutex);

    auto event_loop = event_loop_weak->take();
    if (!event_loop)
        return;
    event_loop->deferred_invoke(move(task));
}

}

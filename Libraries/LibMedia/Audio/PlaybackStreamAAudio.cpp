/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include <AK/Atomic.h>
#include <AK/Format.h>
#include <AK/Variant.h>
#include <AK/Vector.h>
#include <LibCore/ThreadedPromise.h>
#include <LibMedia/Audio/PlaybackStreamAAudio.h>
#include <LibSync/Mutex.h>

#include <aaudio/AAudio.h>

namespace Audio {

// Control commands handed to the audio callback thread. Mirrors the AudioUnit
// backend: state changes are applied from within the data callback so they're
// serialized against buffer production without extra locking.
struct AAudioTask {
    enum class Type {
        Play,
        Pause,
        PauseAndDiscard,
        Volume,
    };

    void resolve(AK::Duration time)
    {
        promise.visit(
            [](Empty) { VERIFY_NOT_REACHED(); },
            [&](NonnullRefPtr<Core::ThreadedPromise<void>>& promise) { promise->resolve(); },
            [&](NonnullRefPtr<Core::ThreadedPromise<AK::Duration>>& promise) { promise->resolve(move(time)); });
    }

    Type type;
    Variant<Empty, NonnullRefPtr<Core::ThreadedPromise<void>>, NonnullRefPtr<Core::ThreadedPromise<AK::Duration>>> promise;
    Optional<double> data {};
};

class AAudioState : public RefCounted<AAudioState> {
public:
    static ErrorOr<NonnullRefPtr<AAudioState>> create(PlaybackStream::AudioDataRequestCallback data_request_callback, OutputState initial_output_state)
    {
        auto state = TRY(adopt_nonnull_ref_or_enomem(new (nothrow) AAudioState(move(data_request_callback), initial_output_state)));

        AAudioStreamBuilder* builder = nullptr;
        if (auto result = AAudio_createStreamBuilder(&builder); result != AAUDIO_OK)
            return Error::from_string_literal("AAudio: could not create stream builder");

        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setSampleRate(builder, AAUDIO_UNSPECIFIED);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        // Tag the stream as music/media so it routes correctly and keeps playing
        // when the screen is off (the foreground service keeps the process alive).
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
        AAudioStreamBuilder_setDataCallback(builder, &AAudioState::on_data_request, state.ptr());
        AAudioStreamBuilder_setErrorCallback(builder, &AAudioState::on_error, state.ptr());

        auto open_result = AAudioStreamBuilder_openStream(builder, &state->m_stream);
        AAudioStreamBuilder_delete(builder);
        if (open_result != AAUDIO_OK || state->m_stream == nullptr)
            return Error::from_string_literal("AAudio: could not open output stream");

        auto sample_rate = AAudioStream_getSampleRate(state->m_stream);
        auto channel_count = AAudioStream_getChannelCount(state->m_stream);
        auto channel_map = channel_count == 1 ? ChannelMap::mono() : ChannelMap::stereo();
        state->m_sample_specification = SampleSpecification(static_cast<u32>(sample_rate), channel_map);

        if (auto result = AAudioStream_requestStart(state->m_stream); result != AAUDIO_OK) {
            AAudioStream_close(state->m_stream);
            state->m_stream = nullptr;
            return Error::from_string_literal("AAudio: could not start output stream");
        }

        return state;
    }

    ~AAudioState()
    {
        if (m_stream != nullptr) {
            AAudioStream_requestStop(m_stream);
            AAudioStream_close(m_stream);
        }
    }

    void queue_task(AAudioTask task)
    {
        Sync::MutexLocker lock(m_task_queue_mutex);
        m_task_queue.append(move(task));
        m_task_queue_is_empty = false;
    }

    void notify_data_available() { m_data_notified = true; }

    SampleSpecification const& sample_specification() const { return m_sample_specification; }

    AK::Duration last_sample_time() const
    {
        return AK::Duration::from_time_units(m_output_time.load(), 1, m_sample_specification.sample_rate());
    }

private:
    enum class Paused : u8 {
        No,
        Explicit,
        Underrun,
    };

    AAudioState(PlaybackStream::AudioDataRequestCallback data_request_callback, OutputState initial_output_state)
        : m_paused(initial_output_state == OutputState::Playing ? Paused::No : Paused::Explicit)
        , m_data_request_callback(move(data_request_callback))
    {
    }

    Optional<AAudioTask> dequeue_task()
    {
        if (m_task_queue_is_empty.load())
            return {};
        Sync::MutexLocker lock(m_task_queue_mutex);
        m_task_queue_is_empty = m_task_queue.size() == 1;
        return m_task_queue.take_first();
    }

    static aaudio_data_callback_result_t on_data_request(AAudioStream*, void* user_data, void* audio_data, int32_t num_frames)
    {
        auto& state = *static_cast<AAudioState*>(user_data);
        auto channel_count = state.m_sample_specification.channel_count();
        auto output_buffer = Span<float>(static_cast<float*>(audio_data), static_cast<size_t>(num_frames) * channel_count);

        if (state.m_paused == Paused::Underrun && state.m_data_notified.exchange(false))
            state.m_paused = Paused::No;

        if (auto task = state.dequeue_task(); task.has_value()) {
            switch (task->type) {
            case AAudioTask::Type::Play:
                state.m_paused = Paused::No;
                break;
            case AAudioTask::Type::Pause:
            case AAudioTask::Type::PauseAndDiscard:
                // AAudio's requestPause/Flush must not be called from the data
                // callback, so we keep the stream running and emit silence — the
                // engine stops handing us data while suspended.
                state.m_paused = Paused::Explicit;
                break;
            case AAudioTask::Type::Volume:
                if (task->data.has_value())
                    state.m_volume = static_cast<float>(*task->data);
                break;
            }
            task->resolve(state.last_sample_time());
        }

        if (state.m_paused == Paused::No) {
            auto written_buffer = state.m_data_request_callback(output_buffer);
            auto frames_written = written_buffer.size() / channel_count;
            state.m_output_time.fetch_add(static_cast<i64>(frames_written));

            if (written_buffer.is_empty()) {
                state.m_paused = Paused::Underrun;
            } else if (state.m_volume != 1.0f) {
                for (size_t i = 0; i < written_buffer.size(); ++i)
                    output_buffer[i] *= state.m_volume;
            }

            // Zero any tail the engine didn't fill this round.
            if (written_buffer.size() < output_buffer.size())
                output_buffer.slice(written_buffer.size()).fill(0);
        } else {
            output_buffer.fill(0);
        }

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void on_error(AAudioStream*, void*, aaudio_result_t result)
    {
        // FIXME: AAudio recommends reopening the stream on a separate thread when
        //        the device disconnects (e.g. headphones unplugged). For now we
        //        just log; the next page load recreates the stream.
        dbgln("AAudio: stream error {}", static_cast<int>(result));
    }

    AAudioStream* m_stream { nullptr };
    SampleSpecification m_sample_specification;

    Sync::Mutex m_task_queue_mutex;
    Vector<AAudioTask, 4> m_task_queue;
    Atomic<bool> m_task_queue_is_empty { true };

    Paused m_paused { Paused::Explicit };
    PlaybackStream::AudioDataRequestCallback m_data_request_callback;
    Atomic<bool> m_data_notified { false };
    float m_volume { 1.0f };
    Atomic<i64> m_output_time { 0 };
};

NonnullRefPtr<PlaybackStream::CreatePromise> PlaybackStream::create(OutputState initial_output_state, u32 target_latency_ms, AudioDataRequestCallback&& data_request_callback)
{
    return PlaybackStreamAAudio::create(initial_output_state, target_latency_ms, move(data_request_callback));
}

NonnullRefPtr<PlaybackStream::CreatePromise> PlaybackStreamAAudio::create(OutputState initial_output_state, u32, AudioDataRequestCallback&& data_request_callback)
{
    auto promise = CreatePromise::construct();
    auto state_or_error = AAudioState::create(move(data_request_callback), initial_output_state);
    if (state_or_error.is_error()) {
        promise->reject(state_or_error.release_error());
        return promise;
    }
    promise->resolve(adopt_ref(*new PlaybackStreamAAudio(state_or_error.release_value())));
    return promise;
}

PlaybackStreamAAudio::PlaybackStreamAAudio(NonnullRefPtr<AAudioState> state)
    : m_state(move(state))
{
}

PlaybackStreamAAudio::~PlaybackStreamAAudio() = default;

SampleSpecification PlaybackStreamAAudio::sample_specification() const
{
    return m_state->sample_specification();
}

void PlaybackStreamAAudio::set_underrun_callback(Function<void()>)
{
    // FIXME: Implement this (the AudioUnit backend doesn't either).
}

NonnullRefPtr<Core::ThreadedPromise<AK::Duration>> PlaybackStreamAAudio::resume()
{
    auto promise = Core::ThreadedPromise<AK::Duration>::create();
    m_state->queue_task({ AAudioTask::Type::Play, promise });
    return promise;
}

NonnullRefPtr<Core::ThreadedPromise<void>> PlaybackStreamAAudio::drain_buffer_and_suspend()
{
    auto promise = Core::ThreadedPromise<void>::create();
    m_state->queue_task({ AAudioTask::Type::Pause, promise });
    return promise;
}

NonnullRefPtr<Core::ThreadedPromise<void>> PlaybackStreamAAudio::discard_buffer_and_suspend()
{
    auto promise = Core::ThreadedPromise<void>::create();
    m_state->queue_task({ AAudioTask::Type::PauseAndDiscard, promise });
    return promise;
}

void PlaybackStreamAAudio::notify_data_available()
{
    m_state->notify_data_available();
}

AK::Duration PlaybackStreamAAudio::total_time_played() const
{
    return m_state->last_sample_time();
}

NonnullRefPtr<Core::ThreadedPromise<void>> PlaybackStreamAAudio::set_volume(double volume)
{
    auto promise = Core::ThreadedPromise<void>::create();
    m_state->queue_task({ AAudioTask::Type::Volume, promise, volume });
    return promise;
}

}

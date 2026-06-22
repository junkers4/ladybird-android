/*
 * Copyright (c) 2026, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include "PlaybackStream.h"
#include <AK/Error.h>
#include <AK/NonnullRefPtr.h>

namespace Audio {

class AAudioState;

// Android audio output backend, built on the NDK AAudio API (API 26+). Mirrors
// the CoreAudio (AudioUnit) backend: a high-priority data callback pulls PCM
// float frames from the engine's AudioDataRequestCallback.
class PlaybackStreamAAudio final : public PlaybackStream {
public:
    static NonnullRefPtr<CreatePromise> create(OutputState initial_output_state, u32 target_latency_ms, AudioDataRequestCallback&&);

    virtual SampleSpecification sample_specification() const override;

    virtual void set_underrun_callback(Function<void()>) override;

    virtual NonnullRefPtr<Core::ThreadedPromise<AK::Duration>> resume() override;
    virtual NonnullRefPtr<Core::ThreadedPromise<void>> drain_buffer_and_suspend() override;
    virtual NonnullRefPtr<Core::ThreadedPromise<void>> discard_buffer_and_suspend() override;

    virtual void notify_data_available() override;

    virtual AK::Duration total_time_played() const override;

    virtual NonnullRefPtr<Core::ThreadedPromise<void>> set_volume(double) override;

private:
    explicit PlaybackStreamAAudio(NonnullRefPtr<AAudioState>);
    ~PlaybackStreamAAudio();

    NonnullRefPtr<AAudioState> m_state;
};

}

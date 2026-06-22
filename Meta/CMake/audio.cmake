include_guard()

# Audio backend -- how we output audio to the speakers.
if (APPLE AND NOT IOS)
    set(LADYBIRD_AUDIO_BACKEND "AUDIO_UNIT")
    return()
elseif (ANDROID)
    # Use the NDK's AAudio API (available since API 26) for native audio output.
    set(LADYBIRD_AUDIO_BACKEND "AAUDIO")
    return()
elseif (NOT WIN32)
    pkg_check_modules(PULSEAUDIO IMPORTED_TARGET libpulse)

    if (PULSEAUDIO_FOUND)
        set(LADYBIRD_AUDIO_BACKEND "PULSE")
        return()
    endif()
else()
    set(LADYBIRD_AUDIO_BACKEND "WASAPI")
    return()
endif()

message(WARNING "No audio backend available")

if (ANDROID)
    # On Android the helper processes run as Android Services packaged into
    # shared libraries (libwebcontentservice.so etc.), not as standalone
    # executable targets. WebWorker and Compositor service libraries are built
    # via their own targets.
    set(ladybird_helper_processes
        ImageDecoder
        RequestServer
        WebContent
    )
else()
    set(ladybird_helper_processes
        Compositor
        ImageDecoder
        RequestServer
        WebContent
        WebWorker
    )
endif()

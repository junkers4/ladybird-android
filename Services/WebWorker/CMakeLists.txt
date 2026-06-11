set(WEBWORKER_SOURCES
    ConnectionFromClient.cpp
    PageHost.cpp
    WorkerHost.cpp
)

if (ANDROID)
    add_library(webworkerservice SHARED
        ${WEBWORKER_SOURCES}
        ${LADYBIRD_SOURCE_DIR}/UI/Android/src/main/cpp/WebWorkerService.cpp
        ${LADYBIRD_SOURCE_DIR}/UI/Android/src/main/cpp/LadybirdServiceBaseJNI.cpp
    )
    target_link_libraries(webworkerservice PRIVATE android)
else()
    add_library(webworkerservice STATIC ${WEBWORKER_SOURCES})
endif()

set_target_properties(webworkerservice PROPERTIES AUTOMOC OFF AUTORCC OFF AUTOUIC OFF)

target_include_directories(webworkerservice PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/../..)
target_include_directories(webworkerservice PRIVATE ${LADYBIRD_SOURCE_DIR})
target_include_directories(webworkerservice PRIVATE ${LADYBIRD_SOURCE_DIR}/Services/)

target_link_libraries(webworkerservice PUBLIC LibCore LibFileSystem LibGfx LibHTTP LibIPC LibJS LibRequests LibWeb LibWebView LibUnicode LibImageDecoderClient LibMain LibURL LibGC)
target_link_libraries(webworkerservice PRIVATE OpenSSL::Crypto OpenSSL::SSL)

if (NOT ANDROID)
    add_executable(WebWorker main.cpp)

    if (LINUX)
        target_sources(WebWorker PRIVATE ../RendererSandboxLinux.cpp)
    elseif (APPLE)
        target_sources(WebWorker PRIVATE ../RendererSandboxMacOS.cpp)
    else()
        target_sources(WebWorker PRIVATE ../RendererSandboxUnimplemented.cpp)
    endif()

    target_include_directories(WebWorker PRIVATE ${LADYBIRD_SOURCE_DIR})
    target_link_libraries(WebWorker PRIVATE webworkerservice LibSandbox OpenSSL::Crypto OpenSSL::SSL)

    if(WIN32)
        target_include_directories(WebWorker PRIVATE $<BUILD_INTERFACE:${PTHREAD_INCLUDE_DIR}>)
        ladybird_windows_bin(WebWorker CONSOLE)
    endif()
endif()

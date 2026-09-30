package io.github.junkers4.ladybird.core.engine

/**
 * Kotlin twin of native/src/BridgeProtocol.h (requirement ARCH-004). BridgeProtocolContractTest keeps
 * the two in sync: names map as `ViewEventLoadStart` <-> `ViewEvent.LOAD_START`.
 */
object ViewEvent {
    const val LOAD_START = 1
    const val LOAD_FINISH = 2
    const val URL_CHANGED = 3
    const val TITLE_CHANGED = 4
    const val FAVICON_CHANGED = 5
    const val LOADING_STATE_CHANGED = 6
    const val NAVIGATION_STATE_CHANGED = 7
    const val REQUEST_ALERT = 8
    const val REQUEST_CONFIRM = 9
    const val REQUEST_PROMPT = 10
    const val REQUEST_ACCEPT_DIALOG = 11
    const val REQUEST_DISMISS_DIALOG = 12
    const val REQUEST_SELECT_DROPDOWN = 13
    const val REQUEST_CONTEXT_MENU = 14
    const val REQUEST_FILE_PICKER = 15
    const val FIND_IN_PAGE_RESULT = 16
    const val ENTER_FULLSCREEN = 17
    const val EXIT_FULLSCREEN = 18
    const val AUDIO_PLAY_STATE_CHANGED = 19
    const val INPUT_METHOD_STATE_CHANGED = 20
    const val LINK_HOVER = 21
    const val LINK_UNHOVER = 22
    const val THEME_COLOR_CHANGED = 23
    const val WEB_CONTENT_CRASHED = 24
    const val REQUEST_CLOSE = 25
    const val REQUEST_ACTIVATE = 26
    const val NEW_TAB = 27
    const val REQUEST_EXTERNAL_URL = 28
    const val DOWNLOAD_FINISHED = 29
    const val CURSOR_CHANGED = 30
    const val ZOOM_CHANGED = 31
}

object EngineEvent {
    const val READY = 1
    const val FAILED = 2
    const val OPEN_URL_IN_NEW_TAB = 3
    const val DISPLAY_ERROR = 4
    const val DOWNLOAD_CONFIRMATION = 5
    const val MENU_CHANGED = 6
}

object MouseEventType {
    const val DOWN = 0
    const val UP = 1
    const val MOVE = 2
    const val LEAVE = 3
}

object MouseButton {
    const val NONE = 0
    const val PRIMARY = 1
    const val SECONDARY = 2
    const val MIDDLE = 4
}

object ScrollPhase {
    const val NONE = 0
    const val ONGOING = 1
    const val MOMENTUM = 2
    const val ENDED = 3
}

object DialogKind {
    const val ALERT = 1
    const val CONFIRM = 2
    const val PROMPT = 3
}

object KeyEventType {
    const val DOWN = 0
    const val UP = 1
}

/** Names of LibWebView application menus the bridge can serialize (AndroidApplication::application_menu). */
object ApplicationMenus {
    val ALL = listOf("zoom", "color_scheme", "contrast", "motion", "bookmarks", "history", "inspect", "debug")
}

/** Names accepted by NativeEngine.nativeActivateApplicationAction. */
object ApplicationActions {
    const val COPY = "copy"
    const val CUT = "cut"
    const val PASTE = "paste"
    const val SELECT_ALL = "select_all"
    const val UNDO = "undo"
    const val REDO = "redo"
    const val VIEW_SOURCE = "view_source"
    const val OPEN_SETTINGS = "open_settings"
    const val OPEN_DOWNLOADS = "open_downloads"
    const val OPEN_ABOUT = "open_about"

    val ALL = listOf(COPY, CUT, PASTE, SELECT_ALL, UNDO, REDO, VIEW_SOURCE, OPEN_SETTINGS, OPEN_DOWNLOADS, OPEN_ABOUT)
}

/**
 * The helper executables staged by native/LadybirdAndroidTargets.cmake. The app links each
 * `lib<Name>.so` from the native library directory into libexec/<Name> (requirement ARCH-001).
 */
object HelperProcesses {
    val ALL = listOf("Compositor", "ImageDecoder", "MediaServer", "RequestServer", "WebContent", "WebWorker")

    fun libraryFileName(helper: String) = "lib$helper.so"
}

/** Names and JNI signatures of NativeEngine's native methods (must match NativeEngineJNI.cpp). */
object NativeMethods {
    val SIGNATURES = mapOf(
        "nativeStartEngine" to "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Z",
        "nativeIsEngineRunning" to "()Z",
        "nativeCreateView" to "(Z)J",
        "nativeDestroyView" to "(J)V",
        "nativeSetActiveView" to "(J)V",
        "nativeSetSurface" to "(JLandroid/view/Surface;)V",
        "nativeSetViewport" to "(JIIF)V",
        "nativeSetVisible" to "(JZ)V",
        "nativeSetDarkMode" to "(JZ)V",
        "nativeLoadUrl" to "(JLjava/lang/String;)V",
        "nativeLoadUserInput" to "(JLjava/lang/String;)V",
        "nativeReload" to "(J)V",
        "nativeStop" to "(J)V",
        "nativeTraverseHistory" to "(JI)V",
        "nativeSetZoom" to "(JD)V",
        "nativeFindInPage" to "(JLjava/lang/String;Z)V",
        "nativeFindNext" to "(JZ)V",
        "nativeMouseEvent" to "(JIFFIIII)V",
        "nativeScroll" to "(JFFFFI)V",
        "nativePinch" to "(JFFF)V",
        "nativeKeyEvent" to "(JIIIIZ)V",
        "nativeImeSetComposing" to "(JLjava/lang/String;)V",
        "nativeImeCommit" to "(JLjava/lang/String;)V",
        "nativeImeFinishComposing" to "(J)V",
        "nativeDialogClosed" to "(JIZLjava/lang/String;)V",
        "nativeSelectDropdownClosed" to "(JJ)V",
        "nativeContextMenuItemSelected" to "(JI)V",
        "nativeFilePickerClosed" to "(J[Ljava/lang/String;[I)V",
        "nativeExitFullscreen" to "(J)V",
        "nativeRunJavaScript" to "(JLjava/lang/String;)V",
        "nativeApplyPolicy" to "(Ljava/lang/String;)V",
        "nativeRequestApplicationMenu" to "(Ljava/lang/String;)V",
        "nativeActivateApplicationMenuItem" to "(Ljava/lang/String;I)V",
        "nativeActivateApplicationAction" to "(Ljava/lang/String;)V",
    )
}

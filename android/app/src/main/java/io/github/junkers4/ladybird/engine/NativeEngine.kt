package io.github.junkers4.ladybird.engine

import android.view.Surface

/**
 * JNI entry points implemented in native/src/NativeEngineJNI.cpp (registered with RegisterNatives).
 * The names and signatures are checked against the native table by BridgeProtocolContractTest.
 */
object NativeEngine {
    @Volatile
    var isLoaded = false
        private set

    fun load(): Boolean {
        if (isLoaded) return true
        isLoaded = runCatching { System.loadLibrary("ladybird_android") }.isSuccess
        return isLoaded
    }

    @JvmStatic external fun nativeStartEngine(installPrefix: String, resourceRoot: String, arguments: Array<String>): Boolean
    @JvmStatic external fun nativeIsEngineRunning(): Boolean
    @JvmStatic external fun nativeCreateView(isPrivate: Boolean): Long
    @JvmStatic external fun nativeDestroyView(viewId: Long)
    @JvmStatic external fun nativeSetActiveView(viewId: Long)
    @JvmStatic external fun nativeSetSurface(viewId: Long, surface: Surface?)
    @JvmStatic external fun nativeSetViewport(viewId: Long, width: Int, height: Int, density: Float)
    @JvmStatic external fun nativeSetVisible(viewId: Long, visible: Boolean)
    @JvmStatic external fun nativeSetDarkMode(viewId: Long, dark: Boolean)
    @JvmStatic external fun nativeLoadUrl(viewId: Long, url: String)
    @JvmStatic external fun nativeLoadUserInput(viewId: Long, text: String)
    @JvmStatic external fun nativeReload(viewId: Long)
    @JvmStatic external fun nativeStop(viewId: Long)
    @JvmStatic external fun nativeTraverseHistory(viewId: Long, delta: Int)
    @JvmStatic external fun nativeSetZoom(viewId: Long, zoomLevel: Double)
    @JvmStatic external fun nativeFindInPage(viewId: Long, query: String, caseSensitive: Boolean)
    @JvmStatic external fun nativeFindNext(viewId: Long, forward: Boolean)
    @JvmStatic external fun nativeMouseEvent(viewId: Long, type: Int, x: Float, y: Float, button: Int, buttons: Int, modifiers: Int, clickCount: Int)
    @JvmStatic external fun nativeScroll(viewId: Long, x: Float, y: Float, deltaX: Float, deltaY: Float, phase: Int)
    @JvmStatic external fun nativePinch(viewId: Long, x: Float, y: Float, scaleDelta: Float)
    @JvmStatic external fun nativeKeyEvent(viewId: Long, type: Int, keyCode: Int, modifiers: Int, codePoint: Int, repeat: Boolean)
    @JvmStatic external fun nativeImeSetComposing(viewId: Long, text: String)
    @JvmStatic external fun nativeImeCommit(viewId: Long, text: String)
    @JvmStatic external fun nativeImeFinishComposing(viewId: Long)
    @JvmStatic external fun nativeDialogClosed(viewId: Long, kind: Int, accepted: Boolean, text: String?)
    @JvmStatic external fun nativeSelectDropdownClosed(viewId: Long, itemId: Long)
    @JvmStatic external fun nativeContextMenuItemSelected(viewId: Long, index: Int)
    @JvmStatic external fun nativeFilePickerClosed(viewId: Long, names: Array<String>, fds: IntArray)
    @JvmStatic external fun nativeExitFullscreen(viewId: Long)
    @JvmStatic external fun nativeRunJavaScript(viewId: Long, script: String)
    @JvmStatic external fun nativeApplyPolicy(json: String)
    @JvmStatic external fun nativeRequestApplicationMenu(name: String)
    @JvmStatic external fun nativeActivateApplicationMenuItem(name: String, index: Int)
    @JvmStatic external fun nativeActivateApplicationAction(name: String)
}

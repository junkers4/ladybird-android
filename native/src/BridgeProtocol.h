/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

// The contract between the native bridge and the Kotlin side (engine/BridgeProtocol.kt).
//
// Every value here has a twin in Kotlin. The JVM test BridgeProtocolContractTest parses this
// file and fails when the two drift apart (requirement ARCH-004), so keep the
// `NAME = value,` layout intact.

namespace LadybirdAndroid {

// Events delivered to NativeCallbacks.onViewEvent(viewId, event, text, extra, i1, i2).
enum ViewEvent : int {
    ViewEventLoadStart = 1,
    ViewEventLoadFinish = 2,
    ViewEventUrlChanged = 3,
    ViewEventTitleChanged = 4,
    ViewEventFaviconChanged = 5,
    ViewEventLoadingStateChanged = 6,
    ViewEventNavigationStateChanged = 7,
    ViewEventRequestAlert = 8,
    ViewEventRequestConfirm = 9,
    ViewEventRequestPrompt = 10,
    ViewEventRequestAcceptDialog = 11,
    ViewEventRequestDismissDialog = 12,
    ViewEventRequestSelectDropdown = 13,
    ViewEventRequestContextMenu = 14,
    ViewEventRequestFilePicker = 15,
    ViewEventFindInPageResult = 16,
    ViewEventEnterFullscreen = 17,
    ViewEventExitFullscreen = 18,
    ViewEventAudioPlayStateChanged = 19,
    ViewEventInputMethodStateChanged = 20,
    ViewEventLinkHover = 21,
    ViewEventLinkUnhover = 22,
    ViewEventThemeColorChanged = 23,
    ViewEventWebContentCrashed = 24,
    ViewEventRequestClose = 25,
    ViewEventRequestActivate = 26,
    ViewEventNewTab = 27,
    ViewEventRequestExternalUrl = 28,
    ViewEventDownloadFinished = 29,
    ViewEventCursorChanged = 30,
    ViewEventZoomChanged = 31,
};

// Events delivered to NativeCallbacks.onEngineEvent(event, text).
enum EngineEvent : int {
    EngineEventReady = 1,
    EngineEventFailed = 2,
    EngineEventOpenUrlInNewTab = 3,
    EngineEventDisplayError = 4,
    EngineEventDownloadConfirmation = 5,
    EngineEventMenuChanged = 6,
};

// Mouse event types accepted by NativeEngine.nativeMouseEvent.
enum MouseEventType : int {
    MouseEventDown = 0,
    MouseEventUp = 1,
    MouseEventMove = 2,
    MouseEventLeave = 3,
};

// Mouse buttons accepted by NativeEngine.nativeMouseEvent (bit flags, same as Compositing::MouseButton).
enum MouseButton : int {
    MouseButtonNone = 0,
    MouseButtonPrimary = 1,
    MouseButtonSecondary = 2,
    MouseButtonMiddle = 4,
};

// Scroll gesture phases accepted by NativeEngine.nativeScroll.
enum ScrollPhase : int {
    ScrollPhaseNone = 0,
    ScrollPhaseOngoing = 1,
    ScrollPhaseMomentum = 2,
    ScrollPhaseEnded = 3,
};

// Dialog kinds used by NativeEngine.nativeDialogClosed.
enum DialogKind : int {
    DialogKindAlert = 1,
    DialogKindConfirm = 2,
    DialogKindPrompt = 3,
};

// Key event types accepted by NativeEngine.nativeKeyEvent.
enum KeyEventType : int {
    KeyEventDown = 0,
    KeyEventUp = 1,
};

}

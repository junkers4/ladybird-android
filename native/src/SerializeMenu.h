/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#pragma once

#include <AK/String.h>
#include <LibWebView/Menu.h>

namespace LadybirdAndroid {

// Serializes a LibWebView menu to JSON for the Kotlin UI (see engine/EngineMenu.kt):
//   { "title": "...", "items": [ item... ] }
// where an item is one of
//   { "type": "action", "index": N, "id": <ActionID>, "text": "...", "enabled": b, "visible": b,
//     "checkable": b, "checked": b }
//   { "type": "separator" }
//   { "type": "submenu", "title": "...", "visible": b, "items": [ item... ] }
// "index" numbers actions in Menu::for_each_action() order, which is what activate_menu_action_at() expects.
String serialize_menu(WebView::Menu&);

// Activates the N-th action (in Menu::for_each_action() order) if it exists, is visible and is enabled.
bool activate_menu_action_at(WebView::Menu&, size_t action_index);

}

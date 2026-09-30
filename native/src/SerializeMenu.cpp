/*
 * Copyright (c) 2026, the ladybird-android contributors.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

#include "SerializeMenu.h"
#include <AK/JsonArray.h>
#include <AK/JsonObject.h>

namespace LadybirdAndroid {

static JsonArray serialize_items(WebView::Menu& menu, size_t& next_action_index)
{
    JsonArray items;
    for (auto& item : menu.items()) {
        item.visit(
            [&](NonnullRefPtr<WebView::Action>& action) {
                JsonObject object;
                object.set("type"sv, "action"sv);
                object.set("index"sv, next_action_index++);
                object.set("id"sv, to_underlying(action->id()));
                object.set("text"sv, action->text());
                object.set("enabled"sv, action->enabled());
                object.set("visible"sv, action->visible());
                object.set("checkable"sv, action->is_checkable());
                object.set("checked"sv, action->is_checkable() && action->checked());
                items.must_append(move(object));
            },
            [&](NonnullRefPtr<WebView::Menu>& submenu) {
                JsonObject object;
                object.set("type"sv, "submenu"sv);
                object.set("title"sv, submenu->title());
                object.set("visible"sv, submenu->visible());
                object.set("items"sv, serialize_items(*submenu, next_action_index));
                items.must_append(move(object));
            },
            [&](WebView::Separator) {
                JsonObject object;
                object.set("type"sv, "separator"sv);
                items.must_append(move(object));
            });
    }
    return items;
}

String serialize_menu(WebView::Menu& menu)
{
    size_t next_action_index = 0;
    JsonObject object;
    object.set("title"sv, menu.title());
    object.set("items"sv, serialize_items(menu, next_action_index));
    return object.serialized();
}

bool activate_menu_action_at(WebView::Menu& menu, size_t action_index)
{
    size_t index = 0;
    RefPtr<WebView::Action> target;
    menu.for_each_action([&](WebView::Action& action) {
        if (index++ == action_index)
            target = action;
    });
    if (!target || !target->enabled() || !target->visible())
        return false;
    target->activate();
    return true;
}

}

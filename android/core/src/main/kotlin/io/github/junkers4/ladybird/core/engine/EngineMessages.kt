package io.github.junkers4.ladybird.core.engine

import io.github.junkers4.ladybird.core.json.Json
import io.github.junkers4.ladybird.core.json.JsonException
import io.github.junkers4.ladybird.core.json.boolean
import io.github.junkers4.ladybird.core.json.list
import io.github.junkers4.ladybird.core.json.long
import io.github.junkers4.ladybird.core.json.obj
import io.github.junkers4.ladybird.core.json.string

/**
 * Decoders for the JSON payloads the native bridge sends (requirement ARCH-007). Every decoder
 * returns null for malformed input instead of throwing, so a bad message can never crash the UI.
 */

/** A LibWebView menu, serialized by native/src/SerializeMenu.cpp (requirements UI-005, UI-006). */
data class EngineMenu(val title: String, val items: List<Item>) {
    sealed interface Item {
        data class Action(
            val index: Int,
            val id: Int,
            val text: String,
            val enabled: Boolean,
            val visible: Boolean,
            val checkable: Boolean,
            val checked: Boolean,
        ) : Item

        data class Submenu(val title: String, val visible: Boolean, val items: List<Item>) : Item

        data object Separator : Item
    }

    /** Visible items, without leading/trailing/duplicate separators. */
    fun visibleItems(): List<Item> = tidy(items)

    companion object {
        fun parse(json: String): EngineMenu? = runCatching { fromMap(Json.parseObject(json)) }.getOrNull()

        internal fun fromMap(map: Map<String, Any?>): EngineMenu {
            val title = map.string("title") ?: throw JsonException("menu without title")
            return EngineMenu(title, parseItems(map.list("items") ?: throw JsonException("menu without items")))
        }

        private fun parseItems(list: List<Any?>): List<Item> = list.map { raw ->
            @Suppress("UNCHECKED_CAST")
            val item = raw as? Map<String, Any?> ?: throw JsonException("menu item is not an object")
            when (item.string("type")) {
                "action" -> Item.Action(
                    index = item.long("index")?.toInt() ?: throw JsonException("action without index"),
                    id = item.long("id")?.toInt() ?: -1,
                    text = item.string("text") ?: "",
                    enabled = item.boolean("enabled") ?: false,
                    visible = item.boolean("visible") ?: true,
                    checkable = item.boolean("checkable") ?: false,
                    checked = item.boolean("checked") ?: false,
                )
                "submenu" -> Item.Submenu(item.string("title") ?: "", item.boolean("visible") ?: true, parseItems(item.list("items") ?: emptyList()))
                "separator" -> Item.Separator
                else -> throw JsonException("unknown menu item type")
            }
        }

        private fun tidy(items: List<Item>): List<Item> {
            val result = mutableListOf<Item>()
            for (item in items) {
                when (item) {
                    is Item.Action -> if (item.visible && item.text.isNotEmpty()) result += item
                    is Item.Submenu -> if (item.visible) {
                        val children = tidy(item.items)
                        if (children.isNotEmpty()) result += item.copy(items = children)
                    }
                    Item.Separator -> if (result.isNotEmpty() && result.last() != Item.Separator) result += item
                }
            }
            while (result.lastOrNull() == Item.Separator) result.removeAt(result.size - 1)
            return result
        }
    }
}

/** Items of a <select> dropdown (requirement UI-007). */
sealed interface SelectItem {
    data class Option(val id: Long, val label: String, val selected: Boolean, val disabled: Boolean) : SelectItem
    data class Group(val label: String, val options: List<Option>) : SelectItem
    data object Separator : SelectItem

    companion object {
        fun parseList(json: String): List<SelectItem>? = runCatching {
            val list = Json.parse(json) as? List<*> ?: throw JsonException("expected array")
            list.map { raw ->
                @Suppress("UNCHECKED_CAST")
                val item = raw as? Map<String, Any?> ?: throw JsonException("select item is not an object")
                when (item.string("type")) {
                    "option" -> parseOption(item)
                    "group" -> Group(item.string("label") ?: "", (item.list("items") ?: emptyList()).map {
                        @Suppress("UNCHECKED_CAST")
                        parseOption(it as? Map<String, Any?> ?: throw JsonException("group option is not an object"))
                    })
                    "separator" -> Separator
                    else -> throw JsonException("unknown select item type")
                }
            }
        }.getOrNull()

        private fun parseOption(item: Map<String, Any?>) = Option(
            id = item.long("id") ?: throw JsonException("option without id"),
            label = item.string("label") ?: "",
            selected = item.boolean("selected") ?: false,
            disabled = item.boolean("disabled") ?: false,
        )

        /** Flattens groups for list-style pickers; group labels become headers (null id). */
        fun flatten(items: List<SelectItem>): List<Pair<Option?, String>> = buildList {
            for (item in items) when (item) {
                is Option -> add(item to item.label)
                is Group -> {
                    add(null to item.label)
                    item.options.forEach { add(it to "  " + it.label) }
                }
                Separator -> Unit
            }
        }
    }
}

/** EngineEvent.OPEN_URL_IN_NEW_TAB payload. */
data class OpenUrlRequest(val url: String, val activate: Boolean, val private: Boolean) {
    companion object {
        fun parse(json: String): OpenUrlRequest? = runCatching {
            val map = Json.parseObject(json)
            OpenUrlRequest(map.string("url") ?: throw JsonException("missing url"), map.boolean("activate") ?: true, map.boolean("private") ?: false)
        }.getOrNull()
    }
}

/** EngineEvent.DOWNLOAD_CONFIRMATION payload. */
data class FinishedDownload(val name: String, val path: String) {
    companion object {
        fun parse(json: String): FinishedDownload? = runCatching {
            val map = Json.parseObject(json)
            FinishedDownload(map.string("name") ?: throw JsonException("missing name"), map.string("path") ?: throw JsonException("missing path"))
        }.getOrNull()
    }
}

/** EngineEvent.MENU_CHANGED payload. */
data class ApplicationMenuUpdate(val name: String, val menu: EngineMenu) {
    companion object {
        fun parse(json: String): ApplicationMenuUpdate? = runCatching {
            val map = Json.parseObject(json)
            val name = map.string("name") ?: throw JsonException("missing name")
            // The bridge embeds the serialized menu either as an object or as a JSON string.
            val menu = map.obj("menu")?.let { EngineMenu.fromMap(it) }
                ?: map.string("menu")?.let { EngineMenu.parse(it) }
                ?: throw JsonException("missing menu")
            ApplicationMenuUpdate(name, menu)
        }.getOrNull()
    }
}

package io.github.junkers4.ladybird.core.ui

import io.github.junkers4.ladybird.core.url.UrlParts

/** The security indicator shown in the location field (requirement UI-004). */
enum class SecurityLevel { SECURE, INSECURE, INTERNAL, NONE }

object SecurityIndicator {
    fun forUrl(url: String): SecurityLevel = when (UrlParts.parse(url)?.scheme) {
        "https", "wss" -> SecurityLevel.SECURE
        "http", "ws" -> SecurityLevel.INSECURE
        "about", "data", "blob", "file" -> SecurityLevel.INTERNAL
        else -> SecurityLevel.NONE
    }
}

/**
 * State of the Ladybird-style toolbar (requirement UI-001): back, forward, reload/stop, location,
 * tab count, security indicator and progress.
 */
data class ToolbarState(
    val url: String = "",
    val title: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isLoading: Boolean = false,
    val tabCount: Int = 1,
    val isPrivate: Boolean = false,
    val shieldsUp: Boolean = true,
) {
    val security: SecurityLevel get() = SecurityIndicator.forUrl(url)

    /** The location text: internal new-tab pages show an empty field with the hint. */
    val locationText: String get() = if (url == "about:newtab" || url == "about:blank") "" else url

    val reloadShowsStop: Boolean get() = isLoading

    /** Tab counts above 99 are shown as ":D" like in Chrome/Vanadium. */
    val tabCountLabel: String get() = if (tabCount > 99) ":D" else tabCount.toString()

    fun onLoadStart(url: String) = copy(url = url, isLoading = true)
    fun onLoadFinish(url: String) = copy(url = url, isLoading = false)
    fun onUrlChanged(url: String) = copy(url = url)
    fun onTitleChanged(title: String) = copy(title = title)
    fun onLoadingState(loading: Boolean) = copy(isLoading = loading)
    fun onNavigationState(back: Boolean, forward: Boolean) = copy(canGoBack = back, canGoForward = forward)
}

/** "3/12", "0/0" or "" for the find bar (requirement UI-008). */
object FindResultText {
    fun format(currentIndex: Long, total: Long): String = when {
        total < 0 -> ""
        total == 0L -> "0/0"
        else -> "${(currentIndex + 1).coerceIn(1, total)}/$total"
    }
}

/** Dark mode (requirement UI-009). */
object ThemePolicy {
    fun useDark(mode: io.github.junkers4.ladybird.core.settings.ThemeMode, systemIsDark: Boolean) = when (mode) {
        io.github.junkers4.ladybird.core.settings.ThemeMode.SYSTEM -> systemIsDark
        io.github.junkers4.ladybird.core.settings.ThemeMode.LIGHT -> false
        io.github.junkers4.ladybird.core.settings.ThemeMode.DARK -> true
    }
}

/**
 * Which app pages map to Ladybird's own WebUI (requirement UI-013/UI-005): the Android UI links to them
 * instead of duplicating engine settings.
 */
object LadybirdPages {
    const val SETTINGS = "about:settings"
    const val DOWNLOADS = "about:downloads"
    const val HISTORY = "about:history"
    const val BOOKMARKS = "about:bookmarks"
    const val ABOUT = "about:version"
    const val NEW_TAB = "about:newtab"
    const val BLOCKING = "about:blocking"

    val ALL = listOf(SETTINGS, DOWNLOADS, HISTORY, BOOKMARKS, ABOUT, NEW_TAB, BLOCKING)
}

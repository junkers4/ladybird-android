package org.serenityos.ladybird

/**
 * A lightweight multi-tab model. The engine runs one live WebContent at a time
 * (each is its own sandboxed process), so rather than keeping N live views we
 * keep N tab *states* and reload the active one on switch. Each tab remembers
 * its own URL, title and network compartment — so you can have e.g. a Normal
 * tab, a Tor tab and an I2P tab side by side.
 */
data class Tab(
    var url: String = AppSettings.DEFAULT_HOME,
    var title: String = "",
    var networkMode: NetworkMode = NetworkMode.Normal,
    // A small snapshot of the page, captured when the tab was last active, shown
    // as the card preview in the tab switcher (Vanadium-style).
    var thumbnail: android.graphics.Bitmap? = null,
)

class TabStore {
    private val tabs = mutableListOf(Tab())
    var activeIndex = 0
        private set

    val count: Int get() = tabs.size
    fun all(): List<Tab> = tabs
    fun active(): Tab = tabs[activeIndex]
    fun tabAt(i: Int): Tab = tabs[i]

    /** Add a new tab (optionally for a network mode) and make it active. */
    fun open(url: String = AppSettings.DEFAULT_HOME, mode: NetworkMode = NetworkMode.Normal): Tab {
        val tab = Tab(url = url, networkMode = mode)
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        return tab
    }

    fun select(i: Int) {
        if (i in tabs.indices) activeIndex = i
    }

    /** Close tab [i]; never leaves zero tabs (recreates a fresh one). Returns
     *  the now-active tab so the caller can load it. */
    fun close(i: Int): Tab {
        if (i !in tabs.indices) return active()
        tabs.removeAt(i)
        if (tabs.isEmpty()) tabs.add(Tab())
        // Keep the *same* tab active when closing one that sits before it,
        // otherwise the selection silently drifts to a neighbour.
        if (i < activeIndex) activeIndex--
        activeIndex = activeIndex.coerceIn(0, tabs.lastIndex)
        return active()
    }
}

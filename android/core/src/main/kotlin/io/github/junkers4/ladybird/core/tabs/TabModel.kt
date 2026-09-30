package io.github.junkers4.ladybird.core.tabs

/**
 * The list of tabs (requirement UI-002). Each tab is backed by a native view (NativeEngine view id).
 * The model is pure; the activity observes it and creates/destroys views accordingly.
 */
class TabModel(private val maximumClosedTabs: Int = 20) {
    data class Tab(
        val id: Long,
        val viewId: Long,
        val isPrivate: Boolean,
        val url: String = "",
        val title: String = "",
        val openerId: Long? = null,
    ) {
        val displayTitle: String get() = title.ifBlank { url.ifBlank { "New tab" } }
    }

    data class ClosedTab(val url: String, val isPrivate: Boolean)

    private val tabs = mutableListOf<Tab>()
    private val closed = ArrayDeque<ClosedTab>()
    private var nextId = 1L

    var activeTabId: Long? = null
        private set

    val all: List<Tab> get() = tabs.toList()
    val normalTabs: List<Tab> get() = tabs.filter { !it.isPrivate }
    val privateTabs: List<Tab> get() = tabs.filter { it.isPrivate }
    val active: Tab? get() = tabs.firstOrNull { it.id == activeTabId }
    val closedCount: Int get() = closed.size

    fun find(tabId: Long) = tabs.firstOrNull { it.id == tabId }
    fun findByView(viewId: Long) = tabs.firstOrNull { it.viewId == viewId }

    /** Opens a tab after the active one (or after its opener), optionally activating it. */
    fun open(viewId: Long, isPrivate: Boolean, url: String = "", activate: Boolean = true, openerId: Long? = null): Tab {
        val tab = Tab(nextId++, viewId, isPrivate, url, "", openerId)
        val anchor = openerId?.let { opener -> tabs.indexOfLast { it.id == opener || it.openerId == opener } }
            ?: tabs.indexOfFirst { it.id == activeTabId }
        if (anchor >= 0) tabs.add(anchor + 1, tab) else tabs.add(tab)
        if (activate || activeTabId == null) activeTabId = tab.id
        return tab
    }

    fun activate(tabId: Long): Boolean {
        if (find(tabId) == null) return false
        activeTabId = tabId
        return true
    }

    /**
     * Closes a tab and returns it. When the active tab closes, its opener becomes active if still open,
     * otherwise the neighbour on the right, else on the left. Private tabs are never remembered.
     */
    fun close(tabId: Long): Tab? {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return null
        val tab = tabs.removeAt(index)
        if (!tab.isPrivate && tab.url.isNotBlank()) {
            closed.addFirst(ClosedTab(tab.url, false))
            while (closed.size > maximumClosedTabs) closed.removeLast()
        }
        if (activeTabId == tabId) {
            activeTabId = tab.openerId?.takeIf { find(it) != null }
                ?: tabs.getOrNull(index)?.id
                ?: tabs.getOrNull(index - 1)?.id
        }
        return tab
    }

    /** Closes every private tab (leaving private browsing) and returns them. */
    fun closeAllPrivate(): List<Tab> = privateTabs.map { close(it.id)!! }

    fun popClosed(): ClosedTab? = closed.removeFirstOrNull()

    fun updatePage(viewId: Long, url: String? = null, title: String? = null) {
        val index = tabs.indexOfFirst { it.viewId == viewId }
        if (index < 0) return
        val tab = tabs[index]
        tabs[index] = tab.copy(url = url ?: tab.url, title = title ?: tab.title)
    }

    fun move(tabId: Long, toIndex: Int) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        tabs.add(toIndex.coerceIn(0, tabs.size), tab)
    }
}

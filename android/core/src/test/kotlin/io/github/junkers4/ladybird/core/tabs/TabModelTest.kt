package io.github.junkers4.ladybird.core.tabs

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@Requirement("UI-002")
class TabModelTest {
    @Test
    fun openActivateAndClose() {
        val model = TabModel()
        val a = model.open(viewId = 10, isPrivate = false, url = "https://a/")
        val b = model.open(viewId = 11, isPrivate = false, url = "https://b/")
        assertEquals(b.id, model.activeTabId)
        assertEquals(listOf(a.id, b.id), model.all.map { it.id })
        val c = model.open(viewId = 12, isPrivate = false, activate = false)
        assertEquals(b.id, model.activeTabId)
        assertEquals(listOf(a.id, b.id, c.id), model.all.map { it.id })
        model.activate(a.id)
        model.close(a.id)
        assertEquals(b.id, model.activeTabId) // right neighbour
        model.close(c.id)
        model.close(b.id)
        assertNull(model.activeTabId)
    }

    @Test
    fun childrenOpenNextToOpenerAndReturnToIt() {
        val model = TabModel()
        val a = model.open(1, false)
        val b = model.open(2, false)
        val child1 = model.open(3, false, openerId = a.id, activate = false)
        val child2 = model.open(4, false, openerId = a.id, activate = true)
        assertEquals(listOf(a.id, child1.id, child2.id, b.id), model.all.map { it.id })
        model.close(child2.id)
        assertEquals(a.id, model.activeTabId)
    }

    @Test
    fun reopenClosedTabsButNeverPrivateOnes() {
        val model = TabModel(maximumClosedTabs = 2)
        val tabs = (1..3).map { model.open(it.toLong(), false, url = "https://$it/") }
        val secret = model.open(9, true, url = "https://secret/")
        tabs.forEach { model.close(it.id) }
        model.close(secret.id)
        assertEquals(2, model.closedCount)
        assertEquals("https://3/", model.popClosed()!!.url)
        assertEquals("https://2/", model.popClosed()!!.url)
        assertNull(model.popClosed())
    }

    @Test
    @Requirement("SEC-013")
    fun privateTabsAreSeparate() {
        val model = TabModel()
        model.open(1, false)
        model.open(2, true)
        model.open(3, true)
        assertEquals(1, model.normalTabs.size)
        assertEquals(2, model.privateTabs.size)
        assertEquals(2, model.closeAllPrivate().size)
        assertEquals(0, model.privateTabs.size)
    }

    @Test
    fun pageUpdatesAndTitles() {
        val model = TabModel()
        val tab = model.open(5, false)
        assertEquals("New tab", tab.displayTitle)
        model.updatePage(5, url = "https://x/")
        assertEquals("https://x/", model.findByView(5)!!.displayTitle)
        model.updatePage(5, title = "X")
        assertEquals("X", model.find(tab.id)!!.displayTitle)
        model.open(6, false)
        model.move(tab.id, 5)
        assertEquals(tab.id, model.all.last().id)
    }
}

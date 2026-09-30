package io.github.junkers4.ladybird.core.ui

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarStateTest {
    @Test
    @Requirement("UI-001")
    fun followsEngineEvents() {
        var state = ToolbarState()
        state = state.onLoadStart("https://a.example/")
        assertTrue(state.reloadShowsStop)
        state = state.onNavigationState(back = true, forward = false).onTitleChanged("A").onLoadFinish("https://a.example/x")
        assertFalse(state.reloadShowsStop)
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
        assertEquals("https://a.example/x", state.locationText)
        assertEquals("A", state.title)
        assertEquals("", state.onUrlChanged("about:newtab").locationText)
        assertEquals("7", state.copy(tabCount = 7).tabCountLabel)
        assertEquals(":D", state.copy(tabCount = 100).tabCountLabel)
    }

    @Test
    @Requirement("UI-004")
    fun securityIndicator() {
        assertEquals(SecurityLevel.SECURE, SecurityIndicator.forUrl("https://a/"))
        assertEquals(SecurityLevel.INSECURE, SecurityIndicator.forUrl("http://a/"))
        assertEquals(SecurityLevel.INTERNAL, SecurityIndicator.forUrl("about:settings"))
        assertEquals(SecurityLevel.NONE, SecurityIndicator.forUrl(""))
    }

    @Test
    @Requirement("UI-008")
    fun findText() {
        assertEquals("3/12", FindResultText.format(2, 12))
        assertEquals("0/0", FindResultText.format(0, 0))
        assertEquals("", FindResultText.format(0, -1))
    }

    @Test
    @Requirement("UI-009")
    fun theme() {
        assertTrue(ThemePolicy.useDark(ThemeMode.SYSTEM, systemIsDark = true))
        assertFalse(ThemePolicy.useDark(ThemeMode.LIGHT, systemIsDark = true))
        assertTrue(ThemePolicy.useDark(ThemeMode.DARK, systemIsDark = false))
    }

    @Test
    @Requirement("UI-005", "UI-013", "UI-014")
    fun ladybirdPagesExistUpstream() {
        val resources = io.github.junkers4.ladybird.core.repo.Repo.text("ladybird/UI/cmake/ResourceFiles.cmake")
        for (page in LadybirdPages.ALL.filter { it != LadybirdPages.ABOUT })
            assertTrue(page, resources.contains(page.removePrefix("about:") + ".html"))
        assertTrue(resources.contains("version.html"))
    }
}

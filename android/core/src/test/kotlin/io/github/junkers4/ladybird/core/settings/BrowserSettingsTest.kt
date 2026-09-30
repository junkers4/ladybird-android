package io.github.junkers4.ladybird.core.settings

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.url.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Requirement("UI-013")
class BrowserSettingsTest {
    @Test
    @Requirement("SEC-004", "SEC-005", "SEC-014", "SEC-016")
    fun secureDefaults() {
        val settings = BrowserSettings()
        assertTrue(settings.httpsOnly)
        assertTrue(settings.stripTrackingParameters)
        assertTrue(settings.globalPrivacyControl)
        assertTrue(settings.contentBlocking)
        assertTrue(settings.youtubeAdBlocking)
        assertTrue(settings.incognitoKeyboard)
        assertTrue(settings.confirmExternalApps)
        assertFalse(settings.geolocation)
        assertEquals(DnsMode.QUAD9, settings.dns)
        assertEquals(AutoplayMode.BLOCK_AUDIO, settings.autoplay)
        assertEquals(SearchEngine.DUCKDUCKGO, settings.searchEngine)
    }

    @Test
    fun roundTripsThroughStorage() {
        val storage = object : SettingsStorage {
            var values: Map<String, String?> = emptyMap()
            override fun read() = values
            override fun write(values: Map<String, String>) { this.values = values }
        }
        val repository = SettingsRepository(storage)
        assertEquals(BrowserSettings(), repository.load())
        val changed = repository.update {
            it.copy(dns = DnsMode.MULLVAD, shieldsDownSites = setOf("b.example", "A.example"), customFilters = "||x^", searchEngine = SearchEngine.BRAVE, clearDataOnExit = true)
        }
        val reloaded = repository.load()
        assertEquals(DnsMode.MULLVAD, reloaded.dns)
        assertEquals(setOf("a.example", "b.example"), reloaded.shieldsDownSites)
        assertEquals("||x^", reloaded.customFilters)
        assertEquals(SearchEngine.BRAVE, reloaded.searchEngine)
        assertTrue(reloaded.clearDataOnExit)
        assertEquals(changed.copy(shieldsDownSites = setOf("a.example", "b.example")), reloaded)
    }

    @Test
    fun garbageFallsBackToDefaults() {
        val settings = BrowserSettings.fromMap(mapOf(BrowserSettings.Keys.HTTPS_ONLY to "maybe", BrowserSettings.Keys.DNS to "evil", BrowserSettings.Keys.AUTOPLAY to null))
        assertTrue(settings.httpsOnly)
        assertEquals(DnsMode.QUAD9, settings.dns)
        assertEquals(AutoplayMode.BLOCK_AUDIO, settings.autoplay)
    }

    @Test
    @Requirement("SEC-015")
    fun clearOnExitIsOptional() {
        assertFalse(BrowserSettings().clearDataOnExit)
        assertTrue(BrowserSettings.fromMap(mapOf(BrowserSettings.Keys.CLEAR_ON_EXIT to "true")).clearDataOnExit)
    }
}

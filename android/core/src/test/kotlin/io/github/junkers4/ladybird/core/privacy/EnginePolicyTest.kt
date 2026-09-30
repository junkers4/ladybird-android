package io.github.junkers4.ladybird.core.privacy

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.json.Json
import io.github.junkers4.ladybird.core.settings.AutoplayMode
import io.github.junkers4.ladybird.core.settings.BrowserSettings
import io.github.junkers4.ladybird.core.settings.DnsMode
import io.github.junkers4.ladybird.core.repo.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePolicyTest {
    private val defaults = EnginePolicy.build(BrowserSettings(), "en")

    @Test
    @Requirement("SEC-006")
    fun gpcOnByDefault() {
        assertEquals(true, defaults["globalPrivacyControl"])
        assertEquals(false, EnginePolicy.build(BrowserSettings(globalPrivacyControl = false), "en")["globalPrivacyControl"])
    }

    @Test
    @Requirement("SEC-007")
    fun encryptedDnsByDefault() {
        assertEquals(mapOf("mode" to "tls", "server" to "9.9.9.9", "port" to 853, "dnssec" to false), defaults["dns"])
        assertEquals(mapOf("mode" to "system"), EnginePolicy.build(BrowserSettings(dns = DnsMode.SYSTEM), "en")["dns"])
        assertEquals("1.1.1.1", (EnginePolicy.build(BrowserSettings(dns = DnsMode.CLOUDFLARE), "en")["dns"] as Map<*, *>)["server"])
    }

    @Test
    @Requirement("SEC-016")
    fun defaultDenyPermissions() {
        assertEquals("block_audio", defaults["autoplay"])
        assertEquals(false, defaults["geolocation"])
        assertEquals("block_all", EnginePolicy.build(BrowserSettings(autoplay = AutoplayMode.BLOCK_ALL), "en")["autoplay"])
    }

    @Test
    @Requirement("ADB-001")
    fun listsOnByDefaultIncludingRegional() {
        val lists = defaults["contentBlockerLists"] as Map<*, *>
        for (list in EnginePolicy.Lists.ALWAYS + EnginePolicy.Lists.ANNOYANCE) assertEquals(list, true, lists[list])
        val slovak = EnginePolicy.build(BrowserSettings(), "sk-SK")["contentBlockerLists"] as Map<*, *>
        assertEquals(true, slovak["easyListCzechAndSlovak"])
        val off = EnginePolicy.build(BrowserSettings(contentBlocking = false), "en")
        assertTrue((off["contentBlockerLists"] as Map<*, *>).values.all { it == false })
        assertEquals("", off["customFilters"])
    }

    @Test
    @Requirement("ADB-001")
    fun listIdentifiersExistInLadybird() {
        val settings = Repo.text("ladybird/Libraries/LibWebView/Settings.cpp")
        for (list in EnginePolicy.Lists.ALWAYS + EnginePolicy.Lists.ANNOYANCE + EnginePolicy.Lists.REGIONAL.values)
            assertTrue("$list is not a Ladybird built-in list", settings.contains("add_list(\"$list\"sv"))
    }

    @Test
    @Requirement("ADB-005")
    fun filterListUpdates() {
        assertEquals(true, defaults["filterListUpdates"])
        assertEquals(true, defaults["backgroundNetworking"])
        val off = EnginePolicy.build(BrowserSettings(filterListUpdates = false), "en")
        assertEquals(false, off["filterListUpdates"])
    }

    @Test
    @Requirement("ADB-002", "ADB-003")
    fun customFiltersCarryYoutubeRulesAndSiteExceptions() {
        val filters = EnginePolicy.build(BrowserSettings(shieldsDownSites = setOf("news.example")), "en")["customFilters"] as String
        assertTrue(filters.contains("youtube.com##ytd-ad-slot-renderer"))
        assertTrue(filters.contains("@@||news.example^\$document"))
        val noYoutube = EnginePolicy.build(BrowserSettings(youtubeAdBlocking = false), "en")["customFilters"] as String
        assertFalse(noYoutube.contains("youtube.com##"))
    }

    @Test
    @Requirement("ARCH-004")
    fun policyKeysAreHandledNatively() {
        val native = Repo.text("native/src/AndroidApplication.cpp")
        for (key in defaults.keys) assertTrue("native side ignores $key", native.contains("\"$key\"sv"))
        Json.parseObject(EnginePolicy.toJson(BrowserSettings(), "en"))
    }
}

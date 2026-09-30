package io.github.junkers4.ladybird.core.privacy

import io.github.junkers4.ladybird.core.adblock.FilterRules
import io.github.junkers4.ladybird.core.adblock.YouTubeRules
import io.github.junkers4.ladybird.core.json.Json
import io.github.junkers4.ladybird.core.settings.AutoplayMode
import io.github.junkers4.ladybird.core.settings.BrowserSettings

/**
 * Turns the app settings into the policy JSON understood by AndroidApplication::apply_policy
 * (native/src/AndroidApplication.cpp). Requirements SEC-006, SEC-007, SEC-016, ADB-001, ADB-002,
 * ADB-003, ADB-005.
 */
object EnginePolicy {
    /** Ladybird's built-in list identifiers (LibWebView/Settings.cpp). */
    object Lists {
        const val EASYLIST = "easyList"
        const val EASYPRIVACY = "easyPrivacy"
        const val COOKIES = "easyListCookie"
        const val ANNOYANCES = "fanboyAnnoyances"
        const val SOCIAL = "fanboySocial"
        const val ANTI_ADBLOCK_WALLS = "adblockWarningRemoval"

        val ALWAYS = listOf(EASYLIST, EASYPRIVACY, ANTI_ADBLOCK_WALLS)
        val ANNOYANCE = listOf(COOKIES, ANNOYANCES, SOCIAL)

        /** Language code -> Ladybird's regional list. */
        val REGIONAL = mapOf(
            "sk" to "easyListCzechAndSlovak", "cs" to "easyListCzechAndSlovak", "de" to "easyListGermany",
            "nl" to "easyListDutch", "it" to "easyListItaly", "pl" to "easyListPolish", "pt" to "easyListPortuguese",
            "es" to "easyListSpanish", "he" to "easyListHebrew", "lt" to "easyListLithuania", "zh" to "easyListChina",
            "bg" to "bulgarianList", "vi" to "abpvn", "id" to "abpindo", "ms" to "abpindo", "hi" to "indianList",
            "no" to "nordicFilters", "nb" to "nordicFilters", "da" to "nordicFilters", "sv" to "nordicFilters", "fi" to "nordicFilters",
        )
    }

    fun build(settings: BrowserSettings, languageCode: String): Map<String, Any?> {
        val lists = linkedMapOf<String, Boolean>()
        for (list in Lists.ALWAYS) lists[list] = settings.contentBlocking
        for (list in Lists.ANNOYANCE) lists[list] = settings.contentBlocking && settings.annoyanceBlocking
        Lists.REGIONAL[languageCode.lowercase().substringBefore('-').substringBefore('_')]?.let { lists[it] = settings.contentBlocking }

        val builtIn = if (settings.contentBlocking && settings.youtubeAdBlocking) YouTubeRules.ALL else emptyList()
        val customFilters = if (settings.contentBlocking) {
            FilterRules.composeCustomFilters(builtIn, settings.customFilters, settings.shieldsDownSites)
        } else {
            ""
        }

        val dnsServer = settings.dns.server
        return linkedMapOf(
            "globalPrivacyControl" to settings.globalPrivacyControl,
            "autoplay" to when (settings.autoplay) {
                AutoplayMode.ALLOW -> "allow"
                AutoplayMode.BLOCK_AUDIO -> "block_audio"
                AutoplayMode.BLOCK_ALL -> "block_all"
            },
            "dns" to if (dnsServer == null) mapOf("mode" to "system") else mapOf("mode" to "tls", "server" to dnsServer.first, "port" to dnsServer.second, "dnssec" to false),
            "contentBlockerLists" to lists,
            "customFilters" to customFilters,
            "filterListUpdates" to settings.filterListUpdates,
            "backgroundNetworking" to settings.filterListUpdates,
            "searchEngine" to settings.searchEngine.ladybirdName,
            "newTabPage" to "about:newtab",
            "geolocation" to settings.geolocation,
        )
    }

    fun toJson(settings: BrowserSettings, languageCode: String): String = Json.write(build(settings, languageCode))
}

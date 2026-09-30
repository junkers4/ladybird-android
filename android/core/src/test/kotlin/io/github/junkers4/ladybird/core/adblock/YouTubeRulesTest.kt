package io.github.junkers4.ladybird.core.adblock

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Requirement("ADB-003")
class YouTubeRulesTest {
    @Test
    fun rulesAreValidFilterSyntax() {
        val result = FilterRules.validate(YouTubeRules.ALL.joinToString("\n"))
        assertEquals(emptyList<FilterRules.Problem>(), result.problems)
        assertEquals(YouTubeRules.ALL.size, result.accepted.size)
    }

    @Test
    fun coversAdEndpointsAndSlots() {
        assertTrue(YouTubeRules.NETWORK.any { it.contains("/api/stats/ads") })
        assertTrue(YouTubeRules.NETWORK.any { it.contains("doubleclick.net") })
        assertTrue(YouTubeRules.COSMETIC.any { it.endsWith("ytd-ad-slot-renderer") })
        // Never block the video itself or the player API.
        assertTrue(YouTubeRules.ALL.none { it.contains("googlevideo.com") || it.contains("/youtubei/v1/player") && !it.contains("adformat") })
    }

    @Test
    fun scriptletIsPackaged() {
        val script = YouTubeRules.scriptlet()
        assertTrue(script.contains("adPlacements"))
        assertTrue(script.contains("playerAds"))
    }
}

package io.github.junkers4.ladybird.core.adblock

/**
 * Built-in YouTube ad blocking (requirement ADB-003).
 *
 * Network rules block YouTube's ad, ad-measurement and ad-tracking endpoints; cosmetic rules hide the
 * ad slots in the page. Ads served from the same video CDN as the video itself cannot be blocked by
 * URL; for those the player-response scriptlet ([SCRIPTLET_RESOURCE]) strips ad placements, which needs
 * document-start script injection in the engine (ADB-006, planned).
 */
object YouTubeRules {
    const val SCRIPTLET_RESOURCE = "/io/github/junkers4/ladybird/core/adblock/youtube-player-prune.js"

    val NETWORK = listOf(
        "||youtube.com/api/stats/ads\$xhr,image,ping",
        "||youtube.com/api/stats/qoe?*adformat=\$xhr,image,ping",
        "||youtube.com/pagead/\$~document",
        "||youtube.com/ptracking\$image,ping,xhr",
        "||youtube.com/get_midroll_\$xhr",
        "||youtube.com/youtubei/v1/log_event?*adformat\$xhr",
        "||youtube.com/api/stats/atr\$xhr,ping",
        "||youtube-nocookie.com/pagead/\$~document",
        "||googleads.g.doubleclick.net^",
        "||static.doubleclick.net^",
        "||ad.doubleclick.net^",
        "||pagead2.googlesyndication.com^",
        "||googleadservices.com^",
        "||imasdk.googleapis.com/js/sdkloader/ima3.js\$script,domain=youtube.com",
        "||www.youtube.com/generate_204\$xhr,ping",
    )

    val COSMETIC = listOf(
        "youtube.com##ytd-ad-slot-renderer",
        "youtube.com##ytd-in-feed-ad-layout-renderer",
        "youtube.com##ytd-promoted-sparkles-web-renderer",
        "youtube.com##ytd-promoted-video-renderer",
        "youtube.com##ytd-display-ad-renderer",
        "youtube.com##ytd-banner-promo-renderer",
        "youtube.com##ytd-statement-banner-renderer",
        "youtube.com##ytd-player-legacy-desktop-watch-ads-renderer",
        "youtube.com###masthead-ad",
        "youtube.com###player-ads",
        "youtube.com##.ytp-ad-module",
        "youtube.com##.ytp-ad-overlay-container",
        "youtube.com##.ytp-ad-image-overlay",
        "youtube.com##ytd-rich-item-renderer:has(> #content > ytd-ad-slot-renderer)",
        "m.youtube.com##ytm-promoted-sparkles-web-renderer",
        "m.youtube.com##ytm-companion-ad-renderer",
        "m.youtube.com##ad-slot-renderer",
        "m.youtube.com##.ad-container",
    )

    val ALL = NETWORK + COSMETIC

    fun scriptlet(): String = YouTubeRules::class.java.getResourceAsStream(SCRIPTLET_RESOURCE)
        ?.bufferedReader()?.use { it.readText() }
        ?: error("missing $SCRIPTLET_RESOURCE")
}

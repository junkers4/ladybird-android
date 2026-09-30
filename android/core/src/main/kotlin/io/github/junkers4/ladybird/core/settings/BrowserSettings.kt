package io.github.junkers4.ladybird.core.settings

import io.github.junkers4.ladybird.core.url.SearchEngine

/** Where names are resolved (requirement SEC-007). */
enum class DnsMode(val id: String) {
    SYSTEM("system"),
    QUAD9("quad9"),
    CLOUDFLARE("cloudflare"),
    MULLVAD("mullvad"),
    ;

    /** DNS-over-TLS server (host, port) or null for the system resolver. */
    val server: Pair<String, Int>?
        get() = when (this) {
            SYSTEM -> null
            QUAD9 -> "9.9.9.9" to 853
            CLOUDFLARE -> "1.1.1.1" to 853
            MULLVAD -> "194.242.2.2" to 853
        }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: QUAD9
    }
}

enum class AutoplayMode(val id: String) {
    ALLOW("allow"),
    BLOCK_AUDIO("block_audio"),
    BLOCK_ALL("block_all"),
    ;

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: BLOCK_AUDIO
    }
}

enum class ThemeMode(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Every user-facing setting of the app with its secure default (requirement UI-013).
 * Persistence is done by the app through [SettingsStorage].
 */
data class BrowserSettings(
    val searchEngine: SearchEngine = SearchEngine.DEFAULT,
    val httpsOnly: Boolean = true,
    val httpsOnlyExceptions: Set<String> = emptySet(),
    val stripTrackingParameters: Boolean = true,
    val globalPrivacyControl: Boolean = true,
    val dns: DnsMode = DnsMode.QUAD9,
    val contentBlocking: Boolean = true,
    val annoyanceBlocking: Boolean = true,
    val youtubeAdBlocking: Boolean = true,
    val customFilters: String = "",
    val shieldsDownSites: Set<String> = emptySet(),
    val filterListUpdates: Boolean = true,
    val autoplay: AutoplayMode = AutoplayMode.BLOCK_AUDIO,
    val geolocation: Boolean = false,
    val javascriptDisabledSites: Set<String> = emptySet(),
    val incognitoKeyboard: Boolean = true,
    val screenshotProtection: Boolean = false,
    val clearDataOnExit: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val confirmExternalApps: Boolean = true,
) {
    fun toMap(): Map<String, String> = mapOf(
        Keys.SEARCH_ENGINE to searchEngine.id,
        Keys.HTTPS_ONLY to httpsOnly.toString(),
        Keys.HTTPS_ONLY_EXCEPTIONS to encodeSet(httpsOnlyExceptions),
        Keys.STRIP_TRACKING to stripTrackingParameters.toString(),
        Keys.GPC to globalPrivacyControl.toString(),
        Keys.DNS to dns.id,
        Keys.CONTENT_BLOCKING to contentBlocking.toString(),
        Keys.ANNOYANCE_BLOCKING to annoyanceBlocking.toString(),
        Keys.YOUTUBE_ADS to youtubeAdBlocking.toString(),
        Keys.CUSTOM_FILTERS to customFilters,
        Keys.SHIELDS_DOWN to encodeSet(shieldsDownSites),
        Keys.FILTER_UPDATES to filterListUpdates.toString(),
        Keys.AUTOPLAY to autoplay.id,
        Keys.GEOLOCATION to geolocation.toString(),
        Keys.JS_DISABLED to encodeSet(javascriptDisabledSites),
        Keys.INCOGNITO_KEYBOARD to incognitoKeyboard.toString(),
        Keys.SCREENSHOT_PROTECTION to screenshotProtection.toString(),
        Keys.CLEAR_ON_EXIT to clearDataOnExit.toString(),
        Keys.THEME to theme.id,
        Keys.CONFIRM_EXTERNAL to confirmExternalApps.toString(),
    )

    object Keys {
        const val SEARCH_ENGINE = "search_engine"
        const val HTTPS_ONLY = "https_only"
        const val HTTPS_ONLY_EXCEPTIONS = "https_only_exceptions"
        const val STRIP_TRACKING = "strip_tracking_parameters"
        const val GPC = "global_privacy_control"
        const val DNS = "dns"
        const val CONTENT_BLOCKING = "content_blocking"
        const val ANNOYANCE_BLOCKING = "annoyance_blocking"
        const val YOUTUBE_ADS = "youtube_ad_blocking"
        const val CUSTOM_FILTERS = "custom_filters"
        const val SHIELDS_DOWN = "shields_down_sites"
        const val FILTER_UPDATES = "filter_list_updates"
        const val AUTOPLAY = "autoplay"
        const val GEOLOCATION = "geolocation"
        const val JS_DISABLED = "javascript_disabled_sites"
        const val INCOGNITO_KEYBOARD = "incognito_keyboard"
        const val SCREENSHOT_PROTECTION = "screenshot_protection"
        const val CLEAR_ON_EXIT = "clear_data_on_exit"
        const val THEME = "theme"
        const val CONFIRM_EXTERNAL = "confirm_external_apps"
    }

    companion object {
        /** Missing or unparseable values fall back to the secure defaults. */
        fun fromMap(map: Map<String, String?>): BrowserSettings {
            val defaults = BrowserSettings()
            fun bool(key: String, default: Boolean) = map[key]?.toBooleanStrictOrNull() ?: default
            return BrowserSettings(
                searchEngine = SearchEngine.byId(map[Keys.SEARCH_ENGINE]),
                httpsOnly = bool(Keys.HTTPS_ONLY, defaults.httpsOnly),
                httpsOnlyExceptions = decodeSet(map[Keys.HTTPS_ONLY_EXCEPTIONS]),
                stripTrackingParameters = bool(Keys.STRIP_TRACKING, defaults.stripTrackingParameters),
                globalPrivacyControl = bool(Keys.GPC, defaults.globalPrivacyControl),
                dns = DnsMode.byId(map[Keys.DNS]),
                contentBlocking = bool(Keys.CONTENT_BLOCKING, defaults.contentBlocking),
                annoyanceBlocking = bool(Keys.ANNOYANCE_BLOCKING, defaults.annoyanceBlocking),
                youtubeAdBlocking = bool(Keys.YOUTUBE_ADS, defaults.youtubeAdBlocking),
                customFilters = map[Keys.CUSTOM_FILTERS] ?: "",
                shieldsDownSites = decodeSet(map[Keys.SHIELDS_DOWN]),
                filterListUpdates = bool(Keys.FILTER_UPDATES, defaults.filterListUpdates),
                autoplay = AutoplayMode.byId(map[Keys.AUTOPLAY]),
                geolocation = bool(Keys.GEOLOCATION, defaults.geolocation),
                javascriptDisabledSites = decodeSet(map[Keys.JS_DISABLED]),
                incognitoKeyboard = bool(Keys.INCOGNITO_KEYBOARD, defaults.incognitoKeyboard),
                screenshotProtection = bool(Keys.SCREENSHOT_PROTECTION, defaults.screenshotProtection),
                clearDataOnExit = bool(Keys.CLEAR_ON_EXIT, defaults.clearDataOnExit),
                theme = ThemeMode.byId(map[Keys.THEME]),
                confirmExternalApps = bool(Keys.CONFIRM_EXTERNAL, defaults.confirmExternalApps),
            )
        }

        private fun encodeSet(values: Set<String>) = values.sorted().joinToString("\n")
        private fun decodeSet(value: String?) = value?.split('\n')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }
}

/** Key/value persistence (SharedPreferences on Android, a map in tests). */
interface SettingsStorage {
    fun read(): Map<String, String?>
    fun write(values: Map<String, String>)
}

class SettingsRepository(private val storage: SettingsStorage) {
    fun load(): BrowserSettings = BrowserSettings.fromMap(storage.read())

    fun update(transform: (BrowserSettings) -> BrowserSettings): BrowserSettings {
        val updated = transform(load())
        storage.write(updated.toMap())
        return updated
    }
}

package org.serenityos.ladybird

import android.content.Context
import android.content.SharedPreferences

enum class SearchEngine(val displayName: String, val template: String) {
    Google("Google", "https://www.google.com/search?q=%s"),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    // The html.duckduckgo.com endpoint works with scripting disabled and never
    // serves bot challenges — a reliable fallback while the JS engine matures.
    DuckDuckGoHtml("DuckDuckGo (no JS)", "https://html.duckduckgo.com/html/?q=%s"),
    Bing("Bing", "https://www.bing.com/search?q=%s"),
    Kagi("Kagi", "https://kagi.com/search?q=%s"),
    Brave("Brave", "https://search.brave.com/search?q=%s"),
    Ecosia("Ecosia", "https://www.ecosia.org/search?q=%s"),
    Yandex("Yandex", "https://yandex.com/search/?text=%s"),
    Baidu("Baidu", "https://www.baidu.com/s?wd=%s");

    fun urlFor(query: String): String = template.format(android.net.Uri.encode(query))

    companion object {
        fun from(name: String?): SearchEngine = entries.firstOrNull { it.name == name } ?: DEFAULT

        // Google's anti-bot pipeline blocks results on most mobile networks, and
        // the JS-heavy duckduckgo.com SPA renders blank while Ladybird's engine
        // matures. Default to the no-JS html.duckduckgo.com endpoint, which
        // returns fully-rendered results. Other engines stay available in Settings.
        val DEFAULT = DuckDuckGoHtml
    }
}

enum class ColorSchemePreference(val nativeValue: Int) {
    Auto(0), Light(1), Dark(2);

    companion object {
        fun from(name: String?): ColorSchemePreference = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/**
 * Identifies a User-Agent preset that the engine can spoof. The native side
 * understands these identifiers through the `spoof-user-agent` debug request
 * with the full UA string as argument; the strings below mirror the
 * `WebView::user_agents` table in libwebview.
 *
 * `Default` keeps the engine-provided UA (which advertises Ladybird and is
 * frequently misclassified by anti-bot services such as reCAPTCHA).
 */
enum class UserAgentPreset(val displayName: String, val uaString: String?, val platformString: String?) {
    Default("Default (Ladybird)", null, null),
    ChromeAndroid(
        "Chrome (Android)",
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36",
        "Linux armv8l"
    ),
    ChromeDesktop(
        "Chrome (Desktop)",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Linux x86_64"
    ),
    FirefoxAndroid(
        "Firefox (Android)",
        "Mozilla/5.0 (Android 14; Mobile; rv:134.0) Gecko/134.0 Firefox/134.0",
        "Linux armv8l"
    ),
    SafariIOS(
        "Safari (iOS)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        "iPhone"
    );

    companion object {
        fun from(name: String?): UserAgentPreset = entries.firstOrNull { it.name == name } ?: ChromeAndroid
    }
}

enum class NavigatorCompatibility(val displayName: String, val nativeName: String) {
    Chrome("Chrome", "chrome"),
    Gecko("Gecko (Firefox)", "gecko"),
    WebKit("WebKit (Safari)", "webkit");

    companion object {
        fun from(name: String?): NavigatorCompatibility = entries.firstOrNull { it.name == name } ?: Chrome
    }
}

/**
 * A browsing "compartment": the network the browser routes through. Each mode
 * recolors the whole UI and (when wired) sends traffic through its local proxy.
 *
 *  - [Normal] talks to the internet directly.
 *  - [Tor] routes through the Tor SOCKS proxy (default 127.0.0.1:9050).
 *  - [I2P] routes through the I2P HTTP proxy (default 127.0.0.1:4444).
 *
 * The proxy endpoints are the conventional local daemon ports; the daemons
 * themselves are bundled/started separately. The mode is consumed by the
 * request path and by the theme.
 */
/** UI languages offered in Settings. [tag] is a BCP-47 language tag, or "" to
 *  follow the system language. [displayName] is shown in its own language. */
enum class AppLanguage(val tag: String, val displayName: String) {
    System("", "System default"),
    English("en", "English"),
    Chinese("zh", "中文"),
    Russian("ru", "Русский"),
    Spanish("es", "Español"),
    Slovak("sk", "Slovenčina"),
    Hindi("hi", "हिन्दी");

    companion object {
        fun from(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: System
    }
}

enum class NetworkMode(
    val displayName: String,
    val proxyType: String?,   // null = direct, "socks5", or "http"
    val proxyHost: String?,
    val proxyPort: Int,
) {
    Normal("Normal", null, null, 0),
    Tor("Tor", "socks5", "127.0.0.1", 9050),
    I2P("I2P", "http", "127.0.0.1", 4444);

    val isProxied: Boolean get() = proxyType != null

    /** curl proxy spec for this compartment (e.g. "socks5://127.0.0.1:9050"),
     *  or "" for a direct connection. Known up front so routing can be applied
     *  before the daemon finishes bootstrapping (prevents direct leaks). */
    val proxySpec: String
        get() = if (proxyType != null && proxyHost != null) "$proxyType://$proxyHost:$proxyPort" else ""

    companion object {
        fun from(name: String?): NetworkMode = entries.firstOrNull { it.name == name } ?: Normal
    }
}

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ladybird_prefs", Context.MODE_PRIVATE)

    init {
        // One-time migration. Earlier builds shipped a desktop UA preset as the
        // effective identity, which got persisted on devices and then kept
        // overriding the (now correct) mobile default — making Google serve
        // reCAPTCHA because a desktop Linux Chrome UA on a touch phone is an
        // obvious anti-bot tell. Bump the schema version and force any stored
        // desktop UA back to the mobile Chrome (Android) preset exactly once,
        // without touching the user's other settings.
        val storedVersion = prefs.getInt(KEY_SETTINGS_VERSION, 0)
        if (storedVersion < CURRENT_SETTINGS_VERSION) {
            val ua = prefs.getString(KEY_UA, null)
            val editor = prefs.edit()
            if (ua == UserAgentPreset.ChromeDesktop.name)
                editor.putString(KEY_UA, UserAgentPreset.ChromeAndroid.name)
            // v2: the old default home was ladybird.org, which made the browser
            // feel like Ladybird rather than a Chrome/Vanadium-style client. Move
            // anyone still on that default onto the new local New Tab page.
            val home = prefs.getString(KEY_HOME, null)
            if (home == null || home == LEGACY_DEFAULT_HOME)
                editor.putString(KEY_HOME, DEFAULT_HOME)
            // v3: Google as the default search engine reliably lands on the
            // reCAPTCHA sorry page for our networks. Move anyone still on the old
            // Google default (or with no stored choice) onto DuckDuckGo, which
            // returns results. A deliberate non-Google choice is left untouched.
            val search = prefs.getString(KEY_SEARCH, null)
            if (search == null || search == SearchEngine.Google.name)
                editor.putString(KEY_SEARCH, SearchEngine.DEFAULT.name)
            // v4: the JS-heavy duckduckgo.com SPA renders as a blank white page in
            // the current engine. Move anyone on that endpoint to the no-JS
            // html.duckduckgo.com endpoint, which renders results correctly.
            if (search == SearchEngine.DuckDuckGo.name)
                editor.putString(KEY_SEARCH, SearchEngine.DuckDuckGoHtml.name)
            editor.putInt(KEY_SETTINGS_VERSION, CURRENT_SETTINGS_VERSION)
            editor.apply()
        }
    }

    var homePage: String
        get() = prefs.getString(KEY_HOME, DEFAULT_HOME) ?: DEFAULT_HOME
        set(value) = prefs.edit().putString(KEY_HOME, value.ifBlank { DEFAULT_HOME }).apply()

    var searchEngine: SearchEngine
        get() = SearchEngine.from(prefs.getString(KEY_SEARCH, SearchEngine.DEFAULT.name))
        set(value) = prefs.edit().putString(KEY_SEARCH, value.name).apply()

    var colorScheme: ColorSchemePreference
        get() = ColorSchemePreference.from(prefs.getString(KEY_COLOR, ColorSchemePreference.Auto.name))
        set(value) = prefs.edit().putString(KEY_COLOR, value.name).apply()

    var javascriptHelpersEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS, true)
        set(value) = prefs.edit().putBoolean(KEY_JS, value).apply()

    /**
      * Default to mobile Chrome. The engine's own default Android UA is already
      * a clean "Chrome/146 ... Mobile Safari" string with a matching
      * "Linux armv8l" platform, so advertising the same here keeps the UA,
      * navigator.platform and layout viewport mutually consistent — a
      * desktop UA on a phone is itself a strong anti-bot signal that pushed
      * Google straight into reCAPTCHA. Users can still pick desktop in Settings.
     */
    var userAgent: UserAgentPreset
          get() = UserAgentPreset.from(prefs.getString(KEY_UA, UserAgentPreset.ChromeAndroid.name))
        set(value) = prefs.edit().putString(KEY_UA, value.name).apply()

    var navigatorCompatibility: NavigatorCompatibility
        get() = NavigatorCompatibility.from(prefs.getString(KEY_NAV_COMPAT, NavigatorCompatibility.Chrome.name))
        set(value) = prefs.edit().putString(KEY_NAV_COMPAT, value.name).apply()

    var pinchZoomEnabled: Boolean
        get() = prefs.getBoolean(KEY_PINCH, true)
        set(value) = prefs.edit().putBoolean(KEY_PINCH, value).apply()

    /**
     * "Desktop site" toggle, stored independently of [userAgent] so toggling it
     * off restores the user's chosen UA preset instead of clobbering it. The
     * effective UA is [effectiveUserAgent].
     */
    var desktopSite: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP, value).apply()

    /** The UA actually applied: ChromeDesktop when "desktop site" is on, else
     *  the user's chosen preset. */
    val effectiveUserAgent: UserAgentPreset
        get() = if (desktopSite) UserAgentPreset.ChromeDesktop else userAgent

    // ----------------------------------------------------------------------
    // Network compartment (Normal / Tor / I2P)
    // ----------------------------------------------------------------------

    var networkMode: NetworkMode
        get() = NetworkMode.from(prefs.getString(KEY_NET_MODE, NetworkMode.Normal.name))
        set(value) = prefs.edit().putString(KEY_NET_MODE, value.name).apply()

    /**
     * Keep the I2P router connection alive even when not browsing through I2P.
     * The I2P network needs time to integrate a router into its tunnels, so
     * tearing it down on every switch is wasteful; defaults to on. (Tor connects
     * quickly per session, so it has no equivalent.)
     */
    var i2pKeepRunning: Boolean
        get() = prefs.getBoolean(KEY_I2P_KEEP, true)
        set(value) = prefs.edit().putBoolean(KEY_I2P_KEEP, value).apply()

    /**
     * Whether i2pd fetches addressbook subscriptions — hosts.txt lists published
     * inside I2P that map .i2p names to destinations — so eepsites resolve
     * automatically instead of needing a jump service. On by default. Applied
     * when i2pd next starts.
     */
    var i2pAddressbookSubscriptions: Boolean
        get() = prefs.getBoolean(KEY_I2P_SUBS, true)
        set(value) = prefs.edit().putBoolean(KEY_I2P_SUBS, value).apply()

    /** When off, browsing history is never recorded. */
    var saveHistory: Boolean
        get() = prefs.getBoolean(KEY_SAVE_HISTORY, true)
        set(value) = prefs.edit().putBoolean(KEY_SAVE_HISTORY, value).apply()

    /** When on, history + cookies + cache are wiped every time the app closes. */
    var clearOnExit: Boolean
        get() = prefs.getBoolean(KEY_CLEAR_ON_EXIT, false)
        set(value) = prefs.edit().putBoolean(KEY_CLEAR_ON_EXIT, value).apply()

    // Web-data clears requested from a screen without a live web view (the history
    // page); the browser activity applies them on its next resume. -1 / false = none.
    var pendingClearCookiesSeconds: Long
        get() = prefs.getLong(KEY_PENDING_COOKIES, -1L)
        set(value) = prefs.edit().putLong(KEY_PENDING_COOKIES, value).apply()
    var pendingClearCache: Boolean
        get() = prefs.getBoolean(KEY_PENDING_CACHE, false)
        set(value) = prefs.edit().putBoolean(KEY_PENDING_CACHE, value).apply()

    /** Selected UI language (BCP-47 tag, or "" for system default). */
    var language: AppLanguage
        get() = AppLanguage.from(prefs.getString(KEY_LANGUAGE, ""))
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.tag).apply()

    /** Master switch for the per-site daily time limits feature. When off, usage
     *  isn't tracked and no site is ever blocked. */
    var siteLimitsEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIMITS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_LIMITS_ENABLED, value).apply()

    // ----------------------------------------------------------------------
    // Background playback allowlist
    //
    // Which sites are allowed to keep their audio/video playing when the app is
    // backgrounded or the screen is locked. Stored as a set of normalized hosts;
    // a host matches itself and any subdomain. Same idea as site limits, just a
    // plain allowlist instead of per-host minutes.
    // ----------------------------------------------------------------------

    var backgroundAudioEnabled: Boolean
        get() = prefs.getBoolean(KEY_BG_AUDIO_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BG_AUDIO_ENABLED, value).apply()

    /** HTTPS-only mode: upgrade plain http:// navigations to https:// (Vanadium /
     *  Brave style). Loopback, .onion and .i2p hosts are left on http. */
    var httpsOnly: Boolean
        get() = prefs.getBoolean(KEY_HTTPS_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_HTTPS_ONLY, value).apply()

    /** Ad & tracker blocking via LibWeb's content blocker (EasyList + Brave
     *  lists): network blocking plus cosmetic element hiding. On by default. */
    var adBlockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()

    /** All hosts allowed to play in the background (sorted for display). */
    fun backgroundAudioSites(): Set<String> =
        prefs.getStringSet(KEY_BG_AUDIO_SITES, emptySet())?.toSortedSet() ?: emptySet()

    /** True when [host] (or its parent domain) may keep playing in the background. */
    fun isBackgroundAudioAllowed(host: String): Boolean {
        if (!backgroundAudioEnabled) return false
        val h = normalizeHost(host)
        if (h.isBlank()) return false
        return backgroundAudioSites().any { h == it || h.endsWith(".$it") }
    }

    fun addBackgroundAudioSite(host: String) {
        val h = normalizeHost(host)
        if (h.isBlank()) return
        prefs.edit().putStringSet(KEY_BG_AUDIO_SITES, backgroundAudioSites() + h).apply()
    }

    fun removeBackgroundAudioSite(host: String) {
        prefs.edit().putStringSet(KEY_BG_AUDIO_SITES, backgroundAudioSites() - normalizeHost(host)).apply()
    }

    // ----------------------------------------------------------------------
    // Per-site daily time limits
    //
    // Limits are stored as a string set of "host<SEP>minutes" entries. Usage is
    // tracked per host as accumulated seconds for the current day; a stored day
    // stamp (yyyy-MM-dd) resets all counters when the day rolls over.
    // ----------------------------------------------------------------------

    /** All configured limits, host -> minutes per day. */
    fun siteLimits(): Map<String, Int> {
        val set = prefs.getStringSet(KEY_LIMITS, emptySet()) ?: emptySet()
        return set.mapNotNull { entry ->
            val i = entry.indexOf(SEP)
            if (i <= 0) return@mapNotNull null
            val host = entry.substring(0, i)
            val minutes = entry.substring(i + 1).toIntOrNull() ?: return@mapNotNull null
            host to minutes
        }.toMap()
    }

    /** Daily limit in minutes for [host], or null if none is set. */
    fun limitMinutesFor(host: String): Int? = siteLimits()[normalizeHost(host)]

    fun setSiteLimit(host: String, minutes: Int) {
        val h = normalizeHost(host)
        if (h.isBlank()) return
        val map = siteLimits().toMutableMap()
        map[h] = minutes.coerceIn(1, 24 * 60)
        writeLimits(map)
    }

    fun removeSiteLimit(host: String) {
        val map = siteLimits().toMutableMap()
        map.remove(normalizeHost(host))
        writeLimits(map)
    }

    private fun writeLimits(map: Map<String, Int>) {
        val set = map.entries.map { it.key + SEP + it.value }.toSet()
        prefs.edit().putStringSet(KEY_LIMITS, set).apply()
    }

    /** Seconds spent on [host] so far today (auto-resets at midnight). */
    fun secondsUsedToday(host: String): Int {
        rolloverIfNeeded()
        return prefs.getInt(usageKey(normalizeHost(host)), 0)
    }

    /** Per-host seconds spent today, across all visited sites (for the overview). */
    fun allUsageToday(): Map<String, Int> {
        rolloverIfNeeded()
        return prefs.all.entries
            .filter { it.key.startsWith(USAGE_PREFIX) && it.key != KEY_USAGE_DAY && it.value is Int }
            .associate { it.key.removePrefix(USAGE_PREFIX) to (it.value as Int) }
    }

    /** Add [seconds] of usage to [host] for today. */
    fun addUsage(host: String, seconds: Int) {
        if (seconds <= 0 || !siteLimitsEnabled) return
        rolloverIfNeeded()
        val key = usageKey(normalizeHost(host))
        prefs.edit().putInt(key, prefs.getInt(key, 0) + seconds).apply()
    }

    /** True when [host] has a limit and today's usage has reached it. */
    fun isOverLimit(host: String): Boolean {
        if (!siteLimitsEnabled) return false
        val limit = limitMinutesFor(host) ?: return false
        return secondsUsedToday(host) >= limit * 60
    }

    private fun rolloverIfNeeded() {
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString(KEY_USAGE_DAY, null) != today) {
            val editor = prefs.edit()
            // Drop all per-host usage counters from previous days.
            prefs.all.keys.filter { it.startsWith(USAGE_PREFIX) }.forEach { editor.remove(it) }
            editor.putString(KEY_USAGE_DAY, today)
            editor.apply()
        }
    }

    private fun usageKey(host: String) = USAGE_PREFIX + host

    /** Reduce a URL or host string to a bare, comparable host (no scheme/www/port/path). */
    fun normalizeHost(input: String): String {
        var s = input.trim().lowercase()
        val scheme = s.indexOf("://")
        if (scheme >= 0) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringBefore(':')        // drop port
        if (s.startsWith("www.")) s = s.substring(4)
        return s
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    companion object {
        // Sentinel handled by the activity: renders a local Chrome-style New Tab
        // page instead of fetching a remote site.
        const val DEFAULT_HOME = "about:newtab"
        const val LEGACY_DEFAULT_HOME = "https://ladybird.org/"
        private const val KEY_HOME = "home_page"
        private const val KEY_SEARCH = "search_engine"
        private const val KEY_COLOR = "color_scheme"
        private const val KEY_JS = "js_helpers"
        private const val KEY_UA = "user_agent_preset"
        private const val KEY_NAV_COMPAT = "navigator_compat"
        private const val KEY_PINCH = "pinch_zoom_enabled"
        private const val KEY_DESKTOP = "desktop_site"
        private const val KEY_NET_MODE = "network_mode"
        private const val KEY_I2P_KEEP = "i2p_keep_running"
        private const val KEY_I2P_SUBS = "i2p_addressbook_subscriptions"
        private const val KEY_SAVE_HISTORY = "save_history"
        private const val KEY_CLEAR_ON_EXIT = "clear_on_exit"
        private const val KEY_PENDING_COOKIES = "pending_clear_cookies_seconds"
        private const val KEY_PENDING_CACHE = "pending_clear_cache"
        private const val KEY_LIMITS_ENABLED = "site_limits_enabled"
        private const val KEY_BG_AUDIO_ENABLED = "background_audio_enabled"
        private const val KEY_BG_AUDIO_SITES = "background_audio_sites"
        private const val KEY_HTTPS_ONLY = "https_only"
        private const val KEY_ADBLOCK = "ad_block_enabled"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_LIMITS = "site_time_limits"
        private const val KEY_USAGE_DAY = "usage_day"
        private const val USAGE_PREFIX = "usage_"
        private const val SEP = '\u0001'
        private const val KEY_SETTINGS_VERSION = "settings_version"
        private const val CURRENT_SETTINGS_VERSION = 4
    }
}

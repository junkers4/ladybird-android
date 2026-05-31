package org.serenityos.ladybird

import android.content.Context
import android.content.SharedPreferences

enum class SearchEngine(val displayName: String, val template: String) {
    Google("Google", "https://www.google.com/search?q=%s"),
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    Bing("Bing", "https://www.bing.com/search?q=%s"),
    Kagi("Kagi", "https://kagi.com/search?q=%s"),
    Brave("Brave", "https://search.brave.com/search?q=%s"),
    Ecosia("Ecosia", "https://www.ecosia.org/search?q=%s");

    fun urlFor(query: String): String = template.format(android.net.Uri.encode(query))

    companion object {
        fun from(name: String?): SearchEngine = entries.firstOrNull { it.name == name } ?: Google
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
            editor.putInt(KEY_SETTINGS_VERSION, CURRENT_SETTINGS_VERSION)
            editor.apply()
        }
    }

    var homePage: String
        get() = prefs.getString(KEY_HOME, DEFAULT_HOME) ?: DEFAULT_HOME
        set(value) = prefs.edit().putString(KEY_HOME, value.ifBlank { DEFAULT_HOME }).apply()

    var searchEngine: SearchEngine
        get() = SearchEngine.from(prefs.getString(KEY_SEARCH, SearchEngine.Google.name))
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

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val DEFAULT_HOME = "https://ladybird.org/"
        private const val KEY_HOME = "home_page"
        private const val KEY_SEARCH = "search_engine"
        private const val KEY_COLOR = "color_scheme"
        private const val KEY_JS = "js_helpers"
        private const val KEY_UA = "user_agent_preset"
        private const val KEY_NAV_COMPAT = "navigator_compat"
        private const val KEY_PINCH = "pinch_zoom_enabled"
        private const val KEY_SETTINGS_VERSION = "settings_version"
        private const val CURRENT_SETTINGS_VERSION = 1
    }
}

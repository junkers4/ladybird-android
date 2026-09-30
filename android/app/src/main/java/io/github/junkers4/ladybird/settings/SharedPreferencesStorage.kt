package io.github.junkers4.ladybird.settings

import android.content.Context
import androidx.preference.PreferenceManager
import io.github.junkers4.ladybird.core.settings.BrowserSettings
import io.github.junkers4.ladybird.core.settings.SettingsStorage

/** Stores BrowserSettings in the default SharedPreferences, which the settings screen edits directly. */
class SharedPreferencesStorage(context: Context) : SettingsStorage {
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    override fun read(): Map<String, String?> = preferences.all.mapValues { (_, value) ->
        when (value) {
            is Set<*> -> value.joinToString("\n")
            null -> null
            else -> value.toString()
        }
    }

    override fun write(values: Map<String, String>) {
        preferences.edit().apply {
            for ((key, value) in values) {
                when (key) {
                    in BOOLEAN_KEYS -> putBoolean(key, value.toBoolean())
                    else -> putString(key, value)
                }
            }
        }.apply()
    }

    companion object {
        val BOOLEAN_KEYS = setOf(
            BrowserSettings.Keys.HTTPS_ONLY, BrowserSettings.Keys.STRIP_TRACKING, BrowserSettings.Keys.GPC,
            BrowserSettings.Keys.CONTENT_BLOCKING, BrowserSettings.Keys.ANNOYANCE_BLOCKING, BrowserSettings.Keys.YOUTUBE_ADS,
            BrowserSettings.Keys.FILTER_UPDATES, BrowserSettings.Keys.GEOLOCATION, BrowserSettings.Keys.INCOGNITO_KEYBOARD,
            BrowserSettings.Keys.SCREENSHOT_PROTECTION, BrowserSettings.Keys.CLEAR_ON_EXIT, BrowserSettings.Keys.CONFIRM_EXTERNAL,
        )
    }
}

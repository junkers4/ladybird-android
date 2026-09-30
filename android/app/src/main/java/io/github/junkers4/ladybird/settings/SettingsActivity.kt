package io.github.junkers4.ladybird.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.junkers4.ladybird.R
import io.github.junkers4.ladybird.core.adblock.FilterRules
import io.github.junkers4.ladybird.core.settings.BrowserSettings
import io.github.junkers4.ladybird.core.url.SearchEngine

/** The app's own settings (UI-013). Engine settings live in Ladybird's about:settings. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment()).commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<ListPreference>(BrowserSettings.Keys.SEARCH_ENGINE)?.apply {
                entries = SearchEngine.ALL.map { it.displayName }.toTypedArray()
                entryValues = SearchEngine.ALL.map { it.id }.toTypedArray()
                if (value == null) value = SearchEngine.DEFAULT.id
            }

            // ADB-004: invalid filters are reported and never reach the engine.
            findPreference<EditTextPreference>(BrowserSettings.Keys.CUSTOM_FILTERS)?.setOnPreferenceChangeListener { _, newValue ->
                val problems = FilterRules.validate(newValue as String).problems
                if (problems.isNotEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.custom_filters_problems)
                        .setMessage(problems.joinToString("\n") { getString(R.string.custom_filters_problem_line, it.line, it.reason) })
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
                true
            }
        }
    }
}

package org.serenityos.ladybird

import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.serenityos.ladybird.databinding.ActivitySettingsBinding

/**
 * Chrome-style settings: every row shows its current value as the summary,
 * choice rows open a radio dialog, and changes apply immediately (the
 * browser activity re-applies settings in onResume).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        binding.rowSearchEngine.setOnClickListener {
            val engines = SearchEngine.entries
            singleChoiceDialog(
                R.string.settings_search_engine,
                engines.map { it.displayName },
                engines.indexOf(settings.searchEngine)
            ) { idx -> settings.searchEngine = engines[idx]; refreshSummaries() }
        }

        binding.rowHomePage.setOnClickListener { showHomePageDialog() }

        binding.rowColorScheme.setOnClickListener {
            val schemes = ColorSchemePreference.entries
            singleChoiceDialog(
                R.string.settings_color_scheme,
                schemes.map { it.name },
                schemes.indexOf(settings.colorScheme)
            ) { idx -> settings.colorScheme = schemes[idx]; refreshSummaries() }
        }

        binding.rowUserAgent.setOnClickListener {
            val agents = UserAgentPreset.entries
            singleChoiceDialog(
                R.string.settings_user_agent,
                agents.map { it.displayName },
                agents.indexOf(settings.userAgent)
            ) { idx -> settings.userAgent = agents[idx]; refreshSummaries() }
        }

        binding.rowNavCompat.setOnClickListener {
            val compats = NavigatorCompatibility.entries
            singleChoiceDialog(
                R.string.settings_navigator_compat,
                compats.map { it.displayName },
                compats.indexOf(settings.navigatorCompatibility)
            ) { idx -> settings.navigatorCompatibility = compats[idx]; refreshSummaries() }
        }

        binding.jsSwitch.setOnCheckedChangeListener { _, checked ->
            settings.javascriptHelpersEnabled = checked
        }
        binding.rowJavascript.setOnClickListener { binding.jsSwitch.toggle() }

        binding.pinchSwitch.setOnCheckedChangeListener { _, checked ->
            settings.pinchZoomEnabled = checked
        }
        binding.rowPinch.setOnClickListener { binding.pinchSwitch.toggle() }

        binding.rowClearData.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_clear_data)
                .setMessage(R.string.settings_clear_data_confirm)
                .setPositiveButton(R.string.dialog_clear) { _, _ ->
                    HistoryStore(this).clear()
                    BookmarksStore(this).clear()
                    Toast.makeText(this, R.string.settings_clear_data_done, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        binding.rowReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_reset)
                .setMessage(R.string.settings_reset_summary)
                .setPositiveButton(R.string.dialog_reset) { _, _ ->
                    settings.resetToDefaults()
                    refreshSummaries()
                    refreshSwitches()
                }
                .setNegativeButton(R.string.dialog_cancel, null)
                .show()
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
        } catch (_: Exception) { "dev" }
        binding.aboutVersion.text = getString(R.string.settings_about_version, version)

        refreshSummaries()
        refreshSwitches()
    }

    private fun refreshSummaries() {
        binding.searchEngineSummary.text = settings.searchEngine.displayName
        binding.homePageSummary.text = settings.homePage
        binding.colorSchemeSummary.text = settings.colorScheme.name
        binding.userAgentSummary.text = settings.userAgent.displayName
        binding.navCompatSummary.text = settings.navigatorCompatibility.displayName
    }

    private fun refreshSwitches() {
        binding.jsSwitch.isChecked = settings.javascriptHelpersEnabled
        binding.pinchSwitch.isChecked = settings.pinchZoomEnabled
    }

    private fun singleChoiceDialog(titleRes: Int, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, idx ->
                onPick(idx)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showHomePageDialog() {
        val edit = EditText(this).apply {
            setText(settings.homePage)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 8, pad, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_home_page)
            .setView(container)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                settings.homePage = edit.text.toString().trim()
                refreshSummaries()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }
}

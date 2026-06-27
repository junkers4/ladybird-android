package org.serenityos.ladybird

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.serenityos.ladybird.databinding.ActivitySettingsBinding
import org.serenityos.ladybird.databinding.DialogSiteLimitBinding
import org.serenityos.ladybird.databinding.ItemSiteLimitBinding
import org.serenityos.ladybird.databinding.SheetSiteLimitsBinding

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

        // Tint the settings chrome to the active network compartment so the
        // colour identity (Tor purple / I2P) carries over from the browser.
        applyNetworkTheme()

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

        binding.rowLanguage.setOnClickListener {
            val langs = AppLanguage.entries
            singleChoiceDialog(
                R.string.settings_language,
                langs.map { langName(it) },
                langs.indexOf(settings.language)
            ) { idx ->
                settings.language = langs[idx]
                applyLanguage(langs[idx])
                refreshSummaries()
            }
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

        binding.rowSiteLimits.setOnClickListener { showSiteLimitsSheet() }

        binding.rowBackgroundAudio.setOnClickListener {
            startActivity(android.content.Intent(this, BackgroundAudioActivity::class.java))
        }

        binding.adBlockSwitch.setOnCheckedChangeListener { _, checked ->
            settings.adBlockEnabled = checked
        }
        binding.rowAdBlock.setOnClickListener { binding.adBlockSwitch.toggle() }

        binding.httpsOnlySwitch.setOnCheckedChangeListener { _, checked ->
            settings.httpsOnly = checked
        }
        binding.rowHttpsOnly.setOnClickListener { binding.httpsOnlySwitch.toggle() }

        binding.saveHistorySwitch.setOnCheckedChangeListener { _, checked ->
            settings.saveHistory = checked
        }
        binding.rowSaveHistory.setOnClickListener { binding.saveHistorySwitch.toggle() }

        binding.clearOnExitSwitch.setOnCheckedChangeListener { _, checked ->
            settings.clearOnExit = checked
        }
        binding.rowClearOnExit.setOnClickListener { binding.clearOnExitSwitch.toggle() }

        // Coloured per-compartment cards open that compartment's own settings.
        setupCompartmentCard(binding.rowCompTor, binding.compTorIcon, NetworkMode.Tor)
        setupCompartmentCard(binding.rowCompI2p, binding.compI2pIcon, NetworkMode.I2P)
        setupCompartmentCard(binding.rowCompNormal, binding.compNormalIcon, NetworkMode.Normal)

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

    private fun applyNetworkTheme() {
        val theme = NetworkTheme.of(settings.networkMode)
        if (theme.mode == NetworkMode.Normal) return
        val bar = androidx.core.content.ContextCompat.getColor(this, theme.barColor)
        val on = androidx.core.content.ContextCompat.getColor(this, theme.onColor)
        binding.settingsToolbar.setBackgroundColor(bar)
        binding.settingsToolbar.setTitleTextColor(on)
        binding.settingsToolbar.navigationIcon?.setTint(on)
        window.statusBarColor = bar
    }

    /** Language names show in their own language, except the system option,
     *  which is localized to the current UI language. */
    private fun langName(l: AppLanguage): String =
        if (l == AppLanguage.System) getString(R.string.language_system_default) else l.displayName

    private fun refreshSummaries() {
        binding.searchEngineSummary.text = settings.searchEngine.displayName
        binding.homePageSummary.text = settings.homePage
        binding.colorSchemeSummary.text = settings.colorScheme.name
        binding.languageSummary.text = langName(settings.language)
        binding.userAgentSummary.text = settings.userAgent.displayName
        binding.navCompatSummary.text = settings.navigatorCompatibility.displayName
        val limitCount = settings.siteLimits().size
        binding.siteLimitsSummary.text = when {
            !settings.siteLimitsEnabled -> getString(R.string.settings_summary_off)
            limitCount == 0 -> getString(R.string.limits_subtitle_zero)
            limitCount == 1 -> getString(R.string.limits_subtitle_one)
            else -> getString(R.string.limits_subtitle_many, limitCount)
        }
        val bgCount = settings.backgroundAudioSites().size
        binding.backgroundAudioSummary.text = when {
            !settings.backgroundAudioEnabled -> getString(R.string.settings_summary_off)
            bgCount == 0 -> getString(R.string.bg_sites_none)
            bgCount == 1 -> getString(R.string.bg_sites_one)
            else -> getString(R.string.bg_sites_many, bgCount)
        }
    }

    /**
     * Bottom sheet listing each limited site as a card with a live "used X of Y
     * min today" bar (red once the cap is hit), a one-tap remove, and an Add
     * button — far friendlier than the old flat list/EditText dialogs.
     */
    private fun showSiteLimitsSheet() {
        val sheet = SheetSiteLimitsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        // Master on/off: when off, nothing is tracked or blocked, and the list dims.
        fun applyEnabled() {
            val on = settings.siteLimitsEnabled
            sheet.limitsAddButton.isEnabled = on
            sheet.limitContainer.alpha = if (on) 1f else 0.4f
        }
        sheet.limitsUsageButton.setOnClickListener {
            startActivity(android.content.Intent(this, UsageOverviewActivity::class.java))
        }
        sheet.limitsEnabledSwitch.isChecked = settings.siteLimitsEnabled
        sheet.limitsEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            settings.siteLimitsEnabled = checked
            applyEnabled()
            refreshSummaries()
        }
        applyEnabled()

        fun rebuild() {
            sheet.limitContainer.removeAllViews()
            val limits = settings.siteLimits().toSortedMap()
            sheet.limitsEmptyState.visibility = if (limits.isEmpty()) View.VISIBLE else View.GONE
            sheet.limitsSubtitle.text = when (limits.size) {
                0 -> getString(R.string.limits_subtitle_zero)
                1 -> getString(R.string.limits_subtitle_one)
                else -> getString(R.string.limits_subtitle_many, limits.size)
            }
            for ((host, minutes) in limits) {
                val row = ItemSiteLimitBinding.inflate(layoutInflater, sheet.limitContainer, false)
                val usedMin = settings.secondsUsedToday(host) / 60
                val reached = settings.isOverLimit(host)
                row.limitHost.text = host
                row.limitProgress.max = minutes
                row.limitProgress.progress = usedMin.coerceAtMost(minutes)
                val tint = ContextCompat.getColor(
                    this,
                    if (reached) R.color.limit_reached else R.color.ladybird_accent
                )
                row.limitProgress.progressTintList = android.content.res.ColorStateList.valueOf(tint)
                row.limitUsage.text =
                    if (reached) getString(R.string.limits_usage_reached, usedMin)
                    else getString(R.string.limits_usage, usedMin, minutes)
                row.root.setOnClickListener { editSiteLimit(host) { rebuild() } }
                row.limitRemove.setOnClickListener {
                    settings.removeSiteLimit(host)
                    refreshSummaries()
                    rebuild()
                }
                sheet.limitContainer.addView(row.root)
            }
        }

        sheet.limitsAddButton.setOnClickListener { editSiteLimit(null) { rebuild() } }
        rebuild()
        dialog.show()
    }

    private fun editSiteLimit(existingHost: String?, onChanged: () -> Unit) {
        val form = DialogSiteLimitBinding.inflate(layoutInflater)
        form.siteInput.setText(existingHost ?: "")
        form.siteInputLayout.isEnabled = existingHost == null
        form.minutesInput.setText(existingHost?.let { settings.limitMinutesFor(it)?.toString() } ?: "")

        MaterialAlertDialogBuilder(this)
            .setTitle(existingHost ?: getString(R.string.limits_add_title))
            .setView(form.root)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val host = form.siteInput.text?.toString().orEmpty()
                val minutes = form.minutesInput.text?.toString()?.toIntOrNull()
                if (host.isNotBlank() && minutes != null && minutes > 0) {
                    settings.setSiteLimit(host, minutes)
                    refreshSummaries()
                    onChanged()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .apply {
                if (existingHost != null) setNeutralButton(R.string.dialog_remove) { _, _ ->
                    settings.removeSiteLimit(existingHost)
                    refreshSummaries()
                    onChanged()
                }
            }
            .show()
    }

    private fun refreshSwitches() {
        binding.jsSwitch.isChecked = settings.javascriptHelpersEnabled
        binding.pinchSwitch.isChecked = settings.pinchZoomEnabled
        binding.adBlockSwitch.isChecked = settings.adBlockEnabled
        binding.httpsOnlySwitch.isChecked = settings.httpsOnly
        binding.saveHistorySwitch.isChecked = settings.saveHistory
        binding.clearOnExitSwitch.isChecked = settings.clearOnExit
    }

    /** Wire a compartment row: plain leading icon (no badge) + open its settings. */
    private fun setupCompartmentCard(row: View, icon: android.widget.ImageView, mode: NetworkMode) {
        icon.setImageResource(NetworkTheme.of(mode).icon)
        icon.background = null
        if (mode == NetworkMode.Normal)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.ladybird_on_surface))
        else icon.clearColorFilter()
        row.setOnClickListener { CompartmentSettingsActivity.start(this, mode) }
    }

    /** Apply the chosen UI language via per-app locales; recreates so the
     *  current screen redraws in the new language immediately. */
    private fun applyLanguage(lang: AppLanguage) {
        val locales = if (lang.tag.isEmpty())
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        else androidx.core.os.LocaleListCompat.forLanguageTags(lang.tag)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
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

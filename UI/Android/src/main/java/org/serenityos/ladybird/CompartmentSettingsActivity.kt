package org.serenityos.ladybird

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import org.serenityos.ladybird.databinding.ActivityCompartmentSettingsBinding

/**
 * Settings for a single network compartment (Tor / I2P / Normal), opened from
 * the coloured compartment cards on the main settings screen. The chrome is
 * tinted to the compartment's identity colour; the body holds whatever options
 * apply to that compartment.
 */
class CompartmentSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompartmentSettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompartmentSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        val mode = NetworkMode.from(intent.getStringExtra(EXTRA_MODE))
        val theme = NetworkTheme.of(mode)
        val accent = if (mode == NetworkMode.Normal) ContextCompat.getColor(this, R.color.ladybird_accent)
                     else ContextCompat.getColor(this, theme.barColor)
        val on = ContextCompat.getColor(this, R.color.ladybird_on_surface)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // Tint the chrome to the compartment (Normal keeps the neutral surface).
        if (mode != NetworkMode.Normal) {
            binding.compartmentToolbar.setBackgroundColor(accent)
            binding.compartmentToolbar.setTitleTextColor(ContextCompat.getColor(this, theme.onColor))
            binding.compartmentToolbar.navigationIcon?.setTint(ContextCompat.getColor(this, theme.onColor))
            window.statusBarColor = accent
        }
        binding.compartmentToolbar.title = getString(R.string.compartment_settings_title, mode.displayName)
        binding.compartmentToolbar.setNavigationOnClickListener { finish() }

        when (mode) {
            NetworkMode.Tor -> buildTor(accent, on)
            NetworkMode.I2P -> buildI2p(accent, on)
            NetworkMode.Normal -> buildNormal(accent, on)
        }
    }

    private fun buildTor(accent: Int, on: Int) {
        header(getString(R.string.compartment_about_tor), accent)
        card {
            addView(description(getString(R.string.compartment_tor_desc)))
        }
        header(getString(R.string.compartment_verification), accent)
        card {
            addView(actionRow(getString(R.string.compartment_tor_check), getString(R.string.compartment_tor_check_sub)) {
                openInBrowser("https://check.torproject.org/")
            })
        }
    }

    private fun buildI2p(accent: Int, on: Int) {
        header("I2P", accent)
        card {
            addView(switchRow(
                getString(R.string.compartment_i2p_keep_title),
                getString(R.string.compartment_i2p_keep_sub),
                settings.i2pKeepRunning
            ) { checked -> settings.i2pKeepRunning = checked })
            addView(switchRow(
                getString(R.string.compartment_i2p_addressbook),
                getString(R.string.compartment_i2p_addressbook_sub),
                settings.i2pAddressbookSubscriptions
            ) { checked -> settings.i2pAddressbookSubscriptions = checked })
        }
        header(getString(R.string.compartment_about_i2p), accent)
        card {
            addView(description(getString(R.string.compartment_i2p_desc)))
        }
    }

    private fun buildNormal(accent: Int, on: Int) {
        header("Normal", accent)
        card {
            addView(description(getString(R.string.compartment_normal_desc)))
        }
    }

    // ---- small view builders (grouped-card style, matches the main settings) ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String, color: Int) {
        binding.compartmentContent.addView(TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(28), dp(24), dp(28), dp(10))
        })
    }

    private inline fun card(build: LinearLayout.() -> Unit) {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@CompartmentSettingsActivity, R.drawable.bg_settings_group)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(16); lp.marginEnd = dp(16)
            layoutParams = lp
            setPadding(0, dp(4), 0, dp(4))
        }
        group.build()
        binding.compartmentContent.addView(group)
    }

    private fun description(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(this@CompartmentSettingsActivity, R.color.ladybird_on_surface_muted))
        textSize = 14f
        setPadding(dp(20), dp(14), dp(20), dp(14))
    }

    private fun actionRow(title: String, subtitle: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(60)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            addView(TextView(this@CompartmentSettingsActivity).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@CompartmentSettingsActivity, R.color.ladybird_on_surface))
                textSize = 16f
            })
            addView(TextView(this@CompartmentSettingsActivity).apply {
                text = subtitle
                setTextColor(ContextCompat.getColor(this@CompartmentSettingsActivity, R.color.ladybird_on_surface_muted))
                textSize = 13f
            })
            setOnClickListener { onClick() }
        }

    private fun switchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = MaterialSwitch(this).apply { isChecked = checked }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            addView(LinearLayout(this@CompartmentSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@CompartmentSettingsActivity).apply {
                    text = title
                    setTextColor(ContextCompat.getColor(this@CompartmentSettingsActivity, R.color.ladybird_on_surface))
                    textSize = 16f
                })
                addView(TextView(this@CompartmentSettingsActivity).apply {
                    text = subtitle
                    setTextColor(ContextCompat.getColor(this@CompartmentSettingsActivity, R.color.ladybird_on_surface_muted))
                    textSize = 13f
                })
            })
            addView(sw.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(12) }
                setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
            })
            setOnClickListener { sw.toggle() }
        }
    }

    private fun openInBrowser(url: String) {
        startActivity(Intent(this, LadybirdActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(url)
        })
        finish()
    }

    companion object {
        const val EXTRA_MODE = "compartment_mode"
        fun start(context: Context, mode: NetworkMode) {
            context.startActivity(Intent(context, CompartmentSettingsActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name))
        }
    }
}

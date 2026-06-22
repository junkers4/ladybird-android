package org.serenityos.ladybird

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "Background playback" settings: a plain allowlist of sites whose audio/video is
 * allowed to keep playing when the app is backgrounded or the screen is locked.
 * Same spirit as Site time limits, but the user just picks which sites it applies
 * to (no per-site value). Built programmatically to avoid extra layout files.
 */
class BackgroundAudioActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var listContainer: LinearLayout
    private lateinit var emptyState: TextView

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)

        val surface = color(R.color.ladybird_surface)
        val onSurface = color(R.color.ladybird_on_surface)
        val muted = color(R.color.ladybird_on_surface_muted)
        val accent = color(R.color.ladybird_accent)

        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else dp(24) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(8), statusBar + dp(8), dp(8), dp(8))
        }

        // Top bar: back + title.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), dp(8))
            addView(ImageButton(this@BackgroundAudioActivity).apply {
                setImageResource(R.drawable.ic_arrow_back)
                background = null
                setColorFilter(onSurface)
                setOnClickListener { finish() }
            })
            addView(TextView(this@BackgroundAudioActivity).apply {
                text = getString(R.string.bg_audio_title)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(onSurface)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    .apply { marginStart = dp(4) }
            })
        })

        // Description.
        root.addView(TextView(this).apply {
            text = getString(R.string.bg_audio_summary)
            setTextColor(muted)
            textSize = 14f
            setPadding(dp(16), 0, dp(16), dp(12))
        })

        // Master enable switch.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(TextView(this@BackgroundAudioActivity).apply {
                text = getString(R.string.bg_audio_enable)
                setTextColor(onSurface)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            addView(SwitchCompat(this@BackgroundAudioActivity).apply {
                isChecked = settings.backgroundAudioEnabled
                setOnCheckedChangeListener { _, checked ->
                    settings.backgroundAudioEnabled = checked
                    listContainer.alpha = if (checked) 1f else 0.4f
                }
            })
        })

        // "Add site" button.
        root.addView(TextView(this).apply {
            text = getString(R.string.bg_audio_add)
            setTextColor(accent)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { promptAddSite() }
        })

        emptyState = TextView(this).apply {
            text = getString(R.string.bg_audio_empty)
            setTextColor(muted)
            textSize = 14f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        root.addView(emptyState)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listContainer.alpha = if (settings.backgroundAudioEnabled) 1f else 0.4f
        root.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(listContainer)
        })

        setContentView(root)
        rebuild()
    }

    private fun promptAddSite() {
        val input = EditText(this).apply {
            hint = getString(R.string.bg_audio_hint)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bg_audio_add)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = input.text?.toString().orEmpty()
                if (host.isNotBlank()) {
                    settings.addBackgroundAudioSite(host)
                    rebuild()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun rebuild() {
        listContainer.removeAllViews()
        val sites = settings.backgroundAudioSites()
        emptyState.visibility = if (sites.isEmpty()) View.VISIBLE else View.GONE

        val onSurface = color(R.color.ladybird_on_surface)
        val muted = color(R.color.ladybird_on_surface_muted)
        val surfaceVariant = color(R.color.ladybird_surface_variant)

        for (host in sites) {
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(surfaceVariant)
                }
                setPadding(dp(16), dp(14), dp(8), dp(14))
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                    .apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
                addView(TextView(this@BackgroundAudioActivity).apply {
                    text = host
                    setTextColor(onSurface)
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                addView(ImageButton(this@BackgroundAudioActivity).apply {
                    setImageResource(R.drawable.ic_close)
                    background = null
                    setColorFilter(muted)
                    setOnClickListener {
                        settings.removeBackgroundAudioSite(host)
                        rebuild()
                    }
                })
            })
        }
    }
}

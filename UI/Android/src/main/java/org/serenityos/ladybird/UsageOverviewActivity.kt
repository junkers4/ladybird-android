package org.serenityos.ladybird

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import org.serenityos.ladybird.databinding.ActivityUsageOverviewBinding

/**
 * "Today's usage" overview: a total, then one horizontal bar per site whose
 * length is proportional to time spent (think a simple TikZ-style bar chart).
 * Sites with a daily limit also show their cap and turn red when reached.
 */
class UsageOverviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUsageOverviewBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsageOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = AppSettings(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        binding.usageToolbar.setNavigationOnClickListener { finish() }

        build()
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(seconds: Int): String {
        val m = seconds / 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    private fun build() {
        val container = binding.usageContent
        container.removeAllViews()

        val usage = settings.allUsageToday()
            .filterValues { it >= 30 }            // ignore sub-30s blips
            .toList().sortedByDescending { it.second }
        val totalSeconds = usage.sumOf { it.second }
        val maxSeconds = (usage.firstOrNull()?.second ?: 1).coerceAtLeast(1)

        // Header card: total time today.
        container.addView(TextView(this).apply {
            text = getString(R.string.usage_today)
            setTextColor(color(R.color.ladybird_on_surface_muted))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(24), dp(20), dp(24), dp(2))
        })
        container.addView(TextView(this).apply {
            text = when {
                totalSeconds == 0 -> getString(R.string.usage_none)
                usage.size == 1 -> getString(R.string.usage_summary_one, fmt(totalSeconds))
                else -> getString(R.string.usage_summary_many, fmt(totalSeconds), usage.size)
            }
            setTextColor(color(R.color.ladybird_on_surface))
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(24), 0, dp(24), dp(12))
        })

        if (usage.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.usage_empty)
                setTextColor(color(R.color.ladybird_on_surface_muted))
                textSize = 14f
                setPadding(dp(24), dp(8), dp(24), 0)
            })
            return
        }

        // One bar per site, length ∝ time (relative to the busiest site).
        for ((host, seconds) in usage) {
            val limit = settings.limitMinutesFor(host)
            val reached = limit != null && seconds >= limit * 60
            val barColor = if (reached) color(R.color.limit_reached) else color(R.color.ladybird_accent)
            val frac = seconds.toFloat() / maxSeconds

            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(2))
                // Title row: host + time (and "/ limit" when there is one).
                addView(LinearLayout(this@UsageOverviewActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(this@UsageOverviewActivity).apply {
                        text = host
                        setTextColor(color(R.color.ladybird_on_surface))
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(TextView(this@UsageOverviewActivity).apply {
                        text = if (limit != null) getString(R.string.usage_site_limit, fmt(seconds), limit) else fmt(seconds)
                        setTextColor(if (reached) barColor else color(R.color.ladybird_on_surface_muted))
                        textSize = 13f
                    })
                })
                // The bar itself: a filled track inside a faint full-width track.
                addView(LinearLayout(this@UsageOverviewActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(6) }
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat(); setColor(color(R.color.ladybird_surface_variant))
                    }
                    addView(View(this@UsageOverviewActivity).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat(); setColor(barColor)
                        }
                        layoutParams = LinearLayout.LayoutParams(0, -1, frac.coerceIn(0.02f, 1f))
                    })
                    // Spacer fills the remaining width so the bar is proportional.
                    addView(View(this@UsageOverviewActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -1, (1f - frac).coerceIn(0f, 0.98f))
                    })
                })
            })
        }
    }
}

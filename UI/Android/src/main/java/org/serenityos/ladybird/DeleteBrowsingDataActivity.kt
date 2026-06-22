package org.serenityos.ladybird

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.serenityos.ladybird.databinding.ActivityDeleteDataBinding

/**
 * Full-screen "Delete browsing data" (Vanadium/Chrome style): a time-range
 * chooser row, data-type checkboxes, and a Delete button. It has no live web
 * view of its own, so it clears history directly and queues the cookie/cache
 * clear for the browser to apply on its next resume (see AppSettings pending*).
 */
class DeleteBrowsingDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeleteDataBinding

    private val ranges = listOf(
        "Last hour" to 3_600L,
        "Last 24 hours" to 86_400L,
        "Last 7 days" to 604_800L,
        "Last 4 weeks" to 2_419_200L,
        "All time" to 0L,
    )
    private val customIndex = ranges.size
    private var rangeIndex = ranges.lastIndex
    private var customSeconds = 0L

    private lateinit var rangeValue: TextView
    private lateinit var cbHistory: CheckBox
    private lateinit var cbCookies: CheckBox
    private lateinit var cbCache: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeleteDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        binding.deleteToolbar.setNavigationOnClickListener { finish() }

        rangeValue = TextView(this).apply {
            setTextColor(color(R.color.ladybird_on_surface_muted))
            textSize = 14f
        }
        binding.deleteContent.addView(timeRangeRow())

        cbHistory = checkRow("Browsing history", "Pages you've visited")
        cbCookies = checkRow("Cookies and site data", "Signs you out of most sites")
        cbCache = checkRow("Cached files", "Frees up storage")

        updateRangeLabel()
        binding.deleteButton.setOnClickListener { doDelete() }
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun themedRipple(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun updateRangeLabel() {
        // Custom keeps the "Since <date>" label set in pickCustom().
        if (rangeIndex != customIndex) rangeValue.text = ranges[rangeIndex].first
    }

    private fun showRangeChooser() {
        val labels = (ranges.map { it.first } + "Custom…").toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Time range")
            .setSingleChoiceItems(labels, rangeIndex) { d, which ->
                d.dismiss()
                if (which == customIndex) {
                    pickCustom()
                } else {
                    rangeIndex = which
                    updateRangeLabel()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun pickCustom() {
        val now = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val picked = java.util.Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
            }
            customSeconds = ((System.currentTimeMillis() - picked.timeInMillis) / 1000).coerceAtLeast(0)
            rangeIndex = customIndex
            rangeValue.text = "Since " + android.text.format.DateFormat.getDateFormat(this).format(picked.time)
        }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH))
            .apply { datePicker.maxDate = System.currentTimeMillis() }
            .show()
    }

    private fun doDelete() {
        val seconds = if (rangeIndex == customIndex) customSeconds else ranges[rangeIndex].second
        if (cbHistory.isChecked) {
            val store = HistoryStore(this)
            if (seconds == 0L) store.clear()
            else store.clearSince(System.currentTimeMillis() - seconds * 1000)
        }
        // No live web view here — queue cookie/cache clears for the browser.
        val s = AppSettings(this)
        if (cbCookies.isChecked) s.pendingClearCookiesSeconds = seconds
        if (cbCache.isChecked) s.pendingClearCache = true
        Toast.makeText(this, R.string.menu_clear_cache_done, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun timeRangeRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        isClickable = true
        setBackgroundResource(themedRipple())
        setOnClickListener { showRangeChooser() }
        addView(LinearLayout(this@DeleteBrowsingDataActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            addView(TextView(this@DeleteBrowsingDataActivity).apply {
                text = "Time range"; setTextColor(color(R.color.ladybird_on_surface)); textSize = 16f
            })
            addView(rangeValue)
        })
        addView(ImageView(this@DeleteBrowsingDataActivity).apply {
            setImageResource(R.drawable.ic_arrow_down)
            setColorFilter(color(R.color.ladybird_on_surface_muted))
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
    }

    private fun checkRow(title: String, subtitle: String): CheckBox {
        val cb = CheckBox(this).apply { isChecked = true }
        binding.deleteContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            isClickable = true
            setBackgroundResource(themedRipple())
            setOnClickListener { cb.toggle() }
            addView(LinearLayout(this@DeleteBrowsingDataActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(TextView(this@DeleteBrowsingDataActivity).apply {
                    text = title; setTextColor(color(R.color.ladybird_on_surface)); textSize = 16f
                })
                addView(TextView(this@DeleteBrowsingDataActivity).apply {
                    text = subtitle; setTextColor(color(R.color.ladybird_on_surface_muted)); textSize = 13f
                })
            })
            addView(cb)
        })
        return cb
    }
}

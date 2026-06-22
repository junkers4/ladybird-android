package org.serenityos.ladybird

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Accumulates the time the user spends on each host that has a daily limit and
 * fires [onLimitReached] when a host's limit is hit. Only hosts that actually
 * have a limit configured are counted, so we don't bloat preferences with every
 * site visited.
 *
 * Lifecycle: call [start] from the Activity's onResume, [stop] from onPause, and
 * [onHost] whenever the foreground page's host changes (load start / URL change).
 */
class TimeLimitTracker(
    private val settings: AppSettings,
    private val onLimitReached: (host: String, minutes: Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var activeHost: String? = null
    private var startedAt: Long = 0L      // 0 = not currently timing
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            flush()
            checkActive()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    /** The foreground page changed; switch which host we are counting. */
    fun onHost(url: String) {
        val host = settings.normalizeHost(url)
        // Count time on every site (when the feature is on) so the usage overview
        // is complete; limits are still only enforced for sites that have one.
        val next = if (host.isNotBlank() && settings.siteLimitsEnabled) host else null
        if (next == activeHost) {
            checkActive()
            return
        }
        flush()
        activeHost = next
        startedAt = if (running && next != null) SystemClock.elapsedRealtime() else 0L
        checkActive()
    }

    fun start() {
        running = true
        if (activeHost != null) startedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        checkActive()
    }

    fun stop() {
        flush()
        running = false
        handler.removeCallbacks(tick)
    }

    /** Persist the elapsed time for the active host and restart the stopwatch. */
    private fun flush() {
        val host = activeHost ?: return
        if (startedAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - startedAt) / 1000L).toInt()
        if (seconds > 0) settings.addUsage(host, seconds)
        startedAt = if (running) now else 0L
    }

    private fun checkActive() {
        val host = activeHost ?: return
        if (settings.isOverLimit(host))
            onLimitReached(host, settings.limitMinutesFor(host) ?: 0)
    }

    companion object {
        private const val TICK_MS = 5_000L
    }
}

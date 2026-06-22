package org.serenityos.ladybird

import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Broad "does it work like a normal browser" coverage: multiple search engines,
 * real-world sites, scrolling, pinch-zoom and video playback. Each test relaunches
 * the activity (safe now that initNativeCode is idempotent). A native crash is
 * reported by Test Lab as "Application crashed"; load durations are logged under
 * the "PerfTest" tag for offline analysis.
 */
@RunWith(AndroidJUnit4::class)
class BrowserBehaviorTest {

    @get:Rule
    var activityScenarioRule = activityScenarioRule<LadybirdActivity>()

    private val tag = "PerfTest"

    /** Navigate to [url] and dwell, logging wall-clock elapsed for perf tracking. */
    private fun open(url: String, dwellMs: Long = 20_000L) {
        val t0 = System.currentTimeMillis()
        Log.i(tag, "OPEN_START $url")
        onView(withId(R.id.urlEditText)).perform(enterUrlAndGo(url))
        onView(withId(R.id.urlEditText)).perform(dwell(dwellMs))
        Log.i(tag, "OPEN_DONE $url elapsed_ms=${System.currentTimeMillis() - t0}")
    }

    // --- Search engines ---
    @Test fun searchEngineGoogle() = open("https://www.google.com/search?q=ladybird+browser")
    @Test fun searchEngineDuckDuckGo() = open("https://duckduckgo.com/?q=ladybird+browser")
    @Test fun searchEngineBing() = open("https://www.bing.com/search?q=ladybird+browser")
    @Test fun searchFromOmniboxQuery() = open("ladybird browser", dwellMs = 25_000L)

    // --- Real-world sites ---
    @Test fun siteExample() = open("https://example.com", dwellMs = 8_000L)
    @Test fun siteWikipedia() = open("https://en.wikipedia.org/wiki/Web_browser")
    @Test fun siteGitHub() = open("https://github.com")
    @Test fun siteMDN() = open("https://developer.mozilla.org")
    @Test fun siteBBC() = open("https://www.bbc.com", dwellMs = 30_000L)

    // --- Scrolling ---
    @Test fun scrollLongPage() {
        open("https://en.wikipedia.org/wiki/Web_browser", dwellMs = 18_000L)
        repeat(4) { onView(withId(R.id.web_view)).perform(swipeUp()) }
        onView(withId(R.id.web_view)).perform(swipeDown(), swipeDown())
        onView(withId(R.id.urlEditText)).perform(dwell(3_000L))
    }

    // --- Reload (swipe-to-refresh on the web surface) ---
    @Test fun reloadPage() {
        open("https://example.com", dwellMs = 8_000L)
        onView(withId(R.id.web_view)).perform(swipeDown())
        onView(withId(R.id.urlEditText)).perform(dwell(8_000L))
    }

    // --- Pinch zoom (UiAutomator) ---
    @Test fun pinchZoom() {
        open("https://en.wikipedia.org/wiki/Web_browser", dwellMs = 15_000L)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val web = device.findObject(By.res("org.serenityos.ladybird", "web_view"))
        if (web != null) {
            web.pinchOpen(0.75f)   // zoom in
            onView(withId(R.id.urlEditText)).perform(dwell(2_000L))
            web.pinchClose(0.75f)  // zoom out
        } else {
            Log.w(tag, "web_view not found for pinch; engine surface may not expose it")
        }
        onView(withId(R.id.urlEditText)).perform(dwell(3_000L))
    }

    // --- Video playback (heavy media page) ---
    @Test fun videoPlayback() {
        // "Big Buck Bunny" on mobile YouTube: exercises media decode + compositor
        // video surface for ~1 minute.
        open("https://m.youtube.com/watch?v=aqz-KE-bpKQ", dwellMs = 60_000L)
    }
}

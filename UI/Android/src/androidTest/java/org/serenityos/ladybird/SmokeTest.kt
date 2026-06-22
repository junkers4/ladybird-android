package org.serenityos.ladybird

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.swipeDown
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.any
import org.hamcrest.Matchers.containsString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    var activityScenarioRule = activityScenarioRule<LadybirdActivity>()

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.serenityos.ladybird", appContext.packageName)
    }

    @Test
    fun loadWebView() {
        // We can actually load a web view, and it is visible
        onView(withId(R.id.web_view)).check(matches(isDisplayed()))
    }

    @Test
    fun urlBarNavigatesToGoogle() {
        // Type URL and press Go; the onLoadStart callback updates the URL bar
        // when the native browser starts the navigation, proving the full
        // browser stack (native library, RequestServer, networking) is alive.
        onView(withId(R.id.urlEditText))
            .perform(replaceText("https://www.google.com"), pressImeActionButton())

        // Wait up to 10 s using UiController (avoids raw Thread.sleep).
        onView(withId(R.id.urlEditText))
            .perform(waitUntilText(containsString("google.com"), timeoutMs = 10_000L))
    }

    @Test
    fun loadYouTubeAndDwell() {
        // Navigate to YouTube (a heavy, JS/media-rich page reported to crash or
        // hang the browser) and dwell, letting the page load and run. If the
        // native process dies, the instrumentation connection drops and this
        // test fails — and the device logcat captures the native crash.
        // The NTP hides the omnibox behind an overlay, so Espresso's "displayed"
        // constraint rejects typing into it. Use a relaxed action that focuses
        // the field, sets the URL, and fires the IME GO action programmatically
        // (the activity's editor-action listener then starts navigation).
        onView(withId(R.id.urlEditText)).perform(enterUrlAndGo("https://m.youtube.com"))

        // Dwell ~90 s, pumping the main thread, to give the page time to load
        // and to surface instability/crashes during rendering. If the native
        // process dies, instrumentation reports failure and logcat has the crash.
        onView(withId(R.id.urlEditText)).perform(dwell(90_000L))
    }

    @Test
    fun loadDuckDuckGo() {
        // Default search engine host renders without crashing.
        navigateAndSettle("https://duckduckgo.com", dwellMs = 30_000L)
    }

    @Test
    fun loadWikipediaAndScroll() {
        // Content-heavy page, then scroll the rendered surface up/down to
        // exercise the compositor scrolling path under real content.
        navigateAndSettle("https://en.wikipedia.org/wiki/Web_browser", dwellMs = 20_000L)
        onView(withId(R.id.web_view)).perform(swipeUp(), swipeUp(), swipeDown())
        onView(withId(R.id.urlEditText)).perform(dwell(5_000L))
    }

    @Test
    fun searchFromOmnibox() {
        // A bare query (not a URL) should go through the configured search
        // engine and render results.
        onView(withId(R.id.urlEditText)).perform(enterUrlAndGo("ladybird browser"))
        onView(withId(R.id.urlEditText)).perform(dwell(30_000L))
    }

    @Test
    fun navigateMultipleSites() {
        // Navigate a sequence of sites back-to-back to surface instability and
        // compositor-context leaks/duplications across navigations.
        for (url in listOf("https://example.com", "https://en.wikipedia.org", "https://duckduckgo.com")) {
            onView(withId(R.id.urlEditText)).perform(enterUrlAndGo(url))
            onView(withId(R.id.urlEditText)).perform(dwell(15_000L))
        }
    }

    private fun navigateAndSettle(url: String, dwellMs: Long) {
        onView(withId(R.id.urlEditText)).perform(enterUrlAndGo(url))
        onView(withId(R.id.urlEditText)).perform(dwell(dwellMs))
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * A [ViewAction] that pumps the main thread for [ms] milliseconds and
     * returns (no assertion), used to let a page load/run while the app stays
     * under instrumentation so a native crash is observed as a test failure.
     */
    /**
     * Relaxed [ViewAction] for the omnibox: it does NOT require the view to be
     * displayed (the NTP keeps it behind an overlay). Focuses the field, sets
     * [url], and fires IME_ACTION_GO so the activity starts navigation.
     */
    private fun enterUrlAndGo(url: String): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(EditText::class.java)
            override fun getDescription() = "focus, set text '$url', fire IME GO"
            override fun perform(uiController: UiController, view: View) {
                val et = view as EditText
                et.requestFocus()
                et.setText(url)
                et.onEditorAction(EditorInfo.IME_ACTION_GO)
                uiController.loopMainThreadUntilIdle()
            }
        }

    private fun dwell(ms: Long): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = any(View::class.java)
            override fun getDescription() = "dwell ${ms}ms pumping the main thread"
            override fun perform(uiController: UiController, view: View) {
                val deadline = System.currentTimeMillis() + ms
                while (System.currentTimeMillis() < deadline) {
                    uiController.loopMainThreadForAtLeast(1000)
                }
            }
        }

    /**
     * A [ViewAction] that loops the main thread in 500 ms bursts until the
     * text of the target [EditText] matches [matcher] or [timeoutMs] elapses.
     */
    private fun waitUntilText(matcher: Matcher<String>, timeoutMs: Long): ViewAction =
        object : ViewAction {
            override fun getConstraints(): Matcher<View> = isDisplayed()
            override fun getDescription() = "wait until EditText text matches [$matcher]"
            override fun perform(uiController: UiController, view: View) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val text = (view as EditText).text.toString()
                    if (matcher.matches(text)) return
                    uiController.loopMainThreadForAtLeast(500)
                }
                val final = (view as EditText).text.toString()
                throw AssertionError(
                    "EditText text '$final' did not match [$matcher] within ${timeoutMs}ms"
                )
            }
        }
}

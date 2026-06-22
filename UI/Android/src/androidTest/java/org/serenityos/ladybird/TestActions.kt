package org.serenityos.ladybird

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matcher
import org.hamcrest.Matchers.any

/**
 * Focuses the omnibox, sets [url], and fires IME_ACTION_GO so the activity
 * starts navigation. Uses relaxed constraints (does NOT require the view to be
 * displayed) because the new-tab page keeps the omnibox behind an overlay.
 */
fun enterUrlAndGo(url: String): ViewAction = object : ViewAction {
    override fun getConstraints(): Matcher<View> = isAssignableFrom(EditText::class.java)
    override fun getDescription() = "set omnibox to '$url' and submit"
    override fun perform(uiController: UiController, view: View) {
        val et = view as EditText
        et.requestFocus()
        et.setText(url)
        et.onEditorAction(EditorInfo.IME_ACTION_GO)
        uiController.loopMainThreadUntilIdle()
    }
}

/** Pumps the main thread for [ms] ms so a page can load/run; asserts nothing. */
fun dwell(ms: Long): ViewAction = object : ViewAction {
    override fun getConstraints(): Matcher<View> = any(View::class.java)
    override fun getDescription() = "dwell ${ms}ms"
    override fun perform(uiController: UiController, view: View) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline)
            uiController.loopMainThreadForAtLeast(1000)
    }
}

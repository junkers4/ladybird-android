package org.serenityos.ladybird

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic: launch the browser with a network compartment preselected so the
 * activity brings the daemon up on startup. If a mode crashes the app, the run
 * fails and the device logcat captures the cause.
 */
@RunWith(AndroidJUnit4::class)
class NetworkModeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun resetMode() {
        // Best-effort: leave the device on Normal for any later test.
        runCatching { AppSettings(ctx).networkMode = NetworkMode.Normal }
    }

    @Test
    fun torStartup() {
        AppSettings(ctx).networkMode = NetworkMode.Tor
        ActivityScenario.launch(LadybirdActivity::class.java).use {
            Thread.sleep(45_000)
        }
    }
}

package io.github.junkers4.ladybird.core.resources

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.engine.HelperProcesses
import io.github.junkers4.ladybird.core.repo.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceInstallPlanTest {
    @Test
    @Requirement("ARCH-008")
    fun installsOncePerVersion() {
        assertTrue(ResourceInstallPlan.needsInstall(null, 1, 100))
        assertFalse(ResourceInstallPlan.needsInstall("1:100\n", 1, 100))
        assertTrue(ResourceInstallPlan.needsInstall("1:100", 2, 100))
        assertTrue(ResourceInstallPlan.needsInstall("1:100", 1, 200))
    }

    @Test
    @Requirement("ARCH-001")
    fun layoutMatchesLibWebViewExpectations() {
        val layout = ResourceInstallPlan.Layout("/data/files/ladybird")
        // LibWebView: helpers are looked up in <prefix>/libexec, prefix = parent of the binary directory,
        // resources in <prefix>/share/Lagom or $XDG_CONFIG_HOME/.lagom.
        assertEquals("/data/files/ladybird/libexec/WebContent", layout.helperLink("WebContent"))
        assertEquals("/data/files/ladybird/share/Lagom", layout.resources)
        assertEquals(layout.config + "/.lagom", layout.lagomLink)
        val env = layout.environment("/data/app/x/lib/arm64")
        assertEquals(layout.config, env["XDG_CONFIG_HOME"])
        assertEquals("/data/app/x/lib/arm64", env["LD_LIBRARY_PATH"])
        assertEquals(listOf("--certificate", layout.certificateBundle), layout.engineArguments())
        val utilities = Repo.text("ladybird/Libraries/LibWebCommon/WebView/Utilities.cpp")
        assertTrue(utilities.contains("\"share/Lagom\"sv"))
        assertTrue(utilities.contains("\"{}/.lagom\""))
    }

    @Test
    @Requirement("ARCH-001", "BLD-006")
    fun helperListMatchesNativeStaging() {
        val cmake = Repo.text("native/LadybirdAndroidTargets.cmake")
        val block = Regex("set\\(LADYBIRD_ANDROID_HELPERS(.*?)\\)", RegexOption.DOT_MATCHES_ALL).find(cmake)!!.groupValues[1]
        assertEquals(HelperProcesses.ALL, block.trim().lines().map { it.trim() })
        assertEquals("libWebContent.so", HelperProcesses.libraryFileName("WebContent"))
    }
}

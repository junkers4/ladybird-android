package io.github.junkers4.ladybird.core.repo

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.engine.ViewEvent
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static checks that the Android UI reacts to the engine events and settings behind each UI requirement.
 * They complement the unit tests of the logic in :core until the emulator smoke test (QA-003) exists.
 */
class BrowserWiringTest {
    private val activity = Repo.text("android/app/src/main/java/io/github/junkers4/ladybird/browser/BrowserActivity.kt")
    private val dialogs = Repo.text("android/app/src/main/java/io/github/junkers4/ladybird/browser/BrowserDialogs.kt")
    private val layout = Repo.text("android/app/src/main/res/layout/activity_browser.xml")

    private fun handles(event: String) = assertTrue("BrowserActivity does not handle ViewEvent.$event", activity.contains("ViewEvent.$event"))

    @Test
    @Requirement("UI-001", "UI-004")
    fun toolbarHasLadybirdsControlsInOrder() {
        val order = listOf("@+id/back", "@+id/forward", "@+id/reload", "@+id/location", "@+id/tab_count", "@+id/menu")
        val positions = order.map { layout.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertTrue("toolbar order differs from Ladybird's", positions == positions.sorted())
        assertTrue(layout.contains("@+id/security") && layout.contains("@+id/progress"))
        handles("NAVIGATION_STATE_CHANGED")
        handles("LOADING_STATE_CHANGED")
    }

    @Test
    @Requirement("UI-006", "UI-007")
    fun dialogsAndMenusAreHandled() {
        listOf("REQUEST_ALERT", "REQUEST_CONFIRM", "REQUEST_PROMPT", "REQUEST_SELECT_DROPDOWN", "REQUEST_CONTEXT_MENU", "REQUEST_FILE_PICKER", "REQUEST_ACCEPT_DIALOG").forEach(::handles)
        assertTrue(dialogs.contains("nativeDialogClosed") && dialogs.contains("nativeSelectDropdownClosed"))
        assertTrue(activity.contains("nativeFilePickerClosed") && activity.contains("nativeContextMenuItemSelected"))
    }

    @Test
    @Requirement("UI-002")
    fun enginesNewTabsBecomeTabs() {
        handles("NEW_TAB")
        handles("REQUEST_CLOSE")
        assertTrue(activity.contains("EngineEvent.OPEN_URL_IN_NEW_TAB"))
    }

    @Test
    @Requirement("UI-008")
    fun findBar() {
        handles("FIND_IN_PAGE_RESULT")
        assertTrue(activity.contains("nativeFindInPage") && activity.contains("nativeFindNext"))
    }

    @Test
    @Requirement("UI-011")
    fun keyboardFollowsTheEngine() {
        handles("INPUT_METHOD_STATE_CHANGED")
        val view = Repo.text("android/app/src/main/java/io/github/junkers4/ladybird/browser/WebContentView.kt")
        for (call in listOf("nativeImeCommit", "nativeImeSetComposing", "nativeImeFinishComposing", "nativeKeyEvent"))
            assertTrue(call, view.contains(call))
    }

    @Test
    @Requirement("UI-015")
    fun fullscreen() {
        handles("ENTER_FULLSCREEN")
        handles("EXIT_FULLSCREEN")
        assertTrue(activity.contains("fullscreen -> NativeEngine.nativeExitFullscreen"))
        assertTrue(activity.contains("controller.hide(WindowInsetsCompat.Type.systemBars())"))
    }

    @Test
    @Requirement("UI-016", "ADB-002")
    fun shieldsPanelTogglesTheSite() {
        assertTrue(layout.contains("@+id/shields"))
        assertTrue(activity.contains("shieldsDownSites - host") && activity.contains("shieldsDownSites + host"))
        assertTrue(activity.contains("Engine.applyPolicy(settings)"))
    }

    @Test
    @Requirement("UI-014", "SEC-018")
    fun downloadsArePublished() {
        assertTrue(activity.contains("EngineEvent.DOWNLOAD_CONFIRMATION -> FinishedDownload.parse"))
        val publisher = Repo.text("android/app/src/main/java/io/github/junkers4/ladybird/browser/DownloadPublisher.kt")
        assertTrue(publisher.contains("MediaStore.Downloads.EXTERNAL_CONTENT_URI"))
        assertTrue(publisher.contains("DownloadPolicy.isDangerous"))
    }

    @Test
    @Requirement("SEC-013", "SEC-017")
    fun secureWindowForPrivateTabs() {
        assertTrue(activity.contains("InputPrivacy.secureWindow(activeTab?.isPrivate ?: false, settings.screenshotProtection)"))
        assertTrue(activity.contains("WindowManager.LayoutParams.FLAG_SECURE"))
        assertTrue(activity.contains("NativeEngine.nativeCreateView(isPrivate)"))
    }

    @Test
    @Requirement("SEC-004", "SEC-005", "SEC-008")
    fun navigationPoliciesAreApplied() {
        assertTrue(activity.contains("enforceHttpsOnly(viewId, url)"))
        assertTrue(activity.contains("TrackingParameters.strip"))
        handles("REQUEST_EXTERNAL_URL")
        assertTrue(dialogs.contains("ExternalUrlPolicy.decide(url)"))
        assertTrue(ViewEvent.REQUEST_EXTERNAL_URL > 0)
    }

    @Test
    @Requirement("SEC-015")
    fun quitClearsDataWhenAsked() {
        assertTrue(activity.contains("if (settings.clearDataOnExit) BrowsingData.clear(this)"))
    }

    @Test
    @Requirement("UI-009", "UI-013", "UI-005")
    fun themeSettingsAndMenus() {
        assertTrue(activity.contains("nativeSetDarkMode"))
        assertTrue(activity.contains("LadybirdPages.SETTINGS") && activity.contains("SettingsActivity::class.java"))
        for (menu in listOf("zoom", "color_scheme", "contrast", "motion", "inspect", "debug"))
            assertTrue(menu, activity.contains("requestEngineMenu(\"$menu\")"))
    }
}

package io.github.junkers4.ladybird.core.repo

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Static checks of the app manifest and its security-related XML. */
class ManifestSecurityTest {
    private val android = "http://schemas.android.com/apk/res/android"

    private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(Repo.file(path)).documentElement

    private val manifest = parse("android/app/src/main/AndroidManifest.xml")

    private fun elements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttribute(name: String) = getAttributeNS(android, name)

    @Test
    @Requirement("SEC-002")
    fun minimalPermissions() {
        val permissions = elements(manifest, "uses-permission").map { it.androidAttribute("name") }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.POST_NOTIFICATIONS"), permissions)
    }

    @Test
    @Requirement("SEC-010")
    fun noBackups() {
        val application = elements(manifest, "application").single()
        assertEquals("false", application.androidAttribute("allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.androidAttribute("dataExtractionRules"))
        val rules = parse("android/app/src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = elements(elements(rules, section).single(), "exclude").map { it.getAttribute("domain") }.toSet()
            assertTrue(section, excluded.containsAll(listOf("root", "file", "database", "sharedpref", "external")))
            assertTrue(section, elements(elements(rules, section).single(), "include").isEmpty())
        }
    }

    @Test
    @Requirement("SEC-011")
    fun onlyTheBrowserIsExportedAndNoCleartext() {
        val components = listOf("activity", "service", "receiver", "provider").flatMap { elements(manifest, it) }
        val exported = components.filter { it.androidAttribute("exported") == "true" }.map { it.androidAttribute("name") }
        assertEquals(listOf(".browser.BrowserActivity"), exported)
        for (component in components)
            assertTrue("${component.androidAttribute("name")} must declare android:exported", component.hasAttributeNS(android, "exported"))
        val application = elements(manifest, "application").single()
        assertEquals("false", application.androidAttribute("usesCleartextTraffic"))
        val config = parse("android/app/src/main/res/xml/network_security_config.xml")
        assertEquals("false", elements(config, "base-config").single().getAttribute("cleartextTrafficPermitted"))
        assertEquals(listOf("system"), elements(config, "certificates").map { it.getAttribute("src") })
    }

    @Test
    @Requirement("UI-017")
    fun handlesLinksAndSearchesFromOtherApps() {
        val activity = elements(manifest, "activity").single { it.androidAttribute("name") == ".browser.BrowserActivity" }
        val filters = elements(activity, "intent-filter")
        val view = filters.single { filter -> elements(filter, "action").any { it.androidAttribute("name") == "android.intent.action.VIEW" } }
        assertEquals(setOf("http", "https"), elements(view, "data").map { it.androidAttribute("scheme") }.toSet())
        assertTrue(elements(view, "category").any { it.androidAttribute("name") == "android.intent.category.BROWSABLE" })
        assertTrue(filters.any { filter -> elements(filter, "action").any { it.androidAttribute("name") == "android.intent.action.WEB_SEARCH" } })
        val activitySource = Repo.text("android/app/src/main/java/io/github/junkers4/ladybird/browser/BrowserActivity.kt")
        assertTrue(activitySource.contains("Intent.ACTION_VIEW -> intent.dataString"))
        assertTrue(activitySource.contains("Intent.ACTION_WEB_SEARCH"))
    }
}

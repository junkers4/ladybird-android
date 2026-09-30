package io.github.junkers4.ladybird.core.repo

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@Requirement("UI-018")
class LauncherIconTest {
    private val res = "android/app/src/main/res"

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Repo.file("$res/$path"))

    private fun layer(tag: String) = (xml("mipmap-anydpi-v26/ic_launcher.xml").getElementsByTagName(tag).item(0) as Element)
        .getAttribute("android:drawable")

    @Test
    fun foregroundIsAlsoTheMonochromeLayer() {
        assertEquals("@drawable/ic_launcher_foreground", layer("foreground"))
        assertEquals(layer("foreground"), layer("monochrome"))
    }

    @Test
    fun backgroundIsWhite() {
        assertEquals("@color/launcher_background", layer("background"))
        val colors = xml("values/colors.xml").getElementsByTagName("color")
        val background = (0 until colors.length).map { colors.item(it) as Element }.single { it.getAttribute("name") == "launcher_background" }
        assertEquals("#FFFFFF", background.textContent.trim().uppercase())
    }

    @Test
    fun glyphIsSolidBlack() {
        val paths = xml("drawable/ic_launcher_foreground.xml").getElementsByTagName("path")
        assertTrue(paths.length > 0)
        for (index in 0 until paths.length)
            assertEquals("#000000", (paths.item(index) as Element).getAttribute("android:fillColor"))
    }
}

package io.github.junkers4.ladybird.core.repo

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

@Requirement("UI-012")
class LocalizationTest {
    private fun resources(language: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(Repo.file("android/app/src/main/res/$language/strings.xml"))
        val result = linkedMapOf<String, String>()
        for (tag in listOf("string", "string-array")) {
            val nodes = document.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (element.getAttribute("translatable") == "false") continue
                result["$tag/" + element.getAttribute("name")] = element.textContent
            }
        }
        return result
    }

    @Test
    fun everyStringIsTranslatedToSlovak() {
        val english = resources("values")
        val slovak = resources("values-sk")
        assertTrue(english.size > 50)
        assertEquals(english.keys, slovak.keys)
    }

    @Test
    fun placeholdersMatch() {
        val english = resources("values")
        val slovak = resources("values-sk")
        val placeholder = Regex("%\\d\\$[sd]")
        for ((key, text) in english)
            assertEquals(key, placeholder.findAll(text).map { it.value }.toSet(), placeholder.findAll(slovak.getValue(key)).map { it.value }.toSet())
    }

    @Test
    fun everyReferencedStringExists() {
        val defined = resources("values").keys.map { it.substringAfter('/') }.toSet() + "app_name"
        val sources = Repo.root.resolve("android/app/src/main").walk().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
        val referenced = sources.flatMap { file -> Regex("(?:(?<!android\\.)R\\.string\\.|@string/)(\\w+)").findAll(file.readText()).map { it.groupValues[1] } }.toSet()
        assertEquals(emptySet<String>(), referenced - defined)
    }
}

package io.github.junkers4.ladybird.core.adblock

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterRulesTest {
    @Test
    @Requirement("ADB-004")
    fun validatesUserFilters() {
        val result = FilterRules.validate(
            """
            ! comment
            [Adblock Plus 2.0]
            ||ads.example^
            @@||good.example^${'$'}document
            example.com##.banner
            ||x.example^${'$'}script,third-party
            ||bad.example^${'$'}frobnicate
            /ab[c/
            example.com##
            example.com##+js(abort-on-property-read, x)
            """.trimIndent(),
        )
        assertEquals(listOf("||ads.example^", "@@||good.example^\$document", "example.com##.banner", "||x.example^\$script,third-party"), result.accepted)
        assertEquals(listOf(7, 8, 9, 10), result.problems.map { it.line })
        assertTrue(result.problems[0].reason.contains("frobnicate"))
    }

    @Test
    @Requirement("ADB-002", "UI-016")
    fun siteExceptions() {
        assertEquals(listOf("@@||news.example^\$document", "@@||news.example^\$elemhide", "@@*\$domain=news.example"), FilterRules.siteExceptions("News.Example."))
        assertThrows(IllegalArgumentException::class.java) { FilterRules.siteExceptions("evil^\$all") }
    }

    @Test
    @Requirement("ADB-004", "ADB-002")
    fun composeDropsInvalidAndSortsSites() {
        val text = FilterRules.composeCustomFilters(listOf("||builtin^"), "||user^\n||bad^\$nope", listOf("b.example", "a.example", "not a host"))
        val lines = text.lines()
        assertTrue(lines.contains("||builtin^"))
        assertTrue(lines.contains("||user^"))
        assertTrue(lines.none { it.contains("nope") })
        assertTrue(lines.indexOf("@@||a.example^\$document") < lines.indexOf("@@||b.example^\$document"))
    }
}

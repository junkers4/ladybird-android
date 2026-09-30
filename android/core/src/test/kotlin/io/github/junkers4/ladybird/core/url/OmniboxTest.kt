package io.github.junkers4.ladybird.core.url

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Test

@Requirement("UI-003")
class OmniboxTest {
    private val omnibox = Omnibox(SearchEngine.DUCKDUCKGO)

    private fun navigate(input: String) = (omnibox.resolve(input) as OmniboxResult.Navigate).url
    private fun search(input: String) = omnibox.resolve(input) as OmniboxResult.Search

    @Test
    fun keepsFullUrls() {
        assertEquals("https://ladybird.org/news?x=1#top", navigate("https://ladybird.org/news?x=1#top"))
        assertEquals("http://example.com", navigate("  http://example.com  "))
        assertEquals("about:settings", navigate("about:settings"))
    }

    @Test
    fun addsHttpsToDomains() {
        assertEquals("https://example.com", navigate("example.com"))
        assertEquals("https://sub.example.co.uk/path?q=1", navigate("sub.example.co.uk/path?q=1"))
        assertEquals("https://example.com:8443/x", navigate("example.com:8443/x"))
        assertEquals("https://xn--bcher-kva.example", navigate("xn--bcher-kva.example"))
    }

    @Test
    fun ipAddressesAndLoopback() {
        assertEquals("https://192.168.1.1", navigate("192.168.1.1"))
        assertEquals("https://[2001:db8::1]:8080/", navigate("[2001:db8::1]:8080/"))
        assertEquals("http://localhost:3000/app", navigate("localhost:3000/app"))
        assertEquals("http://127.0.0.1", navigate("127.0.0.1"))
        assertEquals("http://dev.localhost", navigate("dev.localhost"))
    }

    @Test
    fun searchesEverythingElse() {
        assertEquals("ladybird browser", search("ladybird browser").query)
        assertEquals("https://html.duckduckgo.com/html/?q=ladybird+browser", search("ladybird browser").url)
        assertEquals("hello", search("hello").query)
        assertEquals("what is 1.5", search("what is 1.5").query)
        assertEquals("c++ & rust?", search("c++ & rust?").query)
        assertEquals("https://html.duckduckgo.com/html/?q=c%2B%2B+%26+rust%3F", search("c++ & rust?").url)
        assertEquals("example.com", search("?example.com").query)
        assertEquals("1.2.3", search("1.2.3").query)
        assertEquals("host:99999", search("host:99999").query)
        assertEquals("http://foo bar", search("http://foo bar").query)
    }

    @Test
    fun refusesDangerousTypedSchemes() {
        for (input in listOf("javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,<b>x</b>", "file:///sdcard/x", "view-source:https://a.b", "blob:https://a/b"))
            assertEquals(input, OmniboxResult.Rejected(OmniboxResult.Reason.DANGEROUS_SCHEME), omnibox.resolve(input))
        assertEquals(OmniboxResult.Rejected(OmniboxResult.Reason.EMPTY), omnibox.resolve("   "))
        assertEquals(OmniboxResult.Rejected(OmniboxResult.Reason.EMPTY), omnibox.resolve("?"))
    }

    @Test
    fun externalSchemesAreNavigations() {
        assertEquals("mailto:someone@example.com", navigate("mailto:someone@example.com"))
        assertEquals("tel:+421900000000", navigate("tel:+421900000000"))
    }

    @Test
    fun searchEngineSelection() {
        assertEquals("https://search.brave.com/search?q=a+b", Omnibox(SearchEngine.BRAVE).resolve("a b").let { (it as OmniboxResult.Search).url })
        assertEquals(SearchEngine.DEFAULT, SearchEngine.byId("does-not-exist"))
        assertEquals(SearchEngine.STARTPAGE, SearchEngine.byId("startpage"))
        for (engine in SearchEngine.ALL)
            assert(engine.searchUrl("x").startsWith("https://")) { engine.id }
        assertEquals(SearchEngine.ALL.size, SearchEngine.ALL.map { it.id }.toSet().size)
    }
}

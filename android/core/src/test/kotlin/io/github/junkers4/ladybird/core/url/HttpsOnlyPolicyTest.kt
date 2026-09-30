package io.github.junkers4.ladybird.core.url

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Requirement("SEC-004")
class HttpsOnlyPolicyTest {
    @Test
    fun upgradesHttp() {
        val policy = HttpsOnlyPolicy()
        assertEquals(HttpsOnlyPolicy.Decision.Upgrade("https://example.com/a?b=c#d"), policy.evaluate("http://example.com/a?b=c#d"))
        assertEquals(HttpsOnlyPolicy.Decision.Upgrade("https://example.com/"), policy.evaluate("http://example.com:80/"))
        assertEquals(HttpsOnlyPolicy.Decision.Upgrade("https://example.com:8080/"), policy.evaluate("http://example.com:8080/"))
        assertEquals(HttpsOnlyPolicy.Decision.Upgrade("https://user@example.com/"), policy.evaluate("HTTP://user@example.com/"))
    }

    @Test
    fun leavesOtherUrlsAlone() {
        val policy = HttpsOnlyPolicy()
        for (url in listOf("https://example.com", "about:blank", "mailto:a@b.c", "http://localhost:8080/", "http://127.0.0.1/", "http://[::1]/", "http://app.localhost/"))
            assertEquals(url, HttpsOnlyPolicy.Decision.Proceed(url), policy.evaluate(url))
    }

    @Test
    fun exceptions() {
        val policy = HttpsOnlyPolicy(permanentExceptions = setOf("Legacy.example"))
        assertEquals(HttpsOnlyPolicy.Decision.Proceed("http://legacy.example/"), policy.evaluate("http://legacy.example/"))

        policy.allowForSession("router.lan")
        assertEquals(HttpsOnlyPolicy.Decision.Proceed("http://router.lan/"), policy.evaluate("http://router.lan/"))
        policy.clearSessionExceptions()
        assertTrue(policy.evaluate("http://router.lan/") is HttpsOnlyPolicy.Decision.Upgrade)

        policy.allowPermanently("old.example")
        assertTrue(policy.isAllowed("OLD.example"))
        policy.revoke("old.example")
        assertTrue(policy.evaluate("http://old.example/") is HttpsOnlyPolicy.Decision.Upgrade)
        assertEquals(setOf("legacy.example"), policy.permanentExceptions)
    }

    @Test
    fun canBeDisabled() {
        val policy = HttpsOnlyPolicy(enabled = false)
        assertEquals(HttpsOnlyPolicy.Decision.Proceed("http://example.com/"), policy.evaluate("http://example.com/"))
    }
}

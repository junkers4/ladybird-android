package io.github.junkers4.ladybird.core.url

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.url.ExternalUrlPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Requirement("SEC-008")
class ExternalUrlPolicyTest {
    @Test
    fun webUrlsStayInTheBrowser() {
        for (url in listOf("https://a.b/", "http://a.b/", "about:blank", "data:text/plain,x"))
            assertEquals(Action.LoadInBrowser, ExternalUrlPolicy.decide(url))
    }

    @Test
    fun otherSchemesNeedConfirmation() {
        val mail = ExternalUrlPolicy.decide("mailto:a@b.c") as Action.ConfirmThenOpen
        assertEquals("android.intent.action.VIEW", mail.request.action)
        assertEquals("mailto:a@b.c", mail.request.dataUri)
        assertEquals(null, mail.request.packageName)
        val tel = ExternalUrlPolicy.decide("tel:+421") as Action.ConfirmThenOpen
        assertEquals("android.intent.action.DIAL", tel.request.action)
        assertTrue(ExternalUrlPolicy.decide("market://details?id=org.x") is Action.ConfirmThenOpen)
    }

    @Test
    fun forbiddenSchemesAreBlocked() {
        for (url in listOf("javascript:alert(1)", "file:///data/data/x", "content://com.x.provider/secret", "jar:file:///x!/y"))
            assertTrue(url, ExternalUrlPolicy.decide(url) is Action.Block)
    }

    @Test
    fun intentUrisAreSanitized() {
        val action = ExternalUrlPolicy.decide(
            "intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;component=com.evil/.Exported;" +
                "action=android.intent.action.DELETE;launchFlags=0x3;S.browser_fallback_url=https%3A%2F%2Fexample.com;end",
        ) as Action.ConfirmThenOpen
        assertEquals("android.intent.action.VIEW", action.request.action)
        assertEquals("zxing://scan/", action.request.dataUri)
        assertEquals("com.google.zxing.client.android", action.request.packageName)
        assertEquals(setOf("android.intent.category.BROWSABLE"), action.request.categories)
    }

    @Test
    fun intentFallbacks() {
        assertEquals(Action.Fallback("https://example.com"), ExternalUrlPolicy.decide("intent:#Intent;S.browser_fallback_url=https%3A%2F%2Fexample.com;end"))
        assertEquals(Action.Fallback("https://example.com/page"), ExternalUrlPolicy.decide("intent://example.com/page#Intent;scheme=https;end"))
        assertTrue(ExternalUrlPolicy.decide("intent:#Intent;S.browser_fallback_url=javascript%3Aalert(1);end") is Action.Block)
        assertTrue(ExternalUrlPolicy.decide("intent://x#Intent;scheme=file;end") is Action.Block)
        assertTrue(ExternalUrlPolicy.decide("intent://x#Intent;package=not a package;end") is Action.Block)
        assertTrue(ExternalUrlPolicy.decide("intent://x/no-fragment") is Action.Block)
    }
}

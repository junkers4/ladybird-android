package io.github.junkers4.ladybird.core.url

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Test

@Requirement("SEC-005")
class TrackingParametersTest {
    @Test
    fun removesGlobalTrackers() {
        assertEquals(
            "https://example.com/p?id=7&page=2#frag",
            TrackingParameters.strip("https://example.com/p?utm_source=x&id=7&fbclid=abc&page=2&UTM_Campaign=y&gclid=1#frag"),
        )
        assertEquals("https://example.com/p", TrackingParameters.strip("https://example.com/p?utm_source=x&msclkid=2"))
    }

    @Test
    fun domainSpecificRules() {
        assertEquals("https://www.youtube.com/watch?v=abc&t=10", TrackingParameters.strip("https://www.youtube.com/watch?v=abc&si=TRACK&t=10"))
        assertEquals("https://youtu.be/abc", TrackingParameters.strip("https://youtu.be/abc?si=TRACK"))
        // "si" is only a tracker on the listed domains.
        assertEquals("https://example.com/?si=1", TrackingParameters.strip("https://example.com/?si=1"))
        assertEquals("https://www.amazon.com/dp/B0?th=1", TrackingParameters.strip("https://www.amazon.com/dp/B0?pd_rd_w=1&th=1&ref_=x&pf_rd_p=2"))
    }

    @Test
    fun keepsEncodingAndUnrelatedUrls() {
        val url = "https://example.com/a%20b?q=%E2%9C%93&x=a%26b"
        assertEquals(url, TrackingParameters.strip(url))
        assertEquals("mailto:a@b.c?subject=utm_source", TrackingParameters.strip("mailto:a@b.c?subject=utm_source"))
        assertEquals("https://example.com/", TrackingParameters.strip("https://example.com/"))
        assertEquals("https://example.com/?a=1&b=2", TrackingParameters.strip("https://example.com/?a=1&&b=2&utm_x=3"))
    }
}

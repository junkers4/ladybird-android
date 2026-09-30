package io.github.junkers4.ladybird.core.url

import io.github.junkers4.ladybird.core.Requirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Requirement("ARCH-006")
class UrlPartsTest {
    @Test
    fun splitsComponents() {
        val parts = UrlParts.parse("HTTPS://User:pw@Example.COM:8443/a/b?x=1&y#frag?ment")!!
        assertEquals("https", parts.scheme)
        assertEquals("User:pw@Example.COM:8443", parts.authority)
        assertEquals("example.com", parts.host)
        assertEquals(8443, parts.port)
        assertEquals("/a/b", parts.path)
        assertEquals("x=1&y", parts.query)
        assertEquals("frag?ment", parts.fragment)
        assertEquals("https://User:pw@Example.COM:8443/a/b?x=1&y#frag?ment", parts.toString())
    }

    @Test
    fun ipv6AndOpaqueUrls() {
        assertEquals("::1", UrlParts.parse("http://[::1]:80/")!!.host)
        assertEquals(80, UrlParts.parse("http://[::1]:80/")!!.port)
        val mail = UrlParts.parse("mailto:a@b.c")!!
        assertNull(mail.authority)
        assertNull(mail.host)
        assertEquals("a@b.c", mail.path)
    }

    @Test
    fun hostClassification() {
        assertTrue(HostClassifier.isLoopback("localhost"))
        assertTrue(HostClassifier.isLoopback("127.10.0.1"))
        assertTrue(HostClassifier.isLoopback("::1"))
        assertFalse(HostClassifier.isLoopback("localhost.example.com"))
        assertTrue(HostClassifier.isPrivateNetwork("192.168.0.10"))
        assertTrue(HostClassifier.isPrivateNetwork("172.20.1.1"))
        assertFalse(HostClassifier.isPrivateNetwork("172.32.1.1"))
        assertTrue(HostClassifier.isPrivateNetwork("fd00::1"))
        assertTrue(HostClassifier.matchesDomain("www.youtube.com", "youtube.com"))
        assertFalse(HostClassifier.matchesDomain("notyoutube.com", "youtube.com"))
        assertFalse(HostClassifier.isIpv4("256.1.1.1"))
    }
}

package io.github.junkers4.ladybird.core.security

import io.github.junkers4.ladybird.core.Requirement
import io.github.junkers4.ladybird.core.input.InputPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPoliciesTest {
    @Test
    @Requirement("SEC-009")
    fun onlySystemAnchorsAreExported() {
        val bundle = CertificateBundle.build(mapOf("user:abc" to byteArrayOf(9, 9), "system:b" to byteArrayOf(2), "system:a" to byteArrayOf(1)))
        assertEquals(2, Regex("BEGIN CERTIFICATE").findAll(bundle).count())
        assertEquals(CertificateBundle.pem(byteArrayOf(1)) + CertificateBundle.pem(byteArrayOf(2)), bundle)
        assertEquals("-----BEGIN CERTIFICATE-----\nAQ==\n-----END CERTIFICATE-----\n", CertificateBundle.pem(byteArrayOf(1)))
        val long = CertificateBundle.pem(ByteArray(100))
        assertTrue(long.lines().all { it.length <= 64 })
    }

    @Test
    @Requirement("SEC-018")
    fun downloadNames() {
        assertEquals("passwd", DownloadPolicy.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.sh", DownloadPolicy.sanitizeFileName("..\\..\\evil.sh"))
        assertEquals("a_b_.txt", DownloadPolicy.sanitizeFileName("a<b>.txt"))
        assertEquals("hidden", DownloadPolicy.sanitizeFileName(".hidden"))
        assertEquals("download", DownloadPolicy.sanitizeFileName("..."))
        assertEquals("nulls.pdf", DownloadPolicy.sanitizeFileName("nu\u0000lls.pdf"))
        val long = DownloadPolicy.sanitizeFileName("x".repeat(300) + ".pdf")
        assertTrue(long.length <= 200 && long.endsWith(".pdf"))
    }

    @Test
    @Requirement("SEC-018")
    fun dangerousTypes() {
        assertTrue(DownloadPolicy.isDangerous("app.apk"))
        assertTrue(DownloadPolicy.isDangerous("report.pdf.APK"))
        assertTrue(DownloadPolicy.isDangerous("install.sh"))
        assertFalse(DownloadPolicy.isDangerous("photo.jpg"))
        assertFalse(DownloadPolicy.isDangerous("apk"))
    }

    @Test
    @Requirement("SEC-013", "SEC-014")
    fun incognitoKeyboard() {
        assertEquals(6 or InputPrivacy.IME_FLAG_NO_PERSONALIZED_LEARNING, InputPrivacy.imeOptions(6, isPrivateTab = true, incognitoKeyboardSetting = false))
        assertEquals(6 or InputPrivacy.IME_FLAG_NO_PERSONALIZED_LEARNING, InputPrivacy.imeOptions(6, isPrivateTab = false, incognitoKeyboardSetting = true))
        assertEquals(6, InputPrivacy.imeOptions(6, isPrivateTab = false, incognitoKeyboardSetting = false))
    }

    @Test
    @Requirement("SEC-013", "SEC-017")
    fun screenshotProtection() {
        assertTrue(InputPrivacy.secureWindow(isPrivateTab = true, screenshotProtectionSetting = false))
        assertTrue(InputPrivacy.secureWindow(isPrivateTab = false, screenshotProtectionSetting = true))
        assertFalse(InputPrivacy.secureWindow(isPrivateTab = false, screenshotProtectionSetting = false))
    }
}

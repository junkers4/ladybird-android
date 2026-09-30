package io.github.junkers4.ladybird.core.security

import java.util.Base64

/**
 * Builds the PEM bundle the engine's RequestServer trusts (requirement SEC-009).
 *
 * On Android the platform CA store ("AndroidCAStore") names system anchors `system:<hash>` and
 * user-installed ones `user:<hash>`. Only system anchors are exported.
 */
object CertificateBundle {
    fun isSystemAnchor(alias: String) = alias.startsWith("system:")

    fun pem(derEncoded: ByteArray): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(derEncoded)
        return "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
    }

    /** [anchors] maps a CA-store alias to the DER encoding of the certificate. */
    fun build(anchors: Map<String, ByteArray>): String =
        anchors.filterKeys(::isSystemAnchor).toSortedMap().values.joinToString("") { pem(it) }
}

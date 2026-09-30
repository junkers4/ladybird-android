package io.github.junkers4.ladybird.core.url

/**
 * RFC 3986 component split of an absolute URL, keeping every component's original encoding.
 *
 * The engine does the authoritative (WHATWG) URL parsing; the app only needs to look at a URL's
 * scheme/host and to rewrite its scheme or query, without re-encoding anything else.
 */
data class UrlParts(
    val scheme: String?,
    val authority: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    /** Host in lower case, without user info, port or IPv6 brackets. */
    val host: String?
        get() {
            val authority = authority ?: return null
            val hostAndPort = authority.substringAfterLast('@')
            val host = if (hostAndPort.startsWith("[")) {
                hostAndPort.substringAfter('[').substringBefore(']')
            } else {
                hostAndPort.substringBefore(':')
            }
            return host.lowercase().trimEnd('.').ifEmpty { null }
        }

    val port: Int?
        get() {
            val hostAndPort = authority?.substringAfterLast('@') ?: return null
            val portText = if (hostAndPort.startsWith("[")) hostAndPort.substringAfter("]", "").removePrefix(":") else hostAndPort.substringAfter(':', "")
            return portText.toIntOrNull()
        }

    override fun toString(): String = buildString {
        if (scheme != null) append(scheme).append(':')
        if (authority != null) append("//").append(authority)
        append(path)
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    companion object {
        private val PATTERN = Regex("^(?:([A-Za-z][A-Za-z0-9+.-]*):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$", RegexOption.DOT_MATCHES_ALL)

        fun parse(url: String): UrlParts? {
            val match = PATTERN.matchEntire(url) ?: return null
            val (scheme, authority, path, query, fragment) = match.destructured
            val groups = match.groups
            return UrlParts(
                scheme = groups[1]?.let { scheme.lowercase() },
                authority = groups[2]?.let { authority },
                path = path,
                query = groups[4]?.let { query },
                fragment = groups[5]?.let { fragment },
            )
        }
    }
}

object HostClassifier {
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

    fun isIpv4(host: String) = IPV4.matches(host)

    fun isIpv6(host: String) = host.contains(':') && host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }

    /** localhost, *.localhost, 127.0.0.0/8 and ::1. */
    fun isLoopback(host: String): Boolean {
        val lower = host.lowercase().trimEnd('.')
        if (lower == "localhost" || lower.endsWith(".localhost")) return true
        if (isIpv4(lower) && lower.startsWith("127.")) return true
        return lower == "::1" || lower == "0:0:0:0:0:0:0:1"
    }

    /** RFC 1918 / link-local / unique-local addresses. */
    fun isPrivateNetwork(host: String): Boolean {
        val lower = host.lowercase()
        if (isIpv4(lower)) {
            val octets = lower.split('.').map { it.toInt() }
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254)
        }
        return isIpv6(lower) && (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80:"))
    }

    /** True if [host] equals [domain] or is a subdomain of it. */
    fun matchesDomain(host: String, domain: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        val d = domain.lowercase().trimEnd('.')
        return h == d || h.endsWith(".$d")
    }
}

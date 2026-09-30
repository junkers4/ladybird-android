package io.github.junkers4.ladybird.core.url

/**
 * HTTPS-Only mode (requirement SEC-004).
 *
 * Every top-level navigation to http:// is upgraded to https:// unless the host is loopback or the
 * user explicitly allowed plain HTTP for it (for this session or permanently). The app applies the
 * policy to URLs it loads itself and to page-initiated navigations it sees at load start.
 */
class HttpsOnlyPolicy(
    var enabled: Boolean = true,
    permanentExceptions: Set<String> = emptySet(),
) {
    private val permanent = permanentExceptions.map { it.lowercase() }.toMutableSet()
    private val session = mutableSetOf<String>()

    sealed interface Decision {
        data class Proceed(val url: String) : Decision
        data class Upgrade(val url: String) : Decision
    }

    val permanentExceptions: Set<String> get() = permanent.toSet()

    fun evaluate(url: String): Decision {
        if (!enabled) return Decision.Proceed(url)
        val parts = UrlParts.parse(url) ?: return Decision.Proceed(url)
        if (parts.scheme != "http") return Decision.Proceed(url)
        val host = parts.host ?: return Decision.Proceed(url)
        if (HostClassifier.isLoopback(host) || isAllowed(host)) return Decision.Proceed(url)
        val upgraded = parts.copy(scheme = "https", authority = parts.authority?.let(::upgradePort))
        return Decision.Upgrade(upgraded.toString())
    }

    fun isAllowed(host: String): Boolean {
        val lower = host.lowercase()
        return lower in session || lower in permanent
    }

    /** The user chose "Continue to HTTP site" after the upgrade failed. */
    fun allowForSession(host: String) {
        session += host.lowercase()
    }

    fun allowPermanently(host: String) {
        permanent += host.lowercase()
    }

    fun revoke(host: String) {
        session -= host.lowercase()
        permanent -= host.lowercase()
    }

    fun clearSessionExceptions() = session.clear()

    /** An explicit :80 would point the https:// URL at the plain HTTP port. */
    private fun upgradePort(authority: String): String = if (authority.endsWith(":80")) authority.removeSuffix(":80") else authority
}

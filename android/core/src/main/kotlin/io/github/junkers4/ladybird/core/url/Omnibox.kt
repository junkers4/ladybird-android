package io.github.junkers4.ladybird.core.url

/** What the location field should do with the text the user entered (requirement UI-003). */
sealed interface OmniboxResult {
    data class Navigate(val url: String) : OmniboxResult
    data class Search(val query: String, val url: String) : OmniboxResult
    data class Rejected(val reason: Reason) : OmniboxResult

    enum class Reason { EMPTY, DANGEROUS_SCHEME }
}

class Omnibox(private val searchEngine: SearchEngine = SearchEngine.DEFAULT) {
    fun resolve(rawInput: String): OmniboxResult {
        val input = rawInput.trim()
        if (input.isEmpty()) return OmniboxResult.Rejected(OmniboxResult.Reason.EMPTY)

        // "?foo" forces a search, like in most browsers.
        if (input.startsWith("?")) return search(input.removePrefix("?").trim())

        val scheme = SCHEME.find(input)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && !looksLikeHostWithPort(input)) {
            if (scheme in BLOCKED_TYPED_SCHEMES) return OmniboxResult.Rejected(OmniboxResult.Reason.DANGEROUS_SCHEME)
            if (scheme in WEB_SCHEMES) {
                return if (input.any { it.isWhitespace() }) search(input) else OmniboxResult.Navigate(input)
            }
            if (scheme == "about" || scheme in EXTERNAL_SCHEMES) return OmniboxResult.Navigate(input)
        }

        if (input.any { it.isWhitespace() }) return search(input)

        val hostPart = input.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = hostPart.substringAfterLast('@').let {
            if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it.substringBefore(':')
        }
        val port = if (hostPart.startsWith("[")) hostPart.substringAfter("]", "").removePrefix(":") else hostPart.substringAfter(':', "")
        if (port.isNotEmpty() && port.toIntOrNull() !in 1..65535) return search(input)

        return when {
            HostClassifier.isLoopback(host) -> OmniboxResult.Navigate("http://$input")
            HostClassifier.isIpv4(host) || (hostPart.startsWith("[") && HostClassifier.isIpv6(host)) -> OmniboxResult.Navigate("https://$input")
            looksLikeDomain(host) -> OmniboxResult.Navigate("https://$input")
            else -> search(input)
        }
    }

    private fun search(query: String): OmniboxResult =
        if (query.isEmpty()) OmniboxResult.Rejected(OmniboxResult.Reason.EMPTY) else OmniboxResult.Search(query, searchEngine.searchUrl(query))

    private fun looksLikeDomain(host: String): Boolean {
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
        if (!labels.all { label -> label.all { it.isLetterOrDigit() || it == '-' } }) return false
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }

    private fun looksLikeHostWithPort(input: String): Boolean = HOST_WITH_PORT.containsMatchIn(input)

    companion object {
        private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
        private val HOST_WITH_PORT = Regex("^[A-Za-z0-9.-]+:\\d{1,5}(?:[/?#]|$)")
        private val WEB_SCHEMES = setOf("http", "https")

        /** Schemes the user may not type: they run code or read local data in the page's context. */
        val BLOCKED_TYPED_SCHEMES = setOf("javascript", "vbscript", "data", "file", "blob", "content", "filesystem", "view-source", "chrome", "jar")

        /** Schemes handed to other apps (after confirmation, see ExternalUrlPolicy). */
        val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo", "market", "intent")
    }
}

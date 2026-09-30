package io.github.junkers4.ladybird.core.adblock

import io.github.junkers4.ladybird.core.url.HostClassifier

/**
 * Filter rules we hand to Ladybird's content blocker (adblock-rust, Adblock Plus / uBlock Origin syntax)
 * through Settings::set_custom_content_blocker_filters (requirements ADB-002, ADB-003, ADB-004).
 */
object FilterRules {
    data class Problem(val line: Int, val text: String, val reason: String)

    data class Validation(val accepted: List<String>, val problems: List<Problem>)

    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val KNOWN_OPTIONS = setOf(
        "script", "image", "stylesheet", "object", "xmlhttprequest", "xhr", "subdocument", "frame", "ping", "media",
        "font", "websocket", "other", "document", "doc", "popup", "third-party", "3p", "first-party", "1p",
        "match-case", "important", "domain", "from", "to", "generichide", "elemhide", "ehide", "ghide", "badfilter",
        "all", "redirect", "redirect-rule", "removeparam", "csp", "denyallow", "method", "~third-party", "~3p",
    )

    /**
     * Validates user-entered filters (ADB-004). Comments and blank lines are dropped silently; lines the
     * engine cannot use are reported and not sent.
     */
    fun validate(text: String): Validation {
        val accepted = mutableListOf<String>()
        val problems = mutableListOf<Problem>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@forEachIndexed
            val reason = problem(line)
            if (reason == null) accepted += line else problems += Problem(index + 1, line, reason)
        }
        return Validation(accepted, problems)
    }

    private fun problem(line: String): String? {
        if (line.length > 4096) return "rule is too long"
        if (line.any { it.isISOControl() }) return "rule contains control characters"
        if (line.contains("#@#") || line.contains("##") || line.contains("#?#") || line.contains("#$#")) {
            val separator = listOf("#@#", "#?#", "#$#", "##").first { line.contains(it) }
            val selector = line.substringAfter(separator)
            if (selector.isBlank()) return "cosmetic rule without selector"
            if (line.contains("#+js(") || selector.startsWith("+js(")) return "scriptlets are not supported by the engine"
            return null
        }
        val body = line.removePrefix("@@")
        if (body.isEmpty()) return "empty rule"
        val dollar = body.lastIndexOf('$')
        if (dollar >= 0 && !body.startsWith("/")) {
            val options = body.substring(dollar + 1).split(',').map { it.trim().substringBefore('=').removePrefix("~") }
            val unknown = options.filter { it.isNotEmpty() && it !in KNOWN_OPTIONS && "~$it" !in KNOWN_OPTIONS }
            if (unknown.isNotEmpty()) return "unknown option(s): ${unknown.joinToString()}"
        }
        if (body.startsWith("/") && body.endsWith("/") && body.length > 2) {
            return runCatching { Regex(body.substring(1, body.length - 1)); null }.getOrElse { "invalid regular expression" }
        }
        return null
    }

    /** Rules that turn blocking off on [host] (ADB-002): network exceptions + no cosmetic filtering. */
    fun siteExceptions(host: String): List<String> {
        val normalized = host.lowercase().trimEnd('.')
        require(HOST.matches(normalized) || HostClassifier.isIpv4(normalized)) { "not a host name: $host" }
        return listOf(
            "@@||$normalized^\$document",
            "@@||$normalized^\$elemhide",
            "@@*\$domain=$normalized",
        )
    }

    /** Everything we put into the engine's custom filter slot, in order. */
    fun composeCustomFilters(builtIn: List<String>, userFilters: String, disabledSites: Collection<String>): String {
        val user = validate(userFilters).accepted
        val exceptions = disabledSites.sorted().flatMap { runCatching { siteExceptions(it) }.getOrDefault(emptyList()) }
        return buildString {
            appendLine("! Built-in rules (ladybird-android)")
            builtIn.forEach(::appendLine)
            appendLine("! User filters")
            user.forEach(::appendLine)
            appendLine("! Sites with shields down")
            exceptions.forEach(::appendLine)
        }
    }
}

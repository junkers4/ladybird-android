package io.github.junkers4.ladybird.core.url

import java.net.URLDecoder

/**
 * Decides what happens with URLs the engine cannot load itself (requirement SEC-008).
 *
 * Nothing leaves the browser without an explicit confirmation from the user. For `intent:` URIs
 * only the parts needed to find a matching app are kept: explicit components, selectors, flags and
 * extras other than the browser fallback URL are dropped, so a page cannot target a specific
 * (possibly non-exported-by-intention) component or grant URI permissions.
 */
object ExternalUrlPolicy {
    sealed interface Action {
        /** The engine loads it (http, https, about, data from the engine itself...). */
        data object LoadInBrowser : Action

        /** Ask the user, then hand [request] to Android. */
        data class ConfirmThenOpen(val request: ExternalRequest, val appLabelHint: String?) : Action

        /** Ask the user whether to open [url] in the browser instead (intent: fallback). */
        data class Fallback(val url: String) : Action

        data class Block(val reason: String) : Action
    }

    /** A sanitized, platform-independent description of an Android intent. */
    data class ExternalRequest(
        val action: String,
        val dataUri: String?,
        val packageName: String?,
        val categories: Set<String> = setOf("android.intent.category.BROWSABLE"),
    )

    private val BROWSER_SCHEMES = setOf("http", "https", "about", "blob", "data")
    private val DIAL_SCHEMES = setOf("tel")
    private val FORBIDDEN_SCHEMES = setOf("javascript", "vbscript", "file", "content", "filesystem", "jar", "chrome")
    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun decide(url: String): Action {
        val scheme = UrlParts.parse(url)?.scheme ?: return Action.Block("unparseable URL")
        return when (scheme) {
            in BROWSER_SCHEMES -> Action.LoadInBrowser
            in FORBIDDEN_SCHEMES -> Action.Block("scheme $scheme is not allowed to leave the browser")
            "intent" -> decideIntent(url)
            "market" -> Action.ConfirmThenOpen(ExternalRequest("android.intent.action.VIEW", url, null), "app store")
            in DIAL_SCHEMES -> Action.ConfirmThenOpen(ExternalRequest("android.intent.action.DIAL", url, null), null)
            else -> Action.ConfirmThenOpen(ExternalRequest("android.intent.action.VIEW", url, null), null)
        }
    }

    /** Parses `intent://host/path#Intent;scheme=x;package=y;S.browser_fallback_url=z;end`. */
    fun decideIntent(url: String): Action {
        val fragmentStart = url.indexOf("#Intent;")
        if (fragmentStart < 0 || !url.endsWith(";end")) return Action.Block("malformed intent URI")

        val fields = url.substring(fragmentStart + "#Intent;".length, url.length - ";end".length)
            .split(';')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to decode(it.substringAfter('=')) }

        val fallback = fields["S.browser_fallback_url"]?.takeIf { fallbackIsSafe(it) }
        val targetScheme = fields["scheme"]?.lowercase()
        val packageName = fields["package"]?.takeIf { PACKAGE_NAME.matches(it) }
        // Pages cannot choose the action: only VIEW is ever sent.
        val action = "android.intent.action.VIEW"

        if (targetScheme != null && targetScheme in FORBIDDEN_SCHEMES) return Action.Block("intent targets forbidden scheme $targetScheme")
        if (targetScheme == "http" || targetScheme == "https") {
            // An intent for a web page is just a link; stay in the browser.
            return Action.Fallback(targetScheme + ":" + url.substring("intent:".length, fragmentStart))
        }

        val data = targetScheme?.let { it + ":" + url.substring("intent:".length, fragmentStart) }
        if (data == null && packageName == null) {
            return fallback?.let { Action.Fallback(it) } ?: Action.Block("intent names neither a scheme nor a package")
        }
        return Action.ConfirmThenOpen(ExternalRequest(action, data, packageName), packageName)
    }

    /** Fallback URLs are loaded by the browser itself, so they must be web URLs. */
    fun fallbackIsSafe(url: String): Boolean {
        val scheme = UrlParts.parse(url)?.scheme
        return scheme == "https" || scheme == "http"
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrDefault(value)
}

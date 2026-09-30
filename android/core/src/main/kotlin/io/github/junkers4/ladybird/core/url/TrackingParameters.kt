package io.github.junkers4.ladybird.core.url

/**
 * Removes tracking parameters from URLs (requirement SEC-005).
 *
 * Only the query is rewritten; every other component (and the encoding of kept parameters) is
 * preserved byte for byte.
 */
object TrackingParameters {
    /** Removed on every site. Entries ending in `*` are prefixes. */
    val GLOBAL = listOf(
        "utm_*", "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid",
        "yclid", "ysclid", "twclid", "ttclid", "li_fat_id", "igshid", "igsh", "_hsenc", "_hsmi", "__hssc",
        "__hstc", "__hsfp", "hsCtaTracking", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "vero_conv",
        "wickedid", "rb_clickid", "s_cid", "ml_subscriber", "ml_subscriber_hash", "srsltid", "_openstat",
        "epik", "pk_campaign", "pk_kwd", "piwik_campaign", "piwik_kwd", "matomo_campaign", "at_campaign",
        "at_medium", "ref_src", "ref_url", "cvid", "oicd", "sc_cid", "zanpid", "irclickid",
    )

    /** Removed only on the given domains (and their subdomains). */
    val PER_DOMAIN = mapOf(
        "youtube.com" to listOf("si", "feature", "pp"),
        "youtu.be" to listOf("si", "feature"),
        "music.youtube.com" to listOf("si", "feature"),
        "twitter.com" to listOf("s", "t", "ref_src"),
        "x.com" to listOf("s", "t"),
        "instagram.com" to listOf("igshid", "igsh", "img_index"),
        "facebook.com" to listOf("mibextid", "__cft__*", "__tn__"),
        "reddit.com" to listOf("share_id", "rdt"),
        "tiktok.com" to listOf("_r", "_t", "is_from_webapp", "sender_device", "sender_web_id"),
        "linkedin.com" to listOf("trk", "trackingId", "lipi", "midToken", "midSig", "eid", "refId"),
        "spotify.com" to listOf("si", "context", "nd"),
        "amazon.com" to listOf("pd_rd_*", "pf_rd_*", "ref_", "_encoding", "psc", "qid", "sr", "crid", "sprefix", "content-id", "dib", "dib_tag"),
        "aliexpress.com" to listOf("spm", "scm", "pvid", "algo_pvid", "algo_exp_id", "aff_*", "gatewayAdapt"),
        "bing.com" to listOf("form", "sp", "ghc", "lq", "pq", "sc", "sk"),
        "google.com" to listOf("ved", "ei", "gs_lcrp", "gs_lp", "sca_esv", "sca_upv", "uact", "sclient", "sxsrf", "oq", "aqs", "sourceid", "rlz", "iflsig", "biw", "bih", "dpr"),
    )

    fun strip(url: String): String {
        val parts = UrlParts.parse(url) ?: return url
        if (parts.scheme != "http" && parts.scheme != "https") return url
        val query = parts.query ?: return url
        val host = parts.host ?: return url

        val rules = GLOBAL + PER_DOMAIN.filterKeys { HostClassifier.matchesDomain(host, it) }.values.flatten()
        val kept = query.split('&').filter { parameter ->
            if (parameter.isEmpty()) return@filter false
            val name = parameter.substringBefore('=')
            rules.none { rule -> matches(rule, name) }
        }
        if (kept.size == query.split('&').size) return url
        return parts.copy(query = kept.joinToString("&").ifEmpty { null }).toString()
    }

    private fun matches(rule: String, name: String): Boolean =
        if (rule.endsWith("*")) name.startsWith(rule.dropLast(1), ignoreCase = true) else name.equals(rule, ignoreCase = true)
}

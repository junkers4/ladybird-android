package io.github.junkers4.ladybird.core.url

import java.net.URLEncoder

/**
 * A search engine: [template] contains `%s` where the URL-encoded query goes. [ladybirdName] is the
 * name of the same engine in Ladybird's own list (LibWebView/SearchEngine.cpp), used to keep the
 * engine's settings in sync, when it has one.
 */
data class SearchEngine(
    val id: String,
    val displayName: String,
    val template: String,
    val ladybirdName: String? = null,
) {
    init {
        require(template.startsWith("https://")) { "search engines must use https" }
        require(template.contains("%s")) { "template needs a %s placeholder" }
    }

    fun searchUrl(query: String): String = template.replace("%s", URLEncoder.encode(query, Charsets.UTF_8))

    companion object {
        /** DuckDuckGo's HTML endpoint works without JavaScript, which suits a privacy-focused default. */
        val DUCKDUCKGO = SearchEngine("duckduckgo", "DuckDuckGo", "https://html.duckduckgo.com/html/?q=%s", "DuckDuckGo")
        val BRAVE = SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s", "Brave")
        val STARTPAGE = SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s", "Startpage")
        val MOJEEK = SearchEngine("mojeek", "Mojeek", "https://www.mojeek.com/search?q=%s", "Mojeek")
        val QWANT = SearchEngine("qwant", "Qwant", "https://www.qwant.com/?q=%s", "Qwant")
        val ECOSIA = SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", "Ecosia")
        val WIKIPEDIA = SearchEngine("wikipedia", "Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s")
        val GOOGLE = SearchEngine("google", "Google", "https://www.google.com/search?q=%s", "Google")
        val BING = SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s", "Bing")

        val ALL = listOf(DUCKDUCKGO, BRAVE, STARTPAGE, MOJEEK, QWANT, ECOSIA, WIKIPEDIA, GOOGLE, BING)
        val DEFAULT = DUCKDUCKGO

        fun byId(id: String?): SearchEngine = ALL.firstOrNull { it.id == id } ?: DEFAULT
    }
}

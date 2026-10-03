package com.example.purebrowser.ui.browser

import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.data.browser.SavedPage
import java.net.URI

/** One mutually exclusive tool layer. Confirmation permissions remain owned by BrowserPanels. */
enum class BrowserTool { NONE, MENU, TABS, RESOURCES, DOWNLOADS }

data class AddressSuggestion(val title: String, val url: String, val source: String)
object BrowserChromeRules {
    fun displayAddress(url: String): String {
        if (url.isBlank() || url == HOME_URL) return "搜索或输入网址"
        // Never display user-info as part of a site identity; URI.host is ASCII for IDN safety.
        return runCatching {
            val uri = URI(url)
            val host = uri.host ?: return@runCatching "网址（点按查看完整地址）"
            host + if (uri.port >= 0) ":${uri.port}" else ""
        }.getOrDefault("网址（点按查看完整地址）")
    }
    fun suggestions(query: String, history: List<SavedPage>, bookmarks: List<SavedPage>): List<AddressSuggestion> {
        val term = query.trim()
        val entries = bookmarks.map { AddressSuggestion(it.title, it.url, "书签") } +
            history.map { AddressSuggestion(it.title, it.url, "历史") }
        return entries.distinctBy { it.url }.filter {
            term.isBlank() || it.title.contains(term, true) || it.url.contains(term, true)
        }.take(8)
    }
}

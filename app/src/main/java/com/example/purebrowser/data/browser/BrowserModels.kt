package com.example.purebrowser.data.browser

import com.example.purebrowser.browser.BrowserAddress
import java.util.UUID

const val HOME_URL = "about:blank"
enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
data class TabRecord(val id: String = UUID.randomUUID().toString(), val url: String = HOME_URL, val title: String = "新标签页")
data class SavedPage(val id: String = UUID.randomUUID().toString(), val title: String, val url: String, val time: Long = System.currentTimeMillis())
data class Shortcut(val id: String = UUID.randomUUID().toString(), val title: String, val url: String)
data class BrowserData(
    val tabs: List<TabRecord> = listOf(TabRecord()),
    val selectedId: String = tabs.first().id,
    val bookmarks: List<SavedPage> = emptyList(),
    val history: List<SavedPage> = emptyList(),
    val shortcuts: List<Shortcut> = listOf(Shortcut(title = "必应", url = "https://www.bing.com"), Shortcut(title = "维基百科", url = "https://zh.wikipedia.org"), Shortcut(title = "GitHub", url = "https://github.com")),
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

/** Pure state rules; views, cookies, candidates and credentials never enter persisted browser data. */
object BrowserRules {
    fun normalize(data: BrowserData): BrowserData {
        val tabs = data.tabs.filter { it.id.isNotBlank() && (it.url == HOME_URL || BrowserAddress.isWebUrl(it.url)) }.distinctBy { it.id }.take(50).ifEmpty { listOf(TabRecord()) }
        return data.copy(tabs = tabs, selectedId = data.selectedId.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id,
            bookmarks = data.bookmarks.filter { it.id.isNotBlank() && BrowserAddress.isWebUrl(it.url) }.distinctBy { it.id }.distinctBy { it.url }.take(1000),
            history = data.history.filter { it.id.isNotBlank() && BrowserAddress.isWebUrl(it.url) }.distinctBy { it.id }.take(1000),
            shortcuts = data.shortcuts.filter { it.id.isNotBlank() && BrowserAddress.isWebUrl(it.url) }.distinctBy { it.id }.take(12))
    }
    fun addTab(data: BrowserData, url: String = HOME_URL): BrowserData {
        require(data.tabs.size < 50) { "标签已达 50 个，请先关闭一些页面" }
        require(url == HOME_URL || BrowserAddress.isWebUrl(url))
        val tab = TabRecord(url = url)
        return data.copy(tabs = data.tabs + tab, selectedId = tab.id)
    }
    fun closeTab(data: BrowserData, id: String): BrowserData {
        val index = data.tabs.indexOfFirst { it.id == id }
        if (index < 0) return data
        val remaining = data.tabs.filterNot { it.id == id }.ifEmpty { listOf(TabRecord()) }
        val selected = if (data.selectedId == id) remaining[(index - 1).coerceIn(0, remaining.lastIndex)].id else data.selectedId
        return data.copy(tabs = remaining, selectedId = selected)
    }
    fun visit(data: BrowserData, url: String, title: String, now: Long = System.currentTimeMillis()): BrowserData {
        if (!BrowserAddress.isWebUrl(url)) return data
        val previous = data.history.firstOrNull()?.takeIf { it.url == url }
        val entry = SavedPage(previous?.id ?: UUID.randomUUID().toString(), title.take(180).ifBlank { url }, url, now)
        return data.copy(history = (listOf(entry) + if (previous == null) data.history else data.history.drop(1)).take(1000))
    }
}

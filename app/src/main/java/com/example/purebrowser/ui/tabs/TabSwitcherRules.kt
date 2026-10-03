package com.example.purebrowser.ui.tabs

import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.data.browser.TabRecord
import java.net.URI

/** UI-only rules: filtering never changes the controller's creation order or selection. */
object TabSwitcherRules {
    fun search(tabs: List<TabRecord>, query: String): List<TabRecord> {
        val term = query.trim()
        return if (term.isEmpty()) tabs else tabs.filter {
            it.title.contains(term, ignoreCase = true) || it.url.contains(term, ignoreCase = true)
        }
    }

    /** Locate always operates on the unfiltered order, even if search hid the active tab. */
    fun locate(tabs: List<TabRecord>, selected: String): TabLocation =
        TabLocation(query = "", index = tabs.indexOfFirst { it.id == selected }.takeIf { it >= 0 })

    fun subtitle(tab: TabRecord): String = if (tab.url == HOME_URL) "首页" else
        runCatching { URI(tab.url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "网页"
}

data class TabLocation(val query: String, val index: Int?)

enum class TabViewMode {
    GRID, LIST;

    companion object {
        fun fromStored(value: String?): TabViewMode = entries.firstOrNull { it.name == value } ?: GRID
    }
}

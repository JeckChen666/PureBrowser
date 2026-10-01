package com.example.purebrowser.browser

import android.content.Context
import com.example.purebrowser.data.browser.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TabController(
    private val context: Context,
    private val message: (String) -> Unit,
    private val changed: (List<TabRecord>, String) -> Unit,
    private val visited: (String, String) -> Unit,
    private val link: (String) -> Unit,
) {
    private var data = BrowserData()
    private val sessions = LinkedHashMap<String, BrowserSession>(8, .75f, true)
    private val mutableActive = MutableStateFlow<BrowserSession?>(null)
    val active = mutableActive.asStateFlow()

    fun restore(value: BrowserData) {
        clear()
        data = BrowserRules.normalize(value)
        activate()
    }
    fun newTab(url: String = HOME_URL) = mutate { BrowserRules.addTab(it, url) }
    fun select(id: String) { if (data.tabs.any { it.id == id }) mutate { it.copy(selectedId = id) } }
    fun close(id: String) {
        sessions.remove(id)?.destroy()
        mutate { BrowserRules.closeTab(it, id) }
    }
    private fun mutate(block: (BrowserData) -> BrowserData) {
        runCatching { block(data) }.onSuccess { data = it; changed(data.tabs, data.selectedId); activate() }
            .onFailure { message(it.message ?: "无法更新标签") }
    }
    private fun activate() {
        val record = data.tabs.first { it.id == data.selectedId }
        val session = sessions[record.id] ?: BrowserSession(record.id, context, record, message,
            { page -> update(record.id, page) }, visited, link).also { sessions[record.id] = it }
        mutableActive.value?.takeIf { it !== session }?.engine?.pause()
        mutableActive.value = session
        // Keep current + two recently used pages. Evicted pages explicitly reload on selection.
        while (sessions.size > 3) {
            val id = sessions.keys.first { it != record.id }
            sessions.remove(id)?.destroy()
        }
    }
    private fun update(id: String, page: BrowserPage) {
        val previous = data.tabs.firstOrNull { it.id == id } ?: return
        val url = page.url.takeIf { it == HOME_URL || BrowserAddress.isWebUrl(it) } ?: previous.url
        val title = if (url == HOME_URL) "新标签页" else page.title.ifBlank { previous.title }
        val updated = previous.copy(url = url, title = title)
        if (updated != previous) {
            data = data.copy(tabs = data.tabs.map { if (it.id == id) updated else it })
            changed(data.tabs, data.selectedId)
        }
    }
    fun clear() { sessions.values.toList().forEach { it.destroy() }; sessions.clear(); mutableActive.value = null }
}

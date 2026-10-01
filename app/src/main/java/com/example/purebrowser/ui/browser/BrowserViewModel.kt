package com.example.purebrowser.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.purebrowser.browser.*
import com.example.purebrowser.data.browser.*
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.media.MediaCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val mutableData = MutableStateFlow(BrowserData())
    val data = mutableData.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val storage = LocalBrowserRepository(application)
    private val writes = Channel<BrowserData>(Channel.CONFLATED)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableLink = MutableStateFlow<String?>(null)
    val link = mutableLink.asStateFlow()
    val tabs = TabController(application, ::notify,
        { list, selected -> change { it.copy(tabs = list, selectedId = selected) } },
        { url, title -> change { BrowserRules.visit(it, url, title) } },
        { mutableLink.value = it })
    val engine get() = tabs.active.value?.engine
    val sniffer get() = tabs.active.value?.sniffer
    val repository = DownloadRepository(application)
    private val mutableDownloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads = mutableDownloads.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { storage.load() }
            mutableData.value = loaded
            tabs.restore(loaded)
            mutableReady.value = true
            if (storage.recoveredCorruption) notify("本地数据读取失败，已使用默认配置；请检查本机存储")
            writer.launch {
                try {
                    for (snapshot in writes) runCatching { storage.save(snapshot) }
                        .onFailure { notify("本地保存失败，请检查可用存储空间") }
                } finally { writer.cancel() }
            }
        }
        viewModelScope.launch {
            while (isActive) { refreshDownloads(); delay(2000) }
        }
    }
    private fun change(block: (BrowserData) -> BrowserData) {
        val next = block(mutableData.value)
        if (next != mutableData.value) {
            mutableData.value = next
            if (mutableReady.value) writes.trySend(next)
        }
    }
    fun flush() { if (mutableReady.value) writes.trySend(mutableData.value) }
    fun notify(value: String) { mutableMessage.value = value }
    fun consumeMessage() { mutableMessage.value = null }
    fun dismissLink() { mutableLink.value = null }
    fun navigate(input: String) { engine?.navigate(input) }
    fun newTab(url: String = HOME_URL) { tabs.newTab(url) }
    fun setTheme(value: ThemeMode) { change { it.copy(theme = value) } }
    fun toggleBookmark() {
        val page = engine?.page?.value ?: return
        if (!BrowserAddress.isWebUrl(page.url) || page.error != null) { notify("请先打开一个有效网页"); return }
        val existing = data.value.bookmarks.firstOrNull { it.url == page.url }
        if (existing == null && data.value.bookmarks.size >= 1000) { notify("书签已达 1000 条，请先整理旧书签"); return }
        change { it.copy(bookmarks = if (existing == null) listOf(SavedPage(title = page.title.ifBlank { page.url }, url = page.url)) + it.bookmarks else it.bookmarks.filterNot { b -> b.id == existing.id }) }
        notify(if (existing == null) "已加入书签" else "已取消书签")
    }
    fun deleteBookmark(id: String) { change { it.copy(bookmarks = it.bookmarks.filterNot { p -> p.id == id }) } }
    fun deleteHistory(id: String) { change { it.copy(history = it.history.filterNot { p -> p.id == id }) } }
    fun clearHistory() { change { it.copy(history = emptyList()) } }
    fun deleteShortcut(id: String) { change { it.copy(shortcuts = it.shortcuts.filterNot { p -> p.id == id }) } }
    fun saveShortcut(existing: Shortcut?, title: String, input: String): Boolean = savePage(title, input) { name, url ->
        if (existing == null && data.value.shortcuts.size >= 12) error("最多添加 12 个常用站点")
        val entry = existing?.copy(title = name.take(30), url = url) ?: Shortcut(title = name.take(30), url = url)
        change { it.copy(shortcuts = if (existing == null) it.shortcuts + entry else it.shortcuts.map { s -> if (s.id == entry.id) entry else s }) }
    }
    fun editBookmark(existing: SavedPage, title: String, input: String): Boolean = savePage(title, input) { name, url ->
        require(data.value.bookmarks.none { it.id != existing.id && it.url == url }) { "这个网页已在书签中" }
        change { it.copy(bookmarks = it.bookmarks.map { p -> if (p.id == existing.id) p.copy(title = name, url = url) else p }) }
    }
    private fun savePage(title: String, input: String, operation: (String, String) -> Unit): Boolean = runCatching {
        require(title.isNotBlank()) { "请输入名称" }
        val value = input.trim().let { if (it.contains("://")) it else "https://$it" }
        require(BrowserAddress.isWebUrl(value) && value.length <= 8192) { "请输入有效的 HTTP / HTTPS 网址" }
        operation(title.trim().take(180), value)
    }.onFailure { notify(it.message ?: "无法保存") }.isSuccess
    fun download(candidate: MediaCandidate, userAgent: String, wifiOnly: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.enqueue(candidate, userAgent, wifiOnly) } }
                .onSuccess { notify("任务已加入下载中心") }
                .onFailure { notify("无法创建任务，请检查存储权限和资源地址") }
            refreshDownloads()
        }
    }
    fun removeDownload(id: Long) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.remove(id) } }.onFailure { notify("无法删除任务，请稍后重试") }
            refreshDownloads()
        }
    }
    private suspend fun refreshDownloads() {
        runCatching { withContext(Dispatchers.IO) { repository.snapshot() } }.onSuccess { mutableDownloads.value = it }
    }
    override fun onCleared() { flush(); tabs.clear(); writes.close(); super.onCleared() }
}

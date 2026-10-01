package com.example.purebrowser.ui.browser

import android.app.Application
import android.content.Context
import android.webkit.WebSettings
import com.example.purebrowser.download.DownloadPreferences
import com.example.purebrowser.library.LocalFileActions
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.purebrowser.browser.*
import com.example.purebrowser.data.browser.*
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.library.VideoLibraryRepository
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.media.MediaCandidate
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val library = VideoLibraryRepository(repository)
    private val mutableVideoLibrary = MutableStateFlow<List<VideoAsset>>(emptyList())
    val videoLibrary = mutableVideoLibrary.asStateFlow()
    private val refreshMutex = Mutex()
    private var downloadReadFailureReported = false
    private val preferences = DownloadPreferences(application)
    private val mutableWifiOnly = MutableStateFlow(true)
    val defaultWifiOnly = mutableWifiOnly.asStateFlow()
    private val mutableBusy = MutableStateFlow<Set<Long>>(emptySet())
    val busyIds = mutableBusy.asStateFlow()
    private val mutableSubmitting = MutableStateFlow(false)
    val submitting = mutableSubmitting.asStateFlow()
    private var preferenceWrite = false

    init {
        viewModelScope.launch {
            mutableWifiOnly.value = withContext(Dispatchers.IO) { preferences.wifiOnly() }
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
    fun downloadDraft(candidate: MediaCandidate, userAgent: String): DownloadDraft {
        val session = tabs.active.value
        val page = session?.engine?.page?.value
        return DownloadDraft(candidate, userAgent, page?.url?.takeIf(BrowserAddress::isWebUrl),
            page?.title, session?.recordId, session?.engine?.generation)
    }
    fun setDefaultWifiOnly(value: Boolean) {
        if(preferenceWrite) return
        preferenceWrite = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { preferences.saveWifiOnly(value) }
                mutableWifiOnly.value = value
            } catch (_: Exception) { notify("设置未能保存，请检查本机存储") }
            finally { preferenceWrite = false }
        }
    }
    fun download(draft: DownloadDraft, wifiOnly: Boolean, fileName: String? = null) {
        if(mutableSubmitting.value) return
        mutableSubmitting.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.enqueue(draft, wifiOnly, fileName) }
                notify("任务已加入下载中心")
            } catch (_: Exception) { notify("无法创建任务，请检查存储权限和资源地址") }
            finally { mutableSubmitting.value = false; refreshDownloads() }
        }
    }
    private fun operation(id: Long, success: String, action: () -> Unit) {
        if(id in mutableBusy.value) return
        mutableBusy.value += id
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; notify(success) }
            catch (error: Exception) { notify(error.message?.takeIf { !it.contains("://") && it.any { c -> c in '\u4e00'..'\u9fff' } } ?: "操作未完成，请刷新状态后检查访问权限和可用存储") }
            finally { mutableBusy.value -= id; refreshDownloads() }
        }
    }
    fun cancelDownload(id: Long) = operation(id, "已取消；可以另建任务重试") { repository.cancel(id) }
    fun forgetDownload(id: Long) = operation(id, "已移除记录，设备文件未删除") { repository.forgetRecord(id) }
    fun deleteDownloadFile(id: Long) = operation(id, "已确认删除文件和记录") { repository.deleteFile(id) }
    fun renameVideo(id: Long, value: String) = operation(id, "显示名称已更新，设备文件名未改变") { repository.rename(id, value) }
    fun retryDownload(id: Long) {
        val agent = WebSettings.getDefaultUserAgent(getApplication())
        val policy = mutableWifiOnly.value
        operation(id, "已另建重试任务；原记录和文件保留") { repository.retry(id, agent, policy) }
    }
    fun removeDownload(id: Long) = operation(id, "操作完成") { repository.remove(id) }
    fun launchFile(context: Context, id: Long, share: Boolean) {
        if(id in mutableBusy.value) return
        mutableBusy.value += id
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val uri = repository.fileUri(id) ?: error("文件无法读取或格式未确认，请刷新下载中心")
                    uri to repository.mimeType(id)
                }
                LocalFileActions.launch(context, id, file.first, file.second, share)?.let(::notify)
            } catch (_: Exception) { notify("文件无法读取或格式未确认，请刷新下载中心并检查权限") }
            finally { mutableBusy.value -= id; refreshDownloads() }
        }
    }
    /** Restore the recorded source without replacing an unrelated browsing tab. */
    fun returnToSource(id: Long) {
        viewModelScope.launch {
            val record = runCatching { withContext(Dispatchers.IO) { repository.record(id) } }.getOrNull()
            val url = record?.sourceUrl
            if(url == null) { notify("旧记录没有保存来源页面"); return@launch }
            val matching = data.value.tabs.firstOrNull { it.id == record.sourceTabId && it.url == url }
                ?: data.value.tabs.firstOrNull { it.url == url }
            if(matching != null) tabs.select(matching.id) else newTab(url)
        }
    }
    fun reconcileDownloads() { viewModelScope.launch { refreshDownloads() } }
    private suspend fun refreshDownloads() = refreshMutex.withLock {
        runCatching { withContext(Dispatchers.IO) { repository.stateSnapshot() } }
            .onSuccess { state ->
                downloadReadFailureReported = false
                mutableDownloads.value = state.tasks
                mutableVideoLibrary.value = library.entries(state)
                repository.takeNotice()?.let(::notify)
            }.onFailure {
                if (!downloadReadFailureReported) notify("无法读取或保存下载记录，请检查本机存储")
                downloadReadFailureReported = true
            }
    }
    override fun onCleared() { flush(); tabs.clear(); writes.close(); super.onCleared() }
}

package com.example.purebrowser.browser

import android.content.Context
import android.os.Looper
import android.webkit.WebView
import java.lang.ref.WeakReference
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
    val previewCache = TabPreviewCache()
    private val sessionTokens = mutableMapOf<String, Long>()
    private var nextSessionToken = 0L
    private var previewSource: WeakReference<WebView>? = null
    private var previewSourceId: String? = null
    private var suppressedPreviewKey: TabPreviewKey? = null

    /**
     * Host integration: bind the already-mounted session's WebView, and pass null on unmount.
     * This does not retain an Activity/view or create a WebView for an inactive tab.
     */
    fun bindPreviewSource(tabId: String, view: WebView?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (view == null) {
            if (previewSourceId == tabId) { previewSource = null; previewSourceId = null }
            return
        }
        if (active.value?.recordId != tabId) return
        if (previewSourceId == tabId && previewSource?.get() === view) return
        previewSourceId = tabId
        previewSource = WeakReference(view)
        // Mount can precede layout; post once rather than polling or rendering offscreen.
        view.post { capturePreview(force = false) }
    }

    /** Extra hook for privacy categories that do not already tear down sessions via clear(). */
    fun clearPreviews() {
        // Late title/progress callbacks must not immediately repopulate a just-cleared page.
        suppressedPreviewKey = active.value?.let { previewKey(it) }
        previewCache.clear()
    }

    /** Call once when opening the overview, not when toggling its grid/list presentation. */
    fun captureActivePreview() { capturePreview(force = true) }

    /** Main shell can supply BrowserSession.mountedPreviewView() immediately before opening tabs. */
    fun captureActivePreview(view: WebView?) {
        val session = active.value ?: return
        bindPreviewSource(session.recordId, view)
        capturePreview(force = true)
    }

    private fun previewKey(session: BrowserSession, page: BrowserPage = session.engine.page.value) =
        TabPreviewKey(session.recordId, session.engine.generation, page.url, sessionTokens[session.recordId] ?: 0L)

    private fun capturePreview(force: Boolean) {
        val session = active.value ?: return
        val view = previewSource?.get() ?: return
        if (previewSourceId != session.recordId) return
        val page = session.engine.page.value
        if (page.progress < 100 || page.error != null || page.url == HOME_URL) return
        val key = previewKey(session)
        if (!force && suppressedPreviewKey == key) return
        if (force) suppressedPreviewKey = null // Explicitly reopening the overview permits a new capture.
        previewCache.advance(key)
        val weakView = WeakReference(view)
        previewCache.capture(key, view, force) {
            active.value === session && previewSourceId == session.recordId &&
                previewSource?.get() === weakView.get() && previewKey(session) == key &&
                session.engine.page.value.let { it.progress == 100 && it.error == null }
        }
    }

    fun restore(value: BrowserData) {
        clear()
        data = BrowserRules.normalize(value)
        activate()
    }
    fun newTab(url: String = HOME_URL) = mutate { BrowserRules.addTab(it, url) }
    fun select(id: String) { if (data.tabs.any { it.id == id }) mutate { it.copy(selectedId = id) } }
    fun close(id: String) {
        previewCache.remove(id)
        sessionTokens.remove(id)
        if (previewSourceId == id) { previewSource = null; previewSourceId = null }
        sessions.remove(id)?.destroy()
        mutate { BrowserRules.closeTab(it, id) }
    }
    private fun mutate(block: (BrowserData) -> BrowserData) {
        runCatching { block(data) }.onSuccess { data = it; changed(data.tabs, data.selectedId); activate() }
            .onFailure { message(it.message ?: "无法更新标签") }
    }
    private fun activate() {
        val record = data.tabs.first { it.id == data.selectedId }
        val session = sessions[record.id] ?: run {
            sessionTokens[record.id] = ++nextSessionToken
            BrowserSession(record.id, context, record, message,
                { page -> update(record.id, page) }, visited, link).also { sessions[record.id] = it }
        }
        previewCache.advance(previewKey(session))
        mutableActive.value?.takeIf { it !== session }?.let {
            it.engine.pause()
            previewSource = null
            previewSourceId = null
        }
        mutableActive.value = session
        // Keep current + two recently used pages. Evicted pages explicitly reload on selection.
        while (sessions.size > 3) {
            val id = sessions.keys.first { it != record.id }
            sessions.remove(id)?.destroy()
            sessionTokens.remove(id)
            // The small preview may outlive an evicted session, but recreation gets a new token.
        }
    }
    private fun update(id: String, page: BrowserPage) {
        val previous = data.tabs.firstOrNull { it.id == id } ?: return
        sessions[id]?.let { session ->
            previewCache.advance(previewKey(session, page))
            if (page.error != null) previewCache.remove(id)
        }
        val url = page.url.takeIf { it == HOME_URL || BrowserAddress.isWebUrl(it) } ?: previous.url
        val title = if (url == HOME_URL) "新标签页" else page.title.ifBlank { previous.title }
        val updated = previous.copy(url = url, title = title)
        if (updated != previous) {
            data = data.copy(tabs = data.tabs.map { if (it.id == id) updated else it })
            changed(data.tabs, data.selectedId)
        }
        if (id == data.selectedId && page.progress == 100 && page.error == null) capturePreview(force = false)
    }
    fun clear() {
        // Privacy teardown/onCleared/restore all invalidate pending captures before destroying views.
        previewSource = null
        previewSourceId = null
        suppressedPreviewKey = null
        previewCache.clear()
        sessionTokens.clear()
        mutableActive.value = null
        sessions.values.toList().forEach { it.destroy() }
        sessions.clear()
    }
}

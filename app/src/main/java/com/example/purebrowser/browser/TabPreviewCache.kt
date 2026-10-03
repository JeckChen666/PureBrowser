package com.example.purebrowser.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** Session token prevents an evicted/recreated WebView's generation zero from reusing an old image. */
data class TabPreviewKey(val tabId: String, val navigation: Long, val url: String, val session: Long)

/**
 * Main-thread, session-only preview store. At most 12 small ARGB images / 3 MiB are retained,
 * plus one in-flight 320 x 200 capture. Never serializes, uploads, or logs pixels/URLs.
 * Published bitmaps are NOT recycled: Compose may still draw the previous frame. Eviction,
 * close and clear drop cache ownership and notify readers so those frames can be collected.
 */
class TabPreviewCache(
    val maxEntries: Int = 12,
    val maxBytes: Long = 3L * 1024 * 1024,
) {
    private data class Entry(val key: TabPreviewKey, val bitmap: Bitmap)
    private val images = LinkedHashMap<String, Entry>()
    private val keys = LinkedHashMap<String, TabPreviewKey>()
    private var requestSerial = 0L
    private var pending: TabPreviewKey? = null
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    var byteCount: Long = 0L
        private set
    val entryCount: Int get() = images.size

    init { require(maxEntries > 0 && maxBytes > 0) }

    fun advance(key: TabPreviewKey) {
        if (keys[key.tabId] == key) return
        // Metadata is bounded too; the controller never permits more than 50 open tabs.
        if (key.tabId !in keys && keys.size >= 50) remove(keys.keys.first())
        keys[key.tabId] = key
        discardImage(key.tabId)
        if (pending?.tabId == key.tabId) cancelPending()
        publish()
    }

    fun get(tabId: String, url: String): Bitmap? {
        val entry = images[tabId] ?: return null
        return entry.bitmap.takeIf { entry.key == keys[tabId] && entry.key.url == url && !it.isRecycled }
    }

    /** Takes cache ownership only for the still-current navigation; bounded FIFO capture order. */
    internal fun put(key: TabPreviewKey, bitmap: Bitmap): Boolean {
        if (keys[key.tabId] != key || !BrowserAddress.isWebUrl(key.url) || bitmap.isRecycled ||
            bitmap.width > WIDTH || bitmap.height > HEIGHT || bitmap.allocationByteCount > maxBytes) return false
        discardImage(key.tabId)
        while (images.isNotEmpty() && (images.size >= maxEntries || byteCount + bitmap.allocationByteCount > maxBytes)) {
            discardImage(images.keys.first())
        }
        images[key.tabId] = Entry(key, bitmap)
        byteCount += bitmap.allocationByteCount
        publish()
        return true
    }

    fun remove(tabId: String) {
        keys.remove(tabId)
        discardImage(tabId)
        if (pending?.tabId == tabId) cancelPending()
        publish()
    }

    fun clear() {
        cancelPending()
        images.clear()
        keys.clear()
        byteCount = 0L
        publish()
    }

    /**
     * Draws only an already-mounted active WebView after its visual-state fence. Callback
     * checks include navigation, active identity, URL, attachment, and privacy-clear serial.
     * Never mounts/recreates inactive pages. One pending request; UI view-mode changes only read.
     */
    internal fun capture(key: TabPreviewKey, view: WebView, force: Boolean = false, isCurrent: () -> Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!BrowserAddress.isWebUrl(key.url) || keys[key.tabId] != key || pending == key ||
            (!force && get(key.tabId, key.url) != null) || !isCurrent() || !drawable(view, key)) return
        val serial = ++requestSerial
        pending = key
        val weakView = WeakReference(view)
        runCatching {
            view.postVisualStateCallback(serial, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (serial != requestSerial || pending != key) return
                    pending = null
                    val source = weakView.get() ?: return
                    if (keys[key.tabId] != key || !isCurrent() || !drawable(source, key)) return
                    var bitmap: Bitmap? = null
                    try {
                        // Scale the viewport into a short thumbnail; never allocate a full-page image.
                        val width = minOf(WIDTH, source.width)
                        val height = minOf(HEIGHT.toLong(), source.height.toLong() * width / source.width).coerceAtLeast(1L).toInt()
                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.scale(width.toFloat() / source.width, width.toFloat() / source.width)
                        source.draw(canvas)
                        if (!isCurrent() || !put(key, bitmap)) bitmap.recycle()
                    } catch (_: Exception) {
                        if (images[key.tabId]?.bitmap !== bitmap) bitmap?.recycle()
                    } catch (_: OutOfMemoryError) {
                        if (images[key.tabId]?.bitmap !== bitmap) bitmap?.recycle()
                    }
                }
            })
        }.onFailure { if (serial == requestSerial) pending = null }
    }

    private fun drawable(view: WebView, key: TabPreviewKey) =
        view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0 && view.url == key.url

    private fun cancelPending() { requestSerial++; pending = null }
    private fun discardImage(tabId: String) {
        images.remove(tabId)?.let { byteCount -= it.bitmap.allocationByteCount }
    }
    private fun publish() { mutableRevision.value++ }

    companion object {
        const val WIDTH = 320
        const val HEIGHT = 200
    }
}

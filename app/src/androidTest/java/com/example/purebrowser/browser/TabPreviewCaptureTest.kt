package com.example.purebrowser.browser

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.example.purebrowser.data.browser.BrowserData
import com.example.purebrowser.data.browser.TabRecord
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Authored loopback HTML in one mounted WebView; the other 49 tabs are not loaded for previews. */
class TabPreviewCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var controller: TabController? = null
    private var source: WebView? = null
    private var fixture: RedPageServer? = null

    @After fun cleanup() {
        try {
            compose.runOnUiThread {
                if (controller != null) controller?.clear() else source?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.destroy()
                }
                source = null
            }
        } finally {
            // Stop the socket/workers even when a readiness or pixel assertion fails.
            fixture?.close()
            fixture = null
        }
    }

    @Test fun controllerCapturesRealActivePixelsWithoutChangingSessionOrLoadingInactiveTabs() {
        val server = RedPageServer().also { fixture = it }
        val url = server.url
        lateinit var tabs: TabController
        lateinit var session: BrowserSession
        compose.runOnUiThread {
            tabs = TabController(compose.activity, {}, { _, _ -> }, { _, _ -> }, {}).also { controller = it }
            tabs.restore(BrowserData(tabs = (0 until 50).map { TabRecord("$it") }, selectedId = "0"))
            session = tabs.active.value!!
        }
        compose.setContent {
            AndroidView(factory = { context -> FrameLayout(context).also { host ->
                session.mount(host)
                val view = host.getChildAt(0) as WebView
                source = view
                tabs.bindPreviewSource(session.recordId, view)
            } }, modifier = Modifier.fillMaxSize(), onRelease = { host ->
                tabs.bindPreviewSource(session.recordId, null)
                session.unmount(host)
            })
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            // Actual HTTP navigation: callback URL, WebView.getUrl(), and preview key must agree.
            // A base-URL data document is not a substitute for that contract on older providers.
            source!!.loadUrl(url)
        }
        awaitOnMain("Real loopback page and mounted viewport", state = {
            "${viewState(source!!)}; page=${session.engine.page.value}; " +
                "navigation=${session.engine.generation}; ${server.state()}"
        }) {
            session.engine.page.value.let {
                it.url == url && it.progress == 100 && it.error == null && it.title == "Loopback preview"
            } &&
                drawable(source!!, url) && source!!.progress == 100 && server.pageRequests.get() > 0
        }
        compose.runOnUiThread {
            assertEquals(url, source!!.url)
            assertEquals(url, session.engine.page.value.url)
            assertTrue("loadUrl must exercise a real BrowserEngine navigation", session.engine.generation > 0)
            tabs.captureActivePreview()
        }
        awaitOnMain("Real active preview after the native visual fence", state = {
            "${viewState(source!!)}; page=${session.engine.page.value}; navigation=${session.engine.generation}; " +
                "cacheEntries=${tabs.previewCache.entryCount}; cacheBytes=${tabs.previewCache.byteCount}; ${server.state()}"
        }) {
            tabs.previewCache.revision.value > 0 && tabs.previewCache.get("0", url) != null
        }
        compose.runOnUiThread {
            val bitmap = tabs.previewCache.get("0", url)!!
            assertTrue(bitmap.width <= TabPreviewCache.WIDTH)
            assertTrue(bitmap.height <= TabPreviewCache.HEIGHT)
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("Expected the real red HTML background", Color.red(pixel) > 200 && Color.green(pixel) < 60 && Color.blue(pixel) < 60)
            assertEquals(1, tabs.previewCache.entryCount)
            assertSame(session, tabs.active.value)
            assertNull(tabs.previewCache.get("0", "$url/wrong-navigation"))
            (1 until 50).forEach { assertNull(tabs.previewCache.get("$it", url)) }
            tabs.clearPreviews()
            assertEquals(0, tabs.previewCache.entryCount)
            assertEquals(0L, tabs.previewCache.byteCount)
            assertSame(session, tabs.active.value)
            source!!.evaluateJavascript("document.title='late-after-clear'", null)
        }
        awaitOnMain("Late title update after privacy clear", state = { "page=${session.engine.page.value}" }) {
            session.engine.page.value.title == "late-after-clear"
        }
        compose.runOnUiThread {
            assertEquals("A late title callback must not repopulate the cleared navigation", 0, tabs.previewCache.entryCount)
            assertEquals(0L, tabs.previewCache.byteCount)
        }
    }

    @Test fun pendingVisualFenceCannotRepublishAfterPrivacyClearNavigationOrClose() {
        val server = RedPageServer().also { fixture = it }
        val url = server.url
        lateinit var view: GatedWebView
        val cache = TabPreviewCache()
        compose.setContent {
            AndroidView(factory = { context -> GatedWebView(context, url).also {
                view = it
                source = it
            } }, modifier = Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnUiThread { view.loadUrl(url) }
        awaitOnMain("Gated real loopback page and mounted viewport", state = {
            "${viewState(view)}; finished=${view.ready.get()}; ${server.state()}"
        }) {
            view.ready.get() && drawable(view, url) && view.progress == 100 && server.pageRequests.get() > 0
        }
        compose.runOnUiThread {
            assertEquals(url, view.url)
            val key = TabPreviewKey("active", 1, url, 1)
            cache.advance(key)
            cache.capture(key, view) { true }
            assertEquals("Ready view must reach the intercepted visual fence: ${viewState(view)}", 1, view.callbacks.size)
            val first = view.callbacks.removeAt(0)
            cache.clear()
            first.second.onComplete(first.first)
            assertEquals(0, cache.entryCount)
            assertNull(cache.get("active", url))

            cache.advance(key)
            cache.capture(key, view) { true }
            assertEquals(1, view.callbacks.size)
            val second = view.callbacks.removeAt(0)
            cache.advance(key.copy(navigation = 2)) // Same-URL reload is also a new navigation.
            second.second.onComplete(second.first)
            assertEquals(0, cache.entryCount)
            assertNull(cache.get("active", url))
            cache.capture(key, view) { true } // A stale navigation key cannot request another fence.
            assertEquals(0, view.callbacks.size)

            val next = key.copy(navigation = 2)
            cache.capture(next, view) { true }
            assertEquals(1, view.callbacks.size)
            val third = view.callbacks.removeAt(0)
            cache.remove("active")
            third.second.onComplete(third.first)
            assertEquals(0, cache.entryCount)
            assertEquals(0L, cache.byteCount)
            assertNull(cache.get("active", url))
        }
    }

    /** All WebView state (including getUrl/progress) and cache reads stay on the main thread. */
    private fun awaitOnMain(description: String, state: () -> String, condition: () -> Boolean) {
        var lastState = "not observed"
        try {
            compose.waitUntil(15_000) {
                var ready = false
                compose.runOnUiThread {
                    lastState = state()
                    ready = condition()
                }
                ready
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("$description timed out: $lastState", failure)
        }
    }

    private fun drawable(view: WebView, url: String) =
        view.isAttachedToWindow && view.isShown && view.width > 0 && view.height > 0 && view.url == url

    private fun viewState(view: WebView) =
        "viewUrl=${view.url}; attached=${view.isAttachedToWindow}; shown=${view.isShown}; " +
            "viewport=${view.width}x${view.height}; progress=${view.progress}"

    /** Intercept only the readiness fence; attachment/size/URL come from a real HTTP-loaded view. */
    private class GatedWebView(context: Context, expectedUrl: String) : WebView(context) {
        val callbacks = mutableListOf<Pair<Long, VisualStateCallback>>()
        val ready = AtomicBoolean(false)
        init {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    ready.set(url == expectedUrl && view.url == expectedUrl)
                }
            }
        }
        override fun postVisualStateCallback(requestId: Long, callback: VisualStateCallback) {
            callbacks += requestId to callback
        }
    }

    /** In-process fixture: ephemeral IPv4 loopback port, memory-only authored HTML, no redirects. */
    private class RedPageServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val stopped = AtomicBoolean(false)
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        private val failure = AtomicReference<String?>(null)
        val pageRequests = AtomicInteger(0)
        val url = "http://127.0.0.1:${listener.localPort}/preview"
        private val workers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "tab-preview-fixture-response").apply { isDaemon = true }
        }
        private val acceptor = thread(start = true, isDaemon = true, name = "tab-preview-fixture-accept") {
            while (!stopped.get()) {
                try {
                    val client = listener.accept()
                    clients.add(client)
                    workers.execute { respond(client) }
                } catch (error: Exception) {
                    if (!stopped.get()) failure.compareAndSet(null, error.toString())
                }
            }
        }

        private fun respond(client: Socket) {
            try {
                client.use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = input.readLine() ?: return
                    var completeHeaders = false
                    // Only WebView's local GET is expected; don't wait indefinitely on spare sockets.
                    for (index in 0 until 64) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) { completeHeaders = true; break }
                    }
                    if (!completeHeaders) return
                    val isPage = request.startsWith("GET /preview HTTP/")
                    val body = if (isPage) HTML.toByteArray(Charsets.UTF_8) else byteArrayOf()
                    if (isPage) pageRequests.incrementAndGet()
                    val status = if (isPage) "200 OK" else "204 No Content"
                    val headers = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                        "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(Charsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            } catch (error: IOException) {
                if (!stopped.get()) failure.compareAndSet(null, error.toString())
            } finally { clients.remove(client) }
        }

        fun state() = "fixtureRequests=${pageRequests.get()}; fixtureFailure=${failure.get()}"

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            listener.close()
            clients.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            acceptor.join(2_000)
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val HTML = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Loopback preview</title><link rel='icon' href='data:,'></head>" +
            "<body style='margin:0;background:#ff0000;height:100vh'></body></html>"
    }
}

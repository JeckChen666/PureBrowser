package com.example.purebrowser.browser

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.ResourceSniffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference

/** Pending intents stay in memory. Real-renderer cases intercept every request with offline HTML. */
class BrowserPendingNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixture: OfflineFixture? = null

    @After fun cleanup() = onMain {
        fixture?.close()
        fixture = null
    }

    @Test(timeout = 30_000)
    fun latestResolvedRequestWins_invalidInputPreservesIt_withoutPrematurePublication() = onMain {
        val messages = mutableListOf<String>()
        val publications = mutableListOf<BrowserPage>()
        val engine = BrowserEngine(ResourceSniffer(), { messages.add(it) }, OLD, { publications.add(it) })
        val view = TrackingView(compose.activity)
        try {
            engine.navigate(FIRST)
            engine.navigate("pending.fixture.invalid/target?token=fixture%2Bsignature&part=%2Fraw#kept")
            listOf("", "javascript:alert(1)", "https://user:secret@pending.fixture.invalid/").forEach(engine::navigate)
            assertEquals(3, messages.size)
            assertEquals(OLD, engine.page.value.url)
            assertEquals(0L, engine.generation)
            assertTrue(publications.isEmpty())
            assertTrue(view.loads.isEmpty())
            engine.attach(view)
            assertEquals(listOf(TARGET), view.loads)
            assertEquals("Only WebView navigation callbacks advance the scan generation", 0L, engine.generation)
        } finally { engine.detach(view) }
    }

    @Test(timeout = 30_000)
    fun invalidInputKeepsInitialDestination_andMountedInvalidInputDoesNotNavigate() = onMain {
        val messages = mutableListOf<String>()
        val engine = BrowserEngine(ResourceSniffer(), { messages.add(it) }, OLD)
        val view = TrackingView(compose.activity)
        try {
            engine.navigate("file:///private/test.html")
            engine.attach(view)
            assertEquals(listOf(OLD), view.loads)
            engine.navigate("https://pending.fixture.invalid:99999/invalid")
            assertEquals(listOf(OLD), view.loads)
            assertEquals(2, messages.size)
        } finally { engine.detach(view) }
    }

    @Test(timeout = 30_000)
    fun homeIsTheLatestIntent_evenBeforeTheFirstMount() = onMain {
        val engine = BrowserEngine(ResourceSniffer(), {}, OLD)
        val view = TrackingView(compose.activity)
        try {
            engine.navigate(TARGET)
            engine.home()
            engine.navigate("javascript:ignored")
            engine.attach(view)
            assertEquals(listOf("about:blank"), view.loads)
        } finally { engine.detach(view) }
    }

    @Test(timeout = 30_000)
    fun attachConsumesOnce_repeatedMountDoesNotReplay_andLaterMountedNavigationIsImmediate() = onMain {
        val engine = BrowserEngine(ResourceSniffer(), {}, OLD)
        val firstView = TrackingView(compose.activity)
        var secondView: TrackingView? = null
        var firstDetached = false
        try {
            engine.navigate(TARGET)
            engine.attach(firstView)
            repeat(3) { engine.attach(firstView) }
            assertEquals(listOf(TARGET), firstView.loads)
            engine.navigate(NEXT)
            engine.attach(firstView)
            assertEquals(listOf(TARGET, NEXT), firstView.loads)
            engine.detach(firstView)
            firstDetached = true
            val replacement = TrackingView(compose.activity)
            secondView = replacement
            engine.attach(replacement)
            // TrackingView never publishes page callbacks: the fallback is still OLD. Replaying
            // TARGET or NEXT here would reveal an already-consumed pending intent.
            assertEquals(listOf(OLD), replacement.loads)
        } finally {
            if (!firstDetached) engine.detach(firstView)
            secondView?.let(engine::detach)
        }
    }

    @Test(timeout = 30_000)
    fun detachedViewCannotPublishLatePageHistoryDownloadOrScanCallbacks() = onMain {
        val sniffer = ResourceSniffer()
        val messages = mutableListOf<String>()
        val publications = mutableListOf<BrowserPage>()
        val visits = mutableListOf<String>()
        val engine = BrowserEngine(sniffer, { messages.add(it) }, OLD,
            { publications.add(it) }, { url, _ -> visits.add(url) })
        val view = TrackingView(compose.activity)
        var detached = false
        try {
            engine.navigate(TARGET)
            engine.attach(view)
            engine.scanMedia()
            val abandonedScan = view.scans.single()
            val oldClient = view.webViewClient
            val oldChrome = requireNotNull(view.webChromeClient)
            val oldDownload = requireNotNull(view.downloadCallback)
            val oldLongClick = requireNotNull(view.longClickCallback)
            engine.detach(view)
            detached = true
            val page = engine.page.value
            val generation = engine.generation
            val published = publications.size
            oldChrome.onProgressChanged(view, 7)
            oldChrome.onReceivedTitle(view, "Abandoned page")
            oldClient.onPageStarted(view, FIRST, null)
            oldClient.onPageFinished(view, FIRST)
            oldClient.doUpdateVisitedHistory(view, FIRST, false)
            oldClient.onReceivedHttpError(view, request(FIRST), WebResourceResponse("text/html", "UTF-8", 403,
                "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf())))
            oldClient.shouldInterceptRequest(view, request("https://pending.fixture.invalid/abandoned.mp4"))
            oldDownload.onDownloadStart("https://pending.fixture.invalid/abandoned.mp4", "test-agent", "", "video/mp4", 10)
            assertFalse(oldLongClick.onLongClick(view))
            abandonedScan.onReceiveValue(JSONObject.quote("""[{"url":"https://pending.fixture.invalid/abandoned.mp4","video":true}]"""))
            assertEquals(page, engine.page.value)
            assertEquals(generation, engine.generation)
            assertEquals(published, publications.size)
            assertTrue(messages.isEmpty())
            assertTrue(visits.isEmpty())
            assertTrue(sniffer.candidates.value.isEmpty())
        } finally { if (!detached) engine.detach(view) }
    }

    @Test(timeout = 60_000)
    fun realOfflineWebViewLoadsPendingDestinationOnce_andKeepsNormalBackHistoryAndGenerations() {
        val engine = BrowserEngine(ResourceSniffer(), {}, OLD)
        onMain { engine.navigate(TARGET) }
        mount(engine)
        awaitPage(engine, TARGET, "Target")
        onMain {
            val view = requireNotNull(fixture).view
            assertEquals(listOf(TARGET), view.loads)
            assertEquals(1, view.copyBackForwardList().size)
            assertFalse(engine.page.value.canGoBack)
            repeat(3) { engine.attach(view) }
            assertEquals(listOf(TARGET), view.loads)
        }
        val firstGeneration = engine.generation
        onMain { engine.navigate(NEXT) }
        awaitPage(engine, NEXT, "Next")
        val secondGeneration = engine.generation
        onMain {
            assertTrue(engine.generation > firstGeneration)
            assertTrue(engine.page.value.canGoBack)
            val history = requireNotNull(fixture).view.copyBackForwardList()
            assertEquals(2, history.size)
            assertEquals(TARGET, history.getItemAtIndex(0).url)
            engine.back()
        }
        awaitPage(engine, TARGET, "Target")
        onMain {
            assertTrue(engine.generation > secondGeneration)
            assertTrue(engine.page.value.canGoForward)
            assertEquals(listOf(TARGET, NEXT), requireNotNull(fixture).view.loads)
        }
    }

    @Test(timeout = 60_000)
    fun recreatedEngineAfterSessionTeardownHonorsTheFirstNavigation_beforeItsReplacementMounts() {
        val original = BrowserEngine(ResourceSniffer(), {}, OLD)
        mount(original)
        awaitPage(original, OLD, "Old")
        lateinit var recreated: BrowserEngine
        onMain {
            val mounted = requireNotNull(fixture)
            mounted.teardownSession()
            // Representative of privacy clear: destroy the old session, then lazily recreate
            // from its retained tab record before Compose has mounted a replacement WebView.
            recreated = BrowserEngine(ResourceSniffer(), {}, OLD)
            recreated.navigate(TARGET)
            assertEquals(OLD, recreated.page.value.url)
            assertEquals(0L, recreated.generation)
            mounted.mountSession(recreated)
        }
        awaitPage(recreated, TARGET, "Target")
        onMain {
            assertEquals(listOf(TARGET), requireNotNull(fixture).view.loads)
            assertEquals(1, requireNotNull(fixture).view.copyBackForwardList().size)
            assertFalse(recreated.page.value.canGoBack)
        }
    }

    private fun mount(engine: BrowserEngine) {
        compose.setContent {
            AndroidView(
                factory = { context -> OfflineFixture(context).also { fixture = it; it.mountSession(engine) }.host },
                modifier = Modifier.fillMaxSize(),
                onRelease = { fixture?.close() },
            )
        }
    }

    private fun awaitPage(engine: BrowserEngine, url: String, title: String) {
        compose.waitUntil(15_000) {
            engine.page.value.let { it.url == url && it.title == title && it.progress == 100 && it.error == null }
        }
        // Assert the real DOM, not just the engine's stored URL or intercepted load command.
        val location = AtomicReference<String?>()
        onMain { requireNotNull(fixture).view.evaluateJavascript("location.href") { location.set(it) } }
        compose.waitUntil(10_000) { location.get() != null }
        assertEquals(url, org.json.JSONTokener(location.get()).nextValue())
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
    }

    /** Controlled load/scan boundaries, mirroring BrowserScanNavigationTest without real requests. */
    private class TrackingView(context: Context) : WebView(context) {
        private var currentUrl = OLD
        val loads = mutableListOf<String>()
        val scans = mutableListOf<ValueCallback<String>>()
        var downloadCallback: DownloadListener? = null
        var longClickCallback: View.OnLongClickListener? = null
        override fun getUrl(): String = currentUrl
        override fun loadUrl(url: String) { loads.add(url); currentUrl = url }
        override fun evaluateJavascript(script: String, callback: ValueCallback<String>?) { scans.add(requireNotNull(callback)) }
        override fun setDownloadListener(listener: DownloadListener?) { downloadCallback = listener; super.setDownloadListener(listener) }
        override fun setOnLongClickListener(listener: View.OnLongClickListener?) { longClickCallback = listener; super.setOnLongClickListener(listener) }
    }

    private class OfflineFixture(private val context: Context) {
        val host = FrameLayout(context)
        private var engine: BrowserEngine? = null
        private var mounted: OfflineView? = null
        val view: OfflineView get() = requireNotNull(mounted)
        fun mountSession(next: BrowserEngine) {
            check(mounted == null)
            engine = next
            val webView = OfflineView(context)
            mounted = webView
            host.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            next.attach(webView)
        }
        fun teardownSession() {
            mounted?.let { webView -> host.removeView(webView); engine?.detach(webView) }
            mounted = null
            engine = null
        }
        fun close() = teardownSession()
    }

    /** All URLs, including favicon/subresource requests, are served in-process: no socket or TLS exception. */
    private class OfflineView(context: Context) : WebView(context) {
        val loads = mutableListOf<String>()
        override fun loadUrl(url: String) { loads.add(url); super.loadUrl(url) }
        override fun setWebViewClient(client: WebViewClient) {
            super.setWebViewClient(object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse {
                    client.shouldInterceptRequest(v, request)
                    val title = when (request.url.path) { "/old" -> "Old"; "/target" -> "Target"; "/next" -> "Next"; else -> "Fixture" }
                    val html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>$title</title></head><body>$title</body></html>"
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) = client.onPageStarted(v, url, favicon)
                override fun onPageFinished(v: WebView, url: String?) = client.onPageFinished(v, url)
                override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) = client.doUpdateVisitedHistory(v, url, isReload)
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean = client.shouldOverrideUrlLoading(v, request)
                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) = client.onReceivedError(v, request, error)
                override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) = client.onReceivedHttpError(v, request, response)
            })
        }
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    companion object {
        private const val OLD = "https://pending.fixture.invalid/old"
        private const val FIRST = "https://pending.fixture.invalid/first"
        private const val TARGET = "https://pending.fixture.invalid/target?token=fixture%2Bsignature&part=%2Fraw#kept"
        private const val NEXT = "https://pending.fixture.invalid/next"
    }
}

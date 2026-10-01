package com.example.purebrowser.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.ResourceSniffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

data class BrowserPage(
    val url: String = "about:blank", val title: String = "PureBrowser", val progress: Int = 100,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false, val error: String? = null,
)

/** Passive request observation: never replaces WebView networking or exposes a JS/native bridge. */
class BrowserEngine(
    val sniffer: ResourceSniffer,
    private val message: (String) -> Unit,
    initialUrl: String = "about:blank",
    private val onPageChanged: (BrowserPage) -> Unit = {},
    private val onVisited: (String, String) -> Unit = { _, _ -> },
    private val onLink: (String) -> Unit = {},
) {
    private val pageEpoch = AtomicLong(0)
    private val mutablePage = MutableStateFlow(BrowserPage(url = initialUrl))
    val page = mutablePage.asStateFlow()
    val generation: Long get() = pageEpoch.get()
    private var view: WebView? = null
    private var domScanRunning = false

    private fun publish(next: BrowserPage) {
        mutablePage.value = next
        onPageChanged(next)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        view = webView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                publish(mutablePage.value.copy(progress = newProgress))
            }
            override fun onReceivedTitle(v: WebView, title: String?) {
                publish(mutablePage.value.copy(title = title.orEmpty().take(180)))
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                pageEpoch.set(sniffer.beginPage())
                publish(BrowserPage(url = url ?: "about:blank", progress = 0))
            }
            override fun onPageFinished(v: WebView, url: String?) {
                updateNavigation()
                scanMedia()
                if (url == "about:blank") { v.clearHistory(); updateNavigation() }
                else if (mutablePage.value.error == null && BrowserAddress.isWebUrl(url.orEmpty())) onVisited(url!!, mutablePage.value.title)
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (BrowserAddress.isWebUrl(request.url.toString())) return false
                if (request.isForMainFrame) message("暂不支持打开外部应用或此类链接")
                return true
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.method == "GET") {
                    sniffer.observe(pageEpoch.get(), request.url.toString(), Evidence.REQUEST)
                }
                return null
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) publish(mutablePage.value.copy(error = "网页加载失败，请检查网络或网址", progress = 100))
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) publish(mutablePage.value.copy(error = "网页返回 HTTP ${response.statusCode}"))
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) = updateNavigation()
            // SSL errors use WebView's default cancellation. Never silently bypass verification.
        }
        webView.setDownloadListener { url, _, _, mime, length ->
            sniffer.observe(pageEpoch.get(), url, Evidence.DOWNLOAD, mime, length)
            message("发现下载资源，请在资源面板中确认")
        }
        webView.setOnLongClickListener {
            val hit = webView.hitTestResult
            val url = hit.extra.orEmpty()
            if (hit.type in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && BrowserAddress.isWebUrl(url)) {
                onLink(url)
                true
            } else false
        }
        webView.loadUrl(mutablePage.value.url.takeIf(BrowserAddress::isWebUrl) ?: "about:blank")
    }

    fun navigate(input: String) {
        runCatching { BrowserAddress.resolve(input) }
            .onSuccess { view?.loadUrl(it) }
            .onFailure { message(it.message ?: "网址格式不正确") }
    }
    fun back() { view?.let { if (it.canGoBack()) it.goBack() }; updateNavigation() }
    fun forward() { view?.let { if (it.canGoForward()) it.goForward() }; updateNavigation() }
    fun reload() { view?.reload() }
    fun stop() { view?.stopLoading(); publish(mutablePage.value.copy(progress = 100)) }
    fun home() { view?.loadUrl("about:blank") }
    fun pause() { view?.onPause() }
    fun resume() { view?.onResume() }

    private fun updateNavigation() {
        view?.let { publish(mutablePage.value.copy(url = it.url ?: "about:blank", canGoBack = it.canGoBack(), canGoForward = it.canGoForward())) }
    }

    /** Read-only DOM + Resource Timing fallback, with bounded result size and stale-page suppression. */
    fun scanMedia() {
        val current = view ?: return
        if (domScanRunning || !BrowserAddress.isWebUrl(current.url.orEmpty())) return
        val epoch = pageEpoch.get()
        domScanRunning = true
        current.evaluateJavascript(MEDIA_SCAN) { result ->
            domScanRunning = false
            if (epoch != pageEpoch.get() || result.length > 1_048_576) return@evaluateJavascript
            runCatching {
                val decoded = JSONArray("[$result]").getString(0)
                val data = JSONArray(decoded)
                for (i in 0 until minOf(data.length(), 500)) {
                    val item = data.getJSONObject(i)
                    sniffer.observe(epoch, item.optString("url"), if (item.optBoolean("video")) Evidence.DOM else Evidence.TIMING,
                        item.optString("mime").takeIf { it.isNotBlank() }, videoElement = item.optBoolean("video"))
                }
            }
        }
    }

    fun detach(webView: WebView) {
        if (view === webView) {
            pageEpoch.set(sniffer.beginPage())
            view = null
            domScanRunning = false
        }
        webView.stopLoading()
        webView.webChromeClient = null
        webView.destroy()
    }

    private companion object {
        val MEDIA_SCAN = """
            (function() {
              try {
                var out = [];
                document.querySelectorAll('video, video source').forEach(function(v) {
                  if (out.length >= 100) return;
                  var u = v.currentSrc || v.src;
                  if (u && u.length <= 8192) out.push({url: u, mime: v.type || '', video: true});
                });
                performance.getEntriesByType('resource').slice(-400).forEach(function(r) {
                  if (r.name && r.name.length <= 8192) out.push({url: r.name, video: false});
                });
                return JSON.stringify(out);
              } catch(e) { return '[]'; }
            })();
        """.trimIndent()
    }
}

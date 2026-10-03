package com.example.purebrowser.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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
    private var scanToken = 0L
    private var documentTimeOrigin:Double?=null
    private var sameDocumentUpdate=false
    private var navigationStartedMs=0L
    private var scanningActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val scanner = object : Runnable {
        override fun run() { if (scanningActive && view != null) { scanMedia(); handler.postDelayed(this, 2000) } }
    }

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
                invalidateScan()
                sameDocumentUpdate=false
                navigationStartedMs=System.currentTimeMillis()
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
        resume()
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
    fun pause() { scanningActive = false; handler.removeCallbacks(scanner); view?.onPause() }
    fun resume() { view?.onResume(); scanningActive = true; handler.removeCallbacks(scanner); handler.post(scanner) }

    private fun invalidateScan() {
        // Navigation can discard an evaluateJavascript callback. Never wait on the
        // old document's completion, and never let it unlock a newer scan.
        scanToken++
        domScanRunning = false
    }

    private fun updateNavigation() {
        view?.let {
            val nextUrl = it.url ?: "about:blank"
            if (nextUrl != mutablePage.value.url) { invalidateScan(); sameDocumentUpdate=true;navigationStartedMs=System.currentTimeMillis();pageEpoch.set(sniffer.beginPage()) }
            publish(mutablePage.value.copy(url = nextUrl, canGoBack = it.canGoBack(), canGoForward = it.canGoForward())) }
    }

    /** Read-only DOM + Resource Timing fallback, with bounded result size and stale-page suppression. */
    fun scanMedia() {
        val current = view ?: return
        if (domScanRunning || !BrowserAddress.isWebUrl(current.url.orEmpty())) return
        val epoch = pageEpoch.get()
        domScanRunning = true
        val token = ++scanToken
        current.evaluateJavascript(MEDIA_SCAN.replace("__NAV_TIME__",navigationStartedMs.toString())) { result ->
            if (token != scanToken || current !== view) return@evaluateJavascript
            domScanRunning = false
            if (epoch != pageEpoch.get() || result.length > 262_144) return@evaluateJavascript
            runCatching {
                val decoded = JSONArray("[$result]").getString(0)
                val data = JSONArray(decoded)
                val timeOrigin=data.optJSONObject(0)?.optDouble("documentTimeOrigin",Double.NaN)
                if(timeOrigin!=null && timeOrigin.isFinite() && timeOrigin>0 && timeOrigin!=documentTimeOrigin) {
                    val newDocument=documentTimeOrigin!=null || !sameDocumentUpdate
                    documentTimeOrigin=timeOrigin
                    // History-cache entries belong to their restored document; SPA changes still use the floor.
                    if(newDocument && navigationStartedMs!=0L) { sameDocumentUpdate=false;navigationStartedMs=0;handler.post { if(epoch==pageEpoch.get() && current===view)scanMedia() } }
                }
                val playingUrls=mutableSetOf<String>()
                for (i in 0 until minOf(data.length(), 251)) {
                    val item = data.getJSONObject(i)
                    if(item.has("documentTimeOrigin"))continue
                    if(item.optBoolean("video") && item.optBoolean("playing"))playingUrls+=item.optString("url").substringBefore('#')
                    sniffer.observe(epoch, item.optString("url"), if (item.optBoolean("video")) Evidence.DOM else Evidence.TIMING,
                        item.optString("mime").takeIf { it.isNotBlank() }, videoElement = item.optBoolean("video"),
                        title = item.optString("title").takeIf { it.isNotBlank() },
                        frameUrl = item.optString("frame").takeIf(BrowserAddress::isWebUrl),
                        playing = item.optBoolean("playing"), reliableSource = item.optBoolean("video") &&
                            runCatching { com.example.purebrowser.download.RequestPolicy.sameOrigin(item.optString("frame"), current.url.orEmpty()) }.getOrDefault(false))
                }
                sniffer.updatePlayback(epoch,playingUrls)
            }
        }
    }

    fun detach(webView: WebView) {
        if (view === webView) {
            pause(); scanToken++
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
                var out = [{documentTimeOrigin:performance.timeOrigin}], frames = 0, videos = 0, timing = 0, origin = location.origin, budget = 0;
                function add(item) { var cost=JSON.stringify(item).length;if(budget+cost>90000)return;budget+=cost;out.push(item); }
                function walk(w, depth) {
                  if (w.location.origin !== origin) return;
                  var doc = w.document;
                  doc.querySelectorAll('video, video source').forEach(function(v) {
                    if (videos >= 50) return;
                    var video = v.tagName.toLowerCase() === 'video' ? v : v.parentElement;
                    var u = v.currentSrc || v.src;
                    if (u && u.length <= 8192) { videos++;
                      add({url:u, mime:(v.type || '').slice(0,120), video:true,
                        frame:w.location.href, title:(video.title || doc.title || '').slice(0,180),
                        playing:!video.paused && !video.ended && u===video.currentSrc});
                    }
                  });
                  w.performance.getEntriesByType('resource').slice(-200).forEach(function(r) {
                    if (timing < 200 && r.name && r.name.length <= 8192 && r.startTime + w.performance.timeOrigin >= __NAV_TIME__) {
                      timing++; add({url:r.name,video:false});
                    }
                  });
                  if (depth < 2) doc.querySelectorAll('iframe').forEach(function(f) {
                    if (frames >= 8) return; frames++;
                    try { if (f.contentWindow) walk(f.contentWindow,depth+1); } catch(e) {}
                  });
                }
                walk(window,0); return JSON.stringify(out);
              } catch(e) { return '[]'; }
            })();
        """.trimIndent()
    }
}

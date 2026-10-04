package com.example.purebrowser.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.example.purebrowser.browser.sniff.PageSignal
import com.example.purebrowser.browser.sniff.PageSignalBridge
import com.example.purebrowser.browser.sniff.PageSignalScript
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.ResourceSniffer
import com.example.purebrowser.media.rules.DomNode
import com.example.purebrowser.media.rules.RuleSelectorPolicy
import com.example.purebrowser.media.rules.RuleSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

data class BrowserPage(
    val url: String = "about:blank", val title: String = "PureBrowser", val progress: Int = 100,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false, val error: String? = null,
)

/** Passive page observation: never replaces WebView networking; the sole JS bridge is a read-only signal channel. */
class BrowserEngine(
    val sniffer: ResourceSniffer,
    private val message: (String) -> Unit,
    initialUrl: String = "about:blank",
    private val onPageChanged: (BrowserPage) -> Unit = {},
    private val onVisited: (String, String) -> Unit = { _, _ -> },
    private val onLink: (String) -> Unit = {},
    /** Built-in rule set source for the script's capture-endpoint list; null keeps capture off. */
    private val ruleSetProvider: () -> RuleSet? = { null },
) {
    private val pageEpoch = AtomicLong(0)
    private val mutablePage = MutableStateFlow(BrowserPage(url = initialUrl))
    val page = mutablePage.asStateFlow()
    val generation: Long get() = pageEpoch.get()
    @Volatile private var view: WebView? = null
    /** Optional User-Agent override applied at attach; null keeps the platform default. */
    @Volatile var forcedUserAgent: String? = null
    /** User-Agent snapshot taken at attach; safe to read from any sniffer thread. */
    @Volatile var observedUserAgent: String? = null
        private set
    // One resolved, in-memory intent only. Do not publish/persist an unmounted destination.
    private var pendingNavigation: String? = null
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
    /** Wired by the session layer; called on WebView's JS bridge thread with the epoch current at delivery. */
    @Volatile var pageSignalListener: ((epoch: Long, signal: PageSignal) -> Unit)? = null
    private val signalBridge = PageSignalBridge({ pageEpoch.get() }) { epoch, signal -> pageSignalListener?.invoke(epoch, signal) }
    /** Wired by the session layer; invoked on the main thread from onPageFinished with the settled page. */
    @Volatile var pageSettleListener: ((epoch: Long, url: String) -> Unit)? = null

    private fun publish(next: BrowserPage) {
        mutablePage.value = next
        onPageChanged(next)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(webView: WebView) {
        if (view === webView) return // Mount/update must not replay a consumed navigation.
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
        // Optional caller override (diagnostics); applied before the first load so the whole
        // page is negotiated under one identity.
        forcedUserAgent?.takeIf { it.isNotBlank() }?.let { webView.settings.userAgentString = it }
        // Cached on the main thread: sniffing callbacks may run on WebView IO threads.
        observedUserAgent = webView.settings.userAgentString
        webView.addJavascriptInterface(signalBridge, "PbSniffBridge")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                if (v !== view) return
                publish(mutablePage.value.copy(progress = newProgress))
            }
            override fun onReceivedTitle(v: WebView, title: String?) {
                if (v !== view) return
                publish(mutablePage.value.copy(title = title.orEmpty().take(180)))
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (v !== view) return
                invalidateScan()
                sameDocumentUpdate=false
                navigationStartedMs=System.currentTimeMillis()
                pageEpoch.set(sniffer.beginPage())
                v.evaluateJavascript(pageSignalScript(), null)
                publish(BrowserPage(url = url ?: "about:blank", progress = 0))
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (v !== view) return
                updateNavigation()
                scanMedia()
                if (BrowserAddress.isWebUrl(url.orEmpty())) runCatching { pageSettleListener?.invoke(pageEpoch.get(), url!!) }
                if (url == "about:blank") { v.clearHistory(); updateNavigation() }
                else if (mutablePage.value.error == null && BrowserAddress.isWebUrl(url.orEmpty())) onVisited(url!!, mutablePage.value.title)
            }
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (v !== view) return true
                if (BrowserAddress.isWebUrl(request.url.toString())) return false
                if (request.isForMainFrame) message("暂不支持打开外部应用或此类链接")
                return true
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Capture the epoch BEFORE checking the volatile owner: a concurrent teardown
                // must not let an old view's request borrow the next page's generation.
                val epoch = pageEpoch.get()
                if (v === view) observeGetRequest(epoch, request)
                return null
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (v !== view) return
                if (request.isForMainFrame) publish(mutablePage.value.copy(error = "网页加载失败，请检查网络或网址", progress = 100))
            }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (v !== view) return
                if (request.isForMainFrame) publish(mutablePage.value.copy(error = "网页返回 HTTP ${response.statusCode}"))
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                if (v === view) updateNavigation()
            }
            // SSL errors use WebView's default cancellation. Never silently bypass verification.
        }
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            // Service workers bypass the per-WebView client; feed them through the same passive
            // observation path. Android 11 service-worker quirks still need runtime regression coverage; non-blocking.
            if (serviceWorkerOwner !== this) {
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                        // The process-global client has no owner view to check: only the live epoch admits evidence.
                        observeGetRequest(pageEpoch.get(), request)
                        return null
                    }
                })
                serviceWorkerOwner = this
            }
        }
        webView.setDownloadListener { url, _, _, mime, length ->
            if (view !== webView) return@setDownloadListener
            sniffer.observe(pageEpoch.get(), url, Evidence.DOWNLOAD, mime, length)
            message("发现下载资源，请在资源面板中确认")
        }
        webView.setOnLongClickListener {
            if (view !== webView) return@setOnLongClickListener false
            val hit = webView.hitTestResult
            val url = hit.extra.orEmpty()
            if (hit.type in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && BrowserAddress.isWebUrl(url)) {
                onLink(url)
                true
            } else false
        }
        resume()
        val destination = pendingNavigation ?: mutablePage.value.url.takeIf(BrowserAddress::isWebUrl) ?: "about:blank"
        pendingNavigation = null // Consume before loadUrl can synchronously invoke a callback.
        webView.loadUrl(destination)
    }

    fun navigate(input: String) {
        runCatching { BrowserAddress.resolve(input) }
            .onSuccess { loadOrDefer(it) }
            .onFailure { message(it.message ?: "网址格式不正确") }
    }
    private fun loadOrDefer(destination: String) {
        val current = view
        if (current == null) pendingNavigation = destination
        else {
            pendingNavigation = null
            current.loadUrl(destination)
        }
    }

    fun back() { view?.let { if (it.canGoBack()) it.goBack() }; updateNavigation() }
    fun forward() { view?.let { if (it.canGoForward()) it.goForward() }; updateNavigation() }
    fun reload() { view?.reload() }
    fun stop() { view?.stopLoading(); publish(mutablePage.value.copy(progress = 100)) }
    fun home() { loadOrDefer("about:blank") }
    fun pause() { scanningActive = false; handler.removeCallbacks(scanner); view?.onPause() }
    fun resume() { view?.onResume(); scanningActive = true; handler.removeCallbacks(scanner); handler.post(scanner) }

    private fun invalidateScan() {
        // Navigation can discard an evaluateJavascript callback. Never wait on the
        // old document's completion, and never let it unlock a newer scan.
        scanToken++
        domScanRunning = false
    }

    /**
     * Builds the injected observation script per navigation. The capture-endpoint list comes from
     * the loaded rule set (all rules' patterns, deduped by [RuleSet.captureEndpointSources]); a
     * missing or empty set simply leaves response capture off, never blocks injection. The inline
     * harvest switch (T85) follows [RuleSet.wantsInlineData]: only rules that declare
     * inline-extract actions ever turn the bounded harvest on.
     */
    private fun pageSignalScript(): String = PageSignalScript.build(
        runCatching { ruleSetProvider()?.captureEndpointSources() }.getOrNull().orEmpty(),
        inlineHarvest = runCatching { ruleSetProvider()?.wantsInlineData() == true }.getOrDefault(false),
    )

    /** Shared passive GET observation used by both the WebView and service-worker clients. */
    private fun observeGetRequest(epoch: Long, request: WebResourceRequest) {
        if (request.method != "GET") return
        val range=request.requestHeaders.entries.firstOrNull{it.key.equals("Range",true)}?.value
        val hasRange=range!=null && range.length<=80 && Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)").matches(range)
        sniffer.observe(epoch, request.url.toString(), Evidence.REQUEST, requestHasRange=hasRange)
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
                    sniffer.observe(epoch, item.optString("url"), if(item.optBoolean("metadata")) Evidence.METADATA else if (item.optBoolean("video")) Evidence.DOM else Evidence.TIMING,
                        item.optString("mime").takeIf { it.isNotBlank() }, videoElement = item.optBoolean("video") || item.optBoolean("metadata"),
                        title = item.optString("title").takeIf { it.isNotBlank() },
                        frameUrl = item.optString("frame").takeIf(BrowserAddress::isWebUrl),
                        playing = item.optBoolean("playing"), reliableSource = item.optBoolean("video") &&
                            runCatching { com.example.purebrowser.download.RequestPolicy.sameOrigin(item.optString("frame"), current.url.orEmpty()) }.getOrDefault(false))
                }
                sniffer.updatePlayback(epoch,playingUrls)
            }
        }
    }

    /**
     * Bounded read-only selector query for the rules layer. Masking and limits mirror MEDIA_SCAN:
     * capped nodes, attributes and serialized size, stale-page suppression, and an empty answer for
     * any selector outside the shared whitelist. The engine never executes captured strings; it
     * only returns attribute values and text as data.
     */
    fun querySelectorAll(selector: String, onResult: (List<DomNode>) -> Unit) {
        if (!RuleSelectorPolicy.isValid(selector)) { onResult(emptyList()); return }
        val current = view
        if (current == null || !BrowserAddress.isWebUrl(current.url.orEmpty())) { onResult(emptyList()); return }
        val epoch = pageEpoch.get()
        handler.post {
            val owner = view
            if (owner == null || owner !== current || epoch != pageEpoch.get()) { onResult(emptyList()); return@post }
            current.evaluateJavascript(RULE_DOM_QUERY.replace("__SELECTOR__", JSONObject.quote(selector))) { result ->
                if (epoch != pageEpoch.get() || current !== view || result.length > 65_536) {
                    onResult(emptyList()); return@evaluateJavascript
                }
                val nodes = runCatching {
                    val decoded = JSONArray("[$result]").getString(0)
                    val data = JSONArray(decoded)
                    (0 until data.length()).mapNotNull { index ->
                        val item = data.optJSONObject(index) ?: return@mapNotNull null
                        val attrs = LinkedHashMap<String, String>()
                        val rawAttrs = item.optJSONObject("attrs")
                        if (rawAttrs != null) for (key in rawAttrs.keys()) {
                            val value = rawAttrs.optString(key, "")
                            if (value.length in 1..2048) attrs[key.lowercase(Locale.ROOT)] = value
                        }
                        DomNode(attrs, item.optString("text").takeIf { it.isNotEmpty() })
                    }
                }.getOrDefault(emptyList())
                onResult(nodes)
            }
        }
    }

    /** Snapshot callback handed to the rules engine; requests pages through the bounded runner above. */
    suspend fun domSnapshot(selector: String): List<DomNode> = suspendCancellableCoroutine { continuation ->
        querySelectorAll(selector) { nodes -> if (continuation.isActive) continuation.resume(nodes) }
    }

    fun detach(webView: WebView) {
        if (view === webView) {
            // Revoke callback ownership before stopLoading/onPause can deliver final events.
            view = null
            pendingNavigation = null
            scanningActive = false
            handler.removeCallbacks(scanner)
            webView.onPause()
            invalidateScan()
            pageEpoch.set(sniffer.beginPage())
            if (serviceWorkerOwner === this) {
                serviceWorkerOwner = null
                if (Build.VERSION.SDK_INT >= 24) runCatching {
                    ServiceWorkerController.getInstance().setServiceWorkerClient(passiveServiceWorkerClient)
                }
            }
        }
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.setDownloadListener(null)
        webView.setOnLongClickListener(null)
        webView.removeJavascriptInterface("PbSniffBridge")
        webView.destroy()
    }

    private companion object {
        // The service-worker client is process-global: remember which engine installed it so a
        // background tab's teardown cannot unhook the live tab's observation.
        @Volatile private var serviceWorkerOwner: BrowserEngine? = null
        private val passiveServiceWorkerClient = object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
        }
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
                    // An empty attribute resolves to the document URL; that is never the media itself.
                    if (u && u !== w.location.href && u.length <= 8192) { videos++;
                      add({url:u, mime:(v.type || '').slice(0,120), video:true,
                        frame:w.location.href, title:(video.title || doc.title || '').slice(0,180),
                        playing:!video.paused && !video.ended && u===video.currentSrc});
                    }
                  });
                  var metaBudget=0, metaCount=0, nodes=0;
                  function metadata(v,depth) {
                    if(!v || depth>4 || ++nodes>40)return;
                    if(Array.isArray(v)){v.slice(0,20).forEach(function(x){metadata(x,depth+1);});return;}
                    if(typeof v!=='object')return;
                    var type=v['@type'];
                    if(type==='VideoObject' || (Array.isArray(type) && type.indexOf('VideoObject')>=0)) {
                      if(typeof v.contentUrl==='string' && v.contentUrl.length<=8192) {
                        try { var u=new URL(v.contentUrl,w.location.href).href;
                          add({url:u,metadata:true,mime:typeof v.encodingFormat==='string'?v.encodingFormat.slice(0,120):'',
                            title:typeof v.name==='string'?v.name.slice(0,180):doc.title.slice(0,180),frame:w.location.href});
                        } catch(e) {}
                      }
                    }
                    if(v['@graph'])metadata(v['@graph'],depth+1);
                    if(v.mainEntity)metadata(v.mainEntity,depth+1);
                  }
                  doc.querySelectorAll('script[type="application/ld+json"]').forEach(function(s) {
                    var text=s.textContent||'';
                    if(++metaCount>4 || metaBudget+text.length>65536)return;
                    metaBudget+=text.length;try{metadata(JSON.parse(text),0);}catch(e){}
                  });
                  w.performance.getEntriesByType('resource').slice(-200).forEach(function(r) {
                    if (timing < 200 && r.name && r.name.length <= 8192 && r.startTime + w.performance.timeOrigin >= __NAV_TIME__) {
                      timing++; add({url:r.name,video:false,mime:(r.contentType || '').slice(0,120)});
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

        // Read-only selector runner for the rules layer: attribute values are resolved against the
        // document only when they already look like addresses; everything is capped before returning.
        val RULE_DOM_QUERY = """
            (function () {
              try {
                var out = [], budget = 0;
                function normalize(v) {
                  try {
                    if (/^https?:\/\//i.test(v)) return v;
                    if (v.indexOf('//') === 0) return location.protocol + v;
                    if (v.indexOf('/') === 0) return location.origin + v;
                  } catch (e) {}
                  return v;
                }
                var nodes = document.querySelectorAll(__SELECTOR__);
                for (var i = 0; i < nodes.length && i < 24; i++) {
                  var n = nodes[i], attrs = {}, list = n.attributes || [];
                  for (var j = 0; j < list.length && j < 32; j++) {
                    var v = list[j].value || '';
                    if (v.length <= 2048) attrs[list[j].name] = normalize(v);
                  }
                  var item = { attrs: attrs, text: (n.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 256) };
                  var cost = JSON.stringify(item).length;
                  if (budget + cost > 60000) break;
                  budget += cost; out.push(item);
                }
                return JSON.stringify(out);
              } catch (e) { return '[]'; }
            })();
        """.trimIndent()
    }
}

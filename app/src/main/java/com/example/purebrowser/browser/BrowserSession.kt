package com.example.purebrowser.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.example.purebrowser.browser.sniff.PageSignal
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.download.UrlConnectionTransport
import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.ResourceSniffer
import com.example.purebrowser.media.fingerprint.FamilyDetector
import com.example.purebrowser.media.fingerprint.PlayerConfigParser
import com.example.purebrowser.media.fingerprint.PlayerFamily
import com.example.purebrowser.media.rules.RuleSet
import com.example.purebrowser.media.rules.SiteRulesCoordinator
import com.example.purebrowser.media.verify.AutoProbeQueue
import com.example.purebrowser.media.verify.HttpTransportUrlFetcher
import com.example.purebrowser.media.verify.ParseOnDetectionCoordinator
import com.example.purebrowser.media.verify.SessionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/** A live, bounded tab session. Removing from a host does not destroy its browsing history. */
class BrowserSession(
    val recordId: String,
    appContext: Context,
    record: TabRecord,
    message: (String) -> Unit,
    changed: (BrowserPage) -> Unit,
    visited: (String, String) -> Unit,
    link: (String) -> Unit,
) {
    private val app = appContext.applicationContext
    private val context = MutableContextWrapper(app)
    val sniffer = ResourceSniffer()
    val engine = BrowserEngine(sniffer, message, record.url, changed, visited, link, ruleSetProvider = { RuleSet.load(app) })
    private val mounted = AtomicReference<WebView?>()
    private val webView: WebView? get() = mounted.get()
    private val verifyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeQueue = AutoProbeQueue(verifyScope, HttpTransportUrlFetcher(UrlConnectionTransport()))
    private val coordinator = ParseOnDetectionCoordinator(sniffer, probeQueue, verifyScope, ::sessionContext)
    /** Latest harvested player configuration of the current epoch; family hint input for site rules. */
    @Volatile private var playerConfigHarvest: Pair<Long, Pair<String, String>>? = null
    val siteRules = SiteRulesCoordinator(
        sniffer = sniffer,
        scope = verifyScope,
        ruleSet = { withContext(Dispatchers.IO) { RuleSet.load(app) } },
        recentRequests = sniffer::recentRequests,
        domSnapshot = engine::domSnapshot,
        latestPlayerConfig = { playerConfigHarvest?.takeIf { it.first == engine.generation }?.second },
    )

    init {
        coordinator.attach()
        coordinator.start()
        engine.pageSignalListener = ::onPageSignal
        engine.pageSettleListener = ::onPageSettled
        // Warm the built-in rule cache off the main thread so script injection reads it cheaply.
        verifyScope.launch { RuleSet.load(app) }
    }

    /** Built-in site rules run once per page settle; the engine filters non-web addresses before this. */
    private fun onPageSettled(epoch: Long, url: String) {
        siteRules.onPageSettled(epoch, url)
    }

    /** Page-session mirror for bounded auto-verification; read from the WebView, never persisted. */
    private fun sessionContext(): SessionContext = SessionContext(
        pageUrl = engine.page.value.url,
        userAgent = engine.observedUserAgent,
        cookieFor = { url -> runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() },
    )

    /** Read-only page signals become sniffer evidence; config/blob/payload contents are parsed, never executed. */
    private fun onPageSignal(epoch: Long, signal: PageSignal) {
        when (signal) {
            is PageSignal.MediaUrl -> {
                sniffer.observe(epoch, signal.url, Evidence.REQUEST)
                siteRules.onPageSignal(epoch)
            }
            is PageSignal.IframeSrc -> {
                sniffer.observe(epoch, signal.url, Evidence.REQUEST)
                siteRules.onPageSignal(epoch)
            }
            is PageSignal.MseMime -> Unit // mimeType only: no addressable candidate yet
            is PageSignal.PlayerConfig -> {
                val pageUrl = engine.page.value.url
                playerConfigHarvest = epoch to (signal.family to signal.rawJson)
                val family = FamilyDetector.detect(setOf(signal.family))
                PlayerConfigParser.parse(signal.rawJson, family, origin = pageUrl, baseUrl = pageUrl)
                    .forEach { sniffer.observe(epoch, it.url, Evidence.DOM, title = it.qualityLabel) }
            }
            is PageSignal.BlobManifest -> {
                val pageUrl = engine.page.value.url
                HlsPlaylistParser.variantSummaries(signal.content, pageUrl)
                    .forEach { sniffer.observe(epoch, it.url, Evidence.DOM) }
            }
            is PageSignal.ApiPayload -> {
                val pageUrl = engine.page.value.url
                // The rule-scoped endpoint itself surfaces as rule evidence; the bounded body goes
                // through the generic config walk (URLs + quality labels), never executed.
                sniffer.observe(epoch, signal.url, Evidence.RULE)
                PlayerConfigParser.parse(signal.content, PlayerFamily.UNKNOWN, origin = pageUrl, baseUrl = signal.url)
                    .forEach { sniffer.observe(epoch, it.url, Evidence.RULE, title = it.qualityLabel) }
                siteRules.onPageSignal(epoch)
            }
        }
    }

    fun mount(host: FrameLayout) {
        context.baseContext = host.context
        val view = acquireView()
        if (view.parent !== host) {
            (view.parent as? ViewGroup)?.removeView(view)
            host.removeAllViews()
            host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            engine.resume()
        }
    }
    /** Only the existing mounted view; callers must never construct an inactive tab for preview. */
    fun mountedPreviewView(): WebView? = acquireView().takeIf { it.parent != null }

    fun unmount(host: FrameLayout) {
        val view = webView ?: return
        if (view.parent === host) {
            host.removeView(view)
            engine.pause()
            context.baseContext = app
        }
    }
    fun destroy() {
        coordinator.stop()
        verifyScope.cancel()
        engine.pageSignalListener = null
        engine.pageSettleListener = null
        playerConfigHarvest = null
        webView?.let { (it.parent as? ViewGroup)?.removeView(it); engine.detach(it) }
        context.baseContext = app
    }

    private fun acquireView(): WebView =
        mounted.get() ?: synchronized(this) {
            mounted.get() ?: WebView(context).also { view -> mounted.set(view); engine.attach(view) }
        }
}

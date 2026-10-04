package com.example.purebrowser.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.example.purebrowser.browser.sniff.PageSignal
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.download.UrlConnectionTransport
import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.ResourceSniffer
import com.example.purebrowser.media.fingerprint.FamilyDetector
import com.example.purebrowser.media.fingerprint.PlayerConfigParser
import com.example.purebrowser.media.fingerprint.PlayerFamily
import com.example.purebrowser.media.rules.FetchSpec
import com.example.purebrowser.media.rules.RuleDocument
import com.example.purebrowser.media.rules.RuleFetchBudget
import com.example.purebrowser.media.rules.RuleFetchPolicy
import com.example.purebrowser.media.rules.RuleSet
import com.example.purebrowser.media.rules.SessionOptIn
import com.example.purebrowser.media.rules.SessionOptInPreferences
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
import java.io.ByteArrayOutputStream
import java.util.Locale
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
    /** Single-hop opener for the rule fetch channel; policy/budget/cache live in the coordinator. */
    private val ruleFetchOpener = HttpTransportUrlFetcher(UrlConnectionTransport())
    /**
     * ONE merged per-epoch ledger shared by the rule fetch channel and (via the documented hook)
     * the probe queue: rule fetches charge through the coordinator, probe-side requests report
     * through [ruleFetchBudget.noteExternalRequest]. The AutoProbeQueue -> ledger hop is the
     * orchestrator's wiring point (T83 note): media/verify is outside the rules change set, so
     * nothing calls it from there yet — the shared ceiling stays slack until that hop lands.
     */
    private val ruleFetchBudget = RuleFetchBudget()
    /**
     * Per-site login-session opt-in for the rule fetch channel (T86): default UNKNOWN ≡ off, keyed
     * by the current page's registrable domain, persisted through the thin SharedPreferences
     * adapter. The confirmation surface's visible toggle (BrowserPanels → BrowserViewModel) is the
     * only writer; this session only reads it.
     */
    private val sessionOptIn: SessionOptIn = SessionOptInPreferences(app).sessionOptIn()
    /** Latest harvested player configuration of the current epoch; family hint input for site rules. */
    @Volatile private var playerConfigHarvest: Pair<Long, Pair<String, String>>? = null
    val siteRules = SiteRulesCoordinator(
        sniffer = sniffer,
        scope = verifyScope,
        ruleSet = { withContext(Dispatchers.IO) { RuleSet.load(app) } },
        recentRequests = sniffer::recentRequests,
        domSnapshot = engine::domSnapshot,
        latestPlayerConfig = { playerConfigHarvest?.takeIf { it.first == engine.generation }?.second },
        fetcher = { spec, url -> withContext(Dispatchers.IO) { openRuleFetch(spec, url) } },
        fetchBudget = ruleFetchBudget,
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

    /**
     * One bounded open for the rule fetch channel (T83). Headers come exclusively from the
     * RuleFetchPolicy whitelist (UA/Accept/Accept-Language/Referer); Cookie can never pass that
     * filter by design. The session channel (T86) reuses the browser's login cookie ONLY when the
     * per-site opt-in store says OPT_IN for the current page's registrable domain AND the rule's
     * session block covers the target host — anything else goes out anonymously, exactly like
     * v0.1.7. Redirects are NOT followed here — the coordinator re-validates every hop against the
     * rule's host whitelist before re-opening.
     */
    private fun openRuleFetch(spec: FetchSpec, url: String): RuleDocument? = runCatching {
        val base = LinkedHashMap<String, String>()
        engine.observedUserAgent?.takeIf { it.isNotBlank() }?.let { base["User-Agent"] = it }
        base["Accept"] = "application/json, text/plain, */*"
        base["Accept-Language"] = Locale.getDefault().toLanguageTag()
        RequestPolicy.referer(engine.page.value.url, url)?.let { base["Referer"] = it }
        val headers = RuleFetchPolicy.headersFor(base)
        // Session channel: opt-in is read per page from the store (default UNKNOWN ≡ off); the
        // policy additionally requires the rule's session block to cover the target host and the
        // target itself to pass the fetch policy before any cookie leaves the WebView store.
        val rule = RuleSet.load(app).byId(spec.ruleId)
        val sessionCookie = rule?.let {
            RuleFetchPolicy.sessionCookie(it, url, optIn = sessionOptIn.isOptedIn(engine.page.value.url), cookieFor = { target ->
                runCatching { CookieManager.getInstance().getCookie(target) }.getOrNull()
            })
        }
        val requestHeaders = if (sessionCookie != null) headers + ("Cookie" to sessionCookie) else headers
        ruleFetchOpener.open(url, requestHeaders).use { response ->
            if (response.status >= 400) return null
            // A declared length beyond the rule's own bound skips the fetch outright.
            val declared = response.header("Content-Length")?.trim()?.toLongOrNull()
            if (declared != null && declared > spec.maxBytes) return null
            val buffer = ByteArrayOutputStream(spec.maxBytes.coerceIn(64, 262_144))
            val chunk = ByteArray(8192)
            var read = 0
            while (read < spec.maxBytes) {
                val n = response.body.read(chunk, 0, minOf(chunk.size, spec.maxBytes - read))
                if (n < 0) break
                buffer.write(chunk, 0, n)
                read += n
            }
            RuleDocument(
                url = url,
                status = response.status,
                body = buffer.toString("UTF-8"),
                contentType = response.header("Content-Type"),
                location = response.header("Location"),
                credentialUsed = sessionCookie != null,
            )
        }
    }.getOrNull()

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
            is PageSignal.InlineData -> {
                // T85: bounded read-only harvest reaches the rules engine through the coordinator's
                // per-epoch buffer; no DOM writes, no execution, no separate network use.
                siteRules.onInlineData(epoch, signal.kind, signal.content)
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

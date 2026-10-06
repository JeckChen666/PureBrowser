package com.example.purebrowser.media.rules

import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.ResourceSniffer
import com.example.purebrowser.media.fingerprint.PlayerConfigParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale

/**
 * Page-settle coordinator for the built-in site rules. Runs the pure engine once per page epoch
 * with the sniffer's recent request addresses and, when the host layer provides one, the DOM
 * snapshot callback; address-bearing findings become Evidence.RULE candidates. The coordinator
 * itself still reads no response bodies, headers or credentials: rule-scoped payload signals from
 * the session layer only tell it WHEN to re-run, and late signals (captured payloads, newly seen
 * addresses) re-run the evaluation debounced, at most [MAX_SIGNAL_RE_EVALS] extra times per epoch.
 * Player-family markers reuse the T64 config extraction with the rule's family hint.
 *
 * v0.1.8 (T83/T85): the coordinator is also where the rule fetch channel touches the outside
 * world. A `fetcher` injection (null keeps fetch disabled — the v0.1.7 behavior) is wrapped in
 * [controlledFetch]: policy target checks, the merged budget ledger, per-hop redirect re-checks
 * and a per-(rule, fetch, epoch) result cache all live here, while the engine keeps seeing only a
 * pure callback returning data. Inline harvested blocks (T85) arrive through [onInlineData],
 * stay bounded in a per-epoch buffer, and are handed to the engine as read-only data.
 */
class SiteRulesCoordinator(
    private val sniffer: ResourceSniffer,
    private val scope: CoroutineScope,
    private val ruleSet: suspend () -> RuleSet?,
    private val recentRequests: () -> List<String>,
    private val domSnapshot: (suspend (selector: String) -> List<DomNode>)?,
    private val latestPlayerConfig: () -> Pair<String, String>?,
    private val signalDebounceMs: Long = 500,
    /** Raw single-hop fetch adapter owned by the host layer; null = fetch disabled (v0.1.7). */
    private val fetcher: (suspend (spec: FetchSpec, url: String) -> RuleDocument?)? = null,
    /** Shared per-epoch budget ledger (rule fetches + probe requests count together). */
    internal val fetchBudget: RuleFetchBudget = RuleFetchBudget(),
) {
    companion object {
        const val MAX_SIGNAL_RE_EVALS = 2
        const val MAX_FETCH_REDIRECTS = 3
        /** Engine wall budget for fetch-enabled runs; sits above the ledger's 15 s fetch wall. */
        const val FETCH_CHANNEL_BUDGET_MS = 20_000L
        const val MAX_INLINE_ITEMS = 8
        const val MAX_INLINE_TOTAL_CHARS = 1 shl 20
        private const val MAX_FETCH_CACHE = 64
    }

    private data class Matched(val epoch: Long, val url: String, val ruleId: String, val sessionDomain: String?, val imported: Boolean)

    private val guard = Any()
    @Volatile private var lastEpoch = Long.MIN_VALUE
    private var running = false
    private var pending: Pair<Long, String>? = null
    @Volatile private var matched: Matched? = null
    /** Newest settle accepted or queued; the page-URL input for signal-driven re-evaluations. */
    @Volatile private var settled: Pair<Long, String>? = null

    // Signal-path state, guarded by signalGuard; this lock is never held together with guard.
    private val signalGuard = Any()
    private var armedEpoch = Long.MIN_VALUE
    private var signalTick = 0L
    private var loopRunning = false
    private var reEvalEpoch = Long.MIN_VALUE
    private var reEvalCount = 0

    // Fetch-channel state, guarded by fetchGuard; never held across suspend points.
    private val fetchGuard = Any()
    private var fetchCacheEpoch = Long.MIN_VALUE
    private val fetchCache = LinkedHashMap<FetchKey, RuleDocument>()
    /** Last epoch whose fetch channel hit a budget or policy refusal; observability for the 可见 clause. */
    @Volatile private var fetchOverflowEpoch: Long? = null
    // IMPORTED-tier wall clock (T90, D5): half the shared ledger's wall per epoch, tracked from
    // the first evaluation of that epoch. Guarded by fetchGuard like the rest of the channel state.
    private var importedWallEpoch = Long.MIN_VALUE
    private var importedWallStartMs = 0L

    // Inline-data buffer (T85), guarded by inlineGuard; bounded per epoch.
    private val inlineGuard = Any()
    private var inlineEpoch = Long.MIN_VALUE
    private val inlineBuffer = mutableListOf<RuleInlineContent>()
    private var inlineChars = 0

    private data class FetchKey(val ruleId: String, val fetchId: String)

    /**
     * Bounded inline-data intake (T85): only the page script's read-only harvest reaches this, and
     * only while a loaded rule declares inline-consuming actions. Late arrivals also arm the same
     * debounced re-evaluation the other late signals use.
     */
    fun onInlineData(epoch: Long, kind: String, content: String) {
        val parsed = RuleInlineContent.kindOf(kind) ?: return
        if (content.isEmpty() || content.length > RuleEngine.MAX_REGEX_INPUT_CHARS) return
        synchronized(inlineGuard) {
            if (epoch != inlineEpoch) {
                inlineEpoch = epoch
                inlineBuffer.clear()
                inlineChars = 0
            }
            if (inlineBuffer.size >= MAX_INLINE_ITEMS) return
            if (inlineChars + content.length > MAX_INLINE_TOTAL_CHARS) return
            inlineBuffer += RuleInlineContent(parsed, content)
            inlineChars += content.length
        }
        onPageSignal(epoch)
    }

    /** Bounded snapshot of the epoch's inline harvest; newer epochs discard older content. */
    private fun inlineSnapshot(epoch: Long): List<RuleInlineContent> = synchronized(inlineGuard) {
        if (epoch != inlineEpoch) emptyList() else inlineBuffer.toList()
    }

    /** Invoked from the engine's onPageFinished path; cheap enough to call on the main thread. */
    fun onPageSettled(epoch: Long, pageUrl: String) {
        if (!isHttpUrl(pageUrl)) return
        dispatch(epoch, pageUrl)
    }

    /**
     * Late page-signal notification (rule-captured payloads and address reports) for [epoch].
     * Arms a debounced re-evaluation; a newer armed epoch supersedes older signals, and a page
     * older than one already settled never re-runs.
     */
    fun onPageSignal(epoch: Long) {
        val startLoop = synchronized(signalGuard) {
            if (epoch < armedEpoch) return
            armedEpoch = epoch
            signalTick++
            val wasRunning = loopRunning
            loopRunning = true
            !wasRunning
        }
        if (startLoop) scope.launch { signalLoop() }
    }

    /** Serializes settle and re-evaluation runs through the same single-flight queue. */
    private fun dispatch(epoch: Long, pageUrl: String) {
        val launch = synchronized(guard) {
            when {
                epoch < lastEpoch -> null // a settled page older than one already evaluated
                running -> {
                    // Only the newest settled page is worth a queued re-run.
                    if (pending == null || epoch >= pending!!.first) pending = epoch to pageUrl
                    settled = epoch to pageUrl
                    null
                }
                else -> { lastEpoch = epoch; running = true; settled = epoch to pageUrl; epoch to pageUrl }
            }
        } ?: return
        scope.launch {
            try {
                var job: Pair<Long, String>? = launch
                while (job != null) {
                    runFor(job.first, job.second)
                    job = synchronized(guard) {
                        val next = pending
                        pending = null
                        if (next != null && next.first >= lastEpoch) {
                            lastEpoch = next.first
                            next
                        } else null
                    }
                }
            } finally {
                synchronized(guard) { running = false }
            }
        }
    }

    /**
     * Debounce worker: sleeps, then re-evaluates once when fresh signals arrived for an epoch that
     * has settled and is still the newest. Stops when the window is quiet, the epoch was superseded
     * or its extra-evaluation budget is spent; the next signal restarts it.
     */
    private suspend fun signalLoop() {
        // 0 means "the batch that started this loop is still unevaluated": the triggering signals
        // must survive their own debounce window instead of being consumed at start.
        var consumed = 0L
        while (true) {
            delay(signalDebounceMs)
            var target: Pair<Long, String>? = null
            synchronized(signalGuard) {
                val epoch = armedEpoch
                val settledNow = settled
                val viable = signalTick != consumed && // fresh signals arrived since the last consumed batch
                    settledNow != null && settledNow.first == epoch && // the signal's page settled
                    epoch >= lastEpoch && // not superseded by a newer settle
                    (reEvalEpoch != epoch || reEvalCount < MAX_SIGNAL_RE_EVALS) // per-epoch budget left
                if (viable) {
                    consumed = signalTick
                    if (reEvalEpoch != epoch) { reEvalEpoch = epoch; reEvalCount = 0 }
                    reEvalCount++
                    target = settledNow
                } else {
                    loopRunning = false
                }
            }
            if (target == null) return
            dispatch(target!!.first, target!!.second)
        }
    }

    /** Stale epochs are dropped by the sniffer itself; observations are idempotent otherwise. */
    private suspend fun runFor(epoch: Long, pageUrl: String) {
        val set = ruleSet() ?: return
        fetchBudget.beginUserAction(epoch)
        importedWallStart(epoch)
        dropFetchCache(epoch)
        val inline = inlineSnapshot(epoch)
        val channel: (suspend (FetchSpec, RenderedUrl) -> RuleDocument?)? = fetcher?.let { io ->
            { spec: FetchSpec, url: RenderedUrl -> controlledFetch(set, epoch, spec, url, io) }
        }
        val findings = RuleEngine(set).evaluate(
            pageUrl,
            recentRequests(),
            domSnapshot,
            budgetMs = if (channel == null) RuleEngine.DEFAULT_BUDGET_MS else FETCH_CHANNEL_BUDGET_MS,
            fetcher = channel,
            inlineData = if (set.wantsInlineData()) { { inline } } else null,
        )
        findings.firstOrNull()?.let { first ->
            // T86 可见 clause input: the registrable domain a confirmation page may offer a session
            // toggle for — non-null only when the matched rule DECLARES a session block whose hosts
            // sit inside the page's own registrable domain (SessionTogglePolicy.coveredDomain).
            val sessionDomain = set.byId(first.ruleId)?.session
                ?.let { SessionTogglePolicy.coveredDomain(it, pageUrl) }
            matched = Matched(epoch, pageUrl, first.ruleId, sessionDomain, set.byId(first.ruleId)?.source == RuleSource.IMPORTED)
        }
        val familiesDone = mutableSetOf<String>()
        for (finding in findings) {
            val url = finding.url
            if (url == null) {
                // Family markers: rerun the harvested player configuration under the rule's family hint.
                val rule = set.byId(finding.ruleId) ?: continue
                for (action in rule.actions.filterIsInstance<RuleAction.PlayerConfigExtract>()) {
                    if (!familiesDone.add("${finding.ruleId}:${action.family}")) continue
                    val harvest = latestPlayerConfig() ?: continue
                    PlayerConfigParser.parse(harvest.second, action.family, origin = pageUrl, baseUrl = pageUrl)
                        .forEach { sniffer.observe(epoch, it.url, Evidence.RULE, title = it.qualityLabel) }
                }
            } else {
                sniffer.observe(epoch, url, Evidence.RULE, title = finding.title)
            }
            // T87 wiring: a format-bearing finding folds its whole format set into the PRIMARY
            // candidate's variants (kind per manifest/direct hint) on top of the per-address
            // observations, which remain the fallback surface (the finding's own address is already
            // observed above, so it is excluded here).
            if (finding.formats.isNotEmpty()) {
                val target = url ?: finding.formats.first().url
                sniffer.attachRuleVariants(epoch, target, finding.kindHint, FormatSelector.toVariantSummaries(finding.formats))
                finding.formats.filter { it.url != target }.forEach { format ->
                    sniffer.observe(epoch, format.url, Evidence.RULE)
                }
            }
        }
    }

    /**
     * The one place rule fetches touch the network adapter. Order of checks per hop: policy target
     * (https + host whitelist + no IP/private/link-local), budget acquire, adapter call, byte
     * charge, then the same checks again on every redirect hop. A refusal or exhausted budget
     * returns null — the engine downgrades by skipping the rule's fetch actions — and marks the
     * epoch in [fetchOverflowEpoch] for observability. Session credentials (T86) attach only in the
     * host adapter, after the per-site opt-in and [RuleFetchPolicy.sessionCookie] agree; the hop
     * learns about it through [RuleDocument.credentialUsed], which both charges the ledger's
     * credential accounting and makes [FetchDeny.CROSS_ORIGIN_WITH_CREDENTIALS] refuse cross-origin
     * redirects for that fetch.
     */
    private suspend fun controlledFetch(
        set: RuleSet,
        epoch: Long,
        spec: FetchSpec,
        url: RenderedUrl,
        io: suspend (FetchSpec, String) -> RuleDocument?,
    ): RuleDocument? {
        val key = FetchKey(spec.ruleId, spec.id)
        cachedFetch(epoch, key)?.let { return it }
        val rule = set.byId(spec.ruleId) ?: return null
        // T90 D5 IMPORTED tier switch: imported rules fetch with halved maxBytes and a halved
        // wall clock, and their redirect hops are same-origin only — decided purely, applied here.
        val imported = rule.source == RuleSource.IMPORTED
        val effectiveSpec = if (imported) spec.copy(maxBytes = ImportedTier.effectiveMaxBytes(spec.maxBytes)) else spec
        if (imported && importedWallElapsedMs(epoch) >= ImportedTier.wallBudgetMs(fetchBudget.maxWallMs)) {
            fetchOverflowEpoch = epoch
            return null
        }
        val hosts = rule.match.hostsPattern
        // The fetch-host extension is a built-in-tier privilege; importer rules load without it.
        val extraHosts = if (imported) emptySet() else spec.hosts.toSet()
        var current = url.value
        var hop = 0
        while (hop++ <= MAX_FETCH_REDIRECTS) {
            if (!RuleFetchPolicy.target(current, hosts, extraHosts).allowed) {
                fetchOverflowEpoch = epoch
                return null
            }
            if (!fetchBudget.tryAcquireFetch(epoch)) {
                fetchOverflowEpoch = epoch // clear overflow → skip-rule downgrade
                return null
            }
            val document = runCatching { io(effectiveSpec, current) }.getOrNull() ?: return null
            fetchBudget.chargeBytes(epoch, document.body.length.toLong())
            // T86: a credential-bearing hop charges the ledger and is held to the stricter
            // with-credentials redirect rule (cross-origin with a session cookie refuses).
            if (document.credentialUsed) fetchBudget.noteCredentialUse(epoch)
            if (document.status in 300..399 && document.location != null) {
                val next = RuleFetchPolicy.redirectHop(current, document.location, hosts, credentialUsed = document.credentialUsed)
                if (!next.allowed || (imported && next.resolvedUrl?.let { ImportedTier.redirectAllowed(current, it) } == false)) {
                    fetchOverflowEpoch = epoch
                    return null
                }
                current = next.resolvedUrl ?: return null
                continue
            }
            if (document.status !in 200..299) return null
            cacheFetch(epoch, key, document)
            return document
        }
        return null
    }

    /** Opens (or continues) the imported-tier wall window for [epoch]; returns elapsed ms. */
    private fun importedWallStart(epoch: Long) {
        synchronized(fetchGuard) {
            if (importedWallEpoch != epoch) {
                importedWallEpoch = epoch
                importedWallStartMs = System.currentTimeMillis()
            }
        }
    }

    private fun importedWallElapsedMs(epoch: Long): Long = synchronized(fetchGuard) {
        if (importedWallEpoch != epoch) {
            importedWallEpoch = epoch
            importedWallStartMs = System.currentTimeMillis()
            0L
        } else System.currentTimeMillis() - importedWallStartMs
    }

    private fun cachedFetch(epoch: Long, key: FetchKey): RuleDocument? = synchronized(fetchGuard) {
        if (epoch == fetchCacheEpoch) fetchCache[key] else null
    }

    private fun cacheFetch(epoch: Long, key: FetchKey, document: RuleDocument) {
        synchronized(fetchGuard) {
            if (epoch != fetchCacheEpoch) {
                fetchCacheEpoch = epoch
                fetchCache.clear()
            }
            fetchCache[key] = document
            while (fetchCache.size > MAX_FETCH_CACHE) {
                val oldest = fetchCache.keys.firstOrNull() ?: break
                fetchCache.remove(oldest)
            }
        }
    }

    private fun dropFetchCache(epoch: Long) {
        synchronized(fetchGuard) { if (epoch != fetchCacheEpoch) { fetchCacheEpoch = epoch; fetchCache.clear() } }
    }

    /** True when [epoch]'s fetch channel was refused by policy or budget (R4 可见 clause input). */
    fun fetchOverflowed(epoch: Long): Boolean = fetchOverflowEpoch == epoch

    /** Source label for the analyze entry; non-null only while the same page generation is live. */
    fun matchedNote(generation: Long?, pageUrl: String?): String? {
        val hit = matched ?: return null
        if (generation == null || pageUrl == null || hit.epoch != generation || hit.url != pageUrl) return null
        return if (hit.imported) "导入规则" else "站点规则"
    }

    /**
     * Registrable domain whose login session the confirmation surface may offer (T86 可见 clause):
     * non-null only while the matched page generation is live AND the matched rule declares a
     * session block covering the page's own registrable domain. Carries no cookie, host list or
     * credential value — the caller pairs it with the per-site opt-in store.
     */
    fun matchedSessionDomain(generation: Long?, pageUrl: String?): String? {
        val hit = matched ?: return null
        if (generation == null || pageUrl == null || hit.epoch != generation || hit.url != pageUrl) return null
        return hit.sessionDomain
    }

    private fun isHttpUrl(url: String): Boolean = runCatching {
        URI(url).scheme?.lowercase(Locale.ROOT) in setOf("http", "https")
    }.getOrDefault(false)
}

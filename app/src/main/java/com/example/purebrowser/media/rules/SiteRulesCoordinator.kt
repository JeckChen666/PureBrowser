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
 */
class SiteRulesCoordinator(
    private val sniffer: ResourceSniffer,
    private val scope: CoroutineScope,
    private val ruleSet: suspend () -> RuleSet?,
    private val recentRequests: () -> List<String>,
    private val domSnapshot: (suspend (selector: String) -> List<DomNode>)?,
    private val latestPlayerConfig: () -> Pair<String, String>?,
    private val signalDebounceMs: Long = 500,
) {
    companion object {
        const val MAX_SIGNAL_RE_EVALS = 2
    }

    private data class Matched(val epoch: Long, val url: String, val ruleId: String)

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
        val findings = RuleEngine(set).evaluate(pageUrl, recentRequests(), domSnapshot)
        findings.firstOrNull()?.let { first -> matched = Matched(epoch, pageUrl, first.ruleId) }
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
        }
    }

    /** Source label for the analyze entry; non-null only while the same page generation is live. */
    fun matchedNote(generation: Long?, pageUrl: String?): String? {
        val hit = matched ?: return null
        if (generation == null || pageUrl == null || hit.epoch != generation || hit.url != pageUrl) return null
        return "站点规则"
    }

    private fun isHttpUrl(url: String): Boolean = runCatching {
        URI(url).scheme?.lowercase(Locale.ROOT) in setOf("http", "https")
    }.getOrDefault(false)
}

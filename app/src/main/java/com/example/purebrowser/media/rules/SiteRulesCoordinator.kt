package com.example.purebrowser.media.rules

import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.ResourceSniffer
import com.example.purebrowser.media.fingerprint.PlayerConfigParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale

/**
 * Page-settle coordinator for the built-in site rules. Runs the pure engine once per page epoch
 * with the sniffer's recent request addresses and, when the host layer provides one, the DOM
 * snapshot callback; address-bearing findings become Evidence.RULE candidates. No response bodies,
 * headers or credentials are ever read. Player-family markers reuse the T64 config extraction with
 * the rule's family hint over the page's latest harvested configuration.
 */
class SiteRulesCoordinator(
    private val sniffer: ResourceSniffer,
    private val scope: CoroutineScope,
    private val ruleSet: suspend () -> RuleSet?,
    private val recentRequests: () -> List<String>,
    private val domSnapshot: (suspend (selector: String) -> List<DomNode>)?,
    private val latestPlayerConfig: () -> Pair<String, String>?,
) {
    private data class Matched(val epoch: Long, val url: String, val ruleId: String)

    private val guard = Any()
    private var lastEpoch = Long.MIN_VALUE
    private var running = false
    private var pending: Pair<Long, String>? = null
    @Volatile private var matched: Matched? = null

    /** Invoked from the engine's onPageFinished path; cheap enough to call on the main thread. */
    fun onPageSettled(epoch: Long, pageUrl: String) {
        if (!isHttpUrl(pageUrl)) return
        val launch = synchronized(guard) {
            when {
                epoch < lastEpoch -> null // a settled page older than one already evaluated
                running -> {
                    // Only the newest settled page is worth a queued re-run.
                    if (pending == null || epoch >= pending!!.first) pending = epoch to pageUrl
                    null
                }
                else -> { lastEpoch = epoch; running = true; epoch to pageUrl }
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

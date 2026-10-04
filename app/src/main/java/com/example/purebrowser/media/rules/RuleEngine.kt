package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.fingerprint.DiscoveredSource
import java.net.URI
import java.util.Locale

/** One extracted clue. A null [url] marks a rule hit without an address; markers never become candidates. */
data class RuleFinding(
    val url: String?,
    val title: String?,
    val kindHint: MediaKind,
    val evidenceNote: String,
    val ruleId: String,
)

/**
 * Pure-Kotlin rule evaluator. Its only inputs are addresses and a DOM snapshot the engine requests
 * through a callback; captured values are treated as data and are never executed, decoded as code
 * or re-parsed as patterns. Evaluation runs under a wall-clock budget and bails out when it is gone.
 */
class RuleEngine(private val ruleSet: RuleSet, private val now: () -> Long = System::nanoTime) {
    companion object {
        const val DEFAULT_BUDGET_MS = 50L
        const val MAX_FINDINGS = 32
        const val MAX_REQUESTS = 256
        const val MAX_DOM_QUERIES = 8
        const val MAX_NODES_PER_QUERY = 24
        const val MAX_HINT_URLS_PER_RULE = 8
        const val MAX_URL_LENGTH = 8192
    }

    suspend fun evaluate(
        pageUrl: String,
        requestUrls: List<String>,
        domSnapshot: (suspend (selector: String) -> List<DomNode>)?,
        budgetMs: Long = DEFAULT_BUDGET_MS,
    ): List<RuleFinding> {
        val deadline = now() + budgetMs * 1_000_000L
        val pageHost = runCatching { URI(pageUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        val requests = requestUrls.asSequence()
            .filter { it.length in 1..MAX_URL_LENGTH }
            .distinct()
            .take(MAX_REQUESTS)
            .toList()
        val findings = mutableListOf<RuleFinding>()
        var domQueries = 0
        for (rule in ruleSet.rules) {
            if (findings.size >= MAX_FINDINGS) break
            if (now() > deadline) break // budget exhausted: bail out with what was already collected
            val hostsOk = when {
                rule.match.hostsPattern == null -> true
                pageHost == null -> false
                else -> rule.match.hostsPattern.containsMatchIn(pageHost)
            }
            if (!hostsOk) continue
            val pathPattern = rule.match.pathPattern
            val matchedRequests = if (pathPattern == null) emptyList() else requests.filter {
                runCatching { pathPattern.containsMatchIn(it) }.getOrDefault(false)
            }
            if (pathPattern != null && matchedRequests.isEmpty()) continue
            val before = findings.size
            for (action in rule.actions) {
                if (findings.size >= MAX_FINDINGS || now() > deadline) break
                when (action) {
                    is RuleAction.ManifestHint -> matchedRequests.take(MAX_HINT_URLS_PER_RULE).forEach { request ->
                        if (findings.size < MAX_FINDINGS)
                            findings += RuleFinding(request, null, action.kindHint, note(rule), rule.id)
                    }
                    is RuleAction.DomExtract -> {
                        if (domSnapshot == null || domQueries >= MAX_DOM_QUERIES) continue
                        domQueries++
                        val nodes = runCatching { domSnapshot(action.selector) }.getOrDefault(emptyList())
                        if (now() > deadline) break
                        val titleNode = if (action.titleSelector != null && domQueries < MAX_DOM_QUERIES) {
                            domQueries++
                            runCatching { domSnapshot(action.titleSelector) }.getOrDefault(emptyList()).firstOrNull()
                        } else null
                        val title = titleNode?.text?.trim()?.takeIf { it.isNotEmpty() }?.take(120)
                        nodes.take(MAX_NODES_PER_QUERY).forEach { node ->
                            if (findings.size >= MAX_FINDINGS) return@forEach
                            val url = sanitizeUrl(node.attributes[action.attribute] ?: return@forEach) ?: return@forEach
                            findings += RuleFinding(url, title, DiscoveredSource.kindFor(url), note(rule), rule.id)
                        }
                    }
                    is RuleAction.PlayerConfigExtract ->
                        findings += RuleFinding(null, null, MediaKind.UNKNOWN, note(rule, "播放器配置家族 ${action.family.name.lowercase(Locale.ROOT)}"), rule.id)
                }
            }
            if (findings.size == before && findings.size < MAX_FINDINGS) {
                // A matched rule always leaves a trace; the marker carries no address and no capture.
                findings += RuleFinding(null, null, MediaKind.UNKNOWN, note(rule, "命中页面特征"), rule.id)
            }
        }
        return findings.toList()
    }

    private fun note(rule: SiteRule, detail: String? = null): String = buildString {
        append("站点规则 ").append(rule.id)
        if (detail != null) append("：").append(detail)
        rule.note?.let { append("：").append(it.take(120)) }
    }.take(240)

    /** http(s) only, no whitespace or control bytes; the snapshot runner has already resolved relative values. */
    private fun sanitizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.length !in 1..MAX_URL_LENGTH) return null
        if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return url
    }
}

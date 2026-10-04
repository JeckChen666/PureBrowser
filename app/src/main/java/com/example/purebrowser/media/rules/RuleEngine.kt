package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.dash.MpdCatalog
import com.example.purebrowser.media.fingerprint.DiscoveredSource
import com.example.purebrowser.download.hls.HlsPlaylistParser
import java.net.URI
import java.util.Locale

/**
 * A URL rendered from a rule's fetch template. Pure data the engine hands to its fetch callback;
 * the host layer owns every policy decision and all IO (the engine never sees a transport type).
 */
@JvmInline
value class RenderedUrl(val value: String)

/** Bounded inline text harvested from the page (T85): JSON, JSON-LD or a player-config script. */
data class RuleInlineContent(val kind: Kind, val content: String) {
    enum class Kind { JSON, LDJSON, SCRIPT }

    companion object {
        fun kindOf(raw: String): Kind? = when (raw) {
            "json" -> Kind.JSON
            "ldjson" -> Kind.LDJSON
            "script" -> Kind.SCRIPT
            else -> null
        }
    }
}

/** One extracted clue. A null [url] marks a rule hit without an address; markers never become candidates. */
data class RuleFinding(
    val url: String?,
    val title: String?,
    val kindHint: MediaKind,
    val evidenceNote: String,
    val ruleId: String,
    /** Structured formats attached by the T84 extraction actions; empty for address-only findings. */
    val formats: List<FormatEntry> = emptyList(),
)

/**
 * Pure-Kotlin rule evaluator. Its only inputs are addresses, a DOM snapshot the engine requests
 * through a callback, inline text the host layer harvested, and — for schema-v3 fetch rules — a
 * fetch callback the COORDINATOR provides; captured values are treated as data and are never
 * executed, decoded as code or re-parsed as patterns. Evaluation runs under a wall-clock budget
 * and bails out when it is gone. The engine stays zero-IO by construction: no transport, stream
 * or socket type appears here, and a test guards that.
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
        const val MAX_FORMATS_PER_FINDING = 32
        const val MAX_INLINE_SOURCES = 8
        /** Regex inputs above this many characters are skipped: bounded input is the run-time half
         * of the catastrophic-backtracking guard, on top of the load-time pattern checks. */
        const val MAX_REGEX_INPUT_CHARS = 262_144
        const val MAX_REGEX_MATCH_ITERATIONS = 64
        private val HEIGHT_HINT = Regex("(?:^|[^0-9])([0-9]{3,4})p?(?:$|[^0-9])")
        private const val OG_META_SELECTOR = "meta[property], meta[name]"
    }

    suspend fun evaluate(
        pageUrl: String,
        requestUrls: List<String>,
        domSnapshot: (suspend (selector: String) -> List<DomNode>)? = null,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        fetcher: (suspend (spec: FetchSpec, url: RenderedUrl) -> RuleDocument?)? = null,
        inlineData: (suspend () -> List<RuleInlineContent>)? = null,
    ): List<RuleFinding> {
        val deadline = now() + budgetMs * 1_000_000L
        val pageHost = runCatching { URI(pageUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
        val requests = requestUrls.asSequence()
            .filter { it.length in 1..MAX_URL_LENGTH }
            .distinct()
            .take(MAX_REQUESTS)
            .toList()
        val inline = inlineData?.let { provider ->
            runCatching { provider() }.getOrDefault(emptyList()).filter { it.content.isNotEmpty() }.take(MAX_INLINE_SOURCES)
        } ?: emptyList()
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
            val pathMatch = if (pathPattern == null) null else requests.asSequence()
                .mapNotNull { runCatching { pathPattern.find(it) }.getOrNull() }
                .firstOrNull()
            val matchedRequests = if (pathPattern == null) emptyList() else requests.filter {
                runCatching { pathPattern.containsMatchIn(it) }.getOrDefault(false)
            }
            if (pathPattern != null && matchedRequests.isEmpty()) continue
            // Schema-v3 fetch channel: render each template against this page's bindings and hand
            // it to the coordinator callback; a null answer downgrades to no document, never a crash.
            val fetched = if (rule.fetch.isNotEmpty() && fetcher != null && now() <= deadline) {
                fetchDocuments(rule, pageUrl, pathMatch, fetcher, deadline)
            } else emptyMap()
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
                    is RuleAction.JsonExtract -> extractJson(rule, action, fetched, inline)?.let { findings += it }
                    is RuleAction.RegexExtract -> extractRegex(rule, action, fetched, inline)?.let { findings += it }
                    is RuleAction.OgMeta -> extractOgMeta(rule, action, domSnapshot)?.let {
                        domQueries += it.second
                        if (now() <= deadline) findings += it.first
                    }
                    is RuleAction.ParseManifest -> findings += parseManifest(rule, action, fetched, inline, pageUrl)
                }
            }
            if (findings.size == before && findings.size < MAX_FINDINGS) {
                // A matched rule always leaves a trace; the marker carries no address and no capture.
                findings += RuleFinding(null, null, MediaKind.UNKNOWN, note(rule, "命中页面特征"), rule.id)
            }
        }
        return findings.toList()
    }

    /** Renders and issues one rule's declared fetches; policy and budgets live in the callback. */
    private suspend fun fetchDocuments(
        rule: SiteRule,
        pageUrl: String,
        pathMatch: MatchResult?,
        fetcher: suspend (FetchSpec, RenderedUrl) -> RuleDocument?,
        deadline: Long,
    ): Map<String, RuleDocument> {
        val out = LinkedHashMap<String, RuleDocument>()
        for (spec in rule.fetch) {
            if (now() > deadline) break
            val rendered = RuleTemplatePolicy.render(spec.urlTemplate, pageUrl, pathMatch) ?: continue
            val url = sanitizeUrl(rendered) ?: continue
            if (!url.startsWith("https://", ignoreCase = true)) continue // templates must render https
            val document = runCatching { fetcher(spec, RenderedUrl(url)) }.getOrNull() ?: continue
            if (document.status in 200..299 && document.body.isNotEmpty()) out[spec.id] = document
        }
        return out
    }

    /** jsonExtract over fetched JSON documents and inline JSON/JSON-LD blocks (T84). */
    private fun extractJson(
        rule: SiteRule,
        action: RuleAction.JsonExtract,
        fetched: Map<String, RuleDocument>,
        inline: List<RuleInlineContent>,
    ): RuleFinding? {
        val jsonSources = fetched.values.map { it.body } +
            inline.filter { it.kind != RuleInlineContent.Kind.SCRIPT }.map { it.content }
        val formats = mutableListOf<FormatEntry>()
        for (body in jsonSources) {
            if (formats.size >= MAX_FORMATS_PER_FINDING) break
            val root = RuleSet.parseJsonValue(body) ?: continue
            for (pointer in action.pointers) {
                if (formats.size >= MAX_FORMATS_PER_FINDING) break
                val text = navigate(root, pointer) as? String ?: continue
                val url = sanitizeUrl(text) ?: continue
                formatFromUrl(url, heightHint(pointer))?.let { formats += it }
            }
        }
        if (formats.isEmpty()) return null
        val first = formats.first()
        return RuleFinding(first.url, null, first.kind, note(rule, "结构化提取 ${formats.size} 项"), rule.id, formats.take(MAX_FORMATS_PER_FINDING))
    }

    /** regexExtract over fetched document bodies and every inline block (T84). */
    private fun extractRegex(
        rule: SiteRule,
        action: RuleAction.RegexExtract,
        fetched: Map<String, RuleDocument>,
        inline: List<RuleInlineContent>,
    ): RuleFinding? {
        val regex = runCatching { Regex(action.pattern) }.getOrNull() ?: return null
        val formats = mutableListOf<FormatEntry>()
        val sources = fetched.values.map { it.body } + inline.map { it.content }
        for (body in sources) {
            if (formats.size >= MAX_FORMATS_PER_FINDING) break
            if (body.isEmpty() || body.length > MAX_REGEX_INPUT_CHARS) continue // bounded input half of the guard
            for (match in regex.findAll(body).take(MAX_REGEX_MATCH_ITERATIONS)) {
                if (formats.size >= MAX_FORMATS_PER_FINDING) break
                val values = if (action.groupNames.isEmpty()) {
                    listOf(match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: match.value)
                } else {
                    action.groupNames.mapNotNull { name -> match.groups[name]?.value }
                }
                for (value in values) {
                    if (formats.size >= MAX_FORMATS_PER_FINDING) break
                    val url = sanitizeUrl(value) ?: continue
                    formatFromUrl(url, null)?.let { formats += it }
                }
            }
        }
        if (formats.isEmpty()) return null
        val first = formats.first()
        return RuleFinding(first.url, null, first.kind, note(rule, "正则提取 ${formats.size} 项"), rule.id, formats.take(MAX_FORMATS_PER_FINDING))
    }

    /** ogMeta reads the page's meta tags through the same bounded DOM snapshot channel (T84). */
    private suspend fun extractOgMeta(
        rule: SiteRule,
        action: RuleAction.OgMeta,
        domSnapshot: (suspend (selector: String) -> List<DomNode>)?,
    ): Pair<RuleFinding, Int>? {
        if (domSnapshot == null) return null
        val metas = runCatching { domSnapshot(OG_META_SELECTOR) }.getOrDefault(emptyList())
        val wanted = action.properties.map { it.lowercase(Locale.ROOT) }.toSet()
        val formats = mutableListOf<FormatEntry>()
        for (node in metas.take(MAX_NODES_PER_QUERY * 2)) {
            if (formats.size >= MAX_FORMATS_PER_FINDING) break
            val key = node.attributes["property"] ?: node.attributes["name"] ?: continue
            if (key.lowercase(Locale.ROOT) !in wanted) continue
            val raw = node.attributes["content"] ?: continue
            val url = sanitizeUrl(raw) ?: continue
            formatFromUrl(url, null)?.let { formats += it }
        }
        if (formats.isEmpty()) return null
        val first = formats.first()
        return RuleFinding(first.url, null, first.kind, note(rule, "元信息提取 ${formats.size} 项"), rule.id, formats.take(MAX_FORMATS_PER_FINDING)) to 1
    }

    /** parseManifest runs the existing HLS/DASH parsers over fetched or inline manifest text (T84). */
    private fun parseManifest(
        rule: SiteRule,
        action: RuleAction.ParseManifest,
        fetched: Map<String, RuleDocument>,
        inline: List<RuleInlineContent>,
        pageUrl: String,
    ): List<RuleFinding> {
        val findings = mutableListOf<RuleFinding>()
        val sources: List<Pair<String, String>> = fetched.values.map { it.url to it.body } +
            inline.map { pageUrl to it.content }
        for ((sourceUrl, body) in sources) {
            if (findings.size >= MAX_FINDINGS) break
            val base = sanitizeUrl(sourceUrl) ?: pageUrl
            if (action.kind == MediaKind.HLS) {
                val variants = runCatching { HlsPlaylistParser.variantSummaries(body, base) }.getOrDefault(emptyList())
                if (variants.isEmpty()) continue
                val formats = variants.take(MAX_FORMATS_PER_FINDING).map { variant ->
                    FormatEntry(
                        url = variant.url,
                        height = variant.height,
                        ext = "m3u8",
                        tbr = variant.bandwidth?.let { it / 1000 },
                        kind = MediaKind.HLS,
                    )
                }
                findings += RuleFinding(base, null, MediaKind.HLS, note(rule, "HLS 清单 ${formats.size} 档"), rule.id, formats)
            } else {
                // DASH stays display-only at this tier: the cataloger publishes no per-representation
                // addresses, so the manifest itself is the addressable entry.
                val representations = runCatching { MpdCatalog.representations(body, base) }.getOrDefault(emptyList())
                if (representations.isEmpty()) continue
                val top = representations.first()
                val entry = FormatEntry(base, top.height, null, "mpd", top.bandwidth?.let { it / 1000 }, MediaKind.DASH)
                findings += RuleFinding(base, null, MediaKind.DASH, note(rule, "DASH 清单 ${representations.size} 路"), rule.id, listOf(entry))
            }
        }
        return findings
    }

    /** Restricted dot-path navigation over parsed JSON values; any miss yields null. */
    private fun navigate(root: Any?, pointer: String): Any? {
        var node = root
        for (segment in pointer.split('.')) {
            val name = segment.substringBefore('[')
            val index = segment.substringAfter('[', "").removeSuffix("]").toIntOrNull()
            if (name.isEmpty()) return null
            node = (node as? Map<*, *>)?.get(name) ?: return null
            if (index != null) node = (node as? List<*>)?.getOrNull(index) ?: return null
        }
        return node
    }

    /** Builds a format from an extracted address; the kind and container come from the URL itself. */
    private fun formatFromUrl(url: String, heightHint: Int?): FormatEntry? {
        val kind = DiscoveredSource.kindFor(url)
        val ext = runCatching {
            val leaf = URI(url).path.orEmpty().substringAfterLast('/')
            leaf.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.lowercase(Locale.ROOT)
        }.getOrNull()
        return FormatEntry(url = url, height = heightHint, ext = ext, tbr = null, kind = kind)
    }

    /** Reads a plausible pixel height out of a pointer's last segment (`files[720]`, `h1080p`). */
    private fun heightHint(pointer: String): Int? {
        val leaf = pointer.substringAfterLast('.')
        HEIGHT_HINT.find(leaf)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 144..4320 }?.let { return it }
        HEIGHT_HINT.find(pointer.substringBeforeLast('.'))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 144..4320 }
        return null
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

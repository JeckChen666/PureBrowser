package com.example.purebrowser.media.rules

import android.content.Context
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.fingerprint.PlayerFamily
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Built-in, versioned site rules (T71 frozen decisions): rules are shipped data, interpreter
 * actions are an enumerated whitelist, no credential capture, no remote delivery. The first batch
 * was transcribed clean-room from 参考闭源竞品逆向分析 material — matching faces, DOM selectors and
 * player-family hints only; entries needing header/token capture or sites with a dedicated adapter
 * were excluded at the source.
 *
 * v0.1.8 (T82) extends the same discipline with a schema-v3 tier: controlled `fetch` declarations,
 * `session` blocks validated against the match face's registrable domains, template placeholders
 * bound by the path face's groups, and four extraction actions whose semantics follow 开源 yt-dlp
 * 行为规格转写 (behavior spec only — no upstream source text, identifiers or data).
 */

/** Selector syntax the injected query runner accepts; the loader and the runner enforce the same whitelist. */
object RuleSelectorPolicy {
    const val MAX_LENGTH = 256
    private val allowed = Regex("[A-Za-z0-9_.#,:\\[\\]()=\"'^$*|~+>@ -]{1,256}")
    fun isValid(selector: String): Boolean = selector.length <= MAX_LENGTH && allowed.matches(selector)
}

/** Restricted dot-path grammar for jsonExtract pointers: `a.b[0].c`, no expressions (T84). */
object JsonPointerPolicy {
    const val MAX_LENGTH = 64
    private val segment = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\[[0-9]{1,3}])?")
    private val full = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\[[0-9]{1,3}])?(\\.[A-Za-z_][A-Za-z0-9_-]*(\\[[0-9]{1,3}])?)*")
    fun isValid(pointer: String): Boolean =
        pointer.length in 1..MAX_LENGTH && full.matches(pointer) && pointer.split('.').all { segment.matches(it) }
}

/** OpenGraph/Twitter property-name grammar for ogMeta (T84). */
object OgPropertyPolicy {
    private val allowed = Regex("[A-Za-z][A-Za-z0-9_.:-]{0,63}")
    fun isValid(property: String): Boolean = allowed.matches(property)
}

/**
 * Load-time catastrophic-backtracking guard for regexExtract patterns (T84): the pattern must
 * compile AND carry no quantifier applied to a group that itself contains an unbounded quantifier
 * (the classic nested-loop blowup shape). Run time adds bounded input on top (see RuleEngine).
 * Behavior-spec level guard, transcribed from 开源 yt-dlp 行为规格转写 discipline, not upstream code.
 */
object RegexGuard {
    fun isSafe(pattern: String): Boolean = compiles(pattern) && noNestedUnboundedQuantifier(pattern)

    fun compiles(pattern: String): Boolean = runCatching { Regex(pattern) }.isSuccess

    fun noNestedUnboundedQuantifier(pattern: String): Boolean {
        var i = 0
        while (i < pattern.length) {
            when {
                pattern[i] == '\\' -> i += 2
                pattern[i] == '[' -> { val close = pattern.indexOf(']', i + 1); i = if (close < 0) pattern.length else close }
                pattern[i] == '(' -> {
                    var depth = 0
                    var j = i
                    var close = -1
                    while (j < pattern.length) {
                        when {
                            pattern[j] == '\\' -> j++
                            pattern[j] == '[' -> { val c = pattern.indexOf(']', j + 1); j = if (c < 0) pattern.length else c }
                            pattern[j] == '(' -> depth++
                            pattern[j] == ')' -> { depth--; if (depth == 0) { close = j; break } }
                        }
                        j++
                    }
                    if (close < 0) return true // malformed tail: the compile check already rejected it
                    val inner = pattern.substring(i + 1, close)
                    if (quantifierAt(pattern, close + 1) && hasUnboundedQuantifier(inner)) return false
                    i = close
                }
            }
            i++
        }
        return true
    }

    /** True when a top-level `*`, `+` or open-ended `{n,}` quantifier appears outside character classes. */
    private fun hasUnboundedQuantifier(source: String): Boolean {
        var i = 0
        while (i < source.length) {
            when {
                source[i] == '\\' -> i += 2
                source[i] == '[' -> { val close = source.indexOf(']', i + 1); i = if (close < 0) source.length else close }
                source[i] == '*' || source[i] == '+' -> return true
                source[i] == '{' -> {
                    val close = source.indexOf('}', i)
                    if (close < 0) return true
                    if (source.substring(i + 1, close).endsWith(",")) return true // {n,} is open-ended
                    i = close
                }
            }
            i++
        }
        return false
    }

    private fun quantifierAt(pattern: String, index: Int): Boolean {
        val c = pattern.getOrNull(index) ?: return false
        if (c == '*' || c == '+') return true
        if (c != '{') return false
        val close = pattern.indexOf('}', index)
        if (close < 0) return true
        return pattern.substring(index + 1, close).endsWith(",")
    }
}

/**
 * Matching faces of one rule: [hosts] is matched against the page host, [path] against the URLs of
 * requests observed for the page (the main document included). Both faces are optional but at least
 * one is required, and both must pass whenever present. Patterns compile exactly once, at load.
 */
class RuleMatch internal constructor(
    val hosts: String?,
    val path: String?,
    internal val hostsPattern: Regex?,
    internal val pathPattern: Regex?,
) {
    override fun toString() = "RuleMatch(hosts=${hosts != null}, path=${path != null})"
}

/** Enumerated action whitelist; the engine treats every captured value as data and never executes it. */
sealed interface RuleAction {
    /** Reads [attribute] from the elements selected by [selector]; [titleSelector] optionally names a title element. */
    data class DomExtract(val selector: String, val attribute: String, val titleSelector: String? = null) : RuleAction

    /** Confirms the page uses a known player family; the coordinator reuses the T64 config extraction with this hint. */
    data class PlayerConfigExtract(val family: PlayerFamily) : RuleAction

    /** Labels the request endpoints a rule matched with a manifest kind hint. */
    data class ManifestHint(val kindHint: MediaKind) : RuleAction

    /** Restricted dot-pointer read into inline JSON/JSON-LD blocks or a fetched JSON document (T84). */
    data class JsonExtract(val pointers: List<String>) : RuleAction

    /** Bounded regex capture over inline script text or fetched document bodies (T84). */
    data class RegexExtract(val pattern: String, val groupNames: List<String>) : RuleAction

    /** OpenGraph/Twitter Card meta read through the DOM snapshot (T84). */
    data class OgMeta(val properties: List<String>) : RuleAction

    /** Runs the existing HLS/DASH manifest parsers over fetched or inline manifest text (T84). */
    data class ParseManifest(val kind: MediaKind) : RuleAction
}

/** One element of a DOM snapshot the engine requested through its callback; values are capped, read-only data. */
data class DomNode(val attributes: Map<String, String> = emptyMap(), val text: String? = null)

/**
 * One controlled fetch declared by a schema-v3 rule (T82). `method` stays GET-only for now,
 * [urlTemplate] must render to an https target, and [maxBytes] is required with no default — the
 * loader rejects the rule when any of these fail. [ruleId] is filled by the loader from the owning
 * rule and can never be set from JSON; the coordinator keys its fetch cache on it.
 */
data class FetchSpec(
    val id: String,
    val method: String,
    val urlTemplate: String,
    val maxBytes: Int,
    val ruleId: String,
)

/**
 * Declared login-session reuse (T82/T86): every host must sit inside the registrable domains of
 * the rule's match-hosts face (validated at load) and the user's per-site opt-in is required at
 * run time before any cookie is pulled. The spec itself never carries a cookie value.
 */
data class SessionSpec(val hosts: List<String>)

/** One completed rule fetch as pure data: bounded body text plus redirect-hop metadata (T83). */
data class RuleDocument(
    val url: String,
    val status: Int,
    val body: String,
    val contentType: String? = null,
    /** Redirect target when [status] is a 3xx; the coordinator validates every hop before following. */
    val location: String? = null,
    /**
     * True when the adapter attached a login-session cookie to this hop (T86): the coordinator
     * re-checks redirect targets against the stricter with-credentials rule and charges the shared
     * ledger's credential accounting. Pure metadata — no credential value ever travels here.
     */
    val credentialUsed: Boolean = false,
)

/**
 * Template placeholder whitelist (T82): only `{{pageUrl}}`, `{{m1}}..{{m9}}` and `{{match.<name>}}`
 * are legal, with no expressions and no other variables. The loader validates every placeholder
 * against the groups the rule's path face actually declares.
 */
object RuleTemplatePolicy {
    const val MAX_LENGTH = 512
    const val MAX_PLACEHOLDERS = 4
    private val placeholder = Regex("\\{\\{([^{}]{1,64})\\}\\}")
    private val matchName = Regex("match\\.([A-Za-z][A-Za-z0-9_]*)")

    /** True when every placeholder is legal and bound by [pathPattern]'s capturing groups. */
    fun validatePlaceholders(template: String, pathPattern: Regex?, path: String?): Boolean {
        if (template.length > MAX_LENGTH) return false
        val found = placeholder.findAll(template).toList()
        if (found.size > MAX_PLACEHOLDERS) return false
        // Any stray braces left after removing valid placeholders mean an unclosed/unknown token.
        val remainder = template.replace(placeholder, "")
        if (remainder.contains("{{") || remainder.contains("}}")) return false
        val groupCount = if (pathPattern == null || path == null) 0 else RegexSourceScan.capturingGroupCount(path)
        val names = path?.let { RegexSourceScan.namedGroupNames(it) } ?: emptySet()
        for (match in found) {
            val token = match.groupValues[1].trim()
            when {
                token == "pageUrl" -> Unit
                token.length == 2 && token[0] == 'm' && token[1] in '1'..'9' ->
                    if (token.substring(1).toInt() > groupCount) return false
                else -> {
                    val name = matchName.matchEntire(token)?.groupValues?.get(1) ?: return false
                    if (name !in names) return false
                }
            }
        }
        return true
    }

    /**
     * Renders a template against the page URL and one path match; unresolvable group bindings yield
     * null (the engine then skips that fetch). Values are inserted verbatim — the rendered URL must
     * still pass the fetch policy's target checks in the coordinator.
     */
    fun render(template: String, pageUrl: String, pathMatch: MatchResult?): String? {
        var out = template
        val found = placeholder.findAll(template).toList()
        if (found.size > MAX_PLACEHOLDERS) return null
        for (match in found) {
            val token = match.groupValues[1].trim()
            val value = when {
                token == "pageUrl" -> pageUrl
                token.length == 2 && token[0] == 'm' && token[1] in '1'..'9' ->
                    pathMatch?.groupValues?.getOrNull(token.substring(1).toInt())?.takeIf { it.isNotEmpty() }
                else -> {
                    val name = matchName.matchEntire(token)?.groupValues?.get(1) ?: return null
                    // Named-group lookup THROWS on unknown names in Kotlin; unbound means null.
                    runCatching { pathMatch?.groups?.get(name)?.value }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
            } ?: return null
            out = out.replace(match.value, value)
        }
        return out.takeIf { !it.contains("{{") && !it.contains("}}") }
    }
}

class SiteRule(
    val id: String,
    val version: Int,
    val match: RuleMatch,
    val actions: List<RuleAction>,
    val note: String?,
    /** Regex sources of response-capture endpoints; empty for DOM-only rules. */
    val captureEndpoints: List<String> = emptyList(),
    /** Controlled fetch declarations (schema v3+); empty keeps v0.1.7 behavior. */
    val fetch: List<FetchSpec> = emptyList(),
    /** Declared login-session reuse (schema v3+); null keeps v0.1.7 behavior. */
    val session: SessionSpec? = null,
) {
    // Never dump patterns or notes into diagnostics.
    override fun toString() = "SiteRule(id=$id, version=$version, actions=${actions.size}, captures=${captureEndpoints.size}, fetch=${fetch.size})"
}

class RuleSet(val rules: List<SiteRule>, val version: Int) {
    fun byId(id: String): SiteRule? = rules.firstOrNull { it.id == id }

    /**
     * Deduped capture-endpoint pattern sources across every rule, capped process-wide. Built into
     * the injected page script; only shipped data flows in here, so one page's list is the same
     * read-only superset for all pages.
     */
    fun captureEndpointSources(): List<String> = rules.flatMap { it.captureEndpoints }.distinct().take(MAX_CAPTURE_ENDPOINTS)

    /**
     * True when any loaded rule declares an action that consumes inline page data (T85): the host
     * layer only then asks the injected script to harvest inline JSON/JSON-LD/player-config blocks.
     */
    fun wantsInlineData(): Boolean = rules.any { rule ->
        rule.actions.any { it is RuleAction.JsonExtract || it is RuleAction.RegexExtract || it is RuleAction.ParseManifest }
    }

    companion object {
        val EMPTY = RuleSet(emptyList(), 0)
        const val MAX_RULES = 64
        const val MAX_PATTERN_LENGTH = 512
        const val MAX_ACTIONS_PER_RULE = 4
        const val MAX_FILE_BYTES = 64 * 1024
        const val ASSET_PATH = "rules/site-rules.json"
        const val MAX_CAPTURE_ENDPOINTS_PER_RULE = 4
        const val MAX_CAPTURE_ENDPOINTS = 32
        /**
         * Document version that unlocks the fetch/session/extract schema and the raised caps
         * (D8): documents below it keep the exact v0.1.7 caps and ignore the new keys, which is
         * the load-time half of the backward-compatibility gate.
         */
        const val SCHEMA_FETCH_VERSION = 3
        const val MAX_RULES_V3 = 2048
        const val MAX_FILE_BYTES_V3 = 512 * 1024
        /** Aggregate compiled-regex ceiling across a whole document (match faces, captures, extracts). */
        const val MAX_COMPILED_REGEXES = 4096
        const val MAX_FETCHES_PER_RULE = 4
        const val MAX_FETCH_BYTES = 262_144
        const val MAX_SESSION_HOSTS = 4
        const val MAX_JSON_POINTERS = 8
        const val MAX_OG_PROPERTIES = 8
        const val MAX_REGEX_GROUP_NAMES = 4
        const val MAX_REGEX_PATTERN_LENGTH = 256
        private val cached = AtomicReference<RuleSet?>()

        /** Built-in data: read once per process; a missing, oversized or malformed asset yields an empty set. */
        fun load(context: Context): RuleSet {
            cached.get()?.let { return it }
            val loaded = runCatching {
                val bytes = context.assets.open(ASSET_PATH).use { stream ->
                    val buffer = ByteArray(MAX_FILE_BYTES_V3 + 1)
                    var read = 0
                    while (true) {
                        if (read >= buffer.size) return@use buffer // oversized marker
                        val n = stream.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
                if (bytes.size > MAX_FILE_BYTES_V3) null else parse(bytes.toString(Charsets.UTF_8))
            }.getOrNull() ?: EMPTY
            cached.compareAndSet(null, loaded)
            return cached.get() ?: loaded
        }

        /** Lenient, dependency-free parse kept JVM-testable: any malformed entry is skipped, never fatal. */
        fun parse(text: String): RuleSet {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_FILE_BYTES_V3) return EMPTY
            val root = runCatching { RuleJsonParser(trimmed).parseDocument() }.getOrNull() as? Map<String, Any?>
                ?: return EMPTY
            val version = (root["version"] as? Double)?.toInt() ?: 0
            // Schema ≤ 2 documents keep the exact v0.1.7 file-size gate and rule cap.
            if (version < SCHEMA_FETCH_VERSION && trimmed.length > MAX_FILE_BYTES) return EMPTY
            val rawRules = root["rules"] as? List<*> ?: return RuleSet(emptyList(), version)
            val modern = version >= SCHEMA_FETCH_VERSION
            val maxRules = if (modern) MAX_RULES_V3 else MAX_RULES
            val regexBudget = RegexBudget()
            val out = mutableListOf<SiteRule>()
            val ids = mutableSetOf<String>()
            for (raw in rawRules) {
                if (out.size >= maxRules) break
                val entry = raw as? Map<String, Any?> ?: continue
                val rule = parseRule(entry, modern, regexBudget) ?: continue
                if (ids.add(rule.id)) out += rule
            }
            return RuleSet(out.toList(), version)
        }

        /** Aggregate compiled-regex counter; a rule that would cross the ceiling is rejected whole. */
        private class RegexBudget { var used = 0 }

        private fun parseRule(entry: Map<String, Any?>, modern: Boolean, budget: RegexBudget): SiteRule? {
            val id = (entry["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
            val version = (entry["version"] as? Double)?.toInt() ?: 1
            val matchRaw = entry["match"] as? Map<String, Any?> ?: return null
            val hosts = patternText(matchRaw["hosts"])
            val path = patternText(matchRaw["path"])
            if (hosts == null && path == null) return null
            // Patterns compile exactly once here; an invalid expression rejects the whole rule.
            val hostsPattern = if (hosts != null) {
                runCatching { Regex(hosts, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
            } else null
            val pathPattern = if (path != null) {
                runCatching { Regex(path, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
            } else null
            budget.used += (if (hostsPattern != null) 1 else 0) + (if (pathPattern != null) 1 else 0)
            val actionsRaw = entry["actions"] as? List<*> ?: return null
            val actions = mutableListOf<RuleAction>()
            for (rawAction in actionsRaw) {
                if (actions.size >= MAX_ACTIONS_PER_RULE) break
                val action = parseAction(rawAction as? Map<String, Any?> ?: continue, modern) ?: continue
                if (action is RuleAction.RegexExtract) budget.used += 1 // pattern compiled inside the safety check
                actions += action
            }
            if (actions.isEmpty()) return null
            // Same load-time safety caps as the match faces: bounded count/length, compiled here;
            // an invalid expression rejects the whole rule exactly like a match face.
            val captureEndpoints = parseCaptureEndpoints(entry["captureEndpoints"]) ?: return null
            budget.used += captureEndpoints.size
            if (budget.used > MAX_COMPILED_REGEXES) return null
            val note = (entry["note"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(180)
            val fetch = if (modern) parseFetch(entry, id, hostsPattern, pathPattern, path) ?: return null else emptyList()
            // A DECLARED session that fails validation rejects the rule; an absent block is v0.1.7.
            val session = if (modern && entry.containsKey("session")) parseSession(entry, hostsPattern, hosts) ?: return null else null
            return SiteRule(id, version, RuleMatch(hosts, path, hostsPattern, pathPattern), actions.toList(), note, captureEndpoints, fetch, session)
        }

        /** Null means an endpoint pattern failed to compile: the rule is rejected, never fatal. */
        private fun parseCaptureEndpoints(value: Any?): List<String>? {
            val raw = value as? List<*> ?: return emptyList()
            val out = mutableListOf<String>()
            for (item in raw) {
                if (out.size >= MAX_CAPTURE_ENDPOINTS_PER_RULE) break
                val pattern = patternText(item) ?: continue
                if (runCatching { Regex(pattern) }.isFailure) return null
                out += pattern
            }
            return out
        }

        private fun parseAction(entry: Map<String, Any?>, modern: Boolean): RuleAction? = when (entry["type"] as? String) {
            "domExtract" -> {
                val selector = (entry["selector"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                val attribute = (entry["attribute"] as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= 64 }?.lowercase(Locale.ROOT) ?: return null
                val titleSelector = (entry["titleSelector"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                if (!RuleSelectorPolicy.isValid(selector)) return null
                if (titleSelector != null && !RuleSelectorPolicy.isValid(titleSelector)) return null
                RuleAction.DomExtract(selector, attribute, titleSelector)
            }
            "playerConfig" -> familyOf(entry["family"] as? String)?.let { RuleAction.PlayerConfigExtract(it) }
            "manifestHint" -> kindOf(entry["kind"] as? String)?.let { RuleAction.ManifestHint(it) }
            // Schema v3 extraction actions; lower-version documents never see these parsed.
            "jsonExtract" -> if (!modern) null else {
                val pointers = stringList(entry["pointers"], MAX_JSON_POINTERS) { JsonPointerPolicy.isValid(it) } ?: return null
                if (pointers.isEmpty()) null else RuleAction.JsonExtract(pointers)
            }
            "regexExtract" -> if (!modern) null else {
                val pattern = (entry["pattern"] as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= MAX_REGEX_PATTERN_LENGTH } ?: return null
                if (!RegexGuard.isSafe(pattern)) return null
                // groupNames is optional: absent means whole-match extraction; a present list must
                // name real groups and stay within the cap, or the action is skipped.
                val names = when (val raw = entry["groupNames"]) {
                    null -> emptyList()
                    else -> stringList(raw, MAX_REGEX_GROUP_NAMES) { RegexSourceScan.namedGroupNames(pattern).contains(it) } ?: return null
                }
                RuleAction.RegexExtract(pattern, names)
            }
            "ogMeta" -> if (!modern) null else {
                val properties = stringList(entry["properties"], MAX_OG_PROPERTIES) { OgPropertyPolicy.isValid(it) } ?: return null
                if (properties.isEmpty()) null else RuleAction.OgMeta(properties)
            }
            "parseManifest" -> if (!modern) null else
                kindOf(entry["kind"] as? String)?.takeIf { it == MediaKind.HLS || it == MediaKind.DASH }?.let { RuleAction.ParseManifest(it) }
            else -> null // Unknown action types are skipped, never fatal.
        }

        /**
         * Fetch declarations (T82). Null rejects the whole rule: a rule that fetches must keep its
         * host-whitelist face, every spec needs a unique id, GET method, an https template whose
         * placeholders the path face binds, and a required bounded maxBytes.
         */
        private fun parseFetch(
            entry: Map<String, Any?>,
            ruleId: String,
            hostsPattern: Regex?,
            pathPattern: Regex?,
            path: String?,
        ): List<FetchSpec>? {
            val raw = entry["fetch"] as? List<*> ?: return emptyList()
            if (hostsPattern == null) return null // no host whitelist: no fetch targets are provable
            val out = mutableListOf<FetchSpec>()
            val ids = mutableSetOf<String>()
            for (item in raw) {
                if (out.size >= MAX_FETCHES_PER_RULE) break
                val spec = item as? Map<String, Any?> ?: continue
                val id = (spec["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
                if (!ids.add(id)) return null // ambiguous cache keys
                val method = ((spec["method"] as? String)?.trim()?.uppercase(Locale.ROOT)).takeIf { !it.isNullOrEmpty() } ?: "GET"
                if (method != "GET") return null // GET-only for now (D7 keeps writes off by default)
                val template = (spec["url"] as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= RuleTemplatePolicy.MAX_LENGTH } ?: return null
                if (!template.startsWith("https://", ignoreCase = true)) return null
                if (!RuleTemplatePolicy.validatePlaceholders(template, pathPattern, path)) return null
                val maxBytes = (spec["maxBytes"] as? Double)?.toInt() ?: return null // required, no default
                if (maxBytes <= 0 || maxBytes > MAX_FETCH_BYTES) return null
                out += FetchSpec(id, method, template, maxBytes, ruleId)
            }
            return out.toList()
        }

        /**
         * Session declarations (T82): plain host literals only, each one matched by the hosts face
         * AND inside one of its registrable domains (eTLD+1 containment, D6). Null rejects the rule.
         */
        private fun parseSession(entry: Map<String, Any?>, hostsPattern: Regex?, hosts: String?): SessionSpec? {
            val raw = entry["session"] as? Map<String, Any?> ?: return null
            val hostsRaw = raw["hosts"] as? List<*> ?: return null
            if (hostsRaw.isEmpty() || hostsRaw.size > MAX_SESSION_HOSTS) return null
            val sessionHosts = hostsRaw.map { (it as? String)?.trim()?.lowercase(Locale.ROOT) }
            if (sessionHosts.any { it == null }) return null
            val allowed = hosts?.let { RegistrableDomains.fromHostPattern(it) } ?: emptySet()
            for (host in sessionHosts.filterNotNull()) {
                if (!RegistrableDomains.isPlainHost(host)) return null
                if (hostsPattern == null || !hostsPattern.containsMatchIn(host)) return null
                if (RegistrableDomains.registrableDomain(host) == null || RegistrableDomains.registrableDomain(host) !in allowed) return null
            }
            return SessionSpec(sessionHosts.filterNotNull())
        }

        /** Bounded string-list reader; null when any entry fails its check (the action is then skipped). */
        private fun stringList(value: Any?, cap: Int, check: (String) -> Boolean): List<String>? {
            val raw = value as? List<*> ?: return null
            val out = mutableListOf<String>()
            for (item in raw) {
                if (out.size >= cap) return null // over the cap is malformed, not silently truncated
                val text = (item as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                if (!check(text)) return null
                out += text
            }
            return out
        }

        /**
         * Lenient JSON value reader for the engine's extraction actions (T84): maps/lists/strings/
         * numbers only, never executed. Deeper nesting and longer strings than the schema parser
         * allows, because fetched and inline payloads are bigger than rule metadata.
         */
        internal fun parseJsonValue(text: String): Any? = runCatching {
            RuleJsonParser(text, maxDepth = 16, maxStringChars = 65_536).parseDocument()
        }.getOrNull()

        private fun patternText(value: Any?): String? =
            (value as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_PATTERN_LENGTH }

        private fun familyOf(value: String?): PlayerFamily? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "flashvars" -> PlayerFamily.FLASHVARS
            "kvs" -> PlayerFamily.KVS
            "html5player" -> PlayerFamily.HTML5PLAYER
            "xplayer" -> PlayerFamily.XPLAYER
            "stream_data" -> PlayerFamily.STREAM_DATA
            else -> null
        }

        private fun kindOf(value: String?): MediaKind? = when (value?.trim()?.lowercase(Locale.ROOT)) {
            "hls" -> MediaKind.HLS
            "dash" -> MediaKind.DASH
            "file" -> MediaKind.FILE
            "unknown" -> MediaKind.UNKNOWN
            else -> null
        }

        /** Minimal nested-capable JSON reader with depth, string and strictness caps; mirrors PageSignalParser style. */
        private object Missing
        private class RuleJsonParser(
            private val text: String,
            private val maxDepth: Int = 8,
            private val maxStringChars: Int = 8192,
        ) {
            private var i = 0
            private var depth = 0

            fun parseDocument(): Any? {
                skipSpace()
                val value = parseValue()
                if (value === Missing) throw IllegalArgumentException("bad json")
                skipSpace()
                if (i != text.length) throw IllegalArgumentException("trailing content")
                return value
            }

            private fun skipSpace() { while (i < text.length && text[i].isWhitespace()) i++ }

            private fun parseValue(): Any? {
                if (++depth > maxDepth) throw IllegalArgumentException("nesting limit")
                try {
                    if (i >= text.length) return Missing
                    return when (text[i]) {
                        '{' -> parseObject()
                        '[' -> parseArray()
                        '"' -> parseString() ?: Missing
                        't' -> literal("true", true)
                        'f' -> literal("false", false)
                        'n' -> literal("null", null)
                        else -> if (text[i] == '-' || text[i].isDigit()) number() else Missing
                    }
                } finally { depth-- }
            }

            private fun parseObject(): Any? {
                i++
                val out = LinkedHashMap<String, Any?>()
                while (true) {
                    skipSpace()
                    if (i >= text.length) return Missing
                    when (text[i]) {
                        '}' -> { i++; return out }
                        ',' -> { i++; continue }
                        '"' -> {}
                        else -> return Missing
                    }
                    val key = parseString() ?: return Missing
                    skipSpace()
                    if (i >= text.length || text[i] != ':') return Missing
                    i++
                    skipSpace()
                    if (i >= text.length) return Missing
                    val value = parseValue()
                    if (value === Missing) return Missing
                    out[key] = value
                    skipSpace()
                    if (i < text.length && text[i] == ',') { i++; continue }
                    if (i < text.length && text[i] == '}') { i++; return out }
                    return Missing
                }
            }

            private fun parseArray(): Any? {
                i++
                val out = mutableListOf<Any?>()
                while (true) {
                    skipSpace()
                    if (i >= text.length) return Missing
                    if (text[i] == ']') { i++; return out }
                    if (text[i] == ',') { i++; continue }
                    val value = parseValue()
                    if (value === Missing) return Missing
                    // Bounded element count; the version-tiered file-size gate bounds total memory.
                    // Raised from 256 in v0.1.8 because schema-v3 documents may carry 2048 rules.
                    if (out.size < 4096) out += value
                    skipSpace()
                    if (i < text.length && text[i] == ',') { i++; continue }
                    if (i < text.length && text[i] == ']') { i++; return out }
                    return Missing
                }
            }

            private fun parseString(): String? {
                if (i >= text.length || text[i] != '"') return null
                i++
                val out = StringBuilder()
                while (i < text.length) {
                    val c = text[i]
                    when {
                        c == '"' -> { i++; return out.toString() }
                        c == '\\' -> {
                            i++
                            if (i >= text.length) return null
                            when (val escape = text[i]) {
                                '"', '\\', '/' -> out.append(escape)
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (i + 4 >= text.length) return null
                                    val code = text.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                                    out.append(code.toChar())
                                    i += 4
                                }
                                else -> return null
                            }
                            i++
                        }
                        c < ' ' -> return null
                        else -> { out.append(c); i++ }
                    }
                    if (out.length > maxStringChars) return null
                }
                return null
            }

            private fun number(): Any? {
                val start = i
                while (i < text.length && "+-0123456789.eE".indexOf(text[i]) >= 0) i++
                if (i == start) return Missing
                return text.substring(start, i).toDoubleOrNull() ?: Missing
            }

            private fun literal(word: String, value: Any?): Any? =
                if (text.regionMatches(i, word, 0, word.length)) { i += word.length; value } else Missing
        }
    }
}

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
 */

/** Selector syntax the injected query runner accepts; the loader and the runner enforce the same whitelist. */
object RuleSelectorPolicy {
    const val MAX_LENGTH = 256
    private val allowed = Regex("[A-Za-z0-9_.#,:\\[\\]()=\"'^$*|~+>@ -]{1,256}")
    fun isValid(selector: String): Boolean = selector.length <= MAX_LENGTH && allowed.matches(selector)
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
}

/** One element of a DOM snapshot the engine requested through its callback; values are capped, read-only data. */
data class DomNode(val attributes: Map<String, String> = emptyMap(), val text: String? = null)

class SiteRule(
    val id: String,
    val version: Int,
    val match: RuleMatch,
    val actions: List<RuleAction>,
    val note: String?,
    /** Regex sources of response-capture endpoints; empty for DOM-only rules. */
    val captureEndpoints: List<String> = emptyList(),
) {
    // Never dump patterns or notes into diagnostics.
    override fun toString() = "SiteRule(id=$id, version=$version, actions=${actions.size}, captures=${captureEndpoints.size})"
}

class RuleSet(val rules: List<SiteRule>, val version: Int) {
    fun byId(id: String): SiteRule? = rules.firstOrNull { it.id == id }

    /**
     * Deduped capture-endpoint pattern sources across every rule, capped process-wide. Built into
     * the injected page script; only shipped data flows in here, so one page's list is the same
     * read-only superset for all pages.
     */
    fun captureEndpointSources(): List<String> = rules.flatMap { it.captureEndpoints }.distinct().take(MAX_CAPTURE_ENDPOINTS)

    companion object {
        val EMPTY = RuleSet(emptyList(), 0)
        const val MAX_RULES = 64
        const val MAX_PATTERN_LENGTH = 512
        const val MAX_ACTIONS_PER_RULE = 4
        const val MAX_FILE_BYTES = 64 * 1024
        const val ASSET_PATH = "rules/site-rules.json"
        const val MAX_CAPTURE_ENDPOINTS_PER_RULE = 4
        const val MAX_CAPTURE_ENDPOINTS = 32
        private val cached = AtomicReference<RuleSet?>()

        /** Built-in data: read once per process; a missing, oversized or malformed asset yields an empty set. */
        fun load(context: Context): RuleSet {
            cached.get()?.let { return it }
            val loaded = runCatching {
                val bytes = context.assets.open(ASSET_PATH).use { stream ->
                    val buffer = ByteArray(MAX_FILE_BYTES + 1)
                    var read = 0
                    while (true) {
                        if (read >= buffer.size) return@use buffer // oversized marker
                        val n = stream.read(buffer, read, buffer.size - read)
                        if (n < 0) break
                        read += n
                    }
                    buffer.copyOf(read)
                }
                if (bytes.size > MAX_FILE_BYTES) null else parse(bytes.toString(Charsets.UTF_8))
            }.getOrNull() ?: EMPTY
            cached.compareAndSet(null, loaded)
            return cached.get() ?: loaded
        }

        /** Lenient, dependency-free parse kept JVM-testable: any malformed entry is skipped, never fatal. */
        fun parse(text: String): RuleSet {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_FILE_BYTES) return EMPTY
            val root = runCatching { RuleJsonParser(trimmed).parseDocument() }.getOrNull() as? Map<String, Any?>
                ?: return EMPTY
            val version = (root["version"] as? Double)?.toInt() ?: 0
            val rawRules = root["rules"] as? List<*> ?: return RuleSet(emptyList(), version)
            val out = mutableListOf<SiteRule>()
            val ids = mutableSetOf<String>()
            for (raw in rawRules) {
                if (out.size >= MAX_RULES) break
                val entry = raw as? Map<String, Any?> ?: continue
                val rule = parseRule(entry) ?: continue
                if (ids.add(rule.id)) out += rule
            }
            return RuleSet(out.toList(), version)
        }

        private fun parseRule(entry: Map<String, Any?>): SiteRule? {
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
            val actionsRaw = entry["actions"] as? List<*> ?: return null
            val actions = mutableListOf<RuleAction>()
            for (rawAction in actionsRaw) {
                if (actions.size >= MAX_ACTIONS_PER_RULE) break
                val action = parseAction(rawAction as? Map<String, Any?> ?: continue) ?: continue
                actions += action
            }
            if (actions.isEmpty()) return null
            // Same load-time safety caps as the match faces: bounded count/length, compiled here;
            // an invalid expression rejects the whole rule exactly like a match face.
            val captureEndpoints = parseCaptureEndpoints(entry["captureEndpoints"]) ?: return null
            val note = (entry["note"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(180)
            return SiteRule(id, version, RuleMatch(hosts, path, hostsPattern, pathPattern), actions.toList(), note, captureEndpoints)
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

        private fun parseAction(entry: Map<String, Any?>): RuleAction? = when (entry["type"] as? String) {
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
            else -> null // Unknown action types are skipped, never fatal.
        }

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
        private class RuleJsonParser(private val text: String) {
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
                if (++depth > 8) throw IllegalArgumentException("nesting limit")
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
                    if (out.size < 256) out += value
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
                    if (out.length > 8192) return null
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

package com.example.purebrowser.browser.sniff

/** Read-only page observations reported by PageSignalScript through PbSniffBridge. */
sealed interface PageSignal {
    data class MediaUrl(val url: String) : PageSignal
    data class MseMime(val mime: String) : PageSignal
    data class BlobManifest(val content: String, val truncated: Boolean) : PageSignal
    data class PlayerConfig(val family: String, val rawJson: String) : PageSignal
    data class IframeSrc(val url: String) : PageSignal

    /** Bounded JSON body of a rule-matched fetch/XHR endpoint; the request URL is [url]. */
    data class ApiPayload(val url: String, val content: String) : PageSignal

    /**
     * Bounded inline data block harvested read-only from the page (T85): JSON / JSON-LD script
     * elements or a generic player-config script idiom. Only reported while a loaded rule
     * declares inline-extract actions; the content is data for the rules engine, never executed.
     */
    data class InlineData(val kind: String, val content: String) : PageSignal
}

/**
 * Lenient parser for the flat bridge payload. Kept hand-rolled and org.json-free so it stays
 * JVM-unit-testable alongside the other rules tests; any malformed or mistyped input yields null.
 */
object PageSignalParser {
    fun parse(json: String): PageSignal? {
        val fields = flatObject(json) ?: return null
        return when (fields["type"] as? String) {
            "mediaUrl" -> (fields["url"] as? String)?.takeIf { it.isNotEmpty() }?.let { PageSignal.MediaUrl(it) }
            "mseMime" -> (fields["mime"] as? String)?.takeIf { it.isNotEmpty() }?.let { PageSignal.MseMime(it) }
            "blobManifest" -> {
                val content = fields["content"] as? String
                val truncated = fields["truncated"] as? Boolean
                if (content == null || truncated == null) null else PageSignal.BlobManifest(content, truncated)
            }
            "playerConfig" -> {
                val family = fields["family"] as? String
                val raw = fields["raw"] as? String
                if (family.isNullOrEmpty() || raw == null) null else PageSignal.PlayerConfig(family, raw)
            }
            "iframeSrc" -> (fields["url"] as? String)?.takeIf { it.isNotEmpty() }?.let { PageSignal.IframeSrc(it) }
            "apiPayload" -> {
                val url = fields["url"] as? String
                val content = fields["content"] as? String
                if (url.isNullOrEmpty() || content.isNullOrEmpty()) null else PageSignal.ApiPayload(url, content.take(262_144))
            }
            "inlineData" -> {
                val kind = fields["kind"] as? String
                val content = fields["content"] as? String
                if (kind == null || kind !in INLINE_KINDS || content.isNullOrEmpty()) null
                else content!!.take(262_144).let { PageSignal.InlineData(kind, it) }
            }
            else -> null
        }
    }

    private val INLINE_KINDS = setOf("json", "ldjson", "script")

    // Minimal flat-object scanner: string/boolean/number/null values only, nested structures are malformed.
    private fun flatObject(json: String): Map<String, Any?>? {
        val s = Scanner(json)
        s.skipWhitespace()
        if (!s.consume('{')) return null
        val out = LinkedHashMap<String, Any?>()
        s.skipWhitespace()
        if (s.consume('}')) return if (s.atEnd()) out else null
        while (true) {
            s.skipWhitespace()
            val key = s.parseString() ?: return null
            s.skipWhitespace()
            if (!s.consume(':')) return null
            val value = s.parseValue()
            if (value === Missing) return null
            out[key] = value
            s.skipWhitespace()
            if (s.consume(',')) continue
            if (s.consume('}')) return if (s.atEnd()) out else null
            return null
        }
    }

    private object Missing
    private object JsonNull {
        override fun toString() = "null"
    }

    private class Scanner(private val text: String) {
        private var i = 0
        fun skipWhitespace() { while (i < text.length && text[i].isWhitespace()) i++ }
        fun consume(expected: Char): Boolean {
            if (i < text.length && text[i] == expected) { i++; return true }
            return false
        }
        fun atEnd(): Boolean { skipWhitespace(); return i >= text.length }
        fun parseString(): String? {
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
                if (out.length > 300_000) return null
            }
            return null
        }
        fun parseValue(): Any? {
            skipWhitespace()
            if (i >= text.length || text[i] == '{' || text[i] == '[') return Missing
            return when (text[i]) {
                '"' -> parseString() ?: Missing
                't' -> literal("true", 4, true)
                'f' -> literal("false", 5, false)
                'n' -> literal("null", 4, JsonNull)
                else -> number()
            }
        }
        private fun literal(word: String, length: Int, value: Any?): Any? =
            if (text.regionMatches(i, word, 0, length)) { i += length; value } else Missing
        private fun number(): Any? {
            val start = i
            while (i < text.length && "+-0123456789.eE".indexOf(text[i]) >= 0) i++
            if (i == start) return Missing
            return text.substring(start, i).toDoubleOrNull() ?: Missing
        }
    }
}

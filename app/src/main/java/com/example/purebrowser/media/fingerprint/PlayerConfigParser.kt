package com.example.purebrowser.media.fingerprint

import com.example.purebrowser.media.MediaKind
import java.net.URI
import java.net.URLDecoder
import java.util.IdentityHashMap
import java.util.Locale

/** Extracts playable URLs from embedded player configurations. Every entry point is lenient: malformed or truncated input yields an empty list, never a throw. */
object PlayerConfigParser {
    private const val MAX_RESULTS = 32
    private const val MAX_WALK_DEPTH = 8
    private const val MAX_JSON_DEPTH = 64
    private const val MAX_URL_LENGTH = 8192

    private val extensions = setOf("m3u8", "mp4", "flv", "webm", "m4v", "mov", "mkv")
    private val extensionAlternation = extensions.joinToString("|")
    private val mediaToken = Regex("""^[^"'<>\s]*\.(?:$extensionAlternation)(?:[?#][^"'<>\s]*)?$""", RegexOption.IGNORE_CASE)
    private val quotedMediaUrl = Regex("""["']([^"'\s]*\.(?:$extensionAlternation)(?:[?#][^"'\s]*)?)["']""", RegexOption.IGNORE_CASE)
    private val salvagePair = Regex("""["']?([A-Za-z0-9_]+)["']?\s*:\s*["']([^"']*)["']""")
    private val mediaDefinitionsKey = Regex("mediaDefinitions", RegexOption.IGNORE_CASE)
    private val kvsUrlKey = Regex("""^video_(?:alt_)?url[a-z0-9_]*$""", RegexOption.IGNORE_CASE)
    private val kvsQualityKey = Regex("""^(?:\d{3,4}|low|high|hd)$""", RegexOption.IGNORE_CASE)
    private val labelKeys = listOf("quality", "height", "label", "resolution", "videoQuality", "format")
    private val flvParam = Regex("""(?:[?&"']|^)flv_url=([^&"'\s]+)""", RegexOption.IGNORE_CASE)

    private class SetterForm(val call: String, val label: String?, val kind: MediaKind?)
    private val setterForms = listOf(
        SetterForm("setVideoUrlHigh", "high", null),
        SetterForm("setVideoUrlLow", "low", null),
        SetterForm("setVideoHLS", null, MediaKind.HLS),
        SetterForm("setVideoUrl", null, null),
    )

    fun parse(rawJson: String, family: PlayerFamily, origin: String = "", baseUrl: String? = null): List<DiscoveredSource> {
        val text = rawJson.trim()
        if (text.isEmpty()) return emptyList()
        val sink = Sink(origin, baseUrl)
        val root = runCatching { JsonParser(text).parseDocument() }.getOrNull()
        if (root == null) {
            salvageInto(text, sink, family)
            return sink.result()
        }
        when (family) {
            PlayerFamily.FLASHVARS -> flashvarsInto(root, sink, family)
            PlayerFamily.KVS -> kvsInto(root, sink, family)
            else -> {}
        }
        if (sink.isEmpty()) genericInto(root, sink, family)
        return sink.result()
    }

    fun parseScript(scriptText: String, origin: String = "", baseUrl: String? = null): List<DiscoveredSource> {
        if (scriptText.isBlank()) return emptyList()
        val sink = Sink(origin, baseUrl)
        for (form in setterForms) {
            Regex("""(?:html5player\.)?${form.call}\s*\(\s*(['"])(.*?)\1\s*\)""", RegexOption.IGNORE_CASE).findAll(scriptText).forEach { match ->
                sink.add(match.groupValues[2], form.label, form.kind, PlayerFamily.HTML5PLAYER)
            }
        }
        flvParam.findAll(scriptText).forEach { match ->
            val decoded = runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }.getOrNull() ?: return@forEach
            sink.add(decoded, null, MediaKind.FILE, PlayerFamily.FLASHVARS)
        }
        // Bare quoted-URL scan only when no structured form matched, so unrelated script strings stay out.
        if (sink.isEmpty()) quotedMediaUrl.findAll(scriptText).forEach { match ->
            sink.add(match.groupValues[1], null, null, PlayerFamily.UNKNOWN)
        }
        return sink.result()
    }

    private fun flashvarsInto(root: Node, sink: Sink, family: PlayerFamily) {
        walk(root) { key, value, _ ->
            if (value !is Arr || key == null || !mediaDefinitionsKey.containsMatchIn(key)) return@walk
            value.items.forEach { item ->
                if (item !is Obj) return@forEach
                val url = stringValue(item["videoUrl"]) ?: return@forEach
                sink.add(url, labelValue(item["quality"]), null, family)
            }
        }
    }

    private fun kvsInto(root: Node, sink: Sink, family: PlayerFamily) {
        walk(root) { key, value, _ ->
            if (key == null || !kvsUrlKey.matches(key)) return@walk
            when (value) {
                is Obj -> value.entries.forEach { (quality, target) ->
                    val url = stringValue(target) ?: return@forEach
                    if (kvsQualityKey.matches(quality)) sink.add(url, quality, null, family)
                }
                is Str -> sink.add(value.value, null, null, family)
                else -> {}
            }
        }
    }

    private fun genericInto(root: Node, sink: Sink, family: PlayerFamily) {
        walk(root) { key, value, container ->
            val url = (value as? Str)?.value ?: return@walk
            if (!mediaToken.matches(url.trim())) return@walk
            sink.add(url, labelFrom(container, key), null, family)
        }
    }

    /** Sibling keys of the enclosing object first; a map entry keyed by its own quality labels itself. */
    private fun labelFrom(container: Obj?, ownKey: String?): String? {
        if (container != null) {
            for (name in labelKeys) {
                val label = labelValue(container[name]) ?: continue
                return label.take(32)
            }
        }
        return ownKey?.takeIf { kvsQualityKey.matches(it) }
    }

    private fun salvageInto(text: String, sink: Sink, family: PlayerFamily) {
        salvagePair.findAll(text).forEach { match ->
            val value = match.groupValues[2]
            if (!mediaToken.matches(value.trim())) return@forEach
            sink.add(value, match.groupValues[1].takeIf { kvsQualityKey.matches(it) }, null, family)
        }
        quotedMediaUrl.findAll(text).forEach { match -> sink.add(match.groupValues[1], null, null, family) }
    }

    // Identity semantics so equal-looking nodes in different positions are all visited; parsed trees cannot cycle, the guard only keeps hostile input bounded.
    private fun walk(node: Node, block: (key: String?, value: Node, container: Obj?) -> Unit) {
        walkNode(node, null, null, 0, IdentityHashMap<Node, Boolean>(), block)
    }

    private fun walkNode(
        node: Node,
        key: String?,
        container: Obj?,
        depth: Int,
        visited: IdentityHashMap<Node, Boolean>,
        block: (String?, Node, Obj?) -> Unit,
    ) {
        if (depth > MAX_WALK_DEPTH || visited.put(node, true) != null) return
        block(key, node, container)
        when (node) {
            is Obj -> node.entries.forEach { (childKey, child) -> walkNode(child, childKey, node, depth + 1, visited, block) }
            is Arr -> node.items.forEach { walkNode(it, key, container, depth + 1, visited, block) }
            else -> {}
        }
    }

    private class Sink(private val origin: String, private val baseUrl: String?) {
        private val seen = mutableSetOf<String>()
        private val out = mutableListOf<DiscoveredSource>()

        fun add(rawUrl: String, label: String?, kind: MediaKind? = null, family: PlayerFamily = PlayerFamily.UNKNOWN) {
            if (out.size >= MAX_RESULTS) return
            val url = sanitize(rawUrl, baseUrl) ?: return
            if (!seen.add(url)) return
            out += DiscoveredSource(url, label?.trim()?.takeIf { it.isNotEmpty() }?.take(32), kind ?: DiscoveredSource.kindFor(url), family, origin)
        }

        fun isEmpty() = out.isEmpty()
        fun result(): List<DiscoveredSource> = out.toList()
    }

    /** http(s) only; quotes and outer whitespace stripped; relative input survives only when a base URL resolves it. */
    private fun sanitize(rawUrl: String, baseUrl: String?): String? {
        var url = rawUrl.trim().trim('"', '\'').trim()
        if (url.isEmpty() || url.length > MAX_URL_LENGTH) return null
        if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        val base = baseUrl?.let { runCatching { URI(it) }.getOrNull() }
        if (url.startsWith("//")) {
            val scheme = base?.scheme?.lowercase(Locale.ROOT) ?: return null
            url = "$scheme:$url"
        } else if (url.startsWith("/")) {
            if (base == null || base.scheme == null || base.host == null) return null
            val port = if (base.port > 0) ":${base.port}" else ""
            url = "${base.scheme}://${base.host}$port$url"
        }
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return url
    }

    private fun stringValue(node: Node?): String? = (node as? Str)?.value?.takeIf { it.isNotEmpty() }

    private fun labelValue(node: Node?): String? = when (node) {
        is Str -> node.value.takeIf { it.isNotBlank() }
        is Raw -> node.text.takeIf { it != "null" && it.isNotBlank() }
        else -> null
    }

    private sealed interface Node
    private class Obj(val entries: List<Pair<String, Node>>) : Node {
        operator fun get(key: String): Node? = entries.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second
    }
    private class Arr(val items: List<Node>) : Node
    private class Str(val value: String) : Node
    private class Raw(val text: String) : Node // numbers, booleans and null literals

    private class JsonParser(private val text: String) {
        private var i = 0
        private var depth = 0

        fun parseDocument(): Node? {
            skipSpace()
            if (i >= text.length) return null
            return value()
        }

        private fun skipSpace() {
            while (i < text.length && text[i].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) i++
        }

        private fun value(): Node {
            if (++depth > MAX_JSON_DEPTH) throw IllegalArgumentException("nesting limit")
            try {
                return when (val c = text[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> Str(string())
                    't' -> literal("true")
                    'f' -> literal("false")
                    'n' -> literal("null")
                    else -> if (c == '-' || c.isDigit()) Raw(number()) else throw IllegalArgumentException("unexpected token")
                }
            } finally {
                depth--
            }
        }

        // Truncated documents keep the pairs completed before end of input instead of failing.
        private fun obj(): Obj {
            i++
            val entries = mutableListOf<Pair<String, Node>>()
            while (true) {
                skipSpace()
                if (i >= text.length) return Obj(entries)
                when (text[i]) {
                    '}' -> { i++; return Obj(entries) }
                    ',' -> { i++; continue }
                    '"' -> {}
                    else -> throw IllegalArgumentException("expected key")
                }
                val key = string()
                skipSpace()
                if (i >= text.length || text[i] != ':') return Obj(entries)
                i++
                skipSpace()
                if (i >= text.length) return Obj(entries)
                entries.add(key to value())
            }
        }

        private fun arr(): Arr {
            i++
            val items = mutableListOf<Node>()
            while (true) {
                skipSpace()
                if (i >= text.length) return Arr(items)
                when (text[i]) {
                    ']' -> { i++; return Arr(items) }
                    ',' -> { i++; continue }
                    else -> items.add(value())
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '"' -> { i++; return out.toString() }
                    c == '\\' && i + 1 < text.length -> {
                        i++
                        when (val esc = text[i]) {
                            'u' -> {
                                val hex = if (i + 4 < text.length) text.substring(i + 1, i + 5) else ""
                                val code = hex.toIntOrNull(16) ?: return out.toString()
                                out.append(code.toChar())
                                i += 4
                            }
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            else -> out.append(esc)
                        }
                        i++
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString() // unterminated: keep what we have
        }

        private fun number(): String {
            val start = i
            while (i < text.length && text[i].let { it.isDigit() || it in "+-.eE" }) i++
            return text.substring(start, i)
        }

        private fun literal(word: String): Node {
            if (!text.startsWith(word, i)) throw IllegalArgumentException("bad literal")
            i += word.length
            return Raw(word)
        }
    }
}

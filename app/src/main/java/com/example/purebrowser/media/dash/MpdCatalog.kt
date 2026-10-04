package com.example.purebrowser.media.dash

/**
 * Tolerant, display-only cataloger for DASH MPD documents. Pure Kotlin and hand-rolled like the
 * other sniff parsers so it stays JVM-unit-testable: it lists Representation summaries only —
 * no segment addresses, no BaseURL resolution, no download semantics (DASH stays display-only in
 * this version). Malformed or truncated documents yield whatever complete Representations were
 * already collected, never an exception.
 */
data class DashRepresentation(
    val height: Int?,
    val bandwidth: Long?,
    val codecs: String?,
    val id: String?,
    val mimeType: String?,
)

object MpdCatalog {
    /** Documents above this size are rejected outright before any scanning. */
    const val MAX_MPD_CHARS = 2 * 1024 * 1024
    /** Attribute values above this length are treated as absent. */
    const val MAX_ATTRIBUTE_CHARS = 512
    /** Listing bound: only the first this-many Representations are collected. */
    const val MAX_REPRESENTATIONS = 32

    private val interestingAttributes = setOf("height", "bandwidth", "codecs", "id", "mimeType")

    /**
     * Lists Representation summaries sorted by height descending (unknown last), then bandwidth
     * descending. [documentUrl] identifies the source document for callers; this tier publishes
     * no per-representation addresses, so nothing is resolved against it here.
     */
    fun representations(mpdText: String, documentUrl: String): List<DashRepresentation> {
        if (mpdText.length > MAX_MPD_CHARS) return emptyList()
        val scanner = Scanner(mpdText)
        // The scanner never throws by construction; this guard only guarantees that contract if
        // that ever changes — completed Representations are still returned.
        runCatching { scanner.scan() }
        return scanner.collected.sortedWith(
            compareByDescending<DashRepresentation> { it.height }.thenByDescending { it.bandwidth },
        )
    }

    /** Strip any namespace prefix: both tags and attributes match on local names. */
    private fun localName(raw: String): String = raw.substringAfterLast(':', raw)

    private fun unescape(value: String): String {
        if ('&' !in value) return value
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }

    private class AdaptationSetAttrs(
        val height: Int?,
        val bandwidth: Long?,
        val codecs: String?,
        val mimeType: String?,
    )

    /** Open element; [adaptation] is the nearest enclosing AdaptationSet's attributes, if any. */
    private class Frame(val name: String, val adaptation: AdaptationSetAttrs?)

    private class Scanner(private val text: String) {
        val collected = ArrayList<DashRepresentation>()
        private val stack = ArrayList<Frame>()
        private var i = 0

        fun scan() {
            while (i < text.length) {
                if (text[i] == '<') markup() else i++
            }
        }

        private fun markup() {
            when {
                i + 1 >= text.length -> i = text.length
                text[i + 1] == '/' -> closeTag()
                text[i + 1] == '?' -> skipPast("?>")
                text[i + 1] == '!' -> declarationLike()
                else -> openTag()
            }
        }

        /** Comments, CDATA blocks and doctypes are skipped without interpreting their content. */
        private fun declarationLike() {
            when {
                text.startsWith("<!--", i) -> skipPast("-->")
                text.startsWith("<![CDATA[", i) -> skipPast("]]>")
                else -> skipPast(">")
            }
        }

        /** Consumes past the marker, or to end of input when the terminator never arrives. */
        private fun skipPast(marker: String) {
            val found = text.indexOf(marker, i + 2)
            i = if (found >= 0) found + marker.length else text.length
        }

        private fun closeTag() {
            val start = i + 2
            var end = start
            while (end < text.length && !text[end].isWhitespace() && text[end] != '>') end++
            val name = localName(text.substring(start, end))
            i = if (end < text.length) end + 1 else text.length
            if (name.isEmpty()) return
            // Tolerant unwind: drop frames up to the nearest match; stray closers are ignored.
            while (stack.isNotEmpty()) {
                if (stack.removeAt(stack.lastIndex).name == name) break
            }
        }

        private fun openTag() {
            val nameStart = ++i
            var nameEnd = nameStart
            while (nameEnd < text.length && !text[nameEnd].isWhitespace() &&
                text[nameEnd] != '>' && text[nameEnd] != '/') nameEnd++
            if (nameEnd >= text.length) { i = text.length; return }
            val name = localName(text.substring(nameStart, nameEnd))
            i = nameEnd
            val attrs = HashMap<String, String>()
            var selfClosing = false
            scan@ while (true) {
                while (i < text.length && text[i].isWhitespace()) i++
                when {
                    i >= text.length -> return // truncated tag: abandoned, input is exhausted
                    text[i] == '>' -> { i++; break@scan }
                    text[i] == '/' -> {
                        i++
                        if (i < text.length && text[i] == '>') { i++; selfClosing = true; break@scan }
                    }
                    else -> if (!attribute(attrs)) return
                }
            }
            fun height(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }
            fun bandwidth(value: String?): Long? = value?.toLongOrNull()?.takeIf { it >= 0 }
            val inherited = stack.lastOrNull()?.adaptation
            if (name == "Representation" && collected.size < MAX_REPRESENTATIONS) {
                // Representation attributes win; missing ones fall back to the AdaptationSet.
                collected += DashRepresentation(
                    height = height(attrs["height"]) ?: inherited?.height,
                    bandwidth = bandwidth(attrs["bandwidth"]) ?: inherited?.bandwidth,
                    codecs = attrs["codecs"] ?: inherited?.codecs,
                    id = attrs["id"],
                    mimeType = attrs["mimeType"] ?: inherited?.mimeType,
                )
            }
            val frame = if (name == "AdaptationSet") {
                Frame(name, AdaptationSetAttrs(
                    height(attrs["height"]), bandwidth(attrs["bandwidth"]),
                    attrs["codecs"], attrs["mimeType"],
                ))
            } else Frame(name, inherited)
            if (!selfClosing) stack.add(frame)
            // The listing bound stops scanning: later Representations cannot change the result.
            if (collected.size >= MAX_REPRESENTATIONS) i = text.length
        }

        /** Reads one attribute into [into]; false means the input ended inside the tag. */
        private fun attribute(into: HashMap<String, String>): Boolean {
            val nameStart = i
            while (i < text.length && text[i] != '=' && !text[i].isWhitespace() &&
                text[i] != '>' && text[i] != '/') i++
            if (i >= text.length) return false
            val name = localName(text.substring(nameStart, i))
            if (text[i] != '=') return true // valueless attribute: tolerated and ignored
            i++
            if (i >= text.length) return false
            val value = when (val quote = text[i]) {
                '"', '\'' -> {
                    val valueStart = ++i
                    while (i < text.length && text[i] != quote) i++
                    if (i >= text.length) return false // unterminated quote: truncated document
                    text.substring(valueStart, i).also { i++ }
                }
                else -> {
                    val valueStart = i
                    while (i < text.length && !text[i].isWhitespace() && text[i] != '>') i++
                    if (i >= text.length) return false
                    // Unquoted values cannot legally contain '/', so trim self-closing tails.
                    text.substring(valueStart, i).trimEnd('/')
                }
            }
            if (name in interestingAttributes && value.length <= MAX_ATTRIBUTE_CHARS) into[name] = unescape(value)
            return true
        }
    }
}

package com.example.purebrowser.download.hls

import com.example.purebrowser.media.VariantSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.OffsetDateTime
import java.util.Locale

/** Pure JVM/Kotlin parser. Does not fetch, recursively resolve manifests, or authorize requests. */
object HlsPlaylistParser {
    const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
    const val MAX_VARIANTS = 32
    const val MAX_SEGMENTS = 10_000
    const val MAX_URL_LENGTH = 8192
    /** Parsing/accumulation bound, independent of later transport and storage limits. */
    const val MAX_DURATION_US = 86_400_000_000L
    private const val SECOND_US = 1_000_000L
    private val unsignedInteger = Regex("[0-9]+")
    private val decimal = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val byteRangeValue = Regex("[0-9]{1,20}(?:@[0-9]{1,20})?")
    private val attributeName = Regex("[A-Z0-9-]+")
    private val resolution = Regex("([0-9]+)x([0-9]+)")
    private val codecName = Regex("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*")
    // Mirror RequestPolicy's named debug targets; the caller must still apply release/debug policy.
    private val localHttpHosts = setOf("127.0.0.1", "10.0.2.2")
    private val excludedTags = setOf(
        "#EXT-X-BYTERANGE", "#EXT-X-DISCONTINUITY", "#EXT-X-DISCONTINUITY-SEQUENCE",
        "#EXT-X-GAP", "#EXT-X-I-FRAMES-ONLY", "#EXT-X-PART", "#EXT-X-PART-INF", "#EXT-X-PRELOAD-HINT",
        "#EXT-X-SERVER-CONTROL", "#EXT-X-RENDITION-REPORT", "#EXT-X-SKIP", "#EXT-X-DEFINE",
        "#EXT-X-CONTENT-STEERING",
    )
    private enum class Kind { MASTER, MEDIA }
    private data class Attributes(val values: Map<String, String>, val quoted: Set<String>) {
        operator fun get(name: String) = values[name]
        fun string(name: String, allowEmpty: Boolean = false): String? = values[name]?.also {
            if (name !in quoted || (!allowEmpty && it.isEmpty())) reject("清单属性格式无效")
        }
        fun number(name: String): String? = values[name]?.also {
            if (name in quoted) reject("清单数值属性格式无效")
        }
        override fun toString() = "Attributes(<redacted>)"
    }
    private data class VariantDraft(val value: HlsVariant, val audio: String?, val subtitles: String?, val video: String?) {
        override fun toString() = "VariantDraft(<redacted>)"
    }

    fun parse(body: String, finalUrl: String): HlsPlaylist {
        // Check character length before allocating a bounded UTF-8 representation.
        if (body.length > MAX_PLAYLIST_BYTES || body.toByteArray(Charsets.UTF_8).size > MAX_PLAYLIST_BYTES)
            reject("清单超过大小限制")
        if (body.any { it.isISOControl() && it !in "\r\n\t" }) reject("清单包含无效控制字符")
        val base = checkedUrl(finalUrl)
        val lines = body.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().removePrefix("\uFEFF").trim() != "#EXTM3U")
            reject("不是有效的 HLS 清单")
        var kind: Kind? = null
        fun mark(next: Kind) {
            if (kind != null && kind != next) reject("清单混用了主清单和媒体清单标签")
            kind = next
        }
        val unique = hashSetOf<String>()
        fun once(tag: String) { if (!unique.add(tag)) reject("清单包含重复标签") }
        val variants = arrayListOf<VariantDraft>()
        // A group carries a separate audio resource if ANY member has a URI, not just the default.
        val audioGroups = hashMapOf<String, Boolean>()
        val audioRenditions = arrayListOf<HlsAudioRendition>()
        val renditionNames = hashSetOf<Triple<String, String, String>>()
        val segments = arrayListOf<HlsSegment>()
        // Container classification only; transport support is gated later at the SegmentFormat seam.
        var segmentFormat = SegmentFormat.MPEG_TS
        var pendingInitSegment: HlsInitSegment? = null
        var pendingVariant: Attributes? = null
        var pendingDuration: Long? = null
        var targetDurationUs: Long? = null
        var mediaSequence = 0L
        var totalUs = 0L
        var ended = false
        while (lines.hasNext()) {
            val line = lines.next().trim()
            if (line.isEmpty()) continue
            if (!line.startsWith('#')) {
                if (line.contains("{$")) reject("本版不支持清单变量")
                val url = resolve(base, line)
                val stream = pendingVariant
                if (stream != null) {
                    if (variants.size >= MAX_VARIANTS) reject("清单档位数量超过限制")
                    variants += variant(stream, url)
                    pendingVariant = null
                } else {
                    mark(Kind.MEDIA)
                    if (ended) reject("结束标记后仍有媒体分片")
                    val duration = pendingDuration ?: reject("媒体分片缺少配对时长")
                    if (segments.size >= MAX_SEGMENTS) reject("清单分片数量超过限制")
                    segmentFormat = classifySegment(url, segmentFormat)
                    if (duration > MAX_DURATION_US - totalUs) reject("清单总时长超过限制")
                    segments += HlsSegment(url, duration, segments.size)
                    totalUs += duration
                    pendingDuration = null
                }
                continue
            }
            // Ordinary comments are harmless; all extension tags must be understood.
            if (!line.startsWith("#EXT")) continue
            if (line.contains("{$")) reject("本版不支持清单变量")
            val tag = line.substringBefore(':')
            val payload = line.substringAfter(':', "")
            fun value(): String {
                if (!line.contains(':') || payload.isEmpty()) reject("清单标签缺少有效值")
                return payload
            }
            fun flag() { if (line != tag) reject("清单标签格式无效") }
            if (tag in excludedTags) reject("清单包含本版不支持的媒体特性")
            if (pendingVariant != null) reject("档位声明缺少配对地址")
            when (tag) {
                "#EXTM3U" -> reject("清单包含重复文件头")
                "#EXT-X-VERSION" -> { once(tag); integer(value(), Int.MAX_VALUE.toLong(), true) }
                "#EXT-X-INDEPENDENT-SEGMENTS" -> { flag(); once(tag) }
                "#EXT-X-START" -> {
                    once(tag)
                    val a = attributes(value())
                    signedSeconds(a.number("TIME-OFFSET") ?: reject("清单起始属性缺失"))
                    a.number("PRECISE")?.let { yesNo(it) }
                }
                "#EXT-X-KEY", "#EXT-X-SESSION-KEY" -> {
                    if (tag == "#EXT-X-KEY") mark(Kind.MEDIA) else mark(Kind.MASTER)
                    val a = attributes(value())
                    if (a.number("METHOD") != "NONE") reject("本版不支持加密 HLS")
                    if (a.values.keys != setOf("METHOD")) reject("未加密密钥声明格式无效")
                }
                "#EXT-X-STREAM-INF" -> {
                    mark(Kind.MASTER)
                    if (variants.size >= MAX_VARIANTS) reject("清单档位数量超过限制")
                    pendingVariant = attributes(value())
                }
                "#EXT-X-MEDIA" -> {
                    mark(Kind.MASTER)
                    val a = attributes(value())
                    val type = a.number("TYPE") ?: reject("音轨属性缺失")
                    if (type !in setOf("AUDIO", "VIDEO", "SUBTITLES", "CLOSED-CAPTIONS")) reject("音轨类型无效")
                    val group = a.string("GROUP-ID") ?: reject("音轨分组缺失")
                    val name = a.string("NAME") ?: reject("音轨名称缺失")
                    if (!renditionNames.add(Triple(type, group, name))) reject("音轨声明重复")
                    listOf("DEFAULT", "AUTOSELECT", "FORCED").forEach { key -> a.number(key)?.let { yesNo(it) } }
                    if (a["FORCED"] != null && type != "SUBTITLES") reject("音轨属性组合无效")
                    if (a["DEFAULT"] == "YES" && a["AUTOSELECT"] == "NO") reject("音轨属性组合无效")
                    val uri = a.string("URI")?.let { resolve(base, it) }
                    if (type == "SUBTITLES" && uri == null) reject("字幕声明缺少地址")
                    if (type == "CLOSED-CAPTIONS" && uri != null) reject("内嵌字幕声明格式无效")
                    if (type == "AUDIO") {
                        // Metadata hints only; correctness is proven by the rendition playlist itself.
                        val language = boundedHint(a["LANGUAGE"], 64, "音轨语言属性无效")
                        val channels = boundedHint(a["CHANNELS"], 32, "音轨声道属性无效")
                        audioRenditions += HlsAudioRendition(uri, group, name, language, channels, a["DEFAULT"] == "YES")
                        audioGroups[group] = (audioGroups[group] ?: false) || uri != null
                    }
                }
                "#EXT-X-MAP" -> {
                    // The fMP4 init declaration: carried into the plan; a BYTERANGE window (RFC 8216
                    // §4.3.2.5) is fetched later as one exact closed interval, never an open-ended slice.
                    mark(Kind.MEDIA)
                    once(tag)
                    val a = attributes(value())
                    if (a.values.keys.any { it != "URI" && it != "BYTERANGE" }) reject("初始化段声明格式无效")
                    val uri = a.string("URI") ?: reject("初始化段声明缺少地址")
                    val declaredRange = a.string("BYTERANGE")?.let(::byteRange)
                    pendingInitSegment = HlsInitSegment(resolve(base, uri), declaredRange?.second ?: 0L, declaredRange?.first)
                    segmentFormat = SegmentFormat.FMP4
                }
                "#EXT-X-I-FRAME-STREAM-INF" -> {
                    // A preview-only rendition is not a selectable complete video.
                    mark(Kind.MASTER)
                    val a = attributes(value())
                    resolve(base, a.string("URI") ?: reject("预览档位缺少地址"))
                    a.number("BANDWIDTH")?.let { integer(it, Long.MAX_VALUE, true) }
                }
                "#EXT-X-SESSION-DATA" -> {
                    mark(Kind.MASTER)
                    val a = attributes(value())
                    a.string("DATA-ID") ?: reject("会话元数据缺少标识")
                    if ((a["VALUE"] == null) == (a["URI"] == null)) reject("会话元数据属性组合无效")
                    a.string("VALUE", allowEmpty = true)
                    a.string("URI")?.let { resolve(base, it) }
                }
                "#EXT-X-TARGETDURATION" -> {
                    mark(Kind.MEDIA); once(tag)
                    targetDurationUs = integer(value(), MAX_DURATION_US / SECOND_US, true) * SECOND_US
                }
                "#EXT-X-ALLOW-CACHE" -> { mark(Kind.MEDIA); once(tag); yesNo(value()) }
                "#EXT-X-MEDIA-SEQUENCE" -> {
                    mark(Kind.MEDIA); once(tag)
                    if (segments.isNotEmpty() || pendingDuration != null) reject("分片序号标签位置无效")
                    mediaSequence = integer(value(), Long.MAX_VALUE, false)
                }
                "#EXT-X-PLAYLIST-TYPE" -> {
                    mark(Kind.MEDIA); once(tag)
                    if (value() !in setOf("VOD", "EVENT")) reject("媒体清单类型无效")
                }
                "#EXTINF" -> {
                    mark(Kind.MEDIA)
                    if (ended || pendingDuration != null) reject("分片时长与地址未配对")
                    val text = value()
                    if (',' !in text) reject("分片时长声明格式无效")
                    pendingDuration = seconds(text.substringBefore(','))
                }
                "#EXT-X-ENDLIST" -> {
                    mark(Kind.MEDIA); flag(); once(tag)
                    if (pendingDuration != null) reject("分片时长缺少配对地址")
                    ended = true
                }
                "#EXT-X-PROGRAM-DATE-TIME" -> { mark(Kind.MEDIA); date(value()) }
                "#EXT-X-DATERANGE" -> {
                    mark(Kind.MEDIA)
                    val a = attributes(value())
                    a.string("ID") ?: reject("日期元数据缺少标识")
                    date(a.string("START-DATE") ?: reject("日期元数据缺少起始时间"))
                    a.string("END-DATE")?.let { date(it) }
                    listOf("DURATION", "PLANNED-DURATION").forEach { key -> a.number(key)?.let { seconds(it, positive = false) } }
                    a.number("END-ON-NEXT")?.let { if (it != "YES" || a.string("CLASS") == null) reject("日期元数据格式无效") }
                    if (a["CLASS"] == "com.apple.hls.interstitial" || a["X-ASSET-URI"] != null || a["X-ASSET-LIST"] != null)
                        reject("本版不支持插播媒体")
                }
                else -> reject("清单包含无法安全识别的扩展标签")
            }
        }
        if (pendingVariant != null || pendingDuration != null) reject("清单声明缺少配对地址")
        if (kind == Kind.MASTER) {
            if (variants.isEmpty()) reject("主清单没有视频档位")
            return HlsPlaylist.Master(variants.map { draft ->
                // Separate A/V renditions now map onto the dual-track plan (v0.1.9); codec/subtitle
                // gates and the separate-audio notice downgrade to selectable warnings so
                // parse-on-detection can surface them. An undefined audio group stays unsupported:
                // its audio location is unknowable and must not be guessed.
                val hardReason = when {
                    draft.video != null -> "本版不支持独立视频轨"
                    draft.audio != null && audioGroups[draft.audio] == null -> "本版不支持未定义的音轨分组"
                    else -> null
                }
                val notice = draft.value.unsupportedReason
                    ?: when {
                        draft.subtitles != null -> "此档位声明了字幕轨，本版保存时不含字幕"
                        draft.audio != null && audioGroups[draft.audio] == true -> "此档位带独立音轨，保存时自动分轨合并"
                        else -> null
                    }
                draft.value.copy(supported = hardReason == null, unsupportedReason = hardReason ?: notice)
            }, audioRenditions)
        }
        if (!ended) reject("本版只支持具有结束标记的固定点播清单")
        if (segments.isEmpty()) reject("媒体清单没有分片")
        val target = targetDurationUs ?: reject("媒体清单缺少目标分片时长")
        if (segments.any { ((it.durationUs + SECOND_US / 2) / SECOND_US) * SECOND_US > target })
            reject("分片时长超过清单目标时长")
        return HlsPlaylist.Media(segments, totalUs, target, mediaSequence, segmentFormat, pendingInitSegment)
    }

    /** Stable order breaks metadata ties. Unknown metadata is never invented. */
    fun defaultVariant(variants: List<HlsVariant>): HlsVariant? {
        val supported = variants.filter { it.supported }
        if (supported.isEmpty()) return null
        // Warned-but-selectable variants only qualify when no clean variant exists.
        val clean = supported.filter { it.unsupportedReason == null }
        return pickDefault(clean.ifEmpty { supported })
    }

    /** Master variant summaries for surface-level display; media playlists carry no variants. */
    fun variantSummaries(body: String, finalUrl: String): List<VariantSummary> {
        val master = parse(body, finalUrl) as? HlsPlaylist.Master ?: return emptyList()
        return master.variants.map { variant ->
            VariantSummary(variant.height, variant.bandwidth, variant.codecs, variant.url,
                // An unsupported variant's reason is an exclusion, not a selectable warning.
                variant.unsupportedReason?.takeIf { variant.supported })
        }
    }

    private fun pickDefault(supported: List<HlsVariant>): HlsVariant {
        fun known(v: HlsVariant) = v.width != null && v.width > 0 && v.height != null && v.height > 0
        val quality = compareBy<HlsVariant> { it.height }.thenBy { it.width }.thenBy { it.bandwidth ?: -1L }
        supported.filter { known(it) && it.height!! <= 1080 }.maxWithOrNull(quality)?.let { return it }
        if (supported.all { known(it) && it.height!! > 1080 }) {
            val lowest = supported.minWithOrNull(compareBy<HlsVariant> { it.height }.thenBy { it.width })!!
            return supported.filter { it.height == lowest.height && it.width == lowest.width }
                .maxByOrNull { it.bandwidth ?: -1L } ?: supported.first()
        }
        if (supported.all { !known(it) }) {
            val reliable = supported.filter { it.bandwidth != null && it.bandwidth > 0 }.sortedBy { it.bandwidth }
            if (reliable.isNotEmpty()) return reliable[reliable.size / 2]
        }
        return supported.first()
    }

    private fun variant(a: Attributes, url: String): VariantDraft {
        val peak = a.number("BANDWIDTH")?.let { integer(it, Long.MAX_VALUE, true) }
        val average = a.number("AVERAGE-BANDWIDTH")?.let { integer(it, Long.MAX_VALUE, true) }
        val dimensions = a.number("RESOLUTION")?.let {
            val match = resolution.matchEntire(it) ?: reject("档位分辨率格式无效")
            integer(match.groupValues[1], Int.MAX_VALUE.toLong(), true).toInt() to
                integer(match.groupValues[2], Int.MAX_VALUE.toLong(), true).toInt()
        }
        a.number("FRAME-RATE")?.let { seconds(it) }
        val codecs = a.string("CODECS")
        if (codecs != null && codecs.length > 1024) reject("档位编码属性超过限制")
        val names = codecs?.split(',')?.map { it.trim() }
        if (names?.any { it.length > 128 || !codecName.matches(it) } == true) reject("档位编码属性格式无效")
        // Codec and embedded-caption gates are warnings: the variant stays selectable and any
        // later segment-level incompatibility fails honestly during resolution, never faked.
        val warning = when {
            names?.any { it.substringBefore('.') !in setOf("avc1", "avc3", "mp4a") } == true -> "此档位编码不是 H.264/AAC，保存时可能失败"
            names != null && (!names.any { it.startsWith("avc1") || it.startsWith("avc3") } || !names.any { it.startsWith("mp4a") }) -> "此档位只声明单一 H.264 或 AAC 轨，可能不是完整视频"
            a["CLOSED-CAPTIONS"] != null && a["CLOSED-CAPTIONS"] != "NONE" -> "此档位带内嵌字幕，本版保存时不含字幕"
            else -> null
        }
        a["CLOSED-CAPTIONS"]?.let {
            if (it == "NONE") a.number("CLOSED-CAPTIONS") else a.string("CLOSED-CAPTIONS")
        }
        return VariantDraft(
            HlsVariant(url, peak ?: average, dimensions?.first, dimensions?.second, codecs, warning == null, warning, a.string("AUDIO")),
            a.string("AUDIO"), a.string("SUBTITLES"), a.string("VIDEO"),
        )
    }

    /** No splitting on commas until quoted values have been consumed; duplicate names are fatal. */
    private fun attributes(text: String): Attributes {
        val values = linkedMapOf<String, String>()
        val quoted = hashSetOf<String>()
        var position = 0
        fun spaces() { while (position < text.length && text[position] in " \t") position++ }
        while (position < text.length) {
            spaces()
            val start = position
            while (position < text.length && text[position] != '=') {
                if (text[position] == ',' || text[position] == '"') reject("清单属性格式无效")
                position++
            }
            if (position == text.length) reject("清单属性缺少值")
            val name = text.substring(start, position).trim()
            if (!attributeName.matches(name) || name in values) reject("清单属性名称无效或重复")
            position++; spaces()
            val value: String
            if (position < text.length && text[position] == '"') {
                quoted += name
                position++
                val valueStart = position
                while (position < text.length && text[position] != '"') position++
                if (position == text.length) reject("清单引号属性未闭合")
                value = text.substring(valueStart, position)
                position++; spaces()
                if (position < text.length && text[position] != ',') reject("清单引号属性格式无效")
            } else {
                val valueStart = position
                while (position < text.length && text[position] != ',') position++
                value = text.substring(valueStart, position).trim()
                if (value.isEmpty() || value.any { it.isWhitespace() || it == '"' || it == '=' }) reject("清单属性值无效")
            }
            values[name] = value
            if (position < text.length) {
                position++
                spaces()
                if (position == text.length) reject("清单属性列表末尾无效")
            }
        }
        if (values.isEmpty()) reject("清单属性为空")
        return Attributes(values, quoted)
    }

    private fun integer(text: String, max: Long, positive: Boolean): Long {
        if (text.length > 20 || !unsignedInteger.matches(text)) reject("清单整数格式无效")
        val value = text.toLongOrNull() ?: reject("清单整数超出范围")
        if (value > max || (positive && value == 0L)) reject("清单整数超出范围")
        return value
    }

    private fun seconds(text: String, positive: Boolean = true): Long {
        if (text.length > 32 || !decimal.matches(text)) reject("清单时长格式无效")
        val value = BigDecimal(text)
        if (value > BigDecimal.valueOf(MAX_DURATION_US, 6)) reject("清单时长超过限制")
        val us = value.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact()
        if (positive && us <= 0) reject("清单时长必须为正数")
        return us
    }

    private fun signedSeconds(text: String) { seconds(text.removePrefix("-"), positive = false) }

    /** BYTERANGE="n[@o]": exact byte length n at byte offset o (default 0). */
    private fun byteRange(value: String): Pair<Long, Long> {
        if (value.length > 41 || !byteRangeValue.matches(value)) reject("初始化段字节范围格式无效")
        val length = value.substringBefore('@').toLongOrNull() ?: reject("初始化段字节范围格式无效")
        if (length <= 0L) reject("初始化段字节范围格式无效")
        val offset = value.substringAfter('@', "0").toLongOrNull() ?: reject("初始化段字节范围格式无效")
        return length to offset
    }
    private fun yesNo(text: String) { if (text != "YES" && text != "NO") reject("清单布尔属性无效") }
    private fun date(text: String) {
        if (runCatching { OffsetDateTime.parse(text) }.isFailure) reject("清单日期格式无效")
    }

    private fun checkedUrl(value: String): URI {
        if (value.isEmpty() || value.length > MAX_URL_LENGTH || value.any { it.isISOControl() } || value.contains("{$"))
            reject("资源地址格式无效或超过限制")
        val uri = try { URI(value) } catch (_: Exception) { reject("资源地址格式无效") }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (uri.isOpaque || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535) ||
            !(scheme == "https" || (scheme == "http" && uri.host in localHttpHosts)))
            reject("资源地址不符合本版安全下载范围")
        return uri
    }

    private fun resolve(base: URI, rawReference: String): String {
        // A fragment in a playlist address is a client-side hint (some CDNs tag variant lines
        // with one); HTTP never transmits it and the downloader's URL policy would reject it.
        // It is dropped here instead of failing the whole playlist — every scheme/host/policy
        // check still applies to what remains.
        val reference = rawReference.substringBefore('#')
        if (reference.isEmpty() || reference.length > MAX_URL_LENGTH || reference.contains("{$")) reject("资源地址格式无效或超过限制")
        val relative = try { URI(reference) } catch (_: Exception) { reject("资源地址格式无效") }
        // java.net.URI resolves '?query' against the directory, rather than the current file.
        // Handle this RFC 3986 reference explicitly; never inherit the parent's query for child paths.
        val resolved = if (relative.scheme == null && relative.rawAuthority == null && relative.rawPath.isNullOrEmpty() && relative.rawQuery != null) {
            "${base.scheme}://${base.rawAuthority}${base.rawPath.orEmpty()}?${relative.rawQuery}"
        } else base.resolve(relative).toString()
        checkedUrl(resolved)
        return resolved
    }

    /** Classifies by declared extension only; extensionless TS is verified by the content reader. */
    private fun classifySegment(value: String, current: SegmentFormat): SegmentFormat {
        val extension = URI(value).path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in setOf("m4s", "mp4", "m4a")) return SegmentFormat.FMP4
        if (extension in setOf("aac", "mp3", "vtt", "webvtt", "srt"))
            reject("本版只支持待内容校验的 MPEG-TS 分片")
        // A nested playlist is not fetched here; manifest recursion belongs to the bounded resolver.
        return current
    }

    /** Quoted or bare metadata hint; bounded and control-free, never trusted beyond display. */
    private fun boundedHint(value: String?, limit: Int, reason: String): String? = value?.also {
        if (it.isEmpty() || it.length > limit || it.any { c -> c.isISOControl() }) reject(reason)
    }

    private fun reject(reason: String): Nothing = throw HlsParseException(reason)
}

package com.example.purebrowser.download.dash

import java.net.URI
import java.util.Locale

/**
 * Honest plan-tier MPD parser for T96/T97. Where [com.example.purebrowser.media.dash.MpdCatalog]
 * lists Representation summaries for display only, this parser ALSO resolves a chosen
 * Representation's init + media segment addresses (SegmentTemplate $Number$/$Time$ math and
 * SegmentList entries), resolves relative URLs through the BaseURL chain against the MPD URL, and
 * states honest Chinese reasons (never URLs or credentials) for every unsupported shape:
 * live (dynamic), multi-period, DRM, AV1/HEVC/VP9, missing H.264/AAC codec declaration,
 * SegmentBase/indexRange single-file, byte-range initialization, mediaRange entries, unbounded or
 * over-budget segment counts, and duration-less templates. Pure Kotlin, JVM-unit-testable, no
 * Android types. Malformed documents fail with a safe reason instead of leaking an exception.
 */
class DashPlanException(val safeReason: String) : Exception(safeReason)

object MpdPlanParser {
    private const val MAX_NODES = 20000
    private const val MAX_DEPTH = 32
    private const val MAX_ATTRS = 32
    private const val MAX_ATTRIBUTE_CHARS = 512
    private const val MAX_TEXT_CHARS = 8192
    private const val MAX_TIMELINE_ENTRIES = 8192
    private const val MAX_REPRESENTATIONS_PER_SET = 32

    /** One selection candidate row (display fields mirror MpdCatalog's summaries). */
    data class DashRepresentationOffer(
        val id: String,
        val role: DashTrackRole,
        val codecs: String?,
        val width: Int?,
        val height: Int?,
        val bandwidth: Long?,
        val muxedAudio: Boolean,
        val supported: Boolean,
        val unsupportedReason: String?,
        internal val addressing: SegmentAddressing,
        internal val baseUrl: String,
    ) {
        override fun toString() = "DashRepresentationOffer(height=$height, supported=$supported)"
    }

    /** Parsed static-VOD document: one period, its duration, and selectable offers. */
    class MpdDocument internal constructor(
        internal val mpdUrl: String,
        val durationUs: Long,
        val videoOffers: List<DashRepresentationOffer>,
        /** Default/first supported audio offer of the first usable audio AdaptationSet. */
        val audioOffer: DashRepresentationOffer?,
    ) {
        override fun toString() = "MpdDocument(video=${videoOffers.size}, audio=${audioOffer != null})"
    }

    sealed interface SegmentAddressing {
        /** Effective SegmentTemplate attributes (representation level wins over set level). */
        data class Template(val attrs: Map<String, String>, val timeline: List<TimelineEntry>?) : SegmentAddressing

        data class ListModel(
            val timescale: Long,
            val segmentDurationTicks: Long?,
            val initSourceUrl: String?,
            val initHasRange: Boolean,
            val segmentMediaUrls: List<String>,
            val anyMediaRange: Boolean,
            val timeline: List<TimelineEntry>?,
        ) : SegmentAddressing

        object None : SegmentAddressing
    }

    /** Timeline S entry in timescale ticks; [repeat] of -1 is open-ended until the period end. */
    data class TimelineEntry(val start: Long, val duration: Long, val repeat: Long)

    private data class ExpandedSegment(val startTick: Long, val durationUs: Long)

    // ---------------------------------------------------------------- document tier

    fun parse(mpdText: String, mpdUrl: String): MpdDocument {
        if (mpdText.length > DashBudgets.MAX_MPD_CHARS) throw DashPlanException("DASH 清单超过 2 MiB 上限")
        val root = runCatching { buildTree(mpdText) }.getOrNull()
            ?: throw DashPlanException("DASH 清单无法解析")
        if (root.name != "MPD") throw DashPlanException("文档不是 DASH 清单")
        if (root.attrs["type"]?.lowercase() == "dynamic")
            throw DashPlanException("直播型 DASH 清单不支持下载")
        val periods = root.children.filter { it.name == "Period" }
        if (periods.size != 1) throw DashPlanException("仅支持单一周期点播 DASH 清单")
        val period = periods[0]
        if (root.child("ContentProtection") != null || period.child("ContentProtection") != null)
            throw DashPlanException("加密 DASH 内容不支持下载")
        val periodDurationUs = period.attrs["duration"]?.let(::parseXsDurationUs)
            ?: root.attrs["mediaPresentationDuration"]?.let(::parseXsDurationUs)
            ?: throw DashPlanException("清单未声明总时长，无法确定分片范围")
        if (periodDurationUs !in 1..DashBudgets.MAX_DURATION_US)
            throw DashPlanException("清单时长超出本版范围")

        val mpdBase = singleBase(root, requireHttpUrl(mpdUrl))
        val periodBase = singleBase(period, mpdBase)

        val videoOffers = ArrayList<DashRepresentationOffer>()
        var audioOffer: DashRepresentationOffer? = null
        period.children.filter { it.name == "AdaptationSet" }.forEach { set ->
            when (setKind(set)) {
                DashTrackRole.VIDEO -> videoOffers += setOffers(set, periodBase, DashTrackRole.VIDEO)
                DashTrackRole.AUDIO -> if (audioOffer == null)
                    audioOffer = setOffers(set, periodBase, DashTrackRole.AUDIO).firstOrNull { it.supported }
                null -> {} // text/image/unknown sets are ignored, never silently downloaded
            }
        }
        if (videoOffers.isEmpty()) throw DashPlanException("清单没有可下载的视频档位")
        val sorted = videoOffers.sortedWith(
            compareByDescending<DashRepresentationOffer> { it.height }.thenByDescending { it.bandwidth },
        )
        return MpdDocument(mpdUrl, periodDurationUs, sorted, audioOffer)
    }

    /** Default suggestion mirrors the HLS chooser: highest supported height not above 1080p. */
    fun defaultVideoOffer(offers: List<DashRepresentationOffer>): DashRepresentationOffer? {
        val supported = offers.filter { it.supported }
        return supported.filter { (it.height ?: 0) <= 1080 }.maxByOrNull { it.height ?: 0 }
            ?: supported.maxByOrNull { it.height ?: 0 }
    }

    // ---------------------------------------------------------------- plan tier

    fun buildPlan(document: MpdDocument, video: DashRepresentationOffer): DashDownloadPlan {
        if (!video.supported) throw DashPlanException(video.unsupportedReason ?: "所选档位不在支持范围")
        val audio = document.audioOffer
        val (videoPlan, videoDuration) = resolveRepresentation(video, document.durationUs)
        val audioPlan = audio?.let { resolveRepresentation(it, document.durationUs) }
        if (video.muxedAudio && audioPlan != null) throw DashPlanException("音轨配对与所选档位冲突")
        val duration = minOf(videoDuration, audioPlan?.second ?: Long.MAX_VALUE)
        if (duration !in 1..DashBudgets.MAX_DURATION_US) throw DashPlanException("DASH 时长超出本版范围")
        return DashDownloadPlan(
            entryUrl = document.mpdUrl,
            mpdUrl = document.mpdUrl,
            video = videoPlan,
            audio = if (video.muxedAudio) null else audioPlan?.first,
            muxed = video.muxedAudio,
            durationUs = duration,
        )
    }

    /** Returns the representation plan plus its resolved timeline duration in microseconds. */
    private fun resolveRepresentation(
        offer: DashRepresentationOffer,
        periodDurationUs: Long,
    ): Pair<DashRepresentationPlan, Long> {
        val addressing = offer.addressing
        val initUrl: String
        val segments: List<DashSegmentPlan>
        val timelineTotalUs: Long
        when (addressing) {
            is SegmentAddressing.ListModel -> {
                if (addressing.initHasRange) throw DashPlanException("初始化分片需要字节范围，本版不支持")
                if (addressing.anyMediaRange) throw DashPlanException("分片地址带字节范围，本版不支持")
                if (addressing.initSourceUrl == null) throw DashPlanException("档位缺少初始化分片地址")
                if (addressing.segmentMediaUrls.isEmpty()) throw DashPlanException("档位没有分片地址")
                if (addressing.segmentMediaUrls.size > DashBudgets.MAX_SEGMENTS)
                    throw DashPlanException("分片数量超过 ${DashBudgets.MAX_SEGMENTS} 上限")
                initUrl = resolve(offer.baseUrl, addressing.initSourceUrl)
                val expanded = addressing.timeline?.let {
                    expandTimeline(it, addressing.timescale, periodDurationUs)
                }
                if (expanded != null && expanded.size != addressing.segmentMediaUrls.size)
                    throw DashPlanException("分片列表与时间轴不一致")
                val uniformUs = addressing.segmentDurationTicks?.takeIf { it > 0 }
                    ?.let { ticks -> (ticks * 1_000_000 + addressing.timescale / 2) / addressing.timescale }
                    ?.takeIf { it > 0 }
                segments = addressing.segmentMediaUrls.mapIndexed { index, media ->
                    DashSegmentPlan(resolve(offer.baseUrl, media), expanded?.get(index)?.durationUs ?: uniformUs)
                }
                timelineTotalUs = expanded?.sumOf { it.durationUs }
                    ?: uniformUs?.let { it * addressing.segmentMediaUrls.size }
                    ?: periodDurationUs
            }
            is SegmentAddressing.Template -> {
                val media = addressing.attrs["media"]
                    ?: throw DashPlanException("档位缺少分片地址模板")
                val initTemplate = addressing.attrs["initialization"]
                    ?: throw DashPlanException("档位缺少初始化分片地址")
                val timescale = addressing.attrs["timescale"]?.toLongOrNull() ?: 1L
                if (timescale !in 1..10_000_000L) throw DashPlanException("分片时间刻度无效")
                val startNumber = addressing.attrs["startNumber"]?.toLongOrNull() ?: 1L
                if (startNumber !in 0..Int.MAX_VALUE.toLong()) throw DashPlanException("分片起始编号无效")
                initUrl = resolve(offer.baseUrl, substitute(initTemplate, offer.id, offer.bandwidth, null, null))
                val timeline = addressing.timeline
                if (timeline != null) {
                    val expanded = expandTimeline(timeline, timescale, periodDurationUs)
                    timelineTotalUs = expanded.sumOf { it.durationUs }
                    segments = expanded.mapIndexed { index, expandedSegment ->
                        DashSegmentPlan(
                            resolve(
                                offer.baseUrl,
                                substitute(
                                    media, offer.id, offer.bandwidth,
                                    startNumber + index, expandedSegment.startTick,
                                ),
                            ),
                            expandedSegment.durationUs,
                        )
                    }
                } else {
                    val durationTicks = addressing.attrs["duration"]?.toLongOrNull()
                    if (durationTicks == null || durationTicks <= 0)
                        throw DashPlanException("模板缺少分片时长与时间轴，无法确定分片范围")
                    val segmentUs = durationTicks * 1_000_000 / timescale
                    if (segmentUs <= 0) throw DashPlanException("分片时长无效")
                    val count = ((periodDurationUs + segmentUs - 1) / segmentUs).toInt()
                    if (count < 1) throw DashPlanException("清单时长与分片时长不符")
                    if (count > DashBudgets.MAX_SEGMENTS)
                        throw DashPlanException("分片数量超过 ${DashBudgets.MAX_SEGMENTS} 上限")
                    if (count > 1 && !media.contains("\$Number") && !media.contains("\$Time"))
                        throw DashPlanException("多分片模板缺少编号或时间占位符")
                    timelineTotalUs = periodDurationUs
                    segments = (0 until count).map { index ->
                        DashSegmentPlan(
                            resolve(
                                offer.baseUrl,
                                substitute(media, offer.id, offer.bandwidth, startNumber + index, null),
                            ),
                            segmentUs,
                        )
                    }
                }
            }
            SegmentAddressing.None -> throw DashPlanException(
                if (offer.unsupportedReason != null) offer.unsupportedReason else "档位缺少分片地址",
            )
        }
        // A timeline that disagrees with the declared period duration is a changed source, not noise.
        val tolerance = maxOf(2_000_000L, periodDurationUs / 20)
        if (kotlin.math.abs(timelineTotalUs - periodDurationUs) > tolerance)
            throw DashPlanException("分片时长合计与清单时长不符")
        return DashRepresentationPlan(
            id = offer.id,
            role = offer.role,
            codecs = offer.codecs,
            width = offer.width,
            height = offer.height,
            bandwidth = offer.bandwidth,
            initUrl = initUrl,
            segments = segments,
        ) to if (timelineTotalUs > 0) timelineTotalUs else periodDurationUs
    }

    // ---------------------------------------------------------------- template math

    /**
     * Substitutes $RepresentationID$, $Bandwidth$, $Number$, $Time$, $$ escaping and optional
     * printf-style width tags ($Number%05d$). Any other identifier or format fails honestly.
     */
    internal fun substitute(
        template: String, representationId: String, bandwidth: Long?, number: Long?, time: Long?,
    ): String {
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c != '$') { out.append(c); i++; continue }
            if (i + 1 < template.length && template[i + 1] == '$') { out.append('$'); i += 2; continue }
            val end = template.indexOf('$', i + 1)
            if (end < 0) throw DashPlanException("分片地址模板格式无效")
            val token = template.substring(i + 1, end)
            // ISO 23009-1 introduces the optional format tag with '%'; a ':' separator is tolerated.
            val separator = listOf(token.indexOf('%'), token.indexOf(':')).filter { it >= 0 }.minOrNull()
            val identifier = if (separator != null) token.substring(0, separator) else token
            val formatTag = if (separator != null) token.substring(separator) else null
            val value: String = when (identifier) {
                "RepresentationID" -> representationId
                "Bandwidth" -> bandwidth?.toString()
                    ?: throw DashPlanException("模板需要带宽但档位未声明")
                "Number" -> number?.toString()
                    ?: throw DashPlanException("模板缺少分片编号")
                "Time" -> time?.toString()
                    ?: throw DashPlanException("模板使用时间占位符但缺少时间轴")
                "SubNumber" -> throw DashPlanException("本版不支持子分片编号模板")
                else -> throw DashPlanException("分片地址模板包含未知占位符")
            }
            out.append(applyFormat(value, formatTag, identifier))
            i = end + 1
        }
        return out.toString()
    }

    private fun applyFormat(value: String, tag: String?, identifier: String): String {
        if (tag == null || tag.isEmpty()) return value
        val normalized = tag.removePrefix(":")
        if (identifier !in setOf("Number", "Time", "Bandwidth") ||
            !Regex("%0?[0-9]{0,3}d").matches(normalized)
        ) throw DashPlanException("分片地址模板格式标签无效")
        val number = value.toLongOrNull() ?: throw DashPlanException("分片地址模板格式标签无效")
        return String.format(Locale.ROOT, normalized, number)
    }

    // ---------------------------------------------------------------- timeline math

    /** Expands S entries to bounded segments, resolving inherited starts and open-ended tails. */
    private fun expandTimeline(
        timeline: List<TimelineEntry>,
        timescale: Long,
        periodDurationUs: Long,
    ): List<ExpandedSegment> {
        val expanded = ArrayList<ExpandedSegment>()
        var expectedTick = 0L
        timeline.forEachIndexed { index, entry ->
            if (entry.duration <= 0) throw DashPlanException("时间轴分片时长无效")
            val start = if (entry.start >= 0) entry.start else expectedTick
            if (index == 0 && entry.start > 0) expectedTick = entry.start
            if (start != expectedTick) throw DashPlanException("时间轴不连续")
            var repeats = entry.repeat
            if (repeats < 0) {
                if (index != timeline.lastIndex) throw DashPlanException("开放时间轴条目不在末尾")
                val periodTicks = (periodDurationUs * timescale + 999_999) / 1_000_000
                val fitting = if (periodTicks > start) (periodTicks - start + entry.duration - 1) / entry.duration else 0L
                repeats = (fitting - 1).coerceAtLeast(0)
            }
            if (repeats > DashBudgets.MAX_SEGMENTS)
                throw DashPlanException("分片数量超过 ${DashBudgets.MAX_SEGMENTS} 上限")
            val durationUs = (entry.duration * 1_000_000 + timescale / 2) / timescale
            if (durationUs <= 0) throw DashPlanException("时间轴分片时长无效")
            repeat(repeats.toInt() + 1) { repeatIndex ->
                expanded += ExpandedSegment(start + repeatIndex.toLong() * entry.duration, durationUs)
                if (expanded.size > DashBudgets.MAX_SEGMENTS)
                    throw DashPlanException("分片数量超过 ${DashBudgets.MAX_SEGMENTS} 上限")
            }
            expectedTick = start + (repeats + 1) * entry.duration
        }
        if (expanded.isEmpty()) throw DashPlanException("时间轴没有分片")
        return expanded
    }

    // ---------------------------------------------------------------- URL resolution

    private fun requireHttpUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        if (url.length > DashBudgets.MAX_URL_CHARS || uri == null ||
            uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrEmpty() || uri.rawUserInfo != null
        ) throw DashPlanException("DASH 清单地址不符合安全下载范围")
        return url
    }

    /** RFC-style absolute/relative resolution of [reference] against [base]. */
    internal fun resolve(base: String, reference: String): String {
        val absolute = runCatching {
            val uri = URI(reference)
            uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty()
        }.getOrDefault(false)
        val target = if (absolute) reference
        else runCatching { URI(base).resolve(reference).toString() }.getOrNull()
            ?: throw DashPlanException("分片地址无法解析")
        val uri = runCatching { URI(target) }.getOrNull()
            ?: throw DashPlanException("分片地址无法解析")
        if (target.length > DashBudgets.MAX_URL_CHARS || uri.scheme?.lowercase() !in setOf("http", "https") ||
            uri.host.isNullOrEmpty() || uri.rawUserInfo != null
        ) throw DashPlanException("分片地址不符合安全下载范围")
        return target
    }

    // ---------------------------------------------------------------- codec gating

    internal fun videoUnsupportedReason(codecs: String?): String? {
        val parts = codecs?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            ?: return "档位未标注视频编码，无法确认 H.264"
        parts.forEach { part ->
            when {
                part.startsWith("av01") -> return "AV1 编码本版不支持"
                part.startsWith("hvc1") || part.startsWith("hev1") -> return "HEVC 编码本版不支持"
                part.startsWith("vp09") || part.startsWith("vp9") || part.startsWith("vp08") ->
                    return "VP9/VP8 编码本版不支持"
            }
        }
        return if (parts.any { it.startsWith("avc1") || it.startsWith("avc3") }) null
        else "视频编码不在 H.264 支持范围"
    }

    internal fun audioUnsupportedReason(codecs: String?): String? {
        val parts = codecs?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
            ?: return "音频档位未标注编码，无法确认 AAC"
        val audio = parts.filter { it.startsWith("mp4a") || it.startsWith("ec-3") || it.startsWith("ac-3") }
        if (audio.isEmpty()) return "音频轨不是 AAC 编码"
        if (audio.any { it.startsWith("ec-3") || it.startsWith("ac-3") }) return "音频轨不是 AAC 编码"
        val objectTypes = audio.mapNotNull { it.split('.').getOrNull(2)?.toIntOrNull() }
        if (objectTypes.isNotEmpty() && objectTypes.any { it != 2 }) return "音频不是 AAC-LC 编码"
        return null
    }

    private fun hasAudioCodec(codecs: String?): Boolean {
        val parts = codecs?.split(',')?.map { it.trim().lowercase() } ?: return false
        return parts.any { it.startsWith("mp4a") }
    }

    // ---------------------------------------------------------------- offers

    private fun setOffers(set: DashXmlNode, periodBase: String, role: DashTrackRole): List<DashRepresentationOffer> {
        if (set.child("ContentProtection") != null) return emptyList() // encrypted set is never offered
        val setBase = singleBase(set, periodBase)
        val setTemplateNode = set.child("SegmentTemplate")
        val setTemplate = setTemplateNode?.attrs
        val setList = set.child("SegmentList")
        val setSegmentBase = set.child("SegmentBase") != null
        val inheritedCodecs = set.attrs["codecs"]
        return set.children.filter { it.name == "Representation" }
            .take(MAX_REPRESENTATIONS_PER_SET)
            .map { rep ->
                val codecs = rep.attrs["codecs"] ?: inheritedCodecs
                val repTemplateNode = rep.child("SegmentTemplate")
                val repList = rep.child("SegmentList")
                val hasSegmentBase = setSegmentBase || rep.child("SegmentBase") != null
                val encrypted = rep.child("ContentProtection") != null
                val addressing: SegmentAddressing = when {
                    hasSegmentBase -> SegmentAddressing.None
                    repList != null -> listAddressing(repList)
                    repTemplateNode != null || setTemplateNode != null -> SegmentAddressing.Template(
                        (setTemplateNode?.attrs ?: emptyMap()) + (repTemplateNode?.attrs ?: emptyMap()),
                        timelineOf(repTemplateNode ?: setTemplateNode),
                    )
                    setList != null -> listAddressing(setList)
                    else -> SegmentAddressing.None
                }
                val repBaseText = singleBaseText(rep)
                val base = if (repBaseText != null) resolve(setBase, repBaseText) else setBase
                val codecReason = if (role == DashTrackRole.VIDEO) videoUnsupportedReason(codecs)
                else audioUnsupportedReason(codecs)
                DashRepresentationOffer(
                    id = rep.attrs["id"] ?: "",
                    role = role,
                    codecs = codecs,
                    width = positiveInt(rep.attrs["width"] ?: set.attrs["width"]),
                    height = positiveInt(rep.attrs["height"] ?: set.attrs["height"]),
                    bandwidth = nonNegativeLong(rep.attrs["bandwidth"] ?: set.attrs["bandwidth"]),
                    muxedAudio = role == DashTrackRole.VIDEO && hasAudioCodec(codecs),
                    supported = codecReason == null && rep.attrs["id"] != null && !encrypted &&
                        addressing !is SegmentAddressing.None,
                    unsupportedReason = when {
                        encrypted -> "加密内容不支持下载"
                        rep.attrs["id"] == null -> "档位缺少标识"
                        addressing is SegmentAddressing.None ->
                            if (hasSegmentBase) "indexRange 单文件形式本版不支持" else "档位缺少分片地址"
                        codecReason != null -> codecReason
                        else -> null
                    },
                    addressing = addressing,
                    baseUrl = base,
                )
            }
    }

    private fun listAddressing(list: DashXmlNode): SegmentAddressing.ListModel {
        val init = list.child("Initialization")
        val timescale = list.attrs["timescale"]?.toLongOrNull() ?: 1L
        return SegmentAddressing.ListModel(
            timescale = timescale,
            segmentDurationTicks = list.attrs["duration"]?.toLongOrNull(),
            initSourceUrl = init?.attrs?.get("sourceURL"),
            initHasRange = init?.attrs?.containsKey("range") == true,
            segmentMediaUrls = list.children.filter { it.name == "SegmentURL" }.mapNotNull { it.attrs["media"] },
            anyMediaRange = list.children.any { it.name == "SegmentURL" && it.attrs.containsKey("mediaRange") },
            timeline = timelineOf(list),
        )
    }

    private fun timelineOf(owner: DashXmlNode?): List<TimelineEntry>? {
        val timeline = owner?.child("SegmentTimeline") ?: return null
        val entries = ArrayList<TimelineEntry>()
        timeline.children.filter { it.name == "S" }.forEach { s ->
            if (entries.size >= MAX_TIMELINE_ENTRIES) throw DashPlanException("时间轴条目过多")
            val d = s.attrs["d"]?.toLongOrNull() ?: throw DashPlanException("时间轴缺少分片时长")
            val t = s.attrs["t"]?.toLongOrNull() ?: -1L
            val r = s.attrs["r"]?.toLongOrNull() ?: 0L
            if (d <= 0 || r < -1 || t < -1) throw DashPlanException("时间轴条目无效")
            entries += TimelineEntry(t, d, r)
        }
        if (entries.isEmpty()) throw DashPlanException("时间轴没有分片条目")
        return entries
    }

    private fun setKind(set: DashXmlNode): DashTrackRole? {
        val contentType = set.attrs["contentType"]?.lowercase()
        if (contentType == "video" || contentType == "muxed") return DashTrackRole.VIDEO
        if (contentType == "audio") return DashTrackRole.AUDIO
        val mime = set.attrs["mimeType"]?.substringBefore('/')?.lowercase()
        if (mime == "video") return DashTrackRole.VIDEO
        if (mime == "audio") return DashTrackRole.AUDIO
        val repMime = set.children.firstOrNull { it.name == "Representation" }
            ?.attrs?.get("mimeType")?.substringBefore('/')?.lowercase()
        if (repMime == "video") return DashTrackRole.VIDEO
        if (repMime == "audio") return DashTrackRole.AUDIO
        val codecs = set.attrs["codecs"]
            ?: set.children.firstOrNull { it.name == "Representation" }?.attrs?.get("codecs")
        val parts = codecs?.split(',')?.map { it.trim().lowercase() } ?: return null
        return when {
            parts.any { it.startsWith("avc") } -> DashTrackRole.VIDEO
            parts.any { it.startsWith("mp4a") } -> DashTrackRole.AUDIO
            else -> null
        }
    }

    // ---------------------------------------------------------------- base URLs

    private fun singleBaseText(node: DashXmlNode): String? {
        val bases = node.children.filter { it.name == "BaseURL" }
        if (bases.size > 1) throw DashPlanException("多个 BaseURL 本版不支持")
        return bases.singleOrNull()?.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun singleBase(node: DashXmlNode, parent: String): String =
        singleBaseText(node)?.let { resolve(parent, it) } ?: parent

    // ---------------------------------------------------------------- tolerant tree

    internal class DashXmlNode(val name: String) {
        val attrs = LinkedHashMap<String, String>()
        val children = ArrayList<DashXmlNode>()
        var text = ""
        fun child(name: String) = children.firstOrNull { it.name == name }
        fun descendants(name: String): List<DashXmlNode> {
            val result = ArrayList<DashXmlNode>()
            children.forEach {
                if (it.name == name) result += it
                result += it.descendants(name)
            }
            return result
        }
    }

    private fun buildTree(text: String): DashXmlNode {
        var i = 0
        var nodes = 0
        val stack = ArrayList<DashXmlNode>()
        var root: DashXmlNode? = null
        fun localName(raw: String) = raw.substringAfterLast(':', raw)
        fun unescape(value: String) = if ('&' !in value) value else value
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
        fun appendText(start: Int, end: Int) {
            val parent = stack.lastOrNull() ?: return
            if (parent.text.length >= MAX_TEXT_CHARS) return
            parent.text += text.substring(start, minOf(end, start + MAX_TEXT_CHARS - parent.text.length))
        }
        var textStart = 0
        while (i < text.length) {
            if (text[i] != '<') { i++; continue }
            appendText(textStart, i)
            when {
                i + 1 >= text.length -> i = text.length
                text[i + 1] == '/' -> {
                    var end = i + 2
                    while (end < text.length && !text[end].isWhitespace() && text[end] != '>') end++
                    val name = localName(text.substring(i + 2, end))
                    i = if (end < text.length) end + 1 else text.length
                    while (stack.isNotEmpty()) {
                        val frame = stack.removeAt(stack.lastIndex)
                        if (frame.name == name) break
                    }
                }
                text[i + 1] == '?' -> {
                    val found = text.indexOf("?>", i + 2)
                    i = if (found >= 0) found + 2 else text.length
                }
                text[i + 1] == '!' -> when {
                    text.startsWith("<!--", i) -> {
                        val found = text.indexOf("-->", i + 2)
                        i = if (found >= 0) found + 3 else text.length
                    }
                    text.startsWith("<![CDATA[", i) -> {
                        val found = text.indexOf("]]>", i + 2)
                        i = if (found >= 0) found + 3 else text.length
                    }
                    else -> {
                        val found = text.indexOf('>', i + 2)
                        i = if (found >= 0) found + 1 else text.length
                    }
                }
                else -> {
                    if (++nodes > MAX_NODES) throw DashPlanException("DASH 清单结构过大")
                    var end = i + 1
                    while (end < text.length && !text[end].isWhitespace() && text[end] != '>' && text[end] != '/') end++
                    if (end >= text.length) throw DashPlanException("DASH 清单被截断")
                    val node = DashXmlNode(localName(text.substring(i + 1, end)))
                    i = end
                    var selfClosing = false
                    scan@ while (true) {
                        while (i < text.length && text[i].isWhitespace()) i++
                        when {
                            i >= text.length -> throw DashPlanException("DASH 清单被截断")
                            text[i] == '>' -> { i++; break@scan }
                            text[i] == '/' -> {
                                i++
                                if (i < text.length && text[i] == '>') { i++; selfClosing = true; break@scan }
                            }
                            else -> {
                                val nameStart = i
                                while (i < text.length && text[i] != '=' && !text[i].isWhitespace() &&
                                    text[i] != '>' && text[i] != '/') i++
                                if (i >= text.length) throw DashPlanException("DASH 清单被截断")
                                val attrName = localName(text.substring(nameStart, i))
                                if (text[i] != '=') continue@scan
                                i++
                                if (i >= text.length) throw DashPlanException("DASH 清单被截断")
                                val value = when (val quote = text[i]) {
                                    '"', '\'' -> {
                                        val valueStart = ++i
                                        while (i < text.length && text[i] != quote) i++
                                        if (i >= text.length) throw DashPlanException("DASH 清单被截断")
                                        text.substring(valueStart, i).also { i++ }
                                    }
                                    else -> {
                                        val valueStart = i
                                        while (i < text.length && !text[i].isWhitespace() && text[i] != '>') i++
                                        text.substring(valueStart, i).trimEnd('/')
                                    }
                                }
                                if (node.attrs.size < MAX_ATTRS && attrName.isNotEmpty() &&
                                    value.length <= MAX_ATTRIBUTE_CHARS
                                ) node.attrs[attrName] = unescape(value)
                            }
                        }
                    }
                    val parent = stack.lastOrNull()
                    if (parent != null) parent.children += node else {
                        if (root != null) throw DashPlanException("DASH 清单有多个根元素")
                        root = node
                    }
                    if (!selfClosing) {
                        stack += node
                        if (stack.size > MAX_DEPTH) throw DashPlanException("DASH 清单嵌套过深")
                    }
                }
            }
            textStart = i
        }
        return root ?: throw DashPlanException("DASH 清单没有根元素")
    }

    // ---------------------------------------------------------------- helpers

    internal fun parseXsDurationUs(value: String): Long? {
        val match = Regex(
            "^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([0-9]+(?:\\.[0-9]{1,3})?)S)?)?$",
        ).matchEntire(value.trim()) ?: return null
        val (days, hours, minutes, seconds) = match.destructured
        if (days.isEmpty() && hours.isEmpty() && minutes.isEmpty() && seconds.isEmpty()) return null
        val totalSeconds = (days.toLongOrNull() ?: 0L) * 86400 + (hours.toLongOrNull() ?: 0L) * 3600 +
            (minutes.toLongOrNull() ?: 0L) * 60 + (seconds.toDoubleOrNull() ?: 0.0)
        if (totalSeconds < 0 || totalSeconds > 10.0 * DashBudgets.MAX_DURATION_US / 1_000_000) return null
        return Math.round(totalSeconds * 1_000_000.0)
    }

    private fun positiveInt(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }
    private fun nonNegativeLong(value: String?): Long? = value?.toLongOrNull()?.takeIf { it >= 0 }
}

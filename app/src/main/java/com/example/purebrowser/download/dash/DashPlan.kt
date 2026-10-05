package com.example.purebrowser.download.dash

import com.example.purebrowser.download.RequestPolicy

/**
 * T96/T97 DASH plan model. Static-VOD, unencrypted, H.264 + AAC-LC only; segment addressing is
 * SegmentTemplate ($Number$/$Time$) or SegmentList (SegmentURL). URL-bearing snapshots override
 * toString so no signed address is ever printed. Byte/segment caps live in [DashBudgets] and are
 * shared by the resolver, the fetcher and the transfer so the manifest tier and the transfer tier
 * enforce one merged budget contract.
 */
enum class DashTrackRole { VIDEO, AUDIO }

object DashBudgets {
    /** Mirrors MpdCatalog.MAX_MPD_CHARS / HlsHttpClient manifest caps. */
    const val MAX_MPD_CHARS = 2 * 1024 * 1024
    /** Per-representation segment listing bound (plan contract: bounded, no open-ended lists). */
    const val MAX_SEGMENTS = 4096
    /** Mirrors the HLS per-segment cap. */
    const val MAX_SEGMENT_BYTES = 128L * 1024 * 1024
    /** Mirrors the HLS cumulative transfer ledger cap. */
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    /** Matches DownloadRecord.plannedDurationUs bounds. */
    const val MAX_DURATION_US = 86_400_000_000L
    const val MAX_URL_CHARS = 8192
}

data class DashSegmentPlan(val url: String, val durationUs: Long?) {
    override fun toString() = "DashSegmentPlan(durationUs=$durationUs)"
}

data class DashRepresentationPlan(
    val id: String,
    val role: DashTrackRole,
    val codecs: String?,
    val width: Int?,
    val height: Int?,
    val bandwidth: Long?,
    val initUrl: String,
    val segments: List<DashSegmentPlan>,
) {
    val segmentDurationUs: Long? get() = segments.firstOrNull()?.let { first ->
        if (segments.all { it.durationUs == first.durationUs }) first.durationUs else null
    }

    override fun toString() = "DashRepresentationPlan(segments=${segments.size})"
}

/**
 * A video Representation plus (when the MPD splits tracks) the paired default audio
 * Representation, or one muxed Representation carrying both tracks. The transfer assembles each
 * representation with [Fmp4SegmentAssembler] and merges video+audio via the existing
 * [com.example.purebrowser.download.mux.DualTrackMuxer]; a muxed representation is assembled
 * straight to the staged MP4.
 */
data class DashDownloadPlan(
    val entryUrl: String,
    val mpdUrl: String,
    val video: DashRepresentationPlan,
    val audio: DashRepresentationPlan?,
    val muxed: Boolean,
    val durationUs: Long,
) {
    init {
        // muxed means one representation carries both tracks; otherwise audio==null is an honest
        // video-only plan (no usable audio AdaptationSet), never a silent downgrade of a pair.
        require(!muxed || audio == null) { "DASH 方案的音轨配对不一致" }
    }

    val totalSegments: Int get() = video.segments.size + (audio?.segments?.size ?: 0)

    fun validate(allowLocalHttp: Boolean) {
        require(durationUs in 1..DashBudgets.MAX_DURATION_US) { "DASH 时长超出本版范围" }
        listOf(video, audio).filterNotNull().forEach { representation ->
            require(representation.segments.size in 1..DashBudgets.MAX_SEGMENTS) { "DASH 分片数量超出上限" }
            RequestPolicy.validateUrl(representation.initUrl, allowLocalHttp)
            representation.segments.forEach { RequestPolicy.validateUrl(it.url, allowLocalHttp) }
        }
        RequestPolicy.validateUrl(entryUrl, allowLocalHttp)
        RequestPolicy.validateUrl(mpdUrl, allowLocalHttp)
    }

    override fun toString() = "DashDownloadPlan(segments=$totalSegments, muxed=$muxed)"
}

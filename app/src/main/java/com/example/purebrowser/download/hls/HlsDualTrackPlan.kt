package com.example.purebrowser.download.hls

import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.download.site.DualTrackDownloadPlan
import com.example.purebrowser.download.site.DualTrackMetadata
import com.example.purebrowser.download.site.DualTrackTaskPlan
import java.security.MessageDigest

/**
 * The v0.1.9 separate-audio HLS dual-track declaration: each track is an ORDERED TS SEGMENT LIST
 * (variant media playlist ＋ audio-rendition media playlist), not closed-range byte chunks of two
 * complete MP4s. It rides the existing dual-track lease/cleanup semantics: one use, no resume
 * bytes, honest re-parse on any interruption. Segment format is declared per track and the
 * [SegmentFormat] seam keeps fMP4 out of transfer until the fMP4 assembly foundation lands.
 *
 * Track URLs (including signatures) live only in memory; identity is hashed before persistence.
 */
class HlsDualTrackPlan(
    override val resourceId: String,
    override val videoUrl: String,
    override val audioUrl: String,
    val videoSegments: List<HlsSegment>,
    val audioSegments: List<HlsSegment>,
    val videoDurationUs: Long,
    val audioDurationUs: Long,
    val videoCodec: String,
    val audioCodec: String,
    val language: String?,
    val channels: String?,
    override val durationUs: Long,
    override val safeSourceUrl: String? = null,
) : DualTrackTaskPlan {
    init {
        require(videoUrl != audioUrl) { "双轨需要不同的清单地址" }
        listOf(videoSegments, audioSegments).forEach { segments ->
            require(segments.size in 1..HlsPlaylistParser.MAX_SEGMENTS) { "分轨分片数量无效" }
            segments.forEachIndexed { index, segment ->
                require(index == segment.index && segment.durationUs in 1..HlsPlaylistParser.MAX_DURATION_US) { "分轨分片声明无效" }
            }
        }
        listOf(videoDurationUs, audioDurationUs, durationUs).forEach {
            require(it in 1..DualTrackMetadata.MAX_DURATION_US) { "分轨时长超出预算" }
        }
        metadata().validate()
        DualTrackDownloadPlan.validateSafeSourceUrl(safeSourceUrl)
    }

    override fun validate(allowLocalHttp: Boolean) {
        RequestPolicy.validateUrl(videoUrl, allowLocalHttp)
        RequestPolicy.validateUrl(audioUrl, allowLocalHttp)
        (videoSegments + audioSegments).forEach { RequestPolicy.validateUrl(it.url, allowLocalHttp) }
    }

    override fun metadata() = DualTrackMetadata(
        identityHash = sha(resourceId), videoFormatHash = sha("$videoUrl|$videoCodec"),
        audioFormatHash = sha("$audioUrl|$audioCodec"),
        videoCodec = videoCodec, audioCodec = audioCodec,
        videoLength = null, audioLength = null, durationUs = durationUs,
    )

    override fun toString() = "HlsDualTrackPlan(videoSegments=${videoSegments.size}, audioSegments=${audioSegments.size}, credentials=redacted)"

    companion object {
        /** Maps a prepared dual-track HLS plan onto the transfer-level declaration. */
        fun from(plan: HlsDownloadPlan, safeSourceUrl: String? = null): HlsDualTrackPlan {
            val audio = plan.audio ?: throw IllegalArgumentException("清单没有独立音轨")
            // DualTrackMetadata only accepts avc1 profile ids; any other H.264 token maps to "h264".
            val codecs = plan.variant?.codecs?.split(',')?.map { it.trim() }
            val videoCodec = codecs?.firstOrNull { Regex("avc1\\.[a-fA-F0-9]{6}").matches(it) } ?: "h264"
            val audioCodec = codecs?.firstOrNull { it == "mp4a.40.2" } ?: "aac"
            return HlsDualTrackPlan(
                resourceId = "hls-dual-${sha("${plan.entryUrl}|${plan.playlistUrl}|${audio.playlistUrl}").take(48)}",
                videoUrl = plan.playlistUrl, audioUrl = audio.playlistUrl,
                videoSegments = plan.media.segments, audioSegments = audio.media.segments,
                videoDurationUs = plan.media.durationUs, audioDurationUs = audio.media.durationUs,
                videoCodec = videoCodec, audioCodec = audioCodec,
                language = audio.rendition.language, channels = audio.rendition.channels,
                durationUs = plan.media.durationUs, safeSourceUrl = safeSourceUrl,
            )
        }

        private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

package com.example.purebrowser.download.hls

import java.util.Collections

/** Metadata is only a selection hint; supported still requires TS/H.264/AAC content verification. */
data class HlsVariant(
    val url: String,
    val bandwidth: Long?,
    val width: Int?,
    val height: Int?,
    val codecs: String?,
    val supported: Boolean,
    val unsupportedReason: String?,
    /** EXT-X-MEDIA AUDIO group this variant references; renditions live on the master playlist. */
    val audioGroup: String? = null,
) {
    // Never print a signed address, codec string, or caller-supplied diagnostic.
    override fun toString() = "HlsVariant(url=<redacted>, bandwidth=$bandwidth, width=$width, height=$height, supported=$supported)"
}

/** One #EXT-X-MEDIA TYPE=AUDIO declaration; uri==null means audio is muxed into the variant stream. */
data class HlsAudioRendition(
    val uri: String?,
    val groupId: String,
    val name: String,
    val language: String?,
    val channels: String?,
    val isDefault: Boolean,
) {
    override fun toString() = "HlsAudioRendition(groupId=$groupId, name=$name, language=$language, channels=$channels, default=$isDefault, uri=${if (uri == null) "muxed" else "<redacted>"})"
}

data class HlsSegment(val url: String, val durationUs: Long, val index: Int) {
    override fun toString() = "HlsSegment(url=<redacted>, durationUs=$durationUs, index=$index)"
}

/**
 * One #EXT-X-MAP declaration (RFC 8216 §4.3.2.5): the fMP4 init segment address of a media
 * playlist and its optional byte window. [byteLength]==null means the whole resource; otherwise
 * the init occupies the exact closed range [byteOffset, byteOffset+byteLength-1] and transfer
 * fetches it with one precise closed-interval Range request, never an open-ended or sliced guess.
 */
data class HlsInitSegment(
    val url: String,
    val byteOffset: Long = 0L,
    val byteLength: Long? = null,
) {
    init {
        require(byteOffset >= 0L) { "初始化段字节范围无效" }
        require(byteLength == null || byteLength > 0L) { "初始化段字节范围无效" }
    }

    /** The exact closed byte window, or null when the whole resource is the init segment. */
    fun closedByteRange(): LongRange? = byteLength?.let { byteOffset..byteOffset + it - 1L }

    // Never print a signed address; the byte window carries no secret.
    override fun toString() = "HlsInitSegment(url=<redacted>, byteOffset=$byteOffset, byteLength=$byteLength)"
}

sealed interface HlsPlaylist {
    /** Snapshot the input, including when constructed outside the parser. */
    class Master(variants: List<HlsVariant>, audioRenditions: List<HlsAudioRendition> = emptyList()) : HlsPlaylist {
        val variants: List<HlsVariant> = Collections.unmodifiableList(ArrayList(variants))
        val audioRenditions: List<HlsAudioRendition> = Collections.unmodifiableList(ArrayList(audioRenditions))
        override fun equals(other: Any?) = other is Master && variants == other.variants && audioRenditions == other.audioRenditions
        override fun hashCode() = 31 * variants.hashCode() + audioRenditions.hashCode()
        override fun toString() = "HlsPlaylist.Master(variantCount=${variants.size}, audioRenditions=${audioRenditions.size})"
    }

    class Media(segments: List<HlsSegment>, val durationUs: Long, val targetDurationUs: Long, val mediaSequence: Long = 0,
        val format: SegmentFormat = SegmentFormat.MPEG_TS, val initSegment: HlsInitSegment? = null) : HlsPlaylist {
        val segments: List<HlsSegment> = Collections.unmodifiableList(ArrayList(segments))
        override fun equals(other: Any?) = other is Media && segments == other.segments && durationUs == other.durationUs &&
            targetDurationUs == other.targetDurationUs && mediaSequence == other.mediaSequence && format == other.format &&
            initSegment == other.initSegment
        override fun hashCode() = 31 * (31 * (31 * (31 * (31 * segments.hashCode() + durationUs.hashCode()) + targetDurationUs.hashCode()) + mediaSequence.hashCode()) + format.hashCode()) + initSegment.hashCode()
        override fun toString() = "HlsPlaylist.Media(segmentCount=${segments.size}, durationUs=$durationUs, targetDurationUs=$targetDurationUs, format=$format)"
    }
}

/** Parser-created reasons are fixed safe text: no input interpolation and no URI exception cause. */
class HlsParseException(val safeReason: String) : Exception(safeReason) {
    override fun toString() = "HlsParseException: $safeReason"
}

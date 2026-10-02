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
) {
    // Never print a signed address, codec string, or caller-supplied diagnostic.
    override fun toString() = "HlsVariant(url=<redacted>, bandwidth=$bandwidth, width=$width, height=$height, supported=$supported)"
}

data class HlsSegment(val url: String, val durationUs: Long, val index: Int) {
    override fun toString() = "HlsSegment(url=<redacted>, durationUs=$durationUs, index=$index)"
}

sealed interface HlsPlaylist {
    /** Snapshot the input, including when constructed outside the parser. */
    class Master(variants: List<HlsVariant>) : HlsPlaylist {
        val variants: List<HlsVariant> = Collections.unmodifiableList(ArrayList(variants))
        override fun equals(other: Any?) = other is Master && variants == other.variants
        override fun hashCode() = variants.hashCode()
        override fun toString() = "HlsPlaylist.Master(variantCount=${variants.size})"
    }

    class Media(segments: List<HlsSegment>, val durationUs: Long, val targetDurationUs: Long) : HlsPlaylist {
        val segments: List<HlsSegment> = Collections.unmodifiableList(ArrayList(segments))
        override fun equals(other: Any?) = other is Media && segments == other.segments &&
            durationUs == other.durationUs && targetDurationUs == other.targetDurationUs
        override fun hashCode() = 31 * (31 * segments.hashCode() + durationUs.hashCode()) + targetDurationUs.hashCode()
        override fun toString() = "HlsPlaylist.Media(segmentCount=${segments.size}, durationUs=$durationUs, targetDurationUs=$targetDurationUs)"
    }
}

/** Parser-created reasons are fixed safe text: no input interpolation and no URI exception cause. */
class HlsParseException(val safeReason: String) : Exception(safeReason) {
    override fun toString() = "HlsParseException: $safeReason"
}

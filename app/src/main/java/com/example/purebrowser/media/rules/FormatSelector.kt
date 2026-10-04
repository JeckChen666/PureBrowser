package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary

/**
 * One normalized playback address produced by a rule action (T84). Field semantics follow 开源
 * yt-dlp 行为规格转写: `url`/`ext`/`width`/`height`/`tbr` plus the local kind tag; `tbr` is total
 * bitrate in kbit/s. Pure data — no transport, no headers, no execution.
 */
data class FormatEntry(
    val url: String,
    val height: Int? = null,
    val width: Int? = null,
    val ext: String? = null,
    val tbr: Long? = null,
    val kind: MediaKind = MediaKind.UNKNOWN,
)

/**
 * User preference for variant selection. The T87 UI wires these inputs; this object ships the pure
 * selection logic now so the wiring stays a data-path change.
 */
sealed interface FormatPreference {
    /** Highest quality under the canonical ordering. */
    object Best : FormatPreference
    /** A requested picture height; see [FormatSelector.select] for the fallback ladder. */
    data class FixedHeight(val height: Int) : FormatPreference
    /** A requested container; matching entries then order by the canonical rules. */
    data class FixedExt(val ext: String) : FormatPreference
}

/**
 * Pure format ordering and selection (T84, T87 input). Ordering rules transcribed from the
 * referenced open-source behavior spec, not its code: resolution first (height, width tiebreak),
 * then bitrate; equal quality prefers the mp4 container over webm over ts, and a direct https
 * address over one derived from a manifest; the address itself is the final stable tiebreak so
 * ordering is deterministic and testable.
 */
object FormatSelector {
    /** Container preference rank, lower is better. */
    fun containerRank(ext: String?): Int {
        if (ext == null) return 9
        return when (ext.lowercase()) {
            "mp4" -> 0
            "webm" -> 1
            "ts" -> 2
            else -> 5
        }
    }

    /** Protocol preference rank, lower is better: https-native direct files over manifest-derived entries. */
    fun protocolRank(kind: MediaKind): Int = when (kind) {
        MediaKind.FILE -> 0
        MediaKind.HLS, MediaKind.DASH -> 1
        else -> 2
    }

    /** Canonical deterministic ordering of [formats]; never mutates or deduplicates the input. */
    fun order(formats: List<FormatEntry>): List<FormatEntry> = formats.sortedWith(
        compareByDescending<FormatEntry> { it.height ?: -1 }
            .thenByDescending { it.width ?: -1 }
            .thenByDescending { it.tbr ?: -1L }
            .thenBy { containerRank(it.ext) }
            .thenBy { protocolRank(it.kind) }
            .thenBy { it.url },
    )

    /**
     * Picks the entry matching [preference]. [FormatPreference.FixedHeight] resolves an exact
     * height first, then the best height not above the request, then (only when every entry is
     * taller) the smallest available height — never silently returning a null list's first element.
     */
    fun select(formats: List<FormatEntry>, preference: FormatPreference): FormatEntry? = when (preference) {
        FormatPreference.Best -> order(formats).firstOrNull()
        is FormatPreference.FixedHeight -> {
            val ordered = order(formats)
            ordered.firstOrNull { it.height == preference.height }
                ?: ordered.filter { it.height != null && it.height <= preference.height }.firstOrNull()
                ?: ordered.filter { it.height != null }.minByOrNull { it.height!! }
        }
        is FormatPreference.FixedExt -> {
            val wanted = preference.ext.lowercase()
            order(formats.filter { it.ext?.lowercase() == wanted }).firstOrNull()
        }
    }

    /**
     * Normalizes entries into the surface-level [VariantSummary] shape that fills
     * `MediaCandidate.variants` (T87 does the wiring): bandwidth is tbr expressed in bit/s
     * (tbr × 1000), codecs stay unknown at this tier, and no warning is attached here.
     */
    fun toVariantSummaries(formats: List<FormatEntry>): List<VariantSummary> =
        order(formats).map { format ->
            VariantSummary(
                height = format.height,
                bandwidth = format.tbr?.let { it * 1000 },
                codecs = null,
                url = format.url,
                warning = null,
            )
        }
}

package com.example.purebrowser.media.verify

import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary
import java.util.Locale

/** Outcome of one anonymous, bounded auto-verification request. */
sealed interface ProbeResult {
    /**
     * The URL answered as media. [totalBytes] comes from a 206 Content-Range total; a plain 200
     * keeps it null. [resumable] reflects range support actually observed. [variants] is only set
     * when a master playlist was fetched and parsed; [playlistWarning] carries the parser's fixed
     * safe reason when that playlist had to be rejected. [pageUrl] is the page whose session the
     * probe ran with; it is metadata for consent gating and stays out of every diagnostic.
     */
    data class Verified(
        val totalBytes: Long?,
        val resumable: Boolean,
        val mime: String?,
        val kindHint: MediaKind?,
        val variants: List<VariantSummary>? = null,
        val playlistWarning: String? = null,
        val pageUrl: String? = null,
    ) : ProbeResult {
        override fun toString() = "Verified(totalBytes=$totalBytes, resumable=$resumable, mime=$mime, " +
            "kindHint=$kindHint, variants=${variants?.size}, warned=${playlistWarning != null})"
    }
    data object NotMedia : ProbeResult
    data object Unreachable : ProbeResult
}

/** Pure header interpretation; no IO, so probe semantics stay unit-testable. */
object ProbeInterpreter {
    fun interpret(status: Int, header: (String) -> String?): ProbeResult {
        if (status !in setOf(200, 206)) return ProbeResult.Unreachable
        val mime = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val kindHint = when {
            mime == null -> null
            mime.contains("mpegurl") -> MediaKind.HLS
            mime == "application/dash+xml" -> MediaKind.DASH
            mime.startsWith("video/") -> MediaKind.FILE
            else -> null
        }
        if (kindHint == null) return ProbeResult.NotMedia
        return if (status == 206) {
            val total = contentRangeTotal(header("Content-Range"))
            // A 206 proves the server honors ranges even when the total is absent or unparsable.
            ProbeResult.Verified(total, resumable = true, mime = mime, kindHint = kindHint)
        } else {
            ProbeResult.Verified(
                totalBytes = null,
                resumable = header("Accept-Ranges")?.trim()?.lowercase(Locale.ROOT) == "bytes",
                mime = mime,
                kindHint = kindHint,
            )
        }
    }

    /** "bytes 0-0/12345" -> 12345; anything else (including "*") yields no total. */
    fun contentRangeTotal(raw: String?): Long? {
        val match = Regex("bytes [0-9]+-[0-9]+/([0-9]+)").matchEntire(raw.orEmpty().trim()) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }
}

package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaClassifier
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary
import java.net.URI

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

/**
 * One selectable row of the T87 chooser. `MediaCandidate.variants` stores only the transport-level
 * [VariantSummary] shape, so the chooser rebuilds the richer per-row facts (container, kind) from
 * the variant URL itself — the same derivation `RuleEngine.formatFromUrl` uses at extraction time.
 * Pure data; no requests are made for rows that are only rendered.
 */
data class FormatChoiceRow(
    val url: String,
    val height: Int?,
    val ext: String?,
    val tbr: Long?,
    val kind: MediaKind,
) {
    internal val entry: FormatEntry get() = FormatEntry(url = url, height = height, ext = ext, tbr = tbr, kind = kind)
}

/**
 * Pure list-building and preference→row decisions for the rules-format chooser (T87). Selection
 * itself DELEGATES to [FormatSelector.select] so the canonical ordering and fallback ladder stay
 * single-sourced; this object only rebuilds rows, dedups the height/container pickers and formats
 * labels. No IO, no store, fully JVM-testable.
 */
object FormatChoice {
    private val EXT = Regex("\\.([a-z0-9]{1,5})$")

    /** Rebuilds chooser rows from a candidate's variants, canonically ordered, never mutating input. */
    fun rows(variants: List<VariantSummary>): List<FormatChoiceRow> {
        if (variants.isEmpty()) return emptyList()
        val rows = variants.map { variant ->
            val ext = extOf(variant.url)
            val kind = MediaClassifier.classify(variant.url)
                ?: if (ext != null) MediaKind.FILE else MediaKind.UNKNOWN
            FormatChoiceRow(
                url = variant.url,
                height = variant.height,
                ext = ext,
                tbr = variant.bandwidth?.let { it / 1000 },
                kind = kind,
            )
        }
        return FormatSelector.order(rows.map { it.entry }).map { entry -> rows.first { it.url == entry.url } }
    }

    /** Deduped selectable heights, best first; null heights never populate the picker. */
    fun heights(rows: List<FormatChoiceRow>): List<Int> =
        rows.mapNotNull { it.height }.distinct().sortedDescending()

    /** Selectable containers present among the rows, in canonical preference order (mp4/webm/ts). */
    fun containers(rows: List<FormatChoiceRow>): List<String> {
        val present = rows.mapNotNull { it.ext?.lowercase() }.toSet()
        return listOf("mp4", "webm", "ts").filter { it in present }
    }

    /**
     * One row label, e.g. "1080p · mp4 · 3500kbps". Missing facts drop their segment instead of
     * being invented; a row with nothing to say stays honest about it.
     */
    fun label(row: FormatChoiceRow): String {
        val parts = listOfNotNull(
            row.height?.let { "${it}p" },
            row.ext?.lowercase(),
            row.tbr?.takeIf { it > 0 }?.let { "${it}kbps" },
        )
        return if (parts.isEmpty()) "未知格式" else parts.joinToString(" · ")
    }

    /** The row [FormatSelector.select] picks for [preference]; delegates ordering and fallbacks. */
    fun select(rows: List<FormatChoiceRow>, preference: FormatPreference): FormatChoiceRow? {
        if (rows.isEmpty()) return null
        val entries = rows.map { it.entry }
        val picked = FormatSelector.select(entries, preference) ?: return null
        return rows[entries.indexOf(picked)]
    }

    /** Leaf path extension, lowercased and length-bounded; null when the address has none. */
    private fun extOf(url: String): String? = runCatching {
        val leaf = URI(url).path.orEmpty().substringAfterLast('/')
        EXT.find(leaf)?.groupValues?.get(1)?.lowercase()
    }.getOrNull()
}

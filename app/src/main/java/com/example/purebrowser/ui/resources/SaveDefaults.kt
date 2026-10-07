package com.example.purebrowser.ui.resources

import com.example.purebrowser.download.DownloadRules
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary
import com.example.purebrowser.media.rules.FormatChoice
import com.example.purebrowser.media.rules.FormatPreference

/**
 * T117 pure default rules for the single-screen save flow: the preselected variant over the
 * detect-and-parse summaries (mirroring the parser's ≤1080p-best semantics), the best rules
 * format for quick-save, and the prefilled file names. No IO and no state — JVM-testable; after
 * a manifest read the parser's own default stays authoritative.
 */
internal object SaveDefaults {
    /** Best clean variant at or below 1080p; warned variants qualify only when no clean one exists. */
    fun defaultVariant(variants: List<VariantSummary>): VariantSummary? {
        if (variants.isEmpty()) return null
        val clean = variants.filter { it.warning == null }
        return pick(clean.ifEmpty { variants })
    }

    /** ≤1080p best by height then bandwidth; all-taller lists take the smallest; unknowns stay first. */
    private fun pick(rows: List<VariantSummary>): VariantSummary? {
        val inRange = rows.filter { (it.height ?: 0) in 1..1080 }
        if (inRange.isNotEmpty()) return inRange.maxWithOrNull(compareBy({ it.height ?: 0 }, { it.bandwidth ?: -1L }))
        val known = rows.filter { it.height != null }
        if (known.isNotEmpty()) return known.minWithOrNull(compareBy({ it.height ?: Int.MAX_VALUE }, { it.bandwidth ?: Long.MAX_VALUE }))
        return rows.firstOrNull()
    }

    /** Prefill stem: title-first for rule/site discoveries, URL-derived otherwise (shared helper). */
    /**
     * Quick-save resolution: rules candidates with variant rows pick the canonical Best row and
     * surface it as the saved address; manifest candidates keep their own URL (variant chosen later
     * by the transfer); everything else passes through unchanged.
     */
    fun defaultFormatCandidate(candidate: MediaCandidate): MediaCandidate {
        val variants = candidate.variants ?: return candidate
        if (com.example.purebrowser.media.Evidence.RULE !in candidate.sources) return candidate
        if (candidate.kind == com.example.purebrowser.media.MediaKind.HLS ||
            candidate.kind == com.example.purebrowser.media.MediaKind.DASH) return candidate
        val rows = FormatChoice.rows(variants)
        if (rows.size < 2) return candidate
        val best = FormatChoice.select(rows, FormatPreference.Best) ?: return candidate
        if (best.url == candidate.url) return candidate
        return candidate.copy(
            url = best.url,
            kind = best.kind,
            variants = null,
            title = candidate.title?.takeIf { Regex("^\\d{2,4}[pi]?$").matches(it) } ?: best.height?.let { "${it}p" } ?: candidate.title,
        )
    }

    fun saveNameSuggestion(candidate: MediaCandidate): String {
        val title = candidate.title?.takeIf { it.isNotBlank() }?.take(100)
        if (title != null && (Evidence.SITE in candidate.sources || Evidence.RULE in candidate.sources)) {
            return DownloadRules.safeFileName("$title.mp4")
        }
        return suggestedFileName(candidate)
    }
}

/** Prefill with the picked quality, extension preserved; already-suffixed or unknown stays as-is. */
internal fun qualitySuffixedName(base: String, height: Int?): String {
    val marker = height?.takeIf { it > 0 } ?: return base
    val dot = base.lastIndexOf('.')
    val stem = if (dot > 0) base.substring(0, dot) else base
    if (stem.endsWith("-${marker}p")) return base
    val ext = if (dot > 0) base.substring(dot) else ""
    return "$stem-${marker}p$ext"
}

/** Reserve the .mp4 extension within the repository's UTF-8/character limits, even for edited names. */
internal fun mp4SaveName(value: String): String {
    var stem = DownloadRules.safeFileName(value.substringBeforeLast('.', value))
    while (stem.length > 96 || stem.toByteArray(Charsets.UTF_8).size > 236) {
        stem = stem.dropLast(Character.charCount(stem.codePointBefore(stem.length)))
    }
    return "$stem.mp4"
}

package com.example.purebrowser.media

/** T76 candidate ranking weighting: pure, side-effect-free scoring used by ResourceSniffer as a
 * tiebreaker WITHIN one evidence tier — never across tiers, so DOM evidence still beats REQUEST
 * evidence and a playing DOM element still leads regardless of points. The published order is
 * evidenceTier descending, then primarySignal descending, then verified descending.
 *
 * primarySignal points are bounded, additive, and absent fields always score nothing:
 *  - +[VARIANT_POINTS] when parsed variants exist and the first variant declares height >= 480
 *    (a master manifest whose ladder starts at least at 480p — correct-but-not-main previews and
 *    library snippets rarely have one);
 *  - +[LENGTH_POINTS] when the declared/verified length (totalBytes, else sizeBytes) lies in
 *    [MIN_PRIMARY_BYTES, MAX_PRIMARY_BYTES] — the plausible full-movie window; 57 KB library
 *    files, small previews and absurd declarations get nothing;
 *  - +[TITLE_POINTS] when the title is a bare quality label (e.g. 1080p, 720, 480i);
 *  - +[PLAYING_POINTS] while the candidate is the playing element (existing behavior, kept as a
 *    within-tier nudge in addition to the DOM-playing tier).
 */
object RankWeight {
    const val VARIANT_POINTS = 3
    const val LENGTH_POINTS = 2
    const val TITLE_POINTS = 1
    const val PLAYING_POINTS = 2
    const val MIN_PRIMARY_BYTES = 5L * 1024 * 1024
    const val MAX_PRIMARY_BYTES = 5L * 1024 * 1024 * 1024
    private val QUALITY_LABEL = Regex("^\\d{2,4}[pi]?$")

    /** The pre-existing publish() evidence tier, factored verbatim and renumbered for Evidence.RULE:
     * 5 playing DOM, 4 DOM, 3 RULE (site rules sit between DOM and DOWNLOAD), 2 DOWNLOAD,
     * 1 everything else. Tiers stay the primary comparator; points never cross them. */
    fun evidenceTier(candidate: MediaCandidate): Int = when {
        candidate.playing && Evidence.DOM in candidate.sources -> 5
        Evidence.DOM in candidate.sources -> 4
        Evidence.RULE in candidate.sources -> 3
        Evidence.DOWNLOAD in candidate.sources -> 2
        else -> 1
    }

    fun primarySignal(candidate: MediaCandidate): Int {
        var points = 0
        val variants = candidate.variants
        if (!variants.isNullOrEmpty() && (variants.first().height ?: 0) >= 480) points += VARIANT_POINTS
        val length = candidate.totalBytes ?: candidate.sizeBytes
        if (length != null && length in MIN_PRIMARY_BYTES..MAX_PRIMARY_BYTES) points += LENGTH_POINTS
        val title = candidate.title
        if (title != null && QUALITY_LABEL.matches(title)) points += TITLE_POINTS
        if (candidate.playing) points += PLAYING_POINTS
        return points
    }
}

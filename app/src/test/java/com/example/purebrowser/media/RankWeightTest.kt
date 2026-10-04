package com.example.purebrowser.media

import org.junit.Assert.*
import org.junit.Test

/** Pure JVM tests for the T76 primary-signal weighting; no Android types involved. */
class RankWeightTest {
    private fun candidate(
        variants: List<VariantSummary>? = null, totalBytes: Long? = null, sizeBytes: Long? = null,
        title: String? = null, playing: Boolean = false,
        sources: Set<Evidence> = setOf(Evidence.REQUEST), download: Boolean = false,
    ) = MediaCandidate("https://cdn.example/media", MediaKind.FILE,
        if (download) setOf(Evidence.DOWNLOAD) else sources, sizeBytes = sizeBytes,
        title = title, playing = playing, totalBytes = totalBytes, variants = variants)
    private fun variant(height: Int?) = VariantSummary(height, 800_000L, null, "https://cdn.example/gear.m3u8", null)

    @Test fun evidenceTierMatchesThePreviousPublishOrdering() {
        assertEquals(5, RankWeight.evidenceTier(candidate(playing = true, sources = setOf(Evidence.DOM))))
        assertEquals(4, RankWeight.evidenceTier(candidate(sources = setOf(Evidence.REQUEST, Evidence.DOM))))
        assertEquals(2, RankWeight.evidenceTier(candidate(download = true)))
        assertEquals(1, RankWeight.evidenceTier(candidate()))
        // Playing without DOM evidence does not reach the playing-DOM tier (existing semantics).
        assertEquals(1, RankWeight.evidenceTier(candidate(playing = true)))
    }

    @Test fun ruleEvidenceRanksBetweenDomAndDownloadTiers() {
        assertEquals(3, RankWeight.evidenceTier(candidate(sources = setOf(Evidence.RULE))))
        assertEquals(3, RankWeight.evidenceTier(candidate(sources = setOf(Evidence.REQUEST, Evidence.RULE))))
        // DOWNLOAD never overtakes RULE, and DOM still outranks RULE.
        assertTrue(RankWeight.evidenceTier(candidate(sources = setOf(Evidence.DOWNLOAD, Evidence.RULE))) >
            RankWeight.evidenceTier(candidate(download = true)))
        assertTrue(RankWeight.evidenceTier(candidate(sources = setOf(Evidence.DOM))) >
            RankWeight.evidenceTier(candidate(sources = setOf(Evidence.RULE))))
    }

    @Test fun variantBonusRequiresVariantsWithFirstHeightAtLeast480() {
        assertEquals(RankWeight.VARIANT_POINTS, RankWeight.primarySignal(candidate(variants = listOf(variant(480)))))
        assertEquals(RankWeight.VARIANT_POINTS, RankWeight.primarySignal(candidate(variants = listOf(variant(2160)))))
        assertEquals(0, RankWeight.primarySignal(candidate(variants = listOf(variant(479)))))
        assertEquals(0, RankWeight.primarySignal(candidate(variants = listOf(variant(null)))))
        assertEquals(0, RankWeight.primarySignal(candidate(variants = emptyList())))
        assertEquals(0, RankWeight.primarySignal(candidate(variants = null)))
    }

    @Test fun lengthBonusIsTheFiveMegabyteToFiveGigabyteWindow() {
        assertEquals(RankWeight.LENGTH_POINTS, RankWeight.primarySignal(candidate(totalBytes = RankWeight.MIN_PRIMARY_BYTES)))
        assertEquals(RankWeight.LENGTH_POINTS, RankWeight.primarySignal(candidate(totalBytes = RankWeight.MAX_PRIMARY_BYTES)))
        assertEquals(0, RankWeight.primarySignal(candidate(totalBytes = RankWeight.MIN_PRIMARY_BYTES - 1)))
        assertEquals(0, RankWeight.primarySignal(candidate(totalBytes = RankWeight.MAX_PRIMARY_BYTES + 1)))
        // Tiny library snippets get nothing; the 10 MB preview DOES get the bonus — which is why
        // the variant signal (3) must outweigh it (2) for main-video ranking.
        assertEquals(0, RankWeight.primarySignal(candidate(totalBytes = 57_344L)))
        assertEquals(RankWeight.LENGTH_POINTS, RankWeight.primarySignal(candidate(totalBytes = 10_048_775L)))
    }

    @Test fun declaredSizeBytesIsUsedOnlyWhenVerifiedTotalIsAbsent() {
        assertEquals(RankWeight.LENGTH_POINTS, RankWeight.primarySignal(candidate(sizeBytes = 900_000_000L)))
        assertEquals(0, RankWeight.primarySignal(candidate(totalBytes = 57_344L, sizeBytes = 900_000_000L)))
        assertEquals(0, RankWeight.primarySignal(candidate(totalBytes = null, sizeBytes = null)))
    }

    @Test fun titleBonusMatchesBareQualityLabelsOnly() {
        listOf("1080p", "720", "480i", "2160p").forEach {
            assertEquals(RankWeight.TITLE_POINTS, RankWeight.primarySignal(candidate(title = it)))
        }
        listOf("Movie", "1080p HD", "1080px", "5p", "1080 ", "trailer").forEach {
            assertEquals("no bonus for '$it'", 0, RankWeight.primarySignal(candidate(title = it)))
        }
    }

    @Test fun playingAddsItsPointsOnTopOfOtherSignals() {
        assertEquals(RankWeight.PLAYING_POINTS, RankWeight.primarySignal(candidate(playing = true)))
        assertEquals(RankWeight.VARIANT_POINTS + RankWeight.PLAYING_POINTS,
            RankWeight.primarySignal(candidate(variants = listOf(variant(720)), playing = true)))
        assertEquals(RankWeight.VARIANT_POINTS + RankWeight.LENGTH_POINTS + RankWeight.TITLE_POINTS,
            RankWeight.primarySignal(candidate(variants = listOf(variant(720)), totalBytes = 373_009_519L, title = "1080p")))
    }
}

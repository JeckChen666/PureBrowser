package com.example.purebrowser.ui.resources

import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T117 pure default rules: preselected variant, quick-save best format, prefilled names. */
class SaveDefaultsTest {
    private fun variant(height: Int?, url: String, warning: String? = null, bandwidth: Long? = null) =
        VariantSummary(height, bandwidth, null, url, warning)

    @Test
    fun defaultVariantPrefersBestAtOrBelow1080p() {
        val ladder = listOf(
            variant(720, "https://media.example/720.m3u8"),
            variant(1080, "https://media.example/1080.m3u8"),
            variant(2160, "https://media.example/2160.m3u8"),
        )
        assertEquals("https://media.example/1080.m3u8", SaveDefaults.defaultVariant(ladder)?.url)
    }

    @Test
    fun defaultVariantBreaksHeightTiesByBandwidth() {
        val ladder = listOf(
            variant(1080, "https://media.example/a.m3u8", bandwidth = 1_000_000),
            variant(1080, "https://media.example/b.m3u8", bandwidth = 3_000_000),
        )
        assertEquals("https://media.example/b.m3u8", SaveDefaults.defaultVariant(ladder)?.url)
    }

    @Test
    fun defaultVariantAvoidsWarnedRowsWhileACleanOneExists() {
        val ladder = listOf(
            variant(1080, "https://media.example/warned.m3u8", warning = "此档位编码不是 H.264/AAC，保存时可能失败"),
            variant(720, "https://media.example/clean.m3u8"),
        )
        assertEquals("https://media.example/clean.m3u8", SaveDefaults.defaultVariant(ladder)?.url)
    }

    @Test
    fun defaultVariantFallsBackToWarnedThenSmallestTallThenFirst() {
        // Warned rows qualify once no clean row exists.
        val warned = listOf(
            variant(2160, "https://media.example/big.m3u8", warning = "w"),
            variant(720, "https://media.example/warned.m3u8", warning = "w"),
        )
        assertEquals("https://media.example/warned.m3u8", SaveDefaults.defaultVariant(warned)?.url)
        // An all-taller list takes the smallest height, never silently the tallest.
        val tall = listOf(
            variant(1440, "https://media.example/1440.m3u8"),
            variant(2160, "https://media.example/2160.m3u8"),
        )
        assertEquals("https://media.example/1440.m3u8", SaveDefaults.defaultVariant(tall)?.url)
        // Unknown metadata stays first-come; nothing is invented.
        val unknown = listOf(variant(null, "https://media.example/a.m3u8"), variant(null, "https://media.example/b.m3u8"))
        assertEquals("https://media.example/a.m3u8", SaveDefaults.defaultVariant(unknown)?.url)
    }

    @Test
    fun defaultVariantOfEmptyListIsNull() {
        assertNull(SaveDefaults.defaultVariant(emptyList()))
    }

    @Test
    fun defaultFormatCandidatePicksCanonicalBestForRulesFormatsOnly() {
        val candidate = MediaCandidate(
            "https://page.example/watch", MediaKind.FILE, setOf(Evidence.RULE),
            variants = listOf(
                variant(720, "https://cdn.example/audio-720.webm"),
                variant(1080, "https://cdn.example/video-1080.mp4"),
                variant(720, "https://cdn.example/video-720.mp4"),
            ),
        )
        val resolved = SaveDefaults.defaultFormatCandidate(candidate)
        assertEquals("https://cdn.example/video-1080.mp4", resolved.url)
        assertEquals(MediaKind.FILE, resolved.kind)
        // Non-rules, manifest-kind and single-format candidates stay untouched.
        assertEquals(candidate.url, SaveDefaults.defaultFormatCandidate(
            candidate.copy(sources = setOf(Evidence.DOM))).url)
        assertEquals(candidate.url, SaveDefaults.defaultFormatCandidate(
            candidate.copy(kind = MediaKind.HLS)).url)
        assertEquals(candidate.url, SaveDefaults.defaultFormatCandidate(
            candidate.copy(variants = listOf(variant(720, "https://cdn.example/video-720.mp4")))).url)
    }

    @Test
    fun qualitySuffixPrependsMarkerAndPreservesExtension() {
        assertEquals("clip-1080p.mp4", qualitySuffixedName("clip.mp4", 1080))
        assertEquals("clip-720p.webm", qualitySuffixedName("clip.webm", 720))
        assertEquals("noext-480p", qualitySuffixedName("noext", 480))
        assertEquals("clip.mp4", qualitySuffixedName("clip.mp4", null))
        assertEquals("clip.mp4", qualitySuffixedName("clip.mp4", 0))
        assertEquals("clip-720p.mp4", qualitySuffixedName("clip-720p.mp4", 720))
    }

    @Test
    fun mp4SaveNameReservesTheMp4Extension() {
        assertEquals("chosen.mp4", mp4SaveName("chosen.webm"))
        assertEquals("chosen.mp4", mp4SaveName("chosen.mp4"))
    }

    @Test
    fun saveNameSuggestionPrefersTitleForRulesAndSiteDiscoveries() {
        val rules = MediaCandidate(
            "https://page.example/watch?v=1", MediaKind.FILE, setOf(Evidence.RULE), title = "节目 标题/集")
        assertEquals("节目_标题_集.mp4", SaveDefaults.saveNameSuggestion(rules))
        val site = MediaCandidate(
            "https://page.example/watch", MediaKind.FILE, setOf(Evidence.SITE), title = "站点标题")
        assertEquals("站点标题.mp4", SaveDefaults.saveNameSuggestion(site))
    }
}

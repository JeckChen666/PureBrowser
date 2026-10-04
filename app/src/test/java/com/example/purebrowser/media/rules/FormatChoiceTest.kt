package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary
import org.junit.Assert.*
import org.junit.Test

/**
 * T87 pure tests for the chooser surface: row rebuilding from a candidate's VariantSummary list
 * (container/kind derived from the address, canonical order), height dedup and container pickers,
 * label composition, and the preference→row decision which delegates to [FormatSelector.select].
 */
class FormatChoiceTest {

    private fun v(url: String, height: Int? = null, bandwidth: Long? = null) =
        VariantSummary(height = height, bandwidth = bandwidth, codecs = null, url = url, warning = null)

    @Test fun rowsRebuildKindAndContainerFromTheAddressAndOrderCanonically() {
        val rows = FormatChoice.rows(
            listOf(
                v("https://cdn.tube.example/480.mp4", 480, 1_000_000),
                v("https://cdn.tube.example/1080.mp4", 1080, 3_500_000),
                v("https://cdn.tube.example/master.m3u8", null, null),
                v("https://cdn.tube.example/720.webm", 720, 2_000_000),
                v("https://cdn.tube.example/seg.ts", 720, 2_000_000),
            ),
        )
        // Highest first; equal quality prefers mp4 over webm over ts; unknown heights rank last.
        assertEquals("1080.mp4", rows[0].url.substringAfterLast('/'))
        assertEquals("720.webm", rows[1].url.substringAfterLast('/'))
        assertEquals("seg.ts", rows[2].url.substringAfterLast('/'))
        assertEquals("480.mp4", rows[3].url.substringAfterLast('/'))
        assertEquals("master.m3u8", rows[4].url.substringAfterLast('/'))
        // Kind derivation: m3u8 → HLS; .ts (a fragment shape for the classifier) still selectable as FILE.
        assertEquals(MediaKind.HLS, rows[4].kind)
        assertEquals("m3u8", rows[4].ext)
        assertEquals(MediaKind.FILE, rows[2].kind)
        assertEquals("ts", rows[2].ext)
        assertEquals(MediaKind.FILE, rows[0].kind)
        // Bitrate is normalized back from the summary's bit/s to kbps.
        assertEquals(3500L, rows[0].tbr)
        // Empty input is empty; the input list itself is never mutated.
        assertTrue(FormatChoice.rows(emptyList()).isEmpty())
    }

    @Test fun heightsDedupeDescendingAndSkipUnknowns() {
        val rows = FormatChoice.rows(
            listOf(
                v("https://c/1080.mp4", 1080),
                v("https://c/720.mp4", 720),
                v("https://c/720-alt.mp4", 720),
                v("https://c/240.mp4", 240),
                v("https://c/unknown.mp4", null),
            ),
        )
        assertEquals(listOf(1080, 720, 240), FormatChoice.heights(rows))
    }

    @Test fun containersListOnlySelectableOnesInCanonicalOrder() {
        val rows = FormatChoice.rows(
            listOf(
                v("https://c/a.ts", 480),
                v("https://c/b.mp4", 1080),
                v("https://c/c.webm", 720),
                v("https://c/d.mov", 360),
                v("https://c/e.bin", 240),
            ),
        )
        assertEquals(listOf("mp4", "webm", "ts"), FormatChoice.containers(rows))
        val onlyTs = FormatChoice.rows(listOf(v("https://c/a.ts", 480)))
        assertEquals(listOf("ts"), FormatChoice.containers(onlyTs))
        val none = FormatChoice.rows(listOf(v("https://c/noext", 480)))
        assertTrue(FormatChoice.containers(none).isEmpty())
    }

    @Test fun labelsComposeKnownFactsAndStayHonestAboutMissingOnes() {
        assertEquals("1080p · mp4 · 3500kbps", FormatChoice.label(FormatChoiceRow("https://c/a.mp4", 1080, "mp4", 3500, MediaKind.FILE)))
        assertEquals("720p · webm", FormatChoice.label(FormatChoiceRow("https://c/b.webm", 720, "webm", null, MediaKind.FILE)))
        assertEquals("mp4 · 800kbps", FormatChoice.label(FormatChoiceRow("https://c/c.mp4", null, "mp4", 800, MediaKind.FILE)))
        assertEquals("未知格式", FormatChoice.label(FormatChoiceRow("https://c/noext", null, null, null, MediaKind.UNKNOWN)))
        // Zero/negative bitrates drop their segment instead of printing nonsense.
        assertEquals("480p · ts", FormatChoice.label(FormatChoiceRow("https://c/d.ts", 480, "ts", 0, MediaKind.FILE)))
    }

    @Test fun selectDelegatesToTheCanonicalSelectorIncludingFallbackLadders() {
        val rows = FormatChoice.rows(
            listOf(
                v("https://c/1080.mp4", 1080, 3_500_000),
                v("https://c/720.webm", 720, 2_000_000),
                v("https://c/720.mp4", 720, 2_000_000),
                v("https://c/480.mp4", 480, 800_000),
            ),
        )
        // Best = the canonical top.
        assertEquals("1080.mp4", FormatChoice.select(rows, FormatPreference.Best)!!.url.substringAfterLast('/'))
        // Exact height; the equal-quality container tiebreak prefers mp4 over webm.
        assertEquals("720.mp4", FormatChoice.select(rows, FormatPreference.FixedHeight(720))!!.url.substringAfterLast('/'))
        // Not-above ladder: no 999 exists, so the best height not above the request wins.
        assertEquals("720.mp4", FormatChoice.select(rows, FormatPreference.FixedHeight(999))!!.url.substringAfterLast('/'))
        assertEquals("480.mp4", FormatChoice.select(rows, FormatPreference.FixedHeight(500))!!.url.substringAfterLast('/'))
        // Only-taller case falls to the smallest available height.
        val tall = FormatChoice.rows(listOf(v("https://c/2160.mp4", 2160), v("https://c/1440.mp4", 1440)))
        assertEquals("1440.mp4", FormatChoice.select(tall, FormatPreference.FixedHeight(1080))!!.url.substringAfterLast('/'))
        // Container preference picks that container's best entry.
        assertEquals("720.webm", FormatChoice.select(rows, FormatPreference.FixedExt("webm"))!!.url.substringAfterLast('/'))
        // No match and empty input yield null, never a silent default.
        assertNull(FormatChoice.select(rows, FormatPreference.FixedExt("ts")))
        assertNull(FormatChoice.select(emptyList(), FormatPreference.Best))
    }

    @Test fun rowsRoundTripThroughVariantSummariesWithoutLosingSelectionFacts() {
        val formats = listOf(
            FormatEntry("https://c/480.mp4", 480, null, "mp4", 900, MediaKind.FILE),
            FormatEntry("https://c/1080.mp4", 1080, null, "mp4", 3500, MediaKind.FILE),
        )
        // The coordinator folds formats into variants; the chooser rebuilds equivalent rows, so the
        // preference decision over rebuilt rows matches the decision over the original formats.
        val variants = FormatSelector.toVariantSummaries(formats)
        val rebuilt = FormatChoice.rows(variants)
        assertEquals(
            FormatSelector.select(formats, FormatPreference.FixedHeight(480))?.url,
            FormatChoice.select(rebuilt, FormatPreference.FixedHeight(480))?.url,
        )
        assertEquals(
            FormatSelector.select(formats, FormatPreference.Best)?.url,
            FormatChoice.select(rebuilt, FormatPreference.Best)?.url,
        )
    }
}

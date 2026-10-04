package com.example.purebrowser.media.rules

import com.example.purebrowser.media.MediaKind
import org.junit.Assert.*
import org.junit.Test

/**
 * T84/T87 pure selector fixtures: canonical ordering (height, then bitrate, then container, then
 * protocol), user-preference resolution, and the VariantSummary normalization (bandwidth = tbr*1000).
 * Ordering rules follow the transcribed open-source behavior spec, never its code.
 */
class FormatSelectorTest {
    private fun f(url: String, height: Int? = null, width: Int? = null, ext: String? = null, tbr: Long? = null, kind: MediaKind = MediaKind.FILE) =
        FormatEntry(url, height, width, ext, tbr, kind)

    @Test fun ordersByHeightThenBitrate() {
        val ordered = FormatSelector.order(
            listOf(
                f("https://c.example/360.mp4", height = 360, tbr = 700),
                f("https://c.example/1080-low.mp4", height = 1080, tbr = 2000),
                f("https://c.example/1080-high.mp4", height = 1080, tbr = 4500),
                f("https://c.example/720.mp4", height = 720, tbr = 1500),
                f("https://c.example/unknown.mp4", tbr = 9000), // unknown height sorts last, despite bitrate
            ),
        )
        assertEquals(
            listOf("1080-high", "1080-low", "720", "360", "unknown"),
            ordered.map { it.url.substringAfterLast('/').substringBeforeLast('.') },
        )
    }

    @Test fun equalQualityPrefersContainerThenProtocol() {
        val ordered = FormatSelector.order(
            listOf(
                f("https://c.example/a.ts", height = 720, ext = "ts", tbr = 1500),
                f("https://c.example/a.webm", height = 720, ext = "webm", tbr = 1500),
                f("https://c.example/a.m3u8", height = 720, ext = "m3u8", tbr = 1500, kind = MediaKind.HLS),
                f("https://c.example/a.mp4", height = 720, ext = "mp4", tbr = 1500),
            ),
        )
        // mp4 > webm > ts among direct files; https-native beats the manifest-derived entry.
        assertEquals("mp4", ordered[0].ext)
        assertEquals("webm", ordered[1].ext)
        assertEquals("ts", ordered[2].ext)
        assertEquals(MediaKind.HLS, ordered[3].kind)
    }

    @Test fun selectionResolvesUserPreferences() {
        val formats = listOf(
            f("https://c.example/360.mp4", height = 360, ext = "mp4", tbr = 700),
            f("https://c.example/720.mp4", height = 720, ext = "mp4", tbr = 1500),
            f("https://c.example/1080.mp4", height = 1080, ext = "mp4", tbr = 4500),
            f("https://c.example/720.webm", height = 720, ext = "webm", tbr = 1600),
            f("https://c.example/2160.mp4", height = 2160, ext = "mp4", tbr = 9000),
        )
        assertEquals("2160", FormatSelector.select(formats, FormatPreference.Best)!!.url.substringAfterLast('/').substringBeforeLast('.'))
        assertEquals(1080, FormatSelector.select(formats, FormatPreference.FixedHeight(1080))!!.height)
        // No 1440 entry: the best height not above the request wins.
        assertEquals(1080, FormatSelector.select(formats, FormatPreference.FixedHeight(1440))!!.height)
        // Only taller entries exist: the smallest of them, never a null-with-data surprise.
        assertEquals(360, FormatSelector.select(formats, FormatPreference.FixedHeight(200))!!.height)
        // Container choice filters first, then orders by quality.
        assertEquals(1600L, FormatSelector.select(formats, FormatPreference.FixedExt("webm"))!!.tbr)
        assertNull(FormatSelector.select(formats, FormatPreference.FixedExt("mkv")))
        assertNull(FormatSelector.select(emptyList(), FormatPreference.Best))
    }

    @Test fun variantSummariesNormalizeTbrIntoBitsPerSecond() {
        val variants = FormatSelector.toVariantSummaries(
            listOf(
                f("https://c.example/720.mp4", height = 720, ext = "mp4", tbr = 1500),
                f("https://c.example/360.mp4", height = 360, ext = "mp4"),
            ),
        )
        assertEquals(2, variants.size)
        assertEquals(720, variants[0].height)
        assertEquals(1_500_000L, variants[0].bandwidth)
        assertNull(variants[0].codecs)
        assertNull(variants[0].warning)
        assertEquals(360, variants[1].height)
        assertNull(variants[1].bandwidth) // unknown tbr stays unknown: never invented
    }

    @Test fun orderingIsStableAndDoesNotMutateInput() {
        val a = f("https://c.example/b.mp4", height = 720, tbr = 1500)
        val b = f("https://c.example/a.mp4", height = 720, tbr = 1500)
        val input = listOf(a, b)
        val ordered = FormatSelector.order(input)
        fun leaf(entry: FormatEntry) = entry.url.substringAfterLast('/')
        assertEquals(listOf("a.mp4", "b.mp4"), ordered.map(::leaf)) // address is the stable tiebreak
        assertEquals(listOf("b.mp4", "a.mp4"), input.map(::leaf))   // input untouched
    }
}

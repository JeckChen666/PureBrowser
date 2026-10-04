package com.example.purebrowser.download.hls

import org.junit.Assert.*
import org.junit.Test

class HlsVariantSummaryTest {
    private val base = "https://cdn.example/talk/master.m3u8"

    /** Master with seven H.264 variants, every one referencing a SUBTITLES rendition group. */
    private fun subtitleMaster() = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"English\",DEFAULT=YES,AUTOSELECT=YES,URI=\"subtitles/en.m3u8\"")
        listOf(234 to 416, 360 to 640, 540 to 960, 720 to 1280, 1080 to 1920, 1436 to 2048, 2072 to 3840)
            .forEachIndexed { index, (height, width) ->
                appendLine("#EXT-X-STREAM-INF:BANDWIDTH=${(index + 1) * 200000},RESOLUTION=${width}x$height," +
                    "CODECS=\"avc1.42c00c,mp4a.40.2\",SUBTITLES=\"subs\"")
                appendLine("gear$index/prog_index.m3u8")
            }
    }

    @Test fun subtitleRenditionsNowYieldWarnedButSelectableVariants() {
        val parsed = HlsPlaylistParser.parse(subtitleMaster(), base) as HlsPlaylist.Master
        assertEquals(7, parsed.variants.size)
        parsed.variants.forEach { variant ->
            assertTrue(variant.supported)
            assertNotNull(variant.unsupportedReason)
        }
        // Fallback to warned variants still applies the normal quality heuristics (best <= 1080p).
        val chosen = HlsPlaylistParser.defaultVariant(parsed.variants)!!
        assertEquals(1080, chosen.height)
        assertEquals("https://cdn.example/talk/gear4/prog_index.m3u8", chosen.url)
    }

    @Test fun variantSummariesSurfaceTheSameSevenWarnedEntriesWithoutMediaFetches() {
        val summaries = HlsPlaylistParser.variantSummaries(subtitleMaster(), base)
        assertEquals(7, summaries.size)
        summaries.forEach { summary ->
            assertNotNull(summary.warning)
            assertTrue(summary.url.startsWith("https://cdn.example/talk/gear"))
        }
        assertEquals(1080, summaries[4].height)
        assertEquals(1_000_000L, summaries[4].bandwidth)
        assertEquals("avc1.42c00c,mp4a.40.2", summaries[4].codecs)
        // Diagnostics never print the signed variant addresses.
        assertFalse(summaries.toString().contains("https://"))
    }

    @Test fun mixedMasterPrefersTheCleanVariantOverWarnedOnes() {
        val body = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,AUTOSELECT=YES,URI="subtitles/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L93.B0,mp4a.40.2"
            hdr1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            clean720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.42c00c,mp4a.40.2",SUBTITLES="subs"
            subbed360.m3u8
        """.trimIndent()
        val parsed = HlsPlaylistParser.parse(body, base) as HlsPlaylist.Master
        val chosen = HlsPlaylistParser.defaultVariant(parsed.variants)!!
        assertEquals("https://cdn.example/talk/clean720.m3u8", chosen.url)
        val summaries = HlsPlaylistParser.variantSummaries(body, base)
        assertEquals(3, summaries.size)
        assertNull(summaries.first { it.url.endsWith("clean720.m3u8") }.warning)
        assertNotNull(summaries.first { it.url.endsWith("hdr1080.m3u8") }.warning)
        assertNotNull(summaries.first { it.url.endsWith("subbed360.m3u8") }.warning)
    }

    @Test fun excludedSeparateAudioTrackStaysUnsupportedAndCarriesNoSelectableWarning() {
        val body = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="external",DEFAULT=YES,URI="audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="a"
            split.m3u8
        """.trimIndent()
        val parsed = HlsPlaylistParser.parse(body, base) as HlsPlaylist.Master
        assertFalse(parsed.variants.single().supported)
        assertNull(HlsPlaylistParser.defaultVariant(parsed.variants))
        // An exclusion reason is not offered as a selectable warning.
        assertNull(HlsPlaylistParser.variantSummaries(body, base).single().warning)
    }

    @Test fun mediaPlaylistsYieldNoVariantSummariesAndHardRejectsStillThrow() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg001.ts\n#EXT-X-ENDLIST\n"
        assertTrue(HlsPlaylistParser.variantSummaries(media, base).isEmpty())
        val encrypted = "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"key\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=100\nv.m3u8\n"
        try {
            HlsPlaylistParser.variantSummaries(encrypted, base)
            fail("Expected hard reject for encrypted HLS")
        } catch (expected: HlsParseException) {
            assertEquals("本版不支持加密 HLS", expected.safeReason)
        }
    }
}

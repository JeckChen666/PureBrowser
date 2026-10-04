package com.example.purebrowser.media.dash

import org.junit.Assert.*
import org.junit.Test

class MpdCatalogTest {
    private val documentUrl = "https://media.example/stream/manifest.mpd"

    /** Two Periods, an inherited AdaptationSet, plus declaration, comment and CDATA noise. */
    private fun twoPeriods() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- catalog fixture -->
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static">
          <BaseURL><![CDATA[https://media.example/]]></BaseURL>
          <Period id="p1">
            <AdaptationSet mimeType="video/mp4" height="720" bandwidth="1200000" codecs="avc1.64001f">
              <Representation id="v1" height="1080" bandwidth="3000000" codecs="avc1.640028"/>
              <Representation id="v2"/>
            </AdaptationSet>
          </Period>
          <Period id="p2">
            <AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2">
              <Representation id="a1" bandwidth="128000"/>
            </AdaptationSet>
            <AdaptationSet mimeType="video/mp4">
              <Representation id="v3" height="480" bandwidth="600000" codecs="avc1.42c01e"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test fun twoPeriodsCollectEveryRepresentationWithAdaptationSetInheritance() {
        val reps = MpdCatalog.representations(twoPeriods(), documentUrl)
        assertEquals(listOf(1080, 720, 480, null), reps.map { it.height })
        val inherited = reps[1]
        assertEquals("v2", inherited.id)
        assertEquals(720, inherited.height)
        assertEquals(1_200_000L, inherited.bandwidth)
        assertEquals("avc1.64001f", inherited.codecs)
        assertEquals("video/mp4", inherited.mimeType)
        val audio = reps[3]
        assertEquals("a1", audio.id)
        assertEquals(128_000L, audio.bandwidth)
        assertEquals("mp4a.40.2", audio.codecs)
        assertEquals("audio/mp4", audio.mimeType)
    }

    @Test fun singleQuotedAttributesAreAccepted() {
        val mpd = "<MPD><Period><AdaptationSet mimeType='video/mp4'>" +
            "<Representation id='v1' height='2160' bandwidth='8000000' codecs='avc1.640033'/>" +
            "</AdaptationSet></Period></MPD>"
        val rep = MpdCatalog.representations(mpd, documentUrl).single()
        assertEquals("v1", rep.id)
        assertEquals(2160, rep.height)
        assertEquals(8_000_000L, rep.bandwidth)
        assertEquals("avc1.640033", rep.codecs)
        assertEquals("video/mp4", rep.mimeType)
    }

    @Test fun namespacedTagsAndAttributesMatchLocalNames() {
        val mpd = "<d:MPD><d:Period><d:AdaptationSet mimeType='video/mp4'>" +
            "<d:Representation id='v1' d:height='1440' d:bandwidth='5000000' codecs='hvc1.1.6.L120.90'/>" +
            "</d:AdaptationSet></d:Period></d:MPD>"
        val rep = MpdCatalog.representations(mpd, documentUrl).single()
        assertEquals(1440, rep.height)
        assertEquals(5_000_000L, rep.bandwidth)
        assertEquals("hvc1.1.6.L120.90", rep.codecs)
    }

    @Test fun truncatedMidAttributeKeepsEarlierCompleteRepresentations() {
        val mpd = "<MPD><Period><AdaptationSet mimeType='video/mp4'>" +
            "<Representation id='v1' height='240' bandwidth='300000'/>" +
            "<Representation id='v2' height='480' bandwidth='9000"
        val reps = MpdCatalog.representations(mpd, documentUrl)
        assertEquals(1, reps.size)
        assertEquals("v1", reps[0].id)
        assertEquals(240, reps[0].height)
    }

    @Test fun oversizedDocumentsAreRejectedOutright() {
        val oversized = "<MPD>".padEnd(MpdCatalog.MAX_MPD_CHARS + 1, 'x')
        assertTrue(MpdCatalog.representations(oversized, documentUrl).isEmpty())
    }

    @Test fun attributeValuesBeyondTheCapAreDroppedIndividually() {
        val longCodecs = "a".repeat(MpdCatalog.MAX_ATTRIBUTE_CHARS + 1)
        val mpd = "<MPD><Period><AdaptationSet mimeType='video/mp4'>" +
            "<Representation id='v1' height='360' bandwidth='500000' codecs='$longCodecs'/>" +
            "</AdaptationSet></Period></MPD>"
        val rep = MpdCatalog.representations(mpd, documentUrl).single()
        assertNull(rep.codecs)
        assertEquals(360, rep.height)
        assertEquals(500_000L, rep.bandwidth)
    }

    @Test fun resultCapKeepsTheFirstThirtyTwoInDocumentOrder() {
        val mpd = buildString {
            append("<MPD><Period><AdaptationSet mimeType='video/mp4'>")
            repeat(40) { index ->
                append("<Representation id='v$index' height='${index + 1}' bandwidth='${(index + 1) * 1000}'/>")
            }
            append("</AdaptationSet></Period></MPD>")
        }
        val reps = MpdCatalog.representations(mpd, documentUrl)
        assertEquals(MpdCatalog.MAX_REPRESENTATIONS, reps.size)
        // v0..v31 were kept (heights 1..32); sorting is by height descending afterwards.
        assertEquals("v31", reps.first().id)
        assertEquals(32, reps.first().height)
        assertEquals("v0", reps.last().id)
    }

    @Test fun sortingFallsBackToBandwidthAndPushesUnknownHeightsLast() {
        val mpd = "<MPD><Period><AdaptationSet mimeType='video/mp4'>" +
            "<Representation id='low' height='720' bandwidth='800000'/>" +
            "<Representation id='high' height='720' bandwidth='2500000'/>" +
            "<Representation id='wide-audio' bandwidth='96000'/>" +
            "<Representation id='narrow-audio' bandwidth='64000'/>" +
            "<Representation id='tall' height='1080'/>" +
            "</AdaptationSet></Period></MPD>"
        val reps = MpdCatalog.representations(mpd, documentUrl)
        assertEquals(listOf("tall", "high", "low", "wide-audio", "narrow-audio"), reps.map { it.id })
    }

    @Test fun emptyAndGarbageInputYieldsNoRepresentations() {
        assertTrue(MpdCatalog.representations("", documentUrl).isEmpty())
        assertTrue(MpdCatalog.representations("not xml at all", documentUrl).isEmpty())
        assertTrue(MpdCatalog.representations("<html><body>404</body></html>", documentUrl).isEmpty())
        // Junk numeric values degrade to unknowns instead of losing the entry.
        val tolerant = MpdCatalog.representations(
            "<MPD><Period><Representation id='v' height='x' bandwidth='1.5'/></Period></MPD>", documentUrl,
        ).single()
        assertNull(tolerant.height)
        assertNull(tolerant.bandwidth)
        assertEquals("v", tolerant.id)
    }
}

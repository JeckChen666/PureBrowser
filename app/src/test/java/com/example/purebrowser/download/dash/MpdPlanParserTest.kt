package com.example.purebrowser.download.dash

import org.junit.Assert.*
import org.junit.Test

/**
 * T96/T97 pure-tier coverage: template math, URL resolution, plan rejects and budget bounds.
 * MPD fixtures use ${'$'} escaping because Kotlin raw strings still interpolate dollar signs.
 */
class MpdPlanParserTest {
    private val mpdUrl = "https://cdn.example/media/manifest.mpd"

    /** SegmentTemplate with ${'$'}Number addressing, separate video/audio AdaptationSets. */
    private fun templateMpd(
        duration: String = "PT10S",
        codecs: String = "avc1.64001f",
        audioCodecs: String = "mp4a.40.2",
        timescale: String = "10000000",
        segmentDuration: String = "20000000",
        type: String = "static",
    ) = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="$type" mediaPresentationDuration="$duration" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
  <Period>
    <AdaptationSet contentType="video" mimeType="video/mp4" segmentAlignment="true">
      <SegmentTemplate timescale="$timescale" duration="$segmentDuration" startNumber="1"
        initialization="${'$'}RepresentationID${'$'}/init.mp4" media="${'$'}RepresentationID${'$'}/seg-${'$'}Number${'$'}.m4s"/>
      <Representation id="v720" codecs="$codecs" bandwidth="1200000" width="1280" height="720"/>
      <Representation id="v360" codecs="$codecs" bandwidth="500000" width="640" height="360"/>
    </AdaptationSet>
    <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="en">
      <SegmentTemplate timescale="$timescale" duration="$segmentDuration" startNumber="1"
        initialization="audio/${'$'}RepresentationID${'$'}/init.mp4" media="audio/${'$'}RepresentationID${'$'}/seg-${'$'}Number${'$'}.m4s"/>
      <Representation id="a128" codecs="$audioCodecs" bandwidth="128000" audioSamplingRate="44100"/>
    </AdaptationSet>
  </Period>
</MPD>
"""

    @Test fun templateNumberMathResolvesSegmentCountAndOrder() {
        val document = MpdPlanParser.parse(templateMpd(), mpdUrl)
        assertEquals(10_000_000L, document.durationUs)
        val video = document.videoOffers.singleOrNull { it.id == "v720" }!!
        val plan = MpdPlanParser.buildPlan(document, video)
        val videoPlan = plan.video
        assertEquals("https://cdn.example/media/v720/init.mp4", videoPlan.initUrl)
        // PT10S at 2s per segment: exactly 5 numbered segments.
        assertEquals(
            listOf(
                "https://cdn.example/media/v720/seg-1.m4s",
                "https://cdn.example/media/v720/seg-2.m4s",
                "https://cdn.example/media/v720/seg-3.m4s",
                "https://cdn.example/media/v720/seg-4.m4s",
                "https://cdn.example/media/v720/seg-5.m4s",
            ),
            videoPlan.segments.map { it.url },
        )
        assertEquals(2_000_000L, videoPlan.segments.first().durationUs)
        // Default audio pairing: the first supported audio representation is attached.
        assertEquals("a128", plan.audio!!.id)
        assertEquals("https://cdn.example/media/audio/a128/init.mp4", plan.audio!!.initUrl)
        assertEquals(5, plan.audio!!.segments.size)
        assertFalse(plan.muxed)
        assertEquals(10, plan.totalSegments)
    }

    @Test fun durationRoundingProducesWholeSegmentCount() {
        // PT9.5S at 2s per segment rounds up to 5 segments.
        val document = MpdPlanParser.parse(templateMpd(duration = "PT9.5S"), mpdUrl)
        val plan = MpdPlanParser.buildPlan(document, document.videoOffers.first { it.id == "v720" })
        assertEquals(5, plan.video.segments.size)
    }

    @Test fun baseUrlChainAndRelativeReferencesResolve() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT4S">
  <BaseURL>https://other.example/base/</BaseURL>
  <Period>
    <AdaptationSet contentType="video" mimeType="video/mp4">
      <BaseURL>v/</BaseURL>
      <SegmentTemplate timescale="1" duration="2" initialization="init.mp4" media="chunk-${'$'}Number${'$'}.m4s"/>
      <Representation id="hi" codecs="avc1.42c00c" bandwidth="1" height="480"/>
    </AdaptationSet>
  </Period>
</MPD>"""
        val document = MpdPlanParser.parse(mpd, mpdUrl)
        val plan = MpdPlanParser.buildPlan(document, document.videoOffers.single())
        assertEquals("https://other.example/base/v/init.mp4", plan.video.initUrl)
        assertEquals("https://other.example/base/v/chunk-1.m4s", plan.video.segments[0].url)
        assertEquals("https://other.example/base/v/chunk-2.m4s", plan.video.segments[1].url)
    }

    @Test fun segmentListAddressingAndUniformDurationsResolve() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT6S">
  <Period>
    <AdaptationSet contentType="video" mimeType="video/mp4">
      <Representation id="v" codecs="avc1.42c00c" bandwidth="1" height="480">
        <SegmentList timescale="90000" duration="180000">
          <Initialization sourceURL="init.mp4"/>
          <SegmentURL media="seg1.m4s"/>
          <SegmentURL media="seg2.m4s"/>
          <SegmentURL media="seg3.m4s"/>
        </SegmentList>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""
        val document = MpdPlanParser.parse(mpd, mpdUrl)
        val plan = MpdPlanParser.buildPlan(document, document.videoOffers.single())
        assertEquals("https://cdn.example/media/init.mp4", plan.video.initUrl)
        assertEquals(listOf("seg1.m4s", "seg2.m4s", "seg3.m4s"),
            plan.video.segments.map { it.url.substringAfterLast('/') })
        assertEquals(2_000_000L, plan.video.segments[0].durationUs)
        assertFalse(plan.muxed)
    }

    @Test fun segmentTimelineWithTimeTemplateProducesTickTimes() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT6S">
  <Period>
    <AdaptationSet contentType="video" mimeType="video/mp4">
      <SegmentTemplate timescale="90000" media="t-${'$'}Time${'$'}.m4s" initialization="init.mp4">
        <SegmentTimeline>
          <S t="0" d="180000"/>
          <S d="90000" r="1"/>
          <S d="180000"/>
        </SegmentTimeline>
      </SegmentTemplate>
      <Representation id="v" codecs="avc1.42c00c" bandwidth="1" height="480"/>
    </AdaptationSet>
  </Period>
</MPD>"""
        val document = MpdPlanParser.parse(mpd, mpdUrl)
        val plan = MpdPlanParser.buildPlan(document, document.videoOffers.single())
        // t=0/2s, then two 1s repeats at 2s/3s, then 2s at 4s: cumulative ticks 0,180000,270000,360000.
        assertEquals(
            listOf("t-0.m4s", "t-180000.m4s", "t-270000.m4s", "t-360000.m4s"),
            plan.video.segments.map { it.url.substringAfterLast('/') },
        )
        assertEquals(listOf(2_000_000L, 1_000_000L, 1_000_000L, 2_000_000L),
            plan.video.segments.map { it.durationUs })
    }

    @Test fun printfWidthAndEscapingInTemplates() {
        assertEquals("v/seg-0007.m4s",
            MpdPlanParser.substitute("v/seg-\$Number%04d\$.m4s", "x", 1L, 7L, null))
        assertEquals("v-1200/init.mp4",
            MpdPlanParser.substitute("\$RepresentationID\$-\$Bandwidth\$/init.mp4", "v", 1200L, null, null))
        assertEquals("a\$b/1.m4s",
            MpdPlanParser.substitute("a\$\$b/\$Number\$.m4s", "v", null, 1L, null))
    }

    @Test fun unsupportedTemplateTokensFailHonestly() {
        listOf("\$SubNumber\$", "\$Unknown\$", "\$Number%05x\$").forEach { token ->
            try {
                MpdPlanParser.substitute(token, "v", 1L, 1L, 1L)
                fail("expected DashPlanException for $token")
            } catch (expected: DashPlanException) {
                assertTrue(expected.safeReason.isNotBlank())
            }
        }
    }

    @Test fun honestRejectsForUnsupportedDocumentShapes() {
        fun reasonOf(mpd: String): String {
            try {
                MpdPlanParser.parse(mpd, mpdUrl)
                fail("expected DashPlanException")
            } catch (expected: DashPlanException) {
                return expected.safeReason
            }
            @Suppress("UNREACHABLE_CODE") return ""
        }

        assertTrue(reasonOf(templateMpd(type = "dynamic")).contains("直播"))
        assertTrue(reasonOf(templateMpd(duration = "PT999999999S")).contains("时长"))
        // DRM in Period scope is a global reject.
        assertTrue(reasonOf(
            """<MPD type="static" mediaPresentationDuration="PT4S"><Period><ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011"/></Period></MPD>""",
        ).contains("加密"))
        // No declared duration: the template tier cannot bound the segment list.
        assertTrue(reasonOf(
            """<MPD type="static"><Period><AdaptationSet contentType="video" mimeType="video/mp4"><SegmentTemplate timescale="1" duration="2" initialization="i.mp4" media="s${'$'}Number${'$'}.m4s"/><Representation id="v" codecs="avc1.42c00c" bandwidth="1"/></AdaptationSet></Period></MPD>""",
        ).contains("总时长"))
        // Multiple periods are out of this version's support contract.
        assertTrue(reasonOf("""<MPD type="static" mediaPresentationDuration="PT4S"><Period/><Period/></MPD>""").contains("周期"))
        // Codec-less offers never become silently-downloadable candidates.
        val missing = MpdPlanParser.parse(templateMpd(codecs = "", audioCodecs = ""), mpdUrl)
        assertTrue(missing.videoOffers.none { it.supported })
        assertTrue(missing.videoOffers.all { it.unsupportedReason != null })
        assertTrue(missing.audioOffer == null)
    }

    @Test fun codecGatingLabelsAv1HevcAndNonAacHonestly() {
        assertTrue(MpdPlanParser.videoUnsupportedReason("av01.0.08M.08")!!.contains("AV1"))
        assertTrue(MpdPlanParser.videoUnsupportedReason("hvc1.1.6.L93.B0")!!.contains("HEVC"))
        assertTrue(MpdPlanParser.videoUnsupportedReason("vp09.00.51.08")!!.contains("VP9"))
        assertNull(MpdPlanParser.videoUnsupportedReason("avc1.64001f"))
        assertNull(MpdPlanParser.videoUnsupportedReason("avc3.42c01f"))
        assertTrue(MpdPlanParser.audioUnsupportedReason("mp4a.40.5")!!.contains("AAC-LC"))
        assertTrue(MpdPlanParser.audioUnsupportedReason("ec-3")!!.contains("AAC"))
        assertNull(MpdPlanParser.audioUnsupportedReason("mp4a.40.2"))
    }

    @Test fun planTierRejectsSegmentBaseIndexRangeAndByteRanges() {
        val segmentBaseMpd = """<MPD type="static" mediaPresentationDuration="PT4S"><Period>
  <AdaptationSet contentType="video" mimeType="video/mp4">
    <Representation id="v" codecs="avc1.42c00c" bandwidth="1">
      <SegmentBase indexRange="0-999"/>
    </Representation>
  </AdaptationSet>
</Period></MPD>"""
        val document = MpdPlanParser.parse(segmentBaseMpd, mpdUrl)
        val offer = document.videoOffers.single()
        assertFalse(offer.supported)
        assertTrue(offer.unsupportedReason!!.contains("indexRange"))
        try {
            MpdPlanParser.buildPlan(document, offer); fail("expected DashPlanException")
        } catch (expected: DashPlanException) { assertTrue(expected.safeReason.contains("indexRange")) }

        val rangeMpd = """<MPD type="static" mediaPresentationDuration="PT4S"><Period>
  <AdaptationSet contentType="video" mimeType="video/mp4">
    <Representation id="v" codecs="avc1.42c00c" bandwidth="1">
      <SegmentList timescale="1" duration="2">
        <Initialization sourceURL="init.mp4" range="0-99"/>
        <SegmentURL media="s1.m4s" mediaRange="0-199"/>
      </SegmentList>
    </Representation>
  </AdaptationSet>
</Period></MPD>"""
        val rangeDocument = MpdPlanParser.parse(rangeMpd, mpdUrl)
        try {
            MpdPlanParser.buildPlan(rangeDocument, rangeDocument.videoOffers.single())
            fail("expected DashPlanException")
        } catch (expected: DashPlanException) {
            assertTrue(expected.safeReason.contains("字节范围"))
        }
    }

    @Test fun overBudgetSegmentCountIsRejected() {
        // 2-second segments over a 3-hour period exceed the 4096-segment bound.
        val document = MpdPlanParser.parse(templateMpd(duration = "PT3H"), mpdUrl)
        try {
            MpdPlanParser.buildPlan(document, document.videoOffers.first())
            fail("expected DashPlanException")
        } catch (expected: DashPlanException) {
            assertTrue(expected.safeReason.contains("4096"))
        }
    }

    @Test fun openEndedTimelineIsBoundedByPeriodDuration() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT4S"><Period>
  <AdaptationSet contentType="video" mimeType="video/mp4">
    <SegmentTemplate timescale="1" duration="1" initialization="i.mp4" media="s${'$'}Number${'$'}.m4s">
      <SegmentTimeline><S t="0" d="1" r="-1"/></SegmentTimeline>
    </SegmentTemplate>
    <Representation id="v" codecs="avc1.42c00c" bandwidth="1"/>
  </AdaptationSet>
</Period></MPD>"""
        val document = MpdPlanParser.parse(mpd, mpdUrl)
        val plan = MpdPlanParser.buildPlan(document, document.videoOffers.single())
        assertEquals(4, plan.video.segments.size)
    }

    @Test fun timelineDisagreeingWithDurationFailsHonestly() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT10S"><Period>
  <AdaptationSet contentType="video" mimeType="video/mp4">
    <SegmentTemplate timescale="1" media="s${'$'}Number${'$'}.m4s" initialization="i.mp4">
      <SegmentTimeline><S t="0" d="1"/></SegmentTimeline>
    </SegmentTemplate>
    <Representation id="v" codecs="avc1.42c00c" bandwidth="1"/>
  </AdaptationSet>
</Period></MPD>"""
        val document = MpdPlanParser.parse(mpd, mpdUrl)
        try {
            MpdPlanParser.buildPlan(document, document.videoOffers.single()); fail("expected DashPlanException")
        } catch (expected: DashPlanException) { assertTrue(expected.safeReason.contains("不符")) }
    }

    @Test fun defaultOfferPrefersHighestSupportedHeightUpTo1080p() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT4S"><Period>
  <AdaptationSet contentType="video" mimeType="video/mp4">
    <SegmentTemplate timescale="1" duration="4" initialization="i.mp4" media="s${'$'}Number${'$'}.m4s"/>
    <Representation id="big" codecs="avc1.640028" bandwidth="1" height="2160"/>
    <Representation id="mid" codecs="avc1.64001f" bandwidth="1" height="720"/>
    <Representation id="av1" codecs="av01.0.08M.08" bandwidth="1" height="1080"/>
  </AdaptationSet>
</Period></MPD>"""
        val document = MpdPlanParser.parse(mpd, mpdUrl)
        assertEquals("mid", MpdPlanParser.defaultVideoOffer(document.videoOffers)!!.id)
    }

    @Test fun urlResolutionRejectsNonHttpBaseUrls() {
        val mpd = """<MPD type="static" mediaPresentationDuration="PT4S"><Period>
  <AdaptationSet contentType="video" mimeType="video/mp4">
    <BaseURL>ftp://cdn.example/base/</BaseURL>
    <SegmentTemplate timescale="1" duration="4" initialization="i.mp4" media="s${'$'}Number${'$'}.m4s"/>
    <Representation id="v" codecs="avc1.42c00c" bandwidth="1"/>
  </AdaptationSet>
</Period></MPD>"""
        try {
            MpdPlanParser.parse(mpd, mpdUrl)
            fail("expected DashPlanException")
        } catch (expected: DashPlanException) { assertTrue(expected.safeReason.isNotBlank()) }
    }

    @Test fun mpdOverTwoMiBIsRejectedBeforeParsing() {
        try {
            MpdPlanParser.parse(" ".repeat(DashBudgets.MAX_MPD_CHARS + 1), mpdUrl)
            fail("expected DashPlanException")
        } catch (expected: DashPlanException) { assertTrue(expected.safeReason.contains("2 MiB")) }
    }

    @Test fun xsDurationParsingSupportsDayHourMinuteSecond() {
        assertEquals(3_723_500_000L, MpdPlanParser.parseXsDurationUs("PT1H2M3.5S"))
        assertEquals(86_400_000_000L, MpdPlanParser.parseXsDurationUs("P1D"))
        assertEquals(500_000L, MpdPlanParser.parseXsDurationUs("PT0.5S"))
        assertNull(MpdPlanParser.parseXsDurationUs("PT"))
        assertNull(MpdPlanParser.parseXsDurationUs("garbage"))
    }

    @Test fun planValidateRejectsNonHttpSegmentTargets() {
        val document = MpdPlanParser.parse(templateMpd(), mpdUrl)
        val plan = MpdPlanParser.buildPlan(document, document.videoOffers.first { it.id == "v720" })
        plan.validate(allowLocalHttp = true) // https targets are inside the safe range
        val tampered = DashDownloadPlan(
            entryUrl = plan.entryUrl, mpdUrl = plan.mpdUrl,
            video = plan.video.copy(initUrl = "ftp://cdn.example/media/v720/init.mp4"),
            audio = plan.audio, muxed = plan.muxed, durationUs = plan.durationUs,
        )
        try {
            tampered.validate(allowLocalHttp = false); fail("expected TransferFailure")
        } catch (expected: com.example.purebrowser.download.TransferFailure) {
            assertTrue(expected.safeMessage.isNotBlank())
        }
    }
}

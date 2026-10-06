package com.example.purebrowser.download.dash

import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.HttpResponse
import com.example.purebrowser.download.HttpTransport
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.codec.Av1CapabilityProvider
import com.example.purebrowser.media.codec.Av1DecodeSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * T110 offer-layer gating for DASH documents: MpdPlanParser stays capability-blind; DashResolver
 * hides AV1 representations on no-decoder devices instead of greying them out. Fake transport —
 * an authored static MPD only, no site is contacted.
 */
class DashResolverAv1GateTest {
    private val entry = "https://cdn.example/stream.mpd"
    private val mpd = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT4S">
          <Period duration="PT4S">
            <AdaptationSet contentType="video">
              <SegmentTemplate timescale="1" duration="2" initialization="init-${'$'}RepresentationID${'$'}.m4s"
                               media="seg-${'$'}RepresentationID${'$'}-${'$'}Number${'$'}.m4s" startNumber="1"/>
              <Representation id="v1080av1" codecs="av01.0.05M.08" width="1920" height="1080" bandwidth="3000000"/>
              <Representation id="v720" codecs="avc1.4d401f" width="1280" height="720" bandwidth="1800000"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private fun resolver(support: Av1DecodeSupport) = DashResolver(
        transport(), AccessContextProvider { null }, false, Av1CapabilityProvider { support },
    )

    private fun transport(): HttpTransport {
        val bytes = mpd.toByteArray()
        val response = object : HttpResponse {
            override val status = 200
            override fun header(name: String) = if (name == "Content-Length") bytes.size.toString() else null
            override fun body(): InputStream = ByteArrayInputStream(bytes)
            override fun close() {}
        }
        return HttpTransport { _, _, _ -> response }
    }

    private fun draft() = DownloadDraft(
        MediaCandidate(entry, MediaKind.DASH, emptySet(), "application/dash+xml"),
        "FixtureAgent", sourceUrl = "https://www.example.test/talks/fixture",
    )

    private fun offers(support: Av1DecodeSupport) =
        resolver(support).resolveEntry(draft(), TransferCancellation()).document.videoOffers

    @Test fun availableSupportKeepsBothOffersWithParserSemantics() {
        val offers = offers(Av1DecodeSupport.AVAILABLE)
        assertEquals(2, offers.size)
        val av1 = offers.first { it.codecs!!.startsWith("av01") }
        // The fMP4 assembler honestly accepts H.264/AAC only; decoder presence does not fake support.
        assertTrue(!av1.supported)
        assertEquals("AV1 编码本版不支持", av1.unsupportedReason)
        assertTrue(offers.first { it.codecs!!.startsWith("avc1") }.supported)
    }

    @Test fun warnedSupportKeepsBothOffersUnchanged() {
        val offers = offers(Av1DecodeSupport.WARNED)
        assertEquals(2, offers.size)
        assertTrue(!offers.first { it.codecs!!.startsWith("av01") }.supported)
        assertNotNull(MpdPlanParser.defaultVideoOffer(offers))
    }

    @Test fun hiddenSupportRemovesTheAv1OfferEntirely() {
        val offers = offers(Av1DecodeSupport.HIDDEN)
        assertEquals("hidden means absent, not greyed", 1, offers.size)
        val kept = offers.single()
        assertTrue(kept.codecs!!.startsWith("avc1"))
        assertTrue(kept.supported)
        assertEquals("v720", MpdPlanParser.defaultVideoOffer(offers)!!.id)
    }
}

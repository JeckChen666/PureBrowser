package com.example.purebrowser.media

import com.example.purebrowser.media.codec.Av1Capability
import com.example.purebrowser.media.codec.Av1CapabilityProvider
import com.example.purebrowser.media.codec.Av1DecodeSupport
import com.example.purebrowser.media.verify.ProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T110 candidate-layer gating: probe and rule variant lists pass the AV1 policy in the sniffer. */
class ResourceSnifferAv1GateTest {
    private fun fixed(support: Av1DecodeSupport) = Av1CapabilityProvider { support }
    private fun variants() = listOf(
        VariantSummary(1080, 3_000_000L, "av01.0.05M.08,mp4a.40.2", "https://cdn.example/gear1080av1.m3u8", null),
        VariantSummary(720, 1_800_000L, "avc1.4d401f,mp4a.40.2", "https://cdn.example/gear720.m3u8", null),
    )

    private fun verifiedCandidate(sniffer: ResourceSniffer): MediaCandidate {
        val page = sniffer.beginPage()
        sniffer.observe(page, "https://cdn.example/master.m3u8", Evidence.REQUEST)
        sniffer.applyProbeResult("https://cdn.example/master.m3u8", page,
            ProbeResult.Verified(null, false, "application/vnd.apple.mpegurl", MediaKind.HLS, variants()))
        return sniffer.candidates.value.single()
    }

    @Test fun hiddenSupportDropsAv1VariantsFromProbeResults() {
        val candidate = verifiedCandidate(ResourceSniffer(fixed(Av1DecodeSupport.HIDDEN)))
        assertEquals(1, candidate.variants!!.size)
        assertEquals("avc1.4d401f,mp4a.40.2", candidate.variants!!.single().codecs)
    }

    @Test fun warnedSupportAnnotatesTheAv1VariantWarning() {
        val candidate = verifiedCandidate(ResourceSniffer(fixed(Av1DecodeSupport.WARNED)))
        assertEquals(2, candidate.variants!!.size)
        val av1 = candidate.variants!!.first { it.codecs!!.startsWith("av01") }
        assertEquals(Av1Capability.SOFTWARE_DECODE_WARNING, av1.warning)
        assertNull(candidate.variants!!.first { it.codecs!!.startsWith("avc1") }.warning)
    }

    @Test fun availableSupportKeepsTheParserVerdictUntouched() {
        val candidate = verifiedCandidate(ResourceSniffer(fixed(Av1DecodeSupport.AVAILABLE)))
        assertEquals(2, candidate.variants!!.size)
        assertNull(candidate.variants!!.first().warning)
    }

    @Test fun ruleVariantsPassTheSameGate() {
        val sniffer = ResourceSniffer(fixed(Av1DecodeSupport.HIDDEN))
        val page = sniffer.beginPage()
        sniffer.observe(page, "https://cdn.example/rule-master.m3u8", Evidence.RULE)
        sniffer.attachRuleVariants(page, "https://cdn.example/rule-master.m3u8", MediaKind.HLS, variants())
        // Rule tier normally carries codecs=null; an AV1-declaring finding still gates the same way.
        val gated = sniffer.candidates.value.single().variants!!
        assertEquals(1, gated.size)
        assertEquals("avc1.4d401f,mp4a.40.2", gated.single().codecs)
    }

    @Test fun allAv1ListOnHiddenDeviceAttachesNothing() {
        val sniffer = ResourceSniffer(fixed(Av1DecodeSupport.HIDDEN))
        val page = sniffer.beginPage()
        sniffer.observe(page, "https://cdn.example/only-av1.m3u8", Evidence.REQUEST)
        sniffer.applyProbeResult("https://cdn.example/only-av1.m3u8", page,
            ProbeResult.Verified(null, false, "application/vnd.apple.mpegurl", MediaKind.HLS,
                listOf(VariantSummary(720, 1L, "av01.0.05M.08", "https://cdn.example/only.m3u8", null))))
        // The candidate itself stays verified; its variant surface is simply empty (absent, not greyed).
        val candidate = sniffer.candidates.value.single()
        assertEquals(ProbeState.VERIFIED, candidate.probeState)
        assertEquals(0, candidate.variants!!.size)
    }
}

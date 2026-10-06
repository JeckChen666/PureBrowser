package com.example.purebrowser.media.codec

import com.example.purebrowser.media.VariantSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T110 pure-decision table tests: capability enum, codec detection, and list policies. */
class Av1CapabilityTest {
    private fun summary(codecs: String?, warning: String? = null) =
        VariantSummary(height = 720, bandwidth = 1_000L, codecs = codecs, url = "https://cdn.example/v", warning = warning)

    // ---------------------------------------------------------------- evaluate table

    @Test fun capabilityTableCoversEveryRule() {
        val table = listOf(
            Triple(28, true to true, Av1DecodeSupport.HIDDEN),    // API<29 hides regardless of decoders
            Triple(28, true to false, Av1DecodeSupport.HIDDEN),
            Triple(28, false to true, Av1DecodeSupport.HIDDEN),
            Triple(29, false to false, Av1DecodeSupport.HIDDEN),  // no decoder at all
            Triple(30, false to false, Av1DecodeSupport.HIDDEN),
            Triple(34, false to false, Av1DecodeSupport.HIDDEN),
            Triple(29, false to true, Av1DecodeSupport.WARNED),   // software-only
            Triple(33, false to true, Av1DecodeSupport.WARNED),
            Triple(29, true to false, Av1DecodeSupport.AVAILABLE),
            Triple(30, true to true, Av1DecodeSupport.AVAILABLE), // hardware wins over software
            Triple(34, true to false, Av1DecodeSupport.AVAILABLE),
            Triple(35, true to true, Av1DecodeSupport.AVAILABLE),
        )
        table.forEach { (api, decoders, expected) ->
            assertEquals("api=$api hw=${decoders.first} sw=${decoders.second}", expected,
                Av1Capability.evaluate(decoders.first, decoders.second, api))
        }
    }

    // ---------------------------------------------------------------- codec / mime detection

    @Test fun av01TokensAreDetectedInCodecLists() {
        assertTrue(Av1Capability.isAv1Codecs("av01.0.05M.08"))
        assertTrue(Av1Capability.isAv1Codecs("avc1.640028,av01.0.05M.08"))
        assertTrue(Av1Capability.isAv1Codecs("av01.0.05M.08,mp4a.40.2"))
        assertTrue(Av1Capability.isAv1Codecs(" AV01.0.05M.08 "))
        assertTrue(Av1Capability.isAv1Codecs("av01"))
        assertFalse(Av1Capability.isAv1Codecs(null))
        assertFalse(Av1Capability.isAv1Codecs("avc1.640028"))
        assertFalse(Av1Capability.isAv1Codecs("avc1.4d401f,mp4a.40.2"))
        assertFalse(Av1Capability.isAv1Codecs("vp09.00.10.08"))
        assertFalse(Av1Capability.isAv1Codecs("hev1.1.6.L93.B0"))
        assertFalse(Av1Capability.isAv1Codecs("mp4a.40.2"))
    }

    @Test fun av1TrackMimeIsDetected() {
        assertTrue(Av1Capability.isAv1Mime("video/av01"))
        assertTrue(Av1Capability.isAv1Mime("VIDEO/AV01"))
        assertFalse(Av1Capability.isAv1Mime("video/avc"))
        assertFalse(Av1Capability.isAv1Mime(null))
        assertFalse(Av1Capability.isAv1Mime(""))
    }

    // ---------------------------------------------------------------- hidden / annotated

    @Test fun hiddenAppliesOnlyToAv1EntriesUnderHiddenSupport() {
        assertTrue(Av1Capability.hidden("av01.0.05M.08", Av1DecodeSupport.HIDDEN))
        assertTrue(Av1Capability.hidden("avc1.640028,av01", Av1DecodeSupport.HIDDEN))
        assertFalse(Av1Capability.hidden("av01.0.05M.08", Av1DecodeSupport.WARNED))
        assertFalse(Av1Capability.hidden("av01.0.05M.08", Av1DecodeSupport.AVAILABLE))
        assertFalse(Av1Capability.hidden("avc1.640028", Av1DecodeSupport.HIDDEN))
        assertFalse(Av1Capability.hidden(null, Av1DecodeSupport.HIDDEN))
    }

    @Test fun warnedAnnotatesTheFixedTextAndKeepsTheExistingWarning() {
        assertEquals(Av1Capability.SOFTWARE_DECODE_WARNING, Av1Capability.annotated(null, Av1DecodeSupport.WARNED))
        assertEquals("原提示；${Av1Capability.SOFTWARE_DECODE_WARNING}",
            Av1Capability.annotated("原提示", Av1DecodeSupport.WARNED))
        assertNull(Av1Capability.annotated(null, Av1DecodeSupport.HIDDEN))
        assertNull(Av1Capability.annotated(null, Av1DecodeSupport.AVAILABLE))
        assertEquals("原提示", Av1Capability.annotated("原提示", Av1DecodeSupport.AVAILABLE))
        assertEquals("此档位为 AV1，本机仅软件解码，保存与播放可能缓慢", Av1Capability.SOFTWARE_DECODE_WARNING)
    }

    // ---------------------------------------------------------------- summary list policy

    @Test fun hiddenSupportDropsAv1EntriesAndKeepsTheRest() {
        val variants = listOf(
            summary("av01.0.05M.08"),
            summary("avc1.640028,mp4a.40.2", warning = "既有提示"),
            summary("vp09.00.10.08"),
        )
        val gated = Av1Capability.applyToSummaries(variants, Av1DecodeSupport.HIDDEN)
        assertEquals(2, gated.size)
        assertEquals("avc1.640028,mp4a.40.2", gated[0].codecs)
        assertNull(gated[1].warning) // untouched entry keeps its (null) warning
    }

    @Test fun hiddenSupportMayGateAnAllAv1ListToEmpty() {
        val variants = listOf(summary("av01.0.05M.08"), summary("av01.0.08M.08"))
        assertTrue(Av1Capability.applyToSummaries(variants, Av1DecodeSupport.HIDDEN).isEmpty())
    }

    @Test fun warnedSupportAnnotatesAv1WarningsOnly() {
        val variants = listOf(
            summary("av01.0.05M.08"),
            summary("av01.0.08M.08", warning = "既有提示"),
            summary("avc1.640028"),
        )
        val gated = Av1Capability.applyToSummaries(variants, Av1DecodeSupport.WARNED)
        assertEquals(3, gated.size)
        assertEquals(Av1Capability.SOFTWARE_DECODE_WARNING, gated[0].warning)
        assertEquals("既有提示；${Av1Capability.SOFTWARE_DECODE_WARNING}", gated[1].warning)
        assertNull(gated[2].warning)
    }

    @Test fun availableSupportReturnsTheSameInstanceUntouched() {
        val variants = listOf(summary("av01.0.05M.08"), summary("avc1.640028"))
        assertSame(variants, Av1Capability.applyToSummaries(variants, Av1DecodeSupport.AVAILABLE))
    }

    @Test fun warnedNoAv1EntriesReturnsSameInstance() {
        val variants = listOf(summary("avc1.640028"), summary(null))
        assertSame(variants, Av1Capability.applyToSummaries(variants, Av1DecodeSupport.WARNED))
        assertNotSame(variants, Av1Capability.applyToSummaries(listOf(summary("av01")), Av1DecodeSupport.WARNED))
    }

    @Test fun emptyListStaysEmptyUnderEverySupport() {
        Av1DecodeSupport.values().forEach { support ->
            assertTrue(Av1Capability.applyToSummaries(emptyList(), support).isEmpty())
        }
    }

    // ---------------------------------------------------------------- finished-product defense

    @Test fun finishedProductRejectsAv01OnlyWhenHidden() {
        assertTrue(Av1Capability.rejectsFinishedProduct("video/av01", Av1DecodeSupport.HIDDEN))
        assertFalse(Av1Capability.rejectsFinishedProduct("video/av01", Av1DecodeSupport.WARNED))
        assertFalse(Av1Capability.rejectsFinishedProduct("video/av01", Av1DecodeSupport.AVAILABLE))
        assertFalse(Av1Capability.rejectsFinishedProduct("video/avc", Av1DecodeSupport.HIDDEN))
        assertFalse(Av1Capability.rejectsFinishedProduct(null, Av1DecodeSupport.HIDDEN))
        assertFalse(Av1Capability.rejectsFinishedProduct("", Av1DecodeSupport.HIDDEN))
    }
}

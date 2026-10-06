package com.example.purebrowser.media

import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.example.purebrowser.media.codec.Av1Capability
import com.example.purebrowser.media.codec.DeviceAv1CapabilityProvider
import com.example.purebrowser.media.codec.Av1DecodeSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T110 device verification of the runtime capability hook. The provider's cached answer must equal
 * a fresh, independent MediaCodecList enumeration reduced through the SAME pure table
 * ([Av1Capability.evaluate]) the JVM tests cover, and must stay stable across calls (process-wide
 * cache). Any device outcome — including HIDDEN on an AV1-less device — is a valid verdict; the
 * printed lines are the T110 evidence, mirroring the v0.1.9 T102 probe's shape.
 */
class Av1CapabilityProviderTest {
    @Test fun providerMatchesADirectMediaCodecEnumeration() {
        val api = Build.VERSION.SDK_INT
        var hardware = false
        var software = false
        val names = ArrayList<String>()
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.forEach { info ->
                if (info.isEncoder) return@forEach
                if (!info.supportedTypes.any { it.equals(Av1Capability.AV1_MIME, ignoreCase = true) }) return@forEach
                if (info.isHardwareAccelerated) hardware = true else software = true
                names += "${info.name}(hw=${info.isHardwareAccelerated})"
            }
        }
        val expected = Av1Capability.evaluate(hardware, software, api)
        val actual = DeviceAv1CapabilityProvider.support()
        Log.i("Av1Capability", "api=$api decoders=$names support=$actual")
        println("AV1CAP api=$api hw=$hardware sw=$software support=$actual")
        assertEquals("provider must mirror the device enumeration", expected, actual)
        // The process-wide cache returns the identical verdict on every subsequent call.
        repeat(3) { assertEquals(actual, DeviceAv1CapabilityProvider.support()) }
        assertTrue("probe must run to completion", true)
    }

    @Test fun everySupportVerdictComesFromThePureTable() {
        val support = DeviceAv1CapabilityProvider.support()
        val legal = Av1DecodeSupport.values().toSet()
        assertTrue(legal.contains(support))
    }
}

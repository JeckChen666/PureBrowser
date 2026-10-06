package com.example.purebrowser.media.codec

import android.media.MediaCodecList
import android.os.Build

/**
 * T110 production capability hook: reads the device's real AV1 decode story once per process and
 * caches it — MediaCodecList enumeration is not cheap and codec availability does not change while
 * the process lives. Reuses the v0.1.9 T102 probe's query shape (`MediaCodecList(ALL_CODECS)`,
 * MIME [Av1Capability.AV1_MIME], hardware/software split) so device evidence and runtime gating
 * can be cross-checked by [com.example.purebrowser.media.Av1CapabilityProviderTest].
 *
 * Any failure reading the platform (vendor bugs, stubs in JVM unit contexts) resolves to HIDDEN:
 * when capability is unknown, AV1 stays hidden rather than offered unverifiable.
 */
object DeviceAv1CapabilityProvider : Av1CapabilityProvider {
    @Volatile
    private var cached: Av1DecodeSupport? = null

    override fun support(): Av1DecodeSupport = cached ?: synchronized(this) {
        cached ?: readDevice().also { cached = it }
    }

    private fun readDevice(): Av1DecodeSupport = runCatching {
        val api = Build.VERSION.SDK_INT
        // isHardwareAccelerated needs API 29; below it the policy is HIDDEN before any query.
        if (api < 29) return@runCatching Av1DecodeSupport.HIDDEN
        var hardware = false
        var software = false
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.forEach { info ->
            if (info.isEncoder) return@forEach
            if (!info.supportedTypes.any { it.equals(Av1Capability.AV1_MIME, ignoreCase = true) }) return@forEach
            // isHardwareAccelerated needs API 29; on older levels every listed decoder is software.
            val hw = if (android.os.Build.VERSION.SDK_INT >= 29) {
                runCatching { info.isHardwareAccelerated }.getOrDefault(false)
            } else false
            if (hw) hardware = true else software = true
        }
        Av1Capability.evaluate(hardware, software, api)
    }.getOrDefault(Av1DecodeSupport.HIDDEN)
}

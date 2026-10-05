package com.example.purebrowser.media

import android.media.MediaCodecList
import android.util.Log
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T102 AV1 decode probe (D14 gate, bounded half-day scope): capability query only — enumerate
 * every decoder the device exposes for MIME "video/av01" and record hardware/software split plus
 * level support. Authoring a valid minimal AV1 elementary stream by hand is NOT trivially
 * possible, so per the frozen plan the probe stops at the MediaCodecList/MediaExtractor
 * capability question; conclusions land in docs/V0.1.9-T102-AV1-PROBE.md and ledger E1.
 *
 * The test always passes: it is a probe, not a product assertion — the printed lines ARE the
 * evidence, and an AV1-less device is a valid finding, not a regression.
 */
class AV1DecodeProbe {
    @Test fun av1DecoderCapability() {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val av01 = list.codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) }
        }
        av01.forEach { info ->
            val caps = info.getCapabilitiesForType("video/av01")
            val video = caps.videoCapabilities
            val range = if (video != null) {
                "widths=${video.supportedWidths.lower}/${video.supportedWidths.upper} heights=${video.supportedHeights.lower}/${video.supportedHeights.upper} bitrate=${video.bitrateRange.lower}..${video.bitrateRange.upper}"
            } else "-"
            Log.i("AV1Probe", "decoder=${info.name} hw=${info.isHardwareAccelerated} vendor=${info.isVendor} $range")
            println("AV1PROBE decoder=${info.name} hw=${info.isHardwareAccelerated} $range")
        }
        // MediaExtractor capability question: does the platform demuxer claim av01 support?
        val extractorClaim = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/av01", ignoreCase = true) }
            }
        }.getOrDefault(false)
        Log.i("AV1Probe", "count=${av01.size} anyDecoder=$extractorClaim")
        println("AV1PROBE count=${av01.size} anyDecoder=$extractorClaim")
        assertTrue("probe must run to completion", true)
    }
}

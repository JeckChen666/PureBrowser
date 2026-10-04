package com.example.purebrowser.download.mux

import android.media.MediaCodecInfo
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import com.example.purebrowser.download.mux.DualTrackMuxer.Companion.requireInput
import java.nio.ByteBuffer

/** Container MIME alone is insufficient: AAC-LC must be established from AudioSpecificConfig. */
@androidx.annotation.OptIn(UnstableApi::class)
internal object TrackFormats {
    private const val MAX_CSD_BYTES = 64 * 1024
    private val sampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350)

    fun convert(source: MediaFormat, video: Boolean): Format {
        val mime = source.getString(MediaFormat.KEY_MIME)
        requireInput(mime == if (video) MimeTypes.VIDEO_H264 else MimeTypes.AUDIO_AAC,
            "Only H.264 video and AAC-LC audio are supported")
        if (source.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            requireInput(source.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) in 1..DualTrackMuxer.MAX_SAMPLE_BYTES,
                "Declared sample size exceeds budget")
        }
        val csd = (0..2).mapNotNull { index ->
            val key = "csd-$index"
            if (source.containsKey(key)) copyCsd(source.getByteBuffer(key)) else null
        }
        requireInput(csd.isNotEmpty() && csd.sumOf { it.size } <= MAX_CSD_BYTES, "Missing or excessive codec configuration")
        val builder = Format.Builder().setSampleMimeType(mime).setInitializationData(csd)
        if (video) {
            requireInput(csd.size <= 2, "Unexpected AVC configuration")
            var sps = false
            var pps = false
            csd.forEach { bytes ->
                nalTypes(bytes).forEach { type ->
                    requireInput(type == 7 || type == 8, "Unsupported AVC configuration NAL")
                    sps = sps || type == 7
                    pps = pps || type == 8
                }
            }
            requireInput(sps && pps, "AVC configuration requires SPS and PPS")
            val width = source.getInteger(MediaFormat.KEY_WIDTH)
            val height = source.getInteger(MediaFormat.KEY_HEIGHT)
            requireInput(width in 1..1920 && height in 1..1920 && width.toLong() * height <= 1920L * 1080,
                "Video exceeds the 1080p subset")
            builder.setWidth(width).setHeight(height)
        } else {
            requireInput(csd.size == 1, "Unexpected AAC configuration")
            val rate = source.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = source.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            requireInput(rate in 7350..96000 && channels in 1..8, "Invalid AAC sample rate or channels")
            if (source.containsKey(MediaFormat.KEY_AAC_PROFILE)) requireInput(
                source.getInteger(MediaFormat.KEY_AAC_PROFILE) == MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                "Non-LC AAC profile")
            validateAacLc(csd.single(), rate, channels)
            builder.setSampleRate(rate).setChannelCount(channels)
        }
        return builder.build()
    }

    private fun copyCsd(buffer: ByteBuffer?): ByteArray {
        requireInput(buffer != null && buffer.remaining() in 1..MAX_CSD_BYTES, "Invalid codec configuration size")
        val copy = buffer!!.duplicate()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    /** MediaExtractor's AVC configuration and access units use Annex B start codes. */
    private fun nalTypes(bytes: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var position = 0
        while (position < bytes.size) {
            val prefix = when {
                position + 4 <= bytes.size && bytes[position] == 0.toByte() && bytes[position + 1] == 0.toByte() &&
                    bytes[position + 2] == 0.toByte() && bytes[position + 3] == 1.toByte() -> 4
                position + 3 <= bytes.size && bytes[position] == 0.toByte() && bytes[position + 1] == 0.toByte() &&
                    bytes[position + 2] == 1.toByte() -> 3
                else -> 0
            }
            requireInput(prefix > 0 && position + prefix < bytes.size, "Malformed AVC configuration")
            position += prefix
            requireInput(bytes[position].toInt() and 0x80 == 0, "Invalid AVC NAL header")
            result.add(bytes[position].toInt() and 0x1f)
            // At least a header and payload; the next NAL starts at a 3- or 4-byte start code.
            val payloadStart = position++
            while (position < bytes.size && !(position + 3 <= bytes.size &&
                    bytes[position] == 0.toByte() && bytes[position + 1] == 0.toByte() &&
                    (bytes[position + 2] == 1.toByte() || (position + 4 <= bytes.size &&
                        bytes[position + 2] == 0.toByte() && bytes[position + 3] == 1.toByte())))) position++
            requireInput(position - payloadStart >= 2, "Empty AVC parameter set")
        }
        return result
    }

    internal fun validateAacLc(bytes: ByteArray, expectedRate: Int, expectedChannels: Int) {
        requireInput(bytes.size in 2..MAX_CSD_BYTES, "Missing AAC AudioSpecificConfig")
        val bits = Bits(bytes)
        requireInput(bits.read(5) == 2, "Only AAC-LC object type is supported")
        val rateIndex = bits.read(4)
        val rate = if (rateIndex == 15) bits.read(24) else {
            requireInput(rateIndex < sampleRates.size, "Reserved AAC sample rate")
            sampleRates[rateIndex]
        }
        val channelConfig = bits.read(4)
        val channels = when (channelConfig) { in 1..6 -> channelConfig; 7 -> 8; else -> 0 }
        requireInput(rate == expectedRate && channels == expectedChannels && channels > 0,
            "AAC configuration does not match container parameters")
        // GASpecificConfig: frameLengthFlag (1024 only), dependsOnCoreCoder, extensionFlag.
        requireInput(bits.read(3) == 0, "Unsupported AAC frame length, core coder or extension")
        if (bits.remaining >= 16) {
            val sync = bits.read(11)
            if (sync == 0x2b7) {
                requireInput(bits.read(5) == 5 && bits.read(1) == 0,
                    "HE-AAC/SBR is unsupported")
            } else requireInput(sync == 0, "Unsupported AAC sync extension")
        }
        // Includes rejecting PS and non-zero unrecognized extensions. Zero byte padding is legal.
        while (bits.remaining > 0) requireInput(bits.read(1) == 0, "Unsupported AAC extension")
    }

    private class Bits(private val bytes: ByteArray) {
        private var position = 0
        val remaining get() = bytes.size * 8 - position
        fun read(count: Int): Int {
            requireInput(remaining >= count, "Truncated AAC configuration")
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((bytes[position / 8].toInt() ushr (7 - position % 8)) and 1)
                position++
            }
            return value
        }
    }
}

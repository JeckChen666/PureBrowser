package com.example.purebrowser.download

import com.example.purebrowser.download.site.DualTrackMetadata
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Bounded structural gate for this engine's clear AVC/AAC MP4 output, not a general MP4 parser.
 * Skips media payloads; never searches arbitrary sample bytes for four-character markers.
 * Recognizes protection boxes independently of Android's version-0-only PSSH reporting.
 * Malformed/over-budget structure fails closed. No keys, licenses or decryption are involved.
 */
internal object DualTrackMp4Protection {
    fun clear(channel: FileChannel, cancel: TransferCancellation? = null): Boolean = Scanner(channel, cancel).clear()

    private class Scanner(private val channel: FileChannel, private val cancel: TransferCancellation?) {
        private val size = channel.size()
        private val header = ByteBuffer.allocate(8)
        private var boxes = 0
        private var headerBytes = 0
        private var sampleDescriptions = 0

        fun clear(): Boolean {
            if(size !in 8..(DualTrackMetadata.MAX_VIDEO_BYTES + DualTrackMetadata.MAX_AUDIO_BYTES +
                    DualTrackMetadata.OUTPUT_OVERHEAD_BYTES))return false
            return scan(0, size, 0) && sampleDescriptions > 0
        }

        private fun scan(start: Long, end: Long, depth: Int, entries: Long? = null): Boolean {
            if(depth > 16)throw IOException("双轨 MP4 结构超出预算")
            var position = start
            var seen = 0L
            while(position < end) {
                cancel?.check()
                if(++boxes > 8192 || end - position < 8)throw IOException("双轨 MP4 结构无效或超出预算")
                channel.position(position)
                read(8)
                val shortSize = header.int.toLong() and 0xffffffffL
                val type = ByteArray(4).also(header::get).toString(Charsets.US_ASCII)
                var headerSize = 8L
                val boxSize = when(shortSize) {
                    0L -> end - position
                    1L -> { read(8); headerSize = 16; header.long }
                    else -> shortSize
                }
                if(boxSize < headerSize || boxSize > end - position)throw IOException("双轨 MP4 结构无效")
                val payload = position + headerSize
                val boxEnd = position + boxSize
                if(type in PROTECTION_BOXES)return false
                when {
                    type in CONTAINERS -> if(!scan(payload, boxEnd, depth + 1))return false
                    type == "meta" -> {
                        if(boxEnd - payload < 4)throw IOException("双轨 MP4 结构无效")
                        if(!scan(payload + 4, boxEnd, depth + 1))return false
                    }
                    type == "stsd" -> {
                        if(boxEnd - payload < 8)throw IOException("双轨 MP4 轨道描述无效")
                        channel.position(payload); read(8)
                        val versionFlags = header.int
                        val count = header.int.toLong() and 0xffffffffL
                        if(versionFlags != 0 || count !in 1..16)throw IOException("双轨 MP4 轨道描述不受支持")
                        sampleDescriptions++
                        if(!scan(payload + 8, boxEnd, depth + 1, count))return false
                    }
                    entries != null -> {
                        // Only the clear sample entry subset emitted by the companion muxer.
                        val fixed = when(type) { "avc1", "avc3" -> 78L; "mp4a" -> 28L; else -> return false }
                        if(boxEnd - payload < fixed)throw IOException("双轨 MP4 轨道描述无效")
                        if(type == "mp4a") {
                            channel.position(payload + 8); read(2)
                            if(header.short.toInt() != 0)return false // no QuickTime extended audio entries
                        }
                        if(!scan(payload + fixed, boxEnd, depth + 1))return false
                    }
                    type in setOf("sgpd", "sbgp") -> {
                        if(boxEnd - payload < 8)throw IOException("双轨 MP4 样本组无效")
                        channel.position(payload + 4); read(4)
                        val group = ByteArray(4).also(header::get).toString(Charsets.US_ASCII)
                        if(group == "seig")return false
                    }
                }
                position = boxEnd
                seen++
            }
            if(position != end || (entries != null && seen != entries))throw IOException("双轨 MP4 结构无效")
            return true
        }

        private fun read(count: Int) {
            header.clear(); header.limit(count)
            while(header.hasRemaining()) {
                cancel?.check()
                val n = channel.read(header)
                if(n < 0)throw IOException("双轨 MP4 结构不完整")
                if(n == 0)throw IOException("双轨 MP4 结构无法读取")
                headerBytes += n
                if(headerBytes > 1024 * 1024)throw IOException("双轨 MP4 结构超出预算")
            }
            header.flip()
        }
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "moof", "traf", "udta", "sinf", "schi")
    // UUID extensions are deliberately outside this bounded clear-output subset (including PIFF).
    private val PROTECTION_BOXES = setOf("pssh", "encv", "enca", "sinf", "schi", "schm", "tenc", "senc", "saiz", "saio", "uuid")
}

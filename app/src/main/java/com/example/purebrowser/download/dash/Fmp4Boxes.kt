package com.example.purebrowser.download.dash

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * T96 pure structural validation for init + media segment byte streams (fMP4/CMAF). A walk of the
 * top-level ISO-BMFF boxes proves each fetched file is a complete, untruncated sequence of boxes:
 * the init segment must carry ftyp + moov (sidx optional), each media segment must start with a
 * fragment header (styp/prft/sidx/moof), contain at least one moof and one mdat, and no mdat may
 * appear before the first moof (moof/mdat ordering). sidx presence is optional and never required.
 * Pure Kotlin so truncation negatives are JVM-unit-testable.
 */
object Fmp4Boxes {
    private const val MAX_BOXES_PER_FILE = 65536

    data class Box(val type: String, val size: Long)

    /** Reads the ordered top-level box types; fails on truncation, absurd sizes or box-count abuse. */
    fun topLevelBoxes(input: InputStream, fileNameForDiagnostics: String = "分片"): List<Box> {
        val result = ArrayList<Box>()
        while (true) {
            val header = ByteArray(8)
            if (!readFully(input, header)) {
                if (result.isEmpty()) throw IOException("$fileNameForDiagnostics 不是 fMP4 数据")
                break // clean EOF exactly at a box boundary
            }
            if (result.size >= MAX_BOXES_PER_FILE) throw IOException("$fileNameForDiagnostics box 数量异常")
            var size = u32(header, 0)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerSize = 8L
            if (size == 1L) {
                val large = ByteArray(8)
                if (!readFully(input, large)) throw IOException("$fileNameForDiagnostics 头部被截断")
                size = u64(large, 0)
                headerSize = 16
            }
            if (size < headerSize) throw IOException("$fileNameForDiagnostics box 尺寸无效")
            result += Box(type, size)
            val payload = size - headerSize
            var remaining = payload
            val skip = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(skip, 0, minOf(remaining, skip.size.toLong()).toInt())
                if (n < 0) throw IOException("$fileNameForDiagnostics 不完整（被截断）")
                remaining -= n
            }
        }
        return result
    }

    fun topLevelBoxes(file: File, fileNameForDiagnostics: String = "分片"): List<Box> =
        file.inputStream().use { topLevelBoxes(it, fileNameForDiagnostics) }

    /** Init segment: ftyp then moov (both required), sidx/free/wide allowed, mdat forbidden. */
    fun validateInit(boxes: List<Box>) {
        val types = boxes.map { it.type }
        require(types.isNotEmpty() && types.first() == "ftyp") { "初始化分片缺少 ftyp" }
        require("moov" in types) { "初始化分片缺少 moov" }
        require("mdat" !in types) { "初始化分片不应包含媒体数据" }
    }

    /** Media segment: fragment header first, moof present, mdat present and after the first moof. */
    fun validateSegment(boxes: List<Box>) {
        val types = boxes.map { it.type }
        require(types.isNotEmpty() && types.first() in setOf("styp", "prft", "sidx", "moof")) {
            "分片不是 fMP4 片段（缺少片段头）"
        }
        val firstMoof = types.indexOfFirst { it == "moof" }
        val firstMdat = types.indexOfFirst { it == "mdat" }
        require(firstMoof >= 0) { "分片缺少 moof 片段" }
        require(firstMdat >= 0) { "分片缺少 mdat 媒体数据" }
        require(firstMdat > firstMoof) { "分片 mdat 出现在 moof 之前" }
    }

    /** Split an assembled fMP4 stream file into init bytes and per-moof segment byte ranges. */
    fun splitRanges(boxes: List<Box>, totalBytes: Long): Pair<LongRange, List<LongRange>> {
        var offset = 0L
        var initEnd = -1L
        val segments = ArrayList<LongRange>()
        var segmentStart = -1L
        boxes.forEach { box ->
            if (box.type == "moof") {
                if (initEnd < 0) initEnd = offset
                if (segmentStart >= 0) segments += segmentStart until offset
                segmentStart = offset
            }
            offset += box.size
        }
        if (initEnd < 0 || segmentStart < 0) throw IOException("fMP4 流缺少分片")
        segments += segmentStart until totalBytes
        return 0 until initEnd to segments
    }

    private fun readFully(input: InputStream, target: ByteArray): Boolean {
        var done = 0
        while (done < target.size) {
            val n = input.read(target, done, target.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
        return value
    }

    private fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
        return value
    }
}

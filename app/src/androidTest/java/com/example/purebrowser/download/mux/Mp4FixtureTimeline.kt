package com.example.purebrowser.download.mux

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test-only ISO-BMFF empty-edit authoring. A single-track platform muxer normalizes its first PTS,
 * so supplying positive timestamps does not reliably author delayed tracks. Leave sample data,
 * media duration and chunk offsets untouched; preserve any existing B-frame/gapless trim edit.
 * The replacement moov is appended and the old one becomes free, avoiding relocation of mdat.
 */
internal object Mp4FixtureTimeline {
    private const val MAX_MOOV = 8 * 1024 * 1024
    private data class Box(val type: String, val bytes: ByteArray)

    fun delay(file: File, offsetUs: Long) {
        require(offsetUs in 1..10_000_000)
        RandomAccessFile(file, "rw").use { reader ->
            var position = 0L
            var moovPosition = -1L
            var moov: ByteArray? = null
            while (position < reader.length()) {
                reader.seek(position)
                val shortSize = reader.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also { reader.readFully(it) }.toString(Charsets.US_ASCII)
                val size = when (shortSize) { 0L -> reader.length() - position; 1L -> reader.readLong(); else -> shortSize }
                require(size >= 8 && size <= reader.length() - position)
                if (type == "moov") {
                    require(moov == null && shortSize != 1L && size <= MAX_MOOV)
                    moovPosition = position
                    reader.seek(position)
                    moov = ByteArray(size.toInt()).also { reader.readFully(it) }
                }
                position += size
            }
            val root = children(requireNotNull(moov))
            val movieHeader = root.single { it.type == "mvhd" }
            val movieScale = scale(movieHeader.bytes)
            val delayTicks = (offsetUs * movieScale + 500_000) / 1_000_000
            require(delayTicks > 0)
            require(root.count { it.type == "trak" } == 1)
            val replacement = box("moov", root.map { child ->
                when (child.type) {
                    "trak" -> shiftedTrack(child.bytes, movieScale, delayTicks)
                    "mvhd" -> extendHeaderDuration(child.bytes, delayTicks, track = false)
                    else -> child.bytes
                }
            })
            require(replacement.size <= MAX_MOOV)
            // Publish the replacement first, then turn the old moov into a free box.
            reader.seek(reader.length())
            reader.write(replacement)
            reader.seek(moovPosition + 4)
            reader.write("free".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun shiftedTrack(track: ByteArray, movieScale: Long, delayTicks: Long): ByteArray {
        val children = children(track)
        val mdhd = children(children.single { it.type == "mdia" }.bytes).single { it.type == "mdhd" }.bytes
        val mediaScale = scale(mdhd)
        val mediaDuration = duration(mdhd, track = false)
        var editDuration = (mediaDuration * movieScale + mediaScale - 1) / mediaScale
        var trim = 0L
        val existing = children.firstOrNull { it.type == "edts" }
        if (existing != null) {
            val elst = children(existing.bytes).single { it.type == "elst" }.bytes
            val data = buffer(elst)
            val version = elst[8].toInt() and 0xff
            require(version in 0..1 && data.getInt(12) == 1)
            editDuration = if (version == 1) data.getLong(16) else data.getInt(16).toLong() and 0xffffffffL
            trim = if (version == 1) data.getLong(24) else data.getInt(20).toLong()
            require(trim >= 0)
        }
        val edits = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN)
            .putInt(1 shl 24).putInt(2)
            .putLong(delayTicks).putLong(-1).putShort(1).putShort(0)
            .putLong(editDuration).putLong(trim).putShort(1).putShort(0).array()
        val editBox = box("edts", listOf(box("elst", listOf(edits))))
        return box("trak", children.filter { it.type != "edts" }.map {
            if (it.type == "tkhd") extendHeaderDuration(it.bytes, delayTicks, track = true) else it.bytes
        } + editBox)
    }

    private fun children(parent: ByteArray): List<Box> {
        require(parent.size in 8..MAX_MOOV)
        val result = mutableListOf<Box>()
        val data = buffer(parent)
        var offset = 8
        while (offset < parent.size) {
            require(offset <= parent.size - 8 && result.size < 256)
            val size = data.getInt(offset)
            require(size >= 8 && size <= parent.size - offset)
            result.add(Box(parent.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII),
                parent.copyOfRange(offset, offset + size)))
            offset += size
        }
        return result
    }
    private fun box(type: String, payload: List<ByteArray>): ByteArray {
        val size = 8 + payload.sumOf { it.size }
        require(size <= MAX_MOOV)
        return ByteArrayOutputStream(size).apply {
            write(ByteBuffer.allocate(8).putInt(size).put(type.toByteArray(Charsets.US_ASCII)).array())
            payload.forEach { write(it) }
        }.toByteArray()
    }
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    private fun scale(header: ByteArray): Long {
        val version = header[8].toInt() and 0xff
        require(version in 0..1)
        return (buffer(header).getInt(if (version == 1) 28 else 20).toLong() and 0xffffffffL).also { require(it > 0) }
    }
    private fun durationOffset(header: ByteArray, track: Boolean) =
        if (track) { if (header[8] == 1.toByte()) 36 else 28 } else { if (header[8] == 1.toByte()) 32 else 24 }
    private fun duration(header: ByteArray, track: Boolean): Long {
        val at = durationOffset(header, track)
        return if (header[8] == 1.toByte()) buffer(header).getLong(at) else buffer(header).getInt(at).toLong() and 0xffffffffL
    }
    private fun extendHeaderDuration(header: ByteArray, ticks: Long, track: Boolean): ByteArray {
        val copy = header.copyOf()
        val value = duration(header, track) + ticks
        require(value > 0)
        if (header[8] == 1.toByte()) buffer(copy).putLong(durationOffset(copy, track), value)
        else { require(value <= 0xffffffffL); buffer(copy).putInt(durationOffset(copy, track), value.toInt()) }
        return copy
    }
}

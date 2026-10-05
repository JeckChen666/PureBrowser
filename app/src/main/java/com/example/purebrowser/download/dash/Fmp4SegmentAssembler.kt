package com.example.purebrowser.download.dash

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.TransferFailure
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * T96 media-agnostic fMP4/CMAF assembly: init segment bytes + ordered media segment files are
 * re-muxed into one valid (non-fragmented) single- or dual-track MP4 by feeding the concatenated
 * stream through Media3's [FragmentedMp4Extractor] and writing every sample through the same
 * [Mp4Muxer] API the TS remuxer uses. No networking, no transcoding, no TS-style concatenation
 * disguised as MP4. Integrity: [Fmp4Boxes] proves complete box walks (truncation fails honestly),
 * moof/mdat ordering holds, encrypted samples are rejected, timelines must stay continuous, and
 * the assembled duration must match the plan's declared duration within the shared tolerance.
 *
 * Reusable seam for T95 (independent-audio HLS): `assemble(init, segments, output, ...)` accepts
 * an audio-only stream and yields a single-track AAC MP4 directly consumable by
 * [com.example.purebrowser.download.mux.DualTrackMuxer].
 */
@androidx.annotation.OptIn(UnstableApi::class)
class Fmp4SegmentAssembler {
    data class Result(val durationUs: Long, val videoSamples: Long, val audioSamples: Long)

    private class TrackStats(val format: Format) {
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        var count = 0L
        var previous = Long.MIN_VALUE
        var smallestStep = Long.MAX_VALUE
        fun sample(time: Long) {
            if (time == C.TIME_UNSET) fail("分片缺少可靠时间戳")
            // tfdt may jump at fragment seams for B frames; large jumps are not this version's stable timeline.
            if (previous != Long.MIN_VALUE && abs(time - previous) > 30_000_000) fail("分片时间轴不连续")
            if (previous != Long.MIN_VALUE && time > previous) smallestStep = minOf(smallestStep, time - previous)
            previous = time; min = minOf(min, time); max = maxOf(max, time); count++
            if (count > 1_000_000) fail("音视频采样数量超过本版处理上限")
        }
        val isVideo get() = format.sampleMimeType == MimeTypes.VIDEO_H264
        val isAudio get() = format.sampleMimeType == MimeTypes.AUDIO_AAC
        fun end(): Long = max + if (isAudio) {
            if (format.sampleRate <= 0) fail("音频采样率无效")
            1024_000_000L / format.sampleRate
        } else smallestStep.takeIf { it in 1..1_000_000 } ?: fail("视频帧时长无法确认")
    }

    /**
     * Assembles [init] + [segments] into [output]. Accepts exactly one clear H.264 track, one
     * clear AAC-LC track, or both (muxed representation). [expectedDurationUs] gates the honest
     * duration check; pass null to skip it (fixture/diagnostic use only).
     */
    fun assemble(
        init: File,
        segments: List<File>,
        output: File,
        expectedDurationUs: Long?,
        cancel: TransferCancellation,
        onProgress: (Long) -> Unit = {},
    ): Result {
        require(segments.isNotEmpty()) { "缺少媒体分片" }
        require(!output.exists()) { "输出文件已存在" }
        validateStructure(init, segments, cancel)
        val stats = linkedMapOf<Int, TrackStats>()
        val files = listOf(init) + segments
        extract(files, cancel, stats, null, 0L, onProgress)
        val video = stats.values.singleOrNull { it.isVideo }
        val audio = stats.values.singleOrNull { it.isAudio }
        if (stats.isEmpty() || stats.size > 2 || (video == null && audio == null))
            fail("fMP4 轨道不是受支持的 H.264/AAC 组合")
        stats.values.forEach { track ->
            if (track.count < 2) fail("音视频轨道不完整")
        }
        val start = stats.values.minOf { it.min }
        val end = stats.values.maxOf { it.end() }
        val duration = end - start
        if (expectedDurationUs != null) {
            val tolerance = maxOf(2_000_000L, minOf(5_000_000L, expectedDurationUs / 1000))
            if (abs(duration - expectedDurationUs) > tolerance) fail("音视频时长与清单不符，未发布成品")
        }
        try {
            FileOutputStream(output).use { stream ->
                Mp4Muxer.Builder(SeekableMuxerOutput.of(stream)).setSampleBatchingEnabled(false).build().use { muxer ->
                    val ids = stats.mapValues { (_, s) -> muxer.addTrack(s.format) }
                    extract(files, cancel, linkedMapOf(), Pair(muxer, ids), start, onProgress)
                    stats.forEach { (key, s) ->
                        muxer.writeSampleData(
                            ids.getValue(key), ByteBuffer.allocate(0),
                            BufferInfo(s.end() - start, 0, C.BUFFER_FLAG_END_OF_STREAM),
                        )
                    }
                }
            }
            cancel.check()
            java.io.RandomAccessFile(output, "rw").use { it.fd.sync() }
            return Result(duration, video?.count ?: 0L, audio?.count ?: 0L)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    /** Structural integrity gate: complete box walks, init shape, moof/mdat ordering per segment. */
    private fun validateStructure(init: File, segments: List<File>, cancel: TransferCancellation) {
        cancel.check()
        if (!init.isFile || init.length() < 16L) fail("初始化分片不完整")
        try {
            Fmp4Boxes.validateInit(Fmp4Boxes.topLevelBoxes(init, "初始化分片"))
        } catch (_: Exception) {
            fail("初始化分片不是完整 fMP4 数据")
        }
        segments.forEachIndexed { index, file ->
            cancel.check()
            if (!file.isFile || file.length() < 16L) fail("分片 ${index + 1} 不完整")
            if (file.length() > DashBudgets.MAX_SEGMENT_BYTES) fail("单片超过 128 MiB 上限")
            try {
                Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(file, "分片 ${index + 1}"))
            } catch (_: Exception) {
                fail("分片 ${index + 1} 不是完整 fMP4 数据")
            }
        }
    }

    private fun extract(
        files: List<File>,
        cancel: TransferCancellation,
        stats: MutableMap<Int, TrackStats>,
        writer: Pair<Mp4Muxer, Map<Int, Int>>?,
        offsetUs: Long,
        progress: (Long) -> Unit,
    ) {
        val extractor = FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        val outputs = mutableMapOf<Int, TrackOutput>()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = outputs.getOrPut(id) {
                if (type !in setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) DiscardingTrackOutput()
                else Samples(id, cancel, stats, writer, offsetUs)
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        // One virtual stream keeps fragment data in plan order; moof boundaries delimit samples.
        val enumeration = java.util.Collections.enumeration(files.map { file -> LazyFileInput(file, cancel) as InputStream })
        java.io.SequenceInputStream(enumeration).use { stream ->
            val reader = DataReader { buffer, off, len -> cancel.check(); stream.read(buffer, off, len) }
            val input = DefaultExtractorInput(reader, 0, files.sumOf { it.length() })
            try {
                val position = PositionHolder()
                var lastProgress = 0L
                while (true) {
                    cancel.check()
                    when (extractor.read(input, position)) {
                        Extractor.RESULT_END_OF_INPUT -> break
                        Extractor.RESULT_SEEK -> fail("分片要求不支持的随机读取")
                    }
                    if (input.position - lastProgress >= 1024 * 1024) {
                        progress(input.position)
                        lastProgress = input.position
                    }
                }
                progress(input.position)
            } finally {
                extractor.release()
            }
        }
    }

    private class Samples(
        val id: Int,
        val cancel: TransferCancellation,
        val stats: MutableMap<Int, TrackStats>,
        val writer: Pair<Mp4Muxer, Map<Int, Int>>?,
        val offsetUs: Long,
    ) : TrackOutput {
        var bytes = ByteArray(65536)
        var used = 0

        override fun format(format: Format) {
            if (format.sampleMimeType !in setOf(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_AAC))
                fail("本版只支持 H.264 与 AAC")
            val old = stats[id]?.format
            if (old != null && (old.sampleMimeType != format.sampleMimeType || old.width != format.width ||
                    old.height != format.height || old.sampleRate != format.sampleRate ||
                    old.channelCount != format.channelCount ||
                    old.initializationData.size != format.initializationData.size ||
                    old.initializationData.indices.any { !old.initializationData[it].contentEquals(format.initializationData[it]) })
            ) fail("分片编码参数发生变化")
            if (old == null) stats[id] = TrackStats(format)
            if (writer != null && id !in writer.second) fail("分片轨道发生变化")
        }

        private fun reserve(n: Int) {
            cancel.check()
            if (n < 0 || n > 16 * 1024 * 1024 - used) fail("单个媒体采样超过处理上限")
            if (used + n > bytes.size) bytes = bytes.copyOf(minOf(16 * 1024 * 1024, maxOf(used + n, bytes.size * 2)))
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            reserve(length)
            val n = input.read(bytes, used, length)
            if (n < 0) {
                if (!allowEndOfInput) throw java.io.EOFException()
                return -1
            }
            used += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            reserve(length)
            data.readBytes(bytes, used, length)
            used += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            cancel.check()
            if (cryptoData != null || flags and C.BUFFER_FLAG_ENCRYPTED != 0) fail("不支持加密媒体采样")
            val from = used - offset - size
            if (size <= 0 || offset < 0 || from < 0) fail("媒体采样边界无效")
            val track = stats[id] ?: fail("媒体轨道缺少格式")
            if (track.count == 0L && track.isVideo && flags and C.BUFFER_FLAG_KEY_FRAME == 0)
                fail("视频不以关键帧开始")
            track.sample(timeUs)
            writer?.let { (muxer, ids) ->
                val pts = timeUs - offsetUs
                if (pts < 0) fail("媒体时间轴无效")
                muxer.writeSampleData(
                    ids.getValue(id), ByteBuffer.wrap(bytes, from, size).slice(),
                    BufferInfo(pts, size, flags and C.BUFFER_FLAG_KEY_FRAME),
                )
            }
            bytes.copyInto(bytes, 0, used - offset, used)
            used = offset
        }
    }

    private class LazyFileInput(val file: File, val cancel: TransferCancellation) : InputStream() {
        var stream: InputStream? = null
        private fun stream(): InputStream {
            cancel.check()
            return stream ?: file.inputStream().also { stream = it }
        }

        override fun read() = stream().read()
        override fun read(b: ByteArray, off: Int, len: Int) = stream().read(b, off, len)
        override fun close() { stream?.close() }
    }

    companion object {
        private fun fail(message: String): Nothing = throw TransferFailure(FailureKind.UNSUPPORTED, message)
    }
}

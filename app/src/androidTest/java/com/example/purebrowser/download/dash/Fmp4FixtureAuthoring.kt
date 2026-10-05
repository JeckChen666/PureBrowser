package com.example.purebrowser.download.dash

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import com.example.purebrowser.download.mux.AuthoredMuxFixtures
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * T96 self-authored fMP4 fixtures: the existing H.264/AAC MP4 fixtures are re-packed through
 * media3's own FragmentedMp4Muxer (no third-party media, no network), then split by box walk into
 * an init segment and one file per moof fragment — the exact shape a DASH SegmentTemplate serves.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object Fmp4FixtureAuthoring {

    class CapturedSample(val timeUs: Long, val flags: Int, val payload: ByteArray)

    class CapturedTrack(val format: Format, val samples: List<CapturedSample>) {
        val minTimeUs: Long get() = samples.minOf { it.timeUs }
        val maxTimeUs: Long get() = samples.maxOf { it.timeUs }
        val durationUs: Long
            get() = maxTimeUs - minTimeUs +
                if (format.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_AAC &&
                    format.sampleRate > 0
                ) 1024_000_000L / format.sampleRate else samples.zipWithNext()
                .map { (a, b) -> b.timeUs - a.timeUs }.filter { it > 0 }.minOrNull() ?: 1_000_000L
    }

    /** Reads every video/audio track of a plain MP4 through media3's Mp4Extractor. */
    fun capture(source: File): List<CapturedTrack> {
        val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        val tracks = linkedMapOf<Int, TrackOutput>()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
                if (type !in setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) DiscardingTrackOutput()
                else CaptureSink()
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        val stream = ByteArrayInputStream(source.readBytes())
        val reader = DataReader { buffer, off, len -> stream.read(buffer, off, len) }
        val input = DefaultExtractorInput(reader, 0, source.length())
        try {
            check(extractor.sniff(input)) { "fixture is not MP4" }
            input.resetPeekPosition()
            val position = PositionHolder()
            while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) {
                if (Thread.interrupted()) throw java.io.IOException("interrupted")
            }
        } finally {
            extractor.release()
        }
        return tracks.values.filterIsInstance<CaptureSink>().map { sink ->
            CapturedTrack(sink.format!!, sink.samples.toList())
        }
    }

    private class CaptureSink : TrackOutput {
        var format: Format? = null
        val samples = ArrayList<CapturedSample>()
        private var buffer = ByteArray(1 shl 16)
        private var used = 0

        override fun format(format: Format) {
            if (this.format == null) this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (used + length > buffer.size) buffer = buffer.copyOf((used + length).coerceAtLeast(buffer.size * 2))
            val n = input.read(buffer, used, length)
            if (n < 0) {
                if (!allowEndOfInput) throw java.io.EOFException()
                return -1
            }
            used += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (used + length > buffer.size) buffer = buffer.copyOf((used + length).coerceAtLeast(buffer.size * 2))
            data.readBytes(buffer, used, length)
            used += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            check(cryptoData == null && flags and C.BUFFER_FLAG_ENCRYPTED == 0) { "fixture must be clear" }
            val from = used - offset - size
            check(size > 0 && offset >= 0 && from >= 0)
            samples += CapturedSample(timeUs, flags and C.BUFFER_FLAG_KEY_FRAME, buffer.copyOfRange(from, from + size))
            used = offset
        }
    }

    /**
     * Writes the captured track as one fragmented MP4. Timestamps are normalized to start at zero
     * (fixture authoring only; the assembler re-derives offsets from actual sample times).
     */
    fun writeFragmented(track: CapturedTrack, output: File, fragmentDurationMs: Long) {
        val offset = track.minTimeUs
        FileOutputStream(output).use { stream ->
            FragmentedMp4Muxer.Builder(stream)
                .setFragmentDurationMs(fragmentDurationMs)
                .build()
                .use { muxer ->
                    val id = muxer.addTrack(track.format)
                    track.samples.forEach { sample ->
                        muxer.writeSampleData(
                            id, ByteBuffer.wrap(sample.payload),
                            BufferInfo(sample.timeUs - offset, sample.payload.size, sample.flags),
                        )
                    }
                }
        }
        RandomAccessFile(output, "rw").use { it.fd.sync() }
    }

    /** Splits a fragmented MP4 into init bytes and one media segment file per moof fragment. */
    fun split(source: File, directory: File, stem: String): Pair<File, List<File>> {
        val bytes = source.readBytes()
        val boxes = Fmp4Boxes.topLevelBoxes(ByteArrayInputStream(bytes))
        val (initRange, segmentRanges) = Fmp4Boxes.splitRanges(boxes, bytes.size.toLong())
        val init = File(directory, "$stem-init.mp4").apply {
            writeBytes(bytes.copyOfRange(initRange.first.toInt(), initRange.last.toInt() + 1))
        }
        val segments = segmentRanges.mapIndexed { index, range ->
            File(directory, "$stem-segment-$index.m4s").apply {
                writeBytes(bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1))
            }
        }
        return init to segments
    }

    /** Authored fMP4 fixture pair builder for the assembler/muxer tests. */
    fun authoredFmp4(directory: File, base64: String, stem: String, fragmentDurationMs: Long = 500): Pair<File, List<File>> {
        val source = File(directory, "$stem-source.mp4").apply {
            writeBytes(Base64.decode(base64, Base64.DEFAULT))
        }
        val captured = capture(source).single()
        val fragmented = File(directory, "$stem.fmp4")
        writeFragmented(captured, fragmented, fragmentDurationMs)
        return split(fragmented, directory, stem)
    }
}

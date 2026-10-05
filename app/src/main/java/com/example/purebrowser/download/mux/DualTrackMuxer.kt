package com.example.purebrowser.download.mux

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.example.purebrowser.download.TransferCancellation
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Coded dimensions (before rotation), and the shared, normalized presentation duration. */
data class MuxedTracks(val durationUs: Long, val width: Int? = null, val height: Int? = null)

/** A local unsupported, incomplete, changed or over-budget input; never a request to transcode. */
class DualTrackMuxException(message: String) : IOException(message)

/**
 * Synchronous, local-only H.264 + AAC-LC remuxing; run off the UI thread.
 *
 * Each input must contain exactly one clear track. This deliberately bounded v0.1.5 subset
 * permits up to 1080p (including portrait), 60 minutes, 512 MiB/input, 200,000 samples/track,
 * 4 MiB/sample and 64 KiB codec configuration. No sample payloads are retained between calls
 * to Media3: batching is disabled. Media3 still retains bounded per-sample table metadata.
 * Android's native extractor also has its own allocations; this is not a native heap guarantee.
 *
 * Two streaming passes validate then write; SHA-256 covers every payload, PTS and flag to
 * reject input changes between passes. A shared offset preserves small A/V start differences
 * and decode-order B-frame PTS. One AAC frame of negative encoder preroll is allowed
 * and normalized with the same offset, never dropped. Starts may differ by 250 ms; lengths/ends may differ by
 * max(250 ms, 5% of the longer track), capped at 2 s. Sparse/discontinuous timelines fail.
 *
 * [output] must be a new, task-owned staging path, not a published file. Existing outputs
 * are never overwritten/deleted. Newly created partial output is removed on any failure,
 * including cancellation. Atomic publication, source identity and playback belong to callers.
 * MediaExtractor reads only a bounded local MediaDataSource, never a URI or network source.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class DualTrackMuxer {
    fun mux(video: File, audio: File, output: File, cancel: TransferCancellation): MuxedTracks {
        cancel.check()
        val videoPath = video.canonicalFile
        val audioPath = audio.canonicalFile
        val outputPath = output.canonicalFile
        requireInput(videoPath != audioPath && outputPath != videoPath && outputPath != audioPath,
            "Inputs and output must be distinct")
        requireInput(!output.exists(), "Output already exists")
        var created = false
        try {
            Input(videoPath, true, cancel).use { v ->
                Input(audioPath, false, cancel).use { a ->
                    val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
                    val vs = scan(v, buffer, cancel)
                    val aus = scan(a, buffer, cancel)
                    val duration = validateTimelines(vs, aus)
                    val offset = minOf(vs.min, aus.min)
                    cancel.check()
                    v.checkUnchanged()
                    a.checkUnchanged()
                    requireInput(output.createNewFile(), "Output already exists or cannot be created")
                    created = true
                    // A new extraction pass rather than seekTo(): seek modes may skip leading audio.
                    v.reopen()
                    a.reopen()
                    FileOutputStream(output).use { stream ->
                        val bounded = BoundedOutput(SeekableMuxerOutput.of(stream),
                            (v.length + a.length) * 2 + 16L * 1024 * 1024, cancel)
                        Mp4Muxer.Builder(bounded).setSampleBatchingEnabled(false).build().use { muxer ->
                            val videoId = muxer.addTrack(v.format)
                            val audioId = muxer.addTrack(a.format)
                            muxer.addMetadataEntry(Mp4OrientationData(v.rotation))
                            val writtenV = Stats(v)
                            val writtenA = Stats(a)
                            while (v.extractor.sampleTrackIndex >= 0 || a.extractor.sampleTrackIndex >= 0) {
                                cancel.check()
                                val useVideo = a.extractor.sampleTrackIndex < 0 ||
                                    (v.extractor.sampleTrackIndex >= 0 && v.extractor.sampleTime <= a.extractor.sampleTime)
                                val input = if (useVideo) v else a
                                val stats = if (useVideo) writtenV else writtenA
                                val sample = read(input, buffer, stats, cancel)
                                muxer.writeSampleData(if (useVideo) videoId else audioId, buffer,
                                    BufferInfo(sample.time - offset, sample.size,
                                        if (sample.sync) C.BUFFER_FLAG_KEY_FRAME else 0))
                                cancel.check()
                                input.extractor.advance()
                            }
                            requireInput(vs.matches(writtenV) && aus.matches(writtenA),
                                "Input samples changed during muxing")
                            v.checkUnchanged()
                            a.checkUnchanged()
                            cancel.check()
                            muxer.writeSampleData(videoId, ByteBuffer.allocate(0),
                                BufferInfo(vs.end - offset, 0, C.BUFFER_FLAG_END_OF_STREAM))
                            muxer.writeSampleData(audioId, ByteBuffer.allocate(0),
                                BufferInfo(aus.end - offset, 0, C.BUFFER_FLAG_END_OF_STREAM))
                        }
                    }
                    cancel.check()
                    requireInput(output.length() > 0, "Muxer produced an empty output")
                    RandomAccessFile(output, "rw").use { it.fd.sync() }
                    cancel.check()
                    return MuxedTracks(duration, v.format.width, v.format.height)
                }
            }
        } catch (failure: Throwable) {
            if (created && output.exists() && !output.delete()) {
                failure.addSuppressed(IOException("Could not remove partial mux output"))
            }
            // Native MediaExtractor/Media3 may wrap a cancellation thrown by a read/write callback.
            try { cancel.check() } catch (stopped: java.util.concurrent.CancellationException) {
                stopped.addSuppressed(failure)
                throw stopped
            }
            throw failure
        }
    }

    private fun scan(input: Input, buffer: ByteBuffer, cancel: TransferCancellation): Stats {
        val stats = Stats(input)
        while (input.extractor.sampleTrackIndex >= 0) {
            cancel.check()
            read(input, buffer, stats, cancel)
            input.extractor.advance()
        }
        stats.finish()
        input.checkUnchanged()
        return stats
    }

    private data class Sample(val time: Long, val size: Int, val sync: Boolean)

    private fun read(input: Input, buffer: ByteBuffer, stats: Stats, cancel: TransferCancellation): Sample {
        cancel.check()
        val extractor = input.extractor
        requireInput(extractor.sampleTrackIndex == 0, "Unexpected sample track")
        val flags = extractor.sampleFlags
        requireInput(flags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0,
            "Encrypted or partial samples are unsupported")
        val announced = if (Build.VERSION.SDK_INT >= 28) extractor.sampleSize else -1L
        if (Build.VERSION.SDK_INT >= 28) requireInput(announced in 1..MAX_SAMPLE_BYTES.toLong(), "Sample exceeds size budget")
        buffer.clear()
        // API 26/27 have no getSampleSize: the same fixed-capacity buffer bounds readSampleData.
        val size = extractor.readSampleData(buffer, 0)
        requireInput(size in 1..MAX_SAMPLE_BYTES && (announced < 0 || size.toLong() == announced),
            "Missing, oversized or truncated sample")
        buffer.position(0)
        buffer.limit(size)
        val sample = Sample(extractor.sampleTime, size, flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
        stats.record(sample, flags, buffer)
        cancel.check()
        return sample
    }

    private fun validateTimelines(v: Stats, a: Stats): Long {
        val tolerance = tolerance(maxOf(v.duration, a.duration))
        requireInput(abs(v.min - a.min) <= 250_000, "Audio/video start mismatch")
        requireInput(abs(v.duration - a.duration) <= tolerance && abs(v.end - a.end) <= tolerance,
            "Audio/video duration mismatch (video=${v.duration}us/${v.count} samples, audio=${a.duration}us/${a.count} samples)")
        val duration = maxOf(v.end, a.end) - minOf(v.min, a.min)
        requireInput(duration in 1..MAX_DURATION_US, "Presentation duration exceeds budget")
        return duration
    }

    private class Stats(private val input: Input) {
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        var count = 0
        var bytes = 0L
        private var previous = -1L
        private var first = -1L
        private var step = Long.MAX_VALUE
        private val digest = MessageDigest.getInstance("SHA-256")
        private val header = ByteBuffer.allocate(16)
        private var hash: ByteArray? = null
        var end = 0L
        val duration get() = end - min

        fun record(sample: Sample, flags: Int, payload: ByteBuffer) {
            // Android MP4 extraction subtracts the AAC edit-list encoder delay from CTS.
            // A leading negative LC frame is valid priming, not a missing timestamp.
            val earliest = if (input.video) 0L else -(1_024_000_000L + input.format.sampleRate - 1) / input.format.sampleRate
            requireInput(sample.time in earliest..MAX_TIMESTAMP_US, "Missing or invalid sample timestamp")
            requireInput(++count <= MAX_SAMPLES, "Sample count exceeds budget")
            bytes += sample.size
            requireInput(bytes <= MAX_INPUT_BYTES, "Sample payload exceeds input budget")
            if (count > 1) {
                val delta = sample.time - previous
                requireInput(delta <= 2_000_000 && delta >= if (input.video) -1_000_000 else 1,
                    "Discontinuous or invalid sample timeline")
                if (!input.video) {
                    val frameUs = 1_024_000_000L / input.format.sampleRate
                    requireInput(abs(delta - frameUs) <= maxOf(2L, frameUs / 100),
                        "AAC timestamps do not describe continuous 1024-sample frames")
                } else requireInput(delta != 0L, "Duplicate adjacent video timestamps")
                if (delta > 0) step = minOf(step, delta)
            } else {
                first = sample.time
                if (input.video) requireInput(sample.sync, "Video does not start with a sync sample")
            }
            previous = sample.time
            min = minOf(min, sample.time)
            max = maxOf(max, sample.time)
            requireInput(max - min <= MAX_DURATION_US, "Track duration exceeds budget")
            header.clear()
            header.putLong(sample.time).putInt(sample.size).putInt(flags).flip()
            digest.update(header)
            digest.update(payload.duplicate())
        }

        fun finish() {
            requireInput(count >= 2 && max > min, "Track has insufficient samples")
            requireInput(!input.video || first == min, "Leading open-GOP video pictures are unsupported")
            val tail = if (input.video) step else 1_024_000_000L / input.format.sampleRate
            requireInput(tail in 1..1_000_000, "Cannot establish sample duration")
            end = max + tail
            val declared = input.declaredDuration
            if (declared != null) {
                // KEY_DURATION may be media duration or the presentation end including an
                // empty edit. Accept only a value consistent with the actual readable span/end.
                val spanDeclaration=abs(declared - duration)<=tolerance(duration)
                val endDeclaration=min>=0 && abs(declared - end)<=tolerance(duration)
                requireInput(declared in 1..MAX_DURATION_US && (spanDeclaration || endDeclaration),
                    "Declared duration does not match readable samples")
                val mediaDuration=if(spanDeclaration)declared else declared-min
                // Keep a reliable container-provided final frame duration for VFR/B-frame tracks.
                if (input.video && mediaDuration - (max - min) in 1..1_000_000) end = min + mediaDuration
            }
        }

        fun matches(other: Stats): Boolean {
            other.finish()
            return count == other.count && min == other.min && max == other.max && end == other.end &&
                fingerprint().contentEquals(other.fingerprint())
        }
        private fun fingerprint(): ByteArray = hash ?: digest.digest().also { hash = it }
    }

    private class Input(val file: File, val video: Boolean, val cancel: TransferCancellation) : AutoCloseable {
        val length = file.length()
        private val modified = file.lastModified()
        private var source: LocalSource? = null
        var extractor = MediaExtractor()
            private set
        lateinit var format: Format
            private set
        var declaredDuration: Long? = null
            private set
        var rotation = 0
            private set
        init {
            try {
                requireInput(file.isFile && length in 1..MAX_INPUT_BYTES, "Missing, empty or over-budget input file")
                open()
            } catch (failure: Throwable) { close(); throw failure }
        }
        private fun open() {
            cancel.check()
            source = LocalSource(file, length, cancel)
            extractor.setDataSource(source!!)
            cancel.check()
            requireInput(extractor.trackCount == 1, "Each input must have exactly one track")
            requireInput(extractor.psshInfo.isNullOrEmpty(), "DRM-protected inputs are unsupported")
            val mediaFormat = extractor.getTrackFormat(0)
            val next = TrackFormats.convert(mediaFormat, video)
            if (::format.isInitialized) requireInput(format == next, "Track configuration changed")
            format = next
            declaredDuration = if (mediaFormat.containsKey(MediaFormat.KEY_DURATION))
                mediaFormat.getLong(MediaFormat.KEY_DURATION) else null
            rotation = if (mediaFormat.containsKey(MediaFormat.KEY_ROTATION))
                mediaFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            requireInput(rotation in setOf(0, 90, 180, 270), "Unsupported rotation")
            extractor.selectTrack(0)
        }
        fun checkUnchanged() {
            cancel.check()
            requireInput(file.isFile && file.length() == length && file.lastModified() == modified,
                "Input file changed")
        }
        fun reopen() {
            checkUnchanged()
            close()
            extractor = MediaExtractor()
            open()
        }
        override fun close() {
            try { extractor.release() } finally { source?.close(); source = null }
        }
    }

    /** Bounds individual and cumulative native extractor reads, including repeated metadata seeks. */
    private class LocalSource(file: File, private val length: Long, private val cancel: TransferCancellation) : MediaDataSource() {
        private val reader = RandomAccessFile(file, "r")
        // API 34+ extractors re-read per-sample sample-table chunks (moov-at-end layouts drive
        // ~3 read calls and ~6 KiB of table+data re-reads per AAC sample; measured ~23x the file
        // size for a 60 s track, T103). The budget stays a bounded amplification factor over the
        // input size — 32x plus a 16 MiB floor for short tracks — instead of the former 4x+1 MiB
        // that truncated real-length tracks mid-scan (surfacing as a declared-duration mismatch).
        private var remaining = length * 32 + 16L * 1024 * 1024
        private var calls = 0
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            cancel.check()
            // ~3 calls per sample at the 200k-sample cap, with re-open margin (was 100k, T103).
            requireInput(++calls <= 1_000_000, "Extractor read count exceeds budget")
            requireInput(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size,
                "Invalid local read")
            if (size == 0) return 0
            if (position >= length) return -1
            val count = minOf(size.toLong(), length - position, 64L * 1024).toInt()
            requireInput(remaining >= count, "Extractor read budget exceeded")
            remaining -= count
            reader.seek(position)
            val result = reader.read(buffer, offset, count)
            requireInput(result > 0, "Input was truncated during reading")
            cancel.check()
            return result
        }
        override fun getSize() = length
        @Synchronized override fun close() = reader.close()
    }

    /** Bounds intermediate output space and checks cancellation during Media3 finalization too. */
    private class BoundedOutput(private val delegate: SeekableMuxerOutput, private val limit: Long,
                                private val cancel: TransferCancellation) : SeekableMuxerOutput {
        override fun write(buffer: ByteBuffer): Int {
            cancel.check()
            requireInput(delegate.position + buffer.remaining() <= limit, "Mux output exceeds space budget")
            return delegate.write(buffer).also { cancel.check() }
        }
        override fun getPosition() = delegate.position
        override fun setPosition(position: Long) {
            cancel.check()
            requireInput(position in 0..limit, "Mux output seek exceeds budget")
            delegate.position = position
        }
        override fun getSize() = delegate.size
        override fun truncate(size: Long) {
            cancel.check()
            requireInput(size in 0..limit, "Mux output size exceeds budget")
            delegate.truncate(size)
        }
        override fun isOpen() = delegate.isOpen
        override fun close() = delegate.close()
    }

    companion object {
        internal const val MAX_SAMPLE_BYTES = 4 * 1024 * 1024
        internal const val MAX_INPUT_BYTES = 512L * 1024 * 1024
        internal const val MAX_SAMPLES = 200_000
        internal const val MAX_DURATION_US = 60L * 60 * 1_000_000
        private const val MAX_TIMESTAMP_US = 24L * 60 * 60 * 1_000_000
        private fun tolerance(duration: Long) = maxOf(250_000L, minOf(2_000_000L, duration / 20))
        internal fun requireInput(condition: Boolean, message: String) {
            if (!condition) throw DualTrackMuxException(message)
        }
    }
}

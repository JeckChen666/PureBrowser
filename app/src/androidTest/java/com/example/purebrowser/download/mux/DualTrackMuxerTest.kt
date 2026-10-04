package com.example.purebrowser.download.mux

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.TransferCancellation
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** No sockets, remote assets, external activities or fixture installation. Main owns device runs. */
@RunWith(AndroidJUnit4::class)
class DualTrackMuxerTest {
    private lateinit var directory: File
    private lateinit var video: File
    private lateinit var audio: File
    private lateinit var output: File

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "dual-mux-${UUID.randomUUID()}").apply { assertTrue(mkdirs()) }
        video = File(directory, "video.mp4").apply { writeBytes(Base64.decode(AuthoredMuxFixtures.VIDEO, Base64.DEFAULT)) }
        val rawAudio = File(directory, "seed.m4a").apply { writeBytes(Base64.decode(AuthoredMuxFixtures.AUDIO, Base64.DEFAULT)) }
        audio = File(directory, "audio.m4a")
        // Normalize encoder priming/edit-list handling differences between platform extractors.
        rewrite(rawAudio, audio)
        output = File(directory, "muxed.mp4")
    }

    @After fun tearDown() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test(timeout = 30_000) fun lawfulBFrameFixturesProduceTwoTracksWithUnchangedSamplesAndDecode() {
        val result = DualTrackMuxer().mux(video, audio, output, TransferCancellation())
        assertEquals(160, result.width)
        assertEquals(96, result.height)
        assertTrue(result.durationUs in 1_900_000..2_200_000)
        val inputVideo = samples(video, "video/avc")
        assertTrue("Fixture must exercise non-monotonic B-frame PTS", inputVideo.times.zipWithNext().any { (a, b) -> b < a })
        val outputVideo = samples(output, "video/avc")
        val outputAudio = samples(output, "audio/mp4a-latm")
        assertTimesEqual(inputVideo.times, outputVideo.times, 12)
        assertArrayEquals(inputVideo.hash, outputVideo.hash)
        val inputAudio = samples(audio, "audio/mp4a-latm")
        assertTimesEqual(inputAudio.times, outputAudio.times, 24)
        assertArrayEquals(inputAudio.hash, outputAudio.hash)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            assertEquals(2, extractor.trackCount)
        } finally { extractor.release() }
        // Actual platform decode, not just readable MP4 metadata; first/middle/tail output expected.
        decode(output, "video/avc", result.durationUs, requireNonZeroPcm = false)
        decode(output, "audio/mp4a-latm", result.durationUs, requireNonZeroPcm = true)
    }

    @Test fun sharedOffsetPreservesSmallAvStartDifference() {
        val shiftedVideo = File(directory, "shifted-video.mp4")
        val shiftedAudio = File(directory, "shifted-audio.m4a")
        rewrite(video, shiftedVideo, shiftUs = 1_000_000)
        rewrite(audio, shiftedAudio, shiftUs = 1_100_000)
        val vStart = samples(shiftedVideo, "video/avc").times.min()
        val aStart = samples(shiftedAudio, "audio/mp4a-latm").times.min()
        assertTrue("Video fixture must retain its absolute 1s start", kotlin.math.abs(vStart - 1_000_000) <= 24)
        assertTrue("Fixture must carry an actual 100ms offset", aStart - vStart in 90_000..110_000)
        DualTrackMuxer().mux(shiftedVideo, shiftedAudio, output, TransferCancellation())
        val outV = samples(output, "video/avc").times.min()
        val outA = samples(output, "audio/mp4a-latm").times.min()
        assertEquals(0L, minOf(outV, outA))
        assertTrue(kotlin.math.abs((aStart - vStart) - (outA - outV)) <= 24)
        assertTrue("Fixture must carry an actual 100ms offset", aStart - vStart in 90_000..110_000)
    }

    @Test fun missingEmptyMalformedAndSwappedTracksAreRejectedWithoutOutput() {
        reject(File(directory, "missing"), audio)
        reject(File(directory, "empty").apply { writeBytes(byteArrayOf()) }, audio)
        reject(File(directory, "html").apply { writeText("<html>not media</html>") }, audio)
        reject(audio, video)
        reject(video, video)
    }

    @Test fun multitrackInputIsRejected() {
        val combined = File(directory, "combined.mp4")
        DualTrackMuxer().mux(video, audio, combined, TransferCancellation())
        reject(combined, audio)
        reject(video, combined)
    }

    @Test fun severeStartAndDurationMismatchAreRejected() {
        val shifted = File(directory, "late-audio.m4a")
        rewrite(audio, shifted, shiftUs = 3_000_000)
        assertTrue("Mismatch fixture must actually start at 3s",
            kotlin.math.abs(samples(shifted, "audio/mp4a-latm").times.min() - 3_000_000) <= 24)
        reject(video, shifted)
        val short = File(directory, "short-audio.m4a")
        rewrite(audio, short, truncateUs = 400_000)
        reject(video, short)
    }

    @Test fun discontinuousTimestampAndNonSyncVideoStartAreRejected() {
        val jumped = File(directory, "jump.mp4")
        rewrite(video, jumped, timeMap = { index, time -> time + if (index >= 12) 3_000_000 else 0 })
        reject(jumped, audio)
        val nonSync = File(directory, "non-sync.mp4")
        rewrite(video, nonSync, dropFirst = true)
        reject(nonSync, audio)
    }

    @Test fun hugeFileAndOversizedSampleAreRejectedWithoutUnboundedAllocation() {
        val sparse = File(directory, "over-budget.mp4")
        RandomAccessFile(sparse, "rw").use { it.setLength(DualTrackMuxer.MAX_INPUT_BYTES + 1) }
        reject(sparse, audio)
        val oversized = File(directory, "large-sample.mp4")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.path)
            val muxer = MediaMuxer(oversized.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = muxer.addTrack(extractor.getTrackFormat(0))
                muxer.start()
                val large = ByteBuffer.allocate(DualTrackMuxer.MAX_SAMPLE_BYTES + 1)
                large.put(byteArrayOf(0, 0, 0, 1, 0x65)).position(0)
                muxer.writeSampleData(track, large, MediaCodec.BufferInfo().apply {
                    set(0, large.capacity(), 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                })
                muxer.stop()
            } finally { muxer.release() }
        } finally { extractor.release() }
        reject(oversized, audio)
    }

    @Test(timeout = 60_000) fun cancellationBeforeWorkAndDuringWritingLeavesNoPartialOutput() {
        val stopped = TransferCancellation().apply { cancel() }
        expect<CancellationException> { DualTrackMuxer().mux(video, audio, output, stopped) }
        assertFalse(output.exists())
        val longVideo = File(directory, "long.mp4")
        val longAudio = File(directory, "long.m4a")
        rewrite(video, longVideo, repetitions = 100)
        rewrite(audio, longAudio, repetitions = 100, totalDurationUs = 200_000_000)
        assertComparableFixtureTimelines(longVideo, longAudio)
        val cancel = TransferCancellation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val watcher = executor.submit<Boolean> {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (output.length() == 0L && System.nanoTime() < deadline) Thread.sleep(1)
                if (output.length() > 0) { cancel.cancel(); true } else false
            }
            expect<CancellationException> { DualTrackMuxer().mux(longVideo, longAudio, output, cancel) }
            assertTrue("Cancellation must occur after mux bytes have been written", watcher.get(35, TimeUnit.SECONDS))
            assertFalse(output.exists())
        } finally { executor.shutdownNow() }
    }

    @Test(timeout = 60_000) fun inputTruncationAfterValidationRemovesStagingOutput() {
        val longVideo = File(directory, "mutation-video.mp4")
        val longAudio = File(directory, "mutation-audio.m4a")
        rewrite(video, longVideo, repetitions = 100)
        rewrite(audio, longAudio, repetitions = 100, totalDurationUs = 200_000_000)
        assertComparableFixtureTimelines(longVideo, longAudio)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val watcher = executor.submit<Boolean> {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (output.length() == 0L && System.nanoTime() < deadline) Thread.sleep(1)
                if (output.length() > 0) { RandomAccessFile(longAudio, "rw").use { it.setLength(0) }; true } else false
            }
            expect<Exception> { DualTrackMuxer().mux(longVideo, longAudio, output, TransferCancellation()) }
            assertTrue("Mutation must occur after validation and mux bytes have been written", watcher.get(35, TimeUnit.SECONDS))
            assertFalse(output.exists())
        } finally { executor.shutdownNow() }
    }

    @Test fun compressedOrGappedAacTimelineIsRejected() {
        val compressed = File(directory, "compressed-audio.m4a")
        rewrite(audio, compressed, timeMap = { _, time -> time / 2 })
        reject(video, compressed)
        val gapped = File(directory, "gapped-audio.m4a")
        rewrite(audio, gapped, timeMap = { index, time -> time + if (index >= 30) 100_000 else 0 })
        reject(video, gapped)
    }

    @Test fun existingOutputAndInputAliasesAreNeverTruncatedOrRemoved() {
        val original = video.readBytes()
        expect<DualTrackMuxException> { DualTrackMuxer().mux(video, audio, video, TransferCancellation()) }
        assertArrayEquals(original, video.readBytes())
        val marker = byteArrayOf(1, 2, 3, 4)
        output.writeBytes(marker)
        expect<DualTrackMuxException> { DualTrackMuxer().mux(video, audio, output, TransferCancellation()) }
        assertArrayEquals(marker, output.readBytes())
        val cancel = TransferCancellation().apply { cancel() }
        expect<CancellationException> { DualTrackMuxer().mux(video, audio, output, cancel) }
        assertArrayEquals(marker, output.readBytes())
    }

    @Test fun aacLcRequiresReliableObjectTypeRateChannelsAnd1024SampleFrames() {
        TrackFormats.validateAacLc(byteArrayOf(0x12, 0x08), 44100, 1)
        TrackFormats.validateAacLc(byteArrayOf(0x12, 0x08, 0x56, 0xe5.toByte(), 0), 44100, 1)
        val invalid = listOf(
            byteArrayOf(), byteArrayOf(0x12), // Missing/truncated.
            byteArrayOf(0x2a, 0x08), // HE-AAC object type 5.
            byteArrayOf(0xea.toByte(), 0x08), // HE-AACv2 object type 29.
            byteArrayOf(0x12, 0x0c), // 960-sample frameLengthFlag.
            byteArrayOf(0x12, 0x00), // PCE channel config unsupported.
            byteArrayOf(0x12, 0x08, 0x56, 0xe5.toByte(), 0x80.toByte()) // Implicit SBR present.
        )
        invalid.forEach { expect<DualTrackMuxException> { TrackFormats.validateAacLc(it, 44100, 1) } }
        expect<DualTrackMuxException> { TrackFormats.validateAacLc(byteArrayOf(0x12, 0x08), 48000, 1) }
        expect<DualTrackMuxException> { TrackFormats.validateAacLc(byteArrayOf(0x12, 0x08), 44100, 2) }
    }

    @Test fun invalidCodecConfigurationAndOver1080pAreRejected() {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(video.path)
            val format = extractor.getTrackFormat(0)
            format.setInteger(MediaFormat.KEY_WIDTH, 3840)
            format.setInteger(MediaFormat.KEY_HEIGHT, 2160)
            expect<DualTrackMuxException> { TrackFormats.convert(format, true) }
            format.setInteger(MediaFormat.KEY_WIDTH, 160)
            format.setInteger(MediaFormat.KEY_HEIGHT, 96)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67)))
            expect<DualTrackMuxException> { TrackFormats.convert(format, true) }
            format.setByteBuffer("csd-0", ByteBuffer.allocate(64 * 1024 + 1))
            expect<DualTrackMuxException> { TrackFormats.convert(format, true) }
        } finally { extractor.release() }
    }

    @Test fun rawAacEncoderPrerollIsPreservedAndNormalizedWithVideo() {
        val rawAudio = File(directory, "seed.m4a")
        val inputA = samples(rawAudio, "audio/mp4a-latm")
        val inputV = samples(video, "video/avc")
        val frameUs = (1_024_000_000L + 44100 - 1) / 44100
        assertTrue("Raw seed must have no more than one AAC frame of preroll", inputA.times.min() in -frameUs..0)
        val offset = minOf(inputA.times.min(), inputV.times.min())
        val result = DualTrackMuxer().mux(video, rawAudio, output, TransferCancellation())
        val outputA = samples(output, "audio/mp4a-latm")
        val outputV = samples(output, "video/avc")
        assertTimesEqual(inputA.times.map { it - offset }, outputA.times, 24)
        assertTimesEqual(inputV.times.map { it - offset }, outputV.times, videoOutputTickUs(output))
        assertArrayEquals(inputA.hash, outputA.hash)
        assertArrayEquals(inputV.hash, outputV.hash)
        assertTrue(result.durationUs in 1_900_000..2_200_000)
        decode(output, "audio/mp4a-latm", result.durationUs, requireNonZeroPcm = true)
    }

    private fun assertComparableFixtureTimelines(v: File, a: File) {
        val videoTimes = samples(v, "video/avc").times
        val audioTimes = samples(a, "audio/mp4a-latm").times
        val videoStep = videoTimes.zipWithNext().map { (x, y) -> y - x }.filter { it > 0 }.min()
        val videoDuration = videoTimes.max() - videoTimes.min() + videoStep
        val audioDuration = audioTimes.max() - audioTimes.min() + 1_024_000_000L / 44100
        assertTrue("Fault-injection fixtures must reach mux writing: video=$videoDuration audio=$audioDuration",
            kotlin.math.abs(videoDuration - audioDuration) < 50_000)
    }

    private fun reject(v: File, a: File) {
        expect<Exception> { DualTrackMuxer().mux(v, a, output, TransferCancellation()) }
        assertFalse("Rejected input must not leave output", output.exists())
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit): T {
        try { block() } catch (failure: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got $failure", failure is T)
            @Suppress("UNCHECKED_CAST") return failure as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    /** Both media samples and movie edit offsets quantize PTS: use actual box timescales. */
    private fun videoOutputTickUs(file:File):Long {
        require(file.length()<=8*1024*1024)
        val bytes=file.readBytes()
        val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        fun type(position:Int)=String(bytes,position+4,4,Charsets.US_ASCII)
        fun children(start:Int,end:Int):List<Int> {
            val out=mutableListOf<Int>();var p=start
            while(p<end){require(p+8<=end && out.size<64)
                val raw=buffer.getInt(p).toLong() and 0xffffffffL
                val size=when(raw){0L->(end-p).toLong();1L->{require(p+16<=end);buffer.getLong(p+8)};else->raw}
                require(size>=(if(raw==1L)16 else 8) && size<=end-p)
                out.add(p);p+=size.toInt()
            }
            return out
        }
        fun child(parent:Int,name:String)=children(parent+8,parent+buffer.getInt(parent)).single{type(it)==name}
        fun scale(position:Int):Long {
            val version=bytes[position+8].toInt();require(version in 0..1)
            val offset=position+if(version==1)28 else 20
            val scale=buffer.getInt(offset).toLong() and 0xffffffffL
            require(scale>0);return scale
        }
        val moov=children(0,bytes.size).single{type(it)=="moov"}
        val movieScale=scale(child(moov,"mvhd"))
        val track=children(moov+8,moov+buffer.getInt(moov)).filter{type(it)=="trak"}.single {
            val hdlr=child(child(it,"mdia"),"hdlr")
            String(bytes,hdlr+16,4,Charsets.US_ASCII)=="vide"
        }
        val mediaScale=scale(child(child(track,"mdia"),"mdhd"))
        return maxOf((1_000_000L+movieScale-1)/movieScale,(1_000_000L+mediaScale-1)/mediaScale)
    }

    private fun assertTimesEqual(expected: List<Long>, actual: List<Long>, tickUs: Long) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEachIndexed { index, (a, b) ->
            // Media3's 90 kHz AVC / sample-rate AAC timescale may round an input by one tick.
            assertTrue("PTS at sample $index: $a vs $b", kotlin.math.abs(a - b) <= tickUs)
        }
    }

    private data class SampleSummary(val times: List<Long>, val hash: ByteArray)
    private fun samples(file: File, mime: String): SampleSummary {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime }
            extractor.selectTrack(track)
            val times = mutableListOf<Long>()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(DualTrackMuxer.MAX_SAMPLE_BYTES)
            while (extractor.sampleTrackIndex >= 0) {
                times.add(extractor.sampleTime)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                assertTrue(size > 0)
                buffer.position(0).limit(size)
                digest.update(buffer)
                extractor.advance()
            }
            return SampleSummary(times, digest.digest())
        } finally { extractor.release() }
    }

    /** Recontainers the authored seeds without re-encoding; no full-media readBytes in production. */
    private fun rewrite(source: File, target: File, repetitions: Int = 1, shiftUs: Long = 0,
                        truncateUs: Long = Long.MAX_VALUE, dropFirst: Boolean = false,
                        totalDurationUs: Long = Long.MAX_VALUE,
                        timeMap: (Int, Long) -> Long = { _, time -> time }) {
        val inspect = MediaExtractor()
        val format: MediaFormat
        val min: Long
        val period: Long
        try {
            inspect.setDataSource(source.path)
            format = inspect.getTrackFormat(0)
            inspect.selectTrack(0)
            val times = mutableListOf<Long>()
            while (inspect.sampleTrackIndex >= 0) { times.add(inspect.sampleTime); inspect.advance() }
            min = times.min()
            val positiveStep = times.zipWithNext().map { (a, b) -> b - a }.filter { it > 0 }.min()
            period = times.max() - min + positiveStep
        } finally { inspect.release() }
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val track = muxer.addTrack(format)
            muxer.start()
            val buffer = ByteBuffer.allocate(DualTrackMuxer.MAX_SAMPLE_BYTES)
            repeat(repetitions) { repeatIndex ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(source.path)
                    extractor.selectTrack(0)
                    var index = 0
                    while (extractor.sampleTrackIndex >= 0) {
                        val localTime = extractor.sampleTime - min
                        if (localTime >= truncateUs || timeMap(index, localTime) + repeatIndex * period >= totalDurationUs) break
                        if (!dropFirst || index != 0) {
                            buffer.clear()
                            val size = extractor.readSampleData(buffer, 0)
                            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            muxer.writeSampleData(track, buffer, MediaCodec.BufferInfo().apply {
                                set(0, size, timeMap(index, localTime) + repeatIndex * period, flags)
                            })
                        }
                        index++
                        extractor.advance()
                    }
                } finally { extractor.release() }
            }
            muxer.stop()
        } finally { muxer.release() }
        // Standalone platform muxing normalizes the first input PTS: shifting BufferInfo alone
        // did not create a delayed track on API37. Add an explicit, legal empty edit instead.
        if (shiftUs > 0) Mp4FixtureTimeline.delay(target, shiftUs)
    }

    private fun decode(file: File, mime: String, duration: Long, requireNonZeroPcm: Boolean) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).single { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime }
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(extractor.getTrackFormat(track), null, null, 0)
            decoder.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var decoded = 0
            var lastPts = -1L
            var nonZeroPcm = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!outputEnded && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    if (info.size > 0) {
                        decoded++
                        lastPts = maxOf(lastPts, info.presentationTimeUs)
                        if (requireNonZeroPcm) {
                            val pcm = decoder.getOutputBuffer(index)!!.duplicate()
                            pcm.position(info.offset).limit(info.offset + info.size)
                            while (pcm.hasRemaining()) nonZeroPcm = (pcm.get() != 0.toByte()) || nonZeroPcm
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("Decoder must reach EOS for $mime", outputEnded)
            assertTrue("Must decode first/middle/tail $mime", decoded > 10 && lastPts > duration - 300_000)
            if (requireNonZeroPcm) assertTrue("Authored tone must produce audible, non-zero PCM", nonZeroPcm)
        } finally {
            try { if (started) codec?.stop() } finally { codec?.release(); extractor.release() }
        }
    }
}

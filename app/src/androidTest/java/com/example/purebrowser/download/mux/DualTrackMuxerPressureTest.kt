package com.example.purebrowser.download.mux

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.TransferCancellation
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime-authored compact 31-minute media-duration pressure, not a 31-minute wall-clock/FGS audit.
 * Repeats the existing lawful testsrc2 IDR at 1 fps and existing AAC-LC 880 Hz tone access units.
 * No encoding, new binary assets, network, installation, activities or full-length decoding.
 * The fixture intentionally uses a static picture; it is not a motion/synchronization benchmark.
 * Main owns device execution, memory/ANR observation and separate background/share acceptance.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DualTrackMuxerPressureTest {
    private lateinit var directory: File
    private lateinit var videoSeed: File
    private lateinit var audioSeed: File

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "dual-mux-pressure-${UUID.randomUUID()}").apply { assertTrue(mkdirs()) }
        videoSeed = File(directory, "seed.mp4").apply {
            writeBytes(Base64.decode(AuthoredMuxFixtures.VIDEO, Base64.DEFAULT))
        }
        audioSeed = File(directory, "seed.m4a").apply {
            writeBytes(Base64.decode(AuthoredMuxFixtures.AUDIO, Base64.DEFAULT))
        }
    }

    @After fun tearDown() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test(timeout = 600_000) fun authored31MinuteLowFpsAndToneMuxPreservesTracksAndDecodesFirstMiddleTail() {
        val started = SystemClock.elapsedRealtime()
        val durationUs = 31L * 60 * 1_000_000
        val video = File(directory, "31-minute-video.mp4")
        val audio = File(directory, "31-minute-tone.m4a")
        val output = File(directory, "31-minute-dual.mp4")
        authorTrack(videoSeed, video, durationUs, video = true)
        authorTrack(audioSeed, audio, durationUs, video = false)
        assertTrue("Video fixture must be compact", video.length() in 1..16L * 1024 * 1024)
        assertTrue("Tone fixture must be compact", audio.length() in 1..16L * 1024 * 1024)
        val muxStarted = SystemClock.elapsedRealtime()
        val result = DualTrackMuxer().mux(video, audio, output, TransferCancellation())
        assertTrue(result.durationUs > 30L * 60 * 1_000_000)
        assertTrue(abs(result.durationUs - durationUs) < 100_000)
        assertEquals(160, result.width)
        assertEquals(96, result.height)
        assertTrue(output.length() in 1..48L * 1024 * 1024)
        assertSameSamples(video, output, "video/avc", expectedCount = 31 * 60, stepNumerator = 1_000_000, rate = 1)
        val audioSamples = (durationUs * 44100 + AAC_FRAME_US_NUMERATOR - 1) / AAC_FRAME_US_NUMERATOR
        assertTrue(audioSamples in 80_000..DualTrackMuxer.MAX_SAMPLES.toLong())
        assertSameSamples(audio, output, "audio/mp4a-latm", audioSamples.toInt(), AAC_FRAME_US_NUMERATOR, 44100)
        val inspect = MediaExtractor()
        try {
            inspect.setDataSource(output.path)
            assertEquals(2, inspect.trackCount)
        } finally { inspect.release() }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            for (positionUs in listOf(0L, durationUs / 2, durationUs - 500_000)) {
                val frame = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                assertNotNull("Must decode video at $positionUs", frame)
                frame!!.let {
                    try { assertEquals(160, it.width); assertEquals(96, it.height) } finally { it.recycle() }
                }
                decodeToneWindow(output, positionUs)
            }
        } finally { retriever.release() }
        Log.i("DualTrackMuxPressure", "authored mediaDurationUs=${result.durationUs}; " +
            "videoSamples=1860; audioSamples=$audioSamples; bytes=${output.length()}; " +
            "muxAndVerificationMs=${SystemClock.elapsedRealtime() - muxStarted}; " +
            "fixtureAndTestMs=${SystemClock.elapsedRealtime() - started}; not a wall-clock background audit")
    }

    @Test(timeout = 60_000) fun over60MinuteAuthoredVideoStillFailsWithoutOutput() {
        assertEquals(60L * 60 * 1_000_000, DualTrackMuxer.MAX_DURATION_US)
        assertEquals(200_000, DualTrackMuxer.MAX_SAMPLES)
        val video = File(directory, "over-limit.mp4")
        val output = File(directory, "must-not-exist.mp4")
        authorTrack(videoSeed, video, DualTrackMuxer.MAX_DURATION_US + 1_000_000, video = true)
        try {
            DualTrackMuxer().mux(video, audioSeed, output, TransferCancellation())
            fail("Over-60-minute video must be rejected")
        } catch (expected: DualTrackMuxException) {
            assertTrue("Expected duration rejection: ${expected.message}", expected.message!!.contains("duration", ignoreCase = true))
        }
        assertFalse(output.exists())
    }

    private data class Seed(val format: MediaFormat, val samples: List<ByteArray>)

    /** Only caches the <= 256 tiny authored seed units, never the generated 31-minute media. */
    private fun seed(source: File, video: Boolean): Seed {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            val units = mutableListOf<ByteArray>()
            var total = 0
            while (extractor.sampleTrackIndex >= 0) {
                assertTrue(units.size < 256)
                if (!video || extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    assertTrue(size in 1..buffer.capacity())
                    total += size
                    assertTrue(total <= 1024 * 1024)
                    buffer.position(0).limit(size)
                    units.add(ByteArray(size).also { buffer.get(it) })
                    if (video) break // Repeat one complete IDR; never stretch a B/P reference chain.
                }
                extractor.advance()
            }
            assertTrue(units.isNotEmpty())
            if (!video) assertEquals(44100, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            return Seed(format, units)
        } finally { extractor.release() }
    }

    private fun authorTrack(source: File, output: File, durationUs: Long, video: Boolean) {
        val seed = seed(source, video)
        val numerator = if (video) 1_000_000L else AAC_FRAME_US_NUMERATOR
        val rate = if (video) 1 else seed.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val count = (durationUs * rate + numerator - 1) / numerator
        assertTrue(count in 2..DualTrackMuxer.MAX_SAMPLES.toLong())
        // Do not leave the 2-second seed's descriptive duration/frame rate on a long track.
        seed.format.setLong(MediaFormat.KEY_DURATION, durationUs)
        if (video) seed.format.setInteger(MediaFormat.KEY_FRAME_RATE, 1)
        val units = seed.samples.map { ByteBuffer.wrap(it) }
        val info = MediaCodec.BufferInfo()
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val track = muxer.addTrack(seed.format)
            muxer.start()
            repeat(count.toInt()) { index ->
                val unit = units[index % units.size]
                unit.position(0)
                unit.limit(unit.capacity())
                info.set(0, unit.remaining(), index * numerator / rate, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(track, unit, info)
            }
            muxer.stop()
        } finally { muxer.release() }
    }

    /** Streaming payload hashes plus per-sample expected timing (one output timescale tick allowed). */
    private fun assertSameSamples(input: File, output: File, mime: String, expectedCount: Int,
                                  stepNumerator: Long, rate: Int) {
        fun hash(file: File): ByteArray {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).single {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == mime
                }
                extractor.selectTrack(track)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteBuffer.allocate(64 * 1024)
                var count = 0
                while (extractor.sampleTrackIndex >= 0) {
                    assertTrue(count < expectedCount)
                    assertTrue("Unexpected $mime PTS at $count",
                        abs(extractor.sampleTime - count * stepNumerator / rate) <= 24)
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    assertTrue(size in 1..buffer.capacity())
                    buffer.position(0).limit(size)
                    digest.update(buffer)
                    count++
                    extractor.advance()
                }
                assertEquals(expectedCount, count)
                return digest.digest()
            } finally { extractor.release() }
        }
        assertArrayEquals("Payloads changed in $mime", hash(input), hash(output))
    }

    /** Decode only 16 AAC frames at the requested first/middle/tail point, with a hard deadline. */
    private fun decodeToneWindow(file: File, positionUs: Long) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).single {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            }
            extractor.selectTrack(track)
            extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
            codec.configure(extractor.getTrackFormat(track), null, null, 0)
            codec.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputEnded = false
            var outputEnded = false
            var decoded = 0
            var lastPts = -1L
            var nonZero = false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!outputEnded && System.nanoTime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = if (fed < 16) extractor.readSampleData(codec.getInputBuffer(index)!!, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            fed++
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    if (info.size > 0) {
                        decoded++
                        lastPts = maxOf(lastPts, info.presentationTimeUs)
                        val pcm = codec.getOutputBuffer(index)!!.duplicate()
                        pcm.position(info.offset).limit(info.offset + info.size)
                        while (pcm.hasRemaining()) nonZero = (pcm.get() != 0.toByte()) || nonZero
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("AAC window must reach EOS at $positionUs", outputEnded)
            assertTrue("AAC must decode a non-silent tone near $positionUs", decoded >= 8 && nonZero)
            assertTrue("AAC must reach the requested window", lastPts >= positionUs + 100_000)
        } finally {
            try { if (started) codec?.stop() } finally { codec?.release(); extractor.release() }
        }
    }

    companion object { private const val AAC_FRAME_US_NUMERATOR = 1_024_000_000L }
}

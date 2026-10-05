package com.example.purebrowser.download.dash

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.TransferFailure
import com.example.purebrowser.download.mux.AuthoredMuxFixtures
import com.example.purebrowser.download.mux.DualTrackMuxer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * T96 end-to-end: authored fMP4 init+segments → single-track sample-readable MP4 through the
 * FragmentedMp4Extractor-based assembler, plus the T97 dual-representation merge through the
 * existing DualTrackMuxer. No sockets, remote assets or fixture installation.
 */
@RunWith(AndroidJUnit4::class)
class Fmp4SegmentAssemblerTest {
    private lateinit var directory: File
    private lateinit var videoInit: File
    private lateinit var videoSegments: List<File>
    private lateinit var audioInit: File
    private lateinit var audioSegments: List<File>
    private var videoDurationUs = 0L
    private var audioDurationUs = 0L

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "dash-fmp4-${UUID.randomUUID()}").apply { assertTrue(mkdirs()) }
        val (vi, vs) = Fmp4FixtureAuthoring.authoredFmp4(directory, AuthoredMuxFixtures.VIDEO, "video")
        val (ai, audioSegs) = Fmp4FixtureAuthoring.authoredFmp4(directory, AuthoredMuxFixtures.AUDIO, "audio")
        videoInit = vi; videoSegments = vs; audioInit = ai; audioSegments = audioSegs
        videoDurationUs = Fmp4FixtureAuthoring.capture(File(directory, "video-source.mp4")).single().durationUs
        audioDurationUs = Fmp4FixtureAuthoring.capture(File(directory, "audio-source.mp4")).single().durationUs
        assertTrue("fragment muxing must yield multiple segments", videoSegments.size >= 2)
        assertTrue(audioSegments.size >= 2)
        assertTrue(videoDurationUs in 1_900_000..2_300_000)
        assertTrue(audioDurationUs in 1_900_000..2_300_000)
    }

    @After fun tearDown() { if (::directory.isInitialized) directory.deleteRecursively() }

    @Test(timeout = 30_000) fun authoredVideoSegmentsAssembleToASingleReadableTrack() {
        val output = File(directory, "assembled-video.mp4")
        val result = Fmp4SegmentAssembler().assemble(
            videoInit, videoSegments, output, videoDurationUs, TransferCancellation(),
        )
        assertTrue(result.videoSamples >= 20)
        assertEquals(0L, result.audioSamples)
        assertTrue(result.durationUs in 1_900_000..2_300_000)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
            assertEquals(160, format.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(96, format.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(0)
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            for (point in listOf(0L, result.durationUs / 2)) {
                extractor.seekTo(point, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                assertTrue(extractor.sampleTime >= 0)
                assertTrue(extractor.readSampleData(buffer, 0) > 0)
            }
        } finally { extractor.release() }
    }

    /** The reusable seam T95 calls for independent-audio HLS: audio-only in, AAC track out. */
    @Test(timeout = 30_000) fun authoredAudioSegmentsAssembleToASingleAacTrack() {
        val output = File(directory, "assembled-audio.mp4")
        val result = Fmp4SegmentAssembler().assemble(
            audioInit, audioSegments, output, audioDurationUs, TransferCancellation(),
        )
        assertEquals(0L, result.videoSamples)
        assertTrue(result.audioSamples >= 40)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.path)
            assertEquals(1, extractor.trackCount)
            assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
        } finally { extractor.release() }
    }

    @Test(timeout = 30_000) fun dualRepresentationsMergeThroughTheExistingDualTrackMuxer() {
        val videoTrack = File(directory, "video-track.mp4")
        val audioTrack = File(directory, "audio-track.mp4")
        val merged = File(directory, "merged.mp4")
        Fmp4SegmentAssembler().assemble(videoInit, videoSegments, videoTrack, videoDurationUs, TransferCancellation())
        Fmp4SegmentAssembler().assemble(audioInit, audioSegments, audioTrack, audioDurationUs, TransferCancellation())
        val result = DualTrackMuxer().mux(videoTrack, audioTrack, merged, TransferCancellation())
        assertEquals(160, result.width)
        assertEquals(96, result.height)
        assertTrue(result.durationUs in 1_900_000..2_400_000)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(merged.path)
            assertEquals(2, extractor.trackCount)
            assertEquals(1, (0 until extractor.trackCount).count {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc"
            })
            assertEquals(1, (0 until extractor.trackCount).count {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm"
            })
        } finally { extractor.release() }
    }

    @Test fun truncatedSegmentFailsHonestlyWithoutOutput() {
        val truncated = ArrayList(videoSegments)
        // removeAt (not JDK 21 List.removeLast): the latter is absent on API 28 runtimes.
        val last = truncated.removeAt(truncated.size - 1)
        truncated += File(directory, "cut.m4s").apply {
            writeBytes(last.readBytes().copyOf((last.length() - 5L).coerceAtLeast(1L).toInt()))
        }
        val output = File(directory, "truncated-out.mp4")
        try {
            Fmp4SegmentAssembler().assemble(videoInit, truncated, output, videoDurationUs, TransferCancellation())
            fail("expected honest failure")
        } catch (expected: TransferFailure) {
            assertEquals(FailureKind.UNSUPPORTED, expected.kind)
            // Any of the box-walk integrity messages is an honest truncation refusal: the exact
            // branch depends on where the cut lands (per-segment size gate vs box-walk failure).
            assertTrue(
                expected.safeMessage.contains("不完整") || expected.safeMessage.contains("不是完整"),
            )
        }
        assertFalse(output.exists())
    }

    @Test fun reorderedMoofMdatFailsHonestly() {
        val first = videoSegments.first().readBytes()
        // Prefix the real fragment with a synthetic mdat: mdat before the first moof must fail.
        val mdatFirst = File(directory, "mdat-first.m4s").apply {
            writeBytes(byteArrayOf(0, 0, 0, 16) + "mdat".toByteArray(Charsets.US_ASCII) + ByteArray(8) + first)
        }
        val output = File(directory, "reordered-out.mp4")
        try {
            Fmp4SegmentAssembler().assemble(videoInit, listOf(mdatFirst), output, videoDurationUs, TransferCancellation())
            fail("expected honest failure")
        } catch (expected: TransferFailure) {
            assertTrue(expected.safeMessage.contains("fMP4"))
        }
        assertFalse(output.exists())
    }

    @Test fun durationMismatchWithThePlanFailsHonestly() {
        val output = File(directory, "duration-out.mp4")
        try {
            Fmp4SegmentAssembler().assemble(videoInit, videoSegments, output, videoDurationUs + 10_000_000L, TransferCancellation())
            fail("expected honest failure")
        } catch (expected: TransferFailure) {
            assertTrue(expected.safeMessage.contains("时长"))
        }
        assertFalse(output.exists())
    }

    @Test fun cancellationLeavesNoPartialOutput() {
        val output = File(directory, "cancelled-out.mp4")
        val stopped = TransferCancellation().apply { cancel() }
        try {
            Fmp4SegmentAssembler().assemble(videoInit, videoSegments, output, videoDurationUs, stopped)
            fail("expected cancellation")
        } catch (expected: java.util.concurrent.CancellationException) {
            assertNotNull(expected)
        }
        assertFalse(output.exists())
    }
}

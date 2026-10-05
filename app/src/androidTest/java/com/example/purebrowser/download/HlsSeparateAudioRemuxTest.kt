package com.example.purebrowser.download

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.hls.TsToMp4Remuxer
import com.example.purebrowser.download.mux.DualTrackMuxer
import com.example.purebrowser.download.mux.MuxedTracks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Separate-audio shape: per-track TS→MP4 remux with a shared offset, then the existing muxer. */
@RunWith(AndroidJUnit4::class)
class HlsSeparateAudioRemuxTest {
    private fun segments(prefix: String, dir: File): List<File> =
        dir.listFiles { file -> file.name.startsWith("$prefix-") && file.name.endsWith(".ts") }!!
            .sortedBy { it.name }
    private fun fixtureDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "hls-separate-remux")
        dir.deleteRecursively(); dir.mkdirs()
        listOf("video-000.ts", "video-001.ts", "video-002.ts", "video-003.ts",
            "audio-000.ts", "audio-001.ts", "audio-002.ts", "audio-003.ts", "audio-004.ts").forEach { name ->
            instrumentation.context.assets.open("hls-separate/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        return dir
    }

    @Test fun perTrackRemuxWithSharedOffsetThenMuxerProducesOneVerifiedMp4() {
        val dir = fixtureDir()
        try {
            val remuxer = TsToMp4Remuxer()
            val videoSegments = segments("video", dir)
            val audioSegments = segments("audio", dir)
            assertEquals(4, videoSegments.size); assertEquals(5, audioSegments.size)
            val videoProbe = remuxer.probeTrack(videoSegments, TsToMp4Remuxer.TrackRole.VIDEO, TransferCancellation())
            val audioProbe = remuxer.probeTrack(audioSegments, TsToMp4Remuxer.TrackRole.AUDIO, TransferCancellation())
            assertTrue(videoProbe.samples > 100); assertTrue(audioProbe.samples > 300)
            val offset = minOf(videoProbe.firstUs, audioProbe.firstUs)
            // Two independently encoded renditions: first PTS differs by ~83ms and must survive.
            assertTrue(abs(videoProbe.firstUs - audioProbe.firstUs) in 1..250_000)
            val video = File(dir, "track-video.mp4")
            val audio = File(dir, "track-audio.mp4")
            remuxer.remuxTrack(videoSegments, video, TsToMp4Remuxer.TrackRole.VIDEO, offset, 8_000_000, TransferCancellation())
            remuxer.remuxTrack(audioSegments, audio, TsToMp4Remuxer.TrackRole.AUDIO, offset, 8_001_333, TransferCancellation())
            // Each intermediate is exactly one track of the expected role.
            listOf(video to "video/avc", audio to "audio/mp4a-latm").forEach { (file, mime) ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    assertEquals(1, extractor.trackCount)
                    assertEquals(mime, extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
                } finally { extractor.release() }
            }
            val output = File(dir, "merged.mp4")
            val merged: MuxedTracks = DualTrackMuxer().mux(video, audio, output, TransferCancellation())
            assertTrue(abs(merged.durationUs - 8_000_000) < 150_000)
            assertEquals(FormatCheck.PASSED, ManagedFileStore(InstrumentationRegistry.getInstrumentation().targetContext).inspect(output).format)
        } finally { dir.deleteRecursively() }
    }

    @Test fun perTrackRemuxRefusesAmbiguousOrWrongRoleInputs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = File(instrumentation.targetContext.cacheDir, "hls-separate-roles")
        dir.deleteRecursively(); dir.mkdirs()
        try {
            // The MUXED fixture carries both tracks: it is not a single-role segment list.
            val muxed = File(dir, "muxed.ts")
            instrumentation.context.assets.open("hls/segment-000.ts").use { input -> muxed.outputStream().use { input.copyTo(it) } }
            val output = File(dir, "out.mp4")
            try {
                TsToMp4Remuxer().remuxTrack(listOf(muxed), output, TsToMp4Remuxer.TrackRole.AUDIO, 0L, 2_000_000, TransferCancellation())
                fail("must refuse a two-track input as one role")
            } catch (failure: TransferFailure) {
                assertEquals(FailureKind.UNSUPPORTED, failure.kind); assertFalse(output.exists())
            }
        } finally { dir.deleteRecursively() }
    }
}

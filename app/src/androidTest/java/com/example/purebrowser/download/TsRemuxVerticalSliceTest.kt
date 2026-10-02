package com.example.purebrowser.download

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.purebrowser.download.hls.TsToMp4Remuxer
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TsRemuxVerticalSliceTest {
    @Test fun tsH264AacWithBFramesProducesIndependentSeekableMp4() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrumentation.targetContext.cacheDir,"hls-vertical").apply { mkdirs() }
        try {
            val segments=(0..3).map { i -> File(dir,"$i.ts").also { file -> instrumentation.context.assets.open("hls/segment-%03d.ts".format(i)).use { input -> file.outputStream().use { input.copyTo(it) } } } }
            val output=File(dir,"output.mp4")
            val result=TsToMp4Remuxer().remux(segments,output,8_000_000,TransferCancellation())
            assertEquals(192L,result.videoSamples);assertTrue(result.audioSamples>370)
            assertTrue(kotlin.math.abs(result.durationUs-8_000_000)<100_000)
            val extractor=MediaExtractor()
            try {
                extractor.setDataSource(output.path);assertEquals(2,extractor.trackCount)
                val tracks=(0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                assertTrue(tracks.any { it.getString(MediaFormat.KEY_MIME)=="video/avc" })
                assertTrue(tracks.any { it.getString(MediaFormat.KEY_MIME)=="audio/mp4a-latm" })
                extractor.selectTrack(tracks.indexOfFirst { it.getString(MediaFormat.KEY_MIME)=="video/avc" })
                extractor.seekTo(4_000_000,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);assertTrue(extractor.sampleTime>=2_000_000)
                assertEquals(FormatCheck.PASSED,ManagedFileStore(instrumentation.targetContext).inspect(output).format)
            } finally { extractor.release() }
        } finally { dir.deleteRecursively() }
    }
    @Test fun multipleMuxedAudioTracksAreNotSilentlyReducedToOne() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrument.targetContext.cacheDir,"hls-multi-audio").apply { mkdirs() }
        try {
            val input=File(dir,"multi.ts")
            instrument.context.assets.open("hls/unsupported-two-audio.ts").use { source->input.outputStream().use { source.copyTo(it) } }
            val output=File(dir,"output.mp4")
            try { TsToMp4Remuxer().remux(listOf(input),output,2_000_000,TransferCancellation());fail("must refuse ambiguous audio") }
            catch(error:TransferFailure) { assertEquals(FailureKind.UNSUPPORTED,error.kind);assertFalse(output.exists()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptTsProgramTableCannotBePublished() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrument.targetContext.cacheDir,"hls-corrupt-pat").apply { mkdirs() }
        try {
            val bytes=instrument.context.assets.open("hls/segment-000.ts").use { it.readBytes() }
            var changed=false
            for(packet in 0 until bytes.size/188) {
                val start=packet*188
                val pid=((bytes[start+1].toInt() and 31) shl 8) or (bytes[start+2].toInt() and 255)
                if(pid==0) { bytes[start+10]=(bytes[start+10].toInt() xor 1).toByte();changed=true;break }
            }
            assertTrue(changed);val input=File(dir,"corrupt.ts").apply { writeBytes(bytes) };val output=File(dir,"output.mp4")
            try { TsToMp4Remuxer().remux(listOf(input),output,2_000_000,TransferCancellation());fail("must refuse corrupt PAT") }
            catch(error:TransferFailure) { assertEquals(FailureKind.UNSUPPORTED,error.kind);assertFalse(output.exists()) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun inBandAvcParameterChangesAreRejectedEvenWithoutPlaylistDiscontinuity() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=File(instrument.targetContext.cacheDir,"hls-changed-avc").apply { mkdirs() }
        try {
            val files=listOf("segment-000.ts","unsupported-changed-resolution.ts").mapIndexed { index,name ->
                File(dir,"$index.ts").also { file->instrument.context.assets.open("hls/$name").use { source->file.outputStream().use { source.copyTo(it) } } }
            }
            val output=File(dir,"output.mp4")
            try { TsToMp4Remuxer().remux(files,output,4_000_000,TransferCancellation());fail("must refuse changed AVC parameters") }
            catch(error:TransferFailure) { assertEquals(FailureKind.UNSUPPORTED,error.kind);assertTrue(error.safeMessage.contains("编码参数"));assertFalse(output.exists()) }
        } finally { dir.deleteRecursively() }
    }

}

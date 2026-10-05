package com.example.purebrowser.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Coordinator-level end-to-end for separate-audio HLS: authored playlists + authored TS segments
 * over a fake transport only. Master → variant ＋ audio-rendition plans → dual-track lease →
 * segment fetch → per-track remux → existing muxer → one verified public MP4. No site contact.
 */
@RunWith(AndroidJUnit4::class)
class HlsSeparateAudioTransferTest {
    private val entry = "https://cdn.example/hls-separate/master.m3u8"

    private inner class Fixture {
        val text = mutableMapOf<String, ByteArray>()
        val bytes = mutableMapOf<String, ByteArray>()
        var audioStatus = 200
        fun install() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            text[entry] = instrumentation.context.assets.open("hls-separate/master.m3u8").readBytes()
            text["https://cdn.example/hls-separate/video.m3u8"] = instrumentation.context.assets.open("hls-separate/video.m3u8").readBytes()
            text["https://cdn.example/hls-separate/audio.m3u8"] = instrumentation.context.assets.open("hls-separate/audio.m3u8").readBytes()
            listOf("video-000.ts", "video-001.ts", "video-002.ts", "video-003.ts",
                "audio-000.ts", "audio-001.ts", "audio-002.ts", "audio-003.ts", "audio-004.ts").forEach { name ->
                bytes["https://cdn.example/hls-separate/$name"] = instrumentation.context.assets.open("hls-separate/$name").readBytes()
            }
        }
    }

    private fun transport(fixture: Fixture, visited: MutableList<String>? = null): HttpTransport {
        fun response(payload: ByteArray, status: Int): HttpResponse = object : HttpResponse {
            override val status = status
            override fun header(name: String) = when (name) {
                "Content-Length" -> payload.size.toString()
                else -> null
            }
            override fun body(): InputStream = ByteArrayInputStream(payload)
            override fun close() {}
        }
        return HttpTransport { url, _, _ ->
            visited?.add(url.substringAfterLast('/'))
            val playlist = fixture.text.entries.singleOrNull { it.key == url }?.value
            if (playlist != null) return@HttpTransport response(playlist, 200)
            val segment = fixture.bytes.entries.singleOrNull { it.key == url }?.value
                ?: return@HttpTransport response(byteArrayOf(), 404)
            val audio = url.substringAfterLast('/').startsWith("audio-")
            response(segment, if (audio) fixture.audioStatus else 200)
        }
    }

    private fun draft() = DownloadDraft(
        MediaCandidate(entry, MediaKind.HLS, emptySet(), "application/vnd.apple.mpegurl"),
        "SeparateAudioFixture", sourceUrl = "https://www.example.test/talks/fixture", sourceTitle = "Authored fixture",
        useAccessContext = false, reliableSource = false,
    )

    private fun planned(resolver: HlsResolver): com.example.purebrowser.download.hls.HlsDownloadPlan {
        val cancel = TransferCancellation()
        val options = resolver.resolveEntry(draft(), cancel)
        val variant = (options.playlist as com.example.purebrowser.download.hls.HlsPlaylist.Master).variants.single()
        return resolver.resolvePlan(draft(), options, variant, cancel)
    }

    @Test fun separateAudioPlanSavesOneVerifiedMp4ThroughTheDualTrackLease() = DualTrackTestSupport.test { repo ->
        val fixture = Fixture(); fixture.install()
        val plan = planned(HlsResolver(transport(fixture), AccessContextProvider { null }, false))
        assertNotNull(plan.audio)
        val id = repo.enqueue(draft(), false, "separate.mp4", hlsPlan = plan)
        val record = repo.record(id)!!
        assertEquals(DownloadProtocol.DUAL_TRACK, record.protocol)
        assertNotNull(record.dualTrackMetadata)
        assertFalse(record.resumeAvailable)
        DualTrackTransfer(repo, transport(fixture), AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        val asset = repo.stateSnapshot().assets.single()
        assertEquals(FormatCheck.PASSED, asset.format)
        assertEquals("video/mp4", asset.mimeType)
        assertTrue(!repo.files!!.stage(id).exists())
        assertEquals(0L, repo.files!!.cacheBytes(id))
        // Segment temp files never outlive the task workspace; URLs are never persisted.
        assertEquals(0L, repo.files!!.dualTrackWorkspace.cacheBytes(id))
        assertFalse(repo.store.file.readText().contains("cdn.example/hls-separate/video-"))
    }

    @Test fun audioSegmentFailureNeverPublishesVideoAloneAndKeepsNoResumeBytes() = DualTrackTestSupport.test { repo ->
        val fixture = Fixture(); fixture.install(); fixture.audioStatus = 403
        val plan = planned(HlsResolver(transport(fixture), AccessContextProvider { null }, false))
        val id = repo.enqueue(draft(), false, "separate.mp4", hlsPlan = plan)
        DualTrackTransfer(repo, transport(fixture), AccessContextProvider { null }).run(id, TransferCancellation())
        val record = repo.record(id)!!
        assertEquals(TaskStatus.FAILED, record.taskStatus)
        assertEquals(FailureKind.ACCESS_CONDITION, record.failure)
        assertTrue(record.safeFailure!!.contains("访问条件"))
        assertTrue(repo.stateSnapshot().assets.isEmpty())
        assertFalse(repo.files!!.stage(id).exists())
        assertEquals(0L, repo.files!!.dualTrackWorkspace.cacheBytes(id))
        assertTrue(runCatching { repo.queueResume(id) }.isFailure)
    }
}

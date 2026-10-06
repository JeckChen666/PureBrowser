package com.example.purebrowser.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.dash.Fmp4FixtureAuthoring
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.download.mux.AuthoredMuxFixtures
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * T107 coordinator-level end-to-end: authored fMP4 audio rendition (Fmp4FixtureAuthoring turns the
 * self-authored AAC fixture into init + .m4s segments; the init is served from inside a larger
 * container through an exact closed BYTERANGE window) + authored TS video over a fake transport
 * only. Master → variant ＋ audio-rendition plans → dual-track lease → init/segment fetch →
 * per-track assembly/remux → existing muxer → one verified public MP4. No site contact.
 */
@RunWith(AndroidJUnit4::class)
class HlsFmp4AudioDualTrackTransferTest {
    private val entry = "https://cdn.example/fls-fmp4/master.m3u8"

    private inner class Fixture {
        val text = mutableMapOf<String, ByteArray>()
        val bytes = mutableMapOf<String, ByteArray>()
        val initOffset = 96
        lateinit var initBytes: ByteArray
        lateinit var container: ByteArray
        var ignoreRange = false
        val requestedRanges = mutableListOf<String>()
        fun install() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.cacheDir, "fls-fmp4-${UUID.randomUUID()}")
                .apply { assertTrue(mkdirs()) }
            try {
                val (init, segments) = Fmp4FixtureAuthoring.authoredFmp4(
                    directory, AuthoredMuxFixtures.AUDIO, "audio", fragmentDurationMs = 500,
                )
                val durationUs = Fmp4FixtureAuthoring.capture(File(directory, "audio-source.mp4")).single().durationUs
                assertTrue(segments.size >= 2)
                initBytes = init.readBytes()
                // The init lives at a non-zero offset inside a larger container: only the exact
                // closed BYTERANGE window is the init segment (RFC 8216 §4.3.2.5).
                container = ByteArray(initOffset + initBytes.size + 64).also { initBytes.copyInto(it, initOffset) }
                val perSegmentUs = durationUs / segments.size
                text[entry] = buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-VERSION:6")
                    appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
                    appendLine("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\",URI=\"audio.m3u8\"")
                    appendLine("#EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=320x180,FRAME-RATE=24.000,CODECS=\"avc1.64000c,mp4a.40.2\",AUDIO=\"aud\"")
                    append("video.m3u8")
                }.toByteArray()
                text["https://cdn.example/fls-fmp4/video.m3u8"] = buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-VERSION:6")
                    appendLine("#EXT-X-TARGETDURATION:2")
                    appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                    appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
                    appendLine("#EXTINF:2.000000,")
                    appendLine("video-000.ts")
                    // VOD media playlists must close with the end marker; the production parser's
                    // live gate (no ENDLIST => honest refusal) applies to the fixture too.
                    append("#EXT-X-ENDLIST")
                }.toByteArray()
                text["https://cdn.example/fls-fmp4/audio.m3u8"] = buildString {
                    appendLine("#EXTM3U")
                    appendLine("#EXT-X-VERSION:6")
                    appendLine("#EXT-X-TARGETDURATION:3")
                    appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                    appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
                    appendLine("#EXT-X-MAP:URI=\"audio-init.bin\",BYTERANGE=\"${initBytes.size}@$initOffset\"")
                    segments.forEachIndexed { index, _ ->
                        append(String.format(Locale.US, "#EXTINF:%.6f,\n", perSegmentUs / 1_000_000.0))
                        appendLine("audio-${index.toString().padStart(3, '0')}.m4s")
                    }
                    append("#EXT-X-ENDLIST")
                }.toByteArray()
                bytes["https://cdn.example/fls-fmp4/audio-init.bin"] = container
                segments.forEachIndexed { index, file ->
                    bytes["https://cdn.example/fls-fmp4/audio-${index.toString().padStart(3, '0')}.m4s"] = file.readBytes()
                }
                bytes["https://cdn.example/fls-fmp4/video-000.ts"] = instrumentation.context.assets.open("hls-separate/video-000.ts").readBytes()
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun transport(fixture: Fixture): HttpTransport {
        val range = Regex("bytes=([0-9]{1,19})-([0-9]{1,19})")
        fun response(payload: ByteArray, status: Int, headers: Map<String, String> = emptyMap()): HttpResponse =
            object : HttpResponse {
                override val status = status
                override fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
                override fun body(): InputStream = ByteArrayInputStream(payload)
                override fun close() {}
            }
        return HttpTransport { url, headers, _ ->
            val playlist = fixture.text.entries.singleOrNull { it.key == url }?.value
            if (playlist != null) return@HttpTransport response(playlist, 200, mapOf("Content-Length" to playlist.size.toString()))
            val payload = fixture.bytes.entries.singleOrNull { it.key == url }?.value
                ?: return@HttpTransport response(byteArrayOf(), 404)
            if (url.endsWith("audio-init.bin")) {
                val requested = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
                if (requested == null || fixture.ignoreRange)
                    return@HttpTransport response(payload, 200, mapOf("Content-Length" to payload.size.toString()))
                synchronized(fixture.requestedRanges) { fixture.requestedRanges.add(requested) }
                val match = range.matchEntire(requested) ?: return@HttpTransport response(byteArrayOf(), 416)
                val first = match.groupValues[1].toLong().toInt()
                val last = match.groupValues[2].toLong().toInt()
                val slice = payload.copyOfRange(first, last + 1)
                return@HttpTransport response(slice, 206, mapOf(
                    "Content-Length" to slice.size.toString(),
                    "Content-Range" to "bytes $first-$last/${payload.size}",
                ))
            }
            response(payload, 200, mapOf("Content-Length" to payload.size.toString()))
        }
    }

    private fun draft() = DownloadDraft(
        MediaCandidate(entry, MediaKind.HLS, emptySet(), "application/vnd.apple.mpegurl"),
        "Fmp4FixtureAgent", sourceUrl = "https://www.example.test/talks/fmp4-fixture", sourceTitle = "Authored fixture",
        useAccessContext = false, reliableSource = false,
    )

    private fun planned(resolver: HlsResolver): com.example.purebrowser.download.hls.HlsDownloadPlan {
        val cancel = TransferCancellation()
        val options = resolver.resolveEntry(draft(), cancel)
        val variant = (options.playlist as com.example.purebrowser.download.hls.HlsPlaylist.Master).variants.single()
        return resolver.resolvePlan(draft(), options, variant, cancel)
    }

    @Test fun fmp4AudioRenditionSavesOneVerifiedMp4ThroughTheDualTrackLease() = DualTrackTestSupport.test { repo ->
        val fixture = Fixture(); fixture.install()
        val plan = planned(HlsResolver(transport(fixture), AccessContextProvider { null }, false))
        assertNotNull(plan.audio)
        assertEquals(com.example.purebrowser.download.hls.SegmentFormat.FMP4, plan.audio!!.media.format)
        val id = repo.enqueue(draft(), false, "fmp4-audio.mp4", hlsPlan = plan)
        val record = repo.record(id)!!
        assertEquals(DownloadProtocol.DUAL_TRACK, record.protocol)
        DualTrackTransfer(repo, transport(fixture), AccessContextProvider { null }).run(id, TransferCancellation())
        assertEquals(TaskStatus.SUCCEEDED, repo.record(id)!!.taskStatus)
        // The init was fetched as exactly the declared closed BYTERANGE window, nothing wider.
        assertEquals(listOf("bytes=${fixture.initOffset}-${fixture.initOffset + fixture.initBytes.size - 1}"),
            synchronized(fixture.requestedRanges) { fixture.requestedRanges.toList() })
        val asset = repo.stateSnapshot().assets.single()
        assertEquals(FormatCheck.PASSED, asset.format)
        assertEquals("video/mp4", asset.mimeType)
        assertTrue(!repo.files!!.stage(id).exists())
        assertEquals(0L, repo.files!!.cacheBytes(id))
        // Segment and init temp files never outlive the task workspace; URLs are never persisted.
        assertEquals(0L, repo.files!!.dualTrackWorkspace.cacheBytes(id))
        assertFalse(repo.store.file.readText().contains("cdn.example/fls-fmp4/audio-"))
    }

    @Test fun byterangeInitServedAsAFullBodyIsAnHonestRefusal() = DualTrackTestSupport.test { repo ->
        val fixture = Fixture(); fixture.install(); fixture.ignoreRange = true
        val plan = planned(HlsResolver(transport(fixture), AccessContextProvider { null }, false))
        val id = repo.enqueue(draft(), false, "fmp4-audio.mp4", hlsPlan = plan)
        DualTrackTransfer(repo, transport(fixture), AccessContextProvider { null }).run(id, TransferCancellation())
        val record = repo.record(id)!!
        assertEquals(TaskStatus.FAILED, record.taskStatus)
        assertEquals(FailureKind.HTTP_REJECTED, record.failure)
        assertTrue(record.safeFailure!!.contains("字节范围"))
        assertTrue(repo.stateSnapshot().assets.isEmpty())
        assertFalse(repo.files!!.stage(id).exists())
        assertEquals(0L, repo.files!!.dualTrackWorkspace.cacheBytes(id))
    }
}

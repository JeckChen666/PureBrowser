package com.example.purebrowser.download.hls

import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.HttpResponse
import com.example.purebrowser.download.HttpTransport
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.TransferFailure
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Fake-transport planning tests: playlists are authored text, no site is contacted. */
class HlsDualTrackPlanTest {
    private val entry = "https://cdn.example/talk/master.m3u8"
    private val renditions = listOf(
        "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English\",LANGUAGE=\"en\",CHANNELS=\"2\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio.m3u8\"",
    )
    private fun master(variantAttrs: String = "BANDWIDTH=800000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\",AUDIO=\"aud\"",
        media: List<String> = renditions) = buildString {
        appendLine("#EXTM3U")
        media.forEach { appendLine(it) }
        appendLine("#EXT-X-STREAM-INF:$variantAttrs")
        appendLine("video.m3u8")
    }.trimIndent()
    private fun videoPlaylist(target: Int = 2, entries: String = "#EXTINF:2.000000,\nvideo-000.ts\n#EXTINF:2.000000,\nvideo-001.ts") =
        "#EXTM3U\n#EXT-X-TARGETDURATION:$target\n#EXT-X-PLAYLIST-TYPE:VOD\n$entries\n#EXT-X-ENDLIST\n"
    private fun audioPlaylist(target: Int = 3, entries: String = "#EXTINF:2.005333,\naudio-000.ts\n#EXTINF:1.994667,\naudio-001.ts",
        extra: String = "") = "#EXTM3U\n#EXT-X-TARGETDURATION:$target\n#EXT-X-PLAYLIST-TYPE:VOD\n$extra$entries\n#EXT-X-ENDLIST\n"

    private fun transport(playlists: Map<String, String>): HttpTransport {
        fun response(bytes: ByteArray): HttpResponse = object : HttpResponse {
            override val status = 200
            override fun header(name: String) = if (name == "Content-Length") bytes.size.toString() else null
            override fun body(): InputStream = ByteArrayInputStream(bytes)
            override fun close() {}
        }
        return HttpTransport { url, _, _ ->
            playlists.entries.singleOrNull { url.removeSuffix("/") == it.key.removeSuffix("/") }?.let { response(it.value.toByteArray()) }
                ?: response(byteArrayOf())
        }
    }
    private fun resolver(playlists: Map<String, String>) =
        HlsResolver(transport(playlists), AccessContextProvider { null }, false)
    private fun draft() = DownloadDraft(MediaCandidate(entry, MediaKind.HLS, emptySet(), "application/vnd.apple.mpegurl"),
        "FixtureAgent", sourceUrl = "https://www.example.test/talks/fixture")
    private fun resolve(playlists: Map<String, String>, body: String = master()): HlsDownloadPlan {
        val cancel = TransferCancellation()
        // The custom master under test must actually be served at the entry address; the
        // default map's own master would otherwise silently mask the scenario.
        val client = resolver(playlists + (entry to body))
        val options = client.resolveEntry(draft().copy(candidate = MediaCandidate(entry, MediaKind.HLS, emptySet(),
            "application/vnd.apple.mpegurl")), cancel)
        val variant = (options.playlist as HlsPlaylist.Master).variants.single()
        return client.resolvePlan(draft(), options, variant, cancel)
    }
    private fun defaultPlaylists(video: String = videoPlaylist(), audio: String = audioPlaylist()) = mapOf(
        entry to master(),
        "https://cdn.example/talk/video.m3u8" to video,
        "https://cdn.example/talk/audio.m3u8" to audio,
    )
    private fun unsupported(body: String, playlists: Map<String, String> = defaultPlaylists(), word: String): TransferFailure {
        try { resolve(playlists, body); fail("Expected an honest unsupported failure") }
        catch (failure: TransferFailure) {
            assertEquals(FailureKind.UNSUPPORTED, failure.kind)
            assertTrue(failure.safeMessage, failure.safeMessage.contains(word))
            assertFalse(failure.safeMessage.contains("://"))
            return failure
        }
        throw AssertionError("unreachable")
    }

    @Test fun oneVariantOneRenditionBuildsADualTrackPlan() {
        val plan = resolve(defaultPlaylists())
        assertNotNull(plan.audio)
        assertEquals("https://cdn.example/talk/video.m3u8", plan.playlistUrl)
        assertEquals("https://cdn.example/talk/audio.m3u8", plan.audio!!.playlistUrl)
        assertEquals(2, plan.media.segments.size)
        assertEquals(2, plan.audio!!.media.segments.size)
        assertEquals("en", plan.audio!!.rendition.language)
        assertEquals("2", plan.audio!!.rendition.channels)
        assertEquals(SegmentFormat.MPEG_TS, plan.media.format)
        assertEquals(SegmentFormat.MPEG_TS, plan.audio!!.media.format)
        assertEquals(4_000_000L, plan.media.durationUs)
    }

    @Test fun uriLessAudioGroupFallsBackToTheSingleTrackPlan() {
        val plan = resolve(defaultPlaylists(), master(media = listOf(
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"muxed\",DEFAULT=YES")))
        assertNull(plan.audio)
        assertEquals(2, plan.media.segments.size)
    }

    @Test fun encryptedAudioRenditionIsHonestlyRejected() {
        val encrypted = audioPlaylist(extra = "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n")
        unsupported(master(), defaultPlaylists(audio = encrypted), word = "加密")
    }

    @Test fun liveAudioRenditionIsHonestlyRejected() {
        val live = audioPlaylist().replace("#EXT-X-ENDLIST\n", "")
        unsupported(master(), defaultPlaylists(audio = live), word = "结束标记")
    }

    @Test fun fmp4AudioRenditionIsDeclaredButRejectedAtTheSegmentFormatSeam() {
        val fmp4 = audioPlaylist(entries = "#EXTINF:2.0,\naudio-000.m4s\n#EXTINF:2.0,\naudio-001.m4s")
        val failure = unsupported(master(), defaultPlaylists(audio = fmp4), word = "fMP4")
        // The seam's message is fixed safe text, usable from any caller.
        try { SegmentFormat.FMP4.requireTransferSupported(); fail("Expected the seam to refuse fMP4") }
        catch (seam: TransferFailure) { assertEquals(failure.safeMessage, seam.safeMessage) }
        SegmentFormat.MPEG_TS.requireTransferSupported()
    }

    @Test fun multipleRenditionsWithoutADefaultAreRefusedInsteadOfGuessing() {
        val media = listOf(
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"English\",LANGUAGE=\"en\",URI=\"audio.m3u8\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"Other\",LANGUAGE=\"fr\",URI=\"audio.m3u8\"",
        )
        unsupported(master(media = media), defaultPlaylists(), word = "默认音轨")
    }

    @Test fun nonH264CodecsRefuseTheDualTrackPlan() {
        unsupported(master(variantAttrs = "BANDWIDTH=800000,RESOLUTION=1280x720,CODECS=\"hvc1.1.6.L93.B0,mp4a.40.2\",AUDIO=\"aud\""),
            defaultPlaylists(), word = "H.264")
    }

    @Test fun durationMismatchBetweenTracksIsRefused() {
        val short = audioPlaylist(entries = "#EXTINF:1.0,\naudio-000.ts")
        unsupported(master(), defaultPlaylists(audio = short), word = "时长不一致")
    }

    @Test fun dualTrackDeclarationMapsOntoTheTransferLevelPlan() {
        val plan = resolve(defaultPlaylists())
        val declaration = HlsDualTrackPlan.from(plan, "https://www.example.test/talks/fixture")
        assertEquals(4, declaration.videoSegments.size + declaration.audioSegments.size)
        assertEquals(4_000_000L, declaration.durationUs)
        // The declaration keeps the playlist's own codec tokens: DualTrackMetadata accepts the
        // full avc1 profile id (as sibling fixtures use); only non-profile H.264 maps to "h264".
        assertEquals("avc1.4d401f", declaration.videoCodec)
        assertEquals("mp4a.40.2", declaration.audioCodec)
        assertEquals("https://www.example.test/talks/fixture", declaration.safeSourceUrl)
        // Identity is stable and hashes before persistence; metadata validates within budgets.
        assertEquals(declaration.metadata(), HlsDualTrackPlan.from(plan).metadata())
        declaration.metadata().validate()
        declaration.validate(false)
        assertEquals(declaration.metadata(), declaration.metadata())
        assertFalse(declaration.toString().contains("https://"))
    }

    @Test fun segmentAddressPolicyStillAppliesToBothTracks() {
        val remote = videoPlaylist(entries = "#EXTINF:2.0,\nhttp://foreign.example/video-000.ts")
        try { resolve(defaultPlaylists(video = remote)); fail("Expected URL policy rejection") }
        catch (failure: TransferFailure) { assertEquals(FailureKind.UNSUPPORTED, failure.kind) }
    }
}

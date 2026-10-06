package com.example.purebrowser.download.hls

import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.HttpResponse
import com.example.purebrowser.download.HttpTransport
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.codec.Av1Capability
import com.example.purebrowser.media.codec.Av1CapabilityProvider
import com.example.purebrowser.media.codec.Av1DecodeSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * T110 offer-layer gating for HLS masters: the parser stays capability-blind; HlsResolver applies
 * hide/warn after the pure parse. Fake transport — authored playlists only, no site is contacted.
 */
class HlsResolverAv1GateTest {
    private val entry = "https://cdn.example/master.m3u8"
    private val master = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS=\"av01.0.05M.08,mp4a.40.2\"")
        appendLine("gear1080av1.m3u8")
        appendLine("#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"")
        appendLine("gear720.m3u8")
    }.trimIndent()

    private fun transport(body: String): HttpTransport {
        val bytes = body.toByteArray()
        val response = object : HttpResponse {
            override val status = 200
            override fun header(name: String) = if (name == "Content-Length") bytes.size.toString() else null
            override fun body(): InputStream = ByteArrayInputStream(bytes)
            override fun close() {}
        }
        return HttpTransport { _, _, _ -> response }
    }

    private fun resolver(support: Av1DecodeSupport) = HlsResolver(
        transport(master), AccessContextProvider { null }, false, Av1CapabilityProvider { support },
    )

    private fun draft() = DownloadDraft(
        MediaCandidate(entry, MediaKind.HLS, emptySet(), "application/vnd.apple.mpegurl"),
        "FixtureAgent", sourceUrl = "https://www.example.test/talks/fixture",
    )

    private fun masterOf(support: Av1DecodeSupport): HlsPlaylist.Master {
        val options = resolver(support).resolveEntry(draft(), TransferCancellation())
        return options.playlist as HlsPlaylist.Master
    }

    @Test fun availableSupportKeepsEveryParserVariantAndWarning() {
        val master = masterOf(Av1DecodeSupport.AVAILABLE)
        assertEquals(2, master.variants.size)
        val av1 = master.variants.first { it.codecs!!.startsWith("av01") }
        assertTrue(av1.supported)
        assertEquals("此档位编码不是 H.264/AAC，保存时可能失败", av1.unsupportedReason)
        assertNull(master.variants.first { it.codecs!!.startsWith("avc1") }.unsupportedReason)
    }

    @Test fun warnedSupportKeepsTheAv1VariantSelectableAndAppendsTheSoftwareDecodeNote() {
        val master = masterOf(Av1DecodeSupport.WARNED)
        assertEquals(2, master.variants.size)
        val av1 = master.variants.first { it.codecs!!.startsWith("av01") }
        assertTrue("a WARNED variant stays an allowed honest attempt", av1.supported)
        assertEquals(
            "此档位编码不是 H.264/AAC，保存时可能失败；${Av1Capability.SOFTWARE_DECODE_WARNING}",
            av1.unsupportedReason,
        )
        assertNull(master.variants.first { it.codecs!!.startsWith("avc1") }.unsupportedReason)
    }

    @Test fun hiddenSupportRemovesTheAv1VariantEntirely() {
        val master = masterOf(Av1DecodeSupport.HIDDEN)
        assertEquals(1, master.variants.size)
        assertTrue(master.variants.single().codecs!!.startsWith("avc1"))
        // Default picking can no longer suggest the absent AV1 gear either.
        val chosen = HlsPlaylistParser.defaultVariant(master.variants)
        assertEquals("https://cdn.example/gear720.m3u8", chosen!!.url)
    }

    @Test fun allAv1MasterOnHiddenDeviceYieldsNoSelectableVariant() {
        val onlyAv1 = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS=\"av01.0.05M.08,mp4a.40.2\"")
            appendLine("gear1080av1.m3u8")
        }.trimIndent()
        val options = HlsResolver(
            transport(onlyAv1), AccessContextProvider { null }, false,
            Av1CapabilityProvider { Av1DecodeSupport.HIDDEN },
        ).resolveEntry(draft(), TransferCancellation())
        val master = options.playlist as HlsPlaylist.Master
        assertTrue("hidden means absent, not greyed", master.variants.isEmpty())
        assertNull(HlsPlaylistParser.defaultVariant(master.variants))
    }
}

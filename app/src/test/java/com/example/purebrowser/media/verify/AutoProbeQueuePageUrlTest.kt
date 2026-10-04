package com.example.purebrowser.media.verify

import com.example.purebrowser.media.MediaKind
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AutoProbeQueuePageUrlTest {
    private val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\ngear-720.m3u8\n"

    private class FakeFetcher(private val handle: (String, Map<String, String>) -> FetchedResponse) : UrlFetcher {
        override fun open(url: String, headers: Map<String, String>): FetchedResponse = handle(url, headers)
    }

    private fun text(body: String = "", status: Int = 200, headers: Map<String, String> = emptyMap()) =
        FetchedResponse(status, headers, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))

    private val ctx = SessionContext("https://cdn.example.com/watch", "UnitUA/1.0") { target ->
        if (target.startsWith("https://cdn.example.com/")) "sid=secret" else null
    }

    @Test fun onlyVerifiedOutcomesCarryTheProbingPageAddress() = runTest {
        val fetcher = FakeFetcher { url, _ ->
            when {
                url.endsWith("ok.mp4") -> text(status = 206, body = "x",
                    headers = mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 0-0/42"))
                url.endsWith("page.mp4") -> text(status = 200, body = "x", headers = mapOf("Content-Type" to "text/html"))
                else -> text(status = 403)
            }
        }
        val results = mutableListOf<ProbeResult>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, _, result -> results += result }
        queue.start()
        queue.submit("https://cdn.example.com/ok.mp4", MediaKind.FILE, 1L, ctx)
        queue.submit("https://cdn.example.com/page.mp4", MediaKind.FILE, 1L, ctx)
        queue.submit("https://cdn.example.com/denied.mp4", MediaKind.FILE, 1L, ctx)
        runCurrent()
        assertEquals(3, results.size)
        val verified = results.filterIsInstance<ProbeResult.Verified>().single()
        assertEquals(42L, verified.totalBytes)
        assertEquals("https://cdn.example.com/watch", verified.pageUrl)
        assertTrue(results.contains(ProbeResult.NotMedia))
        assertTrue(results.contains(ProbeResult.Unreachable))
    }

    @Test fun playlistEnrichmentStillDeliversThePageAddress() = runTest {
        val fetcher = FakeFetcher { _, headers ->
            if ("Range" in headers) text(headers = mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
            else text(master, headers = mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
        }
        val results = mutableListOf<ProbeResult>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, _, result -> results += result }
        queue.start()
        queue.submit("https://cdn.example.com/hls/master.m3u8", MediaKind.HLS, 1L, ctx)
        runCurrent()
        val verified = results.single() as ProbeResult.Verified
        assertEquals(1, verified.variants!!.size)
        assertEquals("https://cdn.example.com/watch", verified.pageUrl)
    }

    @Test fun pageAddressesNeverSurfaceInDiagnostics() = runTest {
        val fetcher = FakeFetcher { _, _ ->
            text(status = 206, body = "x", headers = mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 0-0/42"))
        }
        val results = mutableListOf<ProbeResult>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, _, result -> results += result }
        queue.start()
        queue.submit("https://cdn.example.com/ok.mp4", MediaKind.FILE, 1L, ctx)
        runCurrent()
        assertFalse(results.single().toString().contains("cdn.example.com"))
    }
}

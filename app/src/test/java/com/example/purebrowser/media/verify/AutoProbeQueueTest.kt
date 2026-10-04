package com.example.purebrowser.media.verify

import com.example.purebrowser.media.MediaKind
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AutoProbeQueueTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
        gear-720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L93.B0,mp4a.40.2"
        gear-1080.m3u8
    """.trimIndent()
    private val liveMedia = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nseg00001.ts\n"

    private class FakeFetcher(private val handle: (String, Map<String, String>) -> FetchedResponse) : UrlFetcher {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun open(url: String, headers: Map<String, String>): FetchedResponse {
            calls += url to headers
            return handle(url, headers)
        }
        fun probeCalls() = calls.count { "Range" in it.second }
        fun playlistCalls() = calls.count { "Range" !in it.second }
    }

    private fun text(body: String = "", status: Int = 200, headers: Map<String, String> = emptyMap()) =
        FetchedResponse(status, headers, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))

    private val ctx = SessionContext("https://cdn.example/watch", "UnitUA/1.0") { target ->
        if (target.startsWith("https://cdn.example/")) "sid=secret" else null
    }

    @Test fun hlsProbeFetchesPlaylistWithSessionHeadersAndParsesVariants() = runTest {
        val fetcher = FakeFetcher { url, headers ->
            if ("Range" in headers) text(
                status = 206,
                headers = mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=UTF-8",
                    "Content-Range" to "bytes 0-0/999999"),
                body = "x",
            ) else text(master, headers = mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
        }
        val results = mutableListOf<Pair<Long, ProbeResult>>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, epoch, result -> results += epoch to result }
        queue.start()
        queue.submit("https://cdn.example/hls/master.m3u8", MediaKind.HLS, 7L, ctx)
        runCurrent()
        val verified = results.single().second as ProbeResult.Verified
        assertEquals(999999L, verified.totalBytes)
        assertTrue(verified.resumable)
        assertEquals(2, verified.variants!!.size)
        assertNull(verified.variants!![0].warning)
        assertNotNull(verified.variants!![1].warning)
        val playlist = fetcher.calls.single { "Range" !in it.second }
        assertEquals("sid=secret", playlist.second["Cookie"])
        assertEquals("https://cdn.example/watch", playlist.second["Referer"])
        assertEquals("UnitUA/1.0", playlist.second["User-Agent"])
    }

    @Test fun hardRejectedPlaylistsKeepTheProbeVerdictAndCarryTheSafeReason() = runTest {
        val fetcher = FakeFetcher { _, headers ->
            if ("Range" in headers) text(status = 200, headers = mapOf("Content-Type" to "audio/mpegurl"))
            else text(liveMedia)
        }
        val results = mutableListOf<ProbeResult>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, _, result -> results += result }
        queue.start()
        queue.submit("https://cdn.example/live/index.m3u8", MediaKind.HLS, 1L, ctx)
        runCurrent()
        val verified = results.single() as ProbeResult.Verified
        assertEquals(MediaKind.HLS, verified.kindHint)
        assertNull(verified.variants)
        assertNotNull(verified.playlistWarning)
        assertFalse(verified.playlistWarning!!.contains("://"))
    }

    @Test fun forbiddenResponsesAreUnreachable() = runTest {
        val fetcher = FakeFetcher { _, _ -> text(status = 403) }
        val results = mutableListOf<ProbeResult>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, _, result -> results += result }
        queue.start()
        queue.submit("https://cdn.example/denied.mp4", MediaKind.FILE, 1L, ctx)
        runCurrent()
        assertEquals(listOf<ProbeResult>(ProbeResult.Unreachable), results)
    }

    @Test fun probeBudgetIsPerEpochAndCapsAtTwenty() = runTest {
        val fetcher = FakeFetcher { _, headers ->
            text(status = 200, headers = mapOf("Content-Type" to "video/mp4"), body = "x")
        }
        val results = mutableListOf<Pair<Long, ProbeResult>>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, epoch, result -> results += epoch to result }
        queue.start()
        repeat(25) { queue.submit("https://cdn.example/clip$it.mp4", MediaKind.FILE, 1L, ctx) }
        runCurrent()
        assertEquals(20, results.size)
        assertEquals(20, fetcher.calls.size)
        // A new epoch resets the budget.
        repeat(5) { queue.submit("https://cdn.example/next$it.mp4", MediaKind.FILE, 2L, ctx) }
        runCurrent()
        assertEquals(25, results.size)
        assertTrue(results.take(20).all { it.first == 1L })
    }

    @Test fun playlistFetchBudgetCapsAtThreePerEpoch() = runTest {
        val fetcher = FakeFetcher { _, headers ->
            if ("Range" in headers) text(status = 200, headers = mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
            else text(master)
        }
        val results = mutableListOf<ProbeResult>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { _, _, result -> results += result }
        queue.start()
        repeat(5) { queue.submit("https://cdn.example/hls/v$it.m3u8", MediaKind.HLS, 1L, ctx) }
        runCurrent()
        assertEquals(5, results.size)
        assertEquals(3, fetcher.playlistCalls())
        assertEquals(5, fetcher.probeCalls())
        assertEquals(3, results.count { (it as ProbeResult.Verified).variants != null })
        assertEquals(2, results.count { (it as ProbeResult.Verified).variants == null })
    }

    @Test fun staleEpochRequestsAreDroppedAtSubmitAndAtProcessing() = runTest {
        val fetcher = FakeFetcher { _, headers ->
            text(status = 200, headers = mapOf("Content-Type" to "video/mp4"), body = "x")
        }
        val results = mutableListOf<Pair<String, Long>>()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        queue.onResult = { url, epoch, _ -> results += url to epoch }
        // Queue work before the worker exists, then navigate: the queued epoch is stale.
        queue.submit("https://cdn.example/old.mp4", MediaKind.FILE, 1L, ctx)
        queue.submit("https://cdn.example/new.mp4", MediaKind.FILE, 2L, ctx)
        queue.submit("https://cdn.example/older.mp4", MediaKind.FILE, 1L, ctx)
        queue.start()
        runCurrent()
        assertEquals(listOf("https://cdn.example/new.mp4" to 2L), results)
    }

    @Test fun perCandidateCooldownBlocksResubmissionWithinFifteenSeconds() = runTest {
        val fetcher = FakeFetcher { _, headers ->
            text(status = 200, headers = mapOf("Content-Type" to "video/mp4"), body = "x")
        }
        var nowMs = 0L
        val results = mutableListOf<ProbeResult>()
        val queue = AutoProbeQueue(backgroundScope, fetcher, now = { nowMs })
        queue.onResult = { _, _, result -> results += result }
        queue.start()
        queue.submit("https://cdn.example/once.mp4", MediaKind.FILE, 1L, ctx)
        runCurrent()
        nowMs = 1_000
        queue.submit("https://cdn.example/once.mp4", MediaKind.FILE, 1L, ctx)
        runCurrent()
        assertEquals(1, results.size)
        nowMs = 15_000
        queue.submit("https://cdn.example/once.mp4", MediaKind.FILE, 1L, ctx)
        runCurrent()
        assertEquals(2, results.size)
        assertEquals(2, fetcher.calls.size)
    }
}

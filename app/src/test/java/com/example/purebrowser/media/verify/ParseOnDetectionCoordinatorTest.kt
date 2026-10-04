package com.example.purebrowser.media.verify

import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.ProbeState
import com.example.purebrowser.media.ResourceSniffer
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ParseOnDetectionCoordinatorTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
        gear-720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L93.B0,mp4a.40.2"
        gear-1080.m3u8
    """.trimIndent()

    private class FakeFetcher(private val handle: (String, Map<String, String>) -> FetchedResponse) : UrlFetcher {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun open(url: String, headers: Map<String, String>): FetchedResponse {
            calls += url to headers
            return handle(url, headers)
        }
        fun probeCalls() = calls.count { "Range" in it.second }
        fun playlistCalls() = calls.count { "Range" !in it.second }
    }

    private fun text(status: Int, headers: Map<String, String>, body: String = "x") =
        FetchedResponse(status, headers, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))

    private fun fixture() = FakeFetcher { _, headers ->
        if ("Range" in headers) text(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"))
        else text(200, mapOf("Content-Type" to "application/vnd.apple.mpegurl"), master)
    }

    private val ctx = SessionContext("https://cdn.example/watch", "UnitUA/1.0") { null }

    @Test fun hlsObservationsDebounceIntoOneBatchCappedAtSixMasters() = runTest {
        val fetcher = fixture()
        val sniffer = ResourceSniffer()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        val coordinator = ParseOnDetectionCoordinator(sniffer, queue, backgroundScope, { ctx })
        coordinator.attach()
        coordinator.start()
        val page = sniffer.beginPage()
        repeat(5) { sniffer.observe(page, "https://cdn.example/hls/v$it.m3u8", Evidence.REQUEST) }
        runCurrent()
        assertEquals(0, fetcher.calls.size)
        advanceTimeBy(149)
        runCurrent()
        assertEquals(0, fetcher.calls.size)
        assertTrue(sniffer.candidates.value.all { it.probeState == ProbeState.PENDING })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(5, fetcher.playlistCalls())
        assertEquals(5, fetcher.probeCalls())
        val candidates = sniffer.candidates.value
        assertEquals(5, candidates.size)
        assertEquals(5, candidates.count { it.probeState == ProbeState.VERIFIED })
        assertEquals(0, candidates.count { it.probeState == ProbeState.PENDING })
        candidates.filter { it.variants != null }.forEach { assertEquals(2, it.variants!!.size) }
        // Each fetched master carries exactly one warned (non-H.264) variant.
        assertEquals(5, candidates.flatMap { it.variants.orEmpty() }.count { it.warning != null })
    }

    @Test fun fileCandidatesProbeImmediatelyWithoutDebounce() = runTest {
        val fetcher = FakeFetcher { _, headers ->
            text(206, mapOf("Content-Type" to "video/mp4", "Content-Range" to "bytes 0-0/1234567"))
        }
        val sniffer = ResourceSniffer()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        val coordinator = ParseOnDetectionCoordinator(sniffer, queue, backgroundScope, { ctx })
        coordinator.attach()
        coordinator.start()
        val page = sniffer.beginPage()
        sniffer.observe(page, "https://cdn.example/clip.mp4", Evidence.REQUEST)
        runCurrent()
        assertEquals(1, fetcher.probeCalls())
        assertEquals(0, fetcher.playlistCalls())
        val candidate = sniffer.candidates.value.single()
        assertEquals(ProbeState.VERIFIED, candidate.probeState)
        assertEquals(1234567L, candidate.totalBytes)
        assertTrue(candidate.resumable!!)
        assertEquals("video/mp4", candidate.verifiedMime)
    }

    @Test fun missingSessionContextSkipsProbingButKeepsCandidatesListed() = runTest {
        val fetcher = fixture()
        val sniffer = ResourceSniffer()
        val queue = AutoProbeQueue(backgroundScope, fetcher)
        val coordinator = ParseOnDetectionCoordinator(sniffer, queue, backgroundScope, { null })
        coordinator.attach()
        coordinator.start()
        val page = sniffer.beginPage()
        sniffer.observe(page, "https://cdn.example/hls/solo.m3u8", Evidence.REQUEST)
        advanceTimeBy(ProbePolicy.DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(0, fetcher.calls.size)
        assertEquals(ProbeState.PENDING, sniffer.candidates.value.single().probeState)
    }
}

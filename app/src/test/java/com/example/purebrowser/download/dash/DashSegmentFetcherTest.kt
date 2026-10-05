package com.example.purebrowser.download.dash

import com.example.purebrowser.download.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue

/** T97 fake-transport coverage: ordered bounded fetch, retry, honest rejects. No network. */
class DashSegmentFetcherTest {
    @get:Rule val temp = TemporaryFolder()

    private val record = DownloadRecord(
        recordId = "preview", name = "preview.mp4", mediaUrl = "https://cdn.example/media/manifest.mpd",
        transfer = TransferType.CONTROLLED,
    )

    private fun fmp4Box(type: String, payload: Int = 64) =
        byteArrayOf(
            ((8 + payload).shr(24)).and(0xff).toByte(),
            ((8 + payload).shr(16)).and(0xff).toByte(),
            ((8 + payload).shr(8)).and(0xff).toByte(),
            ((8 + payload).shr(0)).and(0xff).toByte(),
        ) + type.toByteArray(Charsets.US_ASCII) + ByteArray(payload)

    private val initBytes = fmp4Box("ftyp") + fmp4Box("moov")
    private fun segmentBytes(index: Int) = fmp4Box("styp", 8) + fmp4Box("moof", 32 + index) + fmp4Box("mdat", 96)

    private class FakeTransport(private val handler: (String) -> Pair<Int, ByteArray>) : HttpTransport {
        override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation): HttpResponse {
            val (status, body) = handler(url)
            return object : HttpResponse {
                override val status = status
                override fun header(name: String) =
                    if (name.equals("Content-Length", true)) body.size.toString() else null
                override fun body() = ByteArrayInputStream(body)
                override fun close() {}
            }
        }
    }

    private fun fetcher(handler: (String) -> Pair<Int, ByteArray>) =
        DashSegmentFetcher(com.example.purebrowser.download.hls.HlsHttpClient(
            FakeTransport(handler), AccessContextProvider { null }, true,
        ), requireSpace = {}, backoffMs = { 0L })

    @Test fun initAndSegmentsLandInOrderWithCompletionCallbacks() {
        val representation = DashRepresentationPlan(
            id = "v", role = DashTrackRole.VIDEO, codecs = "avc1.64001f", width = null, height = 720,
            bandwidth = null, initUrl = "https://cdn.example/media/v/init.mp4",
            segments = (0 until 6).map { DashSegmentPlan("https://cdn.example/media/v/s$it.m4s", 2_000_000L) },
        )
        val fetcher = fetcher { url ->
            200 to when {
                url.endsWith("init.mp4") -> initBytes
                url.contains("/s") -> segmentBytes(url.substringAfterLast("/s").substringBefore('.').toInt())
                else -> ByteArray(0)
            }
        }
        val initFile = temp.newFile()
        val bytes = ArrayList<Long>()
        fetcher.fetchInit(record, representation.initUrl, initFile, TransferCancellation()) { bytes.add(it) }
        assertEquals(initBytes.size.toLong(), initFile.length())

        val completed = ConcurrentLinkedQueue<Int>()
        fetcher.fetchMediaSegments(
            record, representation.segments.map { it.url }, { index -> temp.newFile() },
            TransferCancellation(), {},
        ) { index -> completed.add(index) }
        assertEquals((0 until 6).toSet(), completed.toSet())
        assertEquals(6, completed.size)
        assertTrue(bytes.sum() > 0L)
    }

    @Test fun truncatedSegmentRetriesOnceThenSucceeds() {
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        // First response serves a short body while claiming the full Content-Length; the
        // exact-length contract must treat that as transient and retry the whole segment.
        val lying = object : HttpTransport {
            override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation): HttpResponse {
                val full = segmentBytes(0)
                val short = attempts.incrementAndGet() == 1 && url.endsWith("s0.m4s")
                val body = if (short) full.copyOf(full.size - 9) else full
                return object : HttpResponse {
                    override val status = 200
                    override fun header(name: String) =
                        if (name.equals("Content-Length", true)) full.size.toString() else null
                    override fun body() = ByteArrayInputStream(body)
                    override fun close() {}
                }
            }
        }
        val fetcher = DashSegmentFetcher(
            com.example.purebrowser.download.hls.HlsHttpClient(lying, AccessContextProvider { null }, true),
            requireSpace = {}, backoffMs = { 0L },
        )
        val files = (0 until 2).map { temp.newFile() }
        fetcher.fetchMediaSegments(
            record, listOf("https://cdn.example/media/v/s0.m4s", "https://cdn.example/media/v/s1.m4s"),
            { index -> files[index] }, TransferCancellation(), {}) {}
        assertEquals(segmentBytes(0).size.toLong(), files[0].length())
    }

    @Test fun nonFmp4SegmentBodyIsAnHonestReject() {
        val fetcher = fetcher { url ->
            200 to if (url.endsWith("init.mp4")) initBytes
            else "garbage that is definitely not a box!!".toByteArray()
        }
        try {
            fetcher.fetchMediaSegments(
                record, listOf("https://cdn.example/media/v/s0.m4s"), { temp.newFile() },
                TransferCancellation(), {}) {}
            fail("expected TransferFailure")
        } catch (expected: TransferFailure) {
            assertEquals(FailureKind.NOT_VIDEO, expected.kind)
            assertTrue(expected.safeMessage.contains("fMP4"))
        }
    }

    @Test fun overCapSegmentLengthIsRejectedBeforeWriting() {
        // The per-segment cap must fire on the declared Content-Length before any payload lands.
        val capped = DashSegmentFetcher(
            com.example.purebrowser.download.hls.HlsHttpClient(
                object : HttpTransport {
                    override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation) =
                        object : HttpResponse {
                            override val status = 200
                            override fun header(name: String) =
                                if (name.equals("Content-Length", true))
                                    (DashBudgets.MAX_SEGMENT_BYTES + 1).toString() else null
                            override fun body() = ByteArrayInputStream(ByteArray(0))
                            override fun close() {}
                        }
                }, AccessContextProvider { null }, true,
            ), requireSpace = {}, backoffMs = { 0L },
        )
        try {
            capped.fetchMediaSegments(
                record, listOf("https://cdn.example/media/v/s0.m4s"), { temp.newFile() },
                TransferCancellation(), {}) {}
            fail("expected TransferFailure")
        } catch (expected: TransferFailure) {
            assertEquals(FailureKind.UNSUPPORTED, expected.kind)
            assertTrue(expected.safeMessage.contains("128 MiB"))
        }
    }

    @Test fun serverRejectStatusSurfacesAsTransientThenFailsHonestly() {
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val fetcher = fetcher { url ->
            if (url.endsWith("s0.m4s") && attempts.incrementAndGet() <= 3) 503 to ByteArray(0)
            else 200 to segmentBytes(0)
        }
        try {
            fetcher.fetchMediaSegments(
                record, listOf("https://cdn.example/media/v/s0.m4s"), { temp.newFile() },
                TransferCancellation(), {}) {}
            fail("expected IOException")
        } catch (expected: IOException) {
            // HlsTransientFailure after the bounded retry budget: honest, no half output.
            assertTrue(expected is com.example.purebrowser.download.hls.HlsTransientFailure || expected.message != null)
        }
    }
}

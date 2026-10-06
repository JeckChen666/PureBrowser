package com.example.purebrowser.download.hls

import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadRecord
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.HttpResponse
import com.example.purebrowser.download.HttpTransport
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.TransferFailure
import com.example.purebrowser.download.TransferType
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections

/**
 * T107 fake-transport coverage for the EXT-X-MAP BYTERANGE init request shape: one exact CLOSED
 * `Range: bytes=start-end` interval (RFC 8216 §4.3.2.5) is emitted on every hop, a 206 answer is
 * accepted only for a range request, and an ordinary request never carries a Range header.
 */
class HlsInitByteRangeRequestTest {
    private val initBytes = ByteArray(712) { (it % 251).toByte() }

    private class Captured(val url: String, val headers: Map<String, String>)

    private class CapturingTransport(
        private val requests: MutableList<Captured>,
        private val respond: (Captured) -> HttpResponse,
    ) : HttpTransport {
        override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation): HttpResponse {
            val captured = Captured(url, java.util.Collections.unmodifiableMap(LinkedHashMap(headers)))
            synchronized(requests) { requests.add(captured) }
            return respond(captured)
        }
    }

    private fun response(status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()): HttpResponse =
        object : HttpResponse {
            override val status = status
            override fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
            override fun body(): InputStream = ByteArrayInputStream(body)
            override fun close() {}
        }

    private fun client(respond: (Captured) -> HttpResponse): HlsHttpClient {
        val requests = Collections.synchronizedList(mutableListOf<Captured>())
        return HlsHttpClient(CapturingTransport(requests, respond), AccessContextProvider { null }, false)
    }

    private val record = DownloadRecord(
        recordId = "preview", name = "preview.mp4", mediaUrl = "https://cdn.example/media/init.mp4",
        transfer = TransferType.CONTROLLED,
    )

    @Test fun byterangeInitIsRequestedAsOneExactClosedInterval() {
        // The init window lives inside a larger container, as a BYTERANGE EXT-X-MAP declares it.
        val container = ByteArray(1024).also { initBytes.copyInto(it, 96) }
        val requests = Collections.synchronizedList(mutableListOf<Captured>())
        val transport = CapturingTransport(requests) { captured ->
            val range = captured.headers["Range"] ?: error("expected a Range header")
            val (first, last) = range.removePrefix("bytes=").split('-').map(String::toLong)
            response(206, container.copyOfRange(first.toInt(), last.toInt() + 1), mapOf(
                "Content-Length" to (last - first + 1L).toString(),
                "Content-Range" to "bytes $first-$last/${container.size}",
            ))
        }
        val served = HlsHttpClient(transport, AccessContextProvider { null }, false)
            .get(record, "https://cdn.example/media/init.mp4", TransferCancellation(), 96L..807L) { response, _ ->
                response.body().readBytes().size
            }
        assertEquals(initBytes.size, served)
        val headers = requests.single().headers
        assertEquals("bytes=96-807", headers["Range"])
        // The range rides the same policy-created header set; nothing else is invented.
        assertEquals("identity", headers["Accept-Encoding"])
        assertNull(headers["Cookie"])
    }

    @Test fun rangeIsResentAcrossRedirectHops() {
        val requests = Collections.synchronizedList(mutableListOf<Captured>())
        val transport = CapturingTransport(requests) { captured ->
            if (captured.url.endsWith("init.mp4"))
                response(302, ByteArray(0), mapOf("Location" to "https://cdn.example/media/init-2.mp4"))
            else response(206, initBytes, mapOf(
                "Content-Length" to initBytes.size.toString(),
                "Content-Range" to "bytes 0-${initBytes.size - 1}/${initBytes.size}",
            ))
        }
        HlsHttpClient(transport, AccessContextProvider { null }, false)
            .get(record, "https://cdn.example/media/init.mp4", TransferCancellation(), 0..initBytes.size - 1L) { _, _ -> Unit }
        assertEquals(2, requests.size)
        assertEquals("bytes=0-${initBytes.size - 1}", requests[0].headers["Range"])
        assertEquals("bytes=0-${initBytes.size - 1}", requests[1].headers["Range"])
    }

    @Test fun partialAnswerWithoutARangeRequestIsHonestlyRejected() {
        try {
            client { response(206, initBytes, mapOf("Content-Range" to "bytes 0-711/712")) }
                .get(record, "https://cdn.example/media/init.mp4", TransferCancellation()) { _, _ -> Unit }
            fail("expected an honest rejection")
        } catch (expected: TransferFailure) {
            assertEquals(FailureKind.HTTP_REJECTED, expected.kind)
        }
    }

    @Test fun ordinaryRequestsCarryNoRangeHeader() {
        val requests = Collections.synchronizedList(mutableListOf<Captured>())
        val transport = CapturingTransport(requests) { response(200, initBytes) }
        HlsHttpClient(transport, AccessContextProvider { null }, false)
            .get(record, "https://cdn.example/media/init.mp4", TransferCancellation()) { _, _ -> Unit }
        assertNull(requests.single().headers["Range"])
    }
}

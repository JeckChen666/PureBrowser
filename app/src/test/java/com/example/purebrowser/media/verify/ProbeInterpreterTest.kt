package com.example.purebrowser.media.verify

import com.example.purebrowser.media.MediaKind
import org.junit.Assert.*
import org.junit.Test

class ProbeInterpreterTest {
    private fun interpret(status: Int, vararg headers: Pair<String, String>) =
        ProbeInterpreter.interpret(status) { name -> headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second }

    @Test fun range206WithContentRangeTotalIsVerifiedAndResumable() {
        val result = interpret(206, "Content-Range" to "bytes 0-0/1234567", "Content-Type" to "video/mp4")
        val verified = result as ProbeResult.Verified
        assertEquals(1234567L, verified.totalBytes)
        assertTrue(verified.resumable)
        assertEquals("video/mp4", verified.mime)
        assertEquals(MediaKind.FILE, verified.kindHint)
    }

    @Test fun range206WithoutParsableTotalStillProvesRangeSupport() {
        listOf("bytes 0-0/*", "not-a-range", null).forEach { raw ->
            val headers = if (raw == null) emptyArray<Pair<String, String>>() else arrayOf("Content-Range" to raw)
            val verified = interpret(206, "Content-Type" to "video/mp4", *headers) as ProbeResult.Verified
            assertNull(verified.totalBytes)
            assertTrue(verified.resumable)
        }
        assertEquals(1234567L, ProbeInterpreter.contentRangeTotal("bytes 0-0/1234567"))
        assertNull(ProbeInterpreter.contentRangeTotal("bytes 0-0/*"))
        assertNull(ProbeInterpreter.contentRangeTotal("bytes 0-0/0"))
    }

    @Test fun plain200HasNoVerifiedTotalAndResumabilityFollowsAcceptRanges() {
        val none = interpret(200, "Content-Type" to "video/mp4", "Content-Length" to "999", "Accept-Ranges" to "none") as ProbeResult.Verified
        assertNull(none.totalBytes)
        assertFalse(none.resumable)
        val accepted = interpret(200, "Content-Type" to "video/mp4", "Accept-Ranges" to "bytes") as ProbeResult.Verified
        assertNull(accepted.totalBytes)
        assertTrue(accepted.resumable)
    }

    @Test fun nonSuccessStatusesAreUnreachable() {
        listOf(301, 403, 404, 429, 500, 503).forEach { assertEquals(ProbeResult.Unreachable, interpret(it, "Content-Type" to "video/mp4")) }
    }

    @Test fun mimeParamsAreStrippedAndKindHintsFollowMediaType() {
        val hls = interpret(200, "Content-Type" to "application/vnd.apple.mpegurl; charset=UTF-8") as ProbeResult.Verified
        assertEquals("application/vnd.apple.mpegurl", hls.mime)
        assertEquals(MediaKind.HLS, hls.kindHint)
        assertEquals(MediaKind.HLS, (interpret(200, "Content-Type" to "audio/x-mpegurl") as ProbeResult.Verified).kindHint)
        assertEquals(MediaKind.DASH, (interpret(200, "Content-Type" to "application/dash+xml") as ProbeResult.Verified).kindHint)
        assertEquals(MediaKind.FILE, (interpret(200, "Content-Type" to "VIDEO/WEBM") as ProbeResult.Verified).kindHint)
    }

    @Test fun nonMediaMimesAreNotMediaEvenWithRangeResponses() {
        listOf("text/html", "audio/mpeg", "application/octet-stream", "image/png").forEach {
            assertEquals(ProbeResult.NotMedia, interpret(206, "Content-Type" to it, "Content-Range" to "bytes 0-0/10"))
        }
        assertEquals(ProbeResult.NotMedia, interpret(200))
    }
}

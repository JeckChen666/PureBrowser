package com.example.purebrowser.download

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.site.DualTrackDownloadPlan
import com.example.purebrowser.media.site.YouTubeResolver
import com.example.purebrowser.media.site.YouTubeTrack
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.example.purebrowser.download.V015YouTubeTransferDiagnosticSupport as Diagnostic

/** Explicit P1B opt-in; never included in v015RealSites/product/full-transfer runs by accident.
 * Run ONLY #sintelRangeSemanticsBoundedDiagnostic with -e v015YouTubeTransferDiagnostic true.
 * It resolves once (unchanged IOS worker), probes ONLY the selected video, reads <=192 bytes in
 * total, and emits a scalars-only diagnosticJson via instrumentation status, not a disk report.
 * Audio is described but NOT requested. No enqueue, download, mux, publish or success substitute.
 * A 403, partial full-response contract, bad prefix or skipped comparison fails after reporting.
 * The fixture methods below use injected in-memory HttpTransport only, without real-sites opt-in.
 */
@RunWith(AndroidJUnit4::class)
class V015YouTubeTransferDiagnosticTest {
    @Test(timeout = 100_000)
    fun sintelRangeSemanticsBoundedDiagnostic() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v015YouTubeTransferDiagnostic") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val report = JSONObject().put("videoId", "eRsGyueVLvQ").put("dependency", "youtubei.js@18.1.0")
            .put("scope", "BOUNDED_REQUEST_DIAGNOSTIC_NOT_PRODUCT_ACCEPTANCE")
            .put("downloadSucceeded", false).put("completeTransferAttempted", false)
            .put("maxVariants", Diagnostic.MAX_VARIANTS).put("maxHttpRequests", Diagnostic.MAX_HTTP_REQUESTS)
            .put("maxRedirectsPerVariant", Diagnostic.MAX_REDIRECTS)
            .put("bodyReadLimitPerVariant", Diagnostic.PREFIX_BYTES)
            .put("maxObservedMediaBodyBytes", Diagnostic.MAX_VARIANTS * Diagnostic.PREFIX_BYTES)
            .put("perVariantDeadlineMs", Diagnostic.REQUEST_DEADLINE_MS)
            .put("contrasts", JSONArray().put("FULL_HEADER vs PREFIX_HEADER: range end only")
                .put("PREFIX_HEADER vs PREFIX_QUERY: range carrier only"))
            .put("unchangedContext", "one parse; one video; anonymous; fixed UA; identity encoding; no cpn/token edits")
            .put("notTested", JSONArray().put("audio HTTP").put("complete transfer").put("mux/play/share")
                .put("cpn addition").put("STREAM_HEADERS differences").put("client/access-condition changes"))
        val started = SystemClock.elapsedRealtime()
        var accepted = false
        val scheduler = scheduler()
        try {
            // Isolated test repository, same privacy-lease wrapper used by the UI; never enqueue/store
            // a transient plan or start DownloadRuntime / background transfers on the shared device.
            DualTrackTestSupport.test { repository ->
                report.put("layer", "RESOLVER_METADATA")
                val media = runBlocking { YouTubeResolver(context, repository::guardedTransport).resolve("eRsGyueVLvQ") }
                val resolvedAt = SystemClock.elapsedRealtime()
                report.put("resolveElapsedMs", resolvedAt - started)
                check(media.videoId == "eRsGyueVLvQ")
                val video = media.videos.minBy { it.height }
                val audio = media.audios.first()
                // Construct/validate the same complete-resource plan as the product audit, in memory.
                val plan = DualTrackDownloadPlan("youtube:${media.videoId}", video.id, audio.id,
                    video.url, audio.url, "avc1", "mp4a.40.2", video.length, audio.length,
                    minOf(video.durationMs, audio.durationMs) * 1000)
                plan.validate(false)
                report.put("video", Diagnostic.trackJson(video, true, System.currentTimeMillis() / 1000))
                    .put("audio", Diagnostic.trackJson(audio, false, System.currentTimeMillis() / 1000))
                    .put("plannedDurationUs", plan.durationUs)
                var opens = 0
                val raw = UrlConnectionTransport()
                val counted = HttpTransport { url, headers, token ->
                    check(++opens <= Diagnostic.MAX_HTTP_REQUESTS)
                    raw.open(url, headers, token)
                }
                report.put("layer", "MEDIA_REQUEST")
                val round = Diagnostic.round(video, request(), repository.guardedTransport(counted),
                    resolvedAt, SystemClock::elapsedRealtime, scheduler)
                report.put("round", round.json(video.length)).put("httpRequests", opens)
                    .put("totalObservedMediaBodyBytes", round.observations.sumOf { it.observedBytes })
                    .put("layer", "BOUNDED_OBSERVATION_ONLY")
                accepted = round.accepted
            }
        } catch (failure: Exception) {
            // Don't propagate exception messages, causes or raw response/URI objects into the runner.
            report.put("diagnosticFailure", "SANITIZED_EXCEPTION")
            if (failure is TransferFailure) report.put("failureKind", failure.kind.name)
        } finally {
            scheduler.shutdownNow()
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("boundedObservationsAccepted", accepted)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("diagnosticJson", report.toString())
            })
        }
        assertTrue("P1B bounded observation failed/incomplete; see diagnosticJson. Prefixes are NOT download success.", accepted)
    }

    @Test fun fixtureSingleVariableComparisonsUseSameUrlAndHeadersAndReadOnly64Bytes() = withScheduler { scheduler ->
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val bodies = mutableListOf<CountingBody>()
        val transport = HttpTransport { url, headers, _ ->
            calls += url to headers.toMap()
            val full = calls.size == 1
            val body = CountingBody()
            bodies += body
            response(206, mapOf("Content-Length" to if (full) "4096" else "64",
                "Content-Range" to if (full) "bytes 0-4095/4096" else "bytes 0-63/4096"), body)
        }
        val round = Diagnostic.round(track(), request(), transport, 0, { 10L }, scheduler)
        assertTrue(round.accepted)
        assertEquals(3, calls.size)
        assertTrue("A changes only Range end", calls[0].first == calls[1].first)
        assertEquals("bytes=0-4095", calls[0].second["Range"])
        assertEquals("bytes=0-63", calls[1].second["Range"])
        assertTrue(calls[0].second.filterKeys { it != "Range" } == calls[1].second.filterKeys { it != "Range" })
        assertTrue("B appends only the fixed range parameter", calls[2].first == calls[1].first + "&range=0-63")
        assertTrue(calls[2].second == calls[1].second.filterKeys { it != "Range" })
        assertTrue(calls.all { it.second.keys.none { key -> key.equals("Cookie", true) || key.equals("Authorization", true) } })
        assertEquals(192, bodies.sumOf { it.delivered })
        assertTrue(bodies.all { it.delivered == 64 && it.closed })
        assertEquals(10L, round.observations.first().parseToRequestMs)
        val json = round.json(4096)
        assertFalse(json.getBoolean("downloadSucceeded"))
        assertFalse(json.getBoolean("completeTransferAttempted"))
    }

    @Test fun fixtureRepeated403StopsAfterTwoRequestsWithoutReadingErrorBodies() = withScheduler { scheduler ->
        var opens = 0
        val round = Diagnostic.round(track(), request(), HttpTransport { _, _, _ ->
            opens++
            response(403, mapOf("Location" to "https://signed-secret.invalid/?token=secret"), forbiddenBody())
        }, 0, { 0 }, scheduler)
        assertEquals(2, opens)
        assertEquals(2, round.observations.size)
        assertFalse(round.accepted)
        assertEquals("REPEATED_ACCESS_DENIAL_NO_NEW_BYTE_EVIDENCE", round.stopReason)
        assertTrue(round.observations.all { it.layer == Diagnostic.Layer.ACCESS_CONDITION && it.observedBytes == 0 })
    }

    @Test fun fixtureFull403AndPrefixes206NeverBecomeProductOrDiagnosticSuccess() = withScheduler { scheduler ->
        var opens = 0
        val round = Diagnostic.round(track(), request(), HttpTransport { _, _, _ ->
            if (++opens == 1) response(403, emptyMap(), forbiddenBody())
            else response(206, mapOf("Content-Length" to "64", "Content-Range" to "bytes 0-63/4096"), CountingBody())
        }, 0, { 0 }, scheduler)
        assertEquals(3, opens)
        assertFalse(round.accepted)
        assertFalse(round.observations.first().accepted)
        assertTrue(round.observations.drop(1).all { it.accepted })
        assertFalse(round.json(4096).getBoolean("downloadSucceeded"))
    }

    @Test fun fixturePartialOrContradictoryFullResponsesNeverPassOrReadBody() = withScheduler { scheduler ->
        val cases = listOf(
            206 to mapOf("Content-Length" to "64", "Content-Range" to "bytes 0-63/4096"),
            206 to mapOf("Content-Length" to "4096", "Content-Range" to "bytes 0-4095/8192"),
            200 to mapOf("Content-Length" to "64"),
            200 to mapOf("Content-Length" to "4096", "Content-Range" to "bytes 0-4095/4096"),
            200 to mapOf("Content-Length" to "4096", "Transfer-Encoding" to "chunked"),
            200 to mapOf("Content-Length" to "4096", "Content-Encoding" to "gzip"),
            200 to mapOf("Content-Length" to "url-secret"),
        )
        for ((status, headers) in cases) {
            val observation = Diagnostic.probe(track(), request(), Diagnostic.Semantics.FULL_HEADER,
                HttpTransport { _, _, _ -> response(status, headers, forbiddenBody()) }, 0, { 0 }, scheduler)
            assertFalse(observation.accepted)
            assertEquals(Diagnostic.Layer.RESPONSE_CONTRACT, observation.layer)
            assertEquals(0, observation.observedBytes)
        }
    }

    @Test fun fixtureShortOrNonMp4PrefixFailsWithoutAnExtraByteRead() = withScheduler { scheduler ->
        for (bytes in listOf(ByteArray(10), ByteArray(64))) {
            val observation = Diagnostic.probe(track(), request(), Diagnostic.Semantics.PREFIX_HEADER,
                HttpTransport { _, _, _ -> response(206,
                    mapOf("Content-Length" to "64", "Content-Range" to "bytes 0-63/4096"), ByteArrayInputStream(bytes)) },
                0, { 0 }, scheduler)
            assertFalse(observation.accepted)
            assertEquals(bytes.size, observation.observedBytes)
            assertEquals(if (bytes.size == 10) Diagnostic.Layer.BODY_READ else Diagnostic.Layer.CONTAINER_PREFIX, observation.layer)
        }
    }

    @Test fun fixtureRedirectSafetyAndBudgetRecordHopsButNeverLocation() = withScheduler { scheduler ->
        var opens = 0
        val hostile = Diagnostic.probe(track(), request(), Diagnostic.Semantics.FULL_HEADER,
            HttpTransport { _, _, _ -> opens++; response(302,
                mapOf("Location" to "http://127.0.0.1/?token=secret"), forbiddenBody()) }, 0, { 0 }, scheduler)
        assertEquals(1, opens)
        assertEquals(Diagnostic.Layer.REDIRECT_POLICY, hostile.layer)
        assertFalse(hostile.accepted)
        val loop = Diagnostic.probe(track(), request(), Diagnostic.Semantics.FULL_HEADER,
            HttpTransport { _, _, _ -> response(307,
                mapOf("Location" to "https://fixture.googlevideo.com/next?signature=secret"), forbiddenBody()) },
            0, { 0 }, scheduler)
        assertEquals(Diagnostic.MAX_REDIRECTS + 1, loop.hops.size)
        assertEquals(Diagnostic.Layer.REDIRECT, loop.layer)
        assertFalse(loop.json(4096).toString().contains("secret"))
    }

    @Test fun fixtureQueryRedirectDroppingRangeStopsInsteadOfChangingTheExperiment() = withScheduler { scheduler ->
        var opens = 0
        val observation = Diagnostic.probe(track(), request(), Diagnostic.Semantics.PREFIX_QUERY,
            HttpTransport { _, _, _ -> opens++; response(302,
                mapOf("Location" to "https://fixture.googlevideo.com/next?signature=secret"), forbiddenBody()) },
            0, { 0 }, scheduler)
        assertEquals(1, opens)
        assertEquals(Diagnostic.Layer.REDIRECT_POLICY, observation.layer)
        assertFalse(observation.accepted)
    }

    @Test fun fixtureDeadlineCancelsTransportAndRedactsExceptionMessage() = withScheduler { scheduler ->
        val released = CountDownLatch(1)
        val observation = Diagnostic.probe(track(), request(), Diagnostic.Semantics.FULL_HEADER,
            HttpTransport { _, _, token ->
                token.bind { released.countDown() }
                check(released.await(2, TimeUnit.SECONDS))
                token.check()
                throw IOException("https://fixture.googlevideo.com/?signature=secret")
            }, 0, SystemClock::elapsedRealtime, scheduler, deadlineMs = 25)
        assertEquals(Diagnostic.Layer.DEADLINE, observation.layer)
        assertFalse(observation.accepted)
        assertFalse(observation.json(4096).toString().contains("secret"))
        val error = Diagnostic.probe(track(), request(), Diagnostic.Semantics.FULL_HEADER,
            HttpTransport { _, _, _ -> throw IOException("https://fixture.googlevideo.com/?token=secret") },
            0, { 0 }, scheduler)
        assertEquals(Diagnostic.Layer.NETWORK_OPEN, error.layer)
        assertFalse(error.json(4096).toString().contains("secret"))
    }

    @Test fun fixtureReportSerializesOnlyScalarsAndNotUrlHeaderOrTokenValues() = withScheduler { scheduler ->
        val observation = Diagnostic.probe(track(), request(), Diagnostic.Semantics.FULL_HEADER,
            HttpTransport { _, _, _ -> response(403, mapOf(
                "Content-Length" to "https://example.invalid/?signature=secret",
                "Content-Range" to "token=secret", "Content-Encoding" to "secret",
                "Set-Cookie" to "secret", "Location" to track().url), forbiddenBody()) }, 0, { 0 }, scheduler)
        val json = JSONObject().put("track", Diagnostic.trackJson(track(), true, 1_000))
            .put("observation", observation.json(4096))
        val text = json.toString()
        for (forbidden in listOf("https://", "signature=", "secret", "Set-Cookie", "Location", "sensitive-n"))
            assertFalse("Diagnostic leaked a forbidden value", text.contains(forbidden))
        assertEquals(1_800_000_000L, json.getJSONObject("track").getLong("expireEpochSeconds"))
        assertEquals("INVALID", observation.hops.single().getString("contentRangeState"))
        assertFalse(json.getJSONObject("track").getBoolean("cpnPresent"))
    }

    @Test fun fixtureExistingQueryRangeOrAccessContextIsRejectedBeforeOpeningTransport() = withScheduler { scheduler ->
        var opens = 0
        val transport = HttpTransport { _, _, _ -> opens++; error("Must not open transport") }
        val context = Diagnostic.probe(track(), request().copy(useAccessContext = true), Diagnostic.Semantics.FULL_HEADER,
            transport, 0, { 0 }, scheduler)
        val range = Diagnostic.probe(track().copy(url = track().url + "&range=0-63"), request(), Diagnostic.Semantics.PREFIX_QUERY,
            transport, 0, { 0 }, scheduler)
        assertEquals(0, opens)
        assertEquals(Diagnostic.Layer.REQUEST_POLICY, context.layer)
        assertEquals(Diagnostic.Layer.REQUEST_POLICY, range.layer)
    }

    private fun request() = DownloadRecord(name = "Sintel bounded diagnostic",
        userAgent = "PureBrowser authorized Sintel audit", transfer = TransferType.CONTROLLED,
        protocol = DownloadProtocol.DUAL_TRACK, useAccessContext = false)

    private fun track() = YouTubeTrack("134", "https://fixture.googlevideo.com/videoplayback?signature=secret&n=sensitive-n&expire=1800000000",
        4096, 888000, 360, "video/mp4; codecs=\"avc1.4d401e\"")
    private fun scheduler() = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "v015-youtube-diagnostic-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private fun withScheduler(block: (ScheduledThreadPoolExecutor) -> Unit) {
        val scheduler = scheduler()
        try { block(scheduler) } finally { scheduler.shutdownNow() }
    }
    private fun forbiddenBody(): InputStream = object : InputStream() {
        override fun read(): Int = error("Response body must not be read")
    }
    private fun response(code: Int, headers: Map<String, String>, input: InputStream) = object : HttpResponse {
        override val status = code
        override fun header(name: String) = headers[name]
        override fun body() = input
        override fun close() { input.close() }
    }
    private class CountingBody : InputStream() {
        var delivered = 0
        var closed = false
        private val bytes = ByteArray(4096).apply { "ftyp".toByteArray(Charsets.US_ASCII).copyInto(this, 4) }
        override fun read(): Int = error("Must use explicitly bounded bulk reads")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            check(delivered + length <= Diagnostic.PREFIX_BYTES) { "Exceeded the observation budget" }
            bytes.copyInto(buffer, offset, delivered, delivered + length)
            delivered += length
            return length
        }
        override fun close() { closed = true }
    }
}

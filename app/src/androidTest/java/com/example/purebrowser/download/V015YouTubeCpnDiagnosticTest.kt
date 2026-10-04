package com.example.purebrowser.download

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.site.YouTubeCpnDiagnosticSession
import com.example.purebrowser.media.site.YouTubeMedia
import com.example.purebrowser.media.site.YouTubeResolver
import com.example.purebrowser.media.site.YouTubeTrack
import kotlinx.coroutines.runBlocking
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
import com.example.purebrowser.download.V015YouTubeCpnDiagnosticSupport as Cpn

/** ONLY #sintelSameSessionCpnBoundedDiagnostic may use the network, with BOTH instrumentation
 * arguments v015YouTubeCpnDiagnostic=true and testOnlyCpnOptIn=true. All fixture* methods use
 * only authored, in-memory sessions/responses/streams; no parser network, disk, server or device IO.
 * Never run the old P1 diagnostic or a full/product suite as a substitute for this named method. */
@RunWith(AndroidJUnit4::class)
class V015YouTubeCpnDiagnosticTest {
    @Test fun sintelSameSessionCpnBoundedDiagnostic() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Independent cpn diagnostic opt-in required",
            args.getString("v015YouTubeCpnDiagnostic") == "true" && args.getString("testOnlyCpnOptIn") == "true")
        val report = JSONObject().put("scope", "TEST_ONLY_SAME_SESSION_CPN_SINGLE_VARIABLE")
            .put("maxMediaHttpOpensIncludingRedirects", Cpn.MAX_HTTP_REQUESTS)
            .put("maxRedirectsPerVariant", Cpn.MAX_REDIRECTS).put("maxBytesPerRequest", Cpn.MAX_BYTES_PER_REQUEST)
            .put("deadlineMsPerVariant", Cpn.DEADLINE_MS).put("rangeCarrier", "FULL_RANGE_HEADER")
            .put("anonymous", true).put("downloadSucceeded", false).put("completeTransferAttempted", false)
        val scheduler = scheduler()
        var bothObserved = false
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            // Existing test repository only supplies the privacy lease; no plan/record/enqueue/store.
            DualTrackTestSupport.test { repository ->
                val started = SystemClock.elapsedRealtime()
                val session = runBlocking {
                    YouTubeResolver(context, repository::guardedTransport)
                        .resolveForCpnDiagnostic("eRsGyueVLvQ", testOnlyCpnOptIn = true)
                }
                val resolvedAt = SystemClock.elapsedRealtime()
                val resolvedEpoch = System.currentTimeMillis() / 1000
                report.put("resolveElapsedMs", resolvedAt - started)
                check(session.media.videoId == "eRsGyueVLvQ") { "Diagnostic identity mismatch" }
                val track = session.media.videos.minBy { it.height }
                report.put("track", Cpn.trackJson(track, resolvedEpoch))
                val round = Cpn.round(session, track, repository.guardedTransport(Cpn.anonymousTransport()),
                    resolvedAt, SystemClock::elapsedRealtime, scheduler, testOnlyCpnOptIn = true)
                report.put("round", round.json())
                bothObserved = round.bothObserved
            }
        } catch (_: Exception) {
            // Do not leak throwable/URL/cpn/response body through the runner or logcat.
            report.put("reason", Cpn.Reason.RESOLUTION_OR_DIAGNOSTIC_FAILURE.name)
        } finally {
            scheduler.shutdownNow()
            report.put("both64ByteObservations", bothObserved)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("cpnDiagnosticJson", report.toString())
            })
        }
        assertTrue("Bounded comparison incomplete or refused; no full attempt and no download success", bothObserved)
    }

    @Test fun fixtureDefaultOptInMakesZeroRequestsAndDoesNotConsumeSession() = withScheduler { scheduler ->
        val track = track(); val session = session(track); var opens = 0
        val transport = HttpTransport { _, _, _ -> opens++; success() }
        val disabled = Cpn.round(session, track, transport, 0, { 0 }, scheduler)
        assertEquals(Cpn.Reason.OPT_IN_REQUIRED, disabled.reason); assertEquals(0, opens)
        assertTrue(run(session, track, transport, scheduler).bothObserved)
    }

    @Test fun fixtureSameTrackAndOnlyAppendedSameSessionCpnWithIdenticalFullHeaders() = withScheduler { scheduler ->
        val track = track(); val urls = mutableListOf<String>(); val headers = mutableListOf<Map<String, String>>()
        val bodies = mutableListOf<CountingBody>()
        val round = run(session(track), track, HttpTransport { url, h, _ ->
            urls += url; headers += h.toMap(); val body = CountingBody(); bodies += body; success(body)
        }, scheduler)
        assertEquals(listOf(track.url, track.url + "&cpn=$FIXTURE_CPN"), urls)
        assertEquals(headers[0], headers[1])
        assertEquals(mapOf("User-Agent" to Cpn.USER_AGENT, "Accept-Encoding" to "identity", "Range" to "bytes=0-4095"), headers[0])
        assertTrue(round.bothObserved); assertEquals(2, round.httpRequests)
        assertTrue(bodies.all { it.delivered == 64 && it.closed })
        assertEquals(128, round.json().getInt("totalObservedMediaBodyBytes"))
        assertFalse(round.json().getBoolean("completeResponseVerified")); assertFalse(round.json().getBoolean("downloadSucceeded"))
    }

    @Test fun fixtureStrictCpnAlphabetLengthAndSanitizedValidation() {
        for (bad in listOf("", "A".repeat(15), "A".repeat(17), "A".repeat(15) + "=", "A".repeat(15) + "+", "A".repeat(15) + "/", "A".repeat(15) + "\n", "A".repeat(15) + "é", "A".repeat(16) + "\n", "A".repeat(16) + "\r\n")) {
            try { session(track(), bad); fail("Invalid fixture nonce accepted") }
            catch (e: IllegalArgumentException) { assertEquals("Invalid diagnostic session", e.message) }
        }
        assertFalse(session(track()).toString().contains(FIXTURE_CPN))
    }

    @Test fun fixtureForeignOrCopiedTrackRejectedBeforeTransport() = withScheduler { scheduler ->
        val track = track(); val transport = neverOpen()
        assertEquals(0, run(session(track), track.copy(), transport, scheduler).httpRequests)
        assertEquals(Cpn.Reason.REQUEST_POLICY, run(session(track), track("135"), transport, scheduler).reason)
    }

    @Test fun fixtureSessionCannotBeReusedToRefreshRequestBudget() = withScheduler { scheduler ->
        val track = track(); val session = session(track)
        assertTrue(run(session, track, HttpTransport { _, _, _ -> success() }, scheduler).bothObserved)
        assertEquals(Cpn.Reason.REQUEST_POLICY, run(session, track, neverOpen(), scheduler).reason)
    }

    @Test fun fixtureExistingCpnRangeCredentialsOrUnsafeUrlRejected() = withScheduler { scheduler ->
        val base = track().url
        for (url in listOf(base + "&cpn=$FIXTURE_CPN", base + "&%63pn=$FIXTURE_CPN", base + "&range=0-63",
            base + "&%72ange=0-63", base + "&pot=secret", base + "&po_token=secret", base + "&cookie=secret",
            base + "&Authorization=secret", base + "&token=secret", base + "#fragment",
            base.replace("https:", "http:"), base.replace("fixture.googlevideo.com", "fixture.googlevideo.com.evil.invalid"),
            base.replace("fixture.googlevideo.com", "fixture.googlevideo.com:444"), base.replace("https://", "https://user:pass@"))) {
            val track = track().copy(url = url)
            assertEquals(Cpn.Reason.REQUEST_POLICY, run(session(track), track, neverOpen(), scheduler).reason)
        }
    }

    @Test fun fixtureRepeatedDenialStopsAtTwoWithoutReadingErrorBody() = withScheduler { scheduler ->
        val track = track(); var opens = 0
        val round = run(session(track), track, HttpTransport { _, _, _ -> opens++; response(403, emptyMap(), forbiddenBody()) }, scheduler)
        assertEquals(2, opens); assertEquals(Cpn.Reason.REPEATED_ACCESS_DENIAL_STOP, round.reason)
        assertEquals(0, round.observations.sumOf { it.observedBytes }); assertFalse(round.bothObserved)
    }

    @Test fun fixtureOneRefusalAndOneObservationNeverBecomesCompleteSuccess() = withScheduler { scheduler ->
        val track = track(); var opens = 0
        val round = run(session(track), track, HttpTransport { _, _, _ ->
            if (++opens == 1) response(403, emptyMap(), forbiddenBody()) else success()
        }, scheduler)
        assertEquals(2, opens); assertFalse(round.bothObserved)
        assertEquals(listOf(Cpn.Reason.ACCESS_DENIED, Cpn.Reason.BODY_LIMIT_OBSERVED), round.observations.map { it.reason })
        assertFalse(round.json().getBoolean("downloadSucceeded"))
    }

    @Test fun fixtureValidFull206HeadersPermitOnly64ByteObservation() = withScheduler { scheduler ->
        val track = track()
        val round = run(session(track), track, HttpTransport { _, _, _ -> response(206,
            mapOf("Content-Length" to "4096", "Content-Range" to "bytes 0-4095/4096"), CountingBody()) }, scheduler)
        assertTrue(round.bothObserved)
    }

    @Test fun fixtureShortRangeAndBadFullHeadersStopBeforeBodyAndSecondRequest() = withScheduler { scheduler ->
        val track = track()
        val cases = listOf(206 to mapOf("Content-Length" to "64", "Content-Range" to "bytes 0-63/4096"),
            206 to mapOf("Content-Range" to "bytes 1-4095/4096"), 206 to emptyMap(),
            200 to mapOf("Content-Range" to "bytes 0-4095/4096"), 200 to mapOf("Content-Length" to "4095"),
            200 to mapOf("Content-Length" to "invalid-secret"), 200 to mapOf("Content-Encoding" to "gzip"),
            200 to mapOf("Content-Length" to "4096", "Transfer-Encoding" to "chunked"))
        for ((code, h) in cases) {
            val round = run(session(track), track, HttpTransport { _, _, _ -> response(code, h, forbiddenBody()) }, scheduler)
            assertEquals(1, round.httpRequests); assertEquals(Cpn.Reason.FULL_RESPONSE_CONTRACT_FAILED, round.observations.single().reason)
        }
    }

    @Test fun fixtureUnexpectedHttpStatusStopsWithoutBody() = withScheduler { scheduler ->
        val track = track()
        val round = run(session(track), track, HttpTransport { _, _, _ -> response(503, emptyMap(), forbiddenBody()) }, scheduler)
        assertEquals(1, round.httpRequests); assertEquals(Cpn.Reason.HTTP_STATUS, round.observations.single().reason)
    }

    @Test fun fixtureEarlyEofDoesNotRetryOrCountAsSuccessfulObservation() = withScheduler { scheduler ->
        val track = track()
        val round = run(session(track), track, HttpTransport { _, _, _ -> success(ByteArrayInputStream(ByteArray(7))) }, scheduler)
        assertEquals(1, round.httpRequests); assertEquals(7, round.observations.single().observedBytes)
        assertEquals(Cpn.Reason.EARLY_EOF, round.observations.single().reason)
    }

    @Test fun fixtureRedirectOpensCountAgainstTwoRequestTotalAndIncompleteComparison() = withScheduler { scheduler ->
        val track = track(); var opens = 0
        val round = run(session(track), track, HttpTransport { _, _, _ ->
            if (++opens == 1) response(302, mapOf("Location" to track.url), forbiddenBody()) else success()
        }, scheduler)
        assertEquals(2, opens); assertEquals(Cpn.Reason.REQUEST_BUDGET_EXHAUSTED, round.reason)
        assertEquals(1, round.observations.size); assertFalse(round.bothObserved)
    }

    @Test fun fixtureRedirectLoopHasNoThirdOpen() = withScheduler { scheduler ->
        val track = track(); var opens = 0
        val round = run(session(track), track, HttpTransport { _, _, _ ->
            opens++; response(302, mapOf("Location" to track.url), forbiddenBody())
        }, scheduler)
        assertEquals(2, opens); assertEquals(Cpn.Reason.REQUEST_BUDGET_EXHAUSTED, round.reason)
    }

    @Test fun fixtureUnsafeRedirectStopsWithoutOpeningDestination() = withScheduler { scheduler ->
        val track = track()
        for (location in listOf("http://fixture.googlevideo.com/", "https://evil.invalid/", track.url + "&range=0-63", track.url + "&cpn=$FIXTURE_CPN")) {
            val round = run(session(track), track, HttpTransport { _, _, _ -> response(302,
                mapOf("Location" to location), forbiddenBody()) }, scheduler)
            assertEquals(1, round.httpRequests); assertEquals(Cpn.Reason.REDIRECT_POLICY, round.observations.single().reason)
        }
    }

    @Test fun fixtureCpnRedirectMustNotDropOrReplaceNonce() = withScheduler { scheduler ->
        val track = track()
        for (location in listOf(track.url, track.url + "&cpn=" + "Z".repeat(16))) {
            var opens = 0
            val round = run(session(track), track, HttpTransport { _, _, _ ->
                if (++opens == 1) success() else response(302, mapOf("Location" to location), forbiddenBody())
            }, scheduler)
            assertEquals(2, opens); assertEquals(Cpn.Reason.REDIRECT_POLICY, round.observations.last().reason)
        }
    }

    @Test fun fixtureCancelledBeforeOpenHasZeroRequests() = withScheduler { scheduler ->
        val track = track(); val token = TransferCancellation().apply { cancel() }
        val round = Cpn.round(session(track), track, neverOpen(), 0, { 0 }, scheduler,
            testOnlyCpnOptIn = true, cancellation = token)
        assertEquals(0, round.httpRequests); assertEquals(Cpn.Reason.CANCELLED, round.observations.single().reason)
    }

    @Test fun fixtureDeadlineCancelsBlockedOpenAndStopsComparison() = withScheduler { scheduler ->
        val track = track(); val released = CountDownLatch(1)
        val round = Cpn.round(session(track), track, HttpTransport { _, _, token ->
            token.bind { released.countDown() }
            check(released.await(2, TimeUnit.SECONDS)); token.check(); error("Must cancel")
        }, memoryClock(), ::memoryClock, scheduler,
            testOnlyCpnOptIn = true, deadlineMs = 25)
        assertEquals(1, round.httpRequests); assertEquals(Cpn.Reason.DEADLINE, round.observations.single().reason)
    }

    @Test fun fixtureDeadlineCancelsBlockedBodyAndClosesResponse() = withScheduler { scheduler ->
        val track = track(); val released = CountDownLatch(1); var closed = false
        val round = Cpn.round(session(track), track, HttpTransport { _, _, token ->
            token.bind { released.countDown() }
            success(object : InputStream() {
                override fun read(): Int = error("Bulk reads only")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    check(len <= 64); check(released.await(2, TimeUnit.SECONDS)); token.check(); return -1
                }
                override fun close() { closed = true }
            })
        }, memoryClock(), ::memoryClock, scheduler,
            testOnlyCpnOptIn = true, deadlineMs = 25)
        assertEquals(Cpn.Reason.DEADLINE, round.observations.single().reason); assertTrue(closed)
    }

    @Test fun fixtureExceptionsHeaderValuesAndToStringAreRedacted() = withScheduler { scheduler ->
        val track = track(); val session = session(track)
        val pair = session.consumeVideoPair(track, testOnlyCpnOptIn = true)
        assertFalse(pair.toString().contains(FIXTURE_CPN)); assertFalse(session.toString().contains(FIXTURE_CPN))
        val reports = listOf(
            run(session(track), track, HttpTransport { _, _, _ -> throw IOException(track.url + "&cpn=$FIXTURE_CPN") }, scheduler),
            run(session(track), track, HttpTransport { _, _, _ -> response(403, mapOf("Content-Length" to FIXTURE_CPN,
                "Content-Range" to track.url, "Location" to track.url, "Set-Cookie" to FIXTURE_CPN), forbiddenBody()) }, scheduler),
            run(session(track), track, HttpTransport { _, _, _ -> success(object : InputStream() {
                override fun read(): Int = error("Bulk reads only")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException(track.url + "&cpn=$FIXTURE_CPN")
            }) }, scheduler),
            run(session(track), track, HttpTransport { _, _, _ -> success(object : InputStream() {
                override fun read(): Int = error("Bulk reads only")
                override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(0, off, off + len); return len }
                override fun close() { throw IOException(track.url + "&cpn=$FIXTURE_CPN") }
            }) }, scheduler),
            run(session(track), track, HttpTransport { _, _, _ -> success() }, scheduler),
        )
        for (round in reports) {
            val text = round.json().toString() + round.toString() + round.observations.joinToString() + Cpn.trackJson(track, 1_000)
            for (secret in listOf(FIXTURE_CPN, "https://", "signature=", "secret", "sensitive-n", "Set-Cookie", "Location"))
                assertFalse("Redacted diagnostic leaked a forbidden value", text.contains(secret))
        }
        assertEquals(Cpn.Reason.BODY_READ, reports[2].observations.single().reason)
        assertEquals(Cpn.Reason.RESPONSE_CLOSE, reports[3].observations.single().reason)
        assertFalse(reports[3].bothObserved)
        assertEquals(1_800_000_000L, Cpn.trackJson(track, 1_000).getLong("expireEpochSeconds"))
    }

    @Test fun fixtureParseIntervalsAndDeclaredExpiryRemainScalarObservations() = withScheduler { scheduler ->
        val track = track(); var now = 140L
        val round = Cpn.round(session(track), track, HttpTransport { _, _, _ -> now += 3; success() },
            100, { now }, scheduler, testOnlyCpnOptIn = true)
        assertEquals(listOf(40L, 43L), round.observations.map { it.parseToRequestMs })
        assertEquals(4096L, Cpn.trackJson(track, 1000).getLong("declaredLength"))
        assertEquals(1_799_999_000L, Cpn.trackJson(track, 1000).getLong("expireRemainingSecondsAtResolution"))
    }

    private fun run(session: YouTubeCpnDiagnosticSession, track: YouTubeTrack, transport: HttpTransport,
        scheduler: ScheduledThreadPoolExecutor) = Cpn.round(session, track, transport, 0, { 0 }, scheduler, testOnlyCpnOptIn = true)
    private fun session(track: YouTubeTrack, cpn: String = FIXTURE_CPN) = YouTubeCpnDiagnosticSession(
        YouTubeMedia("fixture-id", "Authored fixture", listOf(track), emptyList()), cpn)
    private fun track(id: String = "134") = YouTubeTrack(id,
        "https://fixture.googlevideo.com/videoplayback?signature=secret&n=sensitive-n&expire=1800000000",
        4096, 888000, 360, "video/mp4; codecs=\"avc1.4d401e\"")
    private fun memoryClock() = System.nanoTime() / 1_000_000
    private fun scheduler() = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "v015-cpn-diagnostic-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private fun withScheduler(block: (ScheduledThreadPoolExecutor) -> Unit) {
        val scheduler = scheduler(); try { block(scheduler) } finally { scheduler.shutdownNow() }
    }
    private fun neverOpen() = HttpTransport { _, _, _ -> error("No transport open permitted") }
    private fun forbiddenBody() = object : InputStream() { override fun read(): Int = error("No body read permitted") }
    private fun success(body: InputStream = CountingBody()) = response(200, mapOf("Content-Length" to "4096"), body)
    private fun response(code: Int, headers: Map<String, String>, input: InputStream) = object : HttpResponse {
        override val status = code
        override fun header(name: String) = headers[name]
        override fun body() = input
        override fun close() { input.close() }
    }
    private class CountingBody : InputStream() {
        var delivered = 0; var closed = false
        private val bytes = ByteArray(4096).apply { "ftyp".toByteArray(Charsets.US_ASCII).copyInto(this, 4) }
        override fun read(): Int = error("Bounded bulk reads only")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed && delivered + length <= 64) { "Observation byte budget exceeded" }
            bytes.copyInto(buffer, offset, delivered, delivered + length); delivered += length; return length
        }
        override fun close() { closed = true }
    }
    companion object { private const val FIXTURE_CPN = "AbCdEf012345-_xy" }
}

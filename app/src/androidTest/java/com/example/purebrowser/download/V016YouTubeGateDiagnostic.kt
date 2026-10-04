package com.example.purebrowser.download

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.media.site.YouTubeResolver
import com.example.purebrowser.media.site.YouTubeTrack
import com.example.purebrowser.media.site.YOUTUBE_WORKER_CLIENTS
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.example.purebrowser.download.V016YouTubeGateDiagnosticSupport as Gate

/** ONLY #clientGateBoundedDiagnostic may use the network, and only with instrumentation argument
 * video=<11-char id> (plus optional clients= comma list, subset of IOS/ANDROID/ANDROID_VR/TV);
 * absent video argument makes it a deliberate no-op. Per client arm, sequentially and with a
 * fresh resolver/session, it resolves through the production resolver path (clientOverride), picks
 * the largest avc1 video-only track <=1080p, then performs ONE bounded anonymous media GET
 * (Range bytes=0-65535, body read <=64KiB+1, at most two redirect hops, 20s arm wall clock) using
 * the production media-open/header policy. Output is one masked JSON line per arm plus a summary
 * via println. Observation only: NO specific HTTP status is ever asserted; no download, mux,
 * enqueue or publish. The fixture* methods use in-memory HttpTransport only, without real-site
 * opt-in. Never run this method as part of a product/full suite. */
@RunWith(AndroidJUnit4::class)
class V016YouTubeGateDiagnostic {
    @Test(timeout = 120_000) fun clientGateBoundedDiagnostic() {
        val args = InstrumentationRegistry.getArguments()
        val video = args.getString("video") ?: return // absent -> deliberate no-op
        require(Regex("[A-Za-z0-9_-]{11}").matches(video)) { "video argument must be an 11-char id" }
        val requested = (args.getString("clients") ?: DEFAULT_CLIENTS).split(',')
            .map(String::trim).filter { it in YOUTUBE_WORKER_CLIENTS }.distinct()
        val clients = (if (requested.isEmpty()) DEFAULT_CLIENTS.split(',') else requested).take(Gate.MAX_CLIENTS)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = scheduler()
        val arms = mutableListOf<Gate.Arm>()
        try {
            // Isolated test repository supplies only the privacy lease; no plan/record/enqueue/store.
            DualTrackTestSupport.test { repository ->
                for (client in clients) {
                    val started = SystemClock.elapsedRealtime()
                    val arm = try {
                        val media = runBlocking {
                            withTimeout(Gate.ARM_DEADLINE_MS) {
                                YouTubeResolver(context, repository::guardedTransport).resolve(video, client)
                            }
                        }
                        val resolveMs = SystemClock.elapsedRealtime() - started
                        // Worker/parse already restrict videos to avc1 video-only <=1080p; take the largest.
                        val track = media.videos.maxBy { it.height }
                        var opens = 0
                        val counted = HttpTransport { url, headers, token ->
                            check(++opens <= Gate.MAX_HTTP_OPENS_PER_ARM) { "Arm media request budget exceeded" }
                            UrlConnectionTransport().open(url, headers, token)
                        }
                        Gate.probe(client, resolveMs, track, request(), repository.guardedTransport(counted),
                            SystemClock::elapsedRealtime, scheduler,
                            deadlineMs = (Gate.ARM_DEADLINE_MS - resolveMs).coerceIn(1, Gate.ARM_DEADLINE_MS))
                    } catch (failure: Exception) {
                        // Fixed token strings only; never a message, URL or cause.
                        Gate.failedArm(client, SystemClock.elapsedRealtime() - started, resolveReason(failure))
                    }
                    arms += arm
                    println("GATE[client=${arm.client}] ${arm.json()}")
                }
            }
        } finally {
            scheduler.shutdownNow()
            println("GATE[summary] arms=${arms.size} expected=${clients.size} twoXxOr206=" +
                arms.filter(Gate.Arm::twoXxOr206).joinToString(",") { it.client })
        }
        assertEquals("Every client arm must produce an observation record", clients.size, arms.size)
    }

    /** Single-variable range-shape battery: ONE resolve (IOS baseline), then three bounded GETs —
     * A closed prefix (control), B open-ended (bytes=0-), C closed mid-file 1 MiB — over the SAME
     * resolved address. Observation only; asserts record count, never statuses. */
    @Test(timeout = 120_000) fun segmentedRangeBoundedDiagnostic() {
        val args = InstrumentationRegistry.getArguments()
        val video = args.getString("video") ?: return // absent -> deliberate no-op
        require(Regex("[A-Za-z0-9_-]{11}").matches(video)) { "video argument must be an 11-char id" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = scheduler()
        val shapes = listOf("A" to "bytes=0-${Gate.RANGE_END}", "B" to "bytes=0-", "C" to "bytes=1048576-2097151")
        val arms = mutableListOf<Pair<String, Gate.Arm>>()
        try {
            DualTrackTestSupport.test { repository ->
                val started = SystemClock.elapsedRealtime()
                val media = runBlocking {
                    withTimeout(Gate.ARM_DEADLINE_MS) {
                        YouTubeResolver(context, repository::guardedTransport).resolve(video, "IOS")
                    }
                }
                val resolveMs = SystemClock.elapsedRealtime() - started
                val track = media.videos.maxBy { it.height }
                check(track.length > 2_097_152) { "track too small for a mid-file probe" }
                for ((shape, range) in shapes) {
                    val arm = Gate.probe("IOS", resolveMs, track, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler,
                        deadlineMs = Gate.ARM_DEADLINE_MS, rangeHeader = range)
                    arms += shape to arm
                    println("SEG[range=$shape] ${arm.json()}")
                }
            }
        } finally {
            scheduler.shutdownNow()
            println("SEG[summary] arms=${arms.size} expected=${shapes.size} twoXxOr206=" +
                arms.filter { it.second.twoXxOr206 }.joinToString(",") { it.first })
        }
        assertEquals("Every range shape must produce an observation record", shapes.size, arms.size)
    }

    @Test fun fixtureRangeGetUsesProductionHeaderPolicyAndObservesScalarsOnly() = withScheduler { scheduler ->
        var requestedUrl: String? = null
        var requestedHeaders: Map<String, String> = emptyMap()
        val arm = Gate.probe("IOS", 10, track(), request(), HttpTransport { url, headers, _ ->
            requestedUrl = url; requestedHeaders = headers.toMap()
            response(206, mapOf("Content-Length" to "65536", "Content-Range" to "bytes 0-65535/1048576"), CountingBody())
        }, { 0 }, scheduler)
        assertEquals(track().url, requestedUrl)
        assertEquals(mapOf("User-Agent" to Gate.USER_AGENT, "Accept-Encoding" to "identity",
            "Range" to "bytes=0-${Gate.RANGE_END}"), requestedHeaders)
        assertEquals("137", arm.trackItag); assertEquals(1080, arm.trackHeight)
        assertEquals(1_048_576L, arm.declaredLength)
        assertTrue(arm.urlHasPotParam && arm.urlHasIpParam && arm.urlHasCpnParam)
        assertEquals(206, arm.status); assertEquals(0, arm.redirectsObserved)
        assertEquals(1_048_576L, arm.contentRangeTotal)
        assertEquals(Gate.MAX_BODY_BYTES - 1, arm.bodyBytesRead)
        assertEquals(Gate.Prefix.FTYP_AT_OFFSET_4, arm.prefix)
        assertEquals(Gate.Layer.BOUNDED_OBSERVATION, arm.layer)
        val text = arm.json().toString() + arm.toString()
        for (forbidden in listOf("https://", "secret", "sensitive-n", "203.0.113.7", "AbCdEf012345", "Set-Cookie", "Location"))
            assertFalse("Gate diagnostic leaked a forbidden value", text.contains(forbidden))
    }

    @Test fun fixtureEncodedOrRepeatedParamNamesAreDecodedForNameFlagsOnly() = withScheduler { scheduler ->
        val encoded = track().copy(url = track().url
            .replace("pot=secret", "%70ot=secret").replace("expire=1800000000", "expire=1800000000&%63pn=x"))
        val flagged = Gate.probe("TV", 0, encoded, request(), success206(), { 0 }, scheduler)
        assertTrue(flagged.urlHasPotParam); assertTrue(flagged.urlHasCpnParam)
        val clean = Gate.probe("TV", 0, track().copy(
            url = "https://fixture.googlevideo.com/videoplayback?n=sensitive-n&expire=1800000000"),
            request(), success206(), { 0 }, scheduler)
        assertFalse(clean.urlHasPotParam); assertFalse(clean.urlHasIpParam); assertFalse(clean.urlHasCpnParam)
    }

    @Test fun fixtureAccessDeniedIsRecordedWithoutReadingErrorBodyOrAssertingStatus() = withScheduler { scheduler ->
        var opens = 0
        val arm = Gate.probe("ANDROID", 0, track(), request(), HttpTransport { _, _, _ ->
            opens++; response(403, emptyMap(), forbiddenBody()) }, { 0 }, scheduler)
        assertEquals(1, opens); assertEquals(403, arm.status)
        assertEquals(0, arm.bodyBytesRead); assertEquals(Gate.Prefix.NOT_OBSERVED, arm.prefix)
        assertEquals(Gate.Layer.HTTP_STATUS, arm.layer); assertNull(arm.contentRangeTotal)
        assertFalse(arm.twoXxOr206)
    }

    @Test fun fixtureFull200WithoutContentRangeRecordsNullTotalAndBoundedRead() = withScheduler { scheduler ->
        val arm = Gate.probe("ANDROID_VR", 0, track(), request(), HttpTransport { _, _, _ ->
            response(200, emptyMap(), CountingBody()) }, { 0 }, scheduler)
        assertEquals(200, arm.status); assertNull(arm.contentRangeTotal)
        assertEquals(Gate.MAX_BODY_BYTES - 1, arm.bodyBytesRead)
        assertEquals(Gate.Layer.BOUNDED_OBSERVATION, arm.layer); assertTrue(arm.twoXxOr206)
    }

    @Test fun fixtureRedirectsAreCountedAndTheArmStopsAtTheHopBudget() = withScheduler { scheduler ->
        var opens = 0
        val chained = Gate.probe("IOS", 0, track(), request(), HttpTransport { _, _, _ ->
            opens++
            if (opens == 1) response(302, mapOf("Location" to
                "https://fixture.googlevideo.com/videoplayback?id=next&signature=secret2"), forbiddenBody())
            else response(206, mapOf("Content-Length" to "65536", "Content-Range" to "bytes 0-65535/1048576"), CountingBody())
        }, { 0 }, scheduler)
        assertEquals(2, opens); assertEquals(1, chained.redirectsObserved)
        assertEquals(206, chained.status); assertEquals(Gate.Layer.BOUNDED_OBSERVATION, chained.layer)
        var loops = 0
        val capped = Gate.probe("IOS", 0, track(), request(), HttpTransport { _, _, _ ->
            loops++; response(302, mapOf("Location" to "https://fixture.googlevideo.com/videoplayback?signature=secret"), forbiddenBody())
        }, { 0 }, scheduler)
        assertEquals(Gate.MAX_HTTP_OPENS_PER_ARM, loops)
        assertEquals(Gate.MAX_HTTP_OPENS_PER_ARM, capped.redirectsObserved)
        assertEquals(Gate.Layer.REDIRECT_LIMIT, capped.layer)
        var hostile = 0
        val unsafe = Gate.probe("IOS", 0, track(), request(), HttpTransport { _, _, _ ->
            hostile++; response(302, mapOf("Location" to "https://evil.invalid/leak?signature=secret"), forbiddenBody())
        }, { 0 }, scheduler)
        assertEquals(1, hostile); assertEquals(Gate.Layer.REDIRECT_POLICY, unsafe.layer)
        assertFalse(capped.json().toString().contains("secret"))
    }

    @Test fun fixtureDeadlineCancelsBlockedBodyAndRedactsExceptions() = withScheduler { scheduler ->
        val released = CountDownLatch(1)
        val arm = Gate.probe("IOS", 0, track(), request(), HttpTransport { _, _, token ->
            token.bind { released.countDown() }
            response(206, mapOf("Content-Range" to "bytes 0-65535/1048576"), object : InputStream() {
                override fun read(): Int = error("Bounded bulk reads only")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    check(released.await(2, TimeUnit.SECONDS)); token.check(); return 1
                }
            })
        }, { 0 }, scheduler, deadlineMs = 25)
        assertEquals(Gate.Layer.DEADLINE, arm.layer)
        // The arm still records the observed 206 hop; the deadline cancels the body read instead.
        assertEquals(0, arm.bodyBytesRead)
        assertFalse(arm.json().toString().contains("secret"))
    }

    @Test fun fixtureFailedResolveArmCarriesReasonScalarOnly() {
        val arm = Gate.failedArm("IOS", 1234, "RESOLVE_ACCESS_CONDITION")
        val json = arm.json()
        assertEquals("RESOLVE_ACCESS_CONDITION", json.getString("resolveReason"))
        assertTrue(json.isNull("httpStatus")); assertEquals(0, json.getInt("bodyBytesRead"))
        assertEquals("NOT_OBSERVED", json.getString("prefixFlag"))
        val text = json.toString() + arm.toString()
        for (forbidden in listOf("https://", "secret", "Exception")) assertFalse(text.contains(forbidden))
    }

    private fun resolveReason(failure: Exception): String = when (failure) {
        is TimeoutCancellationException -> "RESOLVE_DEADLINE"
        is TransferFailure -> "RESOLVE_${failure.kind}"
        else -> "RESOLVE_FAILED"
    }
    private fun request() = DownloadRecord(name = "v0.1.6 client gate diagnostic",
        userAgent = Gate.USER_AGENT, transfer = TransferType.CONTROLLED,
        protocol = DownloadProtocol.DUAL_TRACK, useAccessContext = false)
    private fun track() = YouTubeTrack("137",
        "https://fixture.googlevideo.com/videoplayback?ip=203.0.113.7&pot=secret&cpn=AbCdEf012345-_xy&n=sensitive-n&expire=1800000000",
        1_048_576, 888_000, 1080, "video/mp4; codecs=\"avc1.640028\"")
    private fun success206(): HttpTransport = HttpTransport { _, _, _ ->
        response(206, mapOf("Content-Length" to "65536", "Content-Range" to "bytes 0-65535/1048576"), CountingBody())
    }
    private fun scheduler() = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "v016-youtube-gate-deadline").apply { isDaemon = true }
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
        private val bytes = ByteArray(Gate.MAX_BODY_BYTES - 1).apply { "ftyp".toByteArray(Charsets.US_ASCII).copyInto(this, 4) }
        override fun read(): Int = error("Bounded bulk reads only")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!closed)
            val n = minOf(length, bytes.size - delivered)
            if (n <= 0) return -1
            bytes.copyInto(buffer, offset, delivered, delivered + n); delivered += n; return n
        }
        override fun close() { closed = true }
    }
    private companion object { const val DEFAULT_CLIENTS = "IOS,ANDROID_VR,TV,ANDROID" }
}

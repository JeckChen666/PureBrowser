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
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.example.purebrowser.download.V016YouTubeGateDiagnosticSupport as Gate

/** ONLY the three arg-gated diagnostics (#clientGateBoundedDiagnostic, #segmentedRangeBoundedDiagnostic,
 * #sessionRangeBoundedDiagnostic) may use the network, and only with instrumentation argument
 * video=<11-char id> (plus optional clients= comma list, subset of IOS/ANDROID/ANDROID_VR/TV;
 * #sessionRangeBoundedDiagnostic additionally honors session=true to attach the WebView's own
 * session cookie to its bounded media GETs); absent video argument makes every one of them a
 * deliberate no-op. Per arm, sequentially, each battery resolves through the production resolver
 * path (clientOverride) with a fresh resolver/session, picks the largest avc1 video-only track
 * <=1080p, then performs ONE bounded media GET (Range bytes=0-65535 or the arm's range shape,
 * body read <=64KiB+1, at most two redirect hops, 20s arm wall clock) using the production
 * media-open/header policy. Output is one masked JSON line per arm plus a summary via println.
 * Observation only: NO specific HTTP status is ever asserted; no download, mux, enqueue or
 * publish. The fixture* methods use in-memory HttpTransport only, without real-site opt-in.
 * Never run this class as part of a product/full suite. */
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

    /** Single-variable range-shape battery: ONE resolve (IOS baseline), then bounded GETs over the
     * SAME resolved addresses, one variable per arm — A closed prefix (control), B open-ended
     * (bytes=0-), C closed mid-file 1 MiB; D–N prefix-span brackets on the largest and smallest
     * video tracks; O–Q the audio track's span ceiling; R–W the range query-param carrier;
     * X–Z/AA/AB span-cap vs end-offset-window discrimination; AG–AI plain no-Range GETs;
     * AC/AD/AF the window rule on the largest track. Parameter NAMES only are ever printed.
     * Observation only; asserts record count, never statuses. */
    @Test(timeout = 240_000) fun segmentedRangeBoundedDiagnostic() {
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
                    // The resolver itself budgets 45s; the battery only needs one resolve to succeed.
                    withTimeout(40_000) {
                        YouTubeResolver(context, repository::guardedTransport).resolve(video, "IOS")
                    }
                }
                val resolveMs = SystemClock.elapsedRealtime() - started
                val track = media.videos.maxBy { it.height }
                check(track.length > 2_097_152) { "track too small for a mid-file probe" }
                // Parameter NAMES only (never values), one bounded line per resolved track.
                (media.videos + media.audios).forEach { resolved ->
                    println("SEG[params itag=${resolved.id} height=${resolved.height} len=${resolved.length}] " +
                        Gate.queryParamNames(resolved.url).joinToString(","))
                }
                for ((shape, range) in shapes) {
                    val arm = Gate.probe("IOS", resolveMs, track, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler,
                        deadlineMs = Gate.ARM_DEADLINE_MS, rangeHeader = range)
                    arms += shape to arm
                    println("SEG[range=$shape] ${arm.json()}")
                }
                // T67 audit layer: prefix spans on both extreme tracks (labels D–I), then a
                // threshold bracket on the smallest track (labels J–N), the audio track's span
                // ceiling (labels O–Q), and the browser-player range query carrier (labels R–T).
                val smallest = media.videos.minBy { it.height }
                val audioTrack = media.audios.first()
                val spanArms = listOf(
                    "D" to (track to "bytes=0-4194303"), "E" to (track to "bytes=0-1048575"), "F" to (track to "bytes=0-262143"),
                    "G" to (smallest to "bytes=0-4194303"), "H" to (smallest to "bytes=0-1048575"), "I" to (smallest to "bytes=0-65535"),
                    "J" to (smallest to "bytes=65536-131071"), "K" to (smallest to "bytes=0-131071"),
                    "L" to (smallest to "bytes=0-262143"), "M" to (smallest to "bytes=0-524287"), "N" to (smallest to "bytes=0-786431"))
                for ((label, spanTrack) in spanArms) {
                    check(spanTrack.first.length > spanTrack.second.substringAfterLast('-').toLong()) { "span exceeds track" }
                    val arm = Gate.probe("IOS", resolveMs, spanTrack.first, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler,
                        deadlineMs = Gate.ARM_DEADLINE_MS, rangeHeader = spanTrack.second)
                    arms += label to arm
                    println("SEG[span=$label] ${arm.json()}")
                }
                for (label in listOf("O", "P", "Q")) {
                    val range = when (label) { "O" -> "bytes=0-262143"; "P" -> "bytes=0-1048575"; else -> "bytes=0-4194303" }
                    check(audioTrack.length > range.substringAfterLast('-').toLong()) { "span exceeds track" }
                    val arm = Gate.probe("IOS", resolveMs, audioTrack, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler, deadlineMs = Gate.ARM_DEADLINE_MS, rangeHeader = range)
                    arms += label to arm
                    println("SEG[span=$label] ${arm.json()}")
                }
                val queryArms = listOf(
                    "R" to (smallest to "0-524287"), "S" to (smallest to "0-262143"), "T" to (smallest to "0-4194303"),
                    "U" to (audioTrack to "0-262143"), "V" to (track to "0-4194303"), "W" to (smallest to "0-${smallest.length - 1}"))
                for ((label, carrierTrack) in queryArms) {
                    check(carrierTrack.first.length > carrierTrack.second.substringAfter('-').toLong()) { "span exceeds track" }
                    val arm = Gate.probe("IOS", resolveMs, carrierTrack.first, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler, deadlineMs = Gate.ARM_DEADLINE_MS, queryRange = carrierTrack.second)
                    arms += label to arm
                    println("SEG[query=$label] ${arm.json()}")
                }
                // Span-cap vs end-offset-cap discrimination on the smallest track: a full 256 KiB
                // span at a 2 MiB offset (X), a small span crossing the observed prefix ceiling (Y)
                // and the exact final-chunk shape a 256 KiB transfer would issue (Z); plus the same
                // discrimination on the audio track (labels AA/AB).
                val offsetArms = listOf(
                    Triple("X", smallest, "bytes=2097152-2359295"), Triple("Y", smallest, "bytes=200000-331071"),
                    Triple("Z", smallest, "bytes=4718592-4865642"), Triple("AA", audioTrack, "bytes=10485760-10747903"),
                    Triple("AB", audioTrack, "bytes=14155776-14373563"))
                for ((label, offsetTrack, range) in offsetArms) {
                    check(offsetTrack.length > range.substringAfterLast('-').toLong()) { "span exceeds track" }
                    val arm = Gate.probe("IOS", resolveMs, offsetTrack, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler, deadlineMs = Gate.ARM_DEADLINE_MS, rangeHeader = range)
                    arms += label to arm
                    println("SEG[offset=$label] ${arm.json()}")
                }
                // Plain sequential GET (no Range header, no range param): does the anonymous gate
                // stream the whole resource with a full Content-Length? Labels AG–AI. Then the
                // window rule on the largest track: an 8 MiB prefix (AC), a 1 MiB span at ~85 MiB
                // (AD) and the exact final chunk (AF) — span-cap vs end-offset-window semantics.
                for ((label, plainTrack) in listOf("AG" to smallest, "AH" to audioTrack, "AI" to track)) {
                    val arm = Gate.probe("IOS", resolveMs, plainTrack, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler, deadlineMs = Gate.ARM_DEADLINE_MS, noRange = true)
                    arms += label to arm
                    println("SEG[plain=$label] ${arm.json()}")
                }
                for ((label, range) in listOf("AC" to "bytes=0-8388607", "AD" to "bytes=85000192-85983231",
                    "AF" to "bytes=167772160-170210372")) {
                    check(track.length > range.substringAfterLast('-').toLong()) { "span exceeds track" }
                    val arm = Gate.probe("IOS", resolveMs, track, request(), repository.guardedTransport(UrlConnectionTransport()),
                        SystemClock::elapsedRealtime, scheduler, deadlineMs = Gate.ARM_DEADLINE_MS, rangeHeader = range)
                    arms += label to arm
                    println("SEG[window=$label] ${arm.json()}")
                }
            }
        } finally {
            scheduler.shutdownNow()
            println("SEG[summary] arms=${arms.size} expected=${shapes.size + 31} twoXxOr206=" +
                arms.filter { it.second.twoXxOr206 }.joinToString(",") { it.first })
        }
        assertEquals("Every range shape must produce an observation record", shapes.size + 31, arms.size)
    }

    /** T74 session-context variant of the range-shape battery: the SAME A/B/C shapes plus a D arm
     * with the exact final-chunk window a 64 KiB segmented transfer would issue for the declared
     * total (bytes=<total-65536>-<total-1>). Needs the video argument (absent -> deliberate
     * no-op); the session argument selects the context. session absent/false: anonymous arms,
     * identical policy to segmentedRangeBoundedDiagnostic. session=true: media GETs carry the
     * WEBVIEW's session context — per hop, the cookie CookieManager holds for that hop's URL
     * (youtube.com/googlevideo.com), validated by the shared pure session-header policy; the run
     * first requires in-app login evidence and otherwise Assume-skips with instructions. One
     * fresh resolve per run through the production resolver (anonymous worker, IOS); the battery
     * shares <=6 media GETs total, per arm <=3 opens, a 20s deadline and a <=64KiB+1 body read;
     * hops without a usable session cookie stay anonymous. Output is one masked SESS[shape] JSON
     * line per arm plus a summary whose session flag is a boolean only — cookie contents, URLs
     * and header values are never printed. The resolver itself never sees the session: logged-in
     * playback is not observable from its output, so playability differences are skipped. */
    @Test(timeout = 240_000) fun sessionRangeBoundedDiagnostic() {
        val args = InstrumentationRegistry.getArguments()
        val video = args.getString("video") ?: return // absent -> deliberate no-op
        require(Regex("[A-Za-z0-9_-]{11}").matches(video)) { "video argument must be an 11-char id" }
        val session = args.getString("session")?.equals("true", ignoreCase = true) == true
        val cookieManager = android.webkit.CookieManager.getInstance()
        val sessionCookieFor: ((String) -> String?)? = if (session) {
            // Login guidance: without a youtube.com cookie in the WebView jar there is no session
            // context to test — skip with instructions instead of silently running anonymous arms.
            val loginEvidence = RequestPolicy.sessionCookieHeader(
                runCatching { cookieManager.getCookie("https://www.youtube.com/") }.getOrNull()) != null
            Assume.assumeTrue("需要先在应用内登录 YouTube（安装包启动后人工登录一次），再带 session=true 重跑", loginEvidence)
            val lookup: (String) -> String? = { url -> runCatching { cookieManager.getCookie(url) }.getOrNull() }
            lookup
        } else null
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = scheduler()
        val arms = mutableListOf<Pair<String, Gate.Arm>>()
        val opens = java.util.concurrent.atomic.AtomicInteger()
        try {
            DualTrackTestSupport.test { repository ->
                val started = SystemClock.elapsedRealtime()
                val media = runBlocking {
                    // Fresh resolve per run; the resolver is the anonymous production worker.
                    withTimeout(40_000) { YouTubeResolver(context, repository::guardedTransport).resolve(video, "IOS") }
                }
                val resolveMs = SystemClock.elapsedRealtime() - started
                println("SESS[resolve] client=IOS loggedInPlayback=NOT_OBSERVABLE resolveMs=$resolveMs")
                val track = media.videos.maxBy { it.height }
                check(track.length > 2_097_152) { "track too small for the mid-file and final-chunk probes" }
                val shapes = listOf(
                    "A" to "bytes=0-${Gate.RANGE_END}", "B" to "bytes=0-", "C" to "bytes=1048576-2097151",
                    "D" to "bytes=${track.length - 65536}-${track.length - 1}")
                // Battery-wide budget: at most 6 media GETs across every arm (per-arm cap still applies).
                val budgeted = HttpTransport { url, headers, token ->
                    check(opens.incrementAndGet() <= Gate.MAX_SESSION_HTTP_OPENS) { "Session battery media request budget exceeded" }
                    UrlConnectionTransport().open(url, headers, token)
                }
                for ((shape, range) in shapes) {
                    val arm = Gate.probe("IOS", resolveMs, track, request(), repository.guardedTransport(budgeted),
                        SystemClock::elapsedRealtime, scheduler,
                        deadlineMs = Gate.ARM_DEADLINE_MS, rangeHeader = range, sessionCookieFor = sessionCookieFor)
                    arms += shape to arm
                    println("SESS[shape=$shape] ${arm.json()}")
                }
            }
        } finally {
            scheduler.shutdownNow()
            println("SESS[summary] arms=${arms.size} expected=4 session=${if (session) "on" else "off"} " +
                "sessionArms=${arms.count { it.second.sessionContextOn }} twoXxOr206=" +
                arms.filter { it.second.twoXxOr206 }.joinToString(",") { it.first })
        }
        assertEquals("Every session range shape must produce an observation record", 4, arms.size)
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

    @Test fun fixtureSessionProbeAttachesValidatedSessionCookieAsBooleanOnly() = withScheduler { scheduler ->
        var requestedHeaders: Map<String, String> = emptyMap()
        val arm = Gate.probe("IOS", 0, track(), request(), HttpTransport { url, headers, _ ->
            requestedHeaders = headers.toMap()
            response(206, mapOf("Content-Length" to "65536", "Content-Range" to "bytes 0-65535/1048576"), CountingBody())
        }, { 0 }, scheduler, sessionCookieFor = { "VISITOR=sensitive-session; PREF=hidden" })
        assertEquals("VISITOR=sensitive-session; PREF=hidden", requestedHeaders["Cookie"])
        assertEquals("bytes=0-${Gate.RANGE_END}", requestedHeaders["Range"])
        assertTrue(arm.sessionContextOn)
        val json = arm.json()
        assertEquals(false, json.get("anonymous")); assertEquals(true, json.get("sessionContext"))
        val text = json.toString() + arm.toString()
        for (forbidden in listOf("sensitive-session", "hidden", "VISITOR", "PREF", "Set-Cookie"))
            assertFalse("Session diagnostic leaked a forbidden value", text.contains(forbidden))
    }

    @Test fun fixtureSessionProbeWithoutUsableCookieStaysAnonymous() = withScheduler { scheduler ->
        for (lookup in listOf(null, "", "  ", "s=a\r\nX-Bad: yes", "a".repeat(16385))) {
            var requestedHeaders: Map<String, String> = emptyMap()
            val arm = Gate.probe("IOS", 0, track(), request(), HttpTransport { _, headers, _ ->
                requestedHeaders = headers.toMap()
                response(206, mapOf("Content-Range" to "bytes 0-65535/1048576"), CountingBody())
            }, { 0 }, scheduler, sessionCookieFor = { lookup })
            assertFalse(requestedHeaders.containsKey("Cookie"))
            assertFalse(arm.sessionContextOn)
            val json = arm.json()
            assertEquals(true, json.get("anonymous")); assertEquals(false, json.get("sessionContext"))
        }
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

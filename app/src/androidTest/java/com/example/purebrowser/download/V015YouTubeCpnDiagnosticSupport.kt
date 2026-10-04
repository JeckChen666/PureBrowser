package com.example.purebrowser.download

import com.example.purebrowser.media.site.YouTubeCpnDiagnosticSession
import com.example.purebrowser.media.site.YouTubeTrack
import com.example.purebrowser.media.site.validateCpnDiagnosticUrl
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.HttpURLConnection
import java.net.CookieHandler
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent second-round diagnostic; no common transport/plan/record/store changes.
 * HTTP opens INCLUDING redirects share a hard round-wide budget of two. A redirect can exhaust
 * the comparison: never add requests to complete it. Full Range semantics, <=64 bytes observed,
 * no retry/short-range fallback/full transfer, no raw bytes/URLs/headers/errors in reports. */
internal object V015YouTubeCpnDiagnosticSupport {
    const val MAX_HTTP_REQUESTS = 2
    const val MAX_REDIRECTS = 2
    const val MAX_BYTES_PER_REQUEST = 64
    const val DEADLINE_MS = 12_000L
    const val USER_AGENT = "PureBrowser authorized Sintel audit"
    private val redirects = setOf(301, 302, 303, 307, 308)
    private val digits = Regex("[0-9]{1,19}")
    private val fullRange = Regex("bytes 0-([0-9]{1,19})/([0-9]{1,19})")

    enum class Variant { BASELINE_NO_CPN, SAME_SESSION_CPN_ONLY }
    enum class Reason {
        RESOLUTION_OR_DIAGNOSTIC_FAILURE, OPT_IN_REQUIRED, REQUEST_POLICY, ACCESS_DENIED, REPEATED_ACCESS_DENIAL_STOP,
        HTTP_STATUS, FULL_RESPONSE_CONTRACT_FAILED, EARLY_EOF, BODY_LIMIT_OBSERVED,
        NETWORK_OPEN, BODY_READ, RESPONSE_CLOSE, DEADLINE, CANCELLED, REDIRECT_POLICY, REDIRECT_LIMIT,
        REQUEST_BUDGET_EXHAUSTED, COMPARISON_COMPLETE_OBSERVATION_ONLY, NON_ACCESS_FAILURE_STOP,
    }
    enum class Prefix { NOT_OBSERVED, MP4_FTYP_AT_OFFSET_4, OTHER }

    // Report objects only contain bounded scalar observations, never the session/pair/response.
    class Observation(val variant: Variant) {
        var reason = Reason.REQUEST_POLICY
        var status: Int? = null
        var observedBytes = 0
        var prefix = Prefix.NOT_OBSERVED
        var fullResponseHeadersAccepted = false
        var parseToRequestMs: Long? = null
        var elapsedMs = 0L
        val hops = mutableListOf<JSONObject>()
        val observed get() = reason == Reason.BODY_LIMIT_OBSERVED
        fun json() = JSONObject().put("variant", variant.name).put("reason", reason.name)
            .put("status", status ?: JSONObject.NULL).put("observedBytes", observedBytes)
            .put("prefixClass", prefix.name).put("fullResponseHeadersAccepted", fullResponseHeadersAccepted)
            .put("parseToRequestMs", parseToRequestMs ?: JSONObject.NULL).put("elapsedMs", elapsedMs)
            .put("hops", JSONArray(hops))
        override fun toString() = "CpnObservation(variant=$variant,reason=$reason,bytes=$observedBytes)"
    }
    class Round(val observations: List<Observation>, val reason: Reason, val httpRequests: Int) {
        val bothObserved get() = observations.size == 2 && observations.all { it.observed }
        fun json() = JSONObject().put("observations", JSONArray(observations.map { it.json() }))
            .put("stopReason", reason.name).put("httpRequests", httpRequests)
            .put("totalObservedMediaBodyBytes", observations.sumOf { it.observedBytes })
            .put("both64ByteObservations", bothObserved)
            // Header acceptance and even TWO 64-byte reads are not full-response/download success.
            .put("completeResponseVerified", false).put("completeTransferAttempted", false)
            .put("downloadSucceeded", false)
        override fun toString() = "CpnRound(reason=$reason,requests=$httpRequests)"
    }

    private fun number(raw: String?): Long? = raw?.takeIf(digits::matches)?.toLongOrNull()
    private fun expiry(url: String): Long? = URI(url).rawQuery?.split('&')
        ?.filter { it.substringBefore('=') == "expire" }?.singleOrNull()
        ?.substringAfter('=', "")?.let(::number)
    fun trackJson(track: YouTubeTrack, resolvedEpochSeconds: Long): JSONObject {
        val expires = expiry(track.url)
        return JSONObject().put("formatId", track.id).put("height", track.height)
            .put("declaredLength", track.length).put("durationMs", track.durationMs)
            .put("expireEpochSeconds", expires ?: JSONObject.NULL)
            .put("expireRemainingSecondsAtResolution", expires?.minus(resolvedEpochSeconds) ?: JSONObject.NULL)
            .put("expiryStateAtResolution", if (expires == null) "UNKNOWN" else if (expires <= resolvedEpochSeconds) "EXPIRED" else "FUTURE_TIME_ONLY")
    }
    private fun headerContract(response: HttpResponse, declared: Long): Boolean {
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) return false
        val rawLength = response.header("Content-Length")
        val length = number(rawLength)
        if (rawLength != null && (length == null || length != declared)) return false
        if (rawLength != null && response.header("Transfer-Encoding") != null) return false
        val range = response.header("Content-Range")
        if (response.status == 200) return range == null
        if (response.status != 206) return false
        val m = range?.let(fullRange::matchEntire) ?: return false
        return number(m.groupValues[1]) == declared - 1 && number(m.groupValues[2]) == declared
    }
    private fun hopJson(response: HttpResponse, hop: Int, interval: Long) = JSONObject()
        .put("hop", hop).put("status", response.status).put("parseToRequestMs", interval)
        .put("declaredResponseLength", number(response.header("Content-Length")) ?: JSONObject.NULL)
        .put("responseLengthState", when {
            response.header("Content-Length") == null -> "ABSENT"
            number(response.header("Content-Length")) == null -> "INVALID"
            else -> "NUMERIC"
        }).put("contentRangeState", when {
            response.header("Content-Range") == null -> "ABSENT"
            response.header("Content-Range")?.let(fullRange::matchEntire) == null -> "INVALID_OR_NOT_FULL"
            else -> "FULL_RANGE_SHAPE"
        })

    fun round(
        session: YouTubeCpnDiagnosticSession, track: YouTubeTrack, transport: HttpTransport,
        resolvedAtMs: Long, clock: () -> Long, scheduler: ScheduledThreadPoolExecutor,
        testOnlyCpnOptIn: Boolean = false, cancellation: TransferCancellation = TransferCancellation(),
        deadlineMs: Long = DEADLINE_MS,
    ): Round {
        if (!testOnlyCpnOptIn) return Round(emptyList(), Reason.OPT_IN_REQUIRED, 0)
        val pair = try {
            check(deadlineMs in 1..DEADLINE_MS && track.length >= MAX_BYTES_PER_REQUEST)
            session.consumeVideoPair(track, testOnlyCpnOptIn = true)
        } catch (_: Exception) { return Round(emptyList(), Reason.REQUEST_POLICY, 0) }
        var opens = 0
        val observations = mutableListOf<Observation>()
        val headers = mapOf("User-Agent" to USER_AGENT, "Accept-Encoding" to "identity",
            "Range" to "bytes=0-${track.length - 1}")
        for (variant in Variant.entries) {
            if (opens >= MAX_HTTP_REQUESTS) return Round(observations, Reason.REQUEST_BUDGET_EXHAUSTED, opens)
            val result = Observation(variant)
            observations += result
            val start = clock()
            val expired = AtomicBoolean(false)
            val deadline = scheduler.schedule({ expired.set(true); cancellation.cancel() }, deadlineMs, TimeUnit.MILLISECONDS)
            var url = if (variant == Variant.BASELINE_NO_CPN) pair.track.url else pair.withCpnUrl
            try {
                for (hop in 0..MAX_REDIRECTS) {
                    cancellation.check()
                    check(clock() - start < deadlineMs)
                    result.reason = Reason.REQUEST_POLICY
                    validateCpnDiagnosticUrl(url, cpnExpected = variant == Variant.SAME_SESSION_CPN_ONLY)
                    if (opens >= MAX_HTTP_REQUESTS) { result.reason = Reason.REQUEST_BUDGET_EXHAUSTED; break }
                    val requestedAt = clock()
                    if (hop == 0) result.parseToRequestMs = requestedAt - resolvedAtMs
                    result.reason = Reason.NETWORK_OPEN
                    opens++ // Count attempted opens too; errors never regain the request budget.
                    transport.open(url, headers, cancellation).use { response ->
                        cancellation.check()
                        result.status = response.status
                        result.hops += hopJson(response, hop, requestedAt - resolvedAtMs)
                        if (response.status in redirects) {
                            result.reason = Reason.REDIRECT_LIMIT
                            if (hop == MAX_REDIRECTS) return@use
                            result.reason = Reason.REDIRECT_POLICY
                            val location = response.header("Location") ?: return@use
                            val next = RequestPolicy.redirect(url, location, false, false)
                            validateCpnDiagnosticUrl(next, cpnExpected = variant == Variant.SAME_SESSION_CPN_ONLY)
                            // Do not add/repair/replace cpn, Range or any query on redirects.
                            if (variant == Variant.SAME_SESSION_CPN_ONLY &&
                                URI(next).rawQuery?.split('&')?.singleOrNull { it.startsWith("cpn=") } !=
                                URI(pair.withCpnUrl).rawQuery?.split('&')?.singleOrNull { it.startsWith("cpn=") })
                                return@use
                            url = next
                            result.reason = Reason.REQUEST_BUDGET_EXHAUSTED
                            return@use
                        }
                        if (response.status in setOf(401, 403, 410)) { result.reason = Reason.ACCESS_DENIED; return@use }
                        if (response.status !in setOf(200, 206)) { result.reason = Reason.HTTP_STATUS; return@use }
                        result.reason = Reason.FULL_RESPONSE_CONTRACT_FAILED
                        result.fullResponseHeadersAccepted = headerContract(response, track.length)
                        if (!result.fullResponseHeadersAccepted) return@use
                        result.reason = Reason.BODY_READ
                        response.body().use { input ->
                            val bytes = ByteArray(MAX_BYTES_PER_REQUEST)
                            try {
                                while (result.observedBytes < bytes.size) {
                                    cancellation.check()
                                    val n = input.read(bytes, result.observedBytes, bytes.size - result.observedBytes)
                                    if (n > 0) result.observedBytes += n
                                    cancellation.check()
                                    if (n < 0) { result.reason = Reason.EARLY_EOF; break }
                                    if (n == 0) continue
                                }
                                if (result.observedBytes >= 8) result.prefix =
                                    if (bytes[4] == 102.toByte() && bytes[5] == 116.toByte() &&
                                        bytes[6] == 121.toByte() && bytes[7] == 112.toByte()) Prefix.MP4_FTYP_AT_OFFSET_4 else Prefix.OTHER
                                if (result.observedBytes == bytes.size) result.reason = Reason.BODY_LIMIT_OBSERVED
                            } finally { bytes.fill(0) }
                        }
                    }
                    if (result.status !in redirects || result.reason != Reason.REQUEST_BUDGET_EXHAUSTED) break
                }
            } catch (failure: Exception) {
                // Never serialize/log/propagate an exception message, stack or cause containing a URL/cpn.
                if (result.reason == Reason.BODY_LIMIT_OBSERVED) result.reason = Reason.RESPONSE_CLOSE
                if (failure is CancellationException) result.reason = Reason.CANCELLED
                if (failure is SocketTimeoutException) result.reason = Reason.DEADLINE
            } finally {
                deadline.cancel(false)
                result.elapsedMs = (clock() - start).coerceAtLeast(0)
                if (expired.get() || result.elapsedMs >= deadlineMs) result.reason = Reason.DEADLINE
                else try { cancellation.check() } catch (_: CancellationException) { result.reason = Reason.CANCELLED }
            }
            if (observations.size == 2 && observations.all { it.reason == Reason.ACCESS_DENIED })
                return Round(observations, Reason.REPEATED_ACCESS_DENIAL_STOP, opens)
            if (!result.observed && result.reason != Reason.ACCESS_DENIED)
                return Round(observations, if (result.reason == Reason.REQUEST_BUDGET_EXHAUSTED)
                    Reason.REQUEST_BUDGET_EXHAUSTED else Reason.NON_ACCESS_FAILURE_STOP, opens)
        }
        return Round(observations, Reason.COMPARISON_COMPLETE_OBSERVATION_ONLY, opens)
    }

    /** Test-local fixed anonymous GET; no shared downloader, cookie/auth provider or automatic redirects. */
    fun anonymousTransport(): HttpTransport = HttpTransport { url, headers, token ->
        token.check()
        check(CookieHandler.getDefault() == null) { "Anonymous diagnostic environment required" }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = DEADLINE_MS.toInt(); connection.readTimeout = DEADLINE_MS.toInt()
        connection.requestMethod = "GET"; connection.useCaches = false
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        token.bind { connection.disconnect() }
        try {
            val status = connection.responseCode
            object : HttpResponse {
                override val status = status
                override fun header(name: String) = connection.getHeaderField(name)
                override fun body() = connection.inputStream
                override fun close() { connection.disconnect(); token.clear() }
            }
        } catch (failure: Exception) { connection.disconnect(); token.clear(); throw failure }
    }
}

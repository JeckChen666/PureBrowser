package com.example.purebrowser.download

import com.example.purebrowser.media.site.YouTubeTrack
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only observer, not an alternative downloader. No body bytes or transient URLs leave memory.
 * Sources: pinned youtubei.js 18.1.0 FormatUtils.download / HTTPClient.fetch_function,
 * RequestPolicy, UrlConnectionTransport and DualTrackTransfer.downloadTrack/fullRange.
 * This deliberately does NOT add the library's cpn or STREAM_HEADERS: either would confound
 * the two Range comparisons. No client rotation, credentials, retries, task creation or mux.
 */
internal object V015YouTubeTransferDiagnosticSupport {
    const val PREFIX_BYTES = 64
    const val MAX_VARIANTS = 3
    const val MAX_REDIRECTS = 2
    const val MAX_HTTP_REQUESTS = MAX_VARIANTS * (MAX_REDIRECTS + 1)
    const val REQUEST_DEADLINE_MS = 12_000L
    private val redirects = setOf(301, 302, 303, 307, 308)
    private val denied = setOf(401, 403, 410)
    private val numeric = Regex("[0-9]{1,19}")
    private val contentRange = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})")

    enum class Semantics { FULL_HEADER, PREFIX_HEADER, PREFIX_QUERY }
    enum class Layer {
        RESPONSE_CONTRACT, ACCESS_CONDITION, HTTP_STATUS, REDIRECT, REDIRECT_POLICY,
        REQUEST_POLICY, NETWORK_OPEN, BODY_READ, DEADLINE, CANCELLED, CONTAINER_PREFIX, BOUNDED_OBSERVATION
    }

    // Only enums / scalars reach JSON. Never store header values, Location, exceptions or a URL.
    class Observation(val semantics: Semantics, var parseToRequestMs: Long) {
        val hops = mutableListOf<JSONObject>()
        var status: Int? = null
        var layer = Layer.NETWORK_OPEN
        var failureKind: FailureKind? = null
        var observedBytes = 0
        var prefixFtyp = false
        var responseContract = false
        var eofBeforeLimit = false
        var elapsedMs = 0L
        val accessDenied get() = status in denied
        val accepted get() = layer == Layer.BOUNDED_OBSERVATION && responseContract &&
            prefixFtyp && observedBytes == PREFIX_BYTES
        fun json(declared: Long) = JSONObject()
            .put("semantics", semantics.name).put("method", "GET")
            .put("rangeCarrier", if (semantics == Semantics.PREFIX_QUERY) "URL_QUERY" else "HEADER")
            .put("rangeStart", 0).put("rangeEnd", if (semantics == Semantics.FULL_HEADER) declared - 1 else 63)
            .put("parseToRequestMs", parseToRequestMs).put("elapsedMs", elapsedMs)
            .put("status", status ?: JSONObject.NULL).put("layer", layer.name)
            .put("failureKind", failureKind?.name ?: JSONObject.NULL)
            .put("responseContract", responseContract).put("observedBytes", observedBytes)
            .put("bodyReadLimit", PREFIX_BYTES).put("ftypObserved", prefixFtyp)
            .put("eofBeforeLimit", eofBeforeLimit).put("boundedObservationAccepted", accepted)
            .put("downloadSucceeded", false).put("completeBodyChecked", false)
            .put("hops", JSONArray(hops))
        override fun toString() = "Observation(semantics=$semantics,layer=$layer,status=$status)"
    }

    fun trackJson(track: YouTubeTrack, video: Boolean, nowSeconds: Long): JSONObject {
        val expiry = expirySeconds(track.url)
        return JSONObject().put("formatId", track.id).put("codec", if (video) "avc1" else "mp4a.40.2")
            .put("container", "mp4").put("height", track.height).put("declaredLength", track.length)
            .put("durationMs", track.durationMs).put("addressClass", "HTTPS_GOOGLEVIDEO")
            .put("expireEpochSeconds", expiry ?: JSONObject.NULL)
            .put("expireRemainingSecondsAtResolution", expiry?.minus(nowSeconds) ?: JSONObject.NULL)
            .put("cpnPresent", queryValue(track.url, "cpn") != null)
            .put("queryRangePresent", queryValue(track.url, "range") != null)
    }

    private fun queryValue(url: String, key: String): String? = URI(url).rawQuery?.split('&')
        ?.singleOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")
    fun expirySeconds(url: String): Long? = queryValue(url, "expire")?.let(::number)
    private fun number(value: String?): Long? = value?.takeIf { numeric.matches(it) }?.toLongOrNull()

    private fun validateTrackUrl(url: String) {
        RequestPolicy.validateUrl(url, false)
        val uri = URI(url)
        // Match the resolver's site boundary, while refusing alternate ports / fragments.
        check(uri.host.lowercase().endsWith(".googlevideo.com") && uri.port in setOf(-1, 443) &&
            uri.rawFragment == null && uri.rawUserInfo == null)
    }

    fun probe(
        track: YouTubeTrack, request: DownloadRecord, semantics: Semantics, transport: HttpTransport,
        resolvedAtMs: Long, clock: () -> Long, scheduler: ScheduledThreadPoolExecutor,
        deadlineMs: Long = REQUEST_DEADLINE_MS,
    ): Observation {
        val start = clock()
        val result = Observation(semantics, start - resolvedAtMs)
        val token = TransferCancellation()
        val expired = AtomicBoolean(false)
        val deadline = scheduler.schedule({ expired.set(true); token.cancel() }, deadlineMs, TimeUnit.MILLISECONDS)
        var url = track.url
        try {
            result.layer = Layer.REQUEST_POLICY
            validateTrackUrl(url)
            check(!request.useAccessContext && request.mediaUrl == null && track.length >= PREFIX_BYTES)
            // Do not overwrite an existing query range: the experiment would no longer be single-variable.
            check(URI(url).rawQuery?.split('&')?.none { it.substringBefore('=') == "range" } != false)
            if (semantics == Semantics.PREFIX_QUERY) {
                url += if (URI(url).rawQuery == null) "?range=0-63" else "&range=0-63"
                validateTrackUrl(url)
            }
            for (hop in 0..MAX_REDIRECTS) {
                token.check()
                val headers = RequestPolicy.headers(request, url, null).toMutableMap()
                check(headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) })
                if (semantics != Semantics.PREFIX_QUERY) headers["Range"] =
                    if (semantics == Semantics.FULL_HEADER) "bytes=0-${track.length - 1}" else "bytes=0-63"
                result.layer = Layer.NETWORK_OPEN
                val requestedAt = clock()
                if (hop == 0) result.parseToRequestMs = requestedAt - resolvedAtMs
                transport.open(url, headers, token).use { response ->
                    result.status = response.status
                    result.hops += hopJson(response, hop, requestedAt - resolvedAtMs)
                    if (response.status in redirects) {
                        result.layer = Layer.REDIRECT
                        if (hop == MAX_REDIRECTS) return result
                        result.layer = Layer.REDIRECT_POLICY
                        val location = response.header("Location") ?: return result
                        url = RequestPolicy.redirect(url, location, false, false)
                        validateTrackUrl(url)
                        if (semantics == Semantics.PREFIX_QUERY && queryValue(url, "range") != "0-63") return result
                        // Keep original query semantics on redirects; don't add/repair parameters there.
                    } else {
                        if (result.accessDenied) { result.layer = Layer.ACCESS_CONDITION; return result }
                        if (response.status !in setOf(200, 206)) { result.layer = Layer.HTTP_STATUS; return result }
                        result.layer = Layer.RESPONSE_CONTRACT
                        result.responseContract = contract(response, track.length, semantics)
                        if (!result.responseContract) return result
                        result.layer = Layer.BODY_READ
                        response.body().use { input ->
                            val prefix = ByteArray(PREFIX_BYTES)
                            while (result.observedBytes < PREFIX_BYTES) {
                                token.check()
                                val n = input.read(prefix, result.observedBytes, PREFIX_BYTES - result.observedBytes)
                                if (n < 0) { result.eofBeforeLimit = true; break }
                                if (n == 0) continue
                                result.observedBytes += n
                            }
                            result.prefixFtyp = result.observedBytes >= 12 &&
                                String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp"
                            // Do NOT read a 65th byte to detect EOF, hash/log the prefix or persist it.
                        }
                        token.check()
                        result.layer = if (result.observedBytes != PREFIX_BYTES) Layer.BODY_READ
                            else if (!result.prefixFtyp) Layer.CONTAINER_PREFIX else Layer.BOUNDED_OBSERVATION
                        return result
                    }
                }
            }
        } catch (failure: Exception) {
            // Never retain/rethrow exception text/cause: networking/URI errors may embed the signed URL.
            if (failure is TransferFailure) result.failureKind = failure.kind
            if (expired.get() || failure is SocketTimeoutException || clock() - start >= deadlineMs)
                result.layer = Layer.DEADLINE
            else if (failure is CancellationException) result.layer = Layer.CANCELLED
        } finally {
            token.cancel()
            deadline.cancel(false)
            result.elapsedMs = clock() - start
        }
        return result
    }

    private fun contract(response: HttpResponse, declared: Long, semantics: Semantics): Boolean {
        val encoding = response.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", true)) return false
        val lengthValue = response.header("Content-Length")
        if (response.header("Transfer-Encoding") != null && lengthValue != null) return false
        val length = number(lengthValue)
        if (lengthValue != null && length == null) return false
        if (semantics == Semantics.FULL_HEADER) {
            // Same header-level contract as production; NEVER relax it for a prefix observation.
            if (response.status == 200 && response.header("Content-Range") != null) return false
            val total = if (response.status == 206) DualTrackTransfer.fullRange(response.header("Content-Range"))
                ?: return false else null
            return (length == null || length == declared) && (total == null || total == declared)
        }
        // Both 64-byte variants must actually honor the exact same range, not merely return HTTP200.
        if (response.status != 206 || length != PREFIX_BYTES.toLong()) return false
        val match = response.header("Content-Range")?.let(contentRange::matchEntire) ?: return false
        return number(match.groupValues[1]) == 0L && number(match.groupValues[2]) == 63L &&
            number(match.groupValues[3]) == declared
    }

    private fun hopJson(response: HttpResponse, hop: Int, parseToRequestMs: Long): JSONObject {
        val rawRange = response.header("Content-Range")
        val parsed = rawRange?.let(contentRange::matchEntire)
        val values = parsed?.groupValues?.drop(1)?.map(::number)
        val valid = values?.all { it != null } == true && values[0]!! <= values[1]!! && values[1]!! < values[2]!!
        val lengthValue = response.header("Content-Length")
        val encoding = response.header("Content-Encoding")
        return JSONObject().put("hop", hop).put("status", response.status).put("parseToRequestMs", parseToRequestMs)
            .put("addressClass", "HTTPS_GOOGLEVIDEO")
            .put("responseLength", number(lengthValue) ?: JSONObject.NULL)
            .put("responseLengthState", if (lengthValue == null) "ABSENT" else if (number(lengthValue) == null) "INVALID" else "NUMERIC")
            .put("contentRangeState", if (rawRange == null) "ABSENT" else if (valid) "NUMERIC" else "INVALID")
            .put("rangeStart", if (valid) values[0] else JSONObject.NULL)
            .put("rangeEnd", if (valid) values[1] else JSONObject.NULL)
            .put("rangeTotal", if (valid) values[2] else JSONObject.NULL)
            .put("contentEncodingClass", if (encoding == null) "ABSENT" else if (encoding.equals("identity", true)) "IDENTITY" else "OTHER")
            .put("transferEncodingPresent", response.header("Transfer-Encoding") != null)
    }

    class Round(val observations: List<Observation>, val stopReason: String) {
        val accepted get() = observations.size == MAX_VARIANTS && observations.all { it.accepted }
        fun json(declared: Long) = JSONObject().put("observations", JSONArray(observations.map { it.json(declared) }))
            .put("stopReason", stopReason).put("boundedObservationsAccepted", accepted)
            .put("downloadSucceeded", false).put("completeTransferAttempted", false)
    }

    fun round(
        track: YouTubeTrack, request: DownloadRecord, transport: HttpTransport,
        resolvedAtMs: Long, clock: () -> Long, scheduler: ScheduledThreadPoolExecutor,
    ): Round {
        val observations = mutableListOf<Observation>()
        for (semantics in Semantics.entries) {
            val observation = probe(track, request, semantics, transport, resolvedAtMs, clock, scheduler)
            observations += observation
            // One access denial is evidence to compare; two consecutive denials add no accepted-byte
            // evidence. Don't spend a third request merely trying another syntax after repeated denial.
            if (observations.size >= 2 && observations.takeLast(2).all { it.accessDenied })
                return Round(observations, "REPEATED_ACCESS_DENIAL_NO_NEW_BYTE_EVIDENCE")
            if (!observation.accepted && !observation.accessDenied)
                return Round(observations, "NON_ACCESS_FAILURE_NO_RETRY")
        }
        return Round(observations, "COMPARISON_BUDGET_EXHAUSTED")
    }
}

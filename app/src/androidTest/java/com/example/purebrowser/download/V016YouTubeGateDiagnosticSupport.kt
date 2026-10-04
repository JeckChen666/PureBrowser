package com.example.purebrowser.download

import com.example.purebrowser.media.site.YouTubeTrack
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only v0.1.6 client-gate observer; not an alternative downloader, not product acceptance.
 * One arm = ONE bounded anonymous media GET (Range bytes=0-65535, body read <=64KiB+1, at most two
 * redirect hops) using the same production media-open/header policy as the V015 transfer support.
 * Reports carry scalar observations and URL query parameter NAMES as booleans only: never a URL,
 * param value, header value, Location, exception text or body bytes. No cookies, credentials,
 * retries or client rotation inside an arm. */
internal object V016YouTubeGateDiagnosticSupport {
    const val MAX_CLIENTS = 4
    const val MAX_REDIRECTS = 2
    const val MAX_HTTP_OPENS_PER_ARM = MAX_REDIRECTS + 1
    const val RANGE_END = 65535
    const val MAX_BODY_BYTES = 64 * 1024 + 1
    const val ARM_DEADLINE_MS = 20_000L
    const val USER_AGENT = "PureBrowser authorized Sintel audit"
    private val redirects = setOf(301, 302, 303, 307, 308)
    private val contentRange = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})")

    enum class Layer {
        RESOLVE, REQUEST_POLICY, NETWORK_OPEN, REDIRECT_POLICY, REDIRECT_LIMIT,
        HTTP_STATUS, BODY_READ, BOUNDED_OBSERVATION, DEADLINE, CANCELLED
    }
    enum class Prefix { NOT_OBSERVED, FTYP_AT_OFFSET_4, OTHER }

    // Only enums / scalars / booleans reach JSON; parameter names never carry their values.
    class Arm(val client: String, val resolveMs: Long) {
        var resolveReason: String? = null
        var layer = Layer.RESOLVE
        var trackItag: String? = null
        var trackHeight = 0
        var declaredLength = 0L
        var urlHasPotParam = false
        var urlHasIpParam = false
        var urlHasCpnParam = false
        var urlHasRatebypassParam = false
        var status: Int? = null
        var redirectsObserved = 0
        var contentRangeTotal: Long? = null
        var contentLengthMatchesDeclared = false
        var bodyBytesRead = 0
        var prefix = Prefix.NOT_OBSERVED
        var rangeHeaderObserved = "bytes=0-$RANGE_END"
        var elapsedMs = 0L
        val hops = mutableListOf<JSONObject>()
        val twoXxOr206 get() = status?.let { it in 200..299 } == true
        fun json() = JSONObject()
            .put("client", client).put("resolveMs", resolveMs)
            .put("resolveReason", resolveReason ?: JSONObject.NULL)
            .put("layer", layer.name)
            .put("trackItag", trackItag ?: JSONObject.NULL).put("trackHeight", trackHeight)
            .put("declaredLength", declaredLength)
            .put("urlHasPotParam", urlHasPotParam).put("urlHasIpParam", urlHasIpParam)
            .put("urlHasCpnParam", urlHasCpnParam).put("urlHasRatebypassParam", urlHasRatebypassParam)
            .put("httpStatus", status ?: JSONObject.NULL).put("redirects", redirectsObserved)
            .put("contentRangeTotal", contentRangeTotal ?: JSONObject.NULL)
            .put("contentLengthMatchesDeclared", contentLengthMatchesDeclared)
            .put("bodyBytesRead", bodyBytesRead).put("prefixFlag", prefix.name)
            .put("elapsedMs", elapsedMs).put("rangeEnd", RANGE_END)
            .put("bodyReadLimit", MAX_BODY_BYTES).put("anonymous", true).put("rangeHeader", rangeHeaderObserved)
            // A bounded prefix observation is never download success.
            .put("downloadSucceeded", false).put("completeTransferAttempted", false)
            .put("hops", JSONArray(hops))
        override fun toString() = "GateArm(client=$client,layer=$layer,status=$status)"
    }

    /** Decoded query parameter NAME membership only; values are never parsed, kept or printed. */
    fun urlHasQueryParam(url: String, name: String): Boolean = URI(url).rawQuery?.split('&')?.any {
        runCatching { URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() }.getOrDefault("") == name
    } ?: false

    /** Sorted distinct decoded query parameter NAMES only; values are never parsed, kept or printed. */
    fun queryParamNames(url: String): List<String> = URI(url).rawQuery?.split('&')?.mapNotNull {
        runCatching { URLDecoder.decode(it.substringBefore('=', ""), "UTF-8").lowercase() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }?.distinct()?.sorted() ?: emptyList()

    private fun validateTrackUrl(url: String) {
        RequestPolicy.validateUrl(url, false)
        val uri = URI(url)
        // Match the resolver's site boundary, while refusing alternate ports / fragments / userinfo.
        check(uri.host.lowercase().endsWith(".googlevideo.com") && uri.port in setOf(-1, 443) &&
            uri.rawFragment == null && uri.rawUserInfo == null)
    }

    fun failedArm(client: String, resolveMs: Long, reason: String): Arm =
        Arm(client, resolveMs).apply { resolveReason = reason }

    fun probe(
        client: String, resolveMs: Long, track: YouTubeTrack, request: DownloadRecord,
        transport: HttpTransport, clock: () -> Long, scheduler: ScheduledThreadPoolExecutor,
        deadlineMs: Long = ARM_DEADLINE_MS, rangeHeader: String = "bytes=0-$RANGE_END",
        queryRange: String? = null, noRange: Boolean = false,
    ): Arm {
        val result = Arm(client, resolveMs)
        result.rangeHeaderObserved = when { noRange -> "none"; queryRange != null -> "query:$queryRange"; else -> rangeHeader }
        val start = clock()
        val token = TransferCancellation()
        val expired = AtomicBoolean(false)
        val deadline = scheduler.schedule({ expired.set(true); token.cancel() }, deadlineMs, TimeUnit.MILLISECONDS)
        var url = track.url
        try {
            result.layer = Layer.REQUEST_POLICY
            validateTrackUrl(url)
            check(track.length > RANGE_END) // the prefix range must stay inside the declared resource
            result.trackItag = track.id; result.trackHeight = track.height; result.declaredLength = track.length
            result.urlHasPotParam = urlHasQueryParam(url, "pot")
            result.urlHasIpParam = urlHasQueryParam(url, "ip")
            result.urlHasCpnParam = urlHasQueryParam(url, "cpn")
            result.urlHasRatebypassParam = urlHasQueryParam(url, "ratebypass")
            for (hop in 0..MAX_REDIRECTS) {
                token.check()
                // Same production media-open/header policy as V015: policy headers only, no
                // cookies/credentials, anonymous UA; the fixed Range is the single added header,
                // unless the arm carries its slice window in the range query parameter instead
                // (the browser-player carrier; the value is our own closed interval).
                val headers = RequestPolicy.headers(request, url, null).toMutableMap()
                check(headers.keys.none { it.equals("Cookie", true) || it.equals("Authorization", true) })
                if (queryRange == null && !noRange) headers["Range"] = rangeHeader
                val target = if (queryRange == null) url else {
                    check(!urlHasQueryParam(url, "range"))
                    val appended = url + (if (URI(url).rawQuery == null) "?" else "&") + "range=$queryRange"
                    validateTrackUrl(appended)
                    appended
                }
                result.layer = Layer.NETWORK_OPEN
                transport.open(target, headers, token).use { response ->
                    result.status = response.status
                    result.hops += hopJson(response, hop)
                    if (response.status in redirects) {
                        result.redirectsObserved++
                        if (hop == MAX_REDIRECTS) { result.layer = Layer.REDIRECT_LIMIT; return result }
                        result.layer = Layer.REDIRECT_POLICY
                        val location = response.header("Location") ?: return result
                        url = RequestPolicy.redirect(url, location, false, false)
                        validateTrackUrl(url)
                    } else {
                        if (response.status !in setOf(200, 206)) { result.layer = Layer.HTTP_STATUS; return result }
                        result.layer = Layer.BODY_READ
                        result.contentRangeTotal = response.header("Content-Range")
                            ?.let(contentRange::matchEntire)?.groupValues?.get(3)?.toLongOrNull()
                        result.contentLengthMatchesDeclared =
                            response.header("Content-Length")?.trim()?.toLongOrNull() == track.length
                        response.body().use { input ->
                            val prefix = ByteArray(MAX_BODY_BYTES)
                            try {
                                while (result.bodyBytesRead < MAX_BODY_BYTES) {
                                    token.check()
                                    val n = input.read(prefix, result.bodyBytesRead, MAX_BODY_BYTES - result.bodyBytesRead)
                                    if (n < 0) break
                                    if (n == 0) continue
                                    result.bodyBytesRead += n
                                }
                                if (result.bodyBytesRead >= 8) result.prefix =
                                    if (String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp") Prefix.FTYP_AT_OFFSET_4 else Prefix.OTHER
                                // Do NOT hash/log/persist the prefix or read beyond the budget.
                            } finally { prefix.fill(0) }
                        }
                        token.check()
                        result.layer = Layer.BOUNDED_OBSERVATION
                        return result
                    }
                }
            }
        } catch (failure: Exception) {
            // Never retain/rethrow exception text/cause: networking/URI errors may embed the signed URL.
            if (expired.get() || failure is SocketTimeoutException || clock() - start >= deadlineMs)
                result.layer = Layer.DEADLINE
            else if (failure is CancellationException) result.layer = Layer.CANCELLED
        } finally {
            token.cancel()
            deadline.cancel(false)
            result.elapsedMs = (clock() - start).coerceAtLeast(0)
        }
        return result
    }

    private fun hopJson(response: HttpResponse, hop: Int): JSONObject = JSONObject()
        .put("hop", hop).put("status", response.status)
        .put("contentRangeState", when {
            response.header("Content-Range") == null -> "ABSENT"
            response.header("Content-Range")?.let(contentRange::matchEntire) != null -> "NUMERIC"
            else -> "INVALID"
        })
}

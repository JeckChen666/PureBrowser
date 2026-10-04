package com.example.purebrowser.media.verify

import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.download.hls.HlsParseException
import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.VariantSummary
import com.example.purebrowser.media.dash.MpdCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Single-worker background queue for anonymous, bounded auto-verification. All IO runs through
 * [UrlFetcher]; budgets reset per epoch, stale epochs are dropped at submit and at delivery, and
 * results are handed to [onResult] from the worker thread only for the newest epoch.
 */
class AutoProbeQueue(
    private val scope: CoroutineScope,
    private val fetcher: UrlFetcher,
    private val allowLocalHttp: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var onResult: ((url: String, epoch: Long, result: ProbeResult) -> Unit)? = null

    private val guard = Any()
    @Volatile private var latestEpoch = Long.MIN_VALUE
    private var cooldownEpoch = Long.MIN_VALUE
    private var lastAttempt = hashMapOf<String, Long>()
    private var requests = Channel<ProbeRequest>(Channel.UNLIMITED)
    private var startedOnce = false
    private var worker: Job? = null

    // Worker-only state; the single worker serializes all budget accounting.
    private var activeEpoch = Long.MIN_VALUE
    private var probesUsed = 0
    private var playlistFetchesUsed = 0

    fun start() {
        if (worker?.isActive == true) return
        val channel = synchronized(guard) {
            // First start reuses the constructor channel so submissions made before start() survive.
            if (startedOnce || requests.isClosedForSend) requests = Channel<ProbeRequest>(Channel.UNLIMITED)
            startedOnce = true
            requests
        }
        worker = scope.launch {
            for (request in channel) runCatching { process(request) }
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        synchronized(guard) { requests.close() }
    }

    fun submit(candidateUrl: String, kind: MediaKind, epoch: Long, ctx: SessionContext) {
        val channel = synchronized(guard) {
            if (epoch < latestEpoch) return
            latestEpoch = epoch
            if (epoch != cooldownEpoch) { lastAttempt.clear(); cooldownEpoch = epoch }
            val nowMs = now()
            val last = lastAttempt[candidateUrl]
            if (last != null && nowMs - last < ProbePolicy.COOLDOWN_MS) return
            lastAttempt[candidateUrl] = nowMs
            requests
        }
        channel.trySend(ProbeRequest(candidateUrl, kind, epoch, ctx))
    }

    private fun process(request: ProbeRequest) {
        if (request.epoch != latestEpoch) return
        if (request.epoch != activeEpoch) {
            activeEpoch = request.epoch
            probesUsed = 0
            playlistFetchesUsed = 0
        }
        if (probesUsed >= ProbePolicy.MAX_PROBES_PER_EPOCH) return
        probesUsed++
        var result = cheapProbe(request.url, request.ctx)
        val hlsHint = result is ProbeResult.Verified && result.kindHint == MediaKind.HLS
        // DASH manifests ride the same bounded text fetch and the same playlist budget; a served
        // HLS content type outranks an .mpd-looking URL when the two shape hints disagree.
        val dash = !hlsHint && ((result is ProbeResult.Verified && result.kindHint == MediaKind.DASH) ||
            request.kind == MediaKind.DASH || looksLikeDashManifestUrl(request.url))
        val wantsManifest = dash || hlsHint || request.kind == MediaKind.HLS || looksLikeHlsPlaylistUrl(request.url)
        if (wantsManifest && playlistFetchesUsed < ProbePolicy.MAX_PLAYLIST_FETCHES_PER_EPOCH) {
            playlistFetchesUsed++
            result = if (dash) enrichWithDashManifest(request, result) else enrichWithPlaylist(request, result)
        }
        if (request.epoch != latestEpoch) return
        // Only Verified outcomes earn the page association; it is consent metadata, not evidence.
        val outcome = if (result is ProbeResult.Verified) result.copy(pageUrl = request.ctx.pageUrl) else result
        onResult?.invoke(request.url, request.epoch, outcome)
    }

    /** Anonymous header probe: Range 0-0 only; deep container sniffing stays with the explicit MediaProbe. */
    private fun cheapProbe(url: String, ctx: SessionContext): ProbeResult {
        val opened = openFollowing(url, mapOf("Range" to "bytes=0-0"), ctx) ?: return ProbeResult.Unreachable
        opened.response.use { response ->
            return ProbeInterpreter.interpret(response.status) { response.header(it) }
        }
    }

    private fun enrichWithPlaylist(request: ProbeRequest, current: ProbeResult): ProbeResult {
        val fetched = fetchPlaylistText(request.url, request.ctx) ?: return current
        return try {
            val summaries = HlsPlaylistParser.variantSummaries(fetched.first, fetched.second)
            if (current is ProbeResult.Verified) current.copy(variants = summaries)
            else ProbeResult.Verified(
                totalBytes = null, resumable = false,
                mime = "application/vnd.apple.mpegurl", kindHint = MediaKind.HLS, variants = summaries,
            )
        } catch (e: HlsParseException) {
            // Hard playlist rejects (live, encrypted, unsupported tags) keep the probe verdict honest.
            if (current is ProbeResult.Verified) current.copy(playlistWarning = e.safeReason) else current
        }
    }

    /**
     * Display-tier MPD cataloging: the same bounded playlist fetch lists Representation summaries
     * only — never segment addresses, and DASH download stays unsupported in this version.
     */
    private fun enrichWithDashManifest(request: ProbeRequest, current: ProbeResult): ProbeResult {
        val fetched = fetchPlaylistText(request.url, request.ctx) ?: return current
        val variants = MpdCatalog.representations(fetched.first, fetched.second).map { representation ->
            // No per-variant addresses at this tier; every entry points at the document itself.
            VariantSummary(
                representation.height, representation.bandwidth, representation.codecs,
                url = fetched.second, warning = null,
            )
        }
        // Zero parsed representations still verifies the DASH hint; the surface stays generic.
        return if (current is ProbeResult.Verified) current.copy(kindHint = MediaKind.DASH, variants = variants)
        else ProbeResult.Verified(
            totalBytes = null, resumable = false,
            mime = "application/dash+xml", kindHint = MediaKind.DASH, variants = variants,
        )
    }

    /** Bounded, policy-checked playlist text fetch mirroring HlsHttpClient.text with session headers. */
    private fun fetchPlaylistText(url: String, ctx: SessionContext): Pair<String, String>? {
        val opened = openFollowing(
            url, mapOf("Accept" to "application/vnd.apple.mpegurl,application/x-mpegurl,*/*"), ctx,
        ) ?: return null
        opened.response.use { response ->
            if (response.status !in setOf(200, 206)) return null
            val encoding = response.header("Content-Encoding")
            if (!encoding.isNullOrBlank() && !encoding.equals("identity", true)) return null
            val total = response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
            if (total != null && total > ProbePolicy.PLAYLIST_BYTE_CAP) return null
            val bytes = ByteArrayOutputStream()
            response.body.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > ProbePolicy.PLAYLIST_BYTE_CAP) return null
                    bytes.write(buffer, 0, n)
                }
            }
            if (total != null && bytes.size().toLong() != total) return null
            val decoded = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (_: Exception) { return null }
            return decoded to opened.finalUrl
        }
    }

    /** Session headers mirror RequestPolicy: bounded UA, page referer, cookie only same-origin. */
    private fun requestHeaders(ctx: SessionContext, target: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ctx.userAgent?.filterNot(Char::isISOControl)?.take(1024)?.takeIf { it.isNotEmpty() }?.let { out["User-Agent"] = it }
        out["Accept-Encoding"] = "identity"
        RequestPolicy.referer(ctx.pageUrl, target)?.let { out["Referer"] = it }
        if (RequestPolicy.sameOrigin(ctx.pageUrl, target)) {
            val cookie = ctx.cookie(target)
            if (cookie != null && cookie.length <= 16384 && cookie.none { it.isISOControl() }) out["Cookie"] = cookie
        }
        return out
    }

    /** Redirect chain bounded like MediaProbe; any policy or transport failure means unreachable. */
    private fun openFollowing(initialUrl: String, extraHeaders: Map<String, String>, ctx: SessionContext): Opened? {
        var url = initialUrl
        var hops = 0
        var credentialUsed = false
        while (true) {
            var response: FetchedResponse? = null
            try {
                RequestPolicy.validateUrl(url, allowLocalHttp)
                val headers = requestHeaders(ctx, url) + extraHeaders
                credentialUsed = credentialUsed || headers.containsKey("Cookie")
                response = fetcher.open(url, headers)
                if (response.status in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")
                    response.close()
                    if (hops++ >= ProbePolicy.MAX_REDIRECTS || location.isNullOrBlank()) return null
                    url = RequestPolicy.redirect(url, location, credentialUsed, allowLocalHttp)
                } else return Opened(url, response)
            } catch (_: Exception) {
                response?.let { runCatching { it.close() } }
                return null
            }
        }
    }

    private fun looksLikeHlsPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return ".m3u8" in lower || "mpegurl" in lower
    }

    private fun looksLikeDashManifestUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return ".mpd" in lower || "dash+xml" in lower
    }

    private class Opened(val finalUrl: String, val response: FetchedResponse)

    private data class ProbeRequest(
        val url: String,
        val kind: MediaKind,
        val epoch: Long,
        val ctx: SessionContext,
    ) {
        // The session context (cookie reader) must never surface in diagnostics.
        override fun toString() = "ProbeRequest(url=<redacted>, kind=$kind, epoch=$epoch)"
    }
}

package com.example.purebrowser.media

import com.example.purebrowser.media.verify.ProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Locale

/** Bounded, navigation-scoped store. Called by both WebView workers and main-thread DOM callbacks. */
class ResourceSniffer {
    private var epoch = 0L
    private val entries = linkedMapOf<String, MediaCandidate>()
    private val mutableCandidates = MutableStateFlow<List<MediaCandidate>>(emptyList())
    val candidates = mutableCandidates.asStateFlow()
    private val hookedUrls = hashSetOf<String>()
    // Raw GET addresses of the current page in arrival order; rule-engine input only, never persisted.
    private val recentRequests = ArrayDeque<String>()

    /**
     * Optional parse-on-detection sink. Invoked from observe for http(s) FILE/UNKNOWN/HLS candidates
     * after dedup, at most once per URL per epoch. Runs under this sniffer's monitor: implementations
     * must only enqueue work, never call back into the sniffer synchronously.
     */
    var autoVerifyHook: ((url: String, kind: MediaKind, epoch: Long) -> Unit)? = null

    @Synchronized fun beginPage(): Long {
        epoch++
        entries.clear()
        hookedUrls.clear()
        recentRequests.clear()
        mutableCandidates.value = emptyList()
        return epoch
    }

    @Synchronized fun observe(
        pageEpoch: Long,
        url: String,
        source: Evidence,
        mimeType: String? = null,
        sizeBytes: Long? = null,
        videoElement: Boolean = false,
        title: String? = null,
        frameUrl: String? = null,
        playing: Boolean = false,
        reliableSource: Boolean = false,
        requestHasRange: Boolean = false,
    ) {
        if (pageEpoch != epoch) return
        if (source == Evidence.REQUEST) recordRequest(url)
        val classified = MediaClassifier.classify(url, mimeType, videoElement)
        val kind = (if(source==Evidence.METADATA && classified==MediaKind.FILE)MediaKind.UNKNOWN else classified)
            ?: if(source==Evidence.RULE) MediaKind.UNKNOWN
            else if(source in setOf(Evidence.REQUEST,Evidence.TIMING) && (MediaClassifier.possibleEndpoint(url) || (requestHasRange && !MediaClassifier.isFragmentUrl(url) && runCatching{com.example.purebrowser.download.RequestPolicy.origin(url)!=null && java.net.URI(url).rawUserInfo==null}.getOrDefault(false)))) MediaKind.UNKNOWN else return
        val key = url.substringBefore('#')
        val old = entries[key]
        if (old == null) {
            // Weak endpoint hints may not starve known files/manifests/DOM evidence.
            if(kind==MediaKind.UNKNOWN && (!videoElement || source==Evidence.METADATA) && entries.values.count { it.kind==MediaKind.UNKNOWN && Evidence.DOM !in it.sources }>=32)return
            if(entries.size>=200) {
                val weak=entries.entries.firstOrNull { it.value.kind==MediaKind.UNKNOWN && Evidence.DOM !in it.value.sources }
                if(kind==MediaKind.UNKNOWN || weak==null)return
                entries.remove(weak.key)
            }
        }
        val candidate = MediaCandidate(
            key,
            when {
                // Unknown endpoints keep the stored kind; a probe-verified manifest likewise
                // survives later re-sightings whose URL shape alone still says FILE.
                kind == MediaKind.UNKNOWN && old != null -> old.kind
                old != null && old.probeState == ProbeState.VERIFIED && old.kind == MediaKind.HLS && kind == MediaKind.FILE -> old.kind
                else -> kind
            },
            old?.sources.orEmpty() + source,
            mimeType?.takeIf { it.isNotBlank() } ?: old?.mimeType,
            sizeBytes?.takeIf { it > 0 } ?: old?.sizeBytes,
            title?.takeIf { it.isNotBlank() }?.take(120) ?: old?.title,
            frameUrl ?: old?.frameUrl,
            old?.pageUrl,
            if(source==Evidence.DOM)playing else old?.playing ?: false,
            reliableSource || old?.reliableSource == true,
            old?.totalBytes,
            old?.resumable,
            old?.verifiedMime,
            old?.probeState ?: ProbeState.NONE,
            old?.variants,
        )
        var stored = candidate
        if (old != candidate) {
            entries[key] = candidate
        } else {
            stored = old!!
        }
        val hook = autoVerifyHook
        if (hook != null && stored.kind in setOf(MediaKind.FILE, MediaKind.UNKNOWN, MediaKind.HLS, MediaKind.DASH) &&
            isHttpUrl(stored.url) && hookedUrls.add(key)) {
            // The pending marker is visible even when the observation itself changed nothing.
            if (stored.probeState == ProbeState.NONE) {
                stored = stored.copy(probeState = ProbeState.PENDING)
                entries[key] = stored
            }
            publish()
            hook(key, stored.kind, pageEpoch)
        } else if (old != candidate) {
            publish()
        }
    }

    /**
     * Folds a rule finding's structured format set into an EXISTING candidate's variants (T87).
     * Only the finding's own primary address is a legal target; a manifest/direct kind hint may
     * upgrade an UNKNOWN entry (same conservatism as probe results) but never downgrades a known
     * kind, and a shorter list never displaces variants already attached.
     */
    @Synchronized fun attachRuleVariants(
        pageEpoch: Long,
        url: String,
        kindHint: MediaKind,
        variants: List<VariantSummary>,
    ) {
        if (pageEpoch != epoch || variants.isEmpty()) return
        val key = url.substringBefore('#')
        val old = entries[key] ?: return
        val kind = if (old.kind == MediaKind.UNKNOWN && (kindHint == MediaKind.HLS || kindHint == MediaKind.DASH)) kindHint else old.kind
        val updated = old.copy(
            kind = kind,
            variants = if (variants.size >= (old.variants?.size ?: 0)) variants else old.variants,
        )
        if (updated != old) {
            entries[key] = updated
            publish()
        }
    }

    /** Applies one background verification outcome; stale epochs and unknown URLs are ignored. */
    @Synchronized fun applyProbeResult(url: String, epoch: Long, result: ProbeResult, variants: List<VariantSummary>? = null) {
        if (epoch != this.epoch) return
        val key = url.substringBefore('#')
        val old = entries[key] ?: return
        val updated = when (result) {
            is ProbeResult.Verified -> {
                // Verified manifests upgrade unknown endpoints; DASH stays display-only this version.
                // A content-verified mpegurl body also corrects a master whose URL shape (e.g. a
                // trailing .mp4) pinned it as FILE at first sight — the probe already knows the
                // truth, so the kind follows the served media type, not the suffix (v0.1.7 S-E).
                val verifiedMime = result.mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                val upgraded = when {
                    result.kindHint == MediaKind.HLS && old.kind == MediaKind.UNKNOWN -> MediaKind.HLS
                    result.kindHint == MediaKind.HLS && old.kind == MediaKind.FILE && verifiedMime?.contains("mpegurl") == true -> MediaKind.HLS
                    result.kindHint == MediaKind.DASH && old.kind == MediaKind.UNKNOWN -> MediaKind.DASH
                    else -> old.kind
                }
                old.copy(
                    kind = upgraded,
                    mimeType = when (upgraded) {
                        MediaKind.HLS -> "application/vnd.apple.mpegurl"
                        MediaKind.DASH -> "application/dash+xml"
                        else -> old.mimeType
                    },
                    totalBytes = result.totalBytes ?: old.totalBytes,
                    resumable = result.resumable,
                    verifiedMime = result.mime ?: old.verifiedMime,
                    probeState = ProbeState.VERIFIED,
                    variants = (variants ?: result.variants) ?: old.variants,
                    pageUrl = result.pageUrl ?: old.pageUrl,
                )
            }
            is ProbeResult.NotMedia, is ProbeResult.Unreachable -> old.copy(probeState = ProbeState.FAILED)
        }
        if (updated != old) {
            entries[key] = updated
            publish()
        }
    }

    @Synchronized fun updatePlayback(pageEpoch:Long,playingUrls:Set<String>) {
        if(pageEpoch!=epoch)return
        entries.replaceAll { url,value ->value.copy(playing=url in playingUrls) }
        publish()
    }

    /** Bounded, in-memory window of this page's GET addresses, main document included. */
    private fun recordRequest(url: String) {
        if (url.length > 8192 || !url.startsWith("http", true)) return
        if (recentRequests.size >= 128) recentRequests.removeFirst()
        recentRequests.addLast(url)
    }

    @Synchronized fun recentRequests(): List<String> = recentRequests.toList()

    // Evidence tier first (DOM still beats REQUEST); RankWeight primary signals (variants/length/
    // quality title/playing) break ties WITHIN a tier only; verified remains the last tiebreaker.
    private fun publish() {
        mutableCandidates.value = entries.values.sortedWith(
            compareByDescending<MediaCandidate> { RankWeight.evidenceTier(it) }
                .thenByDescending { RankWeight.primarySignal(it) }
                .thenByDescending { it.probeState == ProbeState.VERIFIED }
        )
    }

    private fun isHttpUrl(url: String): Boolean = runCatching {
        URI(url).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)
}

package com.example.purebrowser.media

import com.example.purebrowser.media.codec.Av1Capability
import com.example.purebrowser.media.codec.Av1CapabilityProvider
import com.example.purebrowser.media.codec.Av1DecodeSupport
import com.example.purebrowser.media.verify.ProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Locale

/** Bounded, navigation-scoped store. Called by both WebView workers and main-thread DOM callbacks. */
class ResourceSniffer(
    /**
     * T110 AV1 runtime gating at the candidate layer. The default keeps the pre-T110 behavior for
     * unmigrated/test constructions (AV1 entries stay listed); production injects the device
     * provider so HIDDEN devices never see AV1 variants and software-decode devices get the
     * performance warning in [VariantSummary.warning].
     */
    private val av1: Av1CapabilityProvider = Av1CapabilityProvider { Av1DecodeSupport.AVAILABLE },
) {
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
        rawVariants: List<VariantSummary>,
    ) {
        if (pageEpoch != epoch || rawVariants.isEmpty()) return
        // T110: rule-sourced variants pass the same AV1 gate before they can surface. An all-AV1
        // list on a HIDDEN device gates to nothing, which is the intended absence, not an update.
        val variants = Av1Capability.applyToSummaries(rawVariants, av1.support())
        if (variants.isEmpty()) return
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
    @Synchronized fun applyProbeResult(url: String, epoch: Long, result: ProbeResult, rawVariants: List<VariantSummary>? = null) {
        if (epoch != this.epoch) return
        val key = url.substringBefore('#')
        val old = entries[key] ?: return
        // T110: both variant entry paths (probe-embedded and explicit override) pass the AV1 gate.
        val support = av1.support()
        val override = rawVariants?.let { Av1Capability.applyToSummaries(it, support) }
        val updated = when (result) {
            is ProbeResult.Verified -> {
                // T110: probe-sourced variant lists pass the AV1 gate here — the candidate layer,
                // after the pure parse, never inside HlsPlaylistParser/MpdCatalog.
                val gated = result.variants?.let { Av1Capability.applyToSummaries(it, support) }
                val verifiedResult = if (gated != null && gated !== result.variants) result.copy(variants = gated) else result
                // Verified manifests upgrade unknown endpoints; the DASH listing stays display-only
                // here, resolution/selection moved to the T97 DASH confirmation dialog.
                // A content-verified mpegurl body also corrects a master whose URL shape (e.g. a
                // trailing .mp4) pinned it as FILE at first sight — the probe already knows the
                // truth, so the kind follows the served media type, not the suffix (v0.1.7 S-E).
                val verifiedMime = verifiedResult.mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                val upgraded = when {
                    verifiedResult.kindHint == MediaKind.HLS && old.kind == MediaKind.UNKNOWN -> MediaKind.HLS
                    verifiedResult.kindHint == MediaKind.HLS && old.kind == MediaKind.FILE && verifiedMime?.contains("mpegurl") == true -> MediaKind.HLS
                    verifiedResult.kindHint == MediaKind.DASH && old.kind == MediaKind.UNKNOWN -> MediaKind.DASH
                    else -> old.kind
                }
                old.copy(
                    kind = upgraded,
                    mimeType = when (upgraded) {
                        MediaKind.HLS -> "application/vnd.apple.mpegurl"
                        MediaKind.DASH -> "application/dash+xml"
                        else -> old.mimeType
                    },
                    totalBytes = verifiedResult.totalBytes ?: old.totalBytes,
                    resumable = verifiedResult.resumable,
                    verifiedMime = verifiedResult.mime ?: old.verifiedMime,
                    probeState = ProbeState.VERIFIED,
                    variants = (override ?: gated) ?: old.variants,
                    pageUrl = verifiedResult.pageUrl ?: old.pageUrl,
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

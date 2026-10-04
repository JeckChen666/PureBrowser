package com.example.purebrowser.media

import com.example.purebrowser.media.verify.ProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Bounded, navigation-scoped store. Called by both WebView workers and main-thread DOM callbacks. */
class ResourceSniffer {
    private var epoch = 0L
    private val entries = linkedMapOf<String, MediaCandidate>()
    private val mutableCandidates = MutableStateFlow<List<MediaCandidate>>(emptyList())
    val candidates = mutableCandidates.asStateFlow()
    private val hookedUrls = hashSetOf<String>()

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
        val classified = MediaClassifier.classify(url, mimeType, videoElement)
        val kind = (if(source==Evidence.METADATA && classified==MediaKind.FILE)MediaKind.UNKNOWN else classified)
            ?: if(source in setOf(Evidence.REQUEST,Evidence.TIMING) && (MediaClassifier.possibleEndpoint(url) || (requestHasRange && !MediaClassifier.isFragmentUrl(url) && runCatching{com.example.purebrowser.download.RequestPolicy.origin(url)!=null && java.net.URI(url).rawUserInfo==null}.getOrDefault(false)))) MediaKind.UNKNOWN else return
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
            if (kind == MediaKind.UNKNOWN && old != null) old.kind else kind,
            old?.sources.orEmpty() + source,
            mimeType?.takeIf { it.isNotBlank() } ?: old?.mimeType,
            sizeBytes?.takeIf { it > 0 } ?: old?.sizeBytes,
            title?.takeIf { it.isNotBlank() }?.take(120) ?: old?.title,
            frameUrl ?: old?.frameUrl,
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
        if (hook != null && stored.kind in setOf(MediaKind.FILE, MediaKind.UNKNOWN, MediaKind.HLS) &&
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

    /** Applies one background verification outcome; stale epochs and unknown URLs are ignored. */
    @Synchronized fun applyProbeResult(url: String, epoch: Long, result: ProbeResult, variants: List<VariantSummary>? = null) {
        if (epoch != this.epoch) return
        val key = url.substringBefore('#')
        val old = entries[key] ?: return
        val updated = when (result) {
            is ProbeResult.Verified -> {
                val upgraded = result.kindHint == MediaKind.HLS && old.kind == MediaKind.UNKNOWN
                old.copy(
                    kind = if (upgraded) MediaKind.HLS else old.kind,
                    mimeType = if (upgraded) "application/vnd.apple.mpegurl" else old.mimeType,
                    totalBytes = result.totalBytes ?: old.totalBytes,
                    resumable = result.resumable,
                    verifiedMime = result.mime ?: old.verifiedMime,
                    probeState = ProbeState.VERIFIED,
                    variants = (variants ?: result.variants) ?: old.variants,
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

    // Verified entries outrank unverified ones only inside the same evidence tier.
    private fun publish() {
        mutableCandidates.value = entries.values.sortedWith(
            compareByDescending<MediaCandidate> {
                when { it.playing && Evidence.DOM in it.sources -> 4; Evidence.DOM in it.sources -> 3
                    Evidence.DOWNLOAD in it.sources -> 2; else -> 1 }
            }.thenByDescending { it.probeState == ProbeState.VERIFIED }
        )
    }

    private fun isHttpUrl(url: String): Boolean = runCatching {
        URI(url).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)
}

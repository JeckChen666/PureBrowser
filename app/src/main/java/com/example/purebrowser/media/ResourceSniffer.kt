package com.example.purebrowser.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, navigation-scoped store. Called by both WebView workers and main-thread DOM callbacks. */
class ResourceSniffer {
    private var epoch = 0L
    private val entries = linkedMapOf<String, MediaCandidate>()
    private val mutableCandidates = MutableStateFlow<List<MediaCandidate>>(emptyList())
    val candidates = mutableCandidates.asStateFlow()

    @Synchronized fun beginPage(): Long {
        epoch++
        entries.clear()
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
        )
        if (old != candidate) {
            entries[key] = candidate
            mutableCandidates.value = entries.values.sortedByDescending {
                when { it.playing && Evidence.DOM in it.sources -> 4; Evidence.DOM in it.sources -> 3
                    Evidence.DOWNLOAD in it.sources -> 2; else -> 1 }
            }
        }
    }

    @Synchronized fun updatePlayback(pageEpoch:Long,playingUrls:Set<String>) {
        if(pageEpoch!=epoch)return
        entries.replaceAll { url,value ->value.copy(playing=url in playingUrls) }
        mutableCandidates.value=entries.values.sortedByDescending {
            when { it.playing && Evidence.DOM in it.sources->4;Evidence.DOM in it.sources->3;Evidence.DOWNLOAD in it.sources->2;else->1 }
        }
    }
}

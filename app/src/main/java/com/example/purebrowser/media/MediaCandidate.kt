package com.example.purebrowser.media

import java.net.URI
import java.util.Locale

enum class MediaKind(val label: String) {
    FILE("视频直链"), HLS("HLS 清单"), DASH("DASH 清单"), LOCAL("页面本地媒体"), UNKNOWN("待确认媒体")
}
enum class Evidence(val label: String) {
    REQUEST("网络请求"), DOM("视频元素"), TIMING("资源时间线"), DOWNLOAD("下载回调"), PROBE("受控媒体分析"), SITE("站点解析"), METADATA("页面媒体声明")
}
/** Background auto-verification state; NONE keeps the pre-verification behavior unchanged. */
enum class ProbeState { NONE, PENDING, VERIFIED, FAILED }

/** Surface-level master-playlist entry for parse-on-detection; warning marks a selectable-but-cautioned variant. */
data class VariantSummary(
    val height: Int?,
    val bandwidth: Long?,
    val codecs: String?,
    val url: String,
    val warning: String?,
) {
    // Never print a signed variant address or codec string in diagnostics.
    override fun toString() = "VariantSummary(height=$height, bandwidth=$bandwidth, warned=${warning != null})"
}

data class MediaCandidate(
    val url: String,
    val kind: MediaKind,
    val sources: Set<Evidence>,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val title: String? = null,
    val frameUrl: String? = null,
    /** Page whose background probe verified this URL; merge keeps the latest non-null address. */
    val pageUrl: String? = null,
    val playing: Boolean = false,
    val reliableSource: Boolean = false,
    val totalBytes: Long? = null,
    val resumable: Boolean? = null,
    val verifiedMime: String? = null,
    val probeState: ProbeState = ProbeState.NONE,
    val variants: List<VariantSummary>? = null,
) {
    val displayName: String get() = title?.takeIf { it.isNotBlank() }?.take(120) ?: runCatching {
        URI(url).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }.getOrNull()?.take(120) ?: kind.label
    // Do not expose signed query strings in the normal resource panel.
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: "当前页面"
}

/** Identifies candidates, not proof of a full, downloadable movie. Original query strings survive. */
object MediaClassifier {
    private val fileExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv")
    private val fragments = setOf("ts", "m4s", "aac", "vtt", "srt")

    fun isFragmentUrl(url: String): Boolean {
        val path=runCatching { URI(url).path.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT) }.getOrDefault("")
        val ext=path.substringAfterLast('.', "")
        return ext in fragments || (ext=="mp4" && Regex("^(init(?:[-_.].*)?|(?:seg(?:ment)?|chunk|frag(?:ment)?)[-_.]?\\d.*)\\.mp4$").matches(path))
    }

    /** Weak endpoint evidence only; never a verified video or automatic network probe. */
    fun possibleEndpoint(url: String): Boolean {
        val uri=runCatching { URI(url) }.getOrNull() ?: return false
        if(uri.scheme?.lowercase(Locale.ROOT) !in setOf("http","https") || uri.host.isNullOrEmpty() || uri.rawUserInfo!=null || url.length>8192)return false
        val path=uri.path.orEmpty().lowercase(Locale.ROOT)
        if(isFragmentUrl(url))return false
        val leaf=path.substringAfterLast('/')
        val ext=leaf.substringAfterLast('.', "")
        if(leaf.contains('.') && ext !in setOf("php","asp","aspx","ashx"))return false
        val endpoint=path.replace(Regex("\\.(?:php|asp|aspx|ashx)$"), "")
        if(Regex("(?:^|/)(?:video(?:playback)?|media|stream|play|download|serve)(?:/|$)").containsMatchIn(endpoint))return true
        return uri.rawQuery.orEmpty().split('&').any { part ->
            val pair=part.split('=',limit=2)
            pair.size==2 && pair[0] in setOf("mime","type","content_type") && runCatching {
                val value=java.net.URLDecoder.decode(pair[1],"UTF-8").lowercase(Locale.ROOT)
                value in setOf("video/mp4","video/webm","audio/mp4","application/vnd.apple.mpegurl","application/dash+xml")
            }.getOrDefault(false)
        }
    }

    fun classify(url: String, mimeType: String? = null, videoElement: Boolean = false): MediaKind? {
        if (url.length > 8192) return null
        if (url.startsWith("blob:", true)) return if (videoElement) MediaKind.LOCAL else null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        val extension = uri.path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
        val name = uri.path.orEmpty().substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
        val obviousMp4Fragment = extension == "mp4" && Regex("^(init(?:[-_.].*)?|(?:seg(?:ment)?|chunk|frag(?:ment)?)[-_.]?\\d.*)$").matches(name)
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            extension == "m3u8" || mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl") -> MediaKind.HLS
            extension == "mpd" || mime == "application/dash+xml" -> MediaKind.DASH
            extension in fragments || mime == "video/mp2t" || obviousMp4Fragment -> null
            extension in fileExtensions || mime.startsWith("video/") -> MediaKind.FILE
            videoElement -> MediaKind.UNKNOWN
            else -> null
        }
    }
}

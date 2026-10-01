package com.example.purebrowser.media

import java.net.URI
import java.util.Locale

enum class MediaKind(val label: String) {
    FILE("视频直链"), HLS("HLS 清单"), DASH("DASH 清单"), LOCAL("页面本地媒体"), UNKNOWN("待确认媒体")
}
enum class Evidence(val label: String) {
    REQUEST("网络请求"), DOM("视频元素"), TIMING("资源时间线"), DOWNLOAD("下载回调")
}
data class MediaCandidate(
    val url: String,
    val kind: MediaKind,
    val sources: Set<Evidence>,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
) {
    val displayName: String get() = runCatching {
        URI(url).path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }.getOrNull()?.take(120) ?: kind.label
    // Do not expose signed query strings in the normal resource panel.
    val host: String get() = runCatching { URI(url).host }.getOrNull() ?: "当前页面"
}

/** Identifies candidates, not proof of a full, downloadable movie. Original query strings survive. */
object MediaClassifier {
    private val fileExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv")
    private val fragments = setOf("ts", "m4s", "aac", "vtt", "srt")

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

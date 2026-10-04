package com.example.purebrowser.ui.resources

import android.webkit.URLUtil
import com.example.purebrowser.download.DownloadRules
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.net.URI
import java.util.Locale

/** Presentation only: no requests, probing, or changes to the original candidate URL. */
internal fun MediaCandidate.canTryDownload(): Boolean = kind in setOf(MediaKind.FILE,MediaKind.UNKNOWN,MediaKind.HLS) && runCatching {
    val uri = URI(url)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535
}.getOrDefault(false)

internal fun MediaCandidate.unsupportedExplanation(): String = when {
    url.startsWith("blob:", ignoreCase = true) || kind == MediaKind.LOCAL ->
        "这是播放器在当前页面中创建的本地媒体地址，不是独立文件直链。请播放视频后，再查看是否发现底层视频直链。"
    kind == MediaKind.HLS ->
        "这是 HLS 播放清单。可显式解析未加密的固定点播 MPEG-TS（H.264 / AAC），选择受支持档位并准备后保存为 MP4；不支持直播、DRM、独立音轨或 fMP4。"
    kind == MediaKind.DASH ->
        "这是 DASH 播放清单，音频和视频可能分开传输。目前不支持分片下载与音视频合并。"
    kind == MediaKind.FILE ->
        "此地址不是可交给受控下载器的 HTTP / HTTPS 文件直链，暂时不能尝试保存。"
    else ->
        "已发现媒体线索，但尚未确认文件类型。请播放视频后重新查看；可尝试保存，响应不是完整 MP4/WebM 时会拒绝入库。"
}

internal fun MediaCandidate.resourceType(): String {
    // MIME metadata is untrusted. Do not turn a URL or arbitrary header into normal-list text.
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.length <= 80 && Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(it) }
    return if (mime == null) kind.label else "${kind.label} · $mime"
}

internal fun MediaCandidate.reliableSizeLabel(): String {
    val bytes = sizeBytes?.takeIf { it >= 0 } ?: return "大小未知"
    if (bytes < 1024) return "响应大小 ${bytes} B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "响应大小 ${String.format(Locale.getDefault(), "%.1f", value)} ${units[index]}"
}

internal fun sourceHost(url: String?): String? = url?.let {
    runCatching { URI(it).host }.getOrNull()?.takeIf(String::isNotBlank)
}

internal fun suggestedFileName(candidate: MediaCandidate): String {
    if(com.example.purebrowser.media.Evidence.SITE in candidate.sources && !candidate.title.isNullOrBlank())return DownloadRules.safeFileName(candidate.title.take(100)+".mp4")
    val guessed = runCatching { URLUtil.guessFileName(candidate.url, null, candidate.mimeType) }
        .getOrNull()?.takeIf(String::isNotBlank) ?: candidate.displayName
    return DownloadRules.safeFileName(guessed)
}

internal fun readableResourceName(value: String): String = value
    .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    .ifBlank { "未命名媒体" }

/** Stable action identity, independent of duplicate page titles, without putting signed URLs in tags. */
internal fun resourceSaveTag(url:String):String = "resource-save-"+java.security.MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun resourceAnalyzeTag(url:String)=resourceSaveTag(url).replace("resource-save-","resource-analyze-")

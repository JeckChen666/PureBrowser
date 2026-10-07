package com.example.purebrowser.ui.resources

import android.webkit.URLUtil
import com.example.purebrowser.download.DownloadRules
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.net.URI
import java.util.Locale

/** Presentation only: no requests, probing, or changes to the original candidate URL. */
internal fun MediaCandidate.canTryDownload(): Boolean = kind in setOf(MediaKind.FILE,MediaKind.UNKNOWN,MediaKind.HLS,MediaKind.DASH) && runCatching {
    val uri = URI(url)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535
}.getOrDefault(false)

/** T118 user-facing kind labels; the underlying kind stays available inside 技术详情. */
internal fun MediaKind.userLabel(): String = when (this) {
    MediaKind.FILE -> "视频文件"
    MediaKind.HLS -> "播放地址（HLS）"
    MediaKind.DASH -> "播放地址（DASH）"
    MediaKind.LOCAL -> "页面内媒体"
    MediaKind.UNKNOWN -> "待确认"
}

internal fun MediaCandidate.unsupportedExplanation(): String = when {
    url.startsWith("blob:", ignoreCase = true) || kind == MediaKind.LOCAL ->
        "这是播放器在页面里临时创建的地址，还不是能直接保存的视频文件。请先播放视频，稍后再看看有没有出现可保存的视频文件。"
    kind == MediaKind.HLS ->
        "这是一条 HLS 播放地址，确认后可以选择清晰度，保存为一个 MP4 视频。直播和受站点保护的内容暂不支持。"
    kind == MediaKind.DASH ->
        "这是一条 DASH 播放地址，确认后可以选择清晰度，视频和声音会合并保存为一个 MP4 视频。个别特殊格式暂不支持。"
    kind == MediaKind.FILE ->
        "这还不是可保存的视频文件地址，暂时无法保存。"
    else ->
        "发现了视频线索，但还不能确定它是什么。可以先播放视频后再回来看，或点“分析媒体”检查；检查不通过就不会保存。"
}

// tech-detail:begin
// Protocol boundary wording folded out of primary copy (T118): shown only inside 技术详情.
internal fun MediaCandidate.technicalBoundary(): String? = when (kind) {
    MediaKind.HLS ->
        "HLS 播放清单：仅支持未加密的固定点播 MPEG-TS（H.264 / AAC），封装校验后保存为独立 MP4；不支持直播、DRM、独立音轨或 fMP4。"
    MediaKind.DASH ->
        "DASH 播放清单：仅支持未加密的固定点播 fMP4 DASH（H.264 / AAC），音视频分轨下载后合并为 MP4；不支持直播、DRM、AV1/HEVC 或 indexRange 单文件形式。"
    MediaKind.LOCAL -> "页面本地媒体（blob/本地地址），不是可独立请求的文件。"
    else -> null
}
// tech-detail:end

internal fun MediaCandidate.resourceType(): String {
    // MIME metadata is untrusted. Do not turn a URL or arbitrary header into normal-list text.
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        ?.takeIf { it.length <= 80 && Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(it) }
    return if (mime == null) kind.userLabel() else "${kind.userLabel()} · $mime"
}

internal fun MediaCandidate.reliableSizeLabel(): String {
    val bytes = sizeBytes?.takeIf { it >= 0 } ?: return "大小未知"
    return "大小 ${formatByteSize(bytes)}"
}

/** Same byte formatting for observed response sizes and auto-verified total sizes. */
internal fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "${String.format(Locale.getDefault(), "%.1f", value)} ${units[index]}"
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

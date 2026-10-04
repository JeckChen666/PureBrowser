package com.example.purebrowser.media.fingerprint

import com.example.purebrowser.media.MediaKind
import java.net.URI
import java.util.Locale

data class DiscoveredSource(
    val url: String,
    val qualityLabel: String?,
    val kindHint: MediaKind,
    val family: PlayerFamily,
    val origin: String,
) {
    companion object {
        private val fileExtensions = setOf("mp4", "flv", "webm", "m4v", "mov", "mkv")

        /** Cheap path-based hint only; other manifest families stay UNKNOWN so downstream probes decide. */
        fun kindFor(url: String): MediaKind {
            val extension = runCatching {
                URI(url).path.orEmpty().substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)
            }.getOrDefault("")
            return when {
                extension == "m3u8" -> MediaKind.HLS
                extension in fileExtensions -> MediaKind.FILE
                else -> MediaKind.UNKNOWN
            }
        }
    }
}

package com.example.purebrowser.download

import com.example.purebrowser.browser.BrowserAddress
import com.example.purebrowser.media.MediaCandidate
import java.net.URI
import java.util.UUID

/** Frozen at resource selection, not inferred from whichever tab is active later. */
data class DownloadDraft(
    val candidate: MediaCandidate,
    val userAgent: String,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val sourceTabId: String? = null,
    val sourceGeneration: Long? = null,
) {
    override fun toString() = "DownloadDraft(kind=${candidate.kind}, sourceGeneration=$sourceGeneration)"
}

data class DownloadRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val systemId: Long,
    val name: String,
    val displayName: String = name,
    val mediaUrl: String? = null,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val createdAt: Long? = null,
    val userAgent: String? = null,
    val wifiOnly: Boolean? = null,
    val mimeType: String? = null,
    val retryOf: String? = null,
    val sourceTabId: String? = null,
    val sourceGeneration: Long? = null,
    val cancelled: Boolean = false,
) {
    override fun toString() = "DownloadRecord(recordId=$recordId, systemId=$systemId)"
}

enum class SystemTaskRead { PRESENT, MISSING, UNAVAILABLE }

enum class FormatCheck { NOT_CHECKED, PASSED, INVALID, UNCONFIRMED }
enum class FileAvailability { AVAILABLE, MISSING, UNREADABLE, UNKNOWN }

/** A file index, not an extra copy of the video. Transport state is kept separately. */
data class VideoAsset(
    val recordId: String,
    val systemId: Long,
    val uri: String,
    val name: String,
    val displayName: String,
    val indexedAt: Long,
    val systemUpdatedAt: Long? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val format: FormatCheck = FormatCheck.NOT_CHECKED,
    val availability: FileAvailability = FileAvailability.UNKNOWN,
    val durationMillis: Long? = null,
)

data class DownloadData(
    val records: List<DownloadRecord> = emptyList(),
    val assets: List<VideoAsset> = emptyList(),
    val legacyMigrationDone: Boolean = true,
)

data class DownloadItem(
    val id: Long,
    val name: String,
    val status: Int,
    val bytes: Long,
    val total: Long,
    val detail: String,
    val verified: Boolean = false,
    val recordId: String = "",
    val format: FormatCheck = FormatCheck.NOT_CHECKED,
    val availability: FileAvailability = FileAvailability.UNKNOWN,
    val sourceUrl: String? = null,
    val systemRead: SystemTaskRead = SystemTaskRead.PRESENT,
    val displayName: String = name,
    val createdAt: Long? = null,
    val wifiOnly: Boolean? = null,
    val canRetry: Boolean = false,
    val retryOf: String? = null,
    val sourceTitle: String? = null,
    val cancelled: Boolean = false,
) {
    override fun toString() = "DownloadItem(id=$id, status=$status, systemRead=$systemRead, format=$format, availability=$availability)"
}

data class DownloadState(val tasks: List<DownloadItem>, val assets: List<VideoAsset>)

object DownloadRules {
    const val MAX_RECORDS = 200
    const val MAX_FILE_BYTES = 8 * 1024 * 1024

    fun safeFileName(value: String): String {
        val filtered=value.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val output=StringBuilder()
        val points=filtered.codePoints().iterator()
        var bytes=0
        while(points.hasNext()) {
            val point=points.nextInt()
            val part=String(Character.toChars(point))
            val size=part.toByteArray(Charsets.UTF_8).size
            // Reserve room for the unique prefix; never split a Unicode code point.
            if(output.length+part.length>100 || bytes+size>240) break
            output.append(part);bytes+=size
        }
        return output.toString().takeUnless { it.isBlank() || it=="." || it==".." } ?: "video.mp4"
    }

    fun isOwnedDownloadUri(value: String, id: Long): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "content" && uri.authority == "downloads" && uri.rawQuery == null &&
            uri.rawFragment == null && uri.path in setOf("/all_downloads/$id", "/my_downloads/$id", "/public_downloads/$id")
    }.getOrDefault(false)

    fun validate(data: DownloadData) {
        require(data.legacyMigrationDone)
        require(data.records.size <= MAX_RECORDS && data.assets.size <= MAX_RECORDS)
        require(data.records.map { it.recordId }.distinct().size == data.records.size)
        require(data.records.map { it.systemId }.distinct().size == data.records.size)
        require(data.assets.map { it.recordId }.distinct().size == data.assets.size)
        data.records.forEach { r ->
            require(r.recordId.isNotBlank() && r.recordId.length <= 100 && r.systemId > 0)
            require(r.name.isNotBlank() && r.name.length <= 200 && r.name !in setOf(".", "..") &&
                r.name.none { it == '/' || it == '\\' || it.isISOControl() })
            require(r.displayName.isNotBlank() && r.displayName.length <= 180 && r.displayName.none { it.isISOControl() })
            require(r.mediaUrl == null || BrowserAddress.isWebUrl(r.mediaUrl))
            require(r.sourceUrl == null || BrowserAddress.isWebUrl(r.sourceUrl))
            require(r.sourceTitle == null || r.sourceTitle.length <= 180)
            require(r.userAgent == null || (r.userAgent.length <= 1024 && r.userAgent.none { it.isISOControl() }))
            require(r.createdAt == null || r.createdAt >= 0)
            require(r.sourceGeneration == null || r.sourceGeneration >= 0)
            require(r.mimeType == null || r.mimeType.length <= 120)
            require(r.retryOf == null || r.retryOf.length <= 100)
            require(r.sourceTabId == null || r.sourceTabId.length <= 100)
        }
        data.assets.forEach { a ->
            val record = data.records.firstOrNull { it.recordId == a.recordId }
            require(record != null && record.systemId == a.systemId)
            require(isOwnedDownloadUri(a.uri, a.systemId))
            require(a.name == record.name && a.displayName == record.displayName)
            require(a.indexedAt >= 0 && (a.systemUpdatedAt == null || a.systemUpdatedAt >= 0))
            require(a.sizeBytes == null || a.sizeBytes >= 0)
            require(a.durationMillis == null || a.durationMillis >= 0)
            require(a.mimeType == null || a.mimeType.length <= 120)
        }
    }

    fun forget(data: DownloadData, systemId: Long): DownloadData = data.copy(
        records = data.records.filterNot { it.systemId == systemId },
        assets = data.assets.filterNot { it.systemId == systemId },
    )

    fun supportedHeader(prefix: ByteArray): Boolean =
        (prefix.size >= 8 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp") ||
            (prefix.size >= 4 && prefix.take(4).map { it.toInt() and 0xff } == listOf(0x1a, 0x45, 0xdf, 0xa3))
}

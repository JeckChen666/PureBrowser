package com.example.purebrowser.download

import com.example.purebrowser.browser.BrowserAddress
import com.example.purebrowser.media.MediaCandidate
import java.net.URI
import java.util.UUID

typealias TaskId = String
enum class DownloadProtocol { DIRECT, HLS }
enum class TransferType { SYSTEM, CONTROLLED }
enum class TaskStatus { QUEUED, WAITING_WIFI, WAITING_NETWORK, PAUSING, PAUSED, RUNNING, MUXING, VERIFYING, PUBLISHING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }
enum class PauseReason { USER, WIFI, NETWORK, SYSTEM, RECOVERY, STORAGE, ACCESS, SOURCE_CHANGED }
enum class FailureKind { NETWORK, HTTP_REJECTED, ACCESS_CONDITION, NOT_VIDEO, UNSUPPORTED, STORAGE, SYSTEM_LIMIT, INTERRUPTED }
enum class AssetLocation { SYSTEM_DOWNLOAD, MEDIASTORE_DOWNLOAD, LEGACY_PUBLIC_FILE }

/** Frozen at resource selection, not inferred from whichever tab is active later. */
data class DownloadDraft(
    val candidate: MediaCandidate,
    val userAgent: String,
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val sourceTabId: String? = null,
    val sourceGeneration: Long? = null,
    val useAccessContext: Boolean = true,
    val frameUrl: String? = candidate.frameUrl,
    val reliableSource: Boolean = candidate.reliableSource,
) {
    override fun toString() = "DownloadDraft(kind=${candidate.kind}, sourceGeneration=$sourceGeneration)"
}

data class DownloadRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val systemId: Long? = null,
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
    val transfer: TransferType = TransferType.SYSTEM,
    val taskStatus: TaskStatus = TaskStatus.QUEUED,
    val received: Long = 0,
    val expected: Long? = null,
    val failure: FailureKind? = null,
    val useAccessContext: Boolean = false,
    val frameUrl: String? = null,
    val reliableSource: Boolean = false,
    val pendingUri: String? = null,
    val protocol: DownloadProtocol = DownloadProtocol.DIRECT,
    val hlsPlaylistUrl: String? = null,
    val hlsWidth: Int? = null,
    val hlsHeight: Int? = null,
    val hlsBandwidth: Long? = null,
    val plannedDurationUs: Long? = null,
    val segmentCount: Int? = null,
    val completedSegments: Int = 0,
    val safeFailure: String? = null,
    val pauseReason: PauseReason? = null,
    val resumeAvailable: Boolean = false,
) {
    override fun toString() = "DownloadRecord(recordId=$recordId, systemId=$systemId)"
}

enum class SystemTaskRead { PRESENT, MISSING, UNAVAILABLE }

enum class FormatCheck { NOT_CHECKED, PASSED, INVALID, UNCONFIRMED }
enum class FileAvailability { AVAILABLE, MISSING, UNREADABLE, UNKNOWN }

/** A file index, not an extra copy of the video. Transport state is kept separately. */
data class VideoAsset(
    val recordId: String,
    val systemId: Long? = null,
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
    val location: AssetLocation = AssetLocation.SYSTEM_DOWNLOAD,
)

data class DownloadData(
    val records: List<DownloadRecord> = emptyList(),
    val assets: List<VideoAsset> = emptyList(),
    val legacyMigrationDone: Boolean = true,
)

data class DownloadItem(
    val id: TaskId,
    val name: String,
    val status: Int,
    val bytes: Long,
    val total: Long,
    val detail: String,
    val verified: Boolean = false,
    val recordId: String = id,
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
    val taskStatus: TaskStatus? = null,
    val failure: FailureKind? = null,
    val useAccessContext: Boolean = false,
    val protocol: DownloadProtocol = DownloadProtocol.DIRECT,
    val segmentCount: Int? = null,
    val completedSegments: Int = 0,
    val pauseReason: PauseReason? = null,
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val cacheBytes: Long = 0,
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

    fun isLocalAssetUri(value:String,packageName:String):Boolean = runCatching {
        val uri=URI(value)
        uri.scheme=="content" && uri.rawQuery==null && uri.rawFragment==null &&
            ((uri.authority=="downloads" && Regex("/(all_downloads|my_downloads|public_downloads)/[1-9][0-9]*").matches(uri.path)) ||
             (uri.authority=="media" && Regex("/external_primary/downloads/[1-9][0-9]*").matches(uri.path)) ||
             (uri.authority=="$packageName.files" && uri.path.startsWith("/downloads/") && uri.path.count { it=='/' }==2))
    }.getOrDefault(false)

    fun validate(data: DownloadData) {
        require(data.legacyMigrationDone)
        require(data.records.size <= MAX_RECORDS && data.assets.size <= MAX_RECORDS)
        require(data.records.map { it.recordId }.distinct().size == data.records.size)
        require(data.records.mapNotNull { it.systemId }.distinct().size == data.records.count { it.systemId != null })
        require(data.assets.map { it.recordId }.distinct().size == data.assets.size)
        data.records.forEach { r ->
            require(r.recordId.isNotBlank() && r.recordId.length <= 100 && (r.systemId == null || r.systemId > 0))
            require((r.transfer == TransferType.SYSTEM) == (r.systemId != null))
            require(r.protocol != DownloadProtocol.HLS || r.transfer == TransferType.CONTROLLED)
            require(r.hlsPlaylistUrl == null || (r.hlsPlaylistUrl.length<=8192 && BrowserAddress.isWebUrl(r.hlsPlaylistUrl)))
            require(r.segmentCount == null || r.segmentCount in 1..10000)
            require(r.completedSegments >= 0 && r.completedSegments <= (r.segmentCount ?: 0))
            require(r.plannedDurationUs == null || r.plannedDurationUs in 1..86_400_000_000L)
            require(r.hlsWidth == null || r.hlsWidth in 1..16384)
            require(r.hlsHeight == null || r.hlsHeight in 1..16384)
            require(r.hlsBandwidth == null || r.hlsBandwidth > 0)
            require(r.safeFailure == null || (r.safeFailure.length <= 180 && !r.safeFailure.contains("://") && r.safeFailure.none { it.isISOControl() }))
            require(r.received >= 0 && (r.expected == null || r.expected >= 0))
            require(r.frameUrl == null || BrowserAddress.isWebUrl(r.frameUrl))
            require(r.pendingUri == null || (r.transfer == TransferType.CONTROLLED && URI(r.pendingUri).scheme == "content"))
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
            require((record.transfer==TransferType.SYSTEM)==(a.location==AssetLocation.SYSTEM_DOWNLOAD))
            require((if (a.location == AssetLocation.SYSTEM_DOWNLOAD) a.systemId != null && isOwnedDownloadUri(a.uri, a.systemId) else URI(a.uri).scheme == "content"))
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

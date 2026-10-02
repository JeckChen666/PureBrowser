package com.example.purebrowser.download

import android.app.DownloadManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.net.toUri
import android.net.Uri
import android.webkit.URLUtil
import com.example.purebrowser.browser.BrowserAddress
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.net.URI
import java.util.UUID

/** Metadata is private and versioned; only explicitly tracked public-file tasks are queried. */
internal class LegacyDownloadRepository(
    private val store: DownloadStore,
    private val backend: DownloadBackend,
    private val allowLocalHttp: Boolean = false,
) {
    constructor(context: Context) : this(DownloadStore(context), AndroidDownloadBackend(context),
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)

    private data class CachedCheck(val uri: String, val size: Long?, val updatedAt: Long?, val result: MediaInspection)
    private val checks = mutableMapOf<Long, CachedCheck>()
    private val reportedNotices = mutableSetOf<String>()
    private var notice: String? = null

    fun takeNotice(): String? = synchronized(DownloadStore.transactionLock) { notice.also { notice = null } }
    private fun load(): DownloadData {
        val value = store.load()
        store.takeNotice()?.takeIf { reportedNotices.add(it) }?.let { notice = it }
        return value
    }

    fun snapshot(): List<DownloadItem> = stateSnapshot().tasks

    fun stateSnapshot(): DownloadState = synchronized(DownloadStore.transactionLock) {
        val original = load()
        val assets = original.assets.associateBy { it.recordId }.toMutableMap()
        val records = original.records.toMutableList()
        val tasks = original.records.mapIndexedNotNull { index, initial ->
            if (initial.transfer != TransferType.SYSTEM) return@mapIndexedNotNull null
            val system = backend.query(initial.systemId!!)
            var record = initial
            if (system is SystemDownloadResult.Present) {
                // Recover only actual system data; source page, creation time, UA and policy stay unknown for legacy rows.
                record = initial.copy(mediaUrl = initial.mediaUrl ?: system.mediaUrl?.takeIf(BrowserAddress::isWebUrl),
                    mimeType = initial.mimeType ?: system.mimeType?.take(120))
                records[index] = record
            }
            var asset = assets[record.recordId]
            if (system is SystemDownloadResult.Present && system.status == DownloadManager.STATUS_SUCCESSFUL) {
                val uri = backend.fileUri(record.systemId!!)?.takeIf { DownloadRules.isOwnedDownloadUri(it, record.systemId!!) } ?: asset?.uri
                if (uri != null) {
                    val observed = backend.access(uri)
                    // Providers may hide newly saved non-media files as ENOENT. Until this
                    // app has actually read/check-tested the file, that isn't proof of loss.
                    val access = if(observed.availability == FileAvailability.MISSING &&
                        asset?.format !in setOf(FormatCheck.PASSED,FormatCheck.INVALID)) observed.copy(availability=FileAvailability.UNKNOWN) else observed
                    var inspection = MediaInspection(asset?.format?.takeIf { it != FormatCheck.NOT_CHECKED } ?: FormatCheck.UNCONFIRMED, asset?.mimeType, asset?.durationMillis)
                    if (access.availability == FileAvailability.AVAILABLE) {
                        val cached = checks[record.systemId!!]
                        inspection = if (cached?.uri == uri && cached.size == access.sizeBytes && cached.updatedAt == system.updatedAt) cached.result
                            else backend.inspect(uri).also { checks[record.systemId!!] = CachedCheck(uri, access.sizeBytes, system.updatedAt, it) }
                    } else checks.remove(record.systemId)
                    asset = VideoAsset(record.recordId, record.systemId, uri, record.name, record.displayName,
                        indexedAt = asset?.indexedAt ?: System.currentTimeMillis(), systemUpdatedAt = system.updatedAt,
                        sizeBytes = access.sizeBytes ?: asset?.sizeBytes, mimeType = inspection.mimeType ?: asset?.mimeType ?: record.mimeType?.takeIf { it.startsWith("video/") },
                        format = inspection.format, availability = access.availability, durationMillis = inspection.durationMillis)
                    assets[record.recordId] = asset
                } else if (asset != null) {
                    asset = asset.copy(availability = FileAvailability.UNKNOWN)
                    assets[record.recordId] = asset
                }
            } else if (asset != null) {
                // A disappeared system row does not prove that the saved file is still usable.
                asset = asset.copy(availability = backend.access(asset.uri).availability)
                assets[record.recordId] = asset
            }
            toItem(record, system, asset)
        }
        val next = original.copy(records = records, assets = original.records.mapNotNull { assets[it.recordId] })
        if (next != original && store.writable) store.save(next)
        DownloadState(tasks, next.assets)
    }

    private fun toItem(record: DownloadRecord, system: SystemDownloadResult, asset: VideoAsset?): DownloadItem {
        val current = system as? SystemDownloadResult.Present
        val usable = current?.status == DownloadManager.STATUS_SUCCESSFUL && asset?.format == FormatCheck.PASSED && asset.availability == FileAvailability.AVAILABLE
        val detail = if (record.cancelled) "已取消，原记录已保留" else when (system) {
            SystemDownloadResult.Missing -> "系统任务不存在，记录已保留"
            SystemDownloadResult.Unavailable -> "暂时无法读取系统任务，状态未确认"
            is SystemDownloadResult.Present -> when (system.status) {
                DownloadManager.STATUS_PENDING -> "排队中"
                DownloadManager.STATUS_RUNNING -> "下载中"
                DownloadManager.STATUS_PAUSED -> "等待网络 / 系统重试"
                DownloadManager.STATUS_SUCCESSFUL -> when (asset?.availability) {
                    FileAvailability.MISSING -> "已下载的文件已丢失"
                    FileAvailability.UNREADABLE -> "文件暂时无法读取，请检查访问权限"
                    FileAvailability.UNKNOWN, null -> if(record.mimeType?.startsWith("text/html")==true) "传输完成，但服务器返回网页类型；文件无法读取，不能确认为视频" else "传输已完成，文件可用性尚未确认"
                    FileAvailability.AVAILABLE -> when (asset.format) {
                        FormatCheck.PASSED -> "已保存 · 格式初检通过"
                        FormatCheck.INVALID -> "文件已保存，但格式初检失败（可能是登录页）"
                        FormatCheck.UNCONFIRMED -> "文件已保存，但无法完成格式初检"
                        FormatCheck.NOT_CHECKED -> "文件已保存，等待格式初检"
                    }
                }
                else -> when (system.reason) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
                    DownloadManager.ERROR_CANNOT_RESUME -> "服务器不支持继续下载"
                    DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络传输失败"
                    401, 403 -> "无权访问：可能需要登录或链接已过期"
                    else -> "下载失败（${system.reason}）"
                }
            }
        }
        return DownloadItem(record.recordId, record.name, current?.status ?: DownloadManager.STATUS_FAILED,
            current?.bytes ?: -1, current?.total ?: -1, detail, usable, record.recordId,
            asset?.format ?: FormatCheck.NOT_CHECKED, asset?.availability ?: FileAvailability.UNKNOWN, record.sourceUrl,
            when(system) { SystemDownloadResult.Missing -> SystemTaskRead.MISSING; SystemDownloadResult.Unavailable -> SystemTaskRead.UNAVAILABLE; else -> SystemTaskRead.PRESENT },
            displayName = record.displayName, createdAt = record.createdAt, wifiOnly = record.wifiOnly,
            canRetry = record.mediaUrl != null && (record.cancelled || system == SystemDownloadResult.Missing ||
                (current != null && current.status !in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED) && !usable)),
            retryOf = record.retryOf, sourceTitle = record.sourceTitle, cancelled = record.cancelled)
    }

    private fun isActive(system: SystemDownloadResult) = system is SystemDownloadResult.Present &&
        system.status in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)

    fun record(id: Long): DownloadRecord? = synchronized(DownloadStore.transactionLock) { load().records.firstOrNull { it.systemId == id } }

    fun rename(id: Long, title: String): Unit = synchronized(DownloadStore.transactionLock) {
        val value = title.trim()
        require(value.isNotBlank() && value.length <= 180 && value.none { it.isISOControl() }) { "请输入 1–180 字的显示名称" }
        val data = load()
        require(data.records.any { it.systemId == id })
        store.save(data.copy(records = data.records.map { if(it.systemId == id) it.copy(displayName = value) else it },
            assets = data.assets.map { if(it.systemId == id) it.copy(displayName = value) else it }))
    }

    /** Forget metadata only. Active or unconfirmed tasks cannot be forgotten into an orphan transfer. */
    fun forgetRecord(id: Long): Unit = synchronized(DownloadStore.transactionLock) {
        val data = load()
        val system = backend.query(id)
        require(data.records.any { it.systemId == id })
        check(!isActive(system) && system != SystemDownloadResult.Unavailable) { "请等待系统状态确认，或先取消正在下载的任务" }
        store.save(DownloadRules.forget(data, id))
        checks.remove(id)
        Unit
    }

    /** Cancel only an ongoing task. Preserve its record for a separate retry or forget. */
    fun cancel(id: Long): Unit = synchronized(DownloadStore.transactionLock) {
        val data = load()
        check(store.writable)
        require(data.records.any { it.systemId == id })
        check(isActive(backend.query(id))) { "任务已结束，请刷新后操作" }
        check(backend.remove(id) > 0) { "系统未确认取消，记录已保留" }
        store.save(data.copy(records = data.records.map { if(it.systemId == id) it.copy(cancelled = true) else it },
            assets = data.assets.filterNot { it.systemId == id }))
        checks.remove(id)
        Unit
    }

    /** Do not equate a disappeared system row with confirmed file deletion. */
    fun deleteFile(id: Long): Unit = synchronized(DownloadStore.transactionLock) {
        val data = load()
        check(store.writable)
        require(data.records.any { it.systemId == id })
        val system = backend.query(id)
        check(!isActive(system) && system != SystemDownloadResult.Unavailable) { "请先确认任务已结束" }
        val uri = (data.assets.firstOrNull { it.systemId == id }?.uri ?: backend.fileUri(id))
            ?.takeIf { DownloadRules.isOwnedDownloadUri(it, id) }
        check(backend.removeFileAndConfirm(id,uri)) {
            "未能确认文件已删除，记录已保留；请检查权限或在系统文件管理器中核实"
        }
        store.save(DownloadRules.forget(data, id))
        checks.remove(id)
        Unit
    }

    /** Compatibility for first-round callers; native product UI uses distinct operations above. */
    fun remove(id: Long) { if(isActive(backend.query(id))) cancel(id) else deleteFile(id) }

    fun fileUri(id: Long): Uri? = synchronized(DownloadStore.transactionLock) {
        val state = stateSnapshot()
        val asset = state.assets.firstOrNull { it.systemId == id && it.format == FormatCheck.PASSED && it.availability == FileAvailability.AVAILABLE }
            ?: return@synchronized null
        if (!DownloadRules.isOwnedDownloadUri(asset.uri, id) || backend.access(asset.uri).availability != FileAvailability.AVAILABLE) null else asset.uri.toUri()
    }

    fun mimeType(id: Long): String = synchronized(DownloadStore.transactionLock) {
        val record = load().records.firstOrNull { it.systemId == id }
        val type = checks[id]?.result?.mimeType ?: record?.mimeType
        type?.takeIf { it.startsWith("video/") } ?: "video/*"
    }
}

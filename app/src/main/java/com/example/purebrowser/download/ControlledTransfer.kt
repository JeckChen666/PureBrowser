package com.example.purebrowser.download

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** One explicit GET chain. Resume is conditional on an owned, durable checkpoint, never a retry. */
class ControlledTransfer(
    private val repository: DownloadRepository,
    private val transport: HttpTransport,
    private val access: AccessContextProvider,
) {
    private val checkpoints get() = repository.files!!.directCheckpoints

    fun run(id: TaskId, cancel: TransferCancellation) {
        val initial = repository.record(id) ?: return
        if (!writable(initial)) return
        val files = repository.files ?: error("未配置文件保存器")
        var trustworthy = true
        var recoverableFailure = false
        var sourceChanged = false
        var stage: File? = null
        try {
            DirectCheckpointStore.checkId(id)
            val ownedStage = files.stage(id).also { stage = it }
            guardStage(id, ownedStage)
            val checkpoint = checkpoints.load(id, ownedStage)
            if (checkpoint == null && (initial.resumeAvailable || checkpoints.exists(id) || ownedStage.length() > 0))
                changedSource()
            val source = initial.mediaUrl ?: throw TransferFailure(FailureKind.UNSUPPORTED, "记录没有资源地址")
            if (checkpoint != null && checkpoint.sourceHash != DirectCheckpointStore.urlHash(source)) changedSource()
            var url = source
            var hops = 0
            var usedCredential = false
            while (true) {
                ensureWriting(id, cancel)
                RequestPolicy.validateUrl(url, repository.allowLocalHttp)
                val cookie = if (RequestPolicy.cookieEligible(initial, url)) try { access.cookieFor(url) }
                    catch (_: Exception) { throw TransferFailure(FailureKind.ACCESS_CONDITION, "当前网站会话无法读取") } else null
                // No caller-supplied headers. Only policy headers and validated Range/If-Range are added.
                val headers = RequestPolicy.headers(initial, url, cookie).toMutableMap()
                val offset = checkpoint?.let { minOf(it.bytes, it.total - 1) }
                if (checkpoint != null && DirectCheckpointStore.urlHash(url) == checkpoint.effectiveHash) {
                    headers["Range"] = "bytes=$offset-"
                    headers["If-Range"] = checkpoint.etag
                }
                usedCredential = usedCredential || !headers["Cookie"].isNullOrBlank()
                transport.open(url, headers, cancel).use { response ->
                    if (response.status in REDIRECTS) {
                        if (hops++ >= 5) throw TransferFailure(FailureKind.HTTP_REJECTED, "服务器跳转次数过多")
                        url = RequestPolicy.redirect(url, response.header("Location") ?:
                            throw TransferFailure(FailureKind.HTTP_REJECTED, "服务器跳转没有目标"), usedCredential, repository.allowLocalHttp)
                    } else {
                        if (response.status == 401 || response.status == 403)
                            throw TransferFailure(FailureKind.ACCESS_CONDITION, "当前访问条件不足")
                        val etag = DirectCheckpointStore.strongEtag(response.header("ETag"))
                        val length = reliableLength(response)
                        val identityEncoding = response.header("Content-Encoding").let { it == null || it.equals("identity", true) }
                        val total: Long?
                        if (checkpoint != null) {
                            // Includes 200, 416, wildcard/multipart ranges, a changed effective URL, or absent validator.
                            if (response.status != 206 || !identityEncoding || etag != checkpoint.etag ||
                                DirectCheckpointStore.urlHash(url) != checkpoint.effectiveHash ||
                                length != checkpoint.total - offset!! ||
                                !exactRange(response.header("Content-Range"), offset, checkpoint.total) ||
                                response.header("Content-Type")?.startsWith("multipart/", true) == true) changedSource()
                            total = checkpoint.total
                        } else {
                            if (response.status != 200) throw TransferFailure(FailureKind.HTTP_REJECTED, "服务器拒绝完整文件请求")
                            if (!identityEncoding) throw TransferFailure(FailureKind.UNSUPPORTED, "不支持压缩编码的视频响应")
                            if (response.header("Content-Range") != null) throw TransferFailure(FailureKind.HTTP_REJECTED, "完整文件响应包含无效范围")
                            total = length
                        }
                        ensureWriting(id, cancel)
                        val eligible = etag != null && total != null && total >= 12
                        val writeDigest = if (eligible) checkpoint?.verifiedDigest ?: MessageDigest.getInstance("SHA-256") else null
                        if (writeDigest != null) try { DirectCheckpointStore.snapshotDigest(writeDigest) }
                            catch (_: Exception) { throw TransferFailure(FailureKind.UNSUPPORTED, "当前设备无法安全保存续传校验") }
                        var count = checkpoint?.bytes ?: 0L
                        var durable = checkpoint?.bytes ?: 0L
                        update(id) { it.copy(taskStatus = TaskStatus.RUNNING, received = count, expected = total,
                            failure = null, safeFailure = null, pauseReason = null, resumeAvailable = checkpoint != null) }
                            ?: throw CancellationException()
                        val prefix = java.io.ByteArrayOutputStream(12)
                        if (checkpoint != null) DirectCheckpointStore.noFollowInput(ownedStage).use { input ->
                            val bytes = ByteArray(12)
                            if (input.read(bytes) != bytes.size || !DownloadRules.supportedHeader(bytes)) changedSource()
                            prefix.write(bytes)
                        }
                        response.body().use { input ->
                            // A complete checkpoint still validates the remote identity with a one-byte 206.
                            if (checkpoint != null && checkpoint.bytes == checkpoint.total) {
                                val expected = DirectCheckpointStore.noFollowInput(ownedStage).use {
                                    it.channel.position(checkpoint.total - 1); it.read()
                                }
                                val actual = input.read()
                                if (actual < 0) throw TransferFailure(FailureKind.NETWORK, "视频响应未完整接收")
                                if (actual != expected) changedSource()
                            }
                            guardStage(id, ownedStage)
                            val fd = Os.open(ownedStage.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW, 384)
                            try {
                                FileOutputStream(fd).use { output ->
                                    // Only now, after remote AND local identity validation, discard an uncommitted tail.
                                    synchronized(DownloadStore.transactionLock) {
                                        ensureWriting(id, cancel)
                                        if (checkpoint != null && !checkpoints.isCurrent(id, ownedStage, checkpoint, fd)) changedSource()
                                        output.channel.truncate(count); output.channel.position(count)
                                    }
                                    var lastUpdate = System.nanoTime() - 500_000_000L
                                    var lastCheckpoint = System.nanoTime()
                                    fun commit() {
                                        if (!eligible || !trustworthy || count < 12) return
                                        output.fd.sync()
                                        checkpoints.save(id, ownedStage, source, url, etag, total, count, writeDigest)
                                        durable = count; lastCheckpoint = System.nanoTime()
                                    }
                                    try {
                                        val buffer = ByteArray(65536)
                                        while (true) {
                                            ensureWriting(id, cancel)
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            if (n == 0) continue
                                            ensureWriting(id, cancel)
                                            if (total != null && n.toLong() > total - count) {
                                                trustworthy = false
                                                if (checkpoint != null) changedSource()
                                                throw TransferFailure(FailureKind.NOT_VIDEO, "响应长度不一致")
                                            }
                                            if (prefix.size() < 12) prefix.write(buffer, 0, minOf(n, 12 - prefix.size()))
                                            if (prefix.size() >= 12 && !DownloadRules.supportedHeader(prefix.toByteArray())) {
                                                trustworthy = false
                                                throw TransferFailure(FailureKind.NOT_VIDEO, "响应不是 MP4/WebM 视频")
                                            }
                                            try { output.write(buffer, 0, n) }
                                            catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "临时视频无法写入") }
                                            writeDigest?.update(buffer, 0, n)
                                            count += n
                                            if (eligible && (durable == 0L || count - durable >= CHECKPOINT_BYTES ||
                                                    System.nanoTime() - lastCheckpoint >= CHECKPOINT_NANOS)) {
                                                try { commit() } catch (_: Exception) {
                                                    throw TransferFailure(FailureKind.STORAGE, "续传检查点无法保存")
                                                }
                                            }
                                            val now = System.nanoTime()
                                            if (now - lastUpdate >= 500_000_000L) {
                                                update(id) { it.copy(received = count, resumeAvailable = durable > 0) }
                                                    ?: throw CancellationException()
                                                lastUpdate = now
                                            }
                                        }
                                        if (total != null && count != total) throw TransferFailure(FailureKind.NETWORK, "视频响应未完整接收")
                                        try { output.fd.sync(); commit() }
                                        catch (_: Exception) { throw TransferFailure(FailureKind.STORAGE, "临时视频无法持久保存") }
                                    } finally {
                                        // Includes aborted reads/writes. A failed commit leaves the previous durable checkpoint intact.
                                        runCatching { commit() }
                                    }
                                }
                            } finally { if (fd.valid()) Os.close(fd) }
                        }
                        ensureWriting(id, cancel)
                        update(id) { it.copy(taskStatus = TaskStatus.VERIFYING, received = count) } ?: throw CancellationException()
                        val inspection = files.inspect(ownedStage)
                        if (inspection.format != FormatCheck.PASSED) {
                            trustworthy = false
                            throw TransferFailure(FailureKind.NOT_VIDEO, "视频容器或轨道未通过初检")
                        }
                        val ext = if (inspection.mimeType == "video/webm") "webm" else "mp4"
                        val record = update(id) { r ->
                            r.copy(name = "${r.name.substringBeforeLast('.', r.name)}.$ext",
                                displayName = "${r.displayName.substringBeforeLast('.', r.displayName)}.$ext",
                                mimeType = inspection.mimeType, taskStatus = TaskStatus.PUBLISHING)
                        } ?: throw CancellationException()
                        ensureWriting(id, cancel)
                        val asset = try { files.publish(record, ownedStage, inspection, { uri ->
                            ensureWriting(id, cancel)
                            update(id) { it.copy(pendingUri = uri) } ?: throw CancellationException()
                        }, cancel) } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { throw TransferFailure(FailureKind.STORAGE, "公共视频文件无法完整保存") }
                        try {
                            synchronized(DownloadStore.transactionLock) {
                                ensureWriting(id, cancel)
                                if (!repository.complete(id, asset)) throw CancellationException()
                            }
                        } catch (e: Exception) { files.delete(asset); throw e }
                        return
                    }
                }
            }
        } catch (_: CancellationException) {
            // Runtime has already committed the stop reason. It settles PAUSING only after this writer exits.
        } catch (e: TransferFailure) {
            sourceChanged = e.safeMessage == SOURCE_CHANGED_MESSAGE
            recoverableFailure = e.kind in setOf(FailureKind.NETWORK, FailureKind.STORAGE) && !sourceChanged
            trustworthy = trustworthy && recoverableFailure
            fail(id, e.kind, e.safeMessage, sourceChanged)
        } catch (_: IOException) {
            recoverableFailure = true
            fail(id, FailureKind.NETWORK, "网络连接中断", false)
        } catch (_: Exception) {
            recoverableFailure = true
            fail(id, FailureKind.STORAGE, "临时视频无法安全保存", false)
        } finally {
            synchronized(DownloadStore.transactionLock) {
                val r = repository.record(id)
                val mayPreserve = r != null && (r.taskStatus in PRESERVE_STATUSES ||
                    (r.taskStatus == TaskStatus.FAILED && recoverableFailure))
                val valid = trustworthy && mayPreserve && stage?.let { checkpoints.hasValid(id, it) } == true
                val preserve = valid
                // Public pending output is never itself a resume cache.
                val cleaned = r == null || runCatching { files.cleanupPending(r) }.isSuccess
                if (!preserve) {
                    runCatching { checkpoints.delete(id) }
                    runCatching {
                        val f = stage ?: files.stage(id)
                        guardStage(id, f, allowDirectory = true)
                        files.removeStage(id)
                    }
                }
                runCatching { repository.change(id) { old ->
                    when {
                        old.taskStatus == TaskStatus.SUCCEEDED -> old.copy(resumeAvailable = false, pauseReason = null, pendingUri = null)
                        sourceChanged && old.taskStatus != TaskStatus.CANCELLED -> old.copy(resumeAvailable = false,
                            pauseReason = PauseReason.SOURCE_CHANGED, safeFailure = SOURCE_CHANGED_MESSAGE,
                            pendingUri = if (cleaned) null else old.pendingUri)
                        else -> old.copy(resumeAvailable = preserve,
                            pendingUri = if (cleaned) null else old.pendingUri)
                    }
                } }
            }
        }
    }

    private fun ensureWriting(id: TaskId, cancel: TransferCancellation) {
        cancel.check()
        if (!repository.transfersAllowed || repository.store.writerRecord(id)?.let(::writable) != true) throw CancellationException()
    }
    private fun update(id: TaskId, block: (DownloadRecord) -> DownloadRecord): DownloadRecord? {
        var changed = false
        val r = repository.change(id) { old -> if (writable(old)) { changed = true; block(old) } else old }
        return r?.takeIf { changed }
    }
    private fun fail(id: TaskId, kind: FailureKind, message: String, changed: Boolean) {
        update(id) { it.copy(taskStatus = TaskStatus.FAILED, failure = kind, safeFailure = message,
            resumeAvailable = if (changed) false else it.resumeAvailable,
            pauseReason = if (changed) PauseReason.SOURCE_CHANGED else it.pauseReason) }
    }
    private fun guardStage(id: TaskId, stage: File, allowDirectory: Boolean = false) {
        DirectCheckpointStore.checkId(id)
        val parent = stage.parentFile ?: throw IOException("临时视频路径不安全")
        if (parent.name != "transfers" || stage.name != "$id.part" ||
            !DirectCheckpointStore.safeDirectory(parent) ||
            !DirectCheckpointStore.safeDirectory(parent.parentFile!!) ||
            parent.canonicalFile.parentFile != parent.parentFile!!.canonicalFile)
            throw TransferFailure(FailureKind.STORAGE, "临时视频目录不安全")
        try {
            if (!allowDirectory || !stage.isDirectory) DirectCheckpointStore.checkLeaf(stage)
            if (stage.canonicalFile.parentFile != parent.canonicalFile) throw IOException()
        } catch (_: Exception) { throw TransferFailure(FailureKind.STORAGE, "临时视频路径不安全") }
    }
    private fun changedSource(): Nothing = throw TransferFailure(FailureKind.HTTP_REJECTED, SOURCE_CHANGED_MESSAGE)
    private fun reliableLength(response: HttpResponse): Long? {
        if (response.header("Transfer-Encoding") != null) return null
        val value = response.header("Content-Length") ?: return null
        return value.takeIf { it.length in 1..19 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
    }
    private fun exactRange(value: String?, start: Long, total: Long): Boolean {
        val match = value?.let { CONTENT_RANGE.matchEntire(it) } ?: return false
        val (first, last, size) = match.destructured
        return first.toLongOrNull() == start && last.toLongOrNull() == total - 1 && size.toLongOrNull() == total
    }
    private fun writable(record: DownloadRecord) = !record.cancelled && record.pauseReason != PauseReason.SOURCE_CHANGED &&
        record.transfer == TransferType.CONTROLLED &&
        record.protocol == DownloadProtocol.DIRECT && record.taskStatus in WRITABLE_STATUSES

    companion object {
        private val WRITABLE_STATUSES = setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING)
        private val PRESERVE_STATUSES = setOf(TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED)
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})")
        private const val CHECKPOINT_BYTES = 1024L * 1024
        private const val CHECKPOINT_NANOS = 2_000_000_000L
        private const val SOURCE_CHANGED_MESSAGE = "资源或续传校验信息已变化，请重新发现资源后开始新下载"
    }
}

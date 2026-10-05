package com.example.purebrowser.download

import android.system.Os
import android.system.OsConstants
import com.example.purebrowser.download.hls.HlsDualTrackPlan
import com.example.purebrowser.download.hls.HlsHttpClient
import com.example.purebrowser.download.hls.HlsSegmentTrackSource
import com.example.purebrowser.download.mux.DualTrackMuxer
import com.example.purebrowser.download.mux.DualTrackMuxException
import com.example.purebrowser.download.mux.MuxedTracks
import com.example.purebrowser.download.site.DualTrackDownloadPlan
import com.example.purebrowser.download.site.DualTrackMetadata
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One slot, two sequential chains of closed-range chunks, one verified MP4. No open-ended ranges,
 * no continuation joins, no checkpoints. The muxer must validate actual track roles/codecs/timestamps;
 * declarations alone are not proof.
 */
class DualTrackTransfer(
    private val repository: DownloadRepository,
    transport: HttpTransport,
    private val access: AccessContextProvider,
    private val mux: (File, File, File, TransferCancellation) -> MuxedTracks = { video, audio, output, cancel ->
        DualTrackMuxer().mux(video, audio, output, cancel)
    },
    // Fixed production slice size; smaller values exist only for authored chunk tests.
    private val chunkBytes: Long = CHUNK_BYTES,
) {
    private val transport = repository.guardedTransport(transport)

    fun run(id: TaskId, cancel: TransferCancellation) {
        val initial = repository.record(id) ?: return
        if (!writable(initial)) return
        val files = repository.files ?: return
        var reserved = false
        val timedOut = AtomicBoolean(false)
        val deadline = deadlines.schedule({ timedOut.set(true); cancel.cancel() },
            DualTrackMetadata.MAX_TRANSFER_NANOS, TimeUnit.NANOSECONDS)
        var startedAt = System.nanoTime()
        var received = 0L
        fun check() {
            cancel.check()
            if (!repository.transfersAllowed || repository.store.writerRecord(id)?.let(::writable) != true)
                throw CancellationException()
            if (System.nanoTime() - startedAt > DualTrackMetadata.MAX_TRANSFER_NANOS)
                throw TransferFailure(FailureKind.SYSTEM_LIMIT, "双轨处理已超出时间预算，请重新解析后确认下载")
        }
        try {
            check()
            val lease = repository.takeDualTrackRequest(id)
                ?: throw TransferFailure(FailureKind.INTERRUPTED, REPARSE_MESSAGE)
            lease.plan.validate(repository.allowLocalHttp)
            val metadata = lease.plan.metadata()
            if (metadata != initial.dualTrackMetadata)
                throw TransferFailure(FailureKind.HTTP_REJECTED, REPARSE_MESSAGE)
            val output = files.stage(id)
            guardOutput(id, output)
            // No stage from a previous run may be concatenated or used as a completed track.
            files.clearPrivate(id)
            val inputBudget = (metadata.videoLength ?: DualTrackMetadata.MAX_VIDEO_BYTES) +
                (metadata.audioLength ?: DualTrackMetadata.MAX_AUDIO_BYTES)
            // Segment tracks additionally hold the TS bytes beside their per-track MP4 copies.
            val reserveFactor = if (lease.plan is HlsDualTrackPlan) 4L else 3L
            reserveStorage(id, output.parentFile!!, reserveFactor * inputBudget +
                2 * DualTrackMetadata.OUTPUT_OVERHEAD_BYTES + DualTrackMetadata.STORAGE_RESERVE_BYTES)
            reserved = true
            val (video, audio) = files.dualTrackWorkspace.fresh(id)
            val request = lease.requestRecord
            startedAt = System.nanoTime()
            var lastUpdate = 0L
            fun progress(n: Int) {
                received += n
                val now = System.nanoTime()
                if (now - lastUpdate >= 500_000_000L) {
                    update(id) { it.copy(received = received, resumeAvailable = false) } ?: throw CancellationException()
                    lastUpdate = now
                }
            }
            when (val trackPlan = lease.plan) {
                is DualTrackDownloadPlan -> {
                    downloadTrack(id, request.copy(mediaUrl = trackPlan.videoUrl), trackPlan.videoUrl, video,
                        metadata.videoLength, DualTrackMetadata.MAX_VIDEO_BYTES, cancel, ::check, ::progress)
                    check()
                    downloadTrack(id, request.copy(mediaUrl = trackPlan.audioUrl), trackPlan.audioUrl, audio,
                        metadata.audioLength, DualTrackMetadata.MAX_AUDIO_BYTES, cancel, ::check, ::progress)
                }
                is HlsDualTrackPlan -> HlsSegmentTrackSource(
                    HlsHttpClient(transport, access, repository.allowLocalHttp), files.dualTrackWorkspace,
                ).fetchTracks(id, request, trackPlan, video, audio,
                    DualTrackMetadata.MAX_VIDEO_BYTES, DualTrackMetadata.MAX_AUDIO_BYTES, cancel, ::check, ::progress)
                else -> throw TransferFailure(FailureKind.UNSUPPORTED, "未知双轨方案")
            }
            check()
            update(id) { it.copy(taskStatus = TaskStatus.MUXING, received = received) } ?: throw CancellationException()
            guardOutput(id, output)
            if (output.exists()) throw TransferFailure(FailureKind.STORAGE, "双轨输出路径不是空的新文件")
            val result = try { mux(video, audio, output, cancel) }
                catch (e: CancellationException) { throw e }
                catch (_: DualTrackMuxException) { throw TransferFailure(FailureKind.NOT_VIDEO, MUX_FAILURE_MESSAGE) }
                catch (_: Exception) { throw TransferFailure(FailureKind.NOT_VIDEO, MUX_FAILURE_MESSAGE) }
            check()
            guardOutput(id, output)
            val maxOutput = received + DualTrackMetadata.OUTPUT_OVERHEAD_BYTES
            if (!output.isFile || output.length() !in 1..maxOutput)
                throw TransferFailure(FailureKind.NOT_VIDEO, "双轨 MP4 输出为空或超出预算")
            if (result.durationUs <= 0 || kotlin.math.abs(result.durationUs - metadata.durationUs) >
                durationTolerance(metadata.durationUs))
                throw TransferFailure(FailureKind.NOT_VIDEO, "双轨实际时长与确认方案不符，请重新解析")
            update(id) { it.copy(taskStatus = TaskStatus.VERIFYING) } ?: throw CancellationException()
            val inspection = files.inspectDualTrack(output, metadata.durationUs, cancel)
            check()
            if (inspection.format != FormatCheck.PASSED || inspection.mimeType != "video/mp4")
                throw TransferFailure(FailureKind.NOT_VIDEO, "双轨 MP4 的声画轨道或时长未通过校验")
            val publishing = update(id) { it.copy(taskStatus = TaskStatus.PUBLISHING, mimeType = "video/mp4") }
                ?: throw CancellationException()
            // Cover the additional public copy, not just the private mux output.
            if (output.parentFile!!.usableSpace < output.length() + DualTrackMetadata.STORAGE_RESERVE_BYTES)
                throw TransferFailure(FailureKind.STORAGE, "双轨成品发布空间不足")
            val asset = try {
                files.publish(publishing, output, inspection, { uri ->
                    check()
                    update(id) { it.copy(pendingUri = uri) } ?: throw CancellationException()
                }, cancel)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw TransferFailure(FailureKind.STORAGE, "双轨公共成品未能完整保存") }
            try {
                synchronized(DownloadStore.transactionLock) {
                    check()
                    if (!repository.complete(id, asset)) throw CancellationException()
                }
            } catch (e: Exception) { files.delete(asset); throw e }
        } catch (_: CancellationException) {
            // Runtime owns user/network stops. A task-owned deadline also covers synchronous muxing.
            if(timedOut.get())update(id) { it.copy(taskStatus=TaskStatus.FAILED, failure=FailureKind.SYSTEM_LIMIT,
                safeFailure="双轨处理已超出时间预算，请重新解析后确认下载", pauseReason=PauseReason.SOURCE_CHANGED,
                received=received, resumeAvailable=false) }
        } catch (e: TransferFailure) {
            update(id) { it.copy(taskStatus = if (e.kind == FailureKind.INTERRUPTED) TaskStatus.INTERRUPTED else TaskStatus.FAILED,
                failure = e.kind, safeFailure = e.safeMessage, pauseReason = PauseReason.SOURCE_CHANGED,
                resumeAvailable = false, received = received) }
        } catch (_: DualTrackMuxException) {
            // This type is an IOException, but it describes local media validation, never network IO.
            cancelOrFail(id, cancel, FailureKind.NOT_VIDEO, MUX_FAILURE_MESSAGE, received)
        } catch (_: IOException) {
            cancelOrFail(id, cancel, FailureKind.NETWORK, "双轨传输未完整接收；请重新解析后确认下载", received)
        } catch (_: Exception) {
            cancelOrFail(id, cancel, FailureKind.NOT_VIDEO, "双轨处理未完成；请重新解析后确认下载", received)
        } finally {
            try { synchronized(DownloadStore.transactionLock) {
                val record = repository.store.writerRecord(id)
                val pendingCleaned = record == null || runCatching { files.cleanupPending(record) }.isSuccess
                val privateCleaned = runCatching { files.clearPrivate(id) }.isSuccess
                if (record != null) runCatching {
                    repository.change(id) { old ->
                        val expired = timedOut.get() && writable(old)
                        val interrupted = old.taskStatus in (TaskControlRules.waiting + setOf(TaskStatus.PAUSING)) || writable(old)
                        old.copy(resumeAvailable = false, pendingUri = if (pendingCleaned) null else old.pendingUri,
                            taskStatus = if (expired) TaskStatus.FAILED else if (interrupted) TaskStatus.INTERRUPTED else old.taskStatus,
                            failure = if (expired) FailureKind.SYSTEM_LIMIT else old.failure,
                            pauseReason = if (interrupted) PauseReason.SOURCE_CHANGED else old.pauseReason,
                            safeFailure = if (expired) "双轨处理已超出时间预算，请重新解析后确认下载" else if (interrupted) REPARSE_MESSAGE else if (!privateCleaned || !pendingCleaned)
                                "双轨临时文件清理未完成，请检查存储；不能继续旧字节" else old.safeFailure)
                    }
                }
            } } finally { deadline.cancel(false); if (reserved) releaseStorage(id) }
        }
    }

    private fun downloadTrack(
        id: TaskId, request: DownloadRecord, source: String, file: File, declared: Long?, maximum: Long,
        cancel: TransferCancellation, check: () -> Unit, progress: (Int) -> Unit,
    ) {
        // The source gate serves any closed interval but rejects open-ended ranges (T67 evidence),
        // so a track is fetched as one strictly sequential chain of inclusive slices and reassembled
        // in byte order. A failed track restarts from byte 0 on the next confirmed attempt; no slice
        // is ever continued, joined, or resumed.
        if (declared != null && declared !in 1..maximum)
            throw TransferFailure(FailureKind.UNSUPPORTED, "双轨资源超出字节预算")
        var opens = 0L
        var budget = declared?.let(::chunkBudget) ?: Long.MAX_VALUE
        repository.files!!.dualTrackWorkspace.check(file, id)
        val fd = try { Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
            OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180) }
            catch (_: Exception) { throw TransferFailure(FailureKind.STORAGE, "双轨临时文件无法安全创建") }
        var count = 0L
        try {
            FileOutputStream(fd).use { output ->
                val buffer = ByteArray(65536)
                val prefix = java.io.ByteArrayOutputStream(12)
                var total = declared
                var start = 0L
                while (total == null || start < total!!) {
                    var url = source
                    var hops = 0
                    var credentialUsed = false
                    var servedEnd = 0L
                    var redirected = true
                    while (redirected) {
                        redirected = false
                        check()
                        // Every open (redirect hops included) is a chunk-form request; the whole
                        // track may not exceed its chunk count plus a small overrun allowance.
                        if (++opens > budget)
                            throw TransferFailure(FailureKind.HTTP_REJECTED, CHUNK_BUDGET_MESSAGE)
                        RequestPolicy.validateUrl(url, repository.allowLocalHttp)
                        val cookie = if (RequestPolicy.cookieEligible(request, url)) try { access.cookieFor(url) }
                            catch (_: Exception) { throw TransferFailure(FailureKind.ACCESS_CONDITION, "网站访问会话无法读取，请返回来源") }
                            else null
                        val headers = RequestPolicy.headers(request, url, cookie).toMutableMap()
                        // One exact closed interval per request. Never open-ended, never a resume join.
                        val wanted = if (total == null) start + chunkBytes - 1
                            else minOf(start + chunkBytes, total!!) - 1
                        headers["Range"] = "bytes=$start-$wanted"
                        credentialUsed = credentialUsed || !headers["Cookie"].isNullOrBlank()
                        transport.open(url, headers, cancel).use { response ->
                            if (response.status in REDIRECTS) {
                                if (hops++ >= 5) throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨服务器跳转次数过多")
                                url = RequestPolicy.redirect(url, response.header("Location")
                                    ?: throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨服务器缺少跳转地址"),
                                    credentialUsed, repository.allowLocalHttp)
                                redirected = true
                            } else {
                                if (response.status in setOf(401, 403, 410))
                                    throw TransferFailure(FailureKind.ACCESS_CONDITION, "轨道地址失效或访问不足，请返回来源重新解析并确认下载")
                                if (response.status !in setOf(200, 206))
                                    throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨服务器未返回完整资源，请重新解析")
                                val encoding = response.header("Content-Encoding")
                                if (encoding != null && !encoding.equals("identity", true))
                                    throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨响应编码不符合原始字节合同")
                                if (response.header("Transfer-Encoding") != null && response.header("Content-Length") != null)
                                    throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨响应长度与传输编码互相矛盾")
                                if (response.status == 200) {
                                    if (response.header("Content-Range") != null)
                                        throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨完整响应包含矛盾的范围信息")
                                    // A full body is only an honest empty resource, never slice bytes.
                                    if (total != null || length(response.header("Content-Length")) != 0L)
                                        throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨服务器未按闭区间分段返回，请重新解析")
                                    throw TransferFailure(FailureKind.UNSUPPORTED, "双轨资源超出字节预算")
                                }
                                val range = chunkRange(response.header("Content-Range"))
                                    ?: throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨响应只是部分资源，不能拼接继续")
                                if (range.first != start)
                                    throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨响应范围与请求起点不符，请重新解析")
                                val chunkLength: Long
                                if (total != null) {
                                    if (range.third != total || range.second != wanted)
                                        throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨长度与确认方案不符，请重新解析")
                                    servedEnd = wanted
                                    chunkLength = wanted - start + 1
                                } else {
                                    // The first slice also discovers the length; its end may be clipped.
                                    if (range.second !in start..wanted)
                                        throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨长度与确认方案不符，请重新解析")
                                    servedEnd = range.second
                                    chunkLength = servedEnd - start + 1
                                    val discovered = range.third
                                    total = discovered
                                    if (discovered !in 1..maximum)
                                        throw TransferFailure(FailureKind.UNSUPPORTED, "双轨资源超出字节预算")
                                    budget = chunkBudget(discovered)
                                }
                                val responseLength = length(response.header("Content-Length"))
                                if (responseLength != null && responseLength != chunkLength)
                                    throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨长度与确认方案不符，请重新解析")
                                var received = 0L
                                response.body().use { input ->
                                    while (true) {
                                        check()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        if (n == 0) continue
                                        check()
                                        if (received + n > chunkLength)
                                            throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨响应超过声明长度或预算")
                                        if (prefix.size() < 12) prefix.write(buffer, 0, minOf(n, 12 - prefix.size()))
                                        if (prefix.size() >= 12 && !inputMp4(prefix.toByteArray()))
                                            throw TransferFailure(FailureKind.NOT_VIDEO, "轨道不是完整 MP4 资源")
                                        try { output.write(buffer, 0, n) }
                                        catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "双轨临时文件无法写入") }
                                        received += n
                                        count += n
                                        progress(n)
                                    }
                                }
                                if (received != chunkLength)
                                    throw TransferFailure(FailureKind.NETWORK, "双轨响应未完整接收；请重新解析后确认下载")
                            }
                        }
                    }
                    start = servedEnd + 1
                }
                if (count < 12 || count != total!!)
                    throw TransferFailure(FailureKind.NETWORK, "双轨响应未完整接收；请重新解析后确认下载")
                try { output.fd.sync() }
                catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "双轨临时文件无法持久保存") }
            }
        } finally { if (fd.valid()) Os.close(fd) }
    }

    private fun chunkBudget(total: Long) = (total + chunkBytes - 1) / chunkBytes + CHUNK_OPEN_ALLOWANCE

    private fun cancelOrFail(id: TaskId, cancel: TransferCancellation, kind: FailureKind, message: String, received: Long) {
        try { cancel.check() } catch (_: CancellationException) { return }
        update(id) { it.copy(taskStatus = TaskStatus.FAILED, failure = kind, safeFailure = message,
            resumeAvailable = false, pauseReason = PauseReason.SOURCE_CHANGED, received = received) }
    }
    private fun update(id: TaskId, block: (DownloadRecord) -> DownloadRecord): DownloadRecord? {
        var changed = false
        return repository.change(id) { if (writable(it)) { changed = true; block(it) } else it }.takeIf { changed }
    }
    private fun writable(r: DownloadRecord) = r.transfer == TransferType.CONTROLLED &&
        r.protocol == DownloadProtocol.DUAL_TRACK && !r.cancelled &&
        r.taskStatus in (TaskControlRules.writing + TaskStatus.QUEUED)
    private fun guardOutput(id: TaskId, file: File) {
        DirectCheckpointStore.checkId(id)
        val parent = file.parentFile ?: throw TransferFailure(FailureKind.STORAGE, "双轨输出路径不安全")
        if (file.name != "$id.part" || parent.name != "transfers" || !DirectCheckpointStore.safeDirectory(parent) ||
            !DirectCheckpointStore.safeDirectory(parent.parentFile!!) || file.canonicalFile.parentFile != parent.canonicalFile)
            throw TransferFailure(FailureKind.STORAGE, "双轨输出路径不安全")
        DirectCheckpointStore.checkLeaf(file)
    }

    companion object {
        internal const val MUX_FAILURE_MESSAGE = "双轨编码、声画时序或封装未通过校验"
        const val REPARSE_MESSAGE = "双轨不保留续传字节，请返回来源重新解析并确认下载"
        /** Closed-range slice size; the final slice of a track is exact, never open-ended. */
        internal const val CHUNK_BYTES = 4L * 1024 * 1024
        /** Small overrun allowance over ceil(length/chunk) open requests per track, redirects included. */
        internal const val CHUNK_OPEN_ALLOWANCE = 8
        internal const val CHUNK_BUDGET_MESSAGE = "双轨分段请求超出预算，请重新解析后确认下载"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val RANGE = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})")
        internal fun fullRange(value: String?): Long? {
            val groups = value?.let(RANGE::matchEntire)?.destructured ?: return null
            val (start, end, total) = groups
            val size = total.toLongOrNull() ?: return null
            return size.takeIf { it > 0 && start.toLongOrNull() == 0L && end.toLongOrNull() == it - 1 }
        }
        /** Inclusive served slice (start, end, total); the slice must lie inside the declared resource. */
        internal fun chunkRange(value: String?): Triple<Long, Long, Long>? {
            val groups = value?.let(RANGE::matchEntire)?.destructured ?: return null
            val (first, last, size) = groups
            val start = first.toLongOrNull() ?: return null
            val end = last.toLongOrNull() ?: return null
            val total = size.toLongOrNull() ?: return null
            return if (start in 0..end && end < total) Triple(start, end, total) else null
        }
        internal fun length(value: String?): Long? {
            if (value == null) return null
            return value.takeIf { it.length in 1..19 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
                ?: throw TransferFailure(FailureKind.HTTP_REJECTED, "双轨服务器长度信息无效")
        }
        private fun inputMp4(prefix: ByteArray) = MediaContainer.mime(prefix) == "video/mp4" ||
            (prefix.size >= 12 && String(prefix, 4, 4, Charsets.US_ASCII) == "ftyp" &&
                String(prefix, 8, 4, Charsets.US_ASCII) == "M4A ")
        private fun durationTolerance(durationUs: Long) = maxOf(2_000_000L, minOf(5_000_000L, durationUs / 1000))
        private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "dual-track-budget").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val storageReservations = mutableMapOf<TaskId, Long>()
        @Synchronized private fun reserveStorage(id: TaskId, directory: File, bytes: Long) {
            if (storageReservations.containsKey(id) || directory.usableSpace < bytes + storageReservations.values.sum())
                throw TransferFailure(FailureKind.STORAGE, "双轨输入、封装及公共副本的预留空间不足")
            storageReservations[id] = bytes
        }
        @Synchronized private fun releaseStorage(id: TaskId) { storageReservations.remove(id) }
    }
}

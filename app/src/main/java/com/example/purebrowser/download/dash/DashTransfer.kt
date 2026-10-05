package com.example.purebrowser.download.dash

import com.example.purebrowser.download.*
import com.example.purebrowser.download.hls.HlsHttpClient
import com.example.purebrowser.download.mux.DualTrackMuxer
import com.example.purebrowser.download.mux.MuxedTracks
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * T97 DASH transfer: plan → bounded init+segment fetch → fMP4 assembly → dual-track merge → one
 * verified MP4. Mirrors the HLS transfer's two-worker bounded fetching and publish semantics, and
 * the dual-track contract for recovery: no resume bytes are kept, a mid-transfer failure cleans
 * the workspace and asks the user to re-parse from the source. A video+audio representation pair
 * assembles each track then merges through the existing [DualTrackMuxer]; a muxed representation
 * assembles straight to the staged MP4. Only this task's verified MP4 reaches the public store.
 */
class DashTransfer(private val repository: DownloadRepository, transport: HttpTransport, access: AccessContextProvider) {
    private val client = HlsHttpClient(transport, access, repository.allowLocalHttp)
    private val assembler = Fmp4SegmentAssembler()

    fun run(id: TaskId, cancel: TransferCancellation) {
        val files = repository.files ?: error("文件保存器未配置")
        val workspace = files.dashWorkspace
        var published: VideoAsset? = null
        try {
            val initial = repository.record(id) ?: return
            if (initial.protocol != DownloadProtocol.DASH || !canUpdate(initial)) return
            val lease = repository.takeDashRequest(id)
                ?: throw TransferFailure(FailureKind.INTERRUPTED, REPARSE_MESSAGE)
            val plan = lease.plan
            val request = lease.requestRecord
            plan.validate(repository.allowLocalHttp)
            if (plan.entryUrl != initial.mediaUrl || plan.durationUs != initial.plannedDurationUs ||
                plan.totalSegments != initial.segmentCount
            ) throw TransferFailure(FailureKind.UNSUPPORTED, "DASH 方案与任务不一致")
            cancel.check(); workspace.requireSpace(id)
            val task = workspace.fresh(id)
            val received = AtomicLong(0L)
            val lastPublish = AtomicLong(0L)
            fun onBytes(bytes: Long) {
                val sum = received.addAndGet(bytes)
                val now = System.currentTimeMillis()
                val old = lastPublish.get()
                if (now - old > 500 && lastPublish.compareAndSet(old, now))
                    update(id) { it.copy(received = sum) } ?: throw CancellationException()
            }
            val fetcher = DashSegmentFetcher(client, requireSpace = { bytes -> workspace.requireSpace(id, bytes) })
            update(id) {
                it.copy(taskStatus = TaskStatus.RUNNING, received = 0, expected = null, completedSegments = 0,
                    safeFailure = null, failure = null, pauseReason = null)
            } ?: throw CancellationException()

            fun fetchRepresentation(representation: DashRepresentationPlan, init: java.io.File, segmentFor: (Int) -> java.io.File) {
                cancel.check()
                fetcher.fetchInit(request, representation.initUrl, init, cancel, ::onBytes)
                fetcher.fetchMediaSegments(
                    request, representation.segments.map { it.url }, segmentFor, cancel, ::onBytes,
                ) { index ->
                    val record = update(id) {
                        it.copy(completedSegments = it.completedSegments + 1, resumeAvailable = false)
                    }
                    if (record == null) throw CancellationException()
                }
            }

            fetchRepresentation(plan.video, task.videoInit(), task::videoSegment)
            plan.audio?.let { fetchRepresentation(it, task.audioInit(), task::audioSegment) }
            cancel.check()

            update(id) { it.copy(taskStatus = TaskStatus.MUXING) } ?: throw CancellationException()
            files.removeStage(id) // A partial MP4 is never resumed, even when all pieces are complete.
            val stage = files.stage(id)
            val lengths = listOf(task.videoInit(), task.audioInit()).filter { it.exists() }.sumOf { it.length() } +
                (plan.video.segments.indices.map { task.videoSegment(it).length() }.sum() +
                    (plan.audio?.segments?.indices?.map { task.audioSegment(it).length() }?.sum() ?: 0L))
            workspace.requireSpace(id, lengths + lengths / 20)
            try {
                if (plan.audio != null) {
                    val videoTrack = task.assembledVideo()
                    val audioTrack = task.assembledAudio()
                    assembler.assemble(
                        task.videoInit(), plan.video.segments.indices.map(task::videoSegment), videoTrack,
                        plan.durationUs, cancel,
                    ) { workspace.requireSpace(id, 65536L) }
                    assembler.assemble(
                        task.audioInit(), plan.audio.segments.indices.map(task::audioSegment), audioTrack,
                        plan.durationUs, cancel,
                    ) { workspace.requireSpace(id, 65536L) }
                    workspace.requireSpace(id, videoTrack.length() + audioTrack.length() + 16L * 1024 * 1024)
                    mux(videoTrack, audioTrack, stage, cancel)
                } else {
                    // Muxed representation (both tracks in one stream) or an honest video-only
                    // MPD: the assembler writes the staged MP4 directly, no dual-track merge.
                    assembler.assemble(
                        task.videoInit(), plan.video.segments.indices.map(task::videoSegment), stage,
                        plan.durationUs, cancel,
                    ) { workspace.requireSpace(id, 65536L) }
                }
            } catch (e: TransferFailure) { throw e }
            catch (e: CancellationException) { throw e }
            catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "MP4 封装文件无法完整写入") }
            catch (_: Exception) { throw TransferFailure(FailureKind.NOT_VIDEO, "音视频封装失败，未保存成品") }

            cancel.check(); update(id) { it.copy(taskStatus = TaskStatus.VERIFYING) } ?: throw CancellationException()
            val inspection = if (plan.audio != null || plan.muxed)
                files.inspectDualTrack(stage, plan.durationUs, cancel)
            else files.inspect(stage)
            if (inspection.format != FormatCheck.PASSED || inspection.mimeType != "video/mp4")
                throw TransferFailure(FailureKind.NOT_VIDEO, "MP4 轨道、时长或采样未通过校验")
            workspace.requireSpace(id, stage.length())
            val record = update(id) { it.copy(taskStatus = TaskStatus.PUBLISHING, mimeType = "video/mp4") }
                ?: throw CancellationException()
            val asset = try {
                files.publish(record, stage, inspection, { uri ->
                    cancel.check(); update(id) { it.copy(pendingUri = uri) } ?: throw CancellationException()
                }, cancel)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw TransferFailure(FailureKind.STORAGE, "公共 MP4 未能完整保存") }
            published = asset; cancel.check()
            if (!repository.complete(id, asset)) throw CancellationException()
            published = null
        } catch (_: CancellationException) {
            // User/coordinator has already committed the appropriate terminal/waiting state.
        } catch (e: TransferFailure) {
            update(id) {
                it.copy(taskStatus = TaskStatus.FAILED, failure = e.kind,
                    pauseReason = if (e.kind == FailureKind.STORAGE) PauseReason.STORAGE
                    else if (e.kind == FailureKind.NETWORK) PauseReason.NETWORK else PauseReason.SOURCE_CHANGED,
                    safeFailure = e.safeMessage.take(180), resumeAvailable = false)
            }
        } catch (_: IOException) {
            update(id) {
                it.copy(taskStatus = TaskStatus.FAILED, failure = FailureKind.NETWORK, pauseReason = PauseReason.NETWORK,
                    safeFailure = "网络中断，请返回来源重新下载", resumeAvailable = false)
            }
        } catch (_: Exception) {
            update(id) {
                it.copy(taskStatus = TaskStatus.FAILED, failure = FailureKind.STORAGE, pauseReason = PauseReason.STORAGE,
                    safeFailure = "任务无法完成，请检查本机存储", resumeAvailable = false)
            }
        } finally {
            published?.let { runCatching { files.delete(it) } }
            val record = repository.record(id)
            runCatching { if (record != null && record.taskStatus != TaskStatus.SUCCEEDED) files.cleanupPending(record) }
            runCatching { files.removeStage(id) }
            runCatching { workspace.delete(id) }
            if (record != null) runCatching {
                repository.change(id) { old ->
                    if (old.taskStatus == TaskStatus.CANCELLED || old.taskStatus == TaskStatus.SUCCEEDED)
                        old.copy(pendingUri = null, resumeAvailable = false)
                    else if (canUpdate(old))
                        old.copy(taskStatus = TaskStatus.INTERRUPTED, failure = FailureKind.INTERRUPTED,
                            pauseReason = PauseReason.SOURCE_CHANGED, pendingUri = null, resumeAvailable = false,
                            safeFailure = REPARSE_MESSAGE)
                    else old.copy(pendingUri = null, resumeAvailable = false)
                }
            }
            // A concurrent cancellation committed during cleanup must still own the final state.
            if (repository.record(id)?.taskStatus == TaskStatus.CANCELLED) runCatching { workspace.delete(id) }
        }
    }

    private fun mux(video: java.io.File, audio: java.io.File, output: java.io.File, cancel: TransferCancellation): MuxedTracks =
        try { DualTrackMuxer().mux(video, audio, output, cancel) }
        catch (e: CancellationException) { throw e }
        catch (_: com.example.purebrowser.download.mux.DualTrackMuxException) {
            throw TransferFailure(FailureKind.NOT_VIDEO, MUX_FAILURE_MESSAGE)
        }

    private val writingStates = setOf(
        TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING,
    )

    private fun canUpdate(record: DownloadRecord) =
        !record.cancelled && record.protocol == DownloadProtocol.DASH && record.taskStatus in writingStates

    private fun update(id: TaskId, block: (DownloadRecord) -> DownloadRecord): DownloadRecord? {
        var changed = false
        return repository.change(id) { old ->
            if (canUpdate(old)) { changed = true; block(old) } else old
        }?.takeIf { changed }
    }

    companion object {
        const val REPARSE_MESSAGE = "DASH 不保留续传字节，请返回来源重新解析并确认下载"
        internal const val MUX_FAILURE_MESSAGE = "DASH 编码、声画时序或封装未通过校验"
    }
}

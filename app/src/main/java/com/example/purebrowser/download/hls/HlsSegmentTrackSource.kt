package com.example.purebrowser.download.hls

import com.example.purebrowser.download.*
import java.io.File
import java.io.IOException

/**
 * Ordered TS segment transport for one separate-audio HLS dual-track task. Each track is a list of
 * segment addresses (never closed-range byte chunks), fetched strictly in playlist order into the
 * task-owned dual-track workspace, then remuxed per-track into a single-track MP4 (TsToMp4Remuxer)
 * so the existing DualTrackMuxer can produce one final MP4. Fresh-only semantics of the dual-track
 * lease are preserved: no checkpoints, no resume, honest re-parse after any interruption.
 */
class HlsSegmentTrackSource(private val client: HlsHttpClient, private val workspace: DualTrackWorkspace) {

    fun fetchTracks(
        id: TaskId, record: DownloadRecord, plan: HlsDualTrackPlan,
        videoOutput: File, audioOutput: File,
        maxVideoBytes: Long, maxAudioBytes: Long,
        cancel: TransferCancellation, check: () -> Unit, progress: (Int) -> Unit,
    ): Pair<File, File> {
        check()
        val video = fetchTrack(id, record, plan.videoSegments, plan.videoUrl,
            video = true, maximum = maxVideoBytes, cancel, check, progress)
        check()
        val audio = fetchTrack(id, record, plan.audioSegments, plan.audioUrl,
            video = false, maximum = maxAudioBytes, cancel, check, progress)
        check()
        // One shared timeline offset keeps the original A/V alignment across separately
        // encoded renditions; each track's own MP4 then starts from that common base.
        val remuxer = TsToMp4Remuxer()
        val videoProbe = remuxer.probeTrack(video, TsToMp4Remuxer.TrackRole.VIDEO, cancel)
        val audioProbe = remuxer.probeTrack(audio, TsToMp4Remuxer.TrackRole.AUDIO, cancel)
        val offset = minOf(videoProbe.firstUs, audioProbe.firstUs)
        val bytes = (video + audio).sumOf { it.length() }
        workspace.requireSpace(id, bytes + bytes / 20)
        try {
            remuxer.remuxTrack(video, videoOutput, TsToMp4Remuxer.TrackRole.VIDEO, offset, plan.videoDurationUs, cancel)
            remuxer.remuxTrack(audio, audioOutput, TsToMp4Remuxer.TrackRole.AUDIO, offset, plan.audioDurationUs, cancel)
        } finally {
            // TS bytes are never needed again once both per-track MP4s exist (or the task fails).
            runCatching { workspace.deleteSegments(id) }
        }
        return videoOutput to audioOutput
    }

    /** Sequential bounded-retry fetch; content checks prove MPEG-TS before a piece is accepted. */
    private fun fetchTrack(
        id: TaskId, record: DownloadRecord, segments: List<HlsSegment>, anchorUrl: String,
        video: Boolean, maximum: Long, cancel: TransferCancellation, check: () -> Unit, progress: (Int) -> Unit,
    ): List<File> {
        var received = 0L
        var retries = 0
        return segments.map { segment ->
            val file = workspace.segment(id, video, segment.index)
            var attempt = 0
            while (true) {
                check(); cancel.check()
                try {
                    fetchSegment(record, segment.url, anchorUrl, file, id, cancel, maximum - received) { n ->
                        received += n; progress(n.toInt())
                    }
                    break
                } catch (failure: HlsTransientFailure) {
                    file.delete()
                    if (attempt >= 2 || retries++ > 20) throw TransferFailure(FailureKind.NETWORK,
                        "分轨分片未能完整接收；请返回来源重新解析并确认下载")
                    attempt++
                    backoff(cancel, attempt)
                } catch (failure: IOException) {
                    file.delete()
                    // TLS identity failures are not transient. We never loosen certificate validation.
                    if (failure is javax.net.ssl.SSLException || attempt >= 2 || retries++ > 20) throw failure
                    attempt++
                    backoff(cancel, attempt)
                }
            }
            file
        }
    }

    private fun backoff(cancel: TransferCancellation, attempt: Int) {
        val end = System.currentTimeMillis() + if (attempt == 1) 1000 else 3000
        while (System.currentTimeMillis() < end) { cancel.check(); Thread.sleep(100) }
    }

    private fun fetchSegment(
        record: DownloadRecord, url: String, anchorUrl: String, file: File, id: TaskId,
        cancel: TransferCancellation, remainingBudget: Long, onBytes: (Long) -> Unit,
    ) {
        val anchored = if (record.mediaUrl == anchorUrl) record else record.copy(mediaUrl = anchorUrl)
        client.get(anchored, url, cancel) { response, _ ->
            val length = HlsHttpClient.contentLength(response)
            if (length != null && length > 128L * 1024 * 1024) throw TransferFailure(FailureKind.UNSUPPORTED, "单片超过 128 MiB 上限")
            workspace.requireSpace(id, length ?: 65536)
            val stream = try { file.outputStream() } catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "临时分片无法创建") }
            var count = 0L
            stream.use { output -> response.body().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    cancel.check(); val n = input.read(buffer); if (n < 0) break
                    count += n
                    if (count > 128L * 1024 * 1024) throw TransferFailure(FailureKind.UNSUPPORTED, "单片超过 128 MiB 上限")
                    if (count > remainingBudget) throw TransferFailure(FailureKind.UNSUPPORTED, "双轨资源超出字节预算")
                    workspace.requireSpace(id, n.toLong())
                    try { output.write(buffer, 0, n) } catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "临时分片写入失败") }
                    onBytes(n.toLong())
                }
                try { output.fd.sync() } catch (_: IOException) { throw TransferFailure(FailureKind.STORAGE, "临时分片无法完整保存") }
            } }
            if (length != null && count != length) throw HlsTransientFailure()
            if (count < 188 * 5 || count % 188 != 0L) throw TransferFailure(FailureKind.NOT_VIDEO, "分片不是完整 MPEG-TS 视频")
            file.inputStream().use { input ->
                val prefix = ByteArray(188 * 5)
                if (input.read(prefix) != prefix.size || (0..4).any { prefix[it * 188] != 0x47.toByte() })
                    throw TransferFailure(FailureKind.NOT_VIDEO, "分片响应不是 MPEG-TS 视频")
            }
        }
    }
}

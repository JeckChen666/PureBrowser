package com.example.purebrowser.download.hls

import com.example.purebrowser.download.*
import com.example.purebrowser.download.dash.Fmp4Boxes
import com.example.purebrowser.download.dash.Fmp4SegmentAssembler
import java.io.File
import java.io.IOException

/**
 * Ordered segment transport for one separate-audio HLS dual-track task. Each track is a list of
 * segment addresses (never open-ended byte chunks), fetched strictly in playlist order into the
 * task-owned dual-track workspace, then turned into a single-track MP4 — TS tracks through
 * TsToMp4Remuxer, fMP4 tracks (T107) through the DASH-proven Fmp4SegmentAssembler after their
 * EXT-X-MAP init segment is fetched (one exact closed byte interval when BYTERANGE is declared,
 * RFC 8216 §4.3.2.5) — so the existing DualTrackMuxer can produce one final MP4. Fresh-only
 * semantics of the dual-track lease are preserved: no checkpoints, no resume, honest re-parse
 * after any interruption. Byte caps and segment cleanup are identical for both containers.
 */
class HlsSegmentTrackSource(private val client: HlsHttpClient, private val workspace: DualTrackWorkspace) {

    /** One track's fetched bytes: the fMP4 init (when declared) plus the ordered segment files. */
    private class TrackFiles(val init: File?, val segments: List<File>)

    fun fetchTracks(
        id: TaskId, record: DownloadRecord, plan: HlsDualTrackPlan,
        videoOutput: File, audioOutput: File,
        maxVideoBytes: Long, maxAudioBytes: Long,
        cancel: TransferCancellation, check: () -> Unit, progress: (Int) -> Unit,
    ): Pair<File, File> {
        check()
        val video = fetchTrack(id, record, plan.videoSegments, plan.videoUrl,
            video = true, format = plan.videoFormat, initSegment = plan.videoInitSegment,
            maximum = maxVideoBytes, cancel, check, progress)
        check()
        val audio = fetchTrack(id, record, plan.audioSegments, plan.audioUrl,
            video = false, format = plan.audioFormat, initSegment = plan.audioInitSegment,
            maximum = maxAudioBytes, cancel, check, progress)
        check()
        // The TS path keeps the original A/V alignment through one shared timeline offset. An
        // fMP4 track rides the assembler seam instead, which normalizes each track to its own
        // first sample (the exact behavior already shipped for DASH dual representations), so a
        // mixed plan normalizes its TS track(s) to their own probe base to match that convention.
        val remuxer = TsToMp4Remuxer()
        val offset = when {
            video.init == null && audio.init == null -> {
                val videoProbe = remuxer.probeTrack(video.segments, TsToMp4Remuxer.TrackRole.VIDEO, cancel)
                val audioProbe = remuxer.probeTrack(audio.segments, TsToMp4Remuxer.TrackRole.AUDIO, cancel)
                minOf(videoProbe.firstUs, audioProbe.firstUs)
            }
            video.init == null -> remuxer.probeTrack(video.segments, TsToMp4Remuxer.TrackRole.VIDEO, cancel).firstUs
            audio.init == null -> remuxer.probeTrack(audio.segments, TsToMp4Remuxer.TrackRole.AUDIO, cancel).firstUs
            else -> 0L
        }
        val bytes = (video.segments + audio.segments + listOfNotNull(video.init, audio.init)).sumOf { it.length() }
        workspace.requireSpace(id, bytes + bytes / 20)
        try {
            produceTrack(remuxer, video, videoOutput, TsToMp4Remuxer.TrackRole.VIDEO, plan.videoDurationUs, offset, cancel)
            produceTrack(remuxer, audio, audioOutput, TsToMp4Remuxer.TrackRole.AUDIO, plan.audioDurationUs, offset, cancel)
        } finally {
            // Segment and init bytes are never needed again once both per-track MP4s exist (or the task fails).
            runCatching { workspace.deleteSegments(id) }
        }
        return videoOutput to audioOutput
    }

    /** The per-track remux seam: TS remux or fMP4 assembly, one single-track MP4 either way. */
    private fun produceTrack(
        remuxer: TsToMp4Remuxer, track: TrackFiles, output: File, role: TsToMp4Remuxer.TrackRole,
        expectedDurationUs: Long, offsetUs: Long, cancel: TransferCancellation,
    ) {
        val init = track.init
        if (init == null) remuxer.remuxTrack(track.segments, output, role, offsetUs, expectedDurationUs, cancel)
        else Fmp4SegmentAssembler().assemble(init, track.segments, output, expectedDurationUs, cancel)
    }

    /** Sequential bounded-retry fetch; content checks prove the container before a piece is accepted. */
    private fun fetchTrack(
        id: TaskId, record: DownloadRecord, segments: List<HlsSegment>, anchorUrl: String,
        video: Boolean, format: SegmentFormat, initSegment: HlsInitSegment?, maximum: Long,
        cancel: TransferCancellation, check: () -> Unit, progress: (Int) -> Unit,
    ): TrackFiles {
        var received = 0L
        var retries = 0
        fun <T> bounded(file: File, fetch: () -> T): T {
            var attempt = 0
            while (true) {
                check(); cancel.check()
                try {
                    return fetch()
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
        }
        val init = initSegment?.let { declared ->
            val file = workspace.init(id, video)
            bounded(file) {
                fetchInitSegment(record, declared, anchorUrl, file, id, cancel, maximum - received) { n ->
                    received += n; progress(n.toInt())
                }
                file
            }
        }
        val files = segments.map { segment ->
            val file = workspace.segment(id, video, segment.index, fmp4 = format == SegmentFormat.FMP4)
            bounded(file) {
                fetchSegment(record, segment.url, anchorUrl, file, id, cancel, maximum - received, format) { n ->
                    received += n; progress(n.toInt())
                }
                file
            }
        }
        return TrackFiles(init, files)
    }

    private fun backoff(cancel: TransferCancellation, attempt: Int) {
        val end = System.currentTimeMillis() + if (attempt == 1) 1000 else 3000
        while (System.currentTimeMillis() < end) { cancel.check(); Thread.sleep(100) }
    }

    private fun fetchSegment(
        record: DownloadRecord, url: String, anchorUrl: String, file: File, id: TaskId,
        cancel: TransferCancellation, remainingBudget: Long, format: SegmentFormat, onBytes: (Long) -> Unit,
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
            if (format == SegmentFormat.FMP4) verifyFmp4Segment(file)
            else {
                if (count < 188 * 5 || count % 188 != 0L) throw TransferFailure(FailureKind.NOT_VIDEO, "分片不是完整 MPEG-TS 视频")
                file.inputStream().use { input ->
                    val prefix = ByteArray(188 * 5)
                    if (input.read(prefix) != prefix.size || (0..4).any { prefix[it * 188] != 0x47.toByte() })
                        throw TransferFailure(FailureKind.NOT_VIDEO, "分片响应不是 MPEG-TS 视频")
                }
            }
        }
    }

    /**
     * The EXT-X-MAP init of an fMP4 track. Without BYTERANGE it is the whole resource; with it,
     * one exact closed `Range: bytes=start-end` request whose served window (Content-Range,
     * Content-Length, body size) must match the declaration exactly — never sliced or guessed.
     */
    private fun fetchInitSegment(
        record: DownloadRecord, declared: HlsInitSegment, anchorUrl: String, file: File, id: TaskId,
        cancel: TransferCancellation, remainingBudget: Long, onBytes: (Long) -> Unit,
    ) {
        val range = declared.closedByteRange()
        val expected = range?.let { it.last - it.first + 1L }
        val anchored = if (record.mediaUrl == anchorUrl) record else record.copy(mediaUrl = anchorUrl)
        client.get(anchored, declared.url, cancel, range) { response, _ ->
            if (range != null) requireServedRange(response, range)
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
            if (expected != null && count != expected) throw HlsTransientFailure()
            verifyFmp4Init(file)
        }
    }

    /** The served window must be exactly the declared closed interval (start, end and size). */
    private fun requireServedRange(response: HttpResponse, wanted: LongRange) {
        val mismatch = TransferFailure(FailureKind.HTTP_REJECTED, "初始化段响应与声明的字节范围不符")
        val groups = servedRangeRegex.matchEntire(response.header("Content-Range") ?: "")?.destructured ?: throw mismatch
        val first = groups.component1().toLongOrNull()
        val last = groups.component2().toLongOrNull()
        val total = groups.component3().toLongOrNull()
        if (first != wanted.first || last != wanted.last || total == null || total <= wanted.last) throw mismatch
        val length = HlsHttpClient.contentLength(response)
        if (length != null && length != wanted.last - wanted.first + 1L) throw mismatch
    }

    private fun verifyFmp4Init(file: File) {
        try {
            Fmp4Boxes.validateInit(Fmp4Boxes.topLevelBoxes(file, "初始化分片"))
        } catch (_: Exception) {
            file.delete()
            throw TransferFailure(FailureKind.NOT_VIDEO, "初始化分片不是完整 fMP4 数据")
        }
    }

    private fun verifyFmp4Segment(file: File) {
        try {
            Fmp4Boxes.validateSegment(Fmp4Boxes.topLevelBoxes(file, "分片"))
        } catch (_: Exception) {
            file.delete()
            throw TransferFailure(FailureKind.NOT_VIDEO, "分片不是完整 fMP4 数据")
        }
    }

    private companion object {
        private val servedRangeRegex = Regex("bytes ([0-9]{1,19})-([0-9]{1,19})/([0-9]{1,19})")
    }
}

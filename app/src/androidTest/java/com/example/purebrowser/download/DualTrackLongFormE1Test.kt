package com.example.purebrowser.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.purebrowser.download.mux.AuthoredMuxFixtures
import com.example.purebrowser.download.site.DualTrackDownloadPlan
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.concurrent.thread

/**
 * T78/E1 enhanced-tier: a >30-minute dual-track pair flows through the REAL transfer + muxer. The
 * long inputs are authored on-device by looping the self-authored 2 s avc1/aac fixtures (no
 * third-party media), served from a loopback-only ServerSocket that honours closed Range requests
 * exactly like the CDN contract the transfer expects. Asserts the published MP4's duration/size
 * bounds and that no staged/cache bytes survive the run.
 */
class DualTrackLongFormE1Test {
    private class Served(val file: File, val port: Int)

    /** Loopback server: one request per connection, 206 for any closed byte range, 200 for none. */
    private fun serve(file: File): Served {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            val bytes = file.readBytes()
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                socket.use {
                    it.soTimeout = 10_000
                    val reader = it.getInputStream().bufferedReader()
                    var range: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                    }
                    if (range == null) {
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                        }.flush()
                    } else {
                        val match = Regex("bytes=([0-9]+)-([0-9]*)").find(range)
                        if (match == null) {
                            it.getOutputStream().write("HTTP/1.1 416\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            val start = match.groupValues[1].toLong().toInt()
                            val end = (match.groupValues[2].ifEmpty { "${bytes.size - 1}" }).toLong().coerceAtMost(bytes.size - 1L).toInt()
                            val slice = bytes.copyOfRange(start, end + 1)
                            it.getOutputStream().apply {
                                write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Length: ${slice.size}\r\nContent-Range: bytes $start-$end/${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(slice)
                            }.flush()
                        }
                    }
                }
            }
        }
        worker.join(200) // let the worker thread come up before the port is used
        return Served(file, server.localPort)
    }

    /** One sequential extractor pass: the ORIGINAL track format (with csd), timestamps, sync flags, payloads, median frame delta. */
    private class Read(val format: MediaFormat, val times: List<Long>, val syncs: List<Boolean>, val payloads: List<ByteArray>, val frameUs: Long)

    private fun readFixture(source: ByteArray, seed: File): Read {
        seed.writeBytes(source)
        val extractor = MediaExtractor()
        extractor.setDataSource(seed.path)
        assertEquals(1, extractor.trackCount.toLong())
        extractor.selectTrack(0)
        val format: MediaFormat = extractor.getTrackFormat(0)
        val times = mutableListOf<Long>()
        val syncs = mutableListOf<Boolean>()
        val payloads = mutableListOf<ByteArray>()
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val payload = ByteArray(size)
            buffer.position(0); buffer.limit(size); buffer.get(payload)
            times += extractor.sampleTime
            syncs += extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            payloads += payload
            extractor.advance()
        }
        extractor.release()
        assertTrue(times.size >= 2)
        val deltas = (1 until times.size).map { times[it] - times[it - 1] }.sorted()
        return Read(format, times, syncs, payloads, deltas[deltas.size / 2])
    }

    /**
     * Re-authors the ~2 s fixture as a single-track MP4 looped [loops] times. Each track loops on
     * its OWN span lattice (last sample end = one median frame past the last timestamp): sample
     * deltas then stay exactly on the fixture's frame grid — the muxer's near-constant frame-delta
     * validation is a real AAC/H.264 property, so synthetic gaps inside a loop would be rejected.
     * Per-track lattices differ by a fraction of a frame, so the audio loop count is chosen to
     * land both authored tracks within the muxer's A/V tolerance (and the test's 1 s assert).
     */
    private fun loopFixture(read: Read, loops: Int, output: File): File {
        val minTime = read.times.min()
        val spanUs = (read.times.max() - minTime) + read.frameUs
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.addTrack(read.format)
        muxer.start()
        val copy = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = android.media.MediaCodec.BufferInfo()
        for (loop in 0 until loops) {
            for (index in read.times.indices) {
                copy.clear(); copy.put(read.payloads[index]); copy.position(0)
                info.set(0, read.payloads[index].size, (read.times[index] - minTime) + loop * spanUs,
                    if (read.syncs[index]) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(0, copy, info)
            }
        }
        muxer.stop(); muxer.release()
        return output
    }

    private fun durationMsOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try { retriever.setDataSource(file.path); retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
        finally { retriever.release() }
    }

    @Test fun thirtyOneMinuteAuthoredPairTransfersAndMuxesIntoOneVerifiedMp4() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val work = File(app.cacheDir, "e1-long-${UUID.randomUUID()}").apply { mkdirs() }
        val dir = File(work, "store").apply { mkdirs() }
        val loops = 930 // 930 x 2 s = 31 minutes
        val repo = DownloadRepository(DownloadStore(dir), DualTrackTestSupport.NoSystem(),
            files = ManagedFileStore(app), allowLocalHttp = true)
        val videoRead = readFixture(Base64.decode(AuthoredMuxFixtures.VIDEO, Base64.DEFAULT), File(work, "seed-video.mp4"))
        val audioRead = readFixture(Base64.decode(AuthoredMuxFixtures.AUDIO, Base64.DEFAULT), File(work, "seed-audio.m4a"))
        // Match the audio loop count to the video lattice so both authored tracks land inside the
        // muxer's A/V tolerance and the 1 s drift assert below.
        val videoSpanUs = (videoRead.times.max() - videoRead.times.min()) + videoRead.frameUs
        val audioSpanUs = (audioRead.times.max() - audioRead.times.min()) + audioRead.frameUs
        val audioLoops = Math.round(loops.toDouble() * videoSpanUs / audioSpanUs).toInt()
        val video = serve(loopFixture(videoRead, loops, File(work, "long-video.mp4")))
        val audio = serve(loopFixture(audioRead, audioLoops, File(work, "long-audio.m4a")))
        val cancel = TransferCancellation()
        var worker: Thread? = null
        try {
            val videoBytes = video.file.length(); val audioBytes = audio.file.length()
            val videoMs = durationMsOf(video.file); val audioMs = durationMsOf(audio.file)
            assertTrue("authored video must exceed 30 minutes", videoMs > 30 * 60 * 1000L)
            assertTrue("authored audio must exceed 30 minutes", audioMs > 30 * 60 * 1000L)
            assertTrue("A/V authored drift stays inside the muxer tolerance", Math.abs(videoMs - audioMs) <= 1_000L)
            val plan = DualTrackDownloadPlan(
                resourceId = "e1-long-form", videoFormatId = "e1-video", audioFormatId = "e1-audio",
                videoUrl = "http://127.0.0.1:${video.port}/long-video.mp4",
                audioUrl = "http://127.0.0.1:${audio.port}/long-audio.m4a",
                videoCodec = "avc1.640028", audioCodec = "mp4a.40.2",
                videoLength = videoBytes, audioLength = audioBytes,
                durationUs = minOf(videoMs, audioMs) * 1_000L,
            )
            val draft = DownloadDraft(
                MediaCandidate(plan.videoUrl, MediaKind.FILE, emptySet(), "video/mp4", reliableSource = true),
                "E1LongForm", sourceUrl = "https://site.example/watch", sourceTitle = "E1 long-form fixture",
                useAccessContext = false, dualTrackPlan = plan,
            )
            val id = repo.enqueue(draft, wifiOnly = false, fileName = "E1-long-form.mp4")
            val started = System.currentTimeMillis()
            worker = Thread { DualTrackTransfer(repo, UrlConnectionTransport(), AccessContextProvider { null }).run(id, cancel) }
            worker.start()
            while (repo.record(id)!!.taskStatus in DownloadRepository.activeStatuses && worker.isAlive) Thread.sleep(2_000)
            worker.join(60_000)
            val elapsed = System.currentTimeMillis() - started
            val record = repo.record(id)!!
            println("E1 status=${record.taskStatus} failure=${record.failure} safeFailure=${record.safeFailure} " +
                "videoBytes=$videoBytes audioBytes=$audioBytes videoMs=$videoMs audioMs=$audioMs elapsedMs=$elapsed")
            assertEquals("E1 transfer did not succeed: ${record.safeFailure}", TaskStatus.SUCCEEDED, record.taskStatus)
            val asset = repo.stateSnapshot().assets.single()
            assertEquals(FormatCheck.PASSED, asset.format)
            assertEquals("video/mp4", asset.mimeType)
            val published = repo.files!!.access(asset)
            assertEquals(FileAvailability.AVAILABLE, published.availability)
            val size = published.sizeBytes ?: asset.sizeBytes ?: 0L
            assertTrue("published size out of bounds: $size", size in 1..(videoBytes + audioBytes + 16L * 1024 * 1024))
            assertTrue("published duration missing or out of bounds: ${asset.durationMillis}",
                asset.durationMillis != null && Math.abs(asset.durationMillis!! - minOf(videoMs, audioMs)) <= 5_000L)
            // Resource bounds: nothing staged, cached or pending survives a finished E1 run.
            assertEquals(0L, repo.files!!.dualTrackWorkspace.cacheBytes(id))
            assertTrue(!repo.files!!.stage(id).exists())
            assertEquals(videoBytes + audioBytes, record.received)
            work.deleteRecursively()
        } finally {
            cancel.cancel()
            runCatching { worker?.join(30_000) }
            repo.records().forEach { r ->
                runCatching { repo.stateSnapshot().assets.firstOrNull { it.recordId == r.recordId }?.let { repo.files!!.delete(it) } }
                runCatching { repo.files!!.cleanupPending(r) }
                runCatching { repo.files!!.clearPrivate(r.recordId) }
            }
            work.deleteRecursively()
        }
    }
}

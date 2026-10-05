package com.example.purebrowser.media

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.UrlConnectionTransport
import com.example.purebrowser.download.WebsiteAccessContext
import com.example.purebrowser.download.hls.HlsDualTrackPlan
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.download.hls.HlsSegmentTrackSource
import com.example.purebrowser.download.DualTrackWorkspace
import com.example.purebrowser.download.hls.HlsHttpClient
import com.example.purebrowser.download.mux.DualTrackMuxer
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * v0.1.9 T103 diagnostic (not an acceptance gate): runs the exact production dual-track chain —
 * HlsResolver plan → HlsSegmentTrackSource TS fetch + per-track remux → DualTrackMuxer — against a
 * caller-supplied separate-audio master and prints the muxer's precise refusal reason, which the
 * production transfer deliberately maps to one generic safe message. Instrumentation arg `url` is
 * the HLS master; no arg = no-op. Host output is not printed at all here (only probe numbers and
 * the English validation reason, which contain no addresses).
 */
class V019DualTrackDiagnosticTest {
    @Test fun dualTrackMuxDiagnostic() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val url = InstrumentationRegistry.getArguments().getString("url") ?: return
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "site"

        val transport = UrlConnectionTransport()
        val access = WebsiteAccessContext()
        val cancel = TransferCancellation()
        val workspace = DualTrackWorkspace(
            File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "dt-diag-${UUID.randomUUID()}")
                .apply { mkdirs() },
        )
        val id = "diag-${UUID.randomUUID().toString().take(8)}"
        val resolver = HlsResolver(transport, access, false)
        val candidate = MediaCandidate(
            url = url, kind = MediaKind.HLS, sources = setOf(Evidence.REQUEST),
            mimeType = "application/vnd.apple.mpegurl", verifiedMime = "application/vnd.apple.mpegurl",
            probeState = ProbeState.VERIFIED,
        )
        val draft = com.example.purebrowser.download.DownloadDraft(
            candidate, android.webkit.WebSettings.getDefaultUserAgent(instrument.targetContext), url,
        )
        val options = resolver.resolveEntry(draft, cancel)
        val variant = (options.playlist as? HlsPlaylist.Master)
            ?.let { HlsPlaylistParser.defaultVariant(it.variants) }!!
        val plan = resolver.resolvePlan(draft, options, variant, cancel)
        val dual = HlsDualTrackPlan.from(plan, url)
        println("DIAG[$label] plan segments v/a=${plan.media.segments.size}/${plan.audio?.media?.segments?.size} " +
            "durationUs v/a=${plan.media.durationUs}/${plan.audio?.media?.durationUs}")
        val source = HlsSegmentTrackSource(HlsHttpClient(transport, access, false), workspace)
        val (video, audio) = workspace.fresh(id)
        try {
            source.fetchTracks(
                id,
                com.example.purebrowser.download.DownloadRecord(
                    recordId = id, name = "diag.mp4", mediaUrl = url, userAgent = draft.userAgent,
                    transfer = com.example.purebrowser.download.TransferType.CONTROLLED,
                ),
                dual, video, audio, 512L * 1024 * 1024, 512L * 1024 * 1024, cancel, {}, {},
            )
            println("DIAG[$label] tracks video=${video.length()}B audio=${audio.length()}B")
            listOf("video" to video, "audio" to audio).forEach { (name, file) ->
                runCatching {
                    val extractor = android.media.MediaExtractor()
                    try {
                        extractor.setDataSource(file.absolutePath)
                        extractor.selectTrack(0)
                        val format = extractor.getTrackFormat(0)
                        var min = Long.MAX_VALUE; var max = Long.MIN_VALUE; var count = 0
                        while (extractor.sampleTrackIndex >= 0) {
                            val t = extractor.sampleTime
                            if (t < min) min = t
                            if (t > max) max = t
                            count++
                            extractor.advance()
                        }
                        println("DIAG[$label] track=$name declared=${format.getLong(android.media.MediaFormat.KEY_DURATION, -1)}us " +
                            "span=${max - min}us min=$min max=$max samples=$count rate=${format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, -1)}")
                    } finally { extractor.release() }
                }.onFailure { println("DIAG[$label] track=$name probe-fail ${it.message}") }
            }
            listOf("video" to video, "audio" to audio).forEach { (name, file) ->
                runCatching {
                    // Exact LocalSource replica: 64KB-capped reads, bounded calls/bytes, MediaDataSource.
                    val raf = java.io.RandomAccessFile(file, "r")
                    val length = raf.length()
                    var remaining = length * 4 + 1024 * 1024
                    var calls = 0
                    val source = object : android.media.MediaDataSource() {
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                            calls++
                            if (size == 0) return 0
                            if (position >= length) return -1
                            val count = minOf(size.toLong(), length - position, 64L * 1024).toInt()
                            remaining -= count
                            raf.seek(position)
                            return raf.read(buffer, offset, count)
                        }
                        override fun getSize() = length
                        override fun close() = raf.close()
                    }
                    val extractor = android.media.MediaExtractor()
                    try {
                        extractor.setDataSource(source)
                        extractor.selectTrack(0)
                        val format = extractor.getTrackFormat(0)
                        var min = Long.MAX_VALUE; var max = Long.MIN_VALUE; var count = 0
                        var prev = -1L; var first = -1L; var step = Long.MAX_VALUE
                        val deltas = mutableListOf<Long>()
                        while (extractor.sampleTrackIndex >= 0) {
                            val t = extractor.sampleTime
                            if (count > 0) { deltas.add(t - prev); if (t - prev > 0) step = minOf(step, t - prev) }
                            else first = t
                            min = minOf(min, t); max = maxOf(max, t); count++
                            prev = t
                            extractor.advance()
                        }
                        val rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE, if (name == "video") -1 else 48000)
                        val tail = if (name == "video") step else 1_024_000_000L / rate
                        val end = max + tail
                        val duration = end - min
                        val declared = format.getLong(android.media.MediaFormat.KEY_DURATION, -1)
                        val tol = maxOf(250_000L, minOf(2_000_000L, duration / 20))
                        println("DIAG[$label] ls-track=$name declared=$declared min=$min max=$max first=$first samples=$count " +
                            "step=$step tail=$tail end=$end dur=$duration tol=$tol calls=$calls readsLeft=$remaining " +
                            "spanDecl=${kotlin.math.abs(declared - duration) <= tol} endDecl=${min >= 0 && kotlin.math.abs(declared - end) <= tol} " +
                            "maxDelta=${deltas.maxOrNull()} minDelta=${deltas.minOrNull()}")
                    } finally { extractor.release() }
                }.onFailure { println("DIAG[$label] ls-track=$name probe-fail ${it.message}") }
            }
            val output = File(video.parentFile, "out.mp4")
            try {
                val muxed = DualTrackMuxer().mux(video, audio, output, cancel)
                println("DIAG[$label] mux=OK durationUs=${muxed.durationUs} ${muxed.width}x${muxed.height} out=${output.length()}B")
            } catch (e: Exception) {
                println("DIAG[$label] mux=FAIL ${e.javaClass.simpleName}: ${e.message}")
                e.stackTrace.take(6).forEach { println("DIAG[$label]   at ${it}") }
            }
        } finally {
            runCatching { workspace.delete(id) }
        }
    }
}

package com.example.purebrowser.media

import android.content.Context
import android.widget.FrameLayout
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.browser.BrowserSession
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.download.DownloadStore
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.ManagedFileStore
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.UrlConnectionTransport
import com.example.purebrowser.download.WebsiteAccessContext
import com.example.purebrowser.download.hls.HlsPlaylist
import com.example.purebrowser.download.hls.HlsPlaylistParser
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.download.hls.HlsTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * T78/E2 enhanced-tier: a REAL site HLS transfer keeps running while the app sits behind HOME for
 * a caller-chosen wall-clock window (default 15 minutes) and publishes its MP4 afterwards. Arg-
 * driven like [V016CrossSiteSaveSmoke]: without `url` the test is a no-op so CI never contacts
 * external sites; `cookie`, `backgroundMs` and `label` are optional. Output stays host-masked.
 */
class V017BackgroundTransferE2Test {
    private fun hostHash(host: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    private fun shell(command: String) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrument.uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { runCatching { it.readBytes() } }
        Thread.sleep(1_000)
    }

    @Test fun realHlsTransferSurvivesFifteenMinutesBehindHome() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val url = InstrumentationRegistry.getArguments().getString("url") ?: return
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "bg"
        val backgroundMs = InstrumentationRegistry.getArguments().getString("backgroundMs")?.toLongOrNull() ?: 15 * 60_000L
        val timeoutMs = InstrumentationRegistry.getArguments().getString("timeoutMs")?.toLongOrNull() ?: 90_000L
        val saveTimeoutMs = InstrumentationRegistry.getArguments().getString("saveTimeoutMs")?.toLongOrNull() ?: 1_800_000L

        InstrumentationRegistry.getArguments().getString("cookie")?.takeIf { it.isNotBlank() }?.let { raw ->
            val hostOf = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: return@let
            raw.split(";").map { it.trim() }.filter { it.contains('=') }.forEach {
                android.webkit.CookieManager.getInstance().setCookie("https://$hostOf", it)
            }
        }

        val session = BrowserSession(
            recordId = "e2-bg-$label", appContext = instrument.targetContext,
            record = TabRecord(id = "e2-bg", url = url),
            message = {}, changed = {}, visited = { _, _ -> }, link = {},
        )
        try {
            instrument.runOnMainSync {
                session.mount(FrameLayout(instrument.targetContext))
            }
            val startLoad = System.currentTimeMillis()
            while (System.currentTimeMillis() - startLoad < 30_000) {
                val page = session.engine.page.value
                if (page.error != null) break
                if (System.currentTimeMillis() - startLoad > 5_000 && page.progress == 100) break
                Thread.sleep(200)
            }
            val page = session.engine.page.value
            println("BG[$label] stage=page loaded=${page.error == null} err=${page.error ?: "none"} " +
                "host=${hostHash(Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: "?")}")
            assumeTrue("page did not load: ${page.error ?: "timeout"}", page.error == null)

            val deadline = System.currentTimeMillis() + timeoutMs
            var candidates = session.sniffer.candidates.value
            while (System.currentTimeMillis() < deadline) {
                candidates = session.sniffer.candidates.value
                val addressable = candidates.filter { it.kind in setOf(MediaKind.FILE, MediaKind.HLS, MediaKind.DASH) }
                val settled = addressable.isNotEmpty() &&
                    addressable.all { it.probeState == ProbeState.VERIFIED || it.probeState == ProbeState.FAILED }
                if (settled) break
                Thread.sleep(500)
            }
            val chosen = candidates.filter { it.probeState == ProbeState.VERIFIED && it.kind == MediaKind.HLS && !it.variants.isNullOrEmpty() }
                .maxByOrNull { c -> c.variants!!.maxOf { it.height ?: 0 } }
            println("BG[$label] stage=select hlsMaster=${chosen != null} variants=${chosen?.variants?.size ?: 0}")
            assumeTrue("no VERIFIED HLS-with-variants candidate on $label", chosen != null)

            val userAgent = WebSettings.getDefaultUserAgent(instrument.targetContext)
            val frozen = chosen!!.copy(sources = chosen.sources.toSet())
            val draft = DownloadDraft(
                frozen, userAgent, page.url.takeIf(com.example.purebrowser.browser.BrowserAddress::isWebUrl), page.title.take(180),
                sourceTabId = session.recordId, sourceGeneration = session.engine.generation,
            )
            val app = ApplicationProvider.getApplicationContext<Context>()
            val dir = File(app.cacheDir, "e2-bg-${UUID.randomUUID()}").apply { mkdirs() }
            val repo = DownloadRepository(DownloadStore(dir), E2NoSystem(), files = ManagedFileStore(app.applicationContext))
            val transport = UrlConnectionTransport()
            val access: AccessContextProvider = WebsiteAccessContext()
            val cancel = TransferCancellation()
            var worker: Thread? = null
            try {
                val resolver = HlsResolver(transport, access, repo.allowLocalHttp)
                val options = resolver.resolveEntry(draft, cancel)
                val variant = (options.playlist as? HlsPlaylist.Master)
                    ?.let { HlsPlaylistParser.defaultVariant(it.variants) }
                val plan = resolver.resolvePlan(draft, options, variant, cancel)
                println("BG[$label] stage=plan segments=${plan.media.segments.size} durationS=${plan.media.durationUs / 1_000_000}")
                val id = repo.enqueue(draft, wifiOnly = false, fileName = "E2-$label.mp4", hlsPlan = plan)
                worker = Thread { HlsTransfer(repo, transport, access).run(id, cancel) }
                worker.start()

                // Let the transfer get genuinely RUNNING before it is sent behind HOME.
                val runningDeadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < runningDeadline) {
                    val record = repo.record(id)!!
                    if (record.taskStatus == com.example.purebrowser.download.TaskStatus.RUNNING &&
                        record.completedSegments >= 2) break
                    Thread.sleep(1_000)
                }
                val before = repo.record(id)!!
                println("BG[$label] stage=behind status=${before.taskStatus} bytes=${before.received} " +
                    "segments=${before.completedSegments}/${before.segmentCount ?: 0}")

                shell("input keyevent KEYCODE_HOME")
                val sleptAt = System.currentTimeMillis()
                Thread.sleep(backgroundMs)
                println("BG[$label] stage=wake sleptMs=${System.currentTimeMillis() - sleptAt}")
                shell("input keyevent KEYCODE_WAKEUP")

                val endDeadline = System.currentTimeMillis() + saveTimeoutMs
                var record = repo.record(id)!!
                while (record.taskStatus in DownloadRepository.activeStatuses && System.currentTimeMillis() < endDeadline) {
                    Thread.sleep(5_000)
                    record = repo.record(id)!!
                }
                println("BG[$label] stage=settled status=${record.taskStatus} bytes=${record.received} failure=${record.failure ?: "-"}")
                assertEquals("background transfer did not succeed on $label: ${record.safeFailure}",
                    com.example.purebrowser.download.TaskStatus.SUCCEEDED, record.taskStatus)
                val asset = repo.stateSnapshot().assets.single()
                assertEquals(FormatCheck.PASSED, asset.format)
                val stored = repo.files!!.access(asset)
                assertEquals(FileAvailability.AVAILABLE, stored.availability)
                val size = stored.sizeBytes ?: asset.sizeBytes ?: 0L
                assertTrue("saved MP4 too small: $size", size > 100 * 1024)
                println("BG[$label] stage=final bytes=$size durationMs=${asset.durationMillis ?: -1} protocol=${record.protocol}")
            } finally {
                cancel.cancel()
                runCatching { worker?.join(30_000) }
                repo.records().forEach { r ->
                    runCatching {
                        repo.stateSnapshot().assets.firstOrNull { it.recordId == r.recordId }?.let { repo.files!!.delete(it) }
                    }
                    runCatching { repo.files!!.cleanupPending(r) }
                    runCatching { repo.files!!.clearPrivate(r.recordId) }
                }
                dir.deleteRecursively()
            }
        } finally {
            instrument.runOnMainSync { session.destroy() }
        }
    }

    private class E2NoSystem : com.example.purebrowser.download.DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("No system enqueue")
        override fun query(id: Long) = com.example.purebrowser.download.SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = com.example.purebrowser.download.FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = com.example.purebrowser.download.MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long) = error("No system remove")
    }
}

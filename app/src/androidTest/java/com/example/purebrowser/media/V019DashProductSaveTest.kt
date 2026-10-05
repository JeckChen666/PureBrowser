package com.example.purebrowser.media

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.browser.BrowserAddress
import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.download.DownloadStore
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.ManagedFileStore
import com.example.purebrowser.download.TaskId
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.UrlConnectionTransport
import com.example.purebrowser.download.WebsiteAccessContext
import com.example.purebrowser.download.dash.DashResolver
import com.example.purebrowser.download.dash.DashTransfer
import com.example.purebrowser.download.dash.MpdPlanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * v0.1.9 T103 (R3) DASH real-product save: the MPD arrives as an instrumentation arg (`url`) —
 * exactly the shape the production sniffer publishes after verification (kind DASH, mime
 * application/dash+xml, [ProbeState.VERIFIED]) — and everything after that is the production path
 * the user's DASH confirmation drives: [DashResolver.resolveEntry]/[DashPlanParser] offer
 * selection (arg `offer` = `default` mirrors the dialog's suggested tier, `lowest` mirrors the user
 * picking the smallest row; default `default`), [DashResolver.resolvePlan], DownloadRepository
 * enqueue with the confirmed plan, then [DashTransfer] over UrlConnectionTransport +
 * WebsiteAccessContext. Success requires TaskStatus.SUCCEEDED and a published MP4 larger than
 * 100 KiB with an `ftyp` box at offset 4 — the same bar as [V016CrossSiteSaveSmoke].
 *
 * Without `url` the test is a no-op so CI never touches external sites. Output is host-masked;
 * no signed segment addresses are printed.
 */
class V019DashProductSaveTest {
    private fun mask(url: String): String {
        val host = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)
        return if (host == null) "<no-host>" else url.replace(host, hostHash(host))
    }
    private fun hostHash(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    @Test fun dashRealProductSave() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val url = InstrumentationRegistry.getArguments().getString("url") ?: return
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "site"
        val offerMode = InstrumentationRegistry.getArguments().getString("offer") ?: "default"
        val saveTimeoutMs = InstrumentationRegistry.getArguments().getString("saveTimeoutMs")?.toLongOrNull() ?: 300_000L

        val app = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(app.cacheDir, "dash-save-${UUID.randomUUID()}").apply { mkdirs() }
        val repo = DownloadRepository(
            DownloadStore(dir), DashSaveNoSystem(), files = ManagedFileStore(app.applicationContext),
        )
        val transport = UrlConnectionTransport()
        val access: AccessContextProvider = WebsiteAccessContext()
        val cancel = TransferCancellation()
        var worker: Thread? = null
        val id: TaskId
        try {
            // The sniffer's published, verified DASH candidate for the MPD the page requested.
            val frozen = MediaCandidate(
                url = url, kind = MediaKind.DASH, sources = setOf(Evidence.REQUEST),
                mimeType = "application/dash+xml", verifiedMime = "application/dash+xml",
                probeState = ProbeState.VERIFIED,
            )
            val draft = DownloadDraft(
                frozen, WebSettings.getDefaultUserAgent(instrument.targetContext),
                url.takeIf(BrowserAddress::isWebUrl), "T103 DASH $label",
            )
            println("DASHSAVE[$label] stage=draft entry=${mask(frozen.url)} useContext=${draft.useAccessContext}")

            val resolver = DashResolver(transport, access, repo.allowLocalHttp)
            val options = resolver.resolveEntry(draft, cancel)
            val supported = options.document.videoOffers.filter { it.supported }
            assumeTrue("no supported DASH video offer on $label", supported.isNotEmpty())
            val offer = when (offerMode) {
                "lowest" -> supported.minByOrNull { (it.height ?: 10_000) * 1_000_000L + (it.bandwidth ?: 0L) }
                else -> MpdPlanParser.defaultVideoOffer(options.document.videoOffers)
            }
            println("DASHSAVE[$label] stage=entry offers=${options.document.videoOffers.size} supported=${supported.size} " +
                "audioOffer=${options.document.audioOffer != null} pick=${offer?.height ?: -1}p mode=$offerMode")
            if (offer == null) { assumeTrue("offer selection produced nothing", false); return }
            val plan = resolver.resolvePlan(draft, options, offer, cancel)
            println("DASHSAVE[$label] stage=plan segments=${plan.totalSegments} durationS=${plan.durationUs / 1_000_000} " +
                "muxed=${plan.muxed} audioSegments=${plan.audio?.segments?.size ?: 0}")

            id = repo.enqueue(draft, wifiOnly = false, fileName = "T103-$label.mp4", dashPlan = plan)
            worker = Thread { DashTransfer(repo, transport, access).run(id, cancel) }
            worker.start()

            val startSave = System.currentTimeMillis()
            var record = repo.record(id)!!
            var lastLog = 0L
            while (record.taskStatus in DownloadRepository.activeStatuses &&
                System.currentTimeMillis() - startSave < saveTimeoutMs
            ) {
                Thread.sleep(500)
                record = repo.record(id)!!
                if (System.currentTimeMillis() - lastLog > 5_000) {
                    lastLog = System.currentTimeMillis()
                    println("DASHSAVE[$label] stage=progress status=${record.taskStatus} bytes=${record.received} " +
                        "segments=${record.completedSegments}/${record.segmentCount ?: 0}")
                }
            }
            if (record.taskStatus in DownloadRepository.activeStatuses) {
                println("DASHSAVE[$label] stage=watchdog status=timeout bytes=${record.received}")
                cancel.cancel()
                worker.join(30_000)
                record = repo.record(id)!!
            }
            worker.join(30_000)
            println("DASHSAVE[$label] stage=settled status=${record.taskStatus} bytes=${record.received} failure=${record.failure ?: "-"} " +
                "safeFailure=${record.safeFailure ?: "-"}")

            assertEquals("DASH save did not succeed on $label: ${record.safeFailure}", TaskStatus.SUCCEEDED, record.taskStatus)
            val asset = repo.stateSnapshot().assets.single()
            assertEquals("published asset failed format check", FormatCheck.PASSED, asset.format)
            val stored = repo.files!!.access(asset)
            assertEquals("published asset not readable: ${stored.availability}", FileAvailability.AVAILABLE, stored.availability)
            val size = stored.sizeBytes ?: asset.sizeBytes ?: 0L
            var prefixRead = 0
            var streamed = 0L
            val prefix = ByteArray(8)
            val buffer = ByteArray(65536)
            app.contentResolver.openInputStream(Uri.parse(asset.uri))!!.use { input ->
                while (prefixRead < prefix.size) {
                    val n = input.read(prefix, prefixRead, prefix.size - prefixRead)
                    if (n < 0) break
                    prefixRead += n
                }
                while (true) { val n = input.read(buffer); if (n < 0) break; streamed += n }
            }
            assertTrue("saved MP4 too small: $size", size > 100 * 1024)
            assertEquals("streamed length differs from published size", size, streamed + prefixRead)
            assertEquals("missing ftyp box at offset 4", "ftyp", String(prefix, 4, 4, Charsets.US_ASCII))
            println("DASHSAVE[$label] stage=final status=SUCCEEDED bytes=$size ftyp=ok " +
                "durationMs=${asset.durationMillis ?: -1} protocol=${record.protocol}")
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
    }

    private class DashSaveNoSystem : com.example.purebrowser.download.DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("No system enqueue")
        override fun query(id: Long) = com.example.purebrowser.download.SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = com.example.purebrowser.download.FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = com.example.purebrowser.download.MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long): Int = error("No system remove")
    }
}

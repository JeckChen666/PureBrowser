package com.example.purebrowser.media

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.browser.BrowserAddress
import com.example.purebrowser.browser.BrowserSession
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.ControlledTransfer
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.download.DownloadStore
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.ManagedFileStore
import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.download.TaskId
import com.example.purebrowser.download.TaskStatus
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
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * v0.1.6 T69 required-tier end-to-end save smoke: 发现→档位→保存→可打开. After the production
 * sniffer (BrowserSession) settles candidates, the test picks the best VERIFIED HLS candidate with
 * variants (fallback: VERIFIED FILE) and drives the PRODUCTION download pipeline exactly as the app
 * does for a real transfer: HlsResolver.resolveEntry/resolvePlan with the page-session context
 * opt-in (the "download-use-context" consent from HlsDownloadConfirmation — cookies still attach
 * only same-origin via RequestPolicy), DownloadRepository.enqueue, then HlsTransfer /
 * ControlledTransfer over UrlConnectionTransport + WebsiteAccessContext. Success requires
 * TaskStatus.SUCCEEDED, a published MP4 larger than 100 KiB with an `ftyp` box at offset 4.
 *
 * Instrumentation args `url`/`label` (plus optional `cookie`, `timeoutMs`, `saveTimeoutMs`) drive
 * the run; without `url` the test is a no-op so CI never touches external sites. Output is
 * host-masked like [V016CrossSiteSmoke]; no site names or raw addresses are printed.
 */
class V016CrossSiteSaveSmoke {
    private fun mask(url: String): String {
        val host = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)
        return if (host == null) "<no-host>" else url.replace(host, hostHash(host))
    }
    private fun hostHash(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    @Test fun crossSiteSave() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val url = InstrumentationRegistry.getArguments().getString("url") ?: return
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "site"
        val timeoutMs = InstrumentationRegistry.getArguments().getString("timeoutMs")?.toLongOrNull() ?: 45_000L
        // Signed manifest tokens (S-A) expire quickly; the whole save is bounded separately.
        val saveTimeoutMs = InstrumentationRegistry.getArguments().getString("saveTimeoutMs")?.toLongOrNull() ?: 300_000L

        InstrumentationRegistry.getArguments().getString("cookie")?.takeIf { it.isNotBlank() }?.let { raw ->
            val hostOf = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: return@let
            raw.split(";").map { it.trim() }.filter { it.contains('=') }.forEach {
                android.webkit.CookieManager.getInstance().setCookie("https://$hostOf", it)
            }
        }

        val session = BrowserSession(
            recordId = "v016-save-$label", appContext = instrument.targetContext,
            record = TabRecord(id = "v016-save", url = url),
            message = {}, changed = {}, visited = { _, _ -> }, link = {},
        )
        val sessionHost = AtomicReference<String>()
        try {
            InstrumentationRegistry.getArguments().getString("ua")?.takeIf { it.isNotBlank() }?.let { ua ->
                session.engine.forcedUserAgent = ua
            }
            instrument.runOnMainSync {
                session.mount(FrameLayout(instrument.targetContext))
                sessionHost.set(session.engine.page.value.url)
            }
            val startLoad = System.currentTimeMillis()
            while (System.currentTimeMillis() - startLoad < 30_000) {
                val page = session.engine.page.value
                if (page.error != null) break
                if (System.currentTimeMillis() - startLoad > 5_000 && page.progress == 100) break
                Thread.sleep(200)
            }
            val page = session.engine.page.value
            println("SAVE[$label] stage=page status=loaded=${page.error == null} err=${page.error ?: "none"} host=${hostHash(Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: "?")}")
            assumeTrue("page did not load: ${page.error ?: "timeout"}", page.error == null)

            val deadline = System.currentTimeMillis() + timeoutMs
            var candidates = session.sniffer.candidates.value
            while (System.currentTimeMillis() < deadline) {
                candidates = session.sniffer.candidates.value
                val addressable = candidates.filter { it.kind in setOf(MediaKind.FILE, MediaKind.HLS, MediaKind.DASH) }
                val settled = addressable.isNotEmpty() &&
                    addressable.all { it.probeState == ProbeState.VERIFIED || it.probeState == ProbeState.FAILED }
                if (settled && page.error == null) break
                Thread.sleep(500)
            }

            val verified = candidates.filter { it.probeState == ProbeState.VERIFIED }
            val hlsMaster = verified.filter { it.kind == MediaKind.HLS && !it.variants.isNullOrEmpty() }
                .maxByOrNull { c -> c.variants!!.maxOf { it.height ?: 0 } }
            val fileBest = verified.filter { it.kind == MediaKind.FILE }.maxByOrNull { it.totalBytes ?: 0L }
            // T89: an HLS master that the production resolver refuses (e.g. fMP4-only variants)
            // is exactly what a user would see as an unsupported tier; the honest save path is
            // then the best direct FILE candidate, mirroring picking the downloadable rule format.
            val hlsPlanUsable = hlsMaster != null && runCatching {
                val resolver = HlsResolver(UrlConnectionTransport(), WebsiteAccessContext(), false)
                val probe = DownloadDraft(
                    hlsMaster.copy(sources = hlsMaster.sources.toSet()),
                    WebSettings.getDefaultUserAgent(instrument.targetContext),
                    page.url.takeIf(BrowserAddress::isWebUrl), page.title.take(180),
                    sourceTabId = session.recordId, sourceGeneration = session.engine.generation,
                )
                val options = resolver.resolveEntry(probe, TransferCancellation())
                val variant = (options.playlist as? HlsPlaylist.Master)
                    ?.let { HlsPlaylistParser.defaultVariant(it.variants) }
                resolver.resolvePlan(probe, options, variant, TransferCancellation())
            }.isSuccess
            val chosen = if (hlsMaster != null && hlsPlanUsable) hlsMaster else fileBest
            println("SAVE[$label] stage=select kind=${chosen?.kind} hlsMaster=${hlsMaster != null} hlsPlanUsable=$hlsPlanUsable fileFallback=${chosen === fileBest && fileBest != null} " +
                "variants=${chosen?.variants?.size ?: 0} maxH=${chosen?.variants?.maxOf { it.height ?: 0 } ?: -1} bytes=${chosen?.totalBytes ?: -1}")
            assumeTrue("no VERIFIED HLS-with-variants or FILE candidate settled on $label", chosen != null)
            assumeTrue("chosen candidate is not downloadable (kind=${chosen!!.kind})", chosen.kind == MediaKind.HLS || chosen.kind == MediaKind.FILE)

            // Production draft construction: BrowserViewModel.downloadDraft + the user leaving the
            // confirmation's use-context toggle at its offered default (useAccessContext=true).
            val userAgent = WebSettings.getDefaultUserAgent(instrument.targetContext)
            val frozen = chosen.copy(sources = chosen.sources.toSet())
            val draft = DownloadDraft(
                frozen, userAgent, page.url.takeIf(BrowserAddress::isWebUrl), page.title.take(180),
                sourceTabId = session.recordId, sourceGeneration = session.engine.generation,
            )
            val contextEligible = RequestPolicy.canUseContext(draft.sourceUrl, draft.frameUrl, draft.reliableSource)
            val probedEligible = RequestPolicy.canUseProbedContext(frozen.url, frozen.pageUrl, frozen.frameUrl)
            println("SAVE[$label] stage=draft kind=${frozen.kind} useContext=${draft.useAccessContext} " +
                "contextEligible=$contextEligible probedEligible=$probedEligible reliable=${draft.reliableSource} frameKnown=${draft.frameUrl != null} entry=${mask(frozen.url)}")

            val app = ApplicationProvider.getApplicationContext<Context>()
            val dir = File(app.cacheDir, "save-smoke-${UUID.randomUUID()}").apply { mkdirs() }
            val repo = DownloadRepository(
                DownloadStore(dir), SaveSmokeNoSystem(), files = ManagedFileStore(app.applicationContext),
            )
            val transport = UrlConnectionTransport()
            val access: AccessContextProvider = WebsiteAccessContext()
            val cancel = TransferCancellation()
            var worker: Thread? = null
            val id: TaskId
            try {
                // Same permission path as the app's download confirmation (HlsDownloadConfirmation /
                // DownloadConfirmation), never a test shortcut around RequestPolicy.
                if (chosen.kind == MediaKind.HLS) {
                    val resolver = HlsResolver(transport, access, repo.allowLocalHttp)
                    val options = resolver.resolveEntry(draft, cancel)
                    val variant = (options.playlist as? HlsPlaylist.Master)
                        ?.let { HlsPlaylistParser.defaultVariant(it.variants) }
                    val plan = resolver.resolvePlan(draft, options, variant, cancel)
                    println("SAVE[$label] stage=plan playlist=master=${options.playlist is HlsPlaylist.Master} " +
                        "variantH=${variant?.height ?: -1} segments=${plan.media.segments.size} durationS=${plan.media.durationUs / 1_000_000}")
                    id = repo.enqueue(draft, wifiOnly = false, fileName = "T69-$label.mp4", hlsPlan = plan)
                    worker = Thread { HlsTransfer(repo, transport, access).run(id, cancel) }
                } else {
                    id = repo.enqueue(draft, wifiOnly = false, fileName = "T69-$label.mp4")
                    worker = Thread { ControlledTransfer(repo, transport, access).run(id, cancel) }
                }
                worker.start()

                val startSave = System.currentTimeMillis()
                var record = repo.record(id)!!
                var lastLog = 0L
                while (record.taskStatus in DownloadRepository.activeStatuses &&
                    System.currentTimeMillis() - startSave < saveTimeoutMs) {
                    Thread.sleep(500)
                    record = repo.record(id)!!
                    if (System.currentTimeMillis() - lastLog > 5_000) {
                        lastLog = System.currentTimeMillis()
                        println("SAVE[$label] stage=progress status=${record.taskStatus} bytes=${record.received} " +
                            "segments=${record.completedSegments}/${record.segmentCount ?: 0}")
                    }
                }
                if (record.taskStatus in DownloadRepository.activeStatuses) {
                    println("SAVE[$label] stage=watchdog status=timeout bytes=${record.received}")
                    cancel.cancel()
                    worker.join(30_000)
                    record = repo.record(id)!!
                }
                worker.join(30_000)
                println("SAVE[$label] stage=settled status=${record.taskStatus} bytes=${record.received} failure=${record.failure ?: "-"} " +
                    "safeFailure=${record.safeFailure ?: "-"}")

                assertEquals("save did not succeed on $label: ${record.safeFailure}", TaskStatus.SUCCEEDED, record.taskStatus)
                val asset = repo.stateSnapshot().assets.single()
                assertEquals("published asset failed format check", FormatCheck.PASSED, asset.format)
                val stored = repo.files!!.access(asset)
                assertEquals("published asset not readable: ${stored.availability}", FileAvailability.AVAILABLE, stored.availability)
                // Stream, never buffer: a real movie exceeds any test heap. Size comes from the
                // published descriptor; readability is proven by streaming the file through.
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
                println("SAVE[$label] stage=final status=SUCCEEDED bytes=$size ftyp=ok " +
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
        } finally {
            instrument.runOnMainSync { session.destroy() }
        }
    }

    private class SaveSmokeNoSystem : com.example.purebrowser.download.DownloadBackend {
        override fun enqueue(url: String, name: String, userAgent: String, wifiOnly: Boolean): Long = error("No system enqueue")
        override fun query(id: Long) = com.example.purebrowser.download.SystemDownloadResult.Missing
        override fun fileUri(id: Long): String? = null
        override fun access(uri: String) = com.example.purebrowser.download.FileAccess(FileAvailability.MISSING)
        override fun inspect(uri: String) = com.example.purebrowser.download.MediaInspection(FormatCheck.UNCONFIRMED)
        override fun remove(id: Long) = error("No system remove")
    }
}

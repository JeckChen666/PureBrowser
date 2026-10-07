package com.example.purebrowser.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.ProbeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T119/R3 link-step measurement through the real browser UI (mirrors [HlsBrowserJourneyTest]):
 * load the caller's page, open the resource panel, tap the best candidate, tap the single 保存
 * action, and prove the download task was created. Taps are counted explicitly; the assertion
 * fails if the default path needed more than 2 taps, which is the acceptance datum itself.
 *
 * Arg-gated like [com.example.purebrowser.media.V016CrossSiteSmoke]: `url` (+ `label`, optional
 * `cookie`, `timeoutMs`) drive the default path; without `url` both tests are no-ops so CI never
 * touches external sites. `quickSave=true` switches to the T117 long-press path (1 interaction).
 * Output is host-masked; no site names, URLs or display names are printed.
 */
class V02xTwoStepPathTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun hostHash(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    @Test(timeout = 420_000)
    fun defaultPathCreatesTaskWithinTwoTaps() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url") ?: return
        assumeTrue("quickSave=true selects the dedicated long-press test", args.getString("quickSave") != "true")
        val label = args.getString("label") ?: "site"
        val timeoutMs = args.getString("timeoutMs")?.toLongOrNull() ?: 120_000L
        applyCookie(url, args.getString("cookie"))
        requirePermissions()

        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10_000) { model.ready.value }
        val worker = Executors.newSingleThreadExecutor()
        fun <T> io(block: () -> T): T = worker.submit(Callable { block() }).get(60, TimeUnit.SECONDS)
        val repository = model.repository
        val previousTabId = model.data.value.selectedId
        var ownedTabId: String? = null
        var createdId: String? = null
        var taps = 0
        var primaryFailure: Throwable? = null
        try {
            compose.activityRule.scenario.onActivity {
                model.newTab()
                ownedTabId = model.data.value.selectedId
            }
            compose.waitUntil(10_000) { model.engine?.page?.value?.url == "about:blank" }
            compose.onNodeWithTag("addressInput").performClick().performTextReplacement(url)
            compose.onNodeWithTag("navigateButton").performClick()

            val chosen = awaitBestCandidate(model, timeoutMs, label)
            val host = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: "?"
            println("PATH[$label] stage=candidates host=${hostHash(host)} kind=${chosen.kind} " +
                "probe=${chosen.probeState} variants=${chosen.variants?.size ?: 0} " +
                "maxH=${chosen.variants?.maxOfOrNull { it.height ?: 0 } ?: -1} bytes=${chosen.totalBytes ?: -1}")
            val previousIds = io { repository.records().map { it.recordId }.toSet() }

            compose.onNodeWithTag("menuButton").performClick()
            compose.onNodeWithTag("resourcesButton").performClick()
            if (args.getString("analyze") == "true") {
                // Rules-site adapter path: 分析当前视频 → 解析可用格式 → 继续确认保存 → the save
                // action. Counted honestly (no ≤2 claim); this is the route the site supports.
                val previousIds = io { repository.records().map { it.recordId }.toSet() }
                compose.onNodeWithTag("analyze-current-video").performClick(); taps++
                println("PATH[$label] stage=tap$taps action=analyze-current-video")
                compose.onNodeWithTag("site-analysis-start").performClick(); taps++
                println("PATH[$label] stage=tap$taps action=site-analysis-start")
                compose.waitUntil(timeoutMs) {
                    runCatching { compose.onNodeWithTag("site-analysis-ready").assertIsEnabled(); true }.getOrDefault(false)
                }
                compose.onNodeWithTag("site-analysis-ready").performClick(); taps++
                println("PATH[$label] stage=tap$taps action=site-analysis-ready")
                compose.waitUntil(20_000) { firstSaveAction() != null }
                val adapterSave = requireNotNull(firstSaveAction())
                adapterSave.second.performScrollTo().performClick(); taps++
                println("PATH[$label] stage=tap$taps action=${adapterSave.first}")
                runCatching {
                    compose.waitUntil(240_000) {
                        val id = io { repository.records().singleOrNull { it.recordId !in previousIds }?.recordId }
                        createdId = id
                        id != null
                    }
                }
                assertTrue("adapter path created no task on $label after $taps taps", createdId != null)
                val record = io { requireNotNull(repository.record(requireNotNull(createdId))) }
                println("PATH[$label] stage=created adapter=true taps=$taps status=${record.taskStatus} " +
                    "protocol=${record.protocol} failure=${record.safeFailure ?: "-"}")
                assertEquals(4, taps)
                return // through finally: cleanup cancels/deletes the created task
            }
            // Tap 1: the best candidate's card save action (the panel's production sort defines best).
            compose.onNodeWithTag("resource-sheet").performScrollToNode(hasTestTag("resource-card-${chosen.displayName}"))
            compose.onNode(hasContentDescription("保存") and hasClickAction() and
                hasAnyAncestor(hasTestTag("resource-card-${chosen.displayName}"))).performScrollTo().performClick()
            taps++
            println("PATH[$label] stage=tap1 action=candidate-save kind=${chosen.kind}")

            // Tap 2: the single save action of whichever confirmation the product opened.
            compose.waitUntil(20_000) { firstSaveAction() != null }
            var saveAction = requireNotNull(firstSaveAction())
            compose.waitUntil(30_000) { runCatching { saveAction.second.performScrollTo().assertIsEnabled(); true }.getOrDefault(false) }
            saveAction.second.performClick()
            taps++
            println("PATH[$label] stage=tap2 action=${saveAction.first}")

            // A ladder first read at save time stops for one explicit row confirm; if the product
            // needs that third tap, take it and FAIL the ≤2 measurement honestly (never a timeout).
            runCatching {
                compose.waitUntil(120_000) {
                    val id = io { repository.records().singleOrNull { it.recordId !in previousIds }?.recordId }
                    createdId = id
                    id != null
                }
            }
            if (createdId == null && firstSaveAction() != null) {
                printSaveSurfaceErrors(label)
                saveAction = requireNotNull(firstSaveAction())
                compose.waitUntil(30_000) { runCatching { saveAction.second.performScrollTo().assertIsEnabled(); true }.getOrDefault(false) }
                saveAction.second.performClick()
                taps++
                println("PATH[$label] stage=tap3 action=${saveAction.first} reason=rows-surfaced-at-save")
                compose.waitUntil(120_000) {
                    val id = io { repository.records().singleOrNull { it.recordId !in previousIds }?.recordId }
                    createdId = id
                    id != null
                }
            }
            assertTrue("no download task was created on $label after $taps taps", createdId != null)
            val record = io { requireNotNull(repository.record(requireNotNull(createdId))) }
            println("PATH[$label] stage=created taps=$taps status=${record.taskStatus} " +
                "protocol=${record.protocol} segments=${record.segmentCount ?: 0} mediaMatch=${record.mediaUrl == chosen.url} " +
                "failure=${record.safeFailure ?: "-"}")
            assertEquals("default path needed more than 2 taps on $label", 2, taps)
            assertTrue("created task is neither running nor finished on $label: ${record.taskStatus}",
                record.taskStatus == TaskStatus.RUNNING ||
                    record.taskStatus in DownloadRepository.activeStatuses ||
                    record.taskStatus == TaskStatus.SUCCEEDED)
        } catch (failure: Throwable) {
            primaryFailure = failure
            printSaveSurfaceErrors(label)
            println("PATH[$label] stage=failure taps=$taps message=${failure.message?.take(160)}")
            throw failure
        } finally {
            try {
                createdId?.let { id ->
                    io { if (repository.record(id)?.taskStatus in DownloadRepository.activeStatuses) repository.cancel(id) }
                    compose.waitUntil(20_000) { io { !repository.transferInFlight(id) } }
                    io {
                        if (repository.record(id) != null) {
                            if (repository.stateSnapshot().assets.any { it.recordId == id }) repository.deleteFile(id)
                            else repository.forgetRecord(id)
                        }
                    }
                }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            } finally {
                try {
                    compose.activityRule.scenario.onActivity {
                        ownedTabId?.takeIf { id -> model.data.value.tabs.any { it.id == id } }?.let { model.tabs.close(it) }
                        if (model.data.value.tabs.any { it.id == previousTabId }) model.tabs.select(previousTabId)
                    }
                } finally { worker.shutdownNow() }
            }
        }
    }

    /** T117 quick path: one long-press on the best card creates the task with remembered defaults. */
    @Test(timeout = 420_000)
    fun quickSaveLongPressCreatesTaskInOneInteraction() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("url") ?: return
        assumeTrue("pass quickSave=true to select the long-press path", args.getString("quickSave") == "true")
        val label = args.getString("label") ?: "site"
        val timeoutMs = args.getString("timeoutMs")?.toLongOrNull() ?: 120_000L
        applyCookie(url, args.getString("cookie"))
        requirePermissions()

        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10_000) { model.ready.value }
        val worker = Executors.newSingleThreadExecutor()
        fun <T> io(block: () -> T): T = worker.submit(Callable { block() }).get(60, TimeUnit.SECONDS)
        val repository = model.repository
        val previousTabId = model.data.value.selectedId
        var ownedTabId: String? = null
        var createdId: String? = null
        var interactions = 0
        var primaryFailure: Throwable? = null
        try {
            compose.activityRule.scenario.onActivity {
                model.newTab()
                ownedTabId = model.data.value.selectedId
            }
            compose.waitUntil(10_000) { model.engine?.page?.value?.url == "about:blank" }
            compose.onNodeWithTag("addressInput").performClick().performTextReplacement(url)
            compose.onNodeWithTag("navigateButton").performClick()

            val chosen = awaitBestCandidate(model, timeoutMs, label)
            println("QUICK[$label] stage=candidates kind=${chosen.kind} probe=${chosen.probeState} " +
                "variants=${chosen.variants?.size ?: 0} bytes=${chosen.totalBytes ?: -1}")
            val previousIds = io { repository.records().map { it.recordId }.toSet() }

            compose.onNodeWithTag("menuButton").performClick()
            compose.onNodeWithTag("resourcesButton").performClick()
            // Interaction 1 (the only one): long-press the card row = 用默认设置快速保存.
            compose.onNodeWithTag("resource-sheet").performScrollToNode(hasTestTag("resource-card-${chosen.displayName}"))
            compose.onNodeWithTag("resource-card-${chosen.displayName}").performTouchInput { longClick() }
            interactions++
            println("QUICK[$label] stage=gesture1 action=long-press-quick-save kind=${chosen.kind}")

            // Manifest protocols resolve their plan off the UI thread before the task is created.
            compose.waitUntil(240_000) {
                val id = io {
                    repository.records().singleOrNull { it.recordId !in previousIds && it.mediaUrl == chosen.url }?.recordId
                }
                createdId = id
                id != null
            }
            val record = io { requireNotNull(repository.record(requireNotNull(createdId))) }
            println("QUICK[$label] stage=created interactions=$interactions status=${record.taskStatus} " +
                "protocol=${record.protocol} failure=${record.safeFailure ?: "-"}")
            assertEquals("quick path needed more than 1 interaction on $label", 1, interactions)
            assertTrue("quick-save created no runnable task on $label: ${record.taskStatus}",
                record.taskStatus == TaskStatus.RUNNING ||
                    record.taskStatus in DownloadRepository.activeStatuses ||
                    record.taskStatus == TaskStatus.SUCCEEDED)
        } catch (failure: Throwable) {
            primaryFailure = failure
            println("QUICK[$label] stage=failure interactions=$interactions message=${failure.message?.take(160)}")
            throw failure
        } finally {
            try {
                createdId?.let { id ->
                    io { if (repository.record(id)?.taskStatus in DownloadRepository.activeStatuses) repository.cancel(id) }
                    compose.waitUntil(20_000) { io { !repository.transferInFlight(id) } }
                    io {
                        if (repository.record(id) != null) {
                            if (repository.stateSnapshot().assets.any { it.recordId == id }) repository.deleteFile(id)
                            else repository.forgetRecord(id)
                        }
                    }
                }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            } finally {
                try {
                    compose.activityRule.scenario.onActivity {
                        ownedTabId?.takeIf { id -> model.data.value.tabs.any { it.id == id } }?.let { model.tabs.close(it) }
                        if (model.data.value.tabs.any { it.id == previousTabId }) model.tabs.select(previousTabId)
                    }
                } finally { worker.shutdownNow() }
            }
        }
    }

    /** The production "best" notion from the T69 smoke: best VERIFIED HLS with a ladder, else largest VERIFIED FILE (DASH treated like HLS). */
    private fun awaitBestCandidate(model: BrowserViewModel, timeoutMs: Long, label: String): MediaCandidate {
        val deadline = System.currentTimeMillis() + timeoutMs
        var candidates = emptyList<MediaCandidate>()
        while (System.currentTimeMillis() < deadline) {
            candidates = model.sniffer?.candidates?.value.orEmpty()
            val addressable = candidates.filter { it.kind in setOf(MediaKind.FILE, MediaKind.HLS, MediaKind.DASH) }
            val settled = addressable.isNotEmpty() &&
                addressable.all { it.probeState == ProbeState.VERIFIED || it.probeState == ProbeState.FAILED }
            if (settled) break
            Thread.sleep(500)
        }
        val verified = candidates.filter { it.probeState == ProbeState.VERIFIED }
        val ladder = verified.filter { (it.kind == MediaKind.HLS || it.kind == MediaKind.DASH) && !it.variants.isNullOrEmpty() }
            .maxByOrNull { it.variants!!.maxOfOrNull { v -> v.height ?: 0 } ?: 0 }
        val chosen = ladder ?: verified.filter { it.kind == MediaKind.FILE }.maxByOrNull { it.totalBytes ?: 0L }
        assumeTrue("no VERIFIED ladder or FILE candidate settled on $label " +
            "(total=${candidates.size} kinds=${candidates.map { it.kind.name }.toSet()})", chosen != null)
        return chosen!!
    }

    /** Diagnosis for the ledger: the safe error text the save surface shows (never raw URLs). */
    private fun printSaveSurfaceErrors(label: String) {
        listOf("hls-error", "dash-error", "rule-format-error").forEach { tag ->
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().forEach { node ->
                val text = node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    ?.joinToString(" ")?.take(160) ?: "-"
                println("PATH[$label] stage=surface-error source=$tag text=$text")
            }
        }
    }

    /** The confirmation's single submit control, whichever dialog the product opened. */
    private fun firstSaveAction(): Pair<String, androidx.compose.ui.test.SemanticsNodeInteraction>? {
        listOf("hls-save", "dash-save", "rule-format-save").forEach { tag ->
            val node = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (node.isNotEmpty()) return tag to compose.onNodeWithTag(tag)
        }
        val direct = compose.onAllNodes(hasText("开始下载") and hasClickAction()).fetchSemanticsNodes()
        if (direct.isNotEmpty()) return "direct-start" to compose.onAllNodes(hasText("开始下载") and hasClickAction())[0]
        return null
    }

    private fun applyCookie(url: String, raw: String?) {
        raw?.takeIf { it.isNotBlank() }?.let { cookie ->
            val hostOf = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: return@let
            cookie.split(";").map { it.trim() }.filter { it.contains('=') }.forEach {
                android.webkit.CookieManager.getInstance().setCookie("https://$hostOf", it)
            }
        }
    }

    private fun requirePermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT <= 28) {
            assertTrue("Pre-grant legacy READ/WRITE_EXTERNAL_STORAGE for this opt-in path",
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
        }
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("Pre-grant POST_NOTIFICATIONS so a system prompt cannot mask the path",
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        }
    }
}

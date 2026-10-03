package com.example.purebrowser.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.AssetLocation
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.DownloadRepository
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.download.TransferType
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional T28: real WebView/sniffer -> HLS confirmation -> app queue -> public MP4 -> library.
 * Opt in with hlsFixture=true; serve the synthetic fixture on localhost:8766 (adb reverse if needed).
 * hlsFixtureBaseUrl may select 10.0.2.2 instead. Requires the debug build and pre-granted download/
 * notification permissions. Does not alter permissions, global settings, existing tasks or files.
 * All repository/file/extractor work runs on a dedicated worker, never the Activity/UI thread.
 */
class HlsBrowserJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 180_000)
    fun signedDynamicMasterThroughExplicitPreviewAndRealQueueAppearsAsPublicMp4InLibrary() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Start the HLS fixture server and pass hlsFixture=true", args.getString("hlsFixture") == "true")
        val base = (args.getString("hlsFixtureBaseUrl") ?: "http://127.0.0.1:8766").trimEnd('/')
        val origin = URI(base)
        require(origin.scheme == "http" && origin.host in setOf("127.0.0.1", "10.0.2.2") &&
            origin.port in 1..65535 && origin.rawUserInfo == null && origin.rawQuery == null &&
            origin.rawFragment == null && origin.path.isNullOrEmpty()) { "Use only the local synthetic HLS fixture" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT <= 28) {
            assertTrue("Pre-grant legacy READ/WRITE_EXTERNAL_STORAGE for this opt-in journey",
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
        }
        if (Build.VERSION.SDK_INT >= 33) {
            assertTrue("Pre-grant POST_NOTIFICATIONS so a system permission prompt cannot mask this journey",
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        }
        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10_000) { model.ready.value }
        assertTrue("Local HTTP fixtures require the debug request policy", model.repository.allowLocalHttp)
        val worker = Executors.newSingleThreadExecutor()
        fun <T> io(block: () -> T): T = worker.submit(Callable { block() }).get(20, TimeUnit.SECONDS)
        val repository = model.repository
        val pageUrl = "$base/dynamic.html"
        val entryUrl = "$base/signed/master.m3u8?sig=fixture%2Bv012"
        val chosenPlaylistUrl = "$base/signed/video.m3u8?variant=two&sig=fixture%2Bv012"
        val fileName = "hls-t28-${UUID.randomUUID()}.mp4"
        val previousTabId = model.data.value.selectedId
        var ownedTabId: String? = null
        var previousIds = emptySet<String>()
        var ownedTaskId: String? = null
        var submitted = false
        var primaryFailure: Throwable? = null
        try {
            compose.activityRule.scenario.onActivity {
                model.newTab()
                ownedTabId = model.data.value.selectedId
            }
            compose.waitUntil(10_000) { model.engine?.page?.value?.url == "about:blank" }
            compose.onNodeWithTag("addressInput").performClick().performTextReplacement(pageUrl)
            compose.onNodeWithTag("navigateButton").performClick()
            compose.waitUntil(25_000) {
                model.sniffer?.candidates?.value?.any {
                    it.url == entryUrl && it.kind == MediaKind.HLS && Evidence.DOM in it.sources
                } == true
            }
            val observed = model.sniffer!!.candidates.value.first { it.url == entryUrl && Evidence.DOM in it.sources }
            assertTrue(observed.reliableSource)
            assertEquals(pageUrl, observed.frameUrl)
            assertEquals(ownedTabId, model.data.value.selectedId)
            val sourceGeneration = model.engine!!.generation
            previousIds = io { repository.records().map { it.recordId }.toSet() }

            compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("resourcesButton").performClick()
            compose.onNodeWithTag("resource-sheet").performScrollToNode(hasTestTag("resource-card-${observed.displayName}"))
            compose.onNode(hasContentDescription("尝试下载") and hasClickAction() and
                hasAnyAncestor(hasTestTag("resource-card-${observed.displayName}"))).performScrollTo().performClick()
            compose.onNodeWithText("确认下载 HLS").assertExists()
            compose.onNodeWithTag("hls-variant-0").assertDoesNotExist()
            compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
            // Override ONLY this confirmation. Never change the device's default download policy.
            val wifiControl = compose.onNode(isToggleable() and hasText("仅 Wi-Fi"))
            wifiControl.performScrollTo()
            if (wifiControl.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On) wifiControl.performClick()
            wifiControl.assertIsOff()
            compose.onNodeWithTag("download-file-name").performScrollTo().performTextReplacement(fileName)
            compose.onNodeWithTag("download-file-name").performImeAction()

            compose.onNodeWithTag("hls-parse-playlist").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("hls-variant-0").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("hls-variant-0").performScrollTo().assertIsSelected()
            // The fixture has two declared aliases of one synthetic rendition, not a real ABR ladder.
            compose.onNodeWithTag("hls-variant-1").performScrollTo().assertIsEnabled().performClick().assertIsSelected()
            compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("hls-prepare-variant").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("hls-plan-ready").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("清单已准备 · 4 个分片").assertExists()
            compose.onNodeWithText("清单时长：0 分 8 秒（清单声明）").assertExists()
            submitted = true
            compose.onNodeWithTag("hls-save").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(15_000) {
                val created = io {
                    repository.records().filter { it.recordId !in previousIds && it.displayName == fileName &&
                        it.mediaUrl == entryUrl && it.sourceTabId == ownedTabId }
                }
                check(created.size <= 1) { "One Save action created multiple matching HLS tasks" }
                ownedTaskId = created.singleOrNull()?.recordId
                ownedTaskId != null
            }
            val id = requireNotNull(ownedTaskId)
            val queued = io { requireNotNull(repository.record(id)) }
            assertEquals(DownloadProtocol.HLS, queued.protocol)
            assertEquals(TransferType.CONTROLLED, queued.transfer)
            assertEquals(entryUrl, queued.mediaUrl)
            assertEquals(chosenPlaylistUrl, queued.hlsPlaylistUrl)
            assertEquals(pageUrl, queued.sourceUrl)
            assertEquals(ownedTabId, queued.sourceTabId)
            assertEquals(sourceGeneration, queued.sourceGeneration)
            assertEquals(false, queued.wifiOnly)
            assertEquals(4, queued.segmentCount)
            assertEquals(8_000_000L, queued.plannedDurationUs)
            try {
                compose.waitUntil(75_000) { model.downloads.value.any { it.id == id && it.verified && it.taskStatus == TaskStatus.SUCCEEDED } }
            } catch (failure: Exception) {
                val state = io { repository.record(id) }
                throw AssertionError("T28 HLS task did not verify: ${state?.taskStatus}, ${state?.failure}, ${state?.safeFailure}", failure)
            }
            compose.waitUntil(10_000) { model.videoLibrary.value.any { it.recordId == id } }
            val saved = io { repository.stateSnapshot().assets.single { it.recordId == id } }
            assertEquals(fileName, saved.displayName)
            assertEquals(FormatCheck.PASSED, saved.format)
            assertEquals(FileAvailability.AVAILABLE, saved.availability)
            assertEquals("video/mp4", saved.mimeType)
            assertTrue((saved.sizeBytes ?: 0) > 0)
            assertTrue((saved.durationMillis ?: 0) in 7_000L..9_000L)
            val uri = io { repository.fileUri(id) }
            assertNotNull(uri)
            io {
                val resultUri = requireNotNull(uri)
                val prefix = ByteArray(12)
                DataInputStream(requireNotNull(context.contentResolver.openInputStream(resultUri))).use { it.readFully(prefix) }
                assertEquals("ftyp", String(prefix, 4, 4, Charsets.US_ASCII))
                if (Build.VERSION.SDK_INT >= 29) {
                    assertEquals(AssetLocation.MEDIASTORE_DOWNLOAD, saved.location)
                    context.contentResolver.query(resultUri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
                        assertTrue(it.moveToFirst())
                        assertEquals("Download/PureBrowser/", it.getString(0))
                        assertEquals(saved.name, it.getString(1))
                        assertEquals(0, it.getInt(2))
                    } ?: error("Published MediaStore row was not readable")
                } else {
                    assertEquals(AssetLocation.LEGACY_PUBLIC_FILE, saved.location)
                    val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PureBrowser")
                    assertTrue(File(directory, saved.name).isFile)
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, resultUri, null)
                    val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                    assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
                    assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
                    extractor.selectTrack(formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
                    extractor.seekTo(4_000_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    assertTrue(extractor.sampleTime in 2_000_000L..4_000_000L)
                } finally { extractor.release() }
                val complete = requireNotNull(repository.record(id))
                assertEquals(TaskStatus.SUCCEEDED, complete.taskStatus)
                assertEquals(4, complete.completedSegments)
            }
            compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("downloadsButton").performClick()
            compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-$id"))
            compose.onNodeWithTag("download-status-$id").assert(hasText("已保存 · 格式初检通过"))
            compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("downloadsLibrary"))
            compose.onNodeWithTag("downloadsLibrary").performClick()
            compose.onNodeWithTag("videoLibraryScreen").assertExists()
            compose.onNodeWithTag("videoLibraryList").performScrollToNode(hasTestTag("video-$id"))
            compose.onNode(hasText(fileName) and hasAnyAncestor(hasTestTag("video-$id")),useUnmergedTree=true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("video-$id").performClick()
            compose.onNodeWithTag("video-open-$id").assertIsEnabled()
            compose.onNodeWithTag("video-share-$id").assertIsEnabled()
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                if (submitted) {
                    compose.waitUntil(10_000) { !model.submitting.value }
                    if (ownedTaskId == null) {
                        ownedTaskId = io {
                            repository.records().singleOrNull { it.recordId !in previousIds && it.displayName == fileName &&
                                it.mediaUrl == entryUrl && it.sourceTabId == ownedTabId }?.recordId
                        }
                    }
                    ownedTaskId?.let { id ->
                        // No repository-wide clear, no task diff deletion, and no directory-wide cleanup.
                        io { if (repository.record(id)?.taskStatus in DownloadRepository.activeStatuses) repository.cancel(id) }
                        compose.waitUntil(20_000) { io { !repository.transferInFlight(id) } }
                        io {
                            if (repository.record(id) != null) {
                                if (repository.stateSnapshot().assets.any { it.recordId == id }) repository.deleteFile(id)
                                else repository.forgetRecord(id)
                            }
                            assertFalse(repository.records().any { it.recordId == id })
                            assertFalse(repository.stateSnapshot().assets.any { it.recordId == id })
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
}

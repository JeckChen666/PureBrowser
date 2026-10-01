package com.example.purebrowser.ui.downloads

import android.app.DownloadManager
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.SystemTaskRead
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.theme.PureBrowserTheme
import com.example.purebrowser.ui.library.VideoLibraryScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Standalone callback-only screens: no app activity, WebView, repository, network or device files. */
class DownloadLibraryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownTotalNeverInventsPercentage() {
        val item = task().copy(bytes = 4096, total = -1)
        assertNull(item.progressFraction())
        showDownloads(listOf(item))
        compose.onNodeWithText("下载管理").assertIsDisplayed()
        compose.onNodeWithTag("download-group-active").assertExists()
        scrollDownloads("download-bytes-1")
        compose.onNodeWithTag("download-bytes-1").assert(hasText("总大小未知", substring = true))
        compose.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("sample-1.mp4", substring = true).assertExists()
    }

    @Test fun groupingAndActionsDoNotTrustStaleOrUnknownSystemStatus() {
        val running = task()
        assertEquals(DownloadUiGroup.ACTIVE, running.uiGroup())
        assertEquals(DownloadUiGroup.ACTIVE, running.copy(status = DownloadManager.STATUS_PENDING).uiGroup())
        assertEquals(DownloadUiGroup.ACTIVE, running.copy(status = DownloadManager.STATUS_PAUSED).uiGroup())
        listOf(
            running.copy(systemRead = SystemTaskRead.UNAVAILABLE),
            running.copy(systemRead = SystemTaskRead.MISSING),
            running.copy(cancelled = true),
            running.copy(status = 0),
        ).forEach {
            assertEquals(DownloadUiGroup.ATTENTION, it.uiGroup())
            assertFalse(it.isActiveTask())
        }
        val completed = completedTask()
        assertEquals(DownloadUiGroup.COMPLETED, completed.uiGroup())
        listOf(
            completed.copy(verified = false),
            completed.copy(format = FormatCheck.INVALID),
            completed.copy(availability = FileAvailability.MISSING),
            completed.copy(availability = FileAvailability.UNREADABLE),
            completed.copy(systemRead = SystemTaskRead.MISSING),
        ).forEach { assertEquals(DownloadUiGroup.ATTENTION, it.uiGroup()) }
        val unknown = completed.copy(systemRead = SystemTaskRead.UNAVAILABLE, canRetry = true)
        assertFalse(unknown.canForgetRecord())
        assertFalse(unknown.canDeleteSavedFile())
        assertFalse(unknown.retryAvailable())
        assertFalse(completed.copy(status = 0).canDeleteSavedFile())
        assertFalse(completed.copy(status = 0).canForgetRecord())
        assertFalse(completed.copy(availability = FileAvailability.UNKNOWN).canDeleteSavedFile())
        assertTrue(completed.copy(systemRead = SystemTaskRead.MISSING).canForgetRecord())
    }

    @Test fun knownProgressRequiresConsistentByteCounters() {
        val known = task().copy(bytes = 50, total = 100)
        assertEquals(0.5f, known.progressFraction()!!, 0.001f)
        assertTrue(known.byteSummary().contains("50%"))
        listOf(known.copy(total = 0), known.copy(total = -1), known.copy(bytes = -1), known.copy(bytes = 101)).forEach {
            assertNull(it.progressFraction())
            assertFalse(it.byteSummary().contains("%"))
        }
    }

    @Test fun unavailableSystemTaskHasNoFakeManagementActions() {
        showDownloads(listOf(completedTask().copy(systemRead = SystemTaskRead.UNAVAILABLE, canRetry = true)))
        scrollDownloads("download-forget-1")
        compose.onNodeWithTag("download-forget-1").assertIsNotEnabled()
        listOf("cancel", "delete", "retry", "open", "share", "source").forEach {
            compose.onNodeWithTag("download-$it-1").assertDoesNotExist()
        }
    }

    @Test fun cancellationRequiresConfirmationAndOnlyCallsCancel() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(task()), calls = calls)
        scrollDownloads("download-cancel-1")
        compose.onNodeWithTag("download-cancel-1").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithText("已传输的数据不会保留用于续传", substring = true).assertExists()
        compose.onNodeWithTag("download-cancel-dialog-1-dismiss").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("download-cancel-1").performClick()
        compose.onNodeWithTag("download-cancel-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("cancel:1"), calls) }
    }

    @Test fun forgettingConfirmsThatTheDeviceFileIsKept() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(completedTask()), calls = calls)
        scrollDownloads("download-forget-1")
        compose.onNodeWithTag("download-forget-1").performClick()
        compose.onNodeWithText("不会删除设备文件", substring = true).assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("download-forget-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("forget:1"), calls) }
    }

    @Test fun deletingConfirmsActualDeviceFileDeletion() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(completedTask()), calls = calls)
        scrollDownloads("download-delete-1")
        compose.onNodeWithTag("download-delete-1").performClick()
        compose.onNodeWithText("实际删除设备", substring = true).assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("download-delete-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("delete:1"), calls) }
    }

    @Test fun retryCreatesANewTaskOnlyAfterExplicitConfirmation() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(task().copy(status = DownloadManager.STATUS_FAILED, canRetry = true)), calls = calls)
        scrollDownloads("download-retry-1")
        compose.onNodeWithTag("download-retry-1").performClick()
        compose.onNodeWithText("不是暂停后续传", substring = true).assertExists()
        compose.onNodeWithText("旧任务记录和已有文件会保留", substring = true).assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("download-retry-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("retry:1"), calls) }
    }

    @Test fun retryIsDisabledWhileBusyAndAbsentWithoutCapability() {
        showDownloads(listOf(
            task().copy(status = DownloadManager.STATUS_FAILED, canRetry = true),
            task(2).copy(status = DownloadManager.STATUS_FAILED, canRetry = false),
        ), busy = setOf(1))
        scrollDownloads("download-retry-1")
        compose.onNodeWithTag("download-retry-1").assertIsNotEnabled()
        scrollDownloads("download-2")
        compose.onNodeWithTag("download-retry-2").assertDoesNotExist()
        compose.onNodeWithTag("download-source-2").assertDoesNotExist()
    }

    @Test fun libraryRetainsMissingUnreadableAndUnconfirmedFilesWithoutPlaybackOrCovers() {
        showLibrary(listOf(
            asset(1).copy(availability = FileAvailability.MISSING),
            asset(2).copy(availability = FileAvailability.UNREADABLE),
            asset(3).copy(format = FormatCheck.UNCONFIRMED),
            asset(4).copy(availability = FileAvailability.UNKNOWN),
        ))
        listOf(1L, 2L, 3L, 4L).forEach { id ->
            scrollLibrary("video-$id")
            compose.onNodeWithTag("video-$id").assertExists()
            compose.onNodeWithTag("video-open-$id").assertDoesNotExist()
            compose.onNodeWithTag("video-share-$id").assertDoesNotExist()
            compose.onNodeWithText("cover-$id").assertDoesNotExist()
            compose.onNodeWithTag("video-source-$id").assertDoesNotExist()
        }
        scrollLibrary("video-1")
        compose.onNodeWithTag("video-delete-1").assertDoesNotExist()
        scrollLibrary("video-4")
        compose.onNodeWithTag("video-delete-4").assertDoesNotExist()
    }

    @Test fun libraryDefaultSourceSetDoesNotInventLegacySources() {
        showLibrary(listOf(asset(1)))
        scrollLibrary("video-1")
        compose.onNodeWithTag("video-open-1").assertExists()
        compose.onNodeWithTag("video-share-1").assertExists()
        compose.onNodeWithTag("video-source-1").assertDoesNotExist()
    }

    @Test fun libraryForgetAndDeleteHaveDistinctConfirmationsAndCallbacks() {
        val calls = mutableListOf<String>()
        showLibrary(listOf(asset(1)), calls = calls)
        scrollLibrary("video-forget-1")
        compose.onNodeWithTag("video-forget-1").performClick()
        compose.onNodeWithText("不删除设备上的文件", substring = true).assertExists()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        compose.onNodeWithTag("video-forget-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("forget:1"), calls) }
        scrollLibrary("video-delete-1")
        compose.onNodeWithTag("video-delete-1").performClick()
        compose.onNodeWithText("实际删除设备上的这个视频文件", substring = true).assertExists()
        compose.runOnIdle { assertEquals(listOf("forget:1"), calls) }
        compose.onNodeWithTag("video-delete-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("forget:1", "delete:1"), calls) }
    }

    @Test fun librarySourceActionRequiresAnExplicitAvailableId() {
        val calls = mutableListOf<String>()
        showLibrary(listOf(asset(1), asset(2)), sourceIds = setOf(2), calls = calls)
        scrollLibrary("video-1")
        compose.onNodeWithTag("video-source-1").assertDoesNotExist()
        scrollLibrary("video-source-2")
        compose.onNodeWithTag("video-source-2").performClick()
        compose.runOnIdle { assertEquals(listOf("source:2"), calls) }
    }

    @Test fun librarySourceAndFileActionsAreDisabledWhileBusy() {
        showLibrary(listOf(asset(1)), sourceIds = setOf(1), busy = setOf(1))
        scrollLibrary("video-source-1")
        listOf("source", "open", "share", "rename", "forget", "delete").forEach {
            compose.onNodeWithTag("video-$it-1").assertIsNotEnabled()
        }
    }

    @Test fun librarySearchMatchesDisplayNameAndActualFilename() {
        showLibrary(listOf(
            asset(1).copy(name = "holiday.mp4", displayName = "海边旅行"),
            asset(2).copy(name = "work.mp4", displayName = "会议记录"),
        ))
        compose.onNodeWithTag("videoLibrarySearch").performTextReplacement("HOLIDAY")
        compose.onNodeWithTag("videoLibraryCount").assert(hasText("1 / 2", substring = true))
        scrollLibrary("video-1")
        compose.onNodeWithTag("video-1").assertExists()
        compose.onNodeWithTag("video-2").assertDoesNotExist()
        scrollLibrary("videoLibrarySearch")
        compose.onNodeWithTag("videoLibrarySearch").performTextReplacement("会议")
        scrollLibrary("video-2")
        compose.onNodeWithTag("video-2").assertExists()
        compose.onNodeWithTag("video-1").assertDoesNotExist()
    }

    @Test fun libraryCanSwitchBetweenNewestAndOldestIndexTime() {
        showLibrary(listOf(asset(1).copy(indexedAt = 1000), asset(2).copy(indexedAt = 2000)))
        compose.onNodeWithTag("videoLibraryNewest").assertIsSelected()
        compose.onNodeWithTag("videoLibraryList").performScrollToIndex(1)
        assertFirstLibraryCard("video-2")
        compose.onNodeWithTag("videoLibraryList").performScrollToIndex(0)
        compose.onNodeWithTag("videoLibraryOldest").performClick().assertIsSelected()
        compose.onNodeWithTag("videoLibraryList").performScrollToIndex(1)
        assertFirstLibraryCard("video-1")
    }

    @Test fun libraryRenameChangesDisplayTitleNotFilename() {
        val calls = mutableListOf<String>()
        showLibrary(listOf(asset(1)), calls = calls)
        scrollLibrary("video-rename-1")
        compose.onNodeWithTag("video-rename-1").performClick()
        compose.onNodeWithText("不重命名设备文件", substring = true).assertExists()
        compose.onNodeWithTag("video-title-input-1").performTextReplacement(" ")
        compose.onNodeWithTag("video-title-save-1").assertIsNotEnabled()
        compose.onNodeWithTag("video-title-input-1").performTextReplacement(" 新的标题 ")
        compose.onNodeWithTag("video-title-save-1").performClick()
        compose.runOnIdle { assertEquals(listOf("rename:1:新的标题"), calls) }
    }

    private fun assertFirstLibraryCard(tag: String) {
        val cards = compose.onAllNodes(hasTestTag("video-1") or hasTestTag("video-2")).fetchSemanticsNodes()
        assertTrue(cards.isNotEmpty())
        assertEquals(tag, cards.first().config[SemanticsProperties.TestTag])
    }

    private fun scrollDownloads(tag: String) {
        compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag(tag))
    }

    private fun scrollLibrary(tag: String) {
        compose.onNodeWithTag("videoLibraryList").performScrollToNode(hasTestTag(tag))
    }

    private fun showDownloads(items: List<DownloadItem>, busy: Set<Long> = emptySet(), calls: MutableList<String> = mutableListOf()) {
        compose.setContent {
            PureBrowserTheme {
                DownloadsScreen(
                    items = items, busyIds = busy, onBack = {},
                    onOpen = { calls += "open:$it" }, onShare = { calls += "share:$it" },
                    onRetry = { calls += "retry:$it" }, onCancel = { calls += "cancel:$it" },
                    onForget = { calls += "forget:$it" }, onDelete = { calls += "delete:$it" },
                    onSource = { calls += "source:$it" }, onLibrary = {},
                )
            }
        }
    }

    private fun showLibrary(
        assets: List<VideoAsset>, sourceIds: Set<Long> = emptySet(), busy: Set<Long> = emptySet(),
        calls: MutableList<String> = mutableListOf(),
    ) {
        compose.setContent {
            PureBrowserTheme {
                VideoLibraryScreen(
                    assets = assets, busyIds = busy, onBack = {},
                    onOpen = { calls += "open:$it" }, onShare = { calls += "share:$it" },
                    onRename = { id, title -> calls += "rename:$id:$title" },
                    onForget = { calls += "forget:$it" }, onDelete = { calls += "delete:$it" },
                    onSource = { calls += "source:$it" }, onDownloads = {},
                    thumbnail = { Text("cover-${it.systemId}") }, sourceAvailableIds = sourceIds,
                )
            }
        }
    }

    private fun task(id: Long = 1) = DownloadItem(
        id = id, name = "sample-$id.mp4", status = DownloadManager.STATUS_RUNNING,
        bytes = 0, total = -1, detail = "下载中", recordId = "record-$id",
    )

    private fun completedTask(id: Long = 1) = task(id).copy(
        status = DownloadManager.STATUS_SUCCESSFUL, verified = true,
        format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE, detail = "已保存",
    )

    private fun asset(id: Long) = VideoAsset(
        recordId = "record-$id", systemId = id, uri = "content://downloads/all_downloads/$id",
        name = "sample-$id.mp4", displayName = "视频 $id", indexedAt = id * 1000,
        format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE,
    )
}

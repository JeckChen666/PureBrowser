package com.example.purebrowser.ui.downloads

import android.app.DownloadManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertHasClickAction
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.PauseReason
import com.example.purebrowser.download.TaskStatus
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

    @Test fun controlledStatesOverrideStaleTransportIntegers() {
        val controlled = task().copy(status = DownloadManager.STATUS_SUCCESSFUL, canPause = true, canResume = true)
        listOf(TaskStatus.QUEUED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK,
            TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.RUNNING,
            TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING).forEach { stage ->
            val item = controlled.copy(taskStatus = stage)
            assertEquals(DownloadUiGroup.ACTIVE, item.uiGroup())
            assertTrue(item.isActiveTask())
            assertFalse(item.canForgetRecord())
            assertFalse(item.canDeleteSavedFile())
            assertFalse(item.retryAvailable())
        }
        listOf(TaskStatus.PAUSING, TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING,
            TaskStatus.CANCELLED, TaskStatus.SUCCEEDED).forEach { stage ->
            assertFalse(controlled.copy(taskStatus = stage).pauseAvailable())
            assertFalse(controlled.copy(taskStatus = stage).resumeAvailable())
        }
        assertEquals(DownloadUiGroup.ATTENTION, completedTask().copy(taskStatus = TaskStatus.FAILED).uiGroup())
    }

    @Test fun optionalCallbacksAndCapabilitiesDoNotCreateFakeControls() {
        showDownloads(listOf(task().copy(taskStatus = TaskStatus.RUNNING, canPause = true, canResume = true)))
        scrollDownloads("download-details-1")
        compose.onNodeWithTag("download-pause-1").assertDoesNotExist()
        compose.onNodeWithTag("download-resume-1").assertDoesNotExist()
        val unsupported = task().copy(taskStatus = TaskStatus.PAUSED)
        assertFalse(unsupported.pauseAvailable())
        assertFalse(unsupported.resumeAvailable())
        assertFalse(task().copy(canPause = true, canResume = true).pauseAvailable())
        assertFalse(task().copy(canPause = true, canResume = true).resumeAvailable())
    }

    @Test fun pauseDispatchesOnlyPauseAndWaitsForRuntimeStateWithAccessibleProgress() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(task().copy(taskStatus = TaskStatus.RUNNING, canPause = true, bytes = 25, total = 100)),
            calls = calls, onPause = { calls += "pause:$it" }, onResume = { calls += "resume:$it" })
        scrollDownloads("download-progress-1")
        val progress = compose.onNodeWithTag("download-progress-1").fetchSemanticsNode().config
        assertEquals(0.25f, progress[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
        assertEquals("已传输 25%", progress[SemanticsProperties.StateDescription])
        scrollDownloads("download-pause-1")
        val button = compose.onNodeWithTag("download-pause-1")
        button.assertIsEnabled().assertHasClickAction()
        assertEquals(Role.Button, button.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertEquals(listOf("暂停下载，sample-1.mp4"), button.fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        button.performClick()
        compose.runOnIdle { assertEquals(listOf("pause:1"), calls) }
        compose.onNodeWithTag("download-resume-1").assertDoesNotExist()
        scrollDownloads("download-status-1")
        compose.onNodeWithTag("download-status-1").assert(hasText("正在传输"))
    }

    @Test fun pausingExplainsWriterWaitAndCannotResumeEvenWithStaleCapabilities() {
        showDownloads(listOf(task().copy(taskStatus = TaskStatus.PAUSING, canPause = true, canResume = true)),
            onPause = {}, onResume = {})
        scrollDownloads("download-pause-help-1")
        compose.onNodeWithTag("download-pause-help-1").assert(hasText("等待当前写入结束", substring = true))
        scrollDownloads("download-cancel-1")
        listOf("pause", "resume", "progress", "forget", "delete").forEach {
            compose.onNodeWithTag("download-$it-1").assertDoesNotExist()
        }
    }

    @Test fun pausedResumeUsesSameIdAndCancellationStillRequiresCacheCleanupConfirmation() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(task().copy(taskStatus = TaskStatus.PAUSED, pauseReason = PauseReason.USER,
            canResume = true, cacheBytes = 4096)), calls = calls, onResume = { calls += "resume:$it" })
        scrollDownloads("download-cache-1")
        compose.onNodeWithTag("download-cache-1").assert(hasText(localFileSize(4096), substring = true))
        compose.onNodeWithTag("download-reason-1").assert(hasText("用户主动暂停", substring = true))
        scrollDownloads("download-resume-1")
        compose.onNodeWithTag("download-resume-1").performClick()
        compose.runOnIdle { assertEquals(listOf("resume:1"), calls) }
        compose.onNodeWithTag("download-progress-1").assertDoesNotExist()
        compose.onNodeWithTag("download-forget-1").assertDoesNotExist()
        compose.onNodeWithTag("download-cancel-1").performClick()
        compose.onNodeWithText("已传输的数据不会保留用于续传", substring = true).assertExists()
        compose.onNodeWithTag("download-cancel-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("resume:1", "cancel:1"), calls) }
    }

    @Test fun waitingNetworkIsCancellableButOnlySafeCheckpointsCanResume() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(task().copy(taskStatus = TaskStatus.WAITING_NETWORK, pauseReason = PauseReason.NETWORK,
            canResume = false, cacheBytes = 2048)), calls = calls, onResume = { calls += "resume:$it" })
        scrollDownloads("download-status-1")
        compose.onNodeWithTag("download-status-1").assert(hasText("等待网络"))
        scrollDownloads("download-cancel-1")
        compose.onNodeWithTag("download-resume-1").assertDoesNotExist()
        compose.onNodeWithTag("download-cancel-1").performClick()
        compose.onNodeWithTag("download-cancel-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("cancel:1"), calls) }
    }

    @Test fun busyPauseAndResumeControlsAreDisabled() {
        showDownloads(listOf(
            task().copy(taskStatus = TaskStatus.RUNNING, canPause = true),
            task(2).copy(taskStatus = TaskStatus.PAUSED, canResume = true),
        ), busy = setOf(1, 2), onPause = {}, onResume = {})
        scrollDownloads("download-pause-1")
        compose.onNodeWithTag("download-pause-1").assertIsNotEnabled()
        compose.onNodeWithTag("download-cancel-1").assertIsNotEnabled()
        scrollDownloads("download-resume-2")
        compose.onNodeWithTag("download-resume-2").assertIsNotEnabled()
        compose.onNodeWithTag("download-cancel-2").assertIsNotEnabled()
    }

    @Test fun finalizationStagesHaveNoFakePauseOrTransferPercentage() {
        val stages = listOf(TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING)
        showDownloads(stages.mapIndexed { index, stage ->
            task((index + 1).toLong()).copy(taskStatus = stage, canPause = true, canResume = true, bytes = 100, total = 100)
        }, onPause = {}, onResume = {})
        stages.forEachIndexed { index, stage ->
            val id = index + 1
            scrollDownloads("download-progress-$id")
            assertEquals(ProgressBarRangeInfo.Indeterminate,
                compose.onNodeWithTag("download-progress-$id").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
            scrollDownloads("download-cancel-$id")
            compose.onNodeWithTag("download-pause-$id").assertDoesNotExist()
            compose.onNodeWithTag("download-resume-$id").assertDoesNotExist()
            assertNull(task().copy(taskStatus = stage, bytes = 100, total = 100).progressFraction())
        }
    }

    @Test fun failedNetworkOrChangedSourceRequiresRedownloadNotResume() {
        val calls = mutableListOf<String>()
        val failed = task().copy(taskStatus = TaskStatus.FAILED, failure = FailureKind.NETWORK,
            canRetry = true, canResume = false)
        listOf(PauseReason.ACCESS, PauseReason.SOURCE_CHANGED).forEach { reason ->
            assertFalse(task().copy(taskStatus = TaskStatus.PAUSED, canResume = true, pauseReason = reason).resumeAvailable())
        }
        showDownloads(listOf(failed), calls = calls, onResume = { calls += "resume:$it" })
        scrollDownloads("download-reason-1")
        compose.onNodeWithTag("download-reason-1").assert(hasText("网络传输失败", substring = true))
        scrollDownloads("download-retry-1")
        compose.onNodeWithTag("download-resume-1").assertDoesNotExist()
        compose.onNodeWithTag("download-retry-1").performClick()
        compose.onNodeWithTag("download-retry-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("retry:1"), calls) }
    }

    @Test fun changedSourceOrAccessConditionNeverOffersUnsafeResume() {
        showDownloads(listOf(PauseReason.ACCESS, PauseReason.SOURCE_CHANGED).mapIndexed { index, reason ->
            task((index + 1).toLong()).copy(taskStatus = TaskStatus.PAUSED, pauseReason = reason, canResume = true)
        }, onResume = {})
        listOf(1, 2).forEach { id ->
            scrollDownloads("download-reason-$id")
            compose.onNodeWithTag("download-reason-$id").assert(hasText("不能安全续传", substring = true))
            scrollDownloads("download-pause-help-$id")
            compose.onNodeWithTag("download-pause-help-$id").assert(hasText("重新下载会创建新任务", substring = true))
            scrollDownloads("download-cancel-$id")
            compose.onNodeWithTag("download-resume-$id").assertDoesNotExist()
            compose.onNodeWithTag("download-cancel-$id").assertIsEnabled()
        }
    }

    @Test fun reconciledInterruptedCheckpointOffersSameTaskRecoveryNotFakeRedownload() {
        val calls = mutableListOf<String>()
        val item = task().copy(taskStatus = TaskStatus.INTERRUPTED, pauseReason = PauseReason.RECOVERY,
            failure = FailureKind.INTERRUPTED, canResume = true, cacheBytes = 2048)
        assertTrue(item.resumeAvailable())
        assertTrue(item.canCancelTask())
        showDownloads(listOf(item), calls = calls, onResume = { calls += "resume:$it" })
        scrollDownloads("download-resume-1")
        compose.onNodeWithTag("download-resume-1").performClick()
        compose.runOnIdle { assertEquals(listOf("resume:1"), calls) }
        compose.onNodeWithTag("download-forget-1").assertDoesNotExist()
        compose.onNodeWithTag("download-cancel-1").assertIsEnabled()
    }

    @Test fun hlsPausedProgressCountsOnlyConfirmedSegmentsAndPrivateCache() {
        val item = task().copy(protocol = DownloadProtocol.HLS, taskStatus = TaskStatus.PAUSED,
            segmentCount = 8, completedSegments = 3, bytes = 4096, total = 8192, cacheBytes = 2048, canResume = true)
        assertEquals(0.375f, item.progressFraction()!!, 0.001f)
        assertFalse(item.byteSummary().contains("%"))
        showDownloads(listOf(item), onResume = {})
        scrollDownloads("download-segments-1")
        compose.onNodeWithTag("download-segments-1").assert(hasText("3 / 8", substring = true))
        compose.onNodeWithTag("download-progress-1").assertDoesNotExist()
    }

    @Test fun narrowLargeFontLayoutCanReachWrappedControlsAndScrollableDetail() {
        val calls = mutableListOf<String>()
        showDownloads(listOf(task().copy(taskStatus = TaskStatus.PAUSED, canResume = true, cacheBytes = 4096,
            pauseReason = PauseReason.RECOVERY, sourceUrl = "https://example.com/watch")), calls = calls,
            onResume = { calls += "resume:$it" }, largeFontNarrow = true)
        scrollDownloads("download-resume-1")
        compose.onNodeWithTag("download-resume-1").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("resume:1"), calls) }
        scrollDownloads("download-details-1")
        compose.onNodeWithTag("download-details-1").assertIsDisplayed().performClick()
        compose.onNodeWithText("私有缓存", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("停止原因", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新下载规则", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭详情").assertIsDisplayed().performClick()
        scrollDownloads("download-cancel-1")
        compose.onNodeWithTag("download-cancel-1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("download-cancel-dialog-1-confirm").assertIsDisplayed()
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

    private fun showDownloads(
        items: List<DownloadItem>, busy: Set<Long> = emptySet(), calls: MutableList<String> = mutableListOf(),
        onPause: ((String) -> Unit)? = null, onResume: ((String) -> Unit)? = null, largeFontNarrow: Boolean = false,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFontNarrow) 2f else density.fontScale)) {
                PureBrowserTheme {
                    Box(if (largeFontNarrow) Modifier.width(260.dp).height(440.dp) else Modifier) {
                        DownloadsScreen(
                            items = items, busyIds = busy.map { it.toString() }.toSet(), onBack = {},
                            onOpen = { calls += "open:$it" }, onShare = { calls += "share:$it" },
                            onRetry = { calls += "retry:$it" }, onCancel = { calls += "cancel:$it" },
                            onForget = { calls += "forget:$it" }, onDelete = { calls += "delete:$it" },
                            onSource = { calls += "source:$it" }, onLibrary = {}, onPause = onPause, onResume = onResume,
                        )
                    }
                }
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
                    assets = assets, busyIds = busy.map { it.toString() }.toSet(), onBack = {},
                    onOpen = { calls += "open:$it" }, onShare = { calls += "share:$it" },
                    onRename = { id, title -> calls += "rename:$id:$title" },
                    onForget = { calls += "forget:$it" }, onDelete = { calls += "delete:$it" },
                    onSource = { calls += "source:$it" }, onDownloads = {},
                    thumbnail = { Text("cover-${it.systemId}") }, sourceAvailableIds = sourceIds.map { it.toString() }.toSet(),
                )
            }
        }
    }

    private fun task(id: Long = 1) = DownloadItem(
        id = id.toString(), name = "sample-$id.mp4", status = DownloadManager.STATUS_RUNNING,
        bytes = 0, total = -1, detail = "下载中", recordId = "record-$id",
    )

    private fun completedTask(id: Long = 1) = task(id).copy(
        status = DownloadManager.STATUS_SUCCESSFUL, verified = true,
        format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE, detail = "已保存",
    )

    private fun asset(id: Long) = VideoAsset(
        recordId = id.toString(), systemId = id, uri = "content://downloads/all_downloads/$id",
        name = "sample-$id.mp4", displayName = "视频 $id", indexedAt = id * 1000,
        format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE,
    )
}

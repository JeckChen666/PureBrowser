package com.example.purebrowser.ui.downloads

import android.app.DownloadManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.PauseReason
import com.example.purebrowser.download.SystemTaskRead
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** No activity/coordinator/polling: exercise only caller-owned snapshots and callback contracts. */
class DownloadQuickSheetUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyQuickViewDismissesBeforeOpeningAllTasks() {
        val calls = mutableListOf<String>()
        showQuick(items = { emptyList() }, calls = calls)
        compose.onNodeWithText("还没有下载任务").assertIsDisplayed()
        compose.onNodeWithTag("downloadQuickOpenAll").performClick()
        compose.onNodeWithTag("downloadQuickSheet").assertDoesNotExist()
        assertEquals(listOf("dismiss", "all"), calls)
    }

    @Test fun libraryNavigationDismissesFirstWithoutMutatingTasks() {
        val calls = mutableListOf<String>()
        showQuick(calls = calls)
        compose.onNodeWithTag("downloadQuickLibrary").performClick()
        assertEquals(listOf("dismiss", "library"), calls)
    }

    @Test fun iconActionsHaveMeasured48dpTargetsAndTaskSpecificSemantics() {
        val calls = mutableListOf<String>()
        showQuick(calls = calls, controlsConnected = true)
        scrollQuick("download-pause-task")
        compose.onNodeWithTag("download-pause-task")
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("暂停下载，sample.mp4")))
            .performClick()
        assertEquals(listOf("pause:task"), calls)
        compose.onNodeWithTag("download-resume-task").assertDoesNotExist()
        compose.onNodeWithTag("download-forget-task").assertDoesNotExist()
    }

    @Test fun unknownTotalAndProcessingNeverInventOverallPercentOrPause() {
        val snapshot = mutableStateOf(task())
        showQuick(items = { listOf(snapshot.value) }, controlsConnected = true)
        scrollQuick("download-progress-task")
        compose.onNodeWithTag("download-progress-task").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        compose.onNodeWithTag("download-bytes-task").assert(hasText("总大小未知", substring = true))
        listOf(TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING).forEach { phase ->
            compose.runOnIdle { snapshot.value = task().copy(taskStatus = phase, total = 100, bytes = 100, canResume = true) }
            scrollQuick("download-progress-task")
            compose.onNodeWithTag("download-progress-task").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            )
            scrollQuick("download-cancel-task")
            compose.onNodeWithTag("download-pause-task").assertDoesNotExist()
            compose.onNodeWithTag("download-resume-task").assertDoesNotExist()
            compose.onNodeWithTag("download-open-task").assertDoesNotExist()
        }
    }

    @Test fun hlsQuickViewUsesSegmentProgressAndNeverTreatsPlaylistLengthAsSavedSize() {
        showQuick(items = { listOf(task().copy(
            protocol = DownloadProtocol.HLS, bytes = 50, total = 100,
            segmentCount = 8, completedSegments = 3,
        )) })
        scrollQuick("download-segments-task")
        compose.onNodeWithTag("download-segments-task").assert(hasText("3 / 8", substring = true))
            .assert(hasText("不代表整体保存进度", substring = true))
        compose.onNodeWithTag("download-bytes-task").assert(hasText("总大小未知", substring = true))
        scrollQuick("download-progress-task")
        compose.onNodeWithTag("download-progress-task").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(3f / 8f, 0f..1f)),
        )
        compose.onNodeWithTag("download-open-task").assertDoesNotExist()
        // An unwired callback never becomes an apparently working control.
        compose.onNodeWithTag("download-pause-task").assertDoesNotExist()
        compose.onNodeWithTag("download-source-task").assertDoesNotExist()
    }

    @Test fun pausedTaskUsesSameIdAndCapabilitiesWithoutPretendingItIsSaved() {
        val calls = mutableListOf<String>()
        showQuick(
            items = { listOf(task().copy(taskStatus = TaskStatus.PAUSED, canPause = false, canResume = true, pauseReason = PauseReason.USER)) },
            calls = calls, controlsConnected = true,
        )
        scrollQuick("download-resume-task")
        compose.onNodeWithTag("download-resume-task").performClick()
        compose.onNodeWithTag("download-open-task").assertDoesNotExist()
        compose.onNodeWithTag("download-forget-task").assertDoesNotExist()
        assertEquals(listOf("resume:task"), calls)
    }

    @Test fun busyIdsDisableConnectedActionsAndConfirmationRechecksLatestSnapshot() {
        val busy = mutableStateOf(emptySet<String>())
        val snapshot = mutableStateOf(task())
        val calls = mutableListOf<String>()
        showQuick(items = { listOf(snapshot.value) }, busy = { busy.value }, calls = calls, controlsConnected = true)
        compose.runOnIdle { busy.value = setOf("task") }
        scrollQuick("download-pause-task")
        compose.onNodeWithTag("download-pause-task").assertIsNotEnabled()
        compose.onNodeWithTag("download-cancel-task").assertIsNotEnabled()
        compose.runOnIdle { busy.value = emptySet() }
        compose.onNodeWithTag("download-cancel-task").performClick()
        compose.onNodeWithTag("downloadQuickSheet").assertDoesNotExist()
        compose.onNodeWithTag("download-cancel-dialog-task-confirm").assertIsEnabled()
        compose.runOnIdle { busy.value = setOf("task") }
        compose.onNodeWithTag("download-cancel-dialog-task-confirm").assertIsNotEnabled()
        compose.runOnIdle { busy.value = emptySet(); snapshot.value = saved() }
        compose.onNodeWithTag("download-cancel-dialog-task-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("download-cancel-dialog-task-dismiss").performClick()
        compose.onNodeWithTag("downloadQuickSheet").assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun cancelRequiresLabeledConfirmationAndDispatchesExactlyOnce() {
        val calls = mutableListOf<String>()
        showQuick(calls = calls)
        scrollQuick("download-cancel-task")
        compose.onNodeWithTag("download-cancel-task").assert(hasText("取消下载", substring = true)).performClick()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithText("已传输的数据不会保留用于续传", substring = true).assertExists()
        compose.onNodeWithTag("download-cancel-dialog-task-confirm").performClick()
        compose.onNodeWithTag("download-cancel-dialog-task").assertDoesNotExist()
        assertEquals(listOf("cancel:task"), calls)
    }

    @Test fun accessFailuresHaveSourceButNoUnsafeResumeAndRetryCreatesNewTask() {
        val calls = mutableListOf<String>()
        showQuick(
            items = { listOf(task().copy(taskStatus = TaskStatus.FAILED, canRetry = true, canResume = true, failure = FailureKind.ACCESS_CONDITION, sourceUrl = "https://example.test/watch")) },
            calls = calls, controlsConnected = true,
        )
        scrollQuick("download-reason-task")
        compose.onNodeWithTag("download-reason-task").assert(hasText("链接已过期", substring = true))
        scrollQuick("download-retry-task")
        compose.onNodeWithTag("download-resume-task").assertDoesNotExist()
        compose.onNodeWithTag("download-retry-task").performClick()
        compose.onNodeWithText("不是在原任务上继续", substring = true).assertExists()
        compose.onNodeWithTag("download-retry-dialog-task-confirm").performClick()
        assertEquals(listOf("retry:task"), calls)
        scrollQuick("download-source-task")
        compose.onNodeWithTag("download-source-task").performClick()
        assertEquals(listOf("retry:task", "dismiss", "source:task"), calls)
    }

    @Test fun completedFileActionsDisappearWhenCallerReportsMissingFile() {
        val snapshot = mutableStateOf(saved())
        val calls = mutableListOf<String>()
        showQuick(items = { listOf(snapshot.value) }, calls = calls)
        scrollQuick("download-open-task")
        compose.onNodeWithTag("download-open-task").performClick()
        compose.onNodeWithTag("download-share-task").performClick()
        assertEquals(listOf("open:task", "share:task"), calls)
        compose.runOnIdle { snapshot.value = saved().copy(availability = FileAvailability.MISSING) }
        scrollQuick("download-details-task")
        compose.onNodeWithTag("download-open-task").assertDoesNotExist()
        compose.onNodeWithTag("download-share-task").assertDoesNotExist()
        // Unwired record actions must not look usable.
        compose.onNodeWithTag("download-forget-task").assertDoesNotExist()
        compose.onNodeWithTag("download-delete-task").assertDoesNotExist()
    }

    @Test fun localDetailReplacesSheetThenReturnsAndRemovedTaskClosesDetail() {
        val snapshot = mutableStateOf(listOf(task()))
        showQuick(items = { snapshot.value })
        scrollQuick("download-details-task")
        compose.onNodeWithTag("download-details-task").performClick()
        compose.onNodeWithTag("downloadQuickSheet").assertDoesNotExist()
        compose.onNodeWithTag("download-detail-task").assertExists()
        compose.onNodeWithText("关闭详情").performClick()
        compose.onNodeWithTag("downloadQuickSheet").assertExists()
        scrollQuick("download-details-task")
        compose.onNodeWithTag("download-details-task").performClick()
        compose.runOnIdle { snapshot.value = emptyList() }
        compose.onNodeWithTag("download-detail-task").assertDoesNotExist()
        compose.onNodeWithText("还没有下载任务").assertExists()
    }

    @Test fun externalDetailDismissesBeforeCallingParent() {
        val calls = mutableListOf<String>()
        showQuick(calls = calls, externalDetail = true)
        scrollQuick("download-details-task")
        compose.onNodeWithTag("download-details-task").performClick()
        assertEquals(listOf("dismiss", "detail:task"), calls)
        compose.onNodeWithTag("download-detail-task").assertDoesNotExist()
    }

    @Test fun recordRemovalAndPhysicalDeleteStayDistinctAndConfirmed() {
        val calls = mutableListOf<String>()
        showQuick(items = { listOf(saved()) }, calls = calls, recordActions = true)
        scrollQuick("download-forget-task")
        compose.onNodeWithTag("download-forget-task").assert(hasText("保留文件", substring = true)).performClick()
        compose.onNodeWithText("不会删除设备文件", substring = true).assertExists()
        compose.onNodeWithTag("download-forget-dialog-task-confirm").performClick()
        scrollQuick("download-delete-task")
        compose.onNodeWithTag("download-delete-task").assert(hasText("删除文件")).performClick()
        compose.onNodeWithText("实际删除设备上此任务保存的文件", substring = true).assertExists()
        compose.onNodeWithTag("download-delete-dialog-task-confirm").performClick()
        assertEquals(listOf("forget:task", "delete:task"), calls)
    }

    @Test fun darkLargeFontNarrowFullPageKeepsIconsAndDestructiveConfirmationReachable() {
        val calls = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PureBrowserTheme(mode = ThemeMode.DARK) {
                    Box(Modifier.width(260.dp).height(440.dp)) {
                        DownloadsScreen(
                            items = listOf(task()), busyIds = emptySet(), onBack = {}, onLibrary = {},
                            onOpen = {}, onShare = {}, onRetry = {}, onCancel = { calls += "cancel:$it" },
                            onForget = {}, onDelete = {}, onSource = {}, onPause = { calls += "pause:$it" },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-pause-task"))
        compose.onNodeWithTag("download-pause-task").assertIsDisplayed()
            .assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-cancel-task"))
        compose.onNodeWithTag("download-cancel-task").assertIsDisplayed().performClick()
        compose.onNodeWithTag("download-cancel-dialog-task-confirm").assertIsDisplayed().performClick()
        assertEquals(listOf("pause:task", "cancel:task"), calls)
    }

    private fun scrollQuick(tag: String) {
        compose.onNodeWithTag("downloadQuickList").performScrollToNode(hasTestTag(tag))
    }

    private fun showQuick(
        items: () -> List<DownloadItem> = { listOf(task()) },
        busy: () -> Set<String> = { emptySet() },
        calls: MutableList<String> = mutableListOf(),
        controlsConnected: Boolean = false,
        recordActions: Boolean = false,
        externalDetail: Boolean = false,
    ) {
        compose.setContent {
            val visible = remember { mutableStateOf(true) }
            PureBrowserTheme {
                if (visible.value) DownloadQuickSheet(
                    items = items(), busyIds = busy(),
                    onDismiss = { calls += "dismiss"; visible.value = false },
                    onOpenAll = { calls += "all" }, onOpenLibrary = { calls += "library" },
                    onOpenFile = { calls += "open:$it" }, onShare = { calls += "share:$it" },
                    onRetry = { calls += "retry:$it" }, onCancel = { calls += "cancel:$it" },
                    onSource = { calls += "source:$it" },
                    onPause = if (controlsConnected) ({ calls += "pause:$it" }) else null,
                    onResume = if (controlsConnected) ({ calls += "resume:$it" }) else null,
                    onForget = if (recordActions) ({ calls += "forget:$it" }) else null,
                    onDelete = if (recordActions) ({ calls += "delete:$it" }) else null,
                    onDetail = if (externalDetail) ({ calls += "detail:$it" }) else null,
                )
            }
        }
    }

    private fun task() = DownloadItem(
        id = "task", name = "sample.mp4", status = DownloadManager.STATUS_RUNNING,
        bytes = 32, total = -1, detail = "下载中", taskStatus = TaskStatus.RUNNING,
        canPause = true, systemRead = SystemTaskRead.PRESENT,
    )

    private fun saved() = task().copy(
        status = DownloadManager.STATUS_SUCCESSFUL, taskStatus = TaskStatus.SUCCEEDED,
        verified = true, format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE,
        canPause = false,
    )
}

package com.example.purebrowser.ui.downloads

import android.app.DownloadManager
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.PauseReason
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.download.SystemTaskRead
import com.example.purebrowser.ui.CopyMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared presentation contract for both downloads surfaces; no repository or engine access. */
class DownloadPresentationRulesTest {
    @Test fun unknownSizesAndPostTransferStagesHaveNoFraction() {
        assertNull(item().copy(total = -1).progressFraction())
        assertNull(item().copy(total = 0).progressFraction())
        assertNull(item().copy(total = 10, bytes = 11).progressFraction())
        listOf(TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING).forEach {
            val processing = item().copy(taskStatus = it, total = 10, bytes = 10, canPause = true, canResume = true)
            assertNull(processing.progressFraction())
            assertFalse(processing.pauseAvailable())
            assertFalse(processing.resumeAvailable())
        }
    }

    @Test fun hlsOnlyReportsReliableSegmentTransferNotOverallSavedSize() {
        val hls = item().copy(protocol = DownloadProtocol.HLS, total = 100, bytes = 50)
        assertNull(hls.progressFraction())
        assertTrue(hls.byteSummary().contains("总大小未知"))
        assertTrue(hls.copy(segmentCount = 4, completedSegments = 2).progressDescription().contains("不代表整体保存进度"))
        assertNull(hls.copy(segmentCount = 4, completedSegments = 5).progressFraction())
    }

    @Test fun recoveryHintsKeepNetworkStorageAndAccessFailuresDistinct() {
        assertTrue(item().copy(failure = FailureKind.NETWORK).recoveryHint().contains("检查网络"))
        assertFalse(item().copy(failure = FailureKind.NETWORK).recoveryHint().contains("需要登录"))
        assertTrue(item().copy(failure = FailureKind.STORAGE).recoveryHint().contains("存储空间"))
        assertTrue(item().copy(failure = FailureKind.ACCESS_CONDITION, sourceUrl = "https://example.test").recoveryHint().contains("需要登录"))
        assertTrue(item().copy(pauseReason = PauseReason.WIFI).recoveryHint().contains("仅允许 Wi-Fi"))
        assertTrue(item().copy(failure = FailureKind.NOT_VIDEO).recoveryHint().contains("暂不支持"))
    }

    /** T118: failed tasks surface mapped user guidance, classified from the safe failure text. */
    @Test fun failedTasksShowMappedGuidanceFromClassifiedSafeFailure() {
        val encrypted = item().copy(taskStatus = TaskStatus.FAILED, failure = FailureKind.UNSUPPORTED, detail = "本版不支持加密 HLS")
        assertEquals(CopyMapping.PROTECTED, encrypted.stoppedReason())
        val expired = item().copy(taskStatus = TaskStatus.FAILED, failure = FailureKind.ACCESS_CONDITION, detail = "当前访问条件不足")
        assertEquals(CopyMapping.LINK_EXPIRED, expired.stoppedReason())
        val unclassified = item().copy(taskStatus = TaskStatus.FAILED, failure = FailureKind.INTERRUPTED, detail = "无法归类的诊断文本")
        assertEquals(CopyMapping.UNKNOWN, unclassified.stoppedReason())
    }

    @Test fun unknownStateDoesNotEnableDestructiveRecordOrFileActions() {
        val unknown = item().copy(systemRead = SystemTaskRead.UNAVAILABLE, taskStatus = TaskStatus.SUCCEEDED,
            availability = FileAvailability.AVAILABLE, canRetry = true)
        assertFalse(unknown.canForgetRecord())
        assertFalse(unknown.canDeleteSavedFile())
        assertFalse(unknown.retryAvailable())
        assertTrue(unknown.recoveryHint().contains("等待状态确认"))
    }

    @Test fun missingFileGetsRecoveryNotFalseCompletion() {
        val missing = item().copy(taskStatus = TaskStatus.SUCCEEDED, verified = true, availability = FileAvailability.MISSING)
        assertTrue(missing.uiGroup() == DownloadUiGroup.ATTENTION)
        assertTrue(missing.recoveryHint().contains("移除记录不会找回文件"))
        assertFalse(missing.canDeleteSavedFile())
    }

    private fun item() = DownloadItem(
        id = "task", name = "sample.mp4", status = DownloadManager.STATUS_RUNNING,
        bytes = 5, total = 10, detail = "", taskStatus = TaskStatus.RUNNING,
    )
}

package com.example.purebrowser.download

import org.junit.Assert.*
import org.junit.Test

class TaskControlRulesTest {
    private fun direct(status:TaskStatus,available:Boolean=false)=DownloadRecord(name="v.mp4",transfer=TransferType.CONTROLLED,
        taskStatus=status,received=100,resumeAvailable=available)
    @Test fun runningDirectNeedsIdentityBeforePause() {
        assertFalse(TaskControlRules.canPause(direct(TaskStatus.RUNNING)))
        assertTrue(TaskControlRules.canPause(direct(TaskStatus.RUNNING,true)))
    }
    @Test fun hlsCanPauseButOfflineStagesCannot() {
        assertTrue(TaskControlRules.canPause(direct(TaskStatus.RUNNING).copy(protocol=DownloadProtocol.HLS)))
        listOf(TaskStatus.MUXING,TaskStatus.VERIFYING,TaskStatus.PUBLISHING).forEach {
            assertFalse(TaskControlRules.canPause(direct(it,true)))
        }
    }
    @Test fun stoppingNeverResumesUntilWriterIsDone() {
        assertFalse(TaskControlRules.canResume(direct(TaskStatus.PAUSING,true)))
        assertTrue(TaskControlRules.canResume(direct(TaskStatus.PAUSED,true)))
        assertFalse(TaskControlRules.canResume(direct(TaskStatus.PAUSED)))
    }
    @Test fun oldPartialAndAuthFailuresCannotClaimResume() {
        assertFalse(TaskControlRules.canResume(direct(TaskStatus.INTERRUPTED)))
        assertFalse(TaskControlRules.canResume(direct(TaskStatus.FAILED,true).copy(failure=FailureKind.ACCESS_CONDITION)))
        assertTrue(TaskControlRules.canResume(direct(TaskStatus.FAILED,true).copy(failure=FailureKind.NETWORK)))
    }
    @Test fun unstartedTaskCanResumeWithoutInventingCheckpoint() {
        assertTrue(TaskControlRules.canResume(direct(TaskStatus.INTERRUPTED).copy(received=0)))
        assertFalse(TaskControlRules.canResume(direct(TaskStatus.FAILED).copy(received=0)))
    }
    @Test fun pausedCanCancelButFinishedAndHistoricalCannot() {
        assertTrue(TaskControlRules.canCancel(direct(TaskStatus.PAUSED,true)))
        assertFalse(TaskControlRules.canCancel(direct(TaskStatus.SUCCEEDED,true)))
        assertFalse(TaskControlRules.canPause(DownloadRecord(name="old.mp4",systemId=1,taskStatus=TaskStatus.RUNNING)))
    }
}

package com.example.purebrowser.download

/** Capability hints never replace checkpoint/HTTP validation at execution time. */
object TaskControlRules {
    val writing = setOf(TaskStatus.RUNNING, TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING)
    val waiting = setOf(TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK)
    val stopped = waiting + setOf(TaskStatus.PAUSED, TaskStatus.INTERRUPTED, TaskStatus.FAILED)
    fun canPause(r: DownloadRecord): Boolean = r.transfer == TransferType.CONTROLLED &&
        r.protocol != DownloadProtocol.DUAL_TRACK && r.protocol != DownloadProtocol.DASH &&
        (r.taskStatus in waiting || r.taskStatus == TaskStatus.QUEUED ||
            (r.taskStatus == TaskStatus.RUNNING && (r.protocol == DownloadProtocol.HLS || r.resumeAvailable)))
    fun canResume(r: DownloadRecord): Boolean = r.transfer == TransferType.CONTROLLED &&
        r.protocol != DownloadProtocol.DUAL_TRACK && r.protocol != DownloadProtocol.DASH &&
        r.taskStatus in stopped && r.pauseReason != PauseReason.SOURCE_CHANGED &&
        r.failure !in setOf(FailureKind.ACCESS_CONDITION, FailureKind.NOT_VIDEO, FailureKind.UNSUPPORTED, FailureKind.HTTP_REJECTED) &&
        (r.resumeAvailable || (r.protocol == DownloadProtocol.DIRECT && r.received == 0L && r.completedSegments == 0 && r.taskStatus != TaskStatus.FAILED))
    fun canCancel(r: DownloadRecord): Boolean = r.transfer == TransferType.CONTROLLED &&
        r.taskStatus !in setOf(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED) &&
        (r.taskStatus != TaskStatus.FAILED || r.resumeAvailable)
}

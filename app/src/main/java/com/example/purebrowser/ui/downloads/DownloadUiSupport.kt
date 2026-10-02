package com.example.purebrowser.ui.downloads

import android.app.DownloadManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.FailureKind
import com.example.purebrowser.download.PauseReason
import com.example.purebrowser.download.SystemTaskRead
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class DownloadUiGroup(val title: String, val tag: String) {
    ACTIVE("进行中与暂停", "active"),
    COMPLETED("已完成", "completed"),
    ATTENTION("失败或需处理", "attention"),
}

/** A stale transport integer must never turn an unread system task into an active one. */
internal fun DownloadItem.isActiveTask(): Boolean =
    systemRead == SystemTaskRead.PRESENT && !cancelled && if (taskStatus != null) {
        taskStatus in setOf(TaskStatus.QUEUED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK,
            TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.RUNNING, TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING)
    } else status in setOf(
        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED,
    )

internal fun DownloadItem.uiGroup(): DownloadUiGroup = when {
    isActiveTask() -> DownloadUiGroup.ACTIVE
    systemRead == SystemTaskRead.PRESENT && !cancelled &&
        (if (taskStatus != null) taskStatus == TaskStatus.SUCCEEDED else status == DownloadManager.STATUS_SUCCESSFUL) && verified &&
        format == FormatCheck.PASSED && availability == FileAvailability.AVAILABLE -> DownloadUiGroup.COMPLETED
    else -> DownloadUiGroup.ATTENTION
}

internal fun DownloadItem.stateLabel(): String = when {
    cancelled -> "已取消下载"
    systemRead == SystemTaskRead.UNAVAILABLE -> "系统任务暂时无法读取"
    systemRead == SystemTaskRead.MISSING -> "系统任务不存在 · 记录已保留"
    taskStatus != null -> when (taskStatus) {
        TaskStatus.QUEUED -> "排队中"
        TaskStatus.WAITING_WIFI -> "等待 Wi-Fi"
        TaskStatus.WAITING_NETWORK -> "等待网络"
        TaskStatus.PAUSING -> "正在暂停 · 等待写入结束"
        TaskStatus.PAUSED -> "已暂停 · 尚未保存成品"
        TaskStatus.RUNNING -> if (protocol == DownloadProtocol.HLS) "正在下载分片" else "正在传输"
        TaskStatus.MUXING -> "正在封装 MP4 · 尚未保存"
        TaskStatus.VERIFYING -> if (protocol == DownloadProtocol.HLS) "正在校验 MP4 · 尚未保存" else "正在校验文件 · 尚未保存"
        TaskStatus.PUBLISHING -> "正在保存至公共下载目录"
        TaskStatus.SUCCEEDED -> savedStateLabel()
        TaskStatus.FAILED -> "下载失败"
        TaskStatus.CANCELLED -> "已取消下载"
        TaskStatus.INTERRUPTED -> "下载已中断"
    }
    status == DownloadManager.STATUS_PENDING -> "排队中"
    status == DownloadManager.STATUS_RUNNING -> "正在传输"
    status == DownloadManager.STATUS_PAUSED -> "等待网络 / 系统重试"
    status == DownloadManager.STATUS_FAILED -> "传输失败"
    status == DownloadManager.STATUS_SUCCESSFUL -> savedStateLabel()
    else -> "任务状态待确认"
}

private fun DownloadItem.savedStateLabel(): String = when (availability) {
    FileAvailability.MISSING -> "文件已丢失"
    FileAvailability.UNREADABLE -> "文件暂时无法读取"
    FileAvailability.UNKNOWN -> "传输完成 · 文件状态待确认"
    FileAvailability.AVAILABLE -> when (format) {
        FormatCheck.INVALID -> "格式初检失败"
        FormatCheck.UNCONFIRMED -> "格式初检无法确认"
        FormatCheck.NOT_CHECKED -> "传输完成 · 等待格式初检"
        FormatCheck.PASSED -> if (verified) "已保存 · 格式初检通过" else "传输完成 · 等待核对"
    }
}

/** Capabilities are necessary, but stale capabilities cannot pause finalization or restart a writer. */
internal fun DownloadItem.pauseAvailable(): Boolean = canPause && isActiveTask() &&
    taskStatus in setOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK)

internal fun DownloadItem.resumeAvailable(): Boolean = canResume && !cancelled && systemRead == SystemTaskRead.PRESENT &&
    taskStatus in setOf(TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED, TaskStatus.FAILED) &&
    pauseReason !in setOf(PauseReason.ACCESS, PauseReason.SOURCE_CHANGED) &&
    failure !in setOf(FailureKind.ACCESS_CONDITION, FailureKind.HTTP_REJECTED, FailureKind.NOT_VIDEO, FailureKind.UNSUPPORTED)

internal fun DownloadItem.canCancelTask(): Boolean = isActiveTask() ||
    (!cancelled && systemRead == SystemTaskRead.PRESENT &&
        (taskStatus == TaskStatus.INTERRUPTED || (taskStatus == TaskStatus.FAILED && resumeAvailable())))

internal fun DownloadItem.stoppedReason(): String? = pauseReason?.let {
    when (it) {
        PauseReason.USER -> "用户主动暂停"
        PauseReason.WIFI -> "原任务仅允许 Wi-Fi，正在等待符合条件的网络"
        PauseReason.NETWORK -> "网络不可用或连接中断"
        PauseReason.SYSTEM -> "系统限制或停止了下载"
        PauseReason.RECOVERY -> "应用重启或任务中断后，需要核对检查点"
        PauseReason.STORAGE -> "存储空间不足或缓存无法写入"
        PauseReason.ACCESS -> "网站访问条件失效，不能安全续传"
        PauseReason.SOURCE_CHANGED -> "下载来源已变化，不能安全续传"
    }
} ?: failure?.let {
    when (it) {
        FailureKind.NETWORK -> "网络传输失败"
        FailureKind.HTTP_REJECTED -> "服务器拒绝了下载请求"
        FailureKind.ACCESS_CONDITION -> "网站访问条件失效"
        FailureKind.NOT_VIDEO -> "返回内容不是可保存的视频"
        FailureKind.UNSUPPORTED -> "下载来源或格式不受支持"
        FailureKind.STORAGE -> "存储空间不足或文件无法写入"
        FailureKind.SYSTEM_LIMIT -> "系统限制了下载"
        FailureKind.INTERRUPTED -> "下载执行已中断"
    }
}

internal fun DownloadItem.pauseResumeExplanation(pauseConnected: Boolean, resumeConnected: Boolean): String? = when {
    !canCancelTask() -> null
    taskStatus == TaskStatus.PAUSING -> "正在等待当前写入结束并保存检查点；请等待已暂停，现在不能继续。"
    taskStatus in setOf(TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING) ->
        "下载后的处理阶段不能暂停或续传；成品保存完成前不会进入视频库。"
    pauseReason in setOf(PauseReason.ACCESS, PauseReason.SOURCE_CHANGED) ->
        "不能安全续传。请返回来源网页重新发现资源；重新下载会创建新任务，不是继续此任务。"
    taskStatus in setOf(TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED, TaskStatus.FAILED) -> when {
        resumeAvailable() && resumeConnected ->
            "保留已确认的私有缓存；继续使用同一任务并核对检查点，仍遵守此任务原有的网络限制。取消下载会清理缓存。"
        resumeAvailable() -> "私有缓存已保留，但当前没有可用的继续操作。取消下载会清理缓存。"
        else -> "当前无法安全继续，请等待状态确认或返回来源重新下载。重新下载创建新任务；取消下载会清理缓存。"
    }
    pauseAvailable() && pauseConnected -> "暂停会保留已确认的私有缓存；取消下载会清理缓存，不保留用于续传。"
    else -> null
}

internal fun DownloadItem.cacheSummary(): String = "应用私有缓存 · ${localFileSize(cacheBytes)}（不是已保存的成品）"

/** Paused/waiting tasks stay cancellable, but must not look like an actively running transfer. */
internal fun DownloadItem.showsLiveProgress(): Boolean = isActiveTask() && if (taskStatus != null) {
    taskStatus in setOf(TaskStatus.RUNNING, TaskStatus.MUXING, TaskStatus.VERIFYING, TaskStatus.PUBLISHING)
} else status == DownloadManager.STATUS_RUNNING

/** Missing is a confirmed absent task; UNAVAILABLE and unknown transport integers are not ended. */
internal fun DownloadItem.hasConfirmedEndedTask(): Boolean = systemRead != SystemTaskRead.UNAVAILABLE && (
    cancelled || systemRead == SystemTaskRead.MISSING ||
        (systemRead == SystemTaskRead.PRESENT && if (taskStatus != null) {
            taskStatus in setOf(TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.INTERRUPTED)
        } else status in setOf(
            DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED,
        ))
    )

internal fun DownloadItem.retryAvailable(): Boolean =
    canRetry && hasConfirmedEndedTask() && uiGroup() == DownloadUiGroup.ATTENTION

internal fun DownloadItem.canForgetRecord(): Boolean = hasConfirmedEndedTask()

internal fun DownloadItem.canDeleteSavedFile(): Boolean = hasConfirmedEndedTask() && !cancelled &&
    availability in setOf(FileAvailability.AVAILABLE, FileAvailability.UNREADABLE)

/** HLS fractions describe only completed segments during transfer, never overall/save progress. */
internal fun DownloadItem.progressFraction(): Float? = when {
    taskStatus != null && taskStatus !in setOf(TaskStatus.RUNNING, TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK) -> null
    protocol == DownloadProtocol.HLS -> {
        val count = segmentCount
        if (taskStatus in setOf(TaskStatus.RUNNING, TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK) && isActiveTask() && count != null && count > 0 && completedSegments in 0..count)
            completedSegments.toFloat() / count else null
    }
    total > 0 && bytes in 0..total -> (bytes.toDouble() / total).toFloat()
    else -> null
}

internal fun DownloadItem.segmentSummary(): String {
    val count = segmentCount
    val reliable = count != null && count > 0 && completedSegments in 0..count
    return if (reliable) "已下载分片 $completedSegments / $count（不是整体保存进度）"
    else "分片进度未确认，不推测总分片数"
}

internal fun DownloadItem.progressDescription(): String = when {
    taskStatus == TaskStatus.MUXING -> "正在封装 MP4，尚未保存成品，不显示整体百分比"
    taskStatus in setOf(TaskStatus.PAUSING, TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK) -> "${stateLabel()}，${if (protocol == DownloadProtocol.HLS) segmentSummary() else byteSummary()}"
    taskStatus in setOf(TaskStatus.VERIFYING, TaskStatus.PUBLISHING) -> "${stateLabel()}，不显示整体百分比"
    protocol == DownloadProtocol.HLS && taskStatus == TaskStatus.RUNNING -> segmentSummary()
    protocol == DownloadProtocol.HLS -> "${stateLabel()}，整体进度未知"
    else -> progressFraction()?.let { "已传输 ${(it * 100).toInt()}%" } ?: "传输进度未确认，不显示百分比"
}

internal fun DownloadItem.byteSummary(): String {
    val transferred = if (bytes >= 0) "${localFileSize(bytes)} 已传输" else "已传输大小未知"
    return when {
        // A playlist's Content-Length/declared bitrate must never become the MP4's size or percent.
        protocol == DownloadProtocol.HLS -> "$transferred · 总大小未知"
        total <= 0 -> "$transferred · 总大小未知"
        progressFraction() == null -> "$transferred · 总大小 ${localFileSize(total)} · 进度待确认"
        else -> "$transferred / ${localFileSize(total)} · ${(progressFraction()!! * 100).toInt()}%"
    }
}

/** Shared presentation helpers only; these functions do not read files or mutate repositories. */
fun localFileSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "大小未知"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else String.format(Locale.getDefault(), "%.1f %s", size, units[unit])
}

fun localSavedTime(time: Long?): String = if (time == null || time < 0) "时间未知" else
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

fun localAvailabilityLabel(availability: FileAvailability): String = when (availability) {
    FileAvailability.AVAILABLE -> "文件可读取"
    FileAvailability.MISSING -> "文件已丢失"
    FileAvailability.UNREADABLE -> "文件暂时无法读取"
    FileAvailability.UNKNOWN -> "文件可用性待确认"
}

fun localFormatLabel(format: FormatCheck): String = when (format) {
    FormatCheck.NOT_CHECKED -> "等待格式初检"
    FormatCheck.PASSED -> "格式初检通过"
    FormatCheck.INVALID -> "格式初检失败，可能不是视频文件"
    FormatCheck.UNCONFIRMED -> "无法完成格式初检，不等于文件损坏"
}

/** Diagnostics and captured titles can contain signed addresses; keep them out of ordinary UI. */
fun localSafeLabel(value: String): String = value.replace(
    Regex("(?i)(?:https?://|content://|file://)\\S+"), "[地址已隐藏]",
)

@Composable
fun LocalFileConfirmation(
    title: String,
    displayName: String,
    explanation: String,
    confirmLabel: String,
    tag: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(tag),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(localSafeLabel(displayName), style = MaterialTheme.typography.titleSmall)
                Text(explanation)
                if (!enabled) Text("当前无法执行，请等待状态确认或操作完成后再试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled, modifier = Modifier.testTag("$tag-confirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("$tag-dismiss")) { Text("返回") }
        },
    )
}

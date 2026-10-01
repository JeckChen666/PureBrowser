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
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.SystemTaskRead
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class DownloadUiGroup(val title: String, val tag: String) {
    ACTIVE("进行中", "active"),
    COMPLETED("已完成", "completed"),
    ATTENTION("失败或需处理", "attention"),
}

/** A stale transport integer must never turn an unread system task into an active one. */
internal fun DownloadItem.isActiveTask(): Boolean =
    systemRead == SystemTaskRead.PRESENT && !cancelled && status in setOf(
        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED,
    )

internal fun DownloadItem.uiGroup(): DownloadUiGroup = when {
    isActiveTask() -> DownloadUiGroup.ACTIVE
    systemRead == SystemTaskRead.PRESENT && !cancelled &&
        status == DownloadManager.STATUS_SUCCESSFUL && verified &&
        format == FormatCheck.PASSED && availability == FileAvailability.AVAILABLE -> DownloadUiGroup.COMPLETED
    else -> DownloadUiGroup.ATTENTION
}

internal fun DownloadItem.stateLabel(): String = when {
    cancelled -> "已取消下载"
    systemRead == SystemTaskRead.UNAVAILABLE -> "系统任务暂时无法读取"
    systemRead == SystemTaskRead.MISSING -> "系统任务不存在 · 记录已保留"
    status == DownloadManager.STATUS_PENDING -> "排队中"
    status == DownloadManager.STATUS_RUNNING -> "正在传输"
    status == DownloadManager.STATUS_PAUSED -> "等待网络 / 系统重试"
    status == DownloadManager.STATUS_FAILED -> "传输失败"
    status == DownloadManager.STATUS_SUCCESSFUL -> when (availability) {
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
    else -> "任务状态待确认"
}

/** Missing is a confirmed absent task; UNAVAILABLE and unknown transport integers are not ended. */
internal fun DownloadItem.hasConfirmedEndedTask(): Boolean = systemRead != SystemTaskRead.UNAVAILABLE && (
    cancelled || systemRead == SystemTaskRead.MISSING ||
        (systemRead == SystemTaskRead.PRESENT && status in setOf(
            DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED,
        ))
    )

internal fun DownloadItem.retryAvailable(): Boolean =
    canRetry && hasConfirmedEndedTask() && uiGroup() == DownloadUiGroup.ATTENTION

internal fun DownloadItem.canForgetRecord(): Boolean = hasConfirmedEndedTask()

internal fun DownloadItem.canDeleteSavedFile(): Boolean = hasConfirmedEndedTask() && !cancelled &&
    availability in setOf(FileAvailability.AVAILABLE, FileAvailability.UNREADABLE)

/** No percentage is fabricated for unknown totals or inconsistent byte counters. */
internal fun DownloadItem.progressFraction(): Float? =
    if (total > 0 && bytes in 0..total) (bytes.toDouble() / total).toFloat() else null

internal fun DownloadItem.byteSummary(): String {
    val transferred = if (bytes >= 0) "${localFileSize(bytes)} 已传输" else "已传输大小未知"
    return when {
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

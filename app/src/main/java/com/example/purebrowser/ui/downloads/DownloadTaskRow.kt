package com.example.purebrowser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.SystemTaskRead
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadTaskRow(
    item: DownloadItem,
    busy: Boolean,
    onDetail: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSource: () -> Unit,
    onAction: (DownloadAction) -> Unit,
    allowPublicRetry: Boolean,
    pauseConnected: Boolean,
    resumeConnected: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    quickView: Boolean = false,
    forgetConnected: Boolean = true,
    deleteConnected: Boolean = true,
) {
    val group = item.uiGroup()
    val pauseExplanation = item.pauseResumeExplanation(pauseConnected, resumeConnected)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("download-${item.id}").semantics {
            stateDescription = item.stateLabel() + if (busy) "，正在处理" else ""
        },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localSafeLabel(item.displayName), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Keep the actual task filename discoverable for migrated records and existing UI tests.
            if (item.displayName != item.name) Text(localSafeLabel(item.name), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.stateLabel(), style = MaterialTheme.typography.labelLarge,
                color = if (group == DownloadUiGroup.ATTENTION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("download-status-${item.id}"),
            )
            // T118: failed tasks show mapped guidance (see stoppedReason); the raw diagnostic
            // wording stays available in the task detail's collapsed 技术详情 section.
            val failedWithGuidance = item.taskStatus == TaskStatus.FAILED && item.stoppedReason() != null
            if (item.detail.isNotBlank() && !failedWithGuidance) {
                Text(localSafeLabel(item.detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.systemRead == SystemTaskRead.PRESENT && !item.cancelled) {
                Text(item.byteSummary(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-bytes-${item.id}"))
            }
            if (!quickView && (item.taskStatus != null || item.cacheBytes > 0)) {
                Text(item.cacheSummary(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-cache-${item.id}"))
            }
            item.stoppedReason()?.let { reason ->
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("download-reason-${item.id}"))
            }
            if (!quickView && pauseExplanation != null) {
                Text(pauseExplanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-pause-help-${item.id}"))
            }
            if (item.protocol == DownloadProtocol.HLS && item.taskStatus in setOf(TaskStatus.RUNNING, TaskStatus.PAUSED, TaskStatus.PAUSING, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED, TaskStatus.FAILED)) {
                Text(item.segmentSummary(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-segments-${item.id}"))
            }
            if (item.showsLiveProgress()) {
                val fraction = item.progressFraction()
                val progressModifier = Modifier.fillMaxWidth().testTag("download-progress-${item.id}").semantics {
                    contentDescription = "下载进度，${localSafeLabel(item.displayName)}"
                    stateDescription = item.progressDescription()
                }
                if (fraction == null) LinearProgressIndicator(modifier = progressModifier)
                else LinearProgressIndicator(progress = { fraction }, modifier = progressModifier)
            } else if (group != DownloadUiGroup.ACTIVE) {
                Text("${localFormatLabel(item.format)} · ${localAvailabilityLabel(item.availability)}", style = MaterialTheme.typography.bodySmall)
                if (item.availability == FileAvailability.UNREADABLE || item.format == FormatCheck.UNCONFIRMED) {
                    Text("暂时无法确认不等于文件损坏，记录和可能有价值的文件会保留。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!quickView && group == DownloadUiGroup.ATTENTION) {
                Text(
                    item.recoveryHint(),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.retryAvailable()) Text("重新下载会创建新任务，保留旧记录；不是续传。", style = MaterialTheme.typography.bodySmall)
                if (!item.hasConfirmedEndedTask()) {
                    Text("任务尚未结束或状态未确认，暂不允许移除记录或删除文件，以免遗留正在进行的任务。", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (busy) Text("正在处理…", modifier = Modifier.testTag("download-busy-${item.id}"), style = MaterialTheme.typography.labelMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (pauseConnected && item.pauseAvailable()) {
                    DownloadIconAction(Glyph.PAUSE, "pause", "暂停下载", item, !busy, onPause)
                }
                if (resumeConnected && item.resumeAvailable()) {
                    DownloadIconAction(Glyph.PLAY, "resume", "继续下载", item, !busy, onResume)
                }
                if (group == DownloadUiGroup.COMPLETED) {
                    DownloadIconAction(Glyph.PLAY, "open", "打开文件", item, !busy, onOpen)
                    DownloadIconAction(Glyph.SHARE, "share", "分享文件", item, !busy, onShare)
                }
                if (item.retryAvailable()) {
                    DownloadIconAction(Glyph.REFRESH, "retry", "重新下载，创建新任务", item, !busy) { onAction(DownloadAction.RETRY) }
                }
                if (allowPublicRetry && item.retryAvailable() && item.useAccessContext) {
                    TextButton(
                        onClick = { onAction(DownloadAction.RETRY_PUBLIC) }, enabled = !busy,
                        modifier = Modifier.downloadButtonModifier("public-retry", "不使用网站登录状态重新下载，创建新任务", item),
                    ) { Text("不使用登录状态重试") }
                }
                if (!item.sourceUrl.isNullOrBlank()) {
                    DownloadIconAction(Glyph.GLOBE, "source", "返回来源网页", item, !busy, onSource)
                }
                DownloadIconAction(Glyph.MENU, "details", "任务详情", item, true, onDetail)
                if (item.canCancelTask()) {
                    TextButton(
                        onClick = { onAction(DownloadAction.CANCEL) }, enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.downloadButtonModifier("cancel", "取消下载并清理缓存", item),
                    ) {
                        Text(if (item.taskStatus in setOf(TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED)) "取消下载并清理缓存" else "取消下载")
                    }
                } else {
                    if (forgetConnected) {
                        TextButton(
                            onClick = { onAction(DownloadAction.FORGET) }, enabled = !busy && item.canForgetRecord(),
                            modifier = Modifier.downloadButtonModifier("forget", "移除记录，保留文件", item),
                        ) { Text("移除记录，保留文件") }
                    }
                    if (deleteConnected && item.canDeleteSavedFile()) {
                        TextButton(
                            onClick = { onAction(DownloadAction.DELETE) }, enabled = !busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.downloadButtonModifier("delete", "删除设备文件", item),
                        ) { Text("删除文件") }
                    }
                }
            }
        }
    }
}

/** Material buttons supply role/click/disabled semantics; add the task so repeated labels are clear. */
private fun Modifier.downloadButtonModifier(action: String, label: String, item: DownloadItem): Modifier =
    this.heightIn(min = 48.dp).testTag("download-$action-${item.id}").semantics {
        contentDescription = "$label，${localSafeLabel(item.displayName)}"
    }

/** Explicit measured targets, not only an expanded hit slop. No text-size-dependent glyphs. */
@Composable
private fun DownloadIconAction(
    glyph: Glyph,
    action: String,
    label: String,
    item: DownloadItem,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).downloadButtonModifier(action, label, item),
    ) {
        // The button owns the task-specific label; do not announce a second child label.
        BrowserGlyph(glyph, label, Modifier.clearAndSetSemantics { })
    }
}

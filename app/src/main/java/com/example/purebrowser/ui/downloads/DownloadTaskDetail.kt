package com.example.purebrowser.ui.downloads

import android.app.DownloadManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.SystemTaskRead

@Composable
internal fun DownloadTaskDetail(
    item: DownloadItem,
    onDismiss: () -> Unit,
    pauseConnected: Boolean = false,
    resumeConnected: Boolean = false,
) {
    AlertDialog(
        modifier = Modifier.testTag("download-detail-${item.id}"),
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("下载任务详情", style = MaterialTheme.typography.titleMedium)
                Text(item.stateLabel(), style = MaterialTheme.typography.labelLarge,
                    color = if (item.uiGroup() == DownloadUiGroup.ATTENTION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        text = {
            Column(
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).testTag("download-detail-content-${item.id}"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailField("显示名称", localSafeLabel(item.displayName))
                DetailField("访问条件", if(item.useAccessContext) "使用适用网站会话与最小来源" else "不使用网站会话")
                DetailField("实际文件名", localSafeLabel(item.name))
                DetailField("创建时间", localSavedTime(item.createdAt))
                DetailField(if(item.taskStatus==null) "系统任务读取" else "传输引擎", if(item.taskStatus!=null) "应用受控下载" else when (item.systemRead) {
                    SystemTaskRead.PRESENT -> "已读取系统任务"
                    SystemTaskRead.MISSING -> "系统任务不存在，不代表文件必然已删除"
                    SystemTaskRead.UNAVAILABLE -> "暂时无法读取，不把缓存状态视为当前状态"
                })
                DetailField("传输状态", when {
                    item.taskStatus!=null -> item.stateLabel()
                    item.cancelled -> "已取消"
                    item.systemRead != SystemTaskRead.PRESENT -> "当前传输状态未确认"
                    else -> when (item.status) {
                        DownloadManager.STATUS_PENDING -> "排队中"
                        DownloadManager.STATUS_RUNNING -> "正在传输"
                        DownloadManager.STATUS_PAUSED -> "等待网络 / 系统自动重试；不提供手动暂停或续传"
                        DownloadManager.STATUS_SUCCESSFUL -> "系统传输完成；仍需核对文件与格式"
                        DownloadManager.STATUS_FAILED -> "传输失败"
                        else -> "未知"
                    }
                })
                if (item.protocol == DownloadProtocol.HLS) DetailField("下载协议", "HLS 固定点播 · 分片下载后封装 MP4")
                DetailField("传输大小", item.byteSummary())
                if (item.taskStatus != null || item.cacheBytes > 0) {
                    DetailField("私有缓存", item.cacheSummary())
                    DetailField("缓存与成品", "私有缓存只用于此任务的安全恢复，不是公共目录中的成品，不会进入视频库。暂停保留检查点；取消下载会清理缓存并保留已取消记录。")
                }
                item.stoppedReason()?.let { DetailField("停止原因", it) }
                item.pauseResumeExplanation(pauseConnected, resumeConnected)?.let { DetailField("暂停与继续", it) }
                if (item.protocol == DownloadProtocol.HLS) {
                    DetailField("分片进度", item.segmentSummary())
                    DetailField("成品保存", "分片完成不等于 MP4 已保存；封装、校验和写入公共目录均完成后才进入视频库。总大小未知，不显示整体百分比。")
                }
                if (item.uiGroup() == DownloadUiGroup.ATTENTION) DetailField("下一步", item.recoveryHint())
                DetailField("格式初检", localFormatLabel(item.format))
                DetailField("文件可用性", localAvailabilityLabel(item.availability))
                DetailField("状态说明", localSafeLabel(item.detail).ifBlank { "暂无补充说明" })
                DetailField("此任务提交时的网络选项", when (item.wifiOnly) {
                    true -> "仅 Wi-Fi；这是此任务提交时保存的选项，不随当前默认设置变化"
                    false -> "允许移动网络；可能产生流量费用"
                    null -> "未记录，不能推测旧任务的网络选项"
                })
                DetailField("提交时的来源网页", when {
                    item.sourceUrl.isNullOrBlank() -> "来源信息缺失，请自行重新找到网页"
                    item.sourceTitle.isNullOrBlank() -> "已保存来源网页，但未记录标题；可返回来源重新发现资源"
                    else -> localSafeLabel(item.sourceTitle) + "\n可返回来源网页重新发现资源"
                })
                DetailField("重新下载关联", item.retryOf?.takeIf { it.isNotBlank() }?.let {
                    "此任务由旧记录 $it 重新下载创建；不是续传，旧记录保留"
                } ?: "未记录关联的旧任务")
                DetailField("重新下载规则", if (item.canRetry) {
                    if (item.protocol == DownloadProtocol.HLS) {
                        "仅在需处理状态可操作。另建任务并重新解析所选档位，不续传，不删除旧记录或旧文件。档位仍存在时无需重新选择；档位消失或不再支持会明确失败，请返回来源网页，不会自动改选其他画质。"
                    } else "仅在需处理状态可操作。创建新下载任务，不续传，不删除旧记录或旧文件。链接仍可能过期或要求登录。"
                } else {
                    "当前没有可用的重新下载操作，不会伪造媒体地址。请从来源网页重新发现资源。"
                })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭详情") } },
    )
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(Modifier.padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

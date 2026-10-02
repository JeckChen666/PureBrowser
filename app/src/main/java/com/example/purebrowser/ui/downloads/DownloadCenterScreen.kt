package com.example.purebrowser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.TaskId
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.SystemTaskRead
import com.example.purebrowser.ui.components.EmptyContent
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.ui.components.ToolButton

private enum class DownloadAction { CANCEL, FORGET, DELETE, RETRY, RETRY_PUBLIC }

/**
 * Callback-only UI: polling, file revalidation and mutation belong to the caller.
 * Null pause/resume callbacks leave those controls unavailable; capabilities never create fake actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    items: List<DownloadItem>,
    busyIds: Set<String>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onForget: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSource: (String) -> Unit,
    onLibrary: () -> Unit,
    onRetryWithoutContext: ((String) -> Unit)? = null,
    onPause: ((TaskId) -> Unit)? = null,
    onResume: ((TaskId) -> Unit)? = null,
) {
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionName by rememberSaveable { mutableStateOf<String?>(null) }
    val detailItem = items.firstOrNull { it.id == detailId }
    val actionItem = items.firstOrNull { it.id == actionId }
    val action = actionName?.let { name -> DownloadAction.entries.firstOrNull { it.name == name } }
    val ordered = items.sortedWith(compareByDescending<DownloadItem> { it.createdAt ?: Long.MIN_VALUE }.thenByDescending { it.id })
    LaunchedEffect(detailId, actionId, items.map { it.id }) {
        if (detailId != null && detailItem == null) detailId = null
        if (actionId != null && actionItem == null) { actionId = null; actionName = null }
    }
    fun request(item: DownloadItem, next: DownloadAction) {
        if (item.id !in busyIds) { actionId = item.id; actionName = next.name }
    }
    fun dismissAction() { actionId = null; actionName = null }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("downloadsScreen"),
        topBar = {
            TopAppBar(
                title = { Text("下载管理") },
                navigationIcon = { ToolButton(Glyph.BACK, "返回", tag = "downloadsBack", action = onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("downloadsList"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("查看本应用记录的下载。系统传输完成后，还会核对格式和文件可读性。", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onLibrary, modifier = Modifier.fillMaxWidth().testTag("downloadsLibrary")) {
                            Text("打开本地视频库")
                        }
                    }
                }
                if (items.isEmpty()) {
                    item(key = "empty") { EmptyContent("还没有下载任务", "在网页资源中心选择可保存的视频后，任务会出现在这里。不会扫描其他应用的下载。") }
                }
                DownloadUiGroup.entries.forEach { group ->
                    val grouped = ordered.filter { it.uiGroup() == group }
                    if (grouped.isNotEmpty()) {
                        item(key = "group-${group.tag}") {
                            Text(
                                "${group.title} · ${grouped.size}", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp).testTag("download-group-${group.tag}").semantics { heading() },
                            )
                        }
                        items(grouped, key = { "download-${it.id}" }) { item ->
                            DownloadTaskCard(
                                item = item, busy = item.id in busyIds,
                                onDetail = { detailId = item.id },
                                onOpen = { if (item.id !in busyIds && item.uiGroup() == DownloadUiGroup.COMPLETED) onOpen(item.id) },
                                onShare = { if (item.id !in busyIds && item.uiGroup() == DownloadUiGroup.COMPLETED) onShare(item.id) },
                                onSource = { if (item.id !in busyIds && !item.sourceUrl.isNullOrBlank()) onSource(item.id) },
                                onAction = { request(item, it) },
                                allowPublicRetry = onRetryWithoutContext != null,
                                pauseConnected = onPause != null,
                                resumeConnected = onResume != null,
                                onPause = { if (item.id !in busyIds && item.pauseAvailable()) onPause?.invoke(item.id) },
                                onResume = { if (item.id !in busyIds && item.resumeAvailable()) onResume?.invoke(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (detailItem != null) DownloadTaskDetail(
        detailItem, onDismiss = { detailId = null },
        pauseConnected = onPause != null, resumeConnected = onResume != null,
    )
    if (actionItem != null && action != null) {
        val permitted = actionItem.id !in busyIds && when (action) {
            DownloadAction.CANCEL -> actionItem.canCancelTask()
            DownloadAction.FORGET -> actionItem.canForgetRecord()
            DownloadAction.DELETE -> actionItem.canDeleteSavedFile()
            DownloadAction.RETRY, DownloadAction.RETRY_PUBLIC -> actionItem.retryAvailable()
        }
        LocalFileConfirmation(
            title = when (action) {
                DownloadAction.CANCEL -> "取消下载？"
                DownloadAction.FORGET -> "移除记录，保留文件？"
                DownloadAction.DELETE -> "删除设备上的文件？"
                DownloadAction.RETRY, DownloadAction.RETRY_PUBLIC -> "创建新的下载任务？"
            },
            displayName = actionItem.displayName,
            explanation = when (action) {
                DownloadAction.CANCEL -> "取消此下载任务，并清理它的临时文件；已传输的数据不会保留用于续传。保留已取消记录，之后可单独移除。不会取消其他任务。"
                DownloadAction.FORGET -> "只从本应用的下载管理和视频库移除这条记录。不会删除设备文件，也不会取消系统任务；如文件仍存在，可在系统文件管理器中查找。本应用不会自动重新导入它。"
                DownloadAction.DELETE -> "这会实际删除设备上此任务保存的文件，不只是隐藏列表记录。删除成功后才移除本应用的相关记录；删除失败或未获授权会保留记录。此操作无法撤销。"
                DownloadAction.RETRY, DownloadAction.RETRY_PUBLIC -> "重新下载会创建新的受控任务，不是暂停后续传。旧任务记录和已有文件会保留，并关联到新记录。原链接可能过期或需要登录；不会补算签名；适用会话会重新读取，不沿用保存的凭据，建议先返回来源网页重新发现资源。" +
                    if (actionItem.protocol == DownloadProtocol.HLS) " HLS 会重新解析原来所选档位；档位消失或不再支持时会明确失败，请返回来源重新选择，不会自动更换画质。" else ""
            },
            confirmLabel = when (action) {
                DownloadAction.CANCEL -> "取消下载并清理临时文件"
                DownloadAction.FORGET -> "仅移除记录"
                DownloadAction.DELETE -> "删除文件"
                DownloadAction.RETRY, DownloadAction.RETRY_PUBLIC -> "创建新任务"
            },
            tag = "download-${action.name.lowercase()}-dialog-${actionItem.id}",
            enabled = permitted,
            onDismiss = ::dismissAction,
            onConfirm = {
                // Clearing dialog state before dispatch also guards queued double taps on its button.
                if (permitted && actionId == actionItem.id && actionName == action.name) {
                    dismissAction()
                    when (action) {
                        DownloadAction.CANCEL -> onCancel(actionItem.id)
                        DownloadAction.FORGET -> onForget(actionItem.id)
                        DownloadAction.DELETE -> onDelete(actionItem.id)
                        DownloadAction.RETRY -> onRetry(actionItem.id)
                        DownloadAction.RETRY_PUBLIC -> onRetryWithoutContext?.invoke(actionItem.id)
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadTaskCard(
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
) {
    val group = item.uiGroup()
    val pauseExplanation = item.pauseResumeExplanation(pauseConnected, resumeConnected)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("download-${item.id}").semantics {
            stateDescription = item.stateLabel() + if (busy) "，正在处理" else ""
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localSafeLabel(item.displayName), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            // Keep the actual task filename discoverable for migrated records and existing UI tests.
            if (item.displayName != item.name) Text(localSafeLabel(item.name), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.stateLabel(), style = MaterialTheme.typography.labelLarge,
                color = if (group == DownloadUiGroup.ATTENTION) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("download-status-${item.id}"),
            )
            Text(localSafeLabel(item.detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.systemRead == SystemTaskRead.PRESENT && !item.cancelled) {
                Text(item.byteSummary(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-bytes-${item.id}"))
            }
            if (item.taskStatus != null || item.cacheBytes > 0) {
                Text(item.cacheSummary(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("download-cache-${item.id}"))
            }
            item.stoppedReason()?.let { reason ->
                Text("停止原因 · $reason", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("download-reason-${item.id}"))
            }
            if (pauseExplanation != null) {
                Text(pauseExplanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("download-pause-help-${item.id}"))
            }
            if (item.protocol == DownloadProtocol.HLS && item.taskStatus in setOf(TaskStatus.RUNNING,TaskStatus.PAUSED,TaskStatus.PAUSING,TaskStatus.WAITING_WIFI,TaskStatus.WAITING_NETWORK,TaskStatus.INTERRUPTED,TaskStatus.FAILED)) {
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
            if (group == DownloadUiGroup.ATTENTION) {
                Text(
                    if (!item.sourceUrl.isNullOrBlank()) "链接可能已过期或需要登录，可返回来源网页重新发现资源。"
                    else "来源信息缺失，请自行重新找到网页；不会自动重新下载。",
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (pauseConnected && item.pauseAvailable()) {
                    TextButton(onClick = onPause, enabled = !busy, modifier = Modifier.downloadButtonModifier("pause", "暂停下载", item)) { Text("暂停下载") }
                }
                if (resumeConnected && item.resumeAvailable()) {
                    TextButton(onClick = onResume, enabled = !busy, modifier = Modifier.downloadButtonModifier("resume", "继续下载", item)) { Text("继续下载") }
                }
                if (group == DownloadUiGroup.COMPLETED) {
                    TextButton(onClick = onOpen, enabled = !busy, modifier = Modifier.downloadButtonModifier("open", "打开文件", item)) { Text("打开文件") }
                    TextButton(onClick = onShare, enabled = !busy, modifier = Modifier.downloadButtonModifier("share", "分享文件", item)) { Text("分享文件") }
                }
                if (item.retryAvailable()) {
                    TextButton(onClick = { onAction(DownloadAction.RETRY) }, enabled = !busy, modifier = Modifier.downloadButtonModifier("retry", "重新下载，创建新任务", item)) { Text("重新下载") }
                }
                if (allowPublicRetry && item.retryAvailable() && item.useAccessContext) {
                    TextButton(onClick={onAction(DownloadAction.RETRY_PUBLIC)},enabled=!busy,modifier=Modifier.downloadButtonModifier("public-retry", "不使用网站条件重新下载，创建新任务", item)) { Text("不使用网站条件重试") }
                }
                if (!item.sourceUrl.isNullOrBlank()) {
                    TextButton(onClick = onSource, enabled = !busy, modifier = Modifier.downloadButtonModifier("source", "返回来源网页", item)) { Text("返回来源网页") }
                }
                TextButton(onClick = onDetail, modifier = Modifier.downloadButtonModifier("details", "任务详情", item)) { Text("任务详情") }
                if (item.canCancelTask()) {
                    TextButton(onClick = { onAction(DownloadAction.CANCEL) }, enabled = !busy, modifier = Modifier.downloadButtonModifier("cancel", "取消下载并清理缓存", item)) { Text(if (item.taskStatus in setOf(TaskStatus.PAUSED, TaskStatus.WAITING_WIFI, TaskStatus.WAITING_NETWORK, TaskStatus.INTERRUPTED)) "取消下载并清理缓存" else "取消下载") }
                } else {
                    TextButton(onClick = { onAction(DownloadAction.FORGET) }, enabled = !busy && item.canForgetRecord(), modifier = Modifier.downloadButtonModifier("forget", "移除记录，保留文件", item)) { Text("移除记录，保留文件") }
                    if (item.canDeleteSavedFile()) {
                        TextButton(onClick = { onAction(DownloadAction.DELETE) }, enabled = !busy, modifier = Modifier.downloadButtonModifier("delete", "删除设备文件", item)) { Text("删除文件") }
                    }
                }
            }
        }
    }
}

/** Material buttons supply role/click/disabled semantics; add the task so repeated labels are clear. */
private fun Modifier.downloadButtonModifier(action: String, label: String, item: DownloadItem): Modifier =
    this.heightIn(min=48.dp).testTag("download-$action-${item.id}").semantics {
        contentDescription = "$label，${localSafeLabel(item.displayName)}"
    }

package com.example.purebrowser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.TaskId
import com.example.purebrowser.ui.components.EmptyContent
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.ui.components.ToolButton

/**
 * Browsing-only view of the caller's existing download snapshot. Never polls, reads files, or
 * creates a coordinator. The parent owns visibility and should remove this composable on dismiss.
 * Navigation calls dismiss first. Optional record actions are absent unless wired. Without an
 * external onDetail, details are shown locally, replacing (not covering) the sheet, as are confirms.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadQuickSheet(
    items: List<DownloadItem>,
    busyIds: Set<String>,
    onDismiss: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenFile: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSource: (String) -> Unit,
    onRetryWithoutContext: ((String) -> Unit)? = null,
    onPause: ((TaskId) -> Unit)? = null,
    onResume: ((TaskId) -> Unit)? = null,
    onForget: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onDetail: ((String) -> Unit)? = null,
) {
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionName by rememberSaveable { mutableStateOf<String?>(null) }
    val detailItem = items.firstOrNull { it.id == detailId }
    val actionItem = items.firstOrNull { it.id == actionId }
    val action = actionName?.let { name -> DownloadAction.entries.firstOrNull { it.name == name } }
    // Keep the state outside the conditional surfaces so returning from detail retains the sheet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val ordered = items.sortedWith(compareByDescending<DownloadItem> { it.createdAt ?: Long.MIN_VALUE }.thenByDescending { it.id })
    LaunchedEffect(detailId, actionId, items.map { it.id }) {
        if (detailId != null && detailItem == null) detailId = null
        if (actionId != null && actionItem == null) { actionId = null; actionName = null }
    }
    fun dismissAction() { actionId = null; actionName = null }
    fun navigate(callback: () -> Unit) { onDismiss(); callback() }

    if (detailItem != null) {
        DownloadTaskDetail(
            detailItem, onDismiss = { detailId = null },
            pauseConnected = onPause != null, resumeConnected = onResume != null,
        )
    } else if (actionItem != null && action != null) {
        DownloadActionConfirmation(
            item = actionItem, action = action, busy = actionItem.id in busyIds,
            connected = when (action) {
                DownloadAction.FORGET -> onForget != null
                DownloadAction.DELETE -> onDelete != null
                DownloadAction.RETRY_PUBLIC -> onRetryWithoutContext != null
                else -> true
            },
            onDismiss = ::dismissAction,
            onConfirm = {
                if (actionId == actionItem.id && actionName == action.name) {
                    dismissAction()
                    when (action) {
                        DownloadAction.CANCEL -> onCancel(actionItem.id)
                        DownloadAction.RETRY -> onRetry(actionItem.id)
                        DownloadAction.RETRY_PUBLIC -> onRetryWithoutContext?.invoke(actionItem.id)
                        DownloadAction.FORGET -> onForget?.invoke(actionItem.id)
                        DownloadAction.DELETE -> onDelete?.invoke(actionItem.id)
                    }
                }
            },
        )
    } else {
        ModalBottomSheet(
            modifier = Modifier.testTag("downloadQuickSheet"),
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            // Header and navigation scroll too: no fixed footer can obscure rows in short windows
            // or at large font scales. ModalBottomSheet supplies system-bar/IME inset handling.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("downloadQuickList"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "quick-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "下载速览 · ${items.size}", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        ToolButton(Glyph.CLOSE, "关闭下载速览", tag = "downloadQuickDismiss", action = onDismiss)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { navigate(onOpenAll) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("downloadQuickOpenAll"),
                        ) { Text("全部任务") }
                        TextButton(
                            onClick = { navigate(onOpenLibrary) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("downloadQuickLibrary"),
                        ) { Text("本地视频库") }
                    }
                }
                if (items.isEmpty()) {
                    item(key = "quick-empty") {
                        EmptyContent("还没有下载任务", "在资源中心确认保存后，任务会出现在这里；不会自动下载。")
                    }
                }
                DownloadUiGroup.entries.forEach { group ->
                    val grouped = ordered.filter { it.uiGroup() == group }
                    if (grouped.isNotEmpty()) {
                        item(key = "quick-group-${group.tag}") {
                            Text(
                                "${group.title} · ${grouped.size}", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp).testTag("download-group-${group.tag}").semantics { heading() },
                            )
                        }
                        items(grouped, key = { "quick-${it.id}" }) { item ->
                            DownloadTaskRow(
                                item = item, busy = item.id in busyIds, quickView = true,
                                onDetail = {
                                    if (onDetail != null) navigate { onDetail(item.id) }
                                    else { dismissAction(); detailId = item.id }
                                },
                                onOpen = { if (item.id !in busyIds && item.uiGroup() == DownloadUiGroup.COMPLETED) onOpenFile(item.id) },
                                onShare = { if (item.id !in busyIds && item.uiGroup() == DownloadUiGroup.COMPLETED) onShare(item.id) },
                                onSource = { if (item.id !in busyIds && !item.sourceUrl.isNullOrBlank()) navigate { onSource(item.id) } },
                                onAction = { next ->
                                    if (item.id !in busyIds) { detailId = null; actionId = item.id; actionName = next.name }
                                },
                                allowPublicRetry = onRetryWithoutContext != null,
                                pauseConnected = onPause != null, resumeConnected = onResume != null,
                                forgetConnected = onForget != null, deleteConnected = onDelete != null,
                                onPause = { if (item.id !in busyIds && item.pauseAvailable()) onPause?.invoke(item.id) },
                                onResume = { if (item.id !in busyIds && item.resumeAvailable()) onResume?.invoke(item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.example.purebrowser.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
        if (item.id !in busyIds) { detailId = null; actionId = item.id; actionName = next.name }
    }
    fun dismissAction() { actionId = null; actionName = null }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("downloadsScreen"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("下载管理", style = MaterialTheme.typography.titleMedium) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                navigationIcon = { ToolButton(Glyph.BACK, "返回", tag = "downloadsBack", action = onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("downloadsList"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("这里只显示本应用创建的下载任务，不查看其他应用的下载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onLibrary, modifier = Modifier.heightIn(min = 48.dp).testTag("downloadsLibrary")) {
                            Text("打开本地视频库")
                        }
                    }
                }
                if (items.isEmpty()) {
                    item(key = "empty") { EmptyContent("还没有下载任务", "在网页里找到想保存的视频并确认保存后，任务会出现在这里。") }
                }
                DownloadUiGroup.entries.forEach { group ->
                    val grouped = ordered.filter { it.uiGroup() == group }
                    if (grouped.isNotEmpty()) {
                        item(key = "group-${group.tag}") {
                            Text(
                                "${group.title} · ${grouped.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp).testTag("download-group-${group.tag}").semantics { heading() },
                            )
                        }
                        items(grouped, key = { "download-${it.id}" }) { item ->
                            DownloadTaskRow(
                                item = item, busy = item.id in busyIds,
                                onDetail = { dismissAction(); detailId = item.id },
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
        DownloadActionConfirmation(
            item = actionItem, action = action, busy = actionItem.id in busyIds,
            connected = action != DownloadAction.RETRY_PUBLIC || onRetryWithoutContext != null,
            onDismiss = ::dismissAction,
            onConfirm = {
                // Clearing state before dispatch also guards queued double taps.
                if (actionId == actionItem.id && actionName == action.name) {
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

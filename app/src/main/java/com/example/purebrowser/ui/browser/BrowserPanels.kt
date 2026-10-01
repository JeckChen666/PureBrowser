package com.example.purebrowser.ui.browser

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import kotlinx.coroutines.delay
import java.util.Locale


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPanels(
    model: BrowserViewModel,
    candidates: List<MediaCandidate>,
    downloads: List<DownloadItem>,
    showResources: Boolean,
    showDownloads: Boolean,
    onDismissResources: () -> Unit,
    onDismissDownloads: () -> Unit,
) {
    val context = LocalContext.current
    var confirmDownload by remember { mutableStateOf<DownloadDraft?>(null) }
    var confirmRemoval by remember { mutableStateOf<DownloadItem?>(null) }
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var pendingPermission by remember { mutableStateOf<DownloadDraft?>(null) }
    val userAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val candidate = pendingPermission
        pendingPermission = null
        if (granted && candidate != null) model.download(candidate, wifiOnly)
        else if (!granted) model.notify("未取得旧版 Android 的下载目录写入权限")
    }
    if(showResources) ResourceSheet(candidates,onDismissResources) { item -> onDismissResources();confirmDownload=model.downloadDraft(item,userAgent) }
    if(showDownloads) DownloadSheet(downloads,onDismissDownloads,{item->confirmRemoval=item}) { item ->
        runCatching {
            val uri=model.repository.fileUri(item.id)?:error("文件不存在")
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,model.repository.mimeType(item.id)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure {model.notify("无法打开文件，请确认系统安装了视频播放器")}
    }
    confirmDownload?.let { draft ->
        val candidate = draft.candidate
        AlertDialog(
            onDismissRequest = { confirmDownload = null }, title = { Text("确认下载直链") },
            text = {
                Column {
                    Text("仅下载你拥有或获授权保存的内容。当前不会传递 Cookie / Authorization；需要登录、Referer 或特殊鉴权的资源可能失败。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it }); Text("仅在 Wi-Fi 下下载")
                    }
                    Text("${candidate.host} · ${candidate.displayName}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = {
                confirmDownload = null
                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingPermission = draft
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else model.download(draft, wifiOnly)
            }) { Text("开始下载") } },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("取消") } },
        )
    }
    confirmRemoval?.let { item ->
        AlertDialog(onDismissRequest = { confirmRemoval = null }, title = { Text("删除下载任务？") },
            text = { Text("将取消传输，并删除此任务对应的已下载文件。此操作不可撤销。") },
            confirmButton = { TextButton(onClick = { confirmRemoval = null; model.removeDownload(item.id) }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { confirmRemoval = null }) { Text("保留") } })
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(value: Long): String = when {
    value < 0 -> "未知"
    value >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", value / (1024.0 * 1024))
    value >= 1024 -> String.format(Locale.ROOT, "%.1f KB", value / 1024.0)
    else -> "$value B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourceSheet(candidates:List<MediaCandidate>,dismiss:()->Unit,select:(MediaCandidate)->Unit) {

        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { dismiss() }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
                Text("视频资源 · ${candidates.size}", style = MaterialTheme.typography.titleLarge)
                Text("这里是候选线索，不保证可下载。签名参数完整保留，但不在列表展示。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                if (candidates.isEmpty()) {
                    EmptyState("尚未发现视频", "请先播放页面上的视频。跨域 iframe、无后缀请求和受保护播放器可能暂时无法识别。")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(candidates.sortedByDescending { com.example.purebrowser.media.Evidence.DOM in it.sources }, key = { it.url }) { item ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${item.kind.label} · ${item.host}", color = MaterialTheme.colorScheme.primary)
                                    Text(item.sources.joinToString(" / ") { it.label }, style = MaterialTheme.typography.bodySmall)
                                    item.sizeBytes?.let { Text("响应大小：${formatBytes(it)}", style = MaterialTheme.typography.bodySmall) }
                                    val note = when (item.kind) {
                                        MediaKind.FILE -> "可尝试公开直链下载；登录态和格式仍需验证"
                                        MediaKind.HLS -> "已发现 HLS；分片下载与合并尚未实现"
                                        MediaKind.DASH -> "已发现 DASH；音视频分离下载尚未实现"
                                        MediaKind.LOCAL -> "blob 不是直链，需要关联底层网络资源"
                                        MediaKind.UNKNOWN -> "已发现视频元素；媒体类型尚未确认"
                                    }
                                    Text(note, style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = { select(item) }, enabled = item.kind == MediaKind.FILE) { Text(if (item.kind == MediaKind.FILE) "下载直链" else "暂不支持下载") }
                                }
                            }
                        }
                    }
                }
            }
        }
    
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(downloads:List<DownloadItem>,dismiss:()->Unit,remove:(DownloadItem)->Unit,open:(DownloadItem)->Unit) {

        ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = { dismiss() }) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
                Text("下载中心", style = MaterialTheme.typography.titleLarge)
                Text("当前使用系统下载服务。文件保存至系统 Download 目录；暂不支持手动暂停。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                if (downloads.isEmpty()) EmptyState("暂无下载任务", "在资源面板中选择公开视频直链后，任务会出现在这里。")
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(downloads, key = { it.id }) { item ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.detail, style = MaterialTheme.typography.bodySmall)
                                if (item.total > 0 && item.status in setOf(DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED, DownloadManager.STATUS_PENDING)) {
                                    LinearProgressIndicator(progress = { (item.bytes.toFloat() / item.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                }
                                Text("${formatBytes(item.bytes)} / ${if (item.total > 0) formatBytes(item.total) else "大小未知"}", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    if (item.verified) TextButton(onClick = { open(item) }) { Text("打开文件") }
                                    TextButton(onClick = { remove(item) }) { Text(if (item.status == DownloadManager.STATUS_SUCCESSFUL) "删除" else "取消 / 删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    
}

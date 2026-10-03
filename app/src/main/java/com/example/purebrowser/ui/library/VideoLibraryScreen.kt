package com.example.purebrowser.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.EmptyContent
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.ui.components.ToolButton
import com.example.purebrowser.ui.downloads.LocalFileConfirmation
import com.example.purebrowser.ui.downloads.localAvailabilityLabel
import com.example.purebrowser.ui.downloads.localFileSize
import com.example.purebrowser.ui.downloads.localFormatLabel
import com.example.purebrowser.ui.downloads.localSafeLabel
import com.example.purebrowser.ui.downloads.localSavedTime
import java.util.Locale

private enum class LibraryAction { RENAME, FORGET, DELETE }

/**
 * Presents the caller's app-owned index, including retained missing/unreadable entries.
 * No directory scanning, metadata IO, URI opening or physical renaming happens in this UI.
 * Source actions require caller-provided evidence because VideoAsset itself has no sourceUrl.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoLibraryScreen(
    assets: List<VideoAsset>,
    busyIds: Set<String>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onForget: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSource: (String) -> Unit,
    onDownloads: () -> Unit,
    thumbnail: @Composable (VideoAsset) -> Unit = {},
    sourceAvailableIds: Set<String> = emptySet(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var actionId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionName by rememberSaveable { mutableStateOf<String?>(null) }
    val actionAsset = assets.firstOrNull { it.recordId == actionId }
    val action = actionName?.let { name -> LibraryAction.entries.firstOrNull { it.name == name } }
    val search = query.trim()
    // Do not filter on availability: a lost or temporarily unreadable file must remain manageable.
    val matches = assets.filter {
        it.displayName.contains(search, ignoreCase = true) || it.name.contains(search, ignoreCase = true)
    }
    val ascending = compareBy<VideoAsset> { it.indexedAt }.thenBy { it.recordId }
    val visible = matches.sortedWith(if (newestFirst) ascending.reversed() else ascending)
    LaunchedEffect(actionId, assets.map { it.recordId }) {
        if (actionId != null && actionAsset == null) { actionId = null; actionName = null }
    }
    fun request(asset: VideoAsset, next: LibraryAction) {
        if (asset.recordId !in busyIds) { actionId = asset.recordId; actionName = next.name }
    }
    fun dismissAction() { actionId = null; actionName = null }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("videoLibraryScreen"),
        topBar = {
            TopAppBar(
                title = { Text("本地视频库") },
                navigationIcon = { ToolButton(Glyph.BACK, "返回", tag = "videoLibraryBack", action = onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("videoLibraryList"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "library-controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("只管理本应用记录的成品，不扫描手机视频。打开和分享交给系统应用，不会自动上传。", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onDownloads, modifier = Modifier.fillMaxWidth().testTag("videoLibraryDownloads")) {
                            Text("打开下载管理")
                        }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("搜索视频名称") },
                            placeholder = { Text("显示名称或实际文件名") },
                            leadingIcon = { BrowserGlyph(Glyph.SEARCH, "搜索视频") },
                            trailingIcon = {
                                if (query.isNotEmpty()) ToolButton(Glyph.CLOSE, "清空搜索", tag = "videoLibraryClearSearch") { query = "" }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("videoLibrarySearch"),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = newestFirst, onClick = { newestFirst = true },
                                label = { Text("最新入库") }, modifier = Modifier.testTag("videoLibraryNewest"),
                            )
                            FilterChip(
                                selected = !newestFirst, onClick = { newestFirst = false },
                                label = { Text("最早入库") }, modifier = Modifier.testTag("videoLibraryOldest"),
                            )
                        }
                        Text(
                            "${visible.size} / ${assets.size} 个条目 · 按入库时间排序",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("videoLibraryCount"),
                        )
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "library-empty") {
                        EmptyContent(
                            if (search.isEmpty()) "还没有本地视频" else "没有找到匹配视频",
                            if (search.isEmpty()) "通过基础格式检查的下载会出现在这里。未通过或待确认的任务请到下载管理查看。"
                            else "试试其他显示名称或文件名。搜索不会删除或隐藏原记录。",
                        )
                    }
                }
                items(visible, key = { "video-${it.recordId}" }) { asset ->
                    VideoAssetCard(
                        asset = asset, busy = asset.recordId in busyIds, thumbnail = thumbnail,
                        sourceAvailable = asset.recordId in sourceAvailableIds,
                        onSource = { if (asset.recordId !in busyIds && asset.recordId in sourceAvailableIds) onSource(asset.recordId) },
                        onOpen = { if (asset.recordId !in busyIds && asset.isUsableVideo()) onOpen(asset.recordId) },
                        onShare = { if (asset.recordId !in busyIds && asset.isUsableVideo()) onShare(asset.recordId) },
                        onAction = { request(asset, it) },
                    )
                }
            }
        }
    }
    if (actionAsset != null && action != null) {
        val enabled = actionAsset.recordId !in busyIds
        if (action == LibraryAction.RENAME) {
            RenameVideoTitleDialog(
                asset = actionAsset, enabled = enabled, onDismiss = ::dismissAction,
                onRename = { title ->
                    if (enabled && actionId == actionAsset.recordId && actionName == action.name) {
                        dismissAction()
                        onRename(actionAsset.recordId, title)
                    }
                },
            )
        } else {
            val forget = action == LibraryAction.FORGET
            LocalFileConfirmation(
                title = if (forget) "移除记录，保留文件？" else "删除设备上的视频文件？",
                displayName = actionAsset.displayName,
                explanation = if (forget) {
                    "只从本应用的视频库和下载管理移除相关记录，不删除设备上的文件，也不会自动重新导入。如果文件仍在，可用系统文件管理器查找；如果文件已丢失，这只清理保留的记录。"
                } else {
                    "将实际删除设备上的这个视频文件，不是仅从列表隐藏。此操作无法撤销，其他应用也将无法再打开此文件。只有删除成功后才移除本应用记录；删除失败或未获得授权会保留记录。"
                },
                confirmLabel = if (forget) "仅移除记录" else "删除文件",
                tag = "video-${action.name.lowercase()}-dialog-${actionAsset.recordId}",
                enabled = enabled && (forget || actionAsset.canDeleteVideoFile()),
                onDismiss = ::dismissAction,
                onConfirm = {
                    if (enabled && (forget || actionAsset.canDeleteVideoFile()) &&
                        actionId == actionAsset.recordId && actionName == action.name) {
                        dismissAction()
                        if (forget) onForget(actionAsset.recordId) else onDelete(actionAsset.recordId)
                    }
                },
            )
        }
    }
}

private fun VideoAsset.isUsableVideo(): Boolean =
    format == FormatCheck.PASSED && availability == FileAvailability.AVAILABLE

private fun VideoAsset.canDeleteVideoFile(): Boolean =
    availability in setOf(FileAvailability.AVAILABLE, FileAvailability.UNREADABLE)

private fun durationLabel(durationMillis: Long?): String {
    if (durationMillis == null || durationMillis < 0) return "时长未获取或不支持"
    val seconds = durationMillis / 1000
    return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoAssetCard(
    asset: VideoAsset,
    busy: Boolean,
    thumbnail: @Composable (VideoAsset) -> Unit,
    sourceAvailable: Boolean,
    onSource: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    val usable = asset.isUsableVideo()
    val status = when {
        asset.availability != FileAvailability.AVAILABLE -> localAvailabilityLabel(asset.availability)
        asset.format != FormatCheck.PASSED -> localFormatLabel(asset.format)
        else -> "可用 · 格式初检通过"
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("video-${asset.recordId}").semantics {
            stateDescription = status + if (busy) "，正在处理" else ""
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(width = 80.dp, height = 72.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .testTag("video-thumbnail-${asset.recordId}")
                        .semantics { contentDescription = if (usable) "本地视频封面" else "视频不可打开：$status" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (usable) "暂无封面" else "不可打开", modifier = Modifier.padding(4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Never render a stale/play-looking thumbnail for missing or unchecked media.
                    if (usable) thumbnail(asset)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localSafeLabel(asset.displayName), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        status, style = MaterialTheme.typography.labelMedium,
                        color = if (usable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("video-status-${asset.recordId}"),
                    )
                }
            }
            if (asset.displayName != asset.name) {
                Text("实际文件名：${localSafeLabel(asset.name)}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            // The index timestamp is not fabricated into a legacy file's original save time.
            Text("入库时间：${localSavedTime(asset.indexedAt)}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (asset.systemUpdatedAt != null) "系统保存/更新时间：${localSavedTime(asset.systemUpdatedAt)}" else "原保存时间未记录",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${localFileSize(asset.sizeBytes)} · ${durationLabel(asset.durationMillis)}", style = MaterialTheme.typography.bodySmall)
            Text("格式：${localSafeLabel(asset.mimeType ?: "未获取或不支持")}", style = MaterialTheme.typography.bodySmall)
            if (!usable) {
                Text(
                    when (asset.availability) {
                        FileAvailability.MISSING -> "设备文件已丢失，保留记录供整理，不提供打开或分享。"
                        FileAvailability.UNREADABLE -> "可能是临时读取或权限问题，不等于文件已删除。记录会保留，暂不提供打开或分享。"
                        FileAvailability.UNKNOWN -> "文件可用性尚未确认，暂不提供打开或分享；请到下载管理查看详情。"
                        FileAvailability.AVAILABLE -> "文件未通过格式初检，暂不提供打开或分享；请到下载管理查看详情。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (busy) Text("正在处理…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("video-busy-${asset.recordId}"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (usable) {
                    TextButton(onClick = onOpen, enabled = !busy, modifier = Modifier.testTag("video-open-${asset.recordId}")) { Text("打开视频") }
                    TextButton(onClick = onShare, enabled = !busy, modifier = Modifier.testTag("video-share-${asset.recordId}")) { Text("分享文件") }
                }
                if (sourceAvailable) {
                    TextButton(onClick = onSource, enabled = !busy, modifier = Modifier.testTag("video-source-${asset.recordId}")) { Text("返回来源网页") }
                }
                TextButton(onClick = { onAction(LibraryAction.RENAME) }, enabled = !busy, modifier = Modifier.testTag("video-rename-${asset.recordId}")) { Text("修改显示名称") }
                TextButton(onClick = { onAction(LibraryAction.FORGET) }, enabled = !busy, modifier = Modifier.testTag("video-forget-${asset.recordId}")) { Text("移除记录，保留文件") }
                if (asset.canDeleteVideoFile()) {
                    TextButton(onClick = { onAction(LibraryAction.DELETE) }, enabled = !busy, modifier = Modifier.testTag("video-delete-${asset.recordId}")) { Text("删除文件") }
                }
            }
        }
    }
}

@Composable
private fun RenameVideoTitleDialog(
    asset: VideoAsset,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by rememberSaveable(asset.recordId) { mutableStateOf(asset.displayName) }
    val trimmed = title.trim()
    val invalid = trimmed.isBlank() || trimmed.length > 180 || trimmed.any { it.isISOControl() }
    val changed = trimmed != asset.displayName
    AlertDialog(
        modifier = Modifier.testTag("video-rename-dialog-${asset.recordId}"),
        onDismissRequest = onDismiss,
        title = { Text("修改显示名称") },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("只修改本应用列表中的显示标题，不重命名设备文件，不改变扩展名或文件内容。")
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, enabled = enabled, singleLine = true,
                    label = { Text("显示名称") }, isError = invalid,
                    supportingText = { Text(if (invalid) "请输入 1–180 个字符，不能包含控制字符。" else "${trimmed.length} / 180") },
                    modifier = Modifier.fillMaxWidth().testTag("video-title-input-${asset.recordId}")
                        .semantics { stateDescription = if (invalid) "显示名称无效" else "只修改显示标题，不重命名文件" },
                )
                Text("实际文件名：${localSafeLabel(asset.name)}", style = MaterialTheme.typography.bodySmall)
                if (!enabled) Text("正在处理此条目，请稍候。")
            }
        },
        confirmButton = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            TextButton(
                onClick = { if (enabled && !invalid && changed) { focus.clearFocus(force=true);keyboard?.hide();onRename(trimmed) } },
                enabled = enabled && !invalid && changed,
                modifier = Modifier.testTag("video-title-save-${asset.recordId}"),
            ) { Text("保存显示名称") }
        },
        dismissButton = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            TextButton(onClick = { focus.clearFocus(force = true); keyboard?.hide(); onDismiss() }) { Text("返回") }
        },
    )
}

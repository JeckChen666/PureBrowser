package com.example.purebrowser.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
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

private enum class LibraryAction { DETAILS, RENAME, FORGET, DELETE }

/**
 * Presents only the caller's app-owned index, including missing/unreadable entries.
 * No scanning, metadata IO, URI opening or physical renaming happens in this UI.
 * Source actions require caller evidence: VideoAsset itself has no sourceUrl.
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
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val search = query.trim()
    // Availability never filters the index: retained records must stay manageable.
    val matches = assets.filter {
        it.displayName.contains(search, ignoreCase = true) || it.name.contains(search, ignoreCase = true)
    }
    val ascending = compareBy<VideoAsset> { it.indexedAt }.thenBy { it.recordId }
    val visible = matches.sortedWith(if (newestFirst) ascending.reversed() else ascending)
    LaunchedEffect(actionId, actionAsset) {
        if (actionId != null && actionAsset == null) { actionId = null; actionName = null }
    }
    fun request(asset: VideoAsset, next: LibraryAction) {
        if (asset.recordId !in busyIds) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            actionId = asset.recordId
            actionName = next.name
        }
    }
    fun dismissAction() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        actionId = null
        actionName = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("videoLibraryScreen"),
        topBar = {
            TopAppBar(
                title = { Text("本地视频库", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { ToolButton(Glyph.BACK, "返回", tag = "videoLibraryBack", action = onBack) },
                actions = { ToolButton(Glyph.DOWNLOAD, "打开下载管理", tag = "videoLibraryDownloads", action = onDownloads) },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            BoxWithConstraints(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                val columns = videoLibraryColumnCount(maxWidth.value, LocalDensity.current.fontScale)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize().testTag("videoLibraryList")
                        .semantics { stateDescription = "缩略图网格，$columns 列" },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "library-controls", span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
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
                            Text(
                                "仅本应用保存记录 · 不扫描手机 · 打开和分享使用系统应用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "library-empty", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyContent(
                                if (search.isEmpty()) "还没有本地视频" else "没有找到匹配视频",
                                if (search.isEmpty()) "通过基础格式检查的下载会出现在这里。未通过或待确认的任务请到下载管理查看。"
                                else "试试其他显示名称或文件名。搜索不会删除或隐藏原记录。",
                            )
                        }
                    }
                    items(visible, key = { it.recordId }, contentType = { "video" }) { asset ->
                        VideoAssetTile(
                            asset = asset, busy = asset.recordId in busyIds, thumbnail = thumbnail,
                            onDetails = { request(asset, LibraryAction.DETAILS) },
                        )
                    }
                }
            }
        }
    }
    if (actionAsset != null && action != null) {
        val enabled = actionAsset.recordId !in busyIds
        when (action) {
            LibraryAction.DETAILS -> VideoFileActionSheet(
                asset = actionAsset, busy = !enabled, thumbnail = thumbnail,
                sourceAvailable = actionAsset.recordId in sourceAvailableIds,
                onDismiss = ::dismissAction,
                onOpen = {
                    if (enabled && actionAsset.isUsableVideo() &&
                        actionId == actionAsset.recordId && actionName == LibraryAction.DETAILS.name) {
                        dismissAction()
                        onOpen(actionAsset.recordId)
                    }
                },
                onShare = {
                    if (enabled && actionAsset.isUsableVideo() &&
                        actionId == actionAsset.recordId && actionName == LibraryAction.DETAILS.name) {
                        dismissAction()
                        onShare(actionAsset.recordId)
                    }
                },
                onSource = {
                    if (enabled && actionAsset.recordId in sourceAvailableIds &&
                        actionId == actionAsset.recordId && actionName == LibraryAction.DETAILS.name) {
                        dismissAction()
                        onSource(actionAsset.recordId)
                    }
                },
                onAction = { request(actionAsset, it) },
            )
            LibraryAction.RENAME -> RenameVideoTitleDialog(
                asset = actionAsset, enabled = enabled, onDismiss = ::dismissAction,
                onRename = { title ->
                    if (enabled && actionId == actionAsset.recordId && actionName == action.name) {
                        dismissAction()
                        onRename(actionAsset.recordId, title)
                    }
                },
            )
            LibraryAction.FORGET, LibraryAction.DELETE -> {
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
                    tag = "video-${action.name.lowercase(Locale.ROOT)}-dialog-${actionAsset.recordId}",
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
}

private fun VideoAsset.isUsableVideo(): Boolean =
    format == FormatCheck.PASSED && availability == FileAvailability.AVAILABLE

private fun VideoAsset.canDeleteVideoFile(): Boolean =
    availability == FileAvailability.AVAILABLE || availability == FileAvailability.UNREADABLE

private fun VideoAsset.videoStatus(): String = when {
    availability != FileAvailability.AVAILABLE -> localAvailabilityLabel(availability)
    format != FormatCheck.PASSED -> localFormatLabel(format)
    else -> "可用 · 格式初检通过"
}

private fun durationLabel(durationMillis: Long?): String {
    if (durationMillis == null || durationMillis < 0) return "时长未获取或不支持"
    val seconds = durationMillis / 1000
    return if (seconds >= 3600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun VideoAssetTile(
    asset: VideoAsset,
    busy: Boolean,
    thumbnail: @Composable (VideoAsset) -> Unit,
    onDetails: () -> Unit,
) {
    Card(
        onClick = onDetails, enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("video-${asset.recordId}").semantics {
            contentDescription = "${localSafeLabel(asset.displayName)}，文件操作"
            stateDescription = asset.videoStatus() + if (busy) "，正在处理" else ""
        },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        VideoThumbnail(asset, thumbnail, Modifier.fillMaxWidth().aspectRatio(1.5f).heightIn(min = 64.dp))
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(localSafeLabel(asset.displayName), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(localFileSize(asset.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                asset.videoStatus(), style = MaterialTheme.typography.labelSmall,
                color = if (asset.isUsableVideo()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("video-status-${asset.recordId}"),
            )
            if (busy) Text("正在处理…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("video-busy-${asset.recordId}"))
        }
    }
}

@Composable
private fun VideoThumbnail(
    asset: VideoAsset,
    thumbnail: @Composable (VideoAsset) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "video-thumbnail-${asset.recordId}",
) {
    val usable = asset.isUsableVideo()
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag(tag).semantics {
                contentDescription = if (usable) "本地视频封面" else "视频不可打开：${asset.videoStatus()}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrowserGlyph(if (usable) Glyph.VIDEO else Glyph.STOP, "", Modifier.clearAndSetSemantics {})
            Text(if (usable) "暂无封面" else "不可打开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Never render a stale/play-looking thumbnail for missing or unchecked media.
        if (usable) {
            // The retained callback may render a legacy fixed-size thumbnail. Tight constraints
            // make it fill this cell without changing its API or the caller's URI behavior.
            Box(Modifier.matchParentSize(), propagateMinConstraints = true) { thumbnail(asset) }
        }
        if (usable && asset.durationMillis != null && asset.durationMillis >= 0) {
            Text(
                durationLabel(asset.durationMillis), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoFileActionSheet(
    asset: VideoAsset,
    busy: Boolean,
    thumbnail: @Composable (VideoAsset) -> Unit,
    sourceAvailable: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSource: () -> Unit,
    onAction: (LibraryAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("video-actions-${asset.recordId}"),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(localSafeLabel(asset.displayName), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ToolButton(Glyph.CLOSE, "关闭文件操作", tag = "video-actions-close-${asset.recordId}", action = onDismiss)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Keep the ordinary sheet compact, but stack real metadata when text needs room.
                val stackMetadata = maxWidth.value < 300f || LocalDensity.current.fontScale >= 1.5f
                if (stackMetadata) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VideoThumbnail(asset, thumbnail, Modifier.width(112.dp).height(72.dp), tag = "video-sheet-thumbnail-${asset.recordId}")
                        VideoMetadataSummary(asset)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        VideoThumbnail(asset, thumbnail, Modifier.width(112.dp).height(72.dp), tag = "video-sheet-thumbnail-${asset.recordId}")
                        Column(Modifier.weight(1f)) { VideoMetadataSummary(asset) }
                    }
                }
            }
            Text("实际文件名：${localSafeLabel(asset.name)}", style = MaterialTheme.typography.bodyMedium)
            Text(
                asset.videoStatus(), color = if (asset.isUsableVideo()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("video-sheet-status-${asset.recordId}"),
            )
            // An index timestamp is not a legacy file's original save time.
            Text("入库时间：${localSavedTime(asset.indexedAt)}", style = MaterialTheme.typography.bodySmall)
            Text(
                asset.systemUpdatedAt?.let { "系统保存/更新时间：${localSavedTime(it)}" } ?: "原保存时间未记录",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!asset.isUsableVideo()) {
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
            if (busy) Text("正在处理…", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (asset.isUsableVideo()) {
                FileActionRow(Glyph.PLAY, "打开视频", "使用系统播放器", "video-open-${asset.recordId}", !busy, onClick = onOpen)
                FileActionRow(Glyph.SHARE, "分享文件", "由你选择接收应用，不会自动上传", "video-share-${asset.recordId}", !busy, onClick = onShare)
            }
            FileActionRow(Glyph.EDIT, "修改显示名称", "仅修改列表标题，不重命名设备文件", "video-rename-${asset.recordId}", !busy) { onAction(LibraryAction.RENAME) }
            if (sourceAvailable) {
                FileActionRow(Glyph.GLOBE, "返回来源网页", "打开此记录关联的网页", "video-source-${asset.recordId}", !busy, onClick = onSource)
            } else {
                Text("未记录可返回的来源网页", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Text("危险操作", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FileActionRow(Glyph.CLOSE, "移除记录，保留文件", "只移除本应用记录，需要确认", "video-forget-${asset.recordId}", !busy) { onAction(LibraryAction.FORGET) }
            if (asset.canDeleteVideoFile()) {
                FileActionRow(Glyph.STOP, "删除本机文件", "实际删除设备文件，无法撤销，需要再次确认", "video-delete-${asset.recordId}", !busy, destructive = true) { onAction(LibraryAction.DELETE) }
            }
        }
    }
}

@Composable
private fun VideoMetadataSummary(asset: VideoAsset) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localFileSize(asset.sizeBytes), style = MaterialTheme.typography.bodySmall)
        Text(durationLabel(asset.durationMillis), style = MaterialTheme.typography.bodySmall)
        Text("格式：${localSafeLabel(asset.mimeType?.takeIf { it.isNotBlank() } ?: "未获取或不支持")}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FileActionRow(
    glyph: Glyph,
    title: String,
    detail: String,
    tag: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BrowserGlyph(glyph, "", Modifier.clearAndSetSemantics {})
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current)
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

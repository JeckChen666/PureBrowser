package com.example.purebrowser.ui.resources

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.ProbeState
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.EmptyContent
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.ui.components.ToolButton
import kotlinx.coroutines.launch

/** Selection and source navigation belong to the caller; this sheet never starts a download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceSheet(
    candidates: List<MediaCandidate>,
    onDismiss: () -> Unit,
    onSelect: (MediaCandidate) -> Unit,
    onSource: () -> Unit,
    onAnalyzePage: (() -> Unit)? = null,
    onQuickSave: ((MediaCandidate) -> Unit)? = null,
) {
    // Equal URLs can carry different evidence. Do not deduplicate or key by URL alone.
    val downloadable = candidates.filter { it.canTryDownload() && it.kind!=MediaKind.UNKNOWN }
        .sortedWith(compareByDescending<MediaCandidate> { it.playing && Evidence.DOM in it.sources }
            .thenByDescending { Evidence.DOM in it.sources }.thenBy { it.kind == MediaKind.HLS })
    val unsupported = candidates.filterNot { it.canTryDownload() && it.kind!=MediaKind.UNKNOWN }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MediaCandidate?>(null) }
    val listState = rememberLazyListState()
    val selectedDetail = detail
    val detailState = key(selectedDetail) { rememberLazyListState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = {
            if (detail != null) {
                // Dismissal may already have animated the sheet to Hidden. Restore the list
                // step in this same window, rather than leaving an invisible modal blocker.
                detail = null
                scope.launch { sheetState.show() }
            } else onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // Details replace content inside this sheet, not a second modal window. The parent
        // still exclusively owns switching between resource/confirmation/other tool panels.
        LazyColumn(
            state = if (selectedDetail == null) listState else detailState,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("resource-sheet"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectedDetail != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ResourceDetailContent(
                            candidate = selectedDetail,
                            onDismiss = { detail = null },
                            onSelect = { detail = null; onSelect(selectedDetail) },
                            onSource = { detail = null; onSource() },
                        )
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ResourceHeader("页面资源", "关闭资源面板", onDismiss, onSource)
                        if(onAnalyzePage!=null) androidx.compose.material3.FilledTonalButton(onClick=onAnalyzePage,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("analyze-current-video")) { Text("分析当前视频") }
                        Text(
                            if (downloadable.none { it.kind == MediaKind.HLS } && downloadable.none { it.kind == MediaKind.DASH }) {
                                "${downloadable.size} 个可保存的视频文件 · ${unsupported.size} 个其他媒体资源"
                            } else {
                                "${downloadable.count { it.kind != MediaKind.HLS && it.kind != MediaKind.DASH }} 个视频文件 · ${downloadable.count { it.kind == MediaKind.HLS }} 个播放地址（HLS） · ${downloadable.count { it.kind == MediaKind.DASH }} 个播放地址（DASH） · ${unsupported.size} 个其他媒体资源"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (downloadable.any { it.kind == MediaKind.HLS }) {
                                "这里优先显示正在播放的视频。点开后选择清晰度即可保存；链接过期或格式不受支持时会保存失败，可回到视频页面重试。"
                            } else if (downloadable.any { it.kind == MediaKind.DASH }) {
                                "这里优先显示正在播放的视频。点开后选择清晰度即可保存，视频和声音会自动合并为一个 MP4 文件；链接过期或格式不受支持时会保存失败，可回到视频页面重试。"
                            } else "这里优先显示正在播放的视频。保存时可以选择是否使用网站的登录状态（仅限同一网站），不用也能尝试公开下载；链接过期或格式问题仍可能导致失败。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (candidates.isEmpty()) {
                    item {
                        EmptyContent("尚未发现视频", "请先在页面里播放视频，然后重新打开这个面板。也可以返回视频页面刷新后再试；受保护的视频可能无法识别。")
                    }
                } else {
                    item {
                        Text("可保存的视频", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                    }
                    if (downloadable.isEmpty()) {
                        item { EmptyContent("还没有可保存的视频文件", "已发现的线索在下方“其他媒体资源”里，继续播放视频后再来看看。应用不会自动下载。") }
                    }
                    items(downloadable) { candidate ->
                        ResourceLine(
                            candidate, downloadable = true,
                            onDetails = { detail = candidate.copy(sources = candidate.sources.toSet()) },
                            onSelect = { onSelect(candidate) },
                            onQuickSave = onQuickSave?.let { quick -> { quick(candidate) } },
                        )
                    }
                    if (unsupported.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) { expanded = !expanded }
                                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("其他媒体资源（${unsupported.size}） · ${if (expanded) "收起" else "展开"}",
                                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                BrowserGlyph(if (expanded) Glyph.LIST else Glyph.PLUS, if (expanded) "收起其他媒体资源" else "展开其他媒体资源")
                            }
                        }
                        if (expanded) {
                            items(unsupported) { candidate ->
                                ResourceLine(
                                    candidate, downloadable = false,
                                    onDetails = { detail = candidate.copy(sources = candidate.sources.toSet()) },
                                    onSelect = { onSelect(candidate) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ResourceLine(candidate: MediaCandidate, downloadable: Boolean, onDetails: () -> Unit, onSelect: () -> Unit, onQuickSave: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().testTag("resource-card-${candidate.displayName}")) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).let { m ->
                if (onQuickSave != null) m.combinedClickable(role = Role.Button,
                    onClick = { onSelect() }, onLongClick = { onQuickSave() })
                else m.clickable(role = Role.Button) { onSelect() }
            }, horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(readableResourceName(candidate.displayName), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                ResourceMetadata(candidate)
                if (downloadable && suggestedFileName(candidate) != candidate.displayName) {
                    Text("将保存为：${suggestedFileName(candidate)}", style = MaterialTheme.typography.bodySmall)
                }
                if (!downloadable) {
                    Text(candidate.unsupportedExplanation(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                ToolButton(Glyph.LIST, "查看详情", action = onDetails)
                if(candidate.kind==MediaKind.UNKNOWN && candidate.canTryDownload()) ToolButton(Glyph.SEARCH, "分析媒体", tag=resourceAnalyzeTag(candidate.url), action=onSelect)
                if (downloadable) ToolButton(Glyph.DOWNLOAD, "保存", tag = resourceSaveTag(candidate.url), action = onSelect)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** No evidence, signed URLs, guessed duration/resolution or invented finished-file size in rows. */
@Composable
internal fun ResourceMetadata(candidate: MediaCandidate) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(candidate.resourceType(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text("来自：${candidate.host}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (candidate.kind == MediaKind.HLS) {
            Text("保存后的文件大小要等下载完成后才知道", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("播放地址${candidate.reliableSizeLabel()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(candidate.reliableSizeLabel(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (candidate.probeState == ProbeState.PENDING) {
            Text("检查中", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        candidate.totalBytes?.takeIf { it > 0 }?.let {
            Text("已确认大小：${formatByteSize(it)}（支持断点续传：${if (candidate.resumable == true) "是" else "否"}）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        candidate.verifiedMime?.takeIf {
            it.length <= 80 && Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(it)
        }?.let {
            Text("已确认类型：$it",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (candidate.kind == MediaKind.HLS) {
            candidate.variants?.takeIf { it.isNotEmpty() }?.let {
                Text("${it.size} 种清晰度${if (it.any { variant -> variant.warning != null }) "（部分清晰度带兼容提示）" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (candidate.kind == MediaKind.DASH) {
            // T97: the listing stays display-only here; resolution and representation choice
            // happen in the DASH confirmation dialog after an explicit user action.
            candidate.variants?.takeIf { it.isNotEmpty() }?.let {
                Text("DASH · ${it.size} 种清晰度（打开后再选择）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

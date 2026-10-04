package com.example.purebrowser.ui.resources

import androidx.compose.foundation.clickable
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
                            if (downloadable.none { it.kind == MediaKind.HLS }) {
                                "${downloadable.size} 个可尝试的直链 · ${unsupported.size} 个其他媒体资源"
                            } else {
                                "${downloadable.count { it.kind != MediaKind.HLS }} 个可尝试的直链 · ${downloadable.count { it.kind == MediaKind.HLS }} 个 HLS 清单 · ${unsupported.size} 个其他媒体资源"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (downloadable.any { it.kind == MediaKind.HLS }) {
                                "优先展示视频元素关联的直链与 HLS。HLS 只在确认面板显式解析和准备，不自动请求；访问条件、签名过期或不支持的格式可能导致失败。"
                            } else "优先展示视频元素关联的直链。确认时可选择适用的同源网站会话和最小来源条件，也可关闭后尝试公开下载；会话不跨源转发，不保证跨来源下载成功。签名过期或文件格式仍可能导致失败。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (candidates.isEmpty()) {
                    item {
                        EmptyContent("尚未发现视频", "请先播放页面上的视频，再重新打开资源面板观察。也可返回来源页后刷新；跨域播放器或受保护媒体可能无法识别。")
                    }
                } else {
                    item {
                        Text("可尝试下载的直链", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                    }
                    if (downloadable.isEmpty()) {
                        item { EmptyContent("暂未发现文件直链", "已发现的线索在下方“其他媒体资源”中。继续播放后再查看，不会自动探测或批量下载。") }
                    }
                    items(downloadable) { candidate ->
                        ResourceLine(
                            candidate, downloadable = true,
                            onDetails = { detail = candidate.copy(sources = candidate.sources.toSet()) },
                            onSelect = { onSelect(candidate) },
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
private fun ResourceLine(candidate: MediaCandidate, downloadable: Boolean, onDetails: () -> Unit, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("resource-card-${candidate.displayName}")) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(readableResourceName(candidate.displayName), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                ResourceMetadata(candidate)
                if (downloadable && suggestedFileName(candidate) != candidate.displayName) {
                    Text("资源文件名：${suggestedFileName(candidate)}", style = MaterialTheme.typography.bodySmall)
                }
                if (!downloadable) {
                    Text(candidate.unsupportedExplanation(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                ToolButton(Glyph.LIST, "查看详情", action = onDetails)
                if(candidate.kind==MediaKind.UNKNOWN && candidate.canTryDownload()) ToolButton(Glyph.SEARCH, "分析媒体", tag=resourceAnalyzeTag(candidate.url), action=onSelect)
                if (downloadable) ToolButton(Glyph.DOWNLOAD, "尝试下载", tag = resourceSaveTag(candidate.url), action = onSelect)
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
        Text("资源主机：${candidate.host}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (candidate.kind == MediaKind.HLS) {
            Text("成品大小未知（清单响应不代表视频大小）", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("清单${candidate.reliableSizeLabel()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(candidate.reliableSizeLabel(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (candidate.probeState == ProbeState.PENDING) {
            Text("验证中", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        candidate.totalBytes?.takeIf { it > 0 }?.let {
            Text("已验证大小：${formatByteSize(it)}（支持断点续传：${if (candidate.resumable == true) "是" else "否"}）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        candidate.verifiedMime?.takeIf {
            it.length <= 80 && Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+").matches(it)
        }?.let {
            Text("已验证类型：$it", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (candidate.kind == MediaKind.HLS) {
            candidate.variants?.takeIf { it.isNotEmpty() }?.let {
                Text("${it.size} 档位${if (it.any { variant -> variant.warning != null }) "（部分档位带兼容提示）" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (candidate.kind == MediaKind.DASH) {
            // Display-only listing: this version has no DASH download path or variant dialog at all.
            candidate.variants?.takeIf { it.isNotEmpty() }?.let {
                Text("DASH · ${it.size} 档位（仅展示，本版暂不支持 DASH 下载）",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

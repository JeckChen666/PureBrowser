package com.example.purebrowser.ui.resources

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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.ui.components.EmptyContent

/** Selection and source navigation belong to the caller; this sheet never starts a download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceSheet(
    candidates: List<MediaCandidate>,
    onDismiss: () -> Unit,
    onSelect: (MediaCandidate) -> Unit,
    onSource: () -> Unit,
) {
    // Re-evaluate incoming observations without retaining a stale list. Equal URLs may carry
    // different evidence, so do not use URL alone as a lazy-list key or silently deduplicate.
    val downloadable = candidates.filter { it.canTryDownload() }
        .sortedWith(compareByDescending<MediaCandidate> { it.playing && Evidence.DOM in it.sources }
            .thenByDescending { Evidence.DOM in it.sources }.thenBy { it.kind == MediaKind.HLS })
    val unsupported = candidates.filterNot { it.canTryDownload() }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<MediaCandidate?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("resource-sheet"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "页面资源",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
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
                        } else "优先展示视频元素关联的直链。仅支持公开文件；登录态、签名过期或文件格式可能导致下载失败。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // In the sheet the owning source is supplied by the caller, never inferred
                    // from a candidate URL (the media host is not necessarily the source page).
                    OutlinedButton(onClick = onSource, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("返回来源页")
                    }
                }
            }
            if (candidates.isEmpty()) {
                item {
                    EmptyContent(
                        "尚未发现视频",
                        "请先播放页面上的视频，再重新打开资源面板观察。也可返回来源页后刷新；跨域播放器或受保护媒体可能无法识别。",
                    )
                }
            } else {
                item {
                    Text(
                        "可尝试下载的直链",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                }
                if (downloadable.isEmpty()) {
                    item {
                        EmptyContent("暂未发现文件直链", "已发现的线索在下方“其他媒体资源”中。继续播放后再查看，不会自动探测或批量下载。")
                    }
                }
                items(downloadable) { candidate ->
                    ResourceCard(
                        candidate = candidate,
                        downloadable = true,
                        onDetails = { detail = candidate.copy(sources = candidate.sources.toSet()) },
                        onSelect = { onSelect(candidate) },
                    )
                }
                if (unsupported.isNotEmpty()) {
                    item {
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                        FilledTonalButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                stateDescription = if (expanded) "已展开" else "已收起"
                            },
                        ) {
                            Text("其他媒体资源（${unsupported.size}） · ${if (expanded) "收起" else "展开"}")
                        }
                    }
                    if (expanded) {
                        items(unsupported) { candidate ->
                            ResourceCard(
                                candidate = candidate,
                                downloadable = false,
                                onDetails = { detail = candidate.copy(sources = candidate.sources.toSet()) },
                                onSelect = {},
                            )
                        }
                    }
                }
            }
            item {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("关闭资源面板")
                }
            }
        }
    }
    detail?.let { candidate ->
        ResourceDetail(
            candidate = candidate,
            onDismiss = { detail = null },
            onSelect = {
                detail = null
                onSelect(candidate)
            },
            onSource = {
                detail = null
                onSource()
            },
        )
    }
}

@Composable
private fun ResourceCard(
    candidate: MediaCandidate,
    downloadable: Boolean,
    onDetails: () -> Unit,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().testTag("resource-card-${candidate.displayName}"),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                readableResourceName(candidate.displayName),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            ResourceMetadata(candidate)
            if (!downloadable) {
                Text(
                    candidate.unsupportedExplanation(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloadable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("查看详情") }
                    Button(onClick = onSelect, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("尝试下载") }
                }
            } else {
                OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("查看详情") }
            }
        }
    }
}

/** Shared metadata deliberately excludes the URL, evidence and guessed media properties. */
@Composable
internal fun ResourceMetadata(candidate: MediaCandidate) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(candidate.resourceType(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "资源主机：${candidate.host}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (candidate.kind == MediaKind.HLS) "成品大小未知（清单响应不代表视频大小）" else candidate.reliableSizeLabel(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

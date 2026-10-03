package com.example.purebrowser.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.BrowserData
import com.example.purebrowser.data.browser.Shortcut
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.library.LocalVideoThumbnail
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph
import java.net.URI

@Composable
fun HomeScreen(data: BrowserData, assets: List<VideoAsset>, library: () -> Unit, play: (String) -> Unit, open: (String) -> Unit, add: () -> Unit, edit: (Shortcut) -> Unit, bookmarks: () -> Unit, history: () -> Unit, downloads: () -> Unit) {
    val visits = remember(data.history) { recentHomeVisits(data.history) }
    val saves = remember(assets) { recentHomeSaves(assets) }
    // BrowserScreen owns the address field and safe-area/IME insets. This is content only.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 720.dp).fillMaxSize().testTag("homeScreen"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BrowserGlyph(Glyph.GLOBE, "PureBrowser", Modifier.size(22.dp))
                    Text("PureBrowser", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                }
            }
            item { HomeSectionHeading("常用站点", "添加", add, "addShortcutButton") }
            item {
                if (data.shortcuts.isEmpty()) {
                    HomeNote("添加常用网页，下次一触即达。")
                } else {
                    HomeSites(data.shortcuts, open, edit)
                    HomeNote("长按站点可编辑", Modifier.padding(top = 4.dp))
                }
            }
            item { HomeSectionHeading("最近访问", "全部", history, "homeHistoryButton") }
            if (visits.isEmpty()) {
                item { HomeNote("从上方输入网址，开始你的第一次浏览。") }
            }
            items(visits) { page ->
                Surface(onClick = { open(page.url) }, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().testTag("homeVisit-${page.id}")) {
                    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrowserGlyph(Glyph.HISTORY, "最近访问")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(page.title.ifBlank { page.url }, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(runCatching { URI(page.url).host }.getOrNull().orEmpty().ifBlank { page.url },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { HomeSectionHeading("最近保存", "查看全部", library, "homeLibraryButton") }
            if (saves.isEmpty()) {
                item { HomeNote("网页中发现视频后，从资源面板保存到本机。") }
            }
            items(saves) { asset ->
                Surface(onClick = { play(asset.recordId) }, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().testTag("homeSave-${asset.recordId}")) {
                    Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LocalVideoThumbnail(asset, Modifier.size(48.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(asset.displayName, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("已保存到设备 · 打开视频", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                // Library/history have section actions above; keep only the remaining management links.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = bookmarks, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("homeBookmarksButton")) {
                        Text("书签", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = downloads, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("homeDownloadsButton")) {
                        Text("下载管理", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HomeNote("本地保存 · 无需账号")
            }
        }
    }
}

@Composable
private fun HomeSites(shortcuts: List<Shortcut>, open: (String) -> Unit, edit: (Shortcut) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = homeSiteColumns(maxWidth.value, fontScale)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shortcuts.chunked(columns).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { shortcut ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f).combinedClickable(
                                role = Role.Button,
                                onClick = { open(shortcut.url) },
                                onLongClickLabel = "编辑常用站点",
                                onLongClick = { edit(shortcut) },
                            ).testTag("homeShortcut-${shortcut.id}"),
                        ) {
                            Row(Modifier.heightIn(min = 56.dp).padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(shortcut.title.take(1).uppercase(), color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(shortcut.title, modifier = Modifier.weight(1f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeading(title: String, actionLabel: String, action: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f).semantics { heading() })
        TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
            Text(actionLabel, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HomeNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

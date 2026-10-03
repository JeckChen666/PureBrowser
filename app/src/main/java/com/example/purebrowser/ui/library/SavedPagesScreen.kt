package com.example.purebrowser.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.SavedPage
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.EmptyContent
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.ui.components.ToolButton
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun SavedPagesScreen(
    pages: List<SavedPage>,
    isHistory: Boolean,
    open: (String) -> Unit,
    edit: (SavedPage) -> Unit,
    remove: (SavedPage) -> Unit,
) {
    var query by rememberSaveable(isHistory) { mutableStateOf("") }
    val filtered = pages.filter { savedPageMatches(it, query) }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val zone = ZoneId.systemDefault()
    // Refresh relative headings at calendar midnight, not every minute or in the background.
    val today by produceState(LocalDate.now(zone), zone, isHistory) {
        if (isHistory) {
            while (true) {
                val now = ZonedDateTime.now(zone)
                value = now.toLocalDate()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
                delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1L))
            }
        }
    }
    val groups = if (isHistory) groupHistoryByLocalDate(filtered, zone) else emptyList()
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun openPage(url: String) {
        focus.clearFocus(force = true)
        keyboard?.hide()
        open(url)
    }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            // Leave text room rather than crushing it between several fixed 48dp actions.
            val stackedActions = maxWidth.value < 320f || LocalDensity.current.fontScale >= 1.3f
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("savedPageSearch"),
                    placeholder = { Text("搜索${if (isHistory) "历史" else "书签"}") },
                    leadingIcon = { BrowserGlyph(Glyph.SEARCH, "搜索") },
                    trailingIcon = {
                        if (query.isNotEmpty()) ToolButton(Glyph.CLOSE, "清空搜索", tag = "savedPageClearSearch") { query = "" }
                    },
                )
                Text(
                    "${filtered.size} / ${pages.size} 条${if (isHistory) "记录" else "书签"} · 仅保存在本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp).testTag("savedPageCount"),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("savedPagesList"),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    if (filtered.isEmpty()) {
                        item(key = "saved-empty") {
                            EmptyContent(
                                if (query.isBlank()) {
                                    if (isHistory) "还没有浏览记录" else "还没有书签"
                                } else "没有找到匹配网页",
                                if (query.isNotBlank()) "试试其他标题或网址，清空搜索可查看所有记录。"
                                else if (isHistory) "访问过的网页会保存在这里。"
                                else "在网页菜单中选择添加书签，即可随时找回。",
                            )
                        }
                    }
                    if (isHistory) {
                        groups.forEach { group ->
                            item(key = "history-date-${group.date}", contentType = "date") {
                                Text(
                                    historyRelativeDateLabel(group.date, today) ?: group.date?.format(dateFormat).orEmpty(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)
                                        .testTag("history-date-${group.date ?: "unknown"}").semantics { heading() },
                                )
                            }
                            group.pages.forEach { page ->
                                item(key = page.id, contentType = "page") {
                                    SavedPageRow(page, true, stackedActions, ::openPage, edit, remove)
                                }
                            }
                        }
                    } else {
                        // Keep the caller's bookmark order; only history is time-ordered.
                        filtered.forEach { page ->
                            item(key = page.id, contentType = "page") {
                                SavedPageRow(page, false, stackedActions, ::openPage, edit, remove)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPageRow(
    page: SavedPage,
    isHistory: Boolean,
    stackedActions: Boolean,
    open: (String) -> Unit,
    edit: (SavedPage) -> Unit,
    remove: (SavedPage) -> Unit,
) {
    val host = runCatching { URI(page.url).host }.getOrNull().orEmpty()
    Surface(
        onClick = { open(page.url) },
        modifier = Modifier.fillMaxWidth().testTag("saved-${page.id}"),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer) {
                        BrowserGlyph(if (isHistory) Glyph.HISTORY else Glyph.BOOKMARK, "", Modifier.clearAndSetSemantics {})
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        page.title.ifBlank { host.ifBlank { "无标题网页" } },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        host.ifBlank { "网址未识别" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (isHistory) {
                        Text(
                            if (page.time < 0) "时间未知" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(page.time)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!stackedActions) SavedPageActions(page, isHistory, edit, remove)
            }
            if (stackedActions) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SavedPageActions(page, isHistory, edit, remove)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SavedPageActions(page: SavedPage, isHistory: Boolean, edit: (SavedPage) -> Unit, remove: (SavedPage) -> Unit) {
    if (!isHistory) ToolButton(Glyph.EDIT, "编辑书签", tag = "edit-${page.id}") { edit(page) }
    ToolButton(Glyph.CLOSE, if (isHistory) "删除这条历史记录" else "删除这条书签", tag = "delete-${page.id}") { remove(page) }
}

package com.example.purebrowser.ui.tabs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.browser.TabPreviewCache
import com.example.purebrowser.data.browser.HOME_URL
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.ui.components.BrowserGlyph
import com.example.purebrowser.ui.components.Glyph
import com.example.purebrowser.ui.components.ToolButton

/** Existing six callback parameters are unchanged; thumbnails and open-time capture are optional. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcher(
    tabs: List<TabRecord>,
    selected: String,
    select: (String) -> Unit,
    close: (String) -> Unit,
    create: () -> Unit,
    dismiss: () -> Unit,
    previewCache: TabPreviewCache? = null,
    requestPreview: () -> Unit = {},
    viewPreferences: TabViewPreferences? = null,
) {
    val context = LocalContext.current
    val preferences = remember(context, viewPreferences) { viewPreferences ?: TabViewPreferences(context) }
    var modeName by rememberSaveable { mutableStateOf(preferences.read().name) }
    val mode = TabViewMode.fromStored(modeName)
    var query by rememberSaveable { mutableStateOf("") }
    var locateRequest by remember { mutableIntStateOf(0) }
    val visible = remember(tabs, query) { TabSwitcherRules.search(tabs, query) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val latestRequestPreview by rememberUpdatedState(requestPreview)
    // Revision is deliberately not a bitmap snapshot: clear/eviction must release cache ownership.
    val previewRevision = previewCache?.revision?.collectAsState()?.value ?: 0L

    LaunchedEffect(Unit) { latestRequestPreview() }
    LaunchedEffect(mode, selected, locateRequest) {
        val index = visible.indexOfFirst { it.id == selected }
        if (index >= 0) {
            if (mode == TabViewMode.GRID) gridState.scrollToItem(index) else listState.scrollToItem(index)
        }
    }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) {
            gridState.scrollToItem(0)
            listState.scrollToItem(0)
        }
    }

    fun changeMode(next: TabViewMode) {
        if (next != mode) { modeName = next.name; preferences.write(next) }
    }
    fun locate() {
        query = TabSwitcherRules.locate(tabs, selected).query
        focus.clearFocus()
        keyboard?.hide()
        locateRequest++
    }

    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Let the modal sheet own system/IME insets; do not apply those a second time here.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
            BackHandler(enabled = query.isNotEmpty()) { query = ""; focus.clearFocus(); keyboard?.hide() }
            Column(Modifier.fillMaxHeight(.96f).padding(horizontal = 12.dp).testTag("tabSwitcher")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("标签页 · ${tabs.size}/50", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    ToolButton(Glyph.CLOSE, "返回浏览", tag = "dismissTabsButton", action = dismiss)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ViewModeButton(Glyph.GRID, "网格视图", "tabGridButton", mode == TabViewMode.GRID) { changeMode(TabViewMode.GRID) }
                    ViewModeButton(Glyph.LIST, "列表视图", "tabListButton", mode == TabViewMode.LIST) { changeMode(TabViewMode.LIST) }
                    Spacer(Modifier.weight(1f))
                    ToolButton(Glyph.LOCATE, "定位当前标签", enabled = tabs.any { it.id == selected }, tag = "locateTabButton", action = ::locate)
                    ToolButton(Glyph.PLUS, "新标签页", enabled = tabs.size < 50, tag = "newTabButton", action = create)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("tabSearchField"),
                    singleLine = true,
                    placeholder = { Text("搜索标题或网址") },
                    leadingIcon = { BrowserGlyph(Glyph.SEARCH, "搜索当前打开的标签") },
                    trailingIcon = {
                        if (query.isNotEmpty()) ToolButton(Glyph.CLOSE, "清空搜索", tag = "clearTabSearchButton") { query = "" }
                    },
                )
                Spacer(Modifier.height(8.dp))
                if (visible.isEmpty()) {
                    Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("没有匹配的标签", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("tabSearchEmpty"))
                        Text("仅搜索当前打开的标签标题和网址", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { query = "" }, modifier = Modifier.heightIn(min = 48.dp).testTag("clearEmptyTabSearchButton")) { Text("清空搜索") }
                    }
                } else if (mode == TabViewMode.GRID) {
                    // Two columns on normal phones, more on wide windows, one for narrow/large-text windows.
                    val cellWidth = 140.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(cellWidth), state = gridState,
                        modifier = Modifier.fillMaxWidth().weight(1f).testTag("tabGrid"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(visible, key = { it.id }) { tab ->
                            TabCard(tab, tab.id == selected, true, previewCache, previewRevision, select, close)
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState, modifier = Modifier.fillMaxWidth().weight(1f).testTag("tabList"),
                        verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        items(visible, key = { it.id }) { tab ->
                            TabCard(tab, tab.id == selected, false, previewCache, previewRevision, select, close)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewModeButton(glyph: Glyph, label: String, tag: String, active: Boolean, action: () -> Unit) {
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { selected = active }
        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)) {
        ToolButton(glyph, label, tag = tag, action = action)
    }
}

@Composable
private fun TabCard(
    tab: TabRecord, active: Boolean, grid: Boolean, previews: TabPreviewCache?, revision: Long,
    select: (String) -> Unit, close: (String) -> Unit,
) {
    val description = tab.title.ifBlank { TabSwitcherRules.subtitle(tab) }
    Card(
        onClick = { select(tab.id) },
        modifier = Modifier.fillMaxWidth().heightIn(min = if (grid) 64.dp else 56.dp).testTag("tab-${tab.id}").semantics { selected = active },
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (grid) {
            val bitmap = remember(previews, revision, tab.id, tab.url) { previews?.get(tab.id, tab.url) }
            Box(Modifier.fillMaxWidth().height(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize().testTag("preview-${tab.id}"), contentScale = ContentScale.Crop)
                else BrowserGlyph(if (tab.url == HOME_URL) Glyph.HOME else Glyph.GLOBE, "暂无页面预览", Modifier.testTag("previewPlaceholder-${tab.id}"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!grid) BrowserGlyph(if (tab.url == HOME_URL) Glyph.HOME else Glyph.GLOBE, if (tab.url == HOME_URL) "首页" else "网页")
            Column(Modifier.weight(1f).padding(horizontal = if (grid) 0.dp else 10.dp, vertical = 8.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(TabSwitcherRules.subtitle(tab), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // A nested independent target consumes the close tap; it must never also select.
            ToolButton(Glyph.CLOSE, "关闭标签：$description", tag = "close-${tab.id}") { close(tab.id) }
        }
    }
}

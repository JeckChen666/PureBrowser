package com.example.purebrowser.ui.tabs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.purebrowser.data.browser.TabRecord
import com.example.purebrowser.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcher(tabs: List<TabRecord>, selected: String, select: (String)->Unit, close: (String)->Unit, create: ()->Unit, dismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxHeight(.85f).padding(horizontal=20.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("标签页 · ${tabs.size}",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                FilledTonalButton(onClick=create,modifier=Modifier.testTag("newTabButton")) { BrowserGlyph(Glyph.PLUS,"新标签页");Text("新建") }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(modifier=Modifier.testTag("tabList"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                items(tabs,key={it.id}) { tab ->
                    Card(onClick={select(tab.id)},modifier=Modifier.fillMaxWidth().testTag("tab-${tab.id}"),colors=CardDefaults.cardColors(containerColor=if(tab.id==selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.padding(start=16.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            BrowserGlyph(if(tab.url=="about:blank") Glyph.HOME else Glyph.GLOBE,"网页")
                            Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                Text(tab.title,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text(if(tab.url=="about:blank") "首页" else runCatching { java.net.URI(tab.url).host }.getOrNull().orEmpty(),style=MaterialTheme.typography.bodySmall)
                            }
                            ToolButton(Glyph.CLOSE,"关闭标签",tag="close-${tab.id}") { close(tab.id) }
                        }
                    }
                }
            }
        }
    }
}

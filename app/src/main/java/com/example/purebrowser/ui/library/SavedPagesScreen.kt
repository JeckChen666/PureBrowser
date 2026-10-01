package com.example.purebrowser.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.purebrowser.data.browser.SavedPage
import com.example.purebrowser.ui.components.*
import java.text.DateFormat
import java.util.Date

@Composable
fun SavedPagesScreen(pages: List<SavedPage>, isHistory: Boolean, open: (String)->Unit, edit: (SavedPage)->Unit, remove: (SavedPage)->Unit) {
    var query by rememberSaveable(isHistory) { mutableStateOf("") }
    val filtered = pages.filter { it.title.contains(query,true) || it.url.contains(query,true) }
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        OutlinedTextField(value=query,onValueChange={query=it},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("savedPageSearch"),placeholder={Text("搜索${if(isHistory) "历史" else "书签"}")},leadingIcon={BrowserGlyph(Glyph.SEARCH,"搜索")})
        Spacer(Modifier.height(12.dp))
        if(filtered.isEmpty()) EmptyContent(if(query.isBlank()) if(isHistory) "还没有浏览记录" else "还没有书签" else "没有找到匹配网页",if(isHistory) "访问过的网页会保存在这里。" else "在网页菜单中选择添加书签，即可随时找回。")
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            items(filtered,key={it.id}) { page ->
                Card(onClick={open(page.url)},modifier=Modifier.testTag("saved-${page.id}"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(start=14.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(page.title,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(runCatching { java.net.URI(page.url).host }.getOrNull().orEmpty(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(isHistory) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(page.time)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(!isHistory) ToolButton(Glyph.EDIT,"编辑书签",tag="edit-${page.id}") { edit(page) }
                        ToolButton(Glyph.CLOSE,"删除记录",tag="delete-${page.id}") { remove(page) }
                    }
                }
            }
        }
    }
}

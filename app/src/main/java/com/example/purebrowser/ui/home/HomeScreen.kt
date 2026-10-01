package com.example.purebrowser.ui.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.purebrowser.data.browser.*
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.library.LocalVideoThumbnail
import com.example.purebrowser.ui.components.*

@Composable
fun HomeScreen(data: BrowserData, assets: List<VideoAsset>, library: ()->Unit, play: (Long)->Unit, open: (String) -> Unit, add: () -> Unit, edit: (Shortcut) -> Unit, bookmarks: () -> Unit, history: () -> Unit, downloads: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("homeScreen"), contentPadding=PaddingValues(horizontal=24.dp,vertical=24.dp), verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Surface(color=MaterialTheme.colorScheme.primaryContainer, shape=RoundedCornerShape(16.dp)) {
                    Box(Modifier.padding(14.dp)) { BrowserGlyph(Glyph.GLOBE,"PureBrowser",Modifier.size(30.dp)) }
                }
                Text("PureBrowser",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                Text("自在浏览，随手保存。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            listOf(Triple("视频库",Glyph.DOWNLOAD,library),Triple("下载",Glyph.DOWNLOAD,downloads),Triple("书签",Glyph.BOOKMARK,bookmarks),Triple("历史",Glyph.HISTORY,history)).chunked(2).forEach { group ->
                Row(Modifier.fillMaxWidth().padding(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    group.forEach { (label,icon,action) ->
                        FilledTonalButton(onClick=action,modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=12.dp,vertical=12.dp)) {
                            BrowserGlyph(icon,label,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(label)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("最近保存",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                TextButton(onClick=library) { Text("查看全部") }
            }
            val recent = assets.filter { it.availability == FileAvailability.AVAILABLE }.sortedByDescending { it.indexedAt }.take(3)
            if(recent.isEmpty()) Text("网页中发现视频后，从资源面板保存到本机。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            recent.forEach { asset ->
                Card(onClick={play(asset.systemId)},modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        LocalVideoThumbnail(asset,Modifier.size(64.dp))
                        Column(Modifier.weight(1f)) { Text(asset.displayName,maxLines=1,overflow=TextOverflow.Ellipsis);Text("已保存到设备",style=MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("常用站点",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                TextButton(onClick=add,modifier=Modifier.testTag("addShortcutButton")) { Text("添加") }
            }
            data.shortcuts.chunked(3).forEach { group ->
                Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    group.forEach { shortcut ->
                        Surface(modifier=Modifier.weight(1f).combinedClickable(onClick={open(shortcut.url)},onLongClick={edit(shortcut)}),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.padding(14.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(shortcut.title.take(1).uppercase(),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                                Text(shortcut.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    repeat(3-group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if(data.shortcuts.isEmpty()) Text("添加常用网页，下次一触即达。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            else Text("长按站点可编辑",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=10.dp))
        }
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("最近访问",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                TextButton(onClick=history) { Text("全部") }
            }
            if(data.history.isEmpty()) Text("从上方输入网址，开始你的第一次浏览。",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=10.dp))
            data.history.take(3).forEach { item ->
                Card(onClick={open(item.url)},modifier=Modifier.fillMaxWidth().padding(top=8.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        BrowserGlyph(Glyph.HISTORY,"最近访问");Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(item.title,maxLines=1,overflow=TextOverflow.Ellipsis);Text(runCatching { java.net.URI(item.url).host }.getOrNull().orEmpty(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item { Text("本地保存 · 无需账号",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

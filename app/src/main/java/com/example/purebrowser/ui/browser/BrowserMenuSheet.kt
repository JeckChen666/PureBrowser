package com.example.purebrowser.ui.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.purebrowser.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenuSheet(bookmarked:Boolean,canBookmark:Boolean,onDismiss:()->Unit,newTab:()->Unit,
                     bookmark:()->Unit,resources:()->Unit,downloads:()->Unit,library:()->Unit,
                     bookmarks:()->Unit,history:()->Unit,settings:()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("页面菜单",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                ToolButton(Glyph.CLOSE,"关闭菜单",tag="dismissMenuButton",action=onDismiss)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                MenuAction(Glyph.PLUS,"新建标签","menu-newTab",true,newTab,Modifier.weight(1f))
                MenuAction(Glyph.BOOKMARK,if(bookmarked)"取消书签" else "添加书签","menu-bookmarkToggle",canBookmark,bookmark,Modifier.weight(1f))
                MenuAction(Glyph.VIDEO,"视频资源","resourcesButton",true,resources,Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(vertical=8.dp))
            MenuRow(Glyph.DOWNLOAD,"下载","downloadsButton",downloads)
            MenuRow(Glyph.VIDEO,"视频库","menu-library",library)
            MenuRow(Glyph.BOOKMARK,"书签","menu-bookmarks",bookmarks)
            MenuRow(Glyph.HISTORY,"历史","menu-history",history)
            HorizontalDivider(Modifier.padding(vertical=4.dp))
            MenuRow(Glyph.SETTINGS,"设置","menu-settings",settings)
        }
    }
}
@Composable
private fun MenuAction(glyph:Glyph,label:String,tag:String,enabled:Boolean,click:()->Unit,modifier:Modifier=Modifier) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally) {
        ToolButton(glyph,label,enabled,tag,click)
        Text(label,maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelMedium,color=if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun MenuRow(glyph:Glyph,label:String,tag:String,click:()->Unit) {
    Surface(onClick=click,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)) {
        Row(Modifier.padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            BrowserGlyph(glyph,label);Text(label,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f));BrowserGlyph(Glyph.NEXT,"打开$label")
        }
    }
}

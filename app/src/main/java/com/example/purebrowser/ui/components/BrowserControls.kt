package com.example.purebrowser.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun BrowserAddressBar(address:String,editing:Boolean,tabCount:Int,change:(String)->Unit,focus:()->Unit,submit:()->Unit,cancel:()->Unit,tabs:()->Unit,menu:@Composable ()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        if (editing) {
            val requester = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { requester.requestFocus(); keyboard?.show() }
            OutlinedTextField(value=address,onValueChange=change,singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.weight(1f).testTag("addressInput").focusRequester(requester),
                placeholder={Text("搜索或输入网址")},leadingIcon={BrowserGlyph(Glyph.SEARCH,"搜索")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Go),keyboardActions=KeyboardActions(onGo={submit()}))
        } else {
            // A non-editing address is not a focusable editor: WebView focus changes must
            // never reopen the keyboard or put the browser chrome back into edit mode.
            OutlinedCard(onClick=focus,modifier=Modifier.weight(1f).heightIn(min=56.dp).testTag("addressInput"),shape=RoundedCornerShape(18.dp),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    BrowserGlyph(Glyph.SEARCH,"搜索")
                    Text(address.ifBlank { "搜索或输入网址" },maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(editing) {
            TextButton(onClick=submit,modifier=Modifier.testTag("navigateButton")){Text("访问")}
            TextButton(onClick=cancel){Text("取消")}
        } else {
            FilledTonalIconButton(onClick=tabs,modifier=Modifier.testTag("tabsButton")){Text(tabCount.toString())}
            menu()
        }
    }
}
@Composable
fun BrowserToolbar(canBack:Boolean,canForward:Boolean,resources:Int,home:()->Unit,back:()->Unit,next:()->Unit,media:()->Unit,downloads:()->Unit) {
    Surface(tonalElevation=2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            ToolButton(Glyph.HOME,"首页",tag="homeButton",action=home)
            ToolButton(Glyph.BACK,"后退",canBack,tag="backButton",action=back)
            ToolButton(Glyph.NEXT,"前进",canForward,tag="forwardButton",action=next)
            FilledTonalButton(onClick=media,modifier=Modifier.testTag("resourcesButton"),contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp)){BrowserGlyph(Glyph.DOWNLOAD,"媒体资源",Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("资源 $resources")}
            ToolButton(Glyph.DOWNLOAD,"下载",tag="downloadsButton",action=downloads)
        }
    }
}
@Composable
fun BrowserPageChrome(title:String,progress:Int,error:String?,reload:()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        if(progress<100) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
            ToolButton(if(progress<100)Glyph.STOP else Glyph.REFRESH,if(progress<100)"停止加载" else "刷新",tag="reloadButton",action=reload)
        }
        error?.let { BrowserErrorBanner(it,reload) }
    }
}
@Composable
fun BrowserErrorBanner(message:String,retry:()->Unit) {
    Surface(color=MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(message,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=retry){Text("重试")}
        }
    }
}

package com.example.purebrowser.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.purebrowser.ui.browser.BrowserChromeRules

@Composable
fun BrowserAddressBar(address: String, editing: Boolean, change: (String)->Unit, focus: ()->Unit,
                      submit: ()->Unit, cancel: ()->Unit, loading: Boolean, reload: ()->Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
        if(editing) {
            val requester=remember { FocusRequester() }
            val keyboard=LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { requester.requestFocus();keyboard?.show() }
            OutlinedTextField(value=address,onValueChange=change,singleLine=true,
                shape=RoundedCornerShape(12.dp),modifier=Modifier.weight(1f).testTag("addressInput").focusRequester(requester),
                placeholder={Text("搜索或输入网址")},leadingIcon={BrowserGlyph(Glyph.SEARCH,"网址与搜索")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,imeAction=ImeAction.Go),
                keyboardActions=KeyboardActions(onGo={submit()}))
            ToolButton(Glyph.NEXT,"访问",tag="navigateButton",action=submit)
            ToolButton(Glyph.CLOSE,"取消输入",tag="cancelAddressButton",action=cancel)
        } else {
            Surface(onClick=focus,shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer,
                modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("addressInput")) {
                Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    BrowserGlyph(Glyph.SEARCH,"编辑网址")
                    Text(BrowserChromeRules.displayAddress(address),maxLines=1,overflow=TextOverflow.Ellipsis,
                        style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
                }
            }
            if(address.isNotBlank()) ToolButton(if(loading) Glyph.STOP else Glyph.REFRESH,
                if(loading) "停止加载" else "刷新",tag="reloadButton",action=reload)
        }
    }
}

@Composable
fun BrowserToolbar(canBack: Boolean, canForward: Boolean, tabCount: Int, isHome: Boolean,
                   back: ()->Unit, next: ()->Unit, home: ()->Unit, tabs: ()->Unit, menu: ()->Unit) {
    Surface(color=MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=2.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                ToolButton(Glyph.BACK,"后退",canBack,tag="backButton",action=back)
                ToolButton(Glyph.NEXT,"前进",canForward,tag="forwardButton",action=next)
                CompositionLocalProvider(LocalContentColor provides if(isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) {
                    IconButton(onClick=home,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("homeButton").semantics { selected=isHome }) { BrowserGlyph(Glyph.HOME,"首页") }
                }
                IconButton(onClick=tabs,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("tabsButton")
                    .semantics { contentDescription="标签页，共 $tabCount 个" }) {
                    Surface(shape=RoundedCornerShape(4.dp),border=androidx.compose.foundation.BorderStroke(1.5.dp,LocalContentColor.current)) {
                        Text(tabCount.toString(),style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(horizontal=5.dp,vertical=1.dp))
                    }
                }
                ToolButton(Glyph.MENU,"菜单",tag="menuButton",action=menu)
            }
        }
    }
}

@Composable
fun BrowserPageChrome(progress:Int,error:String?,retry:()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        if(progress<100) LinearProgressIndicator(progress={progress/100f},modifier=Modifier.fillMaxWidth().height(2.dp))
        error?.let { BrowserErrorBanner(it,retry) }
    }
}
@Composable
fun BrowserErrorBanner(message:String,retry:()->Unit) {
    Surface(color=MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(message,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick=retry,modifier=Modifier.testTag("retryPageButton")){Text("重试")}
        }
    }
}

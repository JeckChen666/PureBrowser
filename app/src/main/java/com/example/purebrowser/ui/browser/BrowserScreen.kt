package com.example.purebrowser.ui.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import com.example.purebrowser.ui.downloads.DownloadsScreen
import com.example.purebrowser.ui.downloads.isActiveTask
import com.example.purebrowser.ui.library.VideoLibraryScreen
import com.example.purebrowser.library.LocalVideoThumbnail
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.example.purebrowser.browser.BrowserPage
import com.example.purebrowser.data.browser.*
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.ui.components.*
import com.example.purebrowser.ui.home.HomeScreen
import com.example.purebrowser.ui.tabs.TabSwitcher
import com.example.purebrowser.ui.library.SavedPagesScreen
import com.example.purebrowser.ui.settings.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private enum class Destination(val label: String) { BROWSER("浏览"), BOOKMARKS("书签"), HISTORY("历史"), SETTINGS("设置"), ABOUT("关于"), DOWNLOADS("下载管理"), LIBRARY("视频库") }
private data class Editor(val title: String, val name: String, val url: String, val shortcut: Shortcut? = null, val bookmark: SavedPage? = null, val isShortcut: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(model: BrowserViewModel = viewModel()) {
    val data by model.data.collectAsStateWithLifecycle()
    val ready by model.ready.collectAsStateWithLifecycle()
    val session by model.tabs.active.collectAsStateWithLifecycle()
    val emptyPage = remember { MutableStateFlow(BrowserPage()) }
    val emptyCandidates = remember { MutableStateFlow<List<MediaCandidate>>(emptyList()) }
    val page by (session?.engine?.page ?: emptyPage).collectAsStateWithLifecycle()
    val candidates by (session?.sniffer?.candidates ?: emptyCandidates).collectAsStateWithLifecycle()
    val downloads by model.downloads.collectAsStateWithLifecycle()
    val assets by model.videoLibrary.collectAsStateWithLifecycle()
    val busy by model.busyIds.collectAsStateWithLifecycle()
    val wifiOnly by model.defaultWifiOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val message by model.message.collectAsStateWithLifecycle()
    val link by model.link.collectAsStateWithLifecycle()
    val routeState=rememberSaveableStateHolder()
    var route by rememberSaveable { mutableStateOf(Destination.BROWSER) }
    var routeTrail by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(BrowserTool.NONE) }
    var confirmationBusy by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<Editor?>(null) }
    var deletion by remember { mutableStateOf<SavedPage?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val hostView = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val imeVisible = WindowInsets.isImeVisible
    fun stopEditing() { keyboard?.hide(); focus.clearFocus(force=true); editing=false; address=page.url.takeIf { it!=HOME_URL }.orEmpty() }
    fun navigate(value: String) { keyboard?.hide(); focus.clearFocus(force=true);editing=false; route=Destination.BROWSER;routeTrail="";model.navigate(value) }
    fun newTab(url: String=HOME_URL) { stopEditing();route=Destination.BROWSER;routeTrail="";model.newTab(url);tool=BrowserTool.NONE }
    fun open(destination: Destination) {
        stopEditing();tool=BrowserTool.NONE
        if(route != destination) { routeTrail = (routeTrail.split(',').filter { it.isNotBlank() } + route.name).takeLast(12).joinToString(",");route=destination }
        model.reconcileDownloads()
    }
    fun source(id: String) { tool=BrowserTool.NONE;stopEditing();route=Destination.BROWSER;routeTrail="";model.returnToSource(id) }
    fun routeBack() {
        stopEditing();tool=BrowserTool.NONE
        val trail = routeTrail.split(',').filter { it.isNotBlank() }
        route = trail.lastOrNull()?.let { runCatching { Destination.valueOf(it) }.getOrNull() } ?: Destination.BROWSER
        routeTrail = trail.dropLast(1).joinToString(",")
    }

    LaunchedEffect(session, page.url) { if(!editing) address=page.url.takeIf { it!=HOME_URL }.orEmpty() }
    // WebView/Compose focus hand-off can reopen the IME during the navigation frame.
    // Close it once the new chrome has settled, without interrupting a newer edit.
    LaunchedEffect(editing, page.url, route) {
        if (!editing && route == Destination.BROWSER) {
            delay(120)
            keyboard?.hide()
            ViewCompat.getWindowInsetsController(hostView)?.hide(WindowInsetsCompat.Type.ime())
        }
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it);if(model.message.value==it)model.consumeMessage() } }
    LaunchedEffect(session,paused,route) {
        if(paused || route!=Destination.BROWSER) session?.engine?.pause() else session?.engine?.resume()
        while(!paused && route==Destination.BROWSER) { delay(3000);session?.engine?.scanMedia() }
    }
    DisposableEffect(lifecycle,model) {
        val observer=LifecycleEventObserver { _,event ->
            when(event) { Lifecycle.Event.ON_PAUSE -> {paused=true;model.engine?.pause();model.flush()};Lifecycle.Event.ON_RESUME -> {paused=false;model.engine?.resume();model.reconcileDownloads()};Lifecycle.Event.ON_STOP -> model.flush();else -> Unit }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);model.flush() }
    }
    fun showTool(next: BrowserTool) { if(confirmationBusy)return;stopEditing();tool=next }
    val backState=rememberNavigationEventState(currentInfo=NavigationEventInfo.None)
    NavigationBackHandler(state=backState,isBackEnabled=!confirmationBusy && editor==null && deletion==null && !clearHistory && link==null && (tool!=BrowserTool.NONE || route!=Destination.BROWSER || editing || page.canGoBack || page.url!=HOME_URL),onBackCompleted={
        when { tool!=BrowserTool.NONE -> tool=BrowserTool.NONE;editing -> stopEditing();route!=Destination.BROWSER -> routeBack();imeVisible -> {keyboard?.hide();focus.clearFocus()};page.canGoBack -> model.engine?.back();else -> model.engine?.home() }
    })

    Scaffold(modifier=Modifier.fillMaxSize(),contentWindowInsets=WindowInsets.safeDrawing,
        snackbarHost={SnackbarHost(snackbar)},topBar={
        if(route==Destination.BROWSER) {
            Column(Modifier.statusBarsPadding()) {
                BrowserAddressBar(address,editing,{address=it},{tool=BrowserTool.NONE;editing=true},
                    {if(editing)navigate(address)},{stopEditing()},page.progress<100,
                    {if(page.progress<100)model.engine?.stop() else model.engine?.reload()})
                if(page.url!=HOME_URL) BrowserPageChrome(page.progress,page.error) {model.engine?.reload()}
            }
        } else if(route !in setOf(Destination.DOWNLOADS,Destination.LIBRARY)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                ToolButton(Glyph.BACK,"返回",tag="routeBackButton",action=::routeBack)
                Text(route.label,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                if(route==Destination.HISTORY && data.history.isNotEmpty()) TextButton(onClick={clearHistory=true},modifier=Modifier.testTag("clearHistoryButton")) {Text("清空")}
            }
        }
    },bottomBar={
        if(route==Destination.BROWSER && !editing && !imeVisible && tool==BrowserTool.NONE && !confirmationBusy) {
            Box(Modifier.navigationBarsPadding()) {
                BrowserToolbar(page.canGoBack,page.canGoForward,data.tabs.size,page.url==HOME_URL,
                    {stopEditing();model.engine?.back()},{stopEditing();model.engine?.forward()},
                    {stopEditing();model.engine?.home()},{showTool(BrowserTool.TABS)},{showTool(BrowserTool.MENU)})
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if(!ready) CircularProgressIndicator(Modifier.align(Alignment.Center)) else {
                session?.let { BrowserWebViewHost(it,Modifier.fillMaxSize().testTag("browserWebView"),active=route==Destination.BROWSER && !paused,previewSource={view->model.tabs.bindPreviewSource(it.recordId,view)}) }
                if(route==Destination.BROWSER && page.url==HOME_URL) Surface(Modifier.fillMaxSize()) {
                    HomeScreen(data,assets,{open(Destination.LIBRARY)},{id->model.launchFile(context,id,false)},::navigate,{editor=Editor("添加常用站点","","",isShortcut=true)}, {site->editor=Editor("编辑常用站点",site.title,site.url,shortcut=site,isShortcut=true)}, {open(Destination.BOOKMARKS)},{open(Destination.HISTORY)},{open(Destination.DOWNLOADS)})
                }
                if(route==Destination.BROWSER && !editing && tool==BrowserTool.NONE && !confirmationBusy && page.url!=HOME_URL) {
                    Column(Modifier.align(Alignment.BottomEnd).padding(12.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(candidates.isNotEmpty()) FilledTonalButton(onClick={model.engine?.scanMedia();showTool(BrowserTool.RESOURCES)},modifier=Modifier.testTag("resourceHintButton")) {
                            BrowserGlyph(Glyph.VIDEO,"发现视频");Spacer(Modifier.width(6.dp));Text("发现 ${candidates.size} 个资源")
                        }
                        val activeCount=downloads.count { it.isActiveTask() }
                        if(activeCount>0) FilledTonalButton(onClick={showTool(BrowserTool.DOWNLOADS)},modifier=Modifier.testTag("downloadQuickButton")) {
                            BrowserGlyph(Glyph.DOWNLOAD,"下载速览");Spacer(Modifier.width(6.dp));Text("下载 · $activeCount")
                        }
                    }
                }
                if(route==Destination.BROWSER && editing) Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
                    androidx.compose.foundation.lazy.LazyColumn(contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),modifier=Modifier.testTag("addressSuggestions")) {
                        item { Text("历史与书签",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(vertical=8.dp)) }
                        val suggestions=BrowserChromeRules.suggestions(address,data.history,data.bookmarks)
                        items(suggestions.size) { index ->
                            val item=suggestions[index]
                            Surface(onClick={navigate(item.url)},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).testTag("addressSuggestion-$index")) {
                                Row(Modifier.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    BrowserGlyph(if(item.source=="书签")Glyph.BOOKMARK else Glyph.HISTORY,item.source)
                                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                        Text(item.title,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text("${item.source} · ${BrowserChromeRules.displayAddress(item.url)}",style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    BrowserGlyph(Glyph.NEXT,"访问")
                                }
                            }
                            HorizontalDivider()
                        }
                        if(suggestions.isEmpty()) item { Text("输入网址或搜索词，点击访问。联想仅使用本机记录。",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=12.dp)) }
                    }
                }
                if(route!=Destination.BROWSER) routeState.SaveableStateProvider(route.name) {
                  Surface(Modifier.fillMaxSize()) {
                    when(route) {
                        Destination.BOOKMARKS -> SavedPagesScreen(data.bookmarks,false,::navigate,{item->editor=Editor("编辑书签",item.title,item.url,bookmark=item)},{deletion=it})
                        Destination.HISTORY -> SavedPagesScreen(data.history,true,::navigate,{}, {deletion=it})
                        Destination.SETTINGS -> SettingsScreen(data.theme,model::setTheme,wifiOnly,model::setDefaultWifiOnly,about={open(Destination.ABOUT)},
                            privacyActions=SettingsPrivacyActions(
                                clearHistory={model.clearLocalData(com.example.purebrowser.privacy.PrivacyCategory.HISTORY)},
                                clearSiteData={model.clearLocalData(com.example.purebrowser.privacy.PrivacyCategory.SITE_DATA)},
                                clearCache={model.clearLocalData(com.example.purebrowser.privacy.PrivacyCategory.CACHE)},
                                clearDownloadTemp={model.clearLocalData(com.example.purebrowser.privacy.PrivacyCategory.DOWNLOAD_TEMP)},
                                diagnosticReport=model::diagnosticReport,
                                shareDiagnosticReport={text->
                                    val share=android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                                        .putExtra(android.content.Intent.EXTRA_TEXT,text)
                                    runCatching { context.startActivity(android.content.Intent.createChooser(share,"分享脱敏诊断")) }
                                        .onFailure { model.notify("没有可用的分享应用") }
                                }))
                        Destination.ABOUT -> AboutScreen()
                        Destination.DOWNLOADS -> DownloadsScreen(downloads,busy,::routeBack,
                            {id->model.launchFile(context,id,false)},{id->model.launchFile(context,id,true)},
                            model::retryDownload,model::cancelDownload,model::forgetDownload,model::deleteDownloadFile,
                            ::source,{open(Destination.LIBRARY)},onRetryWithoutContext={ model.retryDownload(it,false) },onPause=model::pauseDownload,onResume=model::resumeDownload)
                        Destination.LIBRARY -> VideoLibraryScreen(assets,busy,::routeBack,
                            {id->model.launchFile(context,id,false)},{id->model.launchFile(context,id,true)},
                            model::renameVideo,model::forgetDownload,model::deleteDownloadFile,::source,
                            {open(Destination.DOWNLOADS)},thumbnail={asset->LocalVideoThumbnail(asset,Modifier.fillMaxSize())},sourceAvailableIds=downloads.filter { it.sourceUrl != null }.map { it.id }.toSet())
                        else -> Unit
                    }
                  }
                }
            }
        }
    }
    if(tool==BrowserTool.TABS) TabSwitcher(data.tabs,data.selectedId,{id->stopEditing();model.tabs.select(id);tool=BrowserTool.NONE;route=Destination.BROWSER},model.tabs::close,{newTab()},{tool=BrowserTool.NONE},previewCache=model.tabs.previewCache,requestPreview={model.tabs.captureActivePreview()})
    if(tool==BrowserTool.DOWNLOADS) com.example.purebrowser.ui.downloads.DownloadQuickSheet(
        downloads,busy,{tool=BrowserTool.NONE},{open(Destination.DOWNLOADS)},{open(Destination.LIBRARY)},
        {id->model.launchFile(context,id,false)},{id->model.launchFile(context,id,true)},
        model::retryDownload,model::cancelDownload,::source,
        onRetryWithoutContext={model.retryDownload(it,false)},onPause=model::pauseDownload,onResume=model::resumeDownload,
        onForget=model::forgetDownload,onDelete=model::deleteDownloadFile)
    if(tool==BrowserTool.MENU) BrowserMenuSheet(data.bookmarks.any{it.url==page.url},page.url!=HOME_URL && page.error==null,
        {tool=BrowserTool.NONE},{newTab()},{tool=BrowserTool.NONE;model.toggleBookmark()},
        {model.engine?.scanMedia();showTool(BrowserTool.RESOURCES)},{open(Destination.DOWNLOADS)},
        {open(Destination.LIBRARY)},{open(Destination.BOOKMARKS)},{open(Destination.HISTORY)},{open(Destination.SETTINGS)})
    BrowserPanels(model,candidates,tool==BrowserTool.RESOURCES,{tool=BrowserTool.NONE},onBusyChanged={confirmationBusy=it})
    editor?.let { value ->
        PageEditor(value,onDismiss={editor=null},save={name,url->
            val success=if(value.isShortcut) model.saveShortcut(value.shortcut,name,url) else value.bookmark?.let {model.editBookmark(it,name,url)}==true
            if(success)editor=null
            success
        },delete=if(value.shortcut!=null)({model.deleteShortcut(value.shortcut.id);editor=null}) else null)
    }
    deletion?.let { item -> AlertDialog(onDismissRequest={deletion=null},title={Text("删除这条${if(route==Destination.HISTORY)"历史" else "书签"}？")},text={Text("不会删除下载文件或其他浏览数据。")},confirmButton={TextButton(onClick={if(route==Destination.HISTORY)model.deleteHistory(item.id) else model.deleteBookmark(item.id);deletion=null}){Text("删除")}},dismissButton={TextButton(onClick={deletion=null}){Text("取消")}}) }
    if(clearHistory) AlertDialog(onDismissRequest={clearHistory=false},title={Text("清空浏览历史？")},text={Text("书签、网站登录状态与下载文件不会受到影响。")},confirmButton={TextButton(modifier=Modifier.testTag("confirmClearHistory"),onClick={model.clearHistory();clearHistory=false}){Text("清空")}},dismissButton={TextButton(onClick={clearHistory=false}){Text("取消")}})
    link?.let { url -> AlertDialog(onDismissRequest=model::dismissLink,title={Text("打开链接")},text={Text(runCatching{java.net.URI(url).host}.getOrNull().orEmpty())},confirmButton={TextButton(onClick={model.dismissLink();newTab(url)}){Text("在新标签打开")}},dismissButton={TextButton(onClick={model.dismissLink();navigate(url)}){Text("当前标签打开")}}) }
}

@Composable
private fun PageEditor(value: Editor,onDismiss:()->Unit,save:(String,String)->Boolean,delete:(()->Unit)?) {
    var name by remember(value) {mutableStateOf(value.name)}
    var url by remember(value) {mutableStateOf(value.url)}
    var error by remember(value) {mutableStateOf(false)}
    var confirmDelete by remember(value) {mutableStateOf(false)}
    AlertDialog(onDismissRequest=onDismiss,title={Text(value.title)},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        OutlinedTextField(value=name,onValueChange={name=it},singleLine=true,label={Text("名称")},modifier=Modifier.testTag("editorName"))
        OutlinedTextField(value=url,onValueChange={url=it},singleLine=true,label={Text("网址")},modifier=Modifier.testTag("editorUrl"),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
        if(error) Text("请检查名称与网址；不要重复收藏或超过站点数量上限。",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if(delete!=null) TextButton(onClick={confirmDelete=true}) {Text("删除此站点")}
    }},confirmButton={
        // AlertDialog buttons run in the dialog root, unlike this function's caller.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        TextButton(onClick={focus.clearFocus(force=true);keyboard?.hide();error=!save(name,url)},modifier=Modifier.testTag("editorSave")){Text("保存")}
    },dismissButton={
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        TextButton(onClick={focus.clearFocus(force=true);keyboard?.hide();onDismiss()}){Text("取消")}
    })
    if(confirmDelete && delete!=null) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除这个常用站点？")},confirmButton={TextButton(onClick={confirmDelete=false;delete()}){Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}})
}

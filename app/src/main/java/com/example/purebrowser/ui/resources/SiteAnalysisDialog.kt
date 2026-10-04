package com.example.purebrowser.ui.resources

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.*
import com.example.purebrowser.media.resolver.*
import kotlinx.coroutines.*

/** Common site/protocol selection; no media transport before the following confirmation. */
@Composable
fun SiteAnalysisDialog(draft:DownloadDraft,resolver:SiteResolverRegistry,onDismiss:()->Unit,onReady:(ResolvedMediaOption)->Unit) {
    val scope=rememberCoroutineScope()
    var job by remember(draft){mutableStateOf<Job?>(null)}
    var token by remember(draft){mutableStateOf<TransferCancellation?>(null)}
    var busy by remember(draft){mutableStateOf(false)}
    var media by remember(draft){mutableStateOf<ResolvedMediaOptions?>(null)}
    var selected by remember(draft){mutableStateOf<ResolvedMediaOption?>(null)}
    var error by remember(draft){mutableStateOf<String?>(null)}
    DisposableEffect(draft){onDispose{token?.cancel();job?.cancel()}}
    fun close(){token?.cancel();job?.cancel();onDismiss()}
    ResourceDialog(::close) {
        ResourceHeading("分析当前视频")
        Text("公开媒体元数据 · 本机解析",style=MaterialTheme.typography.bodySmall)
        Text("不上传给云解析服务、不使用网站 Cookie。这里只获取有界媒体信息；确认保存后才传输视频。线索和格式声明不是成品保证。",style=MaterialTheme.typography.bodySmall)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(busy){CircularProgressIndicator();Text("正在解析，可取消")}
        media?.let { info ->
            Text(readableResourceName(info.title),style=MaterialTheme.typography.titleMedium)
            info.options.forEachIndexed { index,option ->
                TextButton(onClick={selected=option},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("site-format-$index")) {
                    Text("${if(selected==option)"✓ " else ""}${option.label}")
                }
            }
            selected?.takeIf{it in info.options}?.let { option ->
                Button(onClick={onReady(option)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("site-analysis-ready")){Text("继续确认保存")}
            }
        }
        if(media==null)Button(onClick={
            if(!busy){busy=true;error=null;val cancel=TransferCancellation();token=cancel
                job=scope.launch {
                    try{val parsed=resolver.resolve(draft,cancel);ensureActive();cancel.check();media=parsed;selected=parsed.options.firstOrNull()}
                    catch(e:CancellationException){cancel.cancel();throw e}
                    catch(e:Exception){error=(e as? TransferFailure)?.safeMessage ?: "当前站点解析不可用，请稍后主动重试"}
                    finally{busy=false}
                }
            }
        },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("site-analysis-start")){Text("解析可用格式")}
        TextButton(onClick=::close,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("取消")}
    }
}

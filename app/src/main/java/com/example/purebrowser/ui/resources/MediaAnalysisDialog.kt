package com.example.purebrowser.ui.resources

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.*
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.resolver.*
import kotlinx.coroutines.*

/** User-started classification only; no task/file exists until the following confirmation. */
@Composable
fun MediaAnalysisDialog(draft:DownloadDraft,probe:MediaProbe,onDismiss:()->Unit,onReady:(MediaCandidate)->Unit) {
    val scope=rememberCoroutineScope()
    var token by remember(draft){mutableStateOf<TransferCancellation?>(null)}
    var busy by remember(draft){mutableStateOf(false)}
    var result by remember(draft){mutableStateOf<MediaCandidate?>(null)}
    var error by remember(draft){mutableStateOf<String?>(null)}
    DisposableEffect(draft){onDispose{token?.cancel()}}
    fun close(){token?.cancel();onDismiss()}
    ResourceDialog(::close) {
        ResourceHeading("分析媒体线索")
        Text(readableResourceName(draft.candidate.displayName))
        Text("只进行匿名、最多 64 KiB 的受控分析，不使用网站 Cookie，不创建下载任务。容器线索不等于完整视频；实际保存后仍需格式检查。",style=MaterialTheme.typography.bodySmall)
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        if(busy) { CircularProgressIndicator();Text("正在分析，可取消") }
        result?.let{candidate ->
            ResourceMetadata(candidate)
            Text("已获得容器／清单线索，请继续确认保存范围。",style=MaterialTheme.typography.bodySmall)
            Button(onClick={onReady(candidate)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("media-analysis-ready")){Text("继续确认保存")}
        }
        if(result==null) Button(onClick={
            if(!busy){busy=true;error=null
                val cancel=TransferCancellation();token=cancel
                scope.launch {
                    try {
                        when(val found=withContext(Dispatchers.IO){probe.analyze(draft,cancel)}) {
                            is MediaProbeResult.Candidate -> {ensureActive();cancel.check();result=found.media}
                            is MediaProbeResult.Unsupported -> error=found.reason
                        }
                    }catch(e:CancellationException){cancel.cancel();throw e}
                    catch(e:Exception){error=(e as? TransferFailure)?.safeMessage ?: "媒体分析失败，请稍后主动重试"}
                    finally{if(token===cancel)busy=false}
                }
            }
        },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("media-analysis-start")){Text("分析媒体")}
        TextButton(onClick=::close,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("取消")}
    }
}

package com.example.purebrowser.media.site

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.*
import com.example.purebrowser.download.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Transient formats only. Neither URLs nor player/visitor data are persisted or diagnostic text. */
data class YouTubeTrack(val id:String,val url:String,val length:Long,val durationMs:Long,val height:Int,val mime:String) {
    override fun toString()="YouTubeTrack(id=$id,height=$height)"
}
data class YouTubeMedia(val videoId:String,val title:String,val videos:List<YouTubeTrack>,val audios:List<YouTubeTrack>) {
    override fun toString()="YouTubeMedia(formats=${videos.size+audios.size})"
}

/** Test-only, single-resolution capability. No data-class copy/component/JSON or raw value getter.
 * Production resolve() never creates this object. Consumption clears the session cpn reference. */
class YouTubeCpnDiagnosticSession internal constructor(val media: YouTubeMedia, private var cpn: String?) {
    private val diagnosticVideos = media.videos.toList()
    init { require(cpn != null && cpn!!.length == 16 && Regex("[A-Za-z0-9_-]{16}").matches(cpn!!)) { "Invalid diagnostic session" } }
    override fun toString() = "YouTubeCpnDiagnosticSession(testOnly=true)"

    @Synchronized
    internal fun consumeVideoPair(track: YouTubeTrack, testOnlyCpnOptIn: Boolean = false): YouTubeCpnDiagnosticPair {
        check(testOnlyCpnOptIn) { "Diagnostic opt-in required" }
        check(diagnosticVideos.any { it === track }) { "Diagnostic track identity mismatch" }
        val value = cpn ?: error("Diagnostic session already consumed")
        validateCpnDiagnosticUrl(track.url, cpnExpected = false)
        val appended = track.url + if (URI(track.url).rawQuery == null) "?cpn=$value" else "&cpn=$value"
        validateCpnDiagnosticUrl(appended, cpnExpected = true)
        cpn = null
        return YouTubeCpnDiagnosticPair(track, appended)
    }
}

/** Internal in-memory URLs only; no task/plan/store/schema integration. */
internal class YouTubeCpnDiagnosticPair(val track: YouTubeTrack, val withCpnUrl: String) {
    override fun toString() = "YouTubeCpnDiagnosticPair(testOnly=true)"
}

internal fun validateCpnDiagnosticUrl(url: String, cpnExpected: Boolean) {
    RequestPolicy.validateUrl(url, false)
    val u = URI(url)
    check(u.scheme == "https" && u.host.lowercase().endsWith(".googlevideo.com") &&
        u.port in setOf(-1, 443) && u.rawUserInfo == null && u.rawFragment == null) { "Diagnostic URL policy" }
    // Decode names so encoded/duplicate range, cpn or credential parameters cannot hide in a URL.
    val entries = u.rawQuery?.split('&')?.map {
        java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() to it.substringAfter('=', "")
    }.orEmpty()
    check(entries.none { it.first in setOf("range", "pot", "po_token", "cookie", "authorization", "auth", "token") }) {
        "Diagnostic query policy"
    }
    val cpns = entries.filter { it.first == "cpn" }
    check(if (cpnExpected) cpns.size == 1 && Regex("[A-Za-z0-9_-]{16}").matches(cpns.single().second)
        else cpns.isEmpty()) { "Diagnostic cpn policy" }
}

/** A local worker with NO network/native/storage authority; a polled, validated metadata queue.
 * All actual IO uses the repository's privacy lease wrapper and anonymous native HTTPS requests. */
class YouTubeResolver(private val context:Context,private val guard:(HttpTransport)->HttpTransport={it}) {
    suspend fun resolve(videoId:String):YouTubeMedia = parse(resolveOutput(videoId), videoId)

    /** Explicit test-only entry; false rejects BEFORE assets/WebView/metadata IO. */
    suspend fun resolveForCpnDiagnostic(videoId: String, testOnlyCpnOptIn: Boolean = false): YouTubeCpnDiagnosticSession {
        check(testOnlyCpnOptIn) { "Diagnostic opt-in required" }
        val output = resolveOutput(videoId, testOnlyCpnOptIn = true)
        val media = parse(output, videoId)
        val cpn = output.opt("diagnosticCpn")
        require(cpn is String && cpn.length == 16 && Regex("[A-Za-z0-9_-]{16}").matches(cpn)) { "Invalid diagnostic session" }
        output.remove("diagnosticCpn")
        return YouTubeCpnDiagnosticSession(media, cpn)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveOutput(videoId:String, testOnlyCpnOptIn:Boolean=false):JSONObject {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId))
        val script=withContext(Dispatchers.IO){context.assets.open("site-parser/youtube-worker.js").bufferedReader().use { it.readText() }}
        val cancel=TransferCancellation()
        return withContext(Dispatchers.Main) {
            val web=WebView(context.applicationContext)
            try {
                web.settings.apply {
                    javaScriptEnabled=true;domStorageEnabled=false;allowFileAccess=false;allowContentAccess=false
                    mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW;javaScriptCanOpenWindowsAutomatically=false
                    blockNetworkLoads=true
                }
                web.webViewClient=object:WebViewClient(){
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest)=WebResourceResponse("text/plain","UTF-8",java.io.ByteArrayInputStream(ByteArray(0)))
                }
                val encoded=JSONObject.quote(script).replace("<","\\u003c")
                val id=JSONObject.quote(videoId)
                val html="""<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'; worker-src blob:; connect-src 'none'"><script>
                    window.__queue=[];window.__output=null;window.__failure=null;
                    const worker=new Worker(URL.createObjectURL(new Blob([$encoded],{type:'application/javascript'})));
                    window.__worker=worker;worker.onmessage=e=>{if(e.data.request)window.__queue.push(e.data.request);if(e.data.result)window.__output=e.data.result;if(e.data.failure)window.__failure=e.data.failure;};
                    worker.onerror=()=>{window.__failure='解析运行环境不支持此站点';};worker.postMessage({videoId:$id,testOnlyCpnOptIn:$testOnlyCpnOptIn});
                    </script>"""
                web.loadDataWithBaseURL("https://purebrowser.invalid/","$html","text/html","UTF-8",null)
                withTimeout(45000) {
                    var requests=0
                    while(true) {
                        ensureActive()
                        val wire=web.json("JSON.stringify({requests:window.__queue?window.__queue.splice(0,2):[],output:window.__output||null,failure:window.__failure||null})")
                        if(wire!=null) {
                            if(!wire.isNull("failure"))throw TransferFailure(FailureKind.UNSUPPORTED,"YouTube 当前解析或访问条件不受支持；未创建任务")
                            if(!wire.isNull("output"))return@withTimeout wire.getJSONObject("output")
                            val queue=wire.optJSONArray("requests") ?: JSONArray()
                            for(i in 0 until queue.length()) {
                                if(++requests>20)throw TransferFailure(FailureKind.UNSUPPORTED,"站点解析超过请求预算")
                                val request=queue.getJSONObject(i)
                                val response=readMetadata(request,videoId,cancel)
                                ensureActive()
                                web.evaluateJavascript("window.__worker.postMessage({response:${response}})",null)
                            }
                        }
                        delay(100)
                    }
                    @Suppress("UNREACHABLE_CODE") error("no result")
                }
            } catch(e:TimeoutCancellationException) {
                throw TransferFailure(FailureKind.UNSUPPORTED,"站点解析超时，请稍后主动重试")
            } finally {
                cancel.cancel()
                web.stopLoading();web.destroy()
            }
        }
    }
    private suspend fun readMetadata(request:JSONObject,videoId:String,cancel:TransferCancellation):JSONObject = suspendCancellableCoroutine { continuation ->
        val job=CoroutineScope(Dispatchers.IO).launch {
            try { val result=metadata(request,videoId,cancel);if(continuation.isActive)continuation.resume(result) }
            catch(failure:Exception){if(continuation.isActive)continuation.resumeWithException(failure)}
        }
        continuation.invokeOnCancellation { cancel.cancel();job.cancel() }
    }

    private suspend fun WebView.json(code:String):JSONObject?=suspendCancellableCoroutine { continuation ->
        evaluateJavascript(code) { raw ->
            if(!continuation.isActive)return@evaluateJavascript
            val decoded=runCatching {
                if(raw.length>196608)error("wire budget")
                val text=JSONArray("[$raw]").optString(0)
                JSONObject(text)
            }.getOrNull()
            continuation.resume(decoded)
        }
    }
    private fun metadata(request:JSONObject,videoId:String,cancel:TransferCancellation):JSONObject {
        val url=request.getString("url");val u=runCatching { URI(url) }.getOrNull()
        RequestPolicy.validateUrl(url,false)
        require(u?.host=="www.youtube.com" && u.port in setOf(-1,443) && u.rawFragment==null &&
            Regex("/(?:sw\\.js_data|iframe_api|youtubei/v1/(?:config|player)|s/player/[a-zA-Z0-9_-]+/[^?#]*base\\.js)").matches(u.path))
        val method=request.getString("method");require(method in setOf("GET","POST"))
        val body=request.optString("body");require(body.toByteArray().size<=65536)
        if(u.path=="/youtubei/v1/player")require(JSONObject(body).optString("videoId")==videoId)
        val allowed=setOf("content-type","user-agent","accept","origin","referer","x-youtube-client-name","x-youtube-client-version","x-goog-visitor-id")
        val headers=linkedMapOf("Accept-Encoding" to "identity")
        val supplied=request.optJSONObject("headers") ?: JSONObject()
        supplied.keys().forEach { key ->
            val value=supplied.optString(key)
            if(key.lowercase() in allowed){require(value.length<=4096 && value.none(Char::isISOControl));headers[key]=value}
        }
        val transport=guard(HttpTransport { target,h,token ->
            check(java.net.CookieHandler.getDefault()==null)
            val connection=URI(target).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false;connection.connectTimeout=10000;connection.readTimeout=10000;connection.useCaches=false
            connection.requestMethod=method;h.forEach { (k,v)->connection.setRequestProperty(k,v) }
            token.bind{connection.disconnect()}
            try {
                if(method=="POST"){connection.doOutput=true;connection.setFixedLengthStreamingMode(body.toByteArray().size);connection.outputStream.use{it.write(body.toByteArray())}}
                val status=connection.responseCode
                object:HttpResponse {
                    override val status=status
                    override fun header(name:String)=connection.getHeaderField(name)
                    override fun body()=connection.inputStream
                    override fun close(){connection.disconnect();token.clear()}
                }
            }catch(e:Exception){connection.disconnect();token.clear();throw e}
        })
        return transport.open(url,headers,cancel).use { response ->
            if(response.status!=200)throw TransferFailure(FailureKind.ACCESS_CONDITION,"站点元信息无法在当前匿名条件下取得")
            val encoding=response.header("Content-Encoding")
            if(!encoding.isNullOrBlank() && !encoding.equals("identity",true))throw TransferFailure(FailureKind.UNSUPPORTED,"站点元信息压缩响应不受支持")
            val bytes=ByteArrayOutputStream()
            response.body().use { input ->
                val buffer=ByteArray(8192)
                while(true){cancel.check();val n=input.read(buffer);if(n<0)break
                    if(bytes.size()+n>4*1024*1024)throw TransferFailure(FailureKind.UNSUPPORTED,"站点元信息超过大小预算")
                    bytes.write(buffer,0,n)
                }
            }
            JSONObject().put("id",request.getInt("id")).put("url",url).put("status",200)
                .put("headers",JSONObject().put("Content-Type",response.header("Content-Type") ?: "application/octet-stream"))
                .put("body",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP))
        }
    }
    private fun parse(output:JSONObject,expectedId:String):YouTubeMedia {
        require(output.getString("videoId")==expectedId)
        fun tracks(key:String,video:Boolean):List<YouTubeTrack> {
            val a=output.getJSONArray(key);require(a.length() in 1..16)
            return (0 until a.length()).map { index ->
                val f=a.getJSONObject(index);val url=f.getString("url");RequestPolicy.validateUrl(url,false)
                val host=URI(url).host.lowercase();require(host.endsWith(".googlevideo.com"))
                val mime=f.getString("mime");require(mime.length<160 && if(video)mime.startsWith("video/mp4")&&mime.contains("avc1") else mime.startsWith("audio/mp4")&&mime.contains("mp4a.40.2"))
                val id=f.getString("id");require(Regex("[0-9]{1,5}").matches(id))
                val length=f.getLong("length");val duration=f.getLong("durationMs");val height=f.optInt("height")
                require(length in 1..(8L*1024*1024*1024) && duration in 1..86400000 && (!video || height in 1..1080))
                YouTubeTrack(id,url,length,duration,height,mime)
            }.distinctBy { it.id }
        }
        return YouTubeMedia(expectedId,output.optString("title").filterNot(Char::isISOControl).take(180),tracks("videos",true),tracks("audios",false))
    }
}

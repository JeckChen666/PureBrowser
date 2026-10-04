package com.example.purebrowser.media.resolver

import com.example.purebrowser.download.*
import com.example.purebrowser.download.hls.HlsHttpClient
import com.example.purebrowser.download.site.*
import com.example.purebrowser.media.*
import com.example.purebrowser.media.site.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URI

/** Transient, user-confirmed option; candidate/format information is not an output-file guarantee. */
data class ResolvedMediaOption(val label:String,val candidate:MediaCandidate,val dualTrackPlan:DualTrackDownloadPlan?=null) {
    override fun toString()="ResolvedMediaOption(dual=${dualTrackPlan!=null})"
}
data class ResolvedMediaOptions(val title:String,val options:List<ResolvedMediaOption>)
interface SiteMediaAdapter {
    fun supports(pageUrl:String):Boolean
    suspend fun resolve(draft:DownloadDraft,cancel:TransferCancellation):ResolvedMediaOptions
}

/** Standard/protocol adapters, not a per-site list of copied download implementations. */
class SiteResolverRegistry(private val adapters:List<SiteMediaAdapter>) {
    fun supports(pageUrl:String)=adapters.any{it.supports(pageUrl)}
    suspend fun resolve(draft:DownloadDraft,cancel:TransferCancellation):ResolvedMediaOptions {
        cancel.check()
        val adapter=adapters.firstOrNull{it.supports(draft.sourceUrl.orEmpty())}
            ?: throw TransferFailure(FailureKind.UNSUPPORTED,"此页面没有本版支持的站点／协议适配，请分析已发现的媒体线索")
        return adapter.resolve(draft,cancel)
    }
}
class YouTubeMediaAdapter(private val resolver:YouTubeResolver):SiteMediaAdapter {
    override fun supports(pageUrl:String)=YouTubeIdentity.videoId(pageUrl)!=null
    override suspend fun resolve(draft:DownloadDraft,cancel:TransferCancellation):ResolvedMediaOptions {
        val id=YouTubeIdentity.videoId(draft.sourceUrl.orEmpty()) ?: throw TransferFailure(FailureKind.UNSUPPORTED,"视频身份无效")
        val info=resolver.resolve(id);cancel.check()
        val audio=info.audios.firstOrNull{it.length<=DualTrackMetadata.MAX_AUDIO_BYTES && it.durationMs*1000<=DualTrackMetadata.MAX_DURATION_US}
            ?: throw TransferFailure(FailureKind.UNSUPPORTED,"没有预算内的受支持音频轨")
        val options=info.videos.filter{it.length<=DualTrackMetadata.MAX_VIDEO_BYTES && it.durationMs*1000<=DualTrackMetadata.MAX_DURATION_US}.sortedByDescending{it.height}.map{video->
            val plan=DualTrackDownloadPlan("youtube:$id",video.id,audio.id,video.url,audio.url,"avc1","mp4a.40.2",video.length,audio.length,minOf(video.durationMs,audio.durationMs)*1000,safeSourceUrl="https://www.youtube.com/watch?v=$id")
            ResolvedMediaOption("${video.height}p · H.264 + AAC · 合并 MP4",MediaCandidate(video.url,MediaKind.FILE,setOf(Evidence.SITE),"video/mp4",video.length,info.title),plan)
        }
        if(options.isEmpty())throw TransferFailure(FailureKind.UNSUPPORTED,"没有预算内的受支持视频轨")
        return ResolvedMediaOptions(info.title,options)
    }
}

/** A PeerTube public API shape, usable on independent deployments without a host whitelist.
 * Only exact UUID/short-UUID watch paths; validates response work identity and published complete MP4 URLs. */
class PeerTubeMediaAdapter(private val transport:HttpTransport,private val allowLocalHttp:Boolean=false):SiteMediaAdapter {
    override fun supports(pageUrl:String)=PeerTubeIdentity.videoId(pageUrl)!=null
    override suspend fun resolve(draft:DownloadDraft,cancel:TransferCancellation)=withContext(Dispatchers.IO) {
        val source=draft.sourceUrl.orEmpty();val id=PeerTubeIdentity.videoId(source) ?: throw TransferFailure(FailureKind.UNSUPPORTED,"视频身份无效")
        val uri=URI(source);val api=URI(uri.scheme,uri.rawAuthority,"/api/v1/videos/$id",null,null).toString()
        val record=DownloadRecord(name="metadata",mediaUrl=api,userAgent=draft.userAgent,useAccessContext=false)
        val (text,_)=HlsHttpClient(transport,AccessContextProvider{null},allowLocalHttp).text(record,api,cancel)
        cancel.check()
        val data=runCatching{JSONObject(text)}.getOrElse{throw TransferFailure(FailureKind.UNSUPPORTED,"页面不提供受支持的公开视频元数据")}
        if(data.optString("uuid")!=id || data.optBoolean("isLive") || data.optJSONObject("privacy")?.optInt("id")!=1 || !data.optBoolean("downloadEnabled"))throw TransferFailure(FailureKind.UNSUPPORTED,"返回的作品身份或播放类型不符合范围")
        val title=data.optString("name").filterNot(Char::isISOControl).take(180)
        val files=data.optJSONArray("files") ?: throw TransferFailure(FailureKind.UNSUPPORTED,"此作品没有公开完整文件清单")
        if(files.length()>32)throw TransferFailure(FailureKind.UNSUPPORTED,"媒体格式数量超出预算")
        val options=(0 until files.length()).mapNotNull { index ->
            val f=files.optJSONObject(index) ?: return@mapNotNull null
            val url=f.optString("fileUrl")
            if(runCatching{RequestPolicy.validateUrl(url,allowLocalHttp)}.isFailure || MediaClassifier.classify(url)!=MediaKind.FILE ||
                !URI(url).path.lowercase().endsWith(".mp4"))return@mapNotNull null
            val bytes=f.optLong("size",-1);val height=f.optJSONObject("resolution")?.optInt("id") ?: 0
            if(height !in 1..1080 || bytes !in 1..DualTrackMetadata.MAX_VIDEO_BYTES)return@mapNotNull null
            ResolvedMediaOption("${height}p · MP4 文件候选",MediaCandidate(url,MediaKind.FILE,setOf(Evidence.SITE),"video/mp4",bytes,title))
        }.distinctBy{it.candidate.url}
        if(options.isEmpty())throw TransferFailure(FailureKind.UNSUPPORTED,"没有预算内的公开完整 MP4 格式")
        ResolvedMediaOptions(title,options)
    }
}
object PeerTubeIdentity {
    fun videoId(value:String):String?=runCatching {
        RequestPolicy.validateUrl(value,false)
        val uri=URI(value)
        if(uri.port !in setOf(-1,443) || uri.rawFragment!=null)return null
        val full=Regex("/(?:videos/watch|w)/([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})/?").matchEntire(uri.path)?.groupValues?.get(1)?.lowercase()
        if(full!=null)return full
        val short=Regex("/w/([1-9A-HJ-NP-Za-km-z]{22})/?").matchEntire(uri.path)?.groupValues?.get(1) ?: return null
        val alphabet="123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"
        var value58=java.math.BigInteger.ZERO
        for(c in short)value58=value58.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(alphabet.indexOf(c).toLong()))
        if(value58.bitLength()>128)return null
        val hex=value58.toString(16).padStart(32,'0')
        "${hex.substring(0,8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}"
    }.getOrNull()
}

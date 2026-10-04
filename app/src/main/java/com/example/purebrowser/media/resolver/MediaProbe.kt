package com.example.purebrowser.media.resolver

import com.example.purebrowser.download.*
import com.example.purebrowser.media.*
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Locale

/** The prefix is classification evidence, not a complete movie/track validation or download. */
sealed interface MediaProbeResult {
    data class Candidate(val media: MediaCandidate, val prefixBytes: Int) : MediaProbeResult { override fun toString()="MediaProbeCandidate(kind=${media.kind},prefixBytes=$prefixBytes)" }
    data class Unsupported(val reason: String) : MediaProbeResult
}

/** Explicit, anonymous, bounded analysis. Never copies the browser's Cookie/Authorization. */
class MediaProbe(private val transport: HttpTransport, private val allowLocalHttp: Boolean = false) {
    fun analyze(draft: DownloadDraft, cancel: TransferCancellation): MediaProbeResult {
        var url = draft.candidate.url
        var hops = 0
        while (true) {
            cancel.check()
            RequestPolicy.validateUrl(url, allowLocalHttp)
            val headers = mapOf("User-Agent" to draft.userAgent.filterNot(Char::isISOControl).take(1024),
                "Accept-Encoding" to "identity", "Range" to "bytes=0-${PREFIX_LIMIT - 1}")
            transport.open(url, headers, cancel).use { response ->
                if (response.status in setOf(301,302,303,307,308)) {
                    if (hops++ >= MAX_REDIRECTS) throw TransferFailure(FailureKind.HTTP_REJECTED,"媒体分析跳转次数过多")
                    url = RequestPolicy.redirect(url,response.header("Location") ?: throw TransferFailure(FailureKind.HTTP_REJECTED,"媒体分析跳转无目标"),false,allowLocalHttp)
                } else {
                    when (response.status) {
                        401,403 -> throw TransferFailure(FailureKind.ACCESS_CONDITION,"匿名分析无法访问，请返回来源检查访问条件")
                        429 -> throw TransferFailure(FailureKind.HTTP_REJECTED,"网站暂时限制请求，请稍后主动重试")
                        200,206 -> {}
                        else -> throw TransferFailure(FailureKind.HTTP_REJECTED,"网站未提供可分析的媒体响应")
                    }
                    if (!response.header("Content-Encoding").isNullOrBlank() && !response.header("Content-Encoding").equals("identity",true))
                        return MediaProbeResult.Unsupported("媒体分析不支持压缩编码响应")
                    val type=response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                    if (type?.startsWith("audio/")==true) return MediaProbeResult.Unsupported("这是独立音频轨，不能作为完整视频保存")
                    val declaredLength=length(response.header("Content-Length"))
                    val range=if(response.status==206) parseRange(response.header("Content-Range"),declaredLength) else null
                    val prefix=ByteArrayOutputStream()
                    response.body().use { input ->
                        val buffer=ByteArray(8192)
                        while(prefix.size()<PREFIX_LIMIT) {
                            cancel.check()
                            val n=input.read(buffer,0,minOf(buffer.size,PREFIX_LIMIT-prefix.size()))
                            if(n<0)break
                            if(n==0)throw TransferFailure(FailureKind.NETWORK,"媒体分析响应没有继续提供数据")
                            prefix.write(buffer,0,n)
                        }
                    }
                    cancel.check()
                    if((range!=null && range.second!=prefix.size().toLong()) || (declaredLength!=null && declaredLength<=PREFIX_LIMIT && declaredLength!=prefix.size().toLong()))
                        throw TransferFailure(FailureKind.NETWORK,"媒体分析响应提前结束")
                    val bytes=prefix.toByteArray()
                    val text=bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                    val mime=MediaContainer.mime(bytes)
                    if(bytes.size>=12 && String(bytes,4,4,Charsets.US_ASCII)=="ftyp" &&
                        String(bytes,8,4,Charsets.US_ASCII) in setOf("dash","cmfc","cmfs"))
                        return MediaProbeResult.Unsupported("这是分段或独立轨容器线索，需要完整清单或双轨方案，不能当作完整视频")
                    val kind=when {
                        text.startsWith("#EXTM3U") -> MediaKind.HLS
                        mime!=null -> MediaKind.FILE
                        else -> return MediaProbeResult.Unsupported("响应未确认是完整视频容器或受支持 HLS；可能是网页、片段或其他格式")
                    }
                    if (kind==MediaKind.FILE && type in setOf("video/mp4","video/webm") && type!=mime)
                        return MediaProbeResult.Unsupported("响应类型与实际容器不一致")
                    if (MediaClassifier.isFragmentUrl(url)) return MediaProbeResult.Unsupported("地址仍是初始化段或媒体分片，不能当作完整视频")
                    val candidate=draft.candidate.copy(url=url,kind=kind,mimeType=if(kind==MediaKind.HLS)"application/vnd.apple.mpegurl" else mime,
                        sizeBytes=range?.first ?: declaredLength,sources=draft.candidate.sources+Evidence.PROBE)
                    return MediaProbeResult.Candidate(candidate,bytes.size)
                }
            }
        }
    }
    private fun length(raw:String?):Long? {
        if(raw==null)return null
        return raw.toLongOrNull()?.takeIf { it>=0 } ?: throw TransferFailure(FailureKind.HTTP_REJECTED,"媒体响应长度无效")
    }
    private fun parseRange(raw:String?,declared:Long?):Pair<Long,Long> {
        val m=Regex("bytes 0-([0-9]+)/([0-9]+)").matchEntire(raw.orEmpty())
            ?: throw TransferFailure(FailureKind.HTTP_REJECTED,"媒体分析范围响应无效")
        val end=m.groupValues[1].toLongOrNull();val total=m.groupValues[2].toLongOrNull()
        if(end==null || total==null || end<0 || end>=total || end>=PREFIX_LIMIT || (declared!=null && declared!=end+1))
            throw TransferFailure(FailureKind.HTTP_REJECTED,"媒体分析范围长度不一致")
        return total to (end+1)
    }
    companion object { const val PREFIX_LIMIT=65536;const val MAX_REDIRECTS=3 }
}

package com.example.purebrowser.download

import java.net.URI

class TransferFailure(val kind: FailureKind, val safeMessage: String) : Exception(safeMessage)

/** Only policy-created headers reach the transport. URL/query/header values are never diagnostics. */
object RequestPolicy {
    fun origin(value: String): String? = runCatching {
        val u = URI(value)
        require(u.scheme?.lowercase() in setOf("http","https") && !u.host.isNullOrBlank() && u.rawUserInfo == null)
        val port = if(u.port == -1) if(u.scheme.equals("https",true)) 443 else 80 else u.port
        require(port in 1..65535)
        "${u.scheme.lowercase()}://${u.host.lowercase()}:$port"
    }.getOrNull()
    fun sameOrigin(a: String,b: String) = origin(a) != null && origin(a) == origin(b)
    fun validateUrl(value: String, allowLocalHttp: Boolean) {
        val u = runCatching { URI(value) }.getOrNull()
        if(value.length > 8192 || u == null || origin(value) == null ||
            !(u.scheme.equals("https",true) || (allowLocalHttp && u.scheme.equals("http",true) && u.host in setOf("127.0.0.1","10.0.2.2"))))
            throw TransferFailure(FailureKind.UNSUPPORTED,"资源地址不符合本版安全下载范围")
    }
    fun canUseContext(source: String?, frame: String?, reliable: Boolean): Boolean = reliable && source != null &&
        origin(source) != null && (frame == null || sameOrigin(source,frame))
    /** Probe-verified page association: consent may be offered only when the page is a valid web origin sharing the media and frame origin. */
    fun canUseProbedContext(mediaUrl: String,pageUrl: String?,frameUrl: String?):Boolean = pageUrl!=null &&
        canUseContext(pageUrl,frameUrl,true) && sameOrigin(pageUrl,mediaUrl)
    fun cookieEligible(record:DownloadRecord,target:String):Boolean = record.useAccessContext &&
        canUseContext(record.sourceUrl,record.frameUrl,record.reliableSource) && record.mediaUrl!=null &&
        sameOrigin(record.sourceUrl!!,record.mediaUrl) && sameOrigin(record.sourceUrl,target)
    fun referer(source: String, target: String): String? = runCatching {
        val u = URI(source)
        if(sameOrigin(source,target)) URI("${u.scheme}://${u.rawAuthority}${u.rawPath.orEmpty().ifEmpty { "/" }}").toASCIIString()
        else URI("${u.scheme}://${u.rawAuthority}/").toASCIIString()
    }.getOrNull()
    fun headers(record: DownloadRecord,target: String,cookie: String?): Map<String,String> {
        val out=linkedMapOf("User-Agent" to record.userAgent.orEmpty().filterNot { it.isISOControl() }.take(1024),"Accept-Encoding" to "identity")
        if(record.useAccessContext && canUseContext(record.sourceUrl,record.frameUrl,record.reliableSource)) {
            referer(record.sourceUrl!!,target)?.let { out["Referer"]=it }
            if(cookieEligible(record,target) && !cookie.isNullOrBlank()) {
                if(cookie.length>16384 || cookie.any { it.isISOControl() }) throw TransferFailure(FailureKind.ACCESS_CONDITION,"网站会话无法安全用于此下载")
                out["Cookie"]=cookie
            }
        }
        return out
    }
    fun redirect(current:String,location:String,credentialUsed:Boolean,allowLocalHttp:Boolean):String {
        val next=runCatching { URI(current).resolve(location).toString() }.getOrElse {
            throw TransferFailure(FailureKind.HTTP_REJECTED,"服务器返回了无效跳转") }
        validateUrl(next,allowLocalHttp)
        if(URI(current).scheme.equals("https",true) && !URI(next).scheme.equals("https",true))
            throw TransferFailure(FailureKind.UNSUPPORTED,"不允许 HTTPS 降级跳转")
        if(credentialUsed && !sameOrigin(current,next)) throw TransferFailure(FailureKind.ACCESS_CONDITION,"网站会话下载遇到跨源跳转，请返回来源重新发现")
        return next
    }
}

fun interface AccessContextProvider { fun cookieFor(target: String): String? }
class WebsiteAccessContext : AccessContextProvider {
    private val cookies=android.webkit.CookieManager.getInstance()
    override fun cookieFor(target:String):String?=cookies.getCookie(target)
}

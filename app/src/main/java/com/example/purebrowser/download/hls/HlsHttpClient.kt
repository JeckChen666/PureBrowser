package com.example.purebrowser.download.hls

import com.example.purebrowser.download.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class HlsTransientFailure : IOException("临时网络故障")
/** Each independent request chain has its own credential authority. Media3 never does network IO. */
class HlsHttpClient(private val transport:HttpTransport,private val access:AccessContextProvider,private val allowLocalHttp:Boolean) {
    fun <T> get(record:DownloadRecord,initialUrl:String,cancel:TransferCancellation,consume:(HttpResponse,String)->T):T {
        var url=initialUrl;var hops=0;var usedCredential=false
        val chainEligible=RequestPolicy.cookieEligible(record,initialUrl)
        while(true) {
            cancel.check();RequestPolicy.validateUrl(url,allowLocalHttp)
            val cookie=if(chainEligible && RequestPolicy.sameOrigin(initialUrl,url) && RequestPolicy.cookieEligible(record,url)) {
                try { access.cookieFor(url) } catch(_:Exception) { throw TransferFailure(FailureKind.ACCESS_CONDITION,"当前网站会话无法读取") }
            } else null
            val headers=RequestPolicy.headers(record,url,cookie)
            usedCredential=usedCredential || !headers["Cookie"].isNullOrBlank()
            transport.open(url,headers,cancel).use { response ->
                if(response.status in setOf(301,302,303,307,308)) {
                    if(hops++>=5)throw TransferFailure(FailureKind.HTTP_REJECTED,"清单或分片跳转次数过多")
                    url=RequestPolicy.redirect(url,response.header("Location") ?: throw TransferFailure(FailureKind.HTTP_REJECTED,"服务器跳转没有目标"),usedCredential,allowLocalHttp)
                } else {
                    when(response.status) {
                        401,403 -> throw TransferFailure(FailureKind.ACCESS_CONDITION,"当前访问条件不足，请返回来源重新发现")
                        500,502,503,504 -> throw HlsTransientFailure()
                        200 -> {}
                        else -> throw TransferFailure(FailureKind.HTTP_REJECTED,"服务器拒绝清单或分片请求")
                    }
                    val encoding=response.header("Content-Encoding")
                    if(!encoding.isNullOrBlank() && !encoding.equals("identity",true))throw TransferFailure(FailureKind.UNSUPPORTED,"不支持压缩编码的清单或分片响应")
                    return consume(response,url)
                }
            }
        }
    }
    fun text(record:DownloadRecord,url:String,cancel:TransferCancellation):Pair<String,String> = get(record,url,cancel) { response,finalUrl ->
        val total=contentLength(response)
        if(total!=null && total>2*1024*1024)throw TransferFailure(FailureKind.UNSUPPORTED,"清单超过 2 MiB 上限")
        val bytes=ByteArrayOutputStream()
        response.body().use { input ->
            val buffer=ByteArray(8192)
            while(true) { cancel.check();val n=input.read(buffer);if(n<0)break
                if(bytes.size()+n>2*1024*1024)throw TransferFailure(FailureKind.UNSUPPORTED,"清单超过 2 MiB 上限")
                bytes.write(buffer,0,n)
            }
        }
        if(total!=null && bytes.size().toLong()!=total)throw HlsTransientFailure()
        val decoded=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString() }
            catch(_:Exception) { throw TransferFailure(FailureKind.UNSUPPORTED,"清单不是有效 UTF-8 文本") }
        decoded to finalUrl
    }
    companion object {
        fun contentLength(response:HttpResponse):Long? {
            if(response.header("Transfer-Encoding")!=null)return null
            val raw=response.header("Content-Length") ?: return null
            return raw.toLongOrNull()?.takeIf { it>=0 } ?: throw TransferFailure(FailureKind.HTTP_REJECTED,"服务器响应长度无效")
        }
    }
}

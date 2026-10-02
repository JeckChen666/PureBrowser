package com.example.purebrowser.download

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

interface HttpResponse : AutoCloseable {
    val status: Int
    fun header(name: String): String?
    fun body(): InputStream
}
fun interface HttpTransport { fun open(url:String,headers:Map<String,String>,cancel:TransferCancellation):HttpResponse }

class TransferCancellation {
    private val stopped=AtomicBoolean(false)
    @Volatile private var abort: (() -> Unit)?=null
    fun check() { if(stopped.get()) throw java.util.concurrent.CancellationException() }
    fun bind(action:()->Unit) { abort=action; if(stopped.get()) action() }
    fun cancel() { stopped.set(true); runCatching { abort?.invoke() } }
    fun clear() { abort=null }
}
class UrlConnectionTransport : HttpTransport {
    override fun open(url:String,headers:Map<String,String>,cancel:TransferCancellation):HttpResponse {
        cancel.check()
        if(java.net.CookieHandler.getDefault()!=null) throw TransferFailure(FailureKind.ACCESS_CONDITION,"下载环境包含未隔离的 Cookie 配置")
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects=false
        connection.connectTimeout=15000; connection.readTimeout=30000
        connection.requestMethod="GET"; connection.useCaches=false
        headers.forEach { (k,v)->connection.setRequestProperty(k,v) }
        cancel.bind { connection.disconnect() }
        try {
            val code=connection.responseCode
            return object : HttpResponse {
                override val status=code
                override fun header(name:String)=connection.getHeaderField(name)
                override fun body()=connection.inputStream
                override fun close() { connection.disconnect(); cancel.clear() }
            }
        } catch(e:Exception) { connection.disconnect(); cancel.clear(); throw e }
    }
}

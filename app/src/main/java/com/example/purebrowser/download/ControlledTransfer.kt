package com.example.purebrowser.download

import java.io.IOException
import java.util.concurrent.CancellationException

/** One GET chain, then bounded streaming; no HEAD, automatic redirects, resume or hidden retry. */
class ControlledTransfer(
    private val repository:DownloadRepository,
    private val transport:HttpTransport,
    private val access:AccessContextProvider,
) {
    fun run(id:TaskId,cancel:TransferCancellation) {
        val files=repository.files ?: error("未配置文件保存器")
        try {
            val initial=repository.record(id) ?: return
            if(initial.taskStatus !in DownloadRepository.activeStatuses || initial.taskStatus==TaskStatus.WAITING_WIFI)return
            var url=initial.mediaUrl ?: throw TransferFailure(FailureKind.UNSUPPORTED,"记录没有资源地址")
            var hops=0;var usedCredential=false
            while(true) {
                cancel.check();RequestPolicy.validateUrl(url,repository.allowLocalHttp)
                val cookie=if(RequestPolicy.cookieEligible(initial,url)) try { access.cookieFor(url) } catch(_:Exception) { throw TransferFailure(FailureKind.ACCESS_CONDITION,"当前网站会话无法读取") } else null
                val headers=RequestPolicy.headers(initial,url,cookie)
                usedCredential=usedCredential || !headers["Cookie"].isNullOrBlank()
                val response=transport.open(url,headers,cancel)
                response.use {
                    if(response.status in setOf(301,302,303,307,308)) {
                        if(hops++>=5)throw TransferFailure(FailureKind.HTTP_REJECTED,"服务器跳转次数过多")
                        url=RequestPolicy.redirect(url,response.header("Location") ?: throw TransferFailure(FailureKind.HTTP_REJECTED,"服务器跳转没有目标"),usedCredential,repository.allowLocalHttp)
                    } else {
                        if(response.status==401 || response.status==403)throw TransferFailure(FailureKind.ACCESS_CONDITION,"当前访问条件不足")
                        if(response.status!=200)throw TransferFailure(FailureKind.HTTP_REJECTED,"服务器拒绝完整文件请求")
                        val encoding=response.header("Content-Encoding")
                        if(!encoding.isNullOrBlank() && !encoding.equals("identity",true))throw TransferFailure(FailureKind.UNSUPPORTED,"不支持压缩编码的视频响应")
                        val total=if(response.header("Transfer-Encoding")==null)response.header("Content-Length")?.toLongOrNull()?.takeIf { it>=0 } else null
                        update(id) { it.copy(taskStatus=TaskStatus.RUNNING,received=0,expected=total) }
                        val stage=files.stage(id)
                        var count=0L;var lastUpdate=0L;val prefix=java.io.ByteArrayOutputStream(12)
                        try {
                            val stream=try { stage.outputStream() } catch(_:IOException) { throw TransferFailure(FailureKind.STORAGE,"临时视频无法创建") }
                            stream.use { output -> response.body().use { input ->
                                val buffer=ByteArray(65536)
                                while(true) {
                                    cancel.check();val n=input.read(buffer);if(n<0)break
                                    if(prefix.size()<12)prefix.write(buffer,0,minOf(n,12-prefix.size()))
                                    if(prefix.size()>=12 && !DownloadRules.supportedHeader(prefix.toByteArray()))
                                        throw TransferFailure(FailureKind.NOT_VIDEO,"响应不是 MP4/WebM 视频")
                                    try { output.write(buffer,0,n) } catch(_:IOException) { throw TransferFailure(FailureKind.STORAGE,"临时视频无法写入") }
                                    count+=n
                                    if(total!=null && count>total)throw TransferFailure(FailureKind.NOT_VIDEO,"响应长度不一致")
                                    val now=System.currentTimeMillis()
                                    if(now-lastUpdate>=500) { update(id) { it.copy(received=count) };lastUpdate=now }
                                }
                                output.fd.sync()
                            } }
                        } catch(e:TransferFailure) { throw e }
                        if(total!=null && count!=total)throw TransferFailure(FailureKind.NETWORK,"视频响应未完整接收")
                        cancel.check();update(id) { it.copy(taskStatus=TaskStatus.VERIFYING,received=count) }
                        val inspection=files.inspect(stage)
                        if(inspection.format!=FormatCheck.PASSED)throw TransferFailure(FailureKind.NOT_VIDEO,"视频容器或轨道未通过初检")
                        val ext=if(inspection.mimeType=="video/webm") "webm" else "mp4"
                        val record=update(id) { r ->
                            val base=r.name.substringBeforeLast('.',r.name)
                            r.copy(name="$base.$ext",displayName="${r.displayName.substringBeforeLast('.',r.displayName)}.$ext",mimeType=inspection.mimeType,taskStatus=TaskStatus.PUBLISHING)
                        } ?: throw CancellationException()
                        cancel.check()
                        val asset=try { files.publish(record,stage,inspection,{ uri ->
                            cancel.check();update(id) { it.copy(pendingUri=uri) } ?: throw CancellationException()
                        },cancel) } catch(e:CancellationException) { throw e }
                        catch(_:Exception) { throw TransferFailure(FailureKind.STORAGE,"公共视频文件无法完整保存") }
                        cancel.check()
                        if(!repository.complete(id,asset)) { files.delete(asset);throw CancellationException() }
                        files.removeStage(id)
                        return
                    }
                }
            }
        } catch(_:CancellationException) {
            // Coordinator/user has already committed CANCELLED/WAITING/INTERRUPTED.
        } catch(e:TransferFailure) {
            update(id) { it.copy(taskStatus=TaskStatus.FAILED,failure=e.kind) }
        } catch(_:IOException) {
            update(id) { it.copy(taskStatus=TaskStatus.FAILED,failure=FailureKind.NETWORK) }
        } catch(_:Exception) {
            update(id) { it.copy(taskStatus=TaskStatus.FAILED,failure=FailureKind.STORAGE) }
        } finally {
            val r=repository.record(id)
            if(r!=null && r.taskStatus!=TaskStatus.SUCCEEDED) {
                runCatching { files.cleanupPending(r);files.removeStage(id) }
                    .onSuccess { runCatching { repository.change(id) { old ->
                        if(old.taskStatus in DownloadRepository.activeStatuses && old.taskStatus!=TaskStatus.WAITING_WIFI)
                            old.copy(taskStatus=TaskStatus.FAILED,failure=FailureKind.STORAGE,pendingUri=null)
                        else old.copy(pendingUri=null)
                    } } }
            }
        }
    }
    private fun update(id:TaskId,block:(DownloadRecord)->DownloadRecord):DownloadRecord? {
        var changed=false
        val r=repository.change(id) { old ->
            if(old.taskStatus in DownloadRepository.activeStatuses && old.taskStatus!=TaskStatus.WAITING_WIFI) { changed=true;block(old) } else old
        }
        return r?.takeIf { changed }
    }
}

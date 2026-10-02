package com.example.purebrowser.download.hls

import com.example.purebrowser.download.*
import java.io.File
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Bounded two-worker segment scheduler. Only this task's verified MP4 reaches the public store. */
class HlsTransfer(private val repository:DownloadRepository,transport:HttpTransport,access:AccessContextProvider) {
    private val client=HlsHttpClient(transport,access,repository.allowLocalHttp)
    fun run(id:TaskId,cancel:TransferCancellation) {
        val files=repository.files ?: error("文件保存器未配置")
        val workspace=files.hlsWorkspace
        var published:VideoAsset?=null
        try {
            val initial=repository.record(id) ?: return
            if(initial.protocol!=DownloadProtocol.HLS || initial.taskStatus !in DownloadRepository.activeStatuses || initial.taskStatus==TaskStatus.WAITING_WIFI)return
            val plan=try { workspace.load(id) } catch(_:Exception) { throw TransferFailure(FailureKind.INTERRUPTED,"清单计划无法读取，请重新下载") }
            if(plan.entryUrl!=initial.mediaUrl || plan.media.segments.size!=initial.segmentCount || plan.media.durationUs!=initial.plannedDurationUs)
                throw TransferFailure(FailureKind.UNSUPPORTED,"清单计划与任务不一致")
            plan.media.segments.forEach { RequestPolicy.validateUrl(it.url,repository.allowLocalHttp) }
            workspace.cleanSegments(id);workspace.requireSpace(id)
            update(id) { it.copy(taskStatus=TaskStatus.RUNNING,received=0,expected=null,completedSegments=0,safeFailure=null) }
            downloadSegments(id,initial,plan,cancel,workspace)
            cancel.check();update(id) { it.copy(taskStatus=TaskStatus.MUXING) }
            val stage=files.stage(id)
            val lengths=plan.media.segments.sumOf { workspace.segment(id,it.index).length() }
            workspace.requireSpace(id,lengths+lengths/20)
            try {
                TsToMp4Remuxer().remux(plan.media.segments.map { workspace.segment(id,it.index) },stage,plan.media.durationUs,cancel) {
                    cancel.check();workspace.requireSpace(id)
                }
            } catch(e:TransferFailure) { throw e }
            catch(e:CancellationException) { throw e }
            catch(_:IOException) { throw TransferFailure(FailureKind.STORAGE,"MP4 封装文件无法完整写入") }
            catch(_:Exception) { throw TransferFailure(FailureKind.NOT_VIDEO,"音视频封装失败，未保存成品") }
            cancel.check();update(id) { it.copy(taskStatus=TaskStatus.VERIFYING) }
            val inspection=files.inspectHls(stage,plan.media.durationUs)
            if(inspection.format!=FormatCheck.PASSED)throw TransferFailure(FailureKind.NOT_VIDEO,"MP4 轨道、时长或采样未通过校验")
            workspace.requireSpace(id,stage.length())
            val record=update(id) { it.copy(taskStatus=TaskStatus.PUBLISHING,mimeType="video/mp4") }
                ?: throw CancellationException()
            val asset=try { files.publish(record,stage,inspection,{ uri->
                cancel.check();update(id) { it.copy(pendingUri=uri) } ?: throw CancellationException()
            },cancel) } catch(e:CancellationException) { throw e }
            catch(_:Exception) { throw TransferFailure(FailureKind.STORAGE,"公共 MP4 未能完整保存") }
            published=asset;cancel.check()
            if(!repository.complete(id,asset))throw CancellationException()
            published=null
        } catch(_:CancellationException) {
            // User/coordinator has already committed the appropriate terminal/waiting state.
        } catch(e:TransferFailure) {
            update(id) { it.copy(taskStatus=TaskStatus.FAILED,failure=e.kind,safeFailure=e.safeMessage.take(180)) }
        } catch(_:IOException) {
            update(id) { it.copy(taskStatus=TaskStatus.FAILED,failure=FailureKind.NETWORK,safeFailure="网络中断，请重新下载或返回来源") }
        } catch(_:Exception) {
            update(id) { it.copy(taskStatus=TaskStatus.FAILED,failure=FailureKind.STORAGE,safeFailure="任务无法完成，请检查本机存储") }
        } finally {
            published?.let { runCatching { files.delete(it) } }
            val record=repository.record(id)
            runCatching {
                if(record!=null && record.taskStatus!=TaskStatus.SUCCEEDED)files.cleanupPending(record)
                files.removeStage(id)
                if(record?.taskStatus==TaskStatus.WAITING_WIFI)workspace.cleanSegments(id) else workspace.delete(id)
            }.onSuccess {
                if(record!=null)runCatching { repository.change(id) { old ->
                    if(old.taskStatus in DownloadRepository.activeStatuses && old.taskStatus!=TaskStatus.WAITING_WIFI)
                        old.copy(taskStatus=TaskStatus.INTERRUPTED,failure=FailureKind.INTERRUPTED,pendingUri=null)
                    else old.copy(pendingUri=null)
                } }
            }
        }
    }
    private fun downloadSegments(id:TaskId,record:DownloadRecord,plan:HlsDownloadPlan,cancel:TransferCancellation,workspace:HlsWorkspace) {
        val pool=Executors.newFixedThreadPool(2)
        val completed=ExecutorCompletionService<Int>(pool)
        val tokens=java.util.Collections.synchronizedSet(mutableSetOf<TransferCancellation>())
        val received=AtomicLong();val retries=AtomicInteger();val lastPublish=AtomicLong()
        val futures=mutableSetOf<Future<Int>>()
        cancel.bind { synchronized(tokens) { tokens.forEach { it.cancel() } } }
        fun submit(index:Int) {
            val token=TransferCancellation();tokens.add(token)
            val future=completed.submit(Callable {
                try {
                    val segment=plan.media.segments[index];val file=workspace.segment(id,index)
                    var attempt=0
                    while(true) {
                        cancel.check();token.check()
                        try {
                            fetchSegment(record,segment.url,file,token,workspace,id) { bytes ->
                                val sum=received.addAndGet(bytes)
                                if(sum>8L*1024*1024*1024)throw TransferFailure(FailureKind.UNSUPPORTED,"累计传输超过 8 GiB 上限")
                                val now=System.currentTimeMillis();val old=lastPublish.get()
                                if(now-old>500 && lastPublish.compareAndSet(old,now))update(id) { it.copy(received=sum) }
                            }
                            break
                        } catch(e:IOException) {
                            file.delete()
                            // TLS identity failures are not transient. We never loosen certificate validation.
                            if(e is javax.net.ssl.SSLException || attempt>=2 || retries.incrementAndGet()>20)throw e
                            attempt++;val end=System.currentTimeMillis()+if(attempt==1)1000 else 3000
                            while(System.currentTimeMillis()<end) { cancel.check();token.check();Thread.sleep(100) }
                        }
                    }
                    index
                } finally { tokens.remove(token);token.clear() }
            });futures.add(future)
        }
        var next=0;var done=0
        try {
            repeat(minOf(2,plan.media.segments.size)) { submit(next++) }
            while(done<plan.media.segments.size) {
                cancel.check()
                val future=completed.poll(250,TimeUnit.MILLISECONDS) ?: continue
                futures.remove(future)
                try { future.get() } catch(e:ExecutionException) { throw (e.cause as? Exception ?: IOException("分片传输失败")) }
                done++;update(id) { it.copy(completedSegments=done,received=received.get()) }
                if(next<plan.media.segments.size)submit(next++)
            }
        } finally {
            synchronized(tokens) { tokens.forEach { it.cancel() } }
            futures.forEach { it.cancel(true) };pool.shutdownNow()
            // Do not clear/delete a directory until all writers have actually stopped.
            while(!pool.awaitTermination(250,TimeUnit.MILLISECONDS)) { synchronized(tokens) { tokens.forEach { it.cancel() } } }
            cancel.clear()
        }
    }
    private fun fetchSegment(record:DownloadRecord,url:String,file:File,cancel:TransferCancellation,workspace:HlsWorkspace,id:TaskId,onBytes:(Long)->Unit) {
        client.get(record,url,cancel) { response,_ ->
            val length=HlsHttpClient.contentLength(response)
            if(length!=null && length>128L*1024*1024)throw TransferFailure(FailureKind.UNSUPPORTED,"单片超过 128 MiB 上限")
            workspace.requireSpace(id,length ?: 65536)
            val stream=try { file.outputStream() } catch(_:IOException) { throw TransferFailure(FailureKind.STORAGE,"临时分片无法创建") }
            var count=0L
            stream.use { output -> response.body().use { input ->
                val buffer=ByteArray(65536)
                while(true) {
                    cancel.check();val n=input.read(buffer);if(n<0)break
                    count+=n
                    if(count>128L*1024*1024)throw TransferFailure(FailureKind.UNSUPPORTED,"单片超过 128 MiB 上限")
                    workspace.requireSpace(id,n.toLong())
                    try { output.write(buffer,0,n) } catch(_:IOException) { throw TransferFailure(FailureKind.STORAGE,"临时分片写入失败") }
                    onBytes(n.toLong())
                };output.fd.sync()
            } }
            if(length!=null && count!=length)throw HlsTransientFailure()
            if(count<188*5 || count%188!=0L)throw TransferFailure(FailureKind.NOT_VIDEO,"分片不是完整 MPEG-TS 视频")
            file.inputStream().use { input -> val prefix=ByteArray(188*5)
                if(input.read(prefix)!=prefix.size || (0..4).any { prefix[it*188]!=0x47.toByte() })throw TransferFailure(FailureKind.NOT_VIDEO,"分片响应不是 MPEG-TS 视频")
            }
        }
    }
    private fun update(id:TaskId,block:(DownloadRecord)->DownloadRecord):DownloadRecord? {
        var changed=false
        return repository.change(id) { old ->
            if(old.taskStatus in DownloadRepository.activeStatuses && old.taskStatus!=TaskStatus.WAITING_WIFI) { changed=true;block(old) } else old
        }?.takeIf { changed }
    }
}

package com.example.purebrowser.download

import android.app.DownloadManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.webkit.URLUtil
import com.example.purebrowser.browser.BrowserAddress
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import java.util.UUID
import com.example.purebrowser.download.hls.*
import com.example.purebrowser.download.site.DualTrackDownloadPlan

/** One versioned authority, with legacy reads but no new DownloadManager enqueues. IO callers only. */
class DownloadRepository(
    val store:DownloadStore,
    private val backend:DownloadBackend,
    val allowLocalHttp:Boolean=false,
    val files:ManagedFileStore?=null,
) {
    constructor(context:Context):this(DownloadStore(context),AndroidDownloadBackend(context),
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0,ManagedFileStore(context.applicationContext))
    @Volatile var transfersAllowed:Boolean=true
    @Volatile private var privacyGeneration:Long=0
    private class RequestLease(val token:TransferCancellation) {
        val done=java.util.concurrent.CountDownLatch(1)
    }
    internal class DualTrackRequest(val plan:DualTrackDownloadPlan,val requestRecord:DownloadRecord) {
        override fun toString()="DualTrackRequest(redacted)"
    }
    // Transient access material is never serialized, and a plan can be consumed only once.
    private val dualTrackRequests=mutableMapOf<TaskId,DualTrackRequest>()
    internal fun takeDualTrackRequest(id:TaskId):DualTrackRequest?=synchronized(DownloadStore.transactionLock) {
        if(transfersAllowed)dualTrackRequests.remove(id) else null
    }
    internal fun hasFreshDualTrackRequest(id:TaskId):Boolean=synchronized(DownloadStore.transactionLock) {
        transfersAllowed && dualTrackRequests.containsKey(id)
    }
    internal fun wakeFreshDualTrack(id:TaskId):Boolean=synchronized(DownloadStore.transactionLock) {
        val r=record(id) ?: return@synchronized false
        if(!transfersAllowed || r.protocol!=DownloadProtocol.DUAL_TRACK || !dualTrackRequests.containsKey(id) ||
            transferInFlight(id) || r.received!=0L || r.taskStatus !in TaskControlRules.waiting)return@synchronized false
        change(id) { it.copy(taskStatus=TaskStatus.QUEUED,pauseReason=null,failure=null,safeFailure=null) };true
    }
    private val requestLeases=java.util.concurrent.ConcurrentHashMap<String,RequestLease>()
    internal var retryTransport:HttpTransport=UrlConnectionTransport()
    fun requestGeneration():Long=synchronized(DownloadStore.transactionLock) {
        check(transfersAllowed) { "网站数据正在清理，请稍后操作" };privacyGeneration
    }
    fun beginPrivacyExclusion() {
        val leases=synchronized(DownloadStore.transactionLock) {
            transfersAllowed=false;privacyGeneration++;dualTrackRequests.clear();requestLeases.values.toList()
        }
        leases.forEach { it.token.cancel() }
    }
    fun awaitRequestQuiescence() { requestLeases.values.toList().forEach { it.done.await() } }
    fun guardedTransport(delegate:HttpTransport):HttpTransport=HttpTransport { url,headers,token ->
        val key=java.util.UUID.randomUUID().toString()
        val lease=synchronized(DownloadStore.transactionLock) {
            check(transfersAllowed) { "网站数据正在清理，请稍后请求" }
            token.check();RequestLease(token).also { requestLeases[key]=it }
        }
        fun release() { requestLeases.remove(key);lease.done.countDown() }
        try {
            val response=delegate.open(url,headers,token)
            object:HttpResponse {
                override val status=response.status
                override fun header(name:String)=response.header(name)
                override fun body()=response.body()
                override fun close() { try { response.close() } finally { release() } }
            }
        } catch(e:Exception) { release();throw e }
    }
    var captureStop:(TaskId)->(() -> Unit)={ id -> { stopTransfer(id) } }
    var stopTransfer:(TaskId)->Unit = {}
    var transferInFlight:(TaskId)->Boolean = { false }
    private val legacy=LegacyDownloadRepository(store,backend,allowLocalHttp)
    fun takeNotice()=legacy.takeNotice() ?: store.takeNotice()
    fun records():List<DownloadRecord> = synchronized(DownloadStore.transactionLock) { store.load().records }
    fun record(id:TaskId):DownloadRecord?=records().firstOrNull { it.recordId==id }
    fun enqueue(candidate:MediaCandidate,userAgent:String,wifiOnly:Boolean):TaskId=enqueue(DownloadDraft(candidate,userAgent),wifiOnly)
    fun enqueue(draft:DownloadDraft,wifiOnly:Boolean,fileName:String?=null,retryOf:String?=null,hlsPlan:HlsDownloadPlan?=null,expectedPrivacyGeneration:Long?=null):TaskId=synchronized(DownloadStore.transactionLock) {
        check(transfersAllowed && (expectedPrivacyGeneration==null || expectedPrivacyGeneration==privacyGeneration)) { "网站会话已清理，请重新确认下载" }
        val dualPlan=draft.dualTrackPlan
        require(dualPlan==null || hlsPlan==null) { "不能混用 HLS 与双轨方案" }
        if(dualPlan==null) {
            require(draft.candidate.kind in setOf(MediaKind.FILE,MediaKind.UNKNOWN,MediaKind.HLS))
            require((draft.candidate.kind==MediaKind.HLS)==(hlsPlan!=null)) { "HLS 需要已确认的清单计划" }
        } else {
            dualPlan.validate(allowLocalHttp)
            check(files!=null) { "双轨临时存储未配置" }
        }
        if(hlsPlan!=null) { require(hlsPlan.entryUrl==draft.candidate.url);RequestPolicy.validateUrl(hlsPlan.playlistUrl,allowLocalHttp)
            hlsPlan.media.segments.forEach { RequestPolicy.validateUrl(it.url,allowLocalHttp) }
        }
        if(dualPlan==null)RequestPolicy.validateUrl(draft.candidate.url,allowLocalHttp)
        val data=store.load();check(store.writable)
        check(data.records.size<DownloadRules.MAX_RECORDS) { "下载记录已达 200 条，请移除不再需要的记录" }
        val guessed=fileName ?: if(dualPlan!=null) "video.mp4" else URLUtil.guessFileName(draft.candidate.url,null,draft.candidate.mimeType)
        val id=UUID.randomUUID().toString()
        val display=DownloadRules.safeFileName(if(hlsPlan!=null || dualPlan!=null) guessed.substringBeforeLast('.',guessed)+".mp4" else guessed)
        val context=draft.useAccessContext && RequestPolicy.canUseContext(draft.sourceUrl,draft.frameUrl,draft.reliableSource)
        val record=DownloadRecord(recordId=id,name="${id.take(8)}_$display",displayName=display,mediaUrl=if(dualPlan==null)draft.candidate.url else null,
            sourceUrl=if(dualPlan==null)draft.sourceUrl?.takeIf(BrowserAddress::isWebUrl) else dualPlan.safeSourceUrl,sourceTitle=draft.sourceTitle?.take(180),
            createdAt=System.currentTimeMillis(),userAgent=draft.userAgent.filterNot { it.isISOControl() }.take(1024),
            wifiOnly=wifiOnly,mimeType=if(dualPlan!=null)"video/mp4" else draft.candidate.mimeType?.take(120),retryOf=retryOf,sourceTabId=draft.sourceTabId,
            sourceGeneration=draft.sourceGeneration,transfer=TransferType.CONTROLLED,useAccessContext=context,
            frameUrl=if(dualPlan==null)draft.frameUrl else null,reliableSource=draft.reliableSource,
            protocol=when { dualPlan!=null->DownloadProtocol.DUAL_TRACK;hlsPlan!=null->DownloadProtocol.HLS;else->DownloadProtocol.DIRECT },
            hlsPlaylistUrl=hlsPlan?.variant?.url ?: hlsPlan?.playlistUrl,hlsWidth=hlsPlan?.variant?.width,hlsHeight=hlsPlan?.variant?.height,
            hlsBandwidth=hlsPlan?.variant?.bandwidth,plannedDurationUs=dualPlan?.durationUs ?: hlsPlan?.media?.durationUs,segmentCount=hlsPlan?.media?.segments?.size,resumeAvailable=hlsPlan!=null,dualTrackMetadata=dualPlan?.metadata(),expected=dualPlan?.metadata()?.expectedBytes)
        if(hlsPlan!=null) (files?.hlsWorkspace ?: error("HLS 临时存储未配置")).save(id,hlsPlan)
        try { store.save(data.copy(records=listOf(record)+data.records)) }
        catch(e:Exception) { if(hlsPlan!=null)runCatching { files?.hlsWorkspace?.delete(id) };throw e }
        if(dualPlan!=null)dualTrackRequests[id]=DualTrackRequest(dualPlan,record.copy(mediaUrl=dualPlan.videoUrl,
            sourceUrl=draft.sourceUrl?.takeIf(BrowserAddress::isWebUrl),frameUrl=draft.frameUrl))
        id
    }
    fun change(id:TaskId,block:(DownloadRecord)->DownloadRecord):DownloadRecord?=synchronized(DownloadStore.transactionLock) {
        val data=store.load();val old=data.records.firstOrNull { it.recordId==id } ?: return@synchronized null
        val next=block(old);store.save(data.copy(records=data.records.map { if(it.recordId==id)next else it }))
        if(next.protocol==DownloadProtocol.DUAL_TRACK && next.taskStatus !in setOf(TaskStatus.QUEUED,TaskStatus.WAITING_WIFI,TaskStatus.WAITING_NETWORK,TaskStatus.RUNNING))dualTrackRequests.remove(id)
        next
    }
    fun complete(id:TaskId,asset:VideoAsset):Boolean=synchronized(DownloadStore.transactionLock) {
        val data=store.load();val record=data.records.firstOrNull { it.recordId==id } ?: return@synchronized false
        if(record.taskStatus!=TaskStatus.PUBLISHING) return@synchronized false
        store.save(data.copy(records=data.records.map { if(it.recordId==id) it.copy(taskStatus=TaskStatus.SUCCEEDED,pendingUri=null,failure=null) else it },
            assets=data.assets.filterNot { it.recordId==id }+asset.copy(displayName=record.displayName)));true
    }
    fun snapshot()=stateSnapshot().tasks
    fun stateSnapshot():DownloadState=synchronized(DownloadStore.transactionLock) {
        val old=legacy.stateSnapshot();val data=store.load()
        val assets=data.assets.map { asset ->
            if(asset.location==AssetLocation.SYSTEM_DOWNLOAD) asset else {
                val access=files?.access(asset) ?: FileAccess(FileAvailability.UNKNOWN)
                asset.copy(availability=access.availability,sizeBytes=access.sizeBytes ?: asset.sizeBytes)
            }
        }
        if(assets!=data.assets && store.writable)store.save(data.copy(assets=assets))
        val new=data.records.filter { it.transfer==TransferType.CONTROLLED }.map { r ->
            val asset=assets.firstOrNull { it.recordId==r.recordId }
            val usable=asset?.format==FormatCheck.PASSED && asset.availability==FileAvailability.AVAILABLE
            val status=when(r.taskStatus) {
                TaskStatus.QUEUED->DownloadManager.STATUS_PENDING
                TaskStatus.WAITING_WIFI,TaskStatus.WAITING_NETWORK,TaskStatus.PAUSING,TaskStatus.PAUSED->DownloadManager.STATUS_PAUSED
                TaskStatus.RUNNING,TaskStatus.MUXING,TaskStatus.VERIFYING,TaskStatus.PUBLISHING->DownloadManager.STATUS_RUNNING
                TaskStatus.SUCCEEDED->DownloadManager.STATUS_SUCCESSFUL
                else->DownloadManager.STATUS_FAILED
            }
            DownloadItem(r.recordId,r.name,status,r.received,r.expected ?: -1,detail(r,asset),usable,
                recordId=r.recordId,format=asset?.format ?: FormatCheck.NOT_CHECKED,availability=asset?.availability ?: FileAvailability.UNKNOWN,
                sourceUrl=r.sourceUrl,displayName=r.displayName,createdAt=r.createdAt,wifiOnly=r.wifiOnly,
                canRetry=r.protocol!=DownloadProtocol.DUAL_TRACK && !usable && r.taskStatus in setOf(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.INTERRUPTED,TaskStatus.SUCCEEDED),
                retryOf=r.retryOf,sourceTitle=r.sourceTitle,cancelled=r.taskStatus==TaskStatus.CANCELLED,
                taskStatus=r.taskStatus,failure=r.failure,useAccessContext=r.useAccessContext,protocol=r.protocol,segmentCount=r.segmentCount,completedSegments=r.completedSegments,pauseReason=r.pauseReason,
                canPause=TaskControlRules.canPause(r),canResume=TaskControlRules.canResume(r) && !transferInFlight(r.recordId),
                cacheBytes=files?.cacheBytes(r.recordId) ?: 0L)
        }
        DownloadState((new+old.tasks).sortedByDescending { it.createdAt ?: 0 },assets)
    }
    private fun detail(r:DownloadRecord,a:VideoAsset?):String=when(r.taskStatus) {
        TaskStatus.PAUSING->if(r.protocol==DownloadProtocol.DUAL_TRACK)"正在停止并清理双轨临时文件；没有续传检查点" else "正在停止写入并保存检查点，请稍候"
        TaskStatus.PAUSED->if(r.pauseReason==PauseReason.RECOVERY)"重新打开后已对账，请主动继续" else if(r.resumeAvailable || r.received==0L)"已暂停；可验证缓存后继续" else "已停止，没有可靠续传缓存，请重新下载"
        TaskStatus.WAITING_NETWORK->"等待网络；恢复网络后返回应用继续"
        TaskStatus.QUEUED->"排队等待下载";TaskStatus.WAITING_WIFI->"等待 Wi-Fi；连接后返回应用可继续尝试"
        TaskStatus.RUNNING->if(r.protocol==DownloadProtocol.DUAL_TRACK)"正在依次下载双轨；尚未保存成品，不支持部分续传" else if(r.protocol==DownloadProtocol.HLS)"正在下载分片 ${r.completedSegments}/${r.segmentCount ?: 0}" else "正在保存视频";
        TaskStatus.MUXING->"正在封装独立 MP4，尚未保存成品";TaskStatus.VERIFYING->"正在检查视频格式";TaskStatus.PUBLISHING->"正在写入公共下载目录"
        TaskStatus.SUCCEEDED->when(a?.availability) { FileAvailability.AVAILABLE->"视频已保存";FileAvailability.MISSING->"文件已丢失";else->"文件暂不可读" }
        TaskStatus.CANCELLED->"已取消，可以重新下载";TaskStatus.INTERRUPTED->if(r.failure==FailureKind.SYSTEM_LIMIT)"系统未允许继续下载；返回应用后主动恢复或重新下载" else if(r.resumeAvailable)"下载已中断；可验证进度后继续" else "下载已中断，没有可靠续传检查点，请重新下载"
        TaskStatus.FAILED->r.safeFailure ?: when(r.failure) {
            FailureKind.NETWORK->"网络连接失败，请检查网络后重新下载"
            FailureKind.ACCESS_CONDITION->"当前访问条件不足，请返回来源登录或重新发现"
            FailureKind.HTTP_REJECTED->"服务器拒绝了下载请求，可返回来源重新发现"
            FailureKind.NOT_VIDEO->"响应不是支持的 MP4/WebM 视频"
            FailureKind.UNSUPPORTED->"本版不支持此地址、协议或响应形式"
            FailureKind.STORAGE->"文件未能保存，请检查权限与可用空间"
            FailureKind.SYSTEM_LIMIT->"系统未允许继续下载，请返回应用重试"
            else->"下载未完成，请返回来源重新尝试"
        }
    }
    fun retry(id:TaskId,fallbackAgent:String,defaultWifiOnly:Boolean,useContext:Boolean?=null):TaskId {
        val token=TransferCancellation()
        val key=java.util.UUID.randomUUID().toString()
        val lease=RequestLease(token)
        val generation=synchronized(DownloadStore.transactionLock) {
            check(transfersAllowed) { "网站数据正在清理，请重新确认" }
            requestLeases[key]=lease;privacyGeneration
        }
        try {
        check(snapshot().firstOrNull { it.id==id }?.canRetry==true) { "资源暂时不能重试，请返回来源" }
        val r=record(id) ?: error("记录不存在")
        if(r.protocol==DownloadProtocol.HLS) {
            val draft=DownloadDraft(MediaCandidate(r.mediaUrl ?: error("记录没有入口"),MediaKind.HLS,emptySet(),r.mimeType,frameUrl=r.frameUrl,reliableSource=r.reliableSource),
                r.userAgent ?: fallbackAgent,r.sourceUrl,r.sourceTitle,r.sourceTabId,r.sourceGeneration,
                useAccessContext=useContext ?: r.useAccessContext,reliableSource=r.reliableSource)
            val resolver=HlsResolver(guardedTransport(retryTransport),WebsiteAccessContext(),allowLocalHttp)
            val options=resolver.resolveEntry(draft,token)
            val variant=when(val p=options.playlist) {
                is HlsPlaylist.Master->p.variants.firstOrNull { it.supported && it.url==r.hlsPlaylistUrl }
                    ?: throw TransferFailure(FailureKind.UNSUPPORTED,"原档位已变化，请返回来源重新选择")
                is HlsPlaylist.Media->null
            }
            val plan=resolver.resolvePlan(draft,options,variant,token)
            return enqueue(draft,r.wifiOnly ?: defaultWifiOnly,r.displayName,r.recordId,plan,expectedPrivacyGeneration=generation)
        }
        return enqueue(DownloadDraft(MediaCandidate(r.mediaUrl ?: error("旧记录没有地址"),MediaKind.FILE,emptySet(),r.mimeType,
            frameUrl=r.frameUrl,reliableSource=r.reliableSource),r.userAgent ?: fallbackAgent,r.sourceUrl,r.sourceTitle,r.sourceTabId,r.sourceGeneration,
            useAccessContext=useContext ?: r.useAccessContext,reliableSource=r.reliableSource),r.wifiOnly ?: defaultWifiOnly,r.displayName,r.recordId,expectedPrivacyGeneration=generation)
        } finally { requestLeases.remove(key);lease.done.countDown() }
    }
    fun rename(id:TaskId,title:String)=synchronized(DownloadStore.transactionLock) {
        val value=title.trim();require(value.isNotBlank() && value.length<=180 && value.none { it.isISOControl() }) { "请输入 1–180 字的显示名称" }
        val data=store.load();require(data.records.any { it.recordId==id })
        store.save(data.copy(records=data.records.map { if(it.recordId==id)it.copy(displayName=value) else it },assets=data.assets.map { if(it.recordId==id)it.copy(displayName=value) else it }))
    }
    fun pause(id:TaskId,reason:PauseReason=PauseReason.USER) {
        var stop:(()->Unit)?=null
        change(id) { old ->
            check(TaskControlRules.canPause(old)) { "此阶段或资源不支持暂停；可取消后重新下载" }
            stop=captureStop(id);old.copy(taskStatus=if(transferInFlight(id))TaskStatus.PAUSING else TaskStatus.PAUSED,pauseReason=reason)
        }
        stop?.invoke()
    }
    fun queueResume(id:TaskId) {
        val generation=requestGeneration()
        check(transfersAllowed) { "网站数据正在清理，请稍后继续" }
        check(!transferInFlight(id)) { "任务仍在停止，请稍后继续" }
        val r=record(id) ?: error("记录不存在")
        check(TaskControlRules.canResume(r)) { "没有可靠进度，请重新下载或返回来源" }
        val storage=files ?: error("未配置临时文件存储")
        val valid=if(r.protocol==DownloadProtocol.HLS)storage.hlsWorkspace.hasResumeData(id)
            else (r.received==0L && !storage.stage(id).exists()) || storage.directCheckpoints.hasValid(id,storage.stage(id))
        check(valid) { "恢复缓存已丢失或损坏，请重新下载" }
        change(id) { old ->
            check(transfersAllowed && generation==privacyGeneration && old==r && !transferInFlight(id) && TaskControlRules.canResume(old)) { "状态已变化，请刷新后重试" }
            old.copy(taskStatus=TaskStatus.QUEUED,failure=null,safeFailure=null,pauseReason=null)
        }
    }
    fun clearStoppedTemporary():Int=synchronized(DownloadStore.transactionLock) {
        var count=0
        records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus !in activeStatuses }.forEach { r ->
            if(!transferInFlight(r.recordId)) {
                files?.clearPrivate(r.recordId)
                if(r.taskStatus!=TaskStatus.SUCCEEDED)change(r.recordId) { old -> old.copy(resumeAvailable=false,
                    taskStatus=if(old.taskStatus==TaskStatus.CANCELLED)old.taskStatus else TaskStatus.INTERRUPTED,
                    pauseReason=PauseReason.SOURCE_CHANGED,safeFailure="恢复缓存已清理，请重新下载") }
                count++
            }
        }
        count
    }
    fun cancel(id:TaskId) {
        val r=record(id) ?: error("记录不存在")
        if(r.transfer==TransferType.SYSTEM) { legacy.cancel(r.systemId!!);return }
        check(TaskControlRules.canCancel(r)) { "任务已经结束，请刷新状态" }
        var cancelledNow=false
        change(id) { old -> if(TaskControlRules.canCancel(old)) { cancelledNow=true;old.copy(taskStatus=TaskStatus.CANCELLED,cancelled=true,resumeAvailable=false,pauseReason=null) } else old }
        if(cancelledNow) { stopTransfer(id);if(!transferInFlight(id)) files?.clearPrivate(id) }
    }
    fun forgetRecord(id:TaskId)=synchronized(DownloadStore.transactionLock) {
        val r=record(id) ?: error("记录不存在")
        if(r.transfer==TransferType.SYSTEM) { legacy.forgetRecord(r.systemId!!);return@synchronized }
        check(r.taskStatus !in activeStatuses) { "请先取消正在下载的任务" }
        check(!transferInFlight(id)) { "任务正在停止，请稍后移除记录" }
        files?.cleanupPending(r);files?.clearPrivate(id);dualTrackRequests.remove(id)
        val d=store.load();store.save(d.copy(records=d.records.filterNot { it.recordId==id },assets=d.assets.filterNot { it.recordId==id }))
    }
    fun deleteFile(id:TaskId)=synchronized(DownloadStore.transactionLock) {
        val r=record(id) ?: error("记录不存在")
        if(r.transfer==TransferType.SYSTEM) { legacy.deleteFile(r.systemId!!);return@synchronized }
        check(r.taskStatus !in activeStatuses) { "请先取消正在下载的任务" }
        check(!transferInFlight(id)) { "任务正在结束，请稍后删除成品" }
        val asset=store.load().assets.firstOrNull { it.recordId==id } ?: error("记录没有可删除成品")
        check(files?.delete(asset)==true) { "未能确认文件已删除，记录已保留" };forgetRecord(id)
    }
    fun remove(id:TaskId) { val r=record(id) ?: return; if(r.transfer==TransferType.SYSTEM) { legacy.remove(r.systemId!!);return };if(r.taskStatus in activeStatuses)cancel(id) else deleteFile(id) }
    fun fileUri(id:TaskId):Uri?=synchronized(DownloadStore.transactionLock) {
        val r=record(id) ?: return@synchronized null
        if(r.transfer==TransferType.SYSTEM) return@synchronized legacy.fileUri(r.systemId!!)
        val asset=stateSnapshot().assets.firstOrNull { it.recordId==id && it.format==FormatCheck.PASSED && it.availability==FileAvailability.AVAILABLE }
            ?: return@synchronized null
        if(files?.owned(asset)!=true) null else Uri.parse(asset.uri)
    }
    fun mimeType(id:TaskId)=synchronized(DownloadStore.transactionLock) {
        store.load().assets.firstOrNull { it.recordId==id }?.mimeType ?: "video/*"
    }
    private fun legacyId(systemId:Long):TaskId=records().firstOrNull { it.systemId==systemId }?.recordId ?: error("历史任务不存在")
    @Deprecated("Only for legacy regression tools; UI uses TaskId") fun record(id:Long)=records().firstOrNull { it.systemId==id }
    @Deprecated("Only for legacy regression tools; UI uses TaskId") fun cancel(id:Long)=cancel(legacyId(id))
    @Deprecated("Only for legacy regression tools; UI uses TaskId") fun forgetRecord(id:Long)=forgetRecord(legacyId(id))
    @Deprecated("Only for legacy regression tools; UI uses TaskId") fun deleteFile(id:Long)=deleteFile(legacyId(id))
    @Deprecated("Only for legacy regression tools; UI uses TaskId") fun rename(id:Long,title:String)=rename(legacyId(id),title)
    @Deprecated("Only for legacy regression tools; UI uses TaskId") fun retry(id:Long,agent:String,wifi:Boolean)=retry(legacyId(id),agent,wifi)
    @Deprecated("Only for legacy regression tools; UI uses TaskId") fun fileUri(id:Long)=records().firstOrNull { it.systemId==id }?.let { fileUri(it.recordId) }
    companion object { val activeStatuses=setOf(TaskStatus.QUEUED,TaskStatus.WAITING_WIFI,TaskStatus.WAITING_NETWORK,TaskStatus.PAUSING,TaskStatus.RUNNING,TaskStatus.MUXING,TaskStatus.VERIFYING,TaskStatus.PUBLISHING) }
}

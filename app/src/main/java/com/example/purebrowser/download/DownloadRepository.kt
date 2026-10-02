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

/** One versioned authority, with legacy reads but no new DownloadManager enqueues. IO callers only. */
class DownloadRepository(
    val store:DownloadStore,
    private val backend:DownloadBackend,
    val allowLocalHttp:Boolean=false,
    val files:ManagedFileStore?=null,
) {
    constructor(context:Context):this(DownloadStore(context),AndroidDownloadBackend(context),
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0,ManagedFileStore(context.applicationContext))
    var stopTransfer:(TaskId)->Unit = {}
    private val legacy=LegacyDownloadRepository(store,backend,allowLocalHttp)
    fun takeNotice()=legacy.takeNotice() ?: store.takeNotice()
    fun records():List<DownloadRecord> = synchronized(DownloadStore.transactionLock) { store.load().records }
    fun record(id:TaskId):DownloadRecord?=records().firstOrNull { it.recordId==id }
    fun enqueue(candidate:MediaCandidate,userAgent:String,wifiOnly:Boolean):TaskId=enqueue(DownloadDraft(candidate,userAgent),wifiOnly)
    fun enqueue(draft:DownloadDraft,wifiOnly:Boolean,fileName:String?=null,retryOf:String?=null):TaskId=synchronized(DownloadStore.transactionLock) {
        require(draft.candidate.kind in setOf(MediaKind.FILE,MediaKind.UNKNOWN)) { "本版不支持保存此协议" }
        RequestPolicy.validateUrl(draft.candidate.url,allowLocalHttp)
        val data=store.load();check(store.writable)
        check(data.records.size<DownloadRules.MAX_RECORDS) { "下载记录已达 200 条，请移除不再需要的记录" }
        val guessed=fileName ?: URLUtil.guessFileName(draft.candidate.url,null,draft.candidate.mimeType)
        val id=UUID.randomUUID().toString()
        val display=DownloadRules.safeFileName(guessed)
        val context=draft.useAccessContext && RequestPolicy.canUseContext(draft.sourceUrl,draft.frameUrl,draft.reliableSource)
        val record=DownloadRecord(recordId=id,name="${id.take(8)}_$display",displayName=display,mediaUrl=draft.candidate.url,
            sourceUrl=draft.sourceUrl?.takeIf(BrowserAddress::isWebUrl),sourceTitle=draft.sourceTitle?.take(180),
            createdAt=System.currentTimeMillis(),userAgent=draft.userAgent.filterNot { it.isISOControl() }.take(1024),
            wifiOnly=wifiOnly,mimeType=draft.candidate.mimeType?.take(120),retryOf=retryOf,sourceTabId=draft.sourceTabId,
            sourceGeneration=draft.sourceGeneration,transfer=TransferType.CONTROLLED,useAccessContext=context,
            frameUrl=draft.frameUrl,reliableSource=draft.reliableSource)
        store.save(data.copy(records=listOf(record)+data.records));id
    }
    fun change(id:TaskId,block:(DownloadRecord)->DownloadRecord):DownloadRecord?=synchronized(DownloadStore.transactionLock) {
        val data=store.load();val old=data.records.firstOrNull { it.recordId==id } ?: return@synchronized null
        val next=block(old);store.save(data.copy(records=data.records.map { if(it.recordId==id)next else it }));next
    }
    fun complete(id:TaskId,asset:VideoAsset):Boolean=synchronized(DownloadStore.transactionLock) {
        val data=store.load();val record=data.records.firstOrNull { it.recordId==id } ?: return@synchronized false
        if(record.taskStatus!=TaskStatus.PUBLISHING) return@synchronized false
        store.save(data.copy(records=data.records.map { if(it.recordId==id) it.copy(taskStatus=TaskStatus.SUCCEEDED,pendingUri=null,failure=null) else it },
            assets=data.assets.filterNot { it.recordId==id }+asset));true
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
                TaskStatus.WAITING_WIFI->DownloadManager.STATUS_PAUSED
                TaskStatus.RUNNING,TaskStatus.VERIFYING,TaskStatus.PUBLISHING->DownloadManager.STATUS_RUNNING
                TaskStatus.SUCCEEDED->DownloadManager.STATUS_SUCCESSFUL
                else->DownloadManager.STATUS_FAILED
            }
            DownloadItem(r.recordId,r.name,status,r.received,r.expected ?: -1,detail(r,asset),usable,
                recordId=r.recordId,format=asset?.format ?: FormatCheck.NOT_CHECKED,availability=asset?.availability ?: FileAvailability.UNKNOWN,
                sourceUrl=r.sourceUrl,displayName=r.displayName,createdAt=r.createdAt,wifiOnly=r.wifiOnly,
                canRetry=!usable && r.taskStatus in setOf(TaskStatus.FAILED,TaskStatus.CANCELLED,TaskStatus.INTERRUPTED,TaskStatus.SUCCEEDED),
                retryOf=r.retryOf,sourceTitle=r.sourceTitle,cancelled=r.taskStatus==TaskStatus.CANCELLED,
                taskStatus=r.taskStatus,failure=r.failure,useAccessContext=r.useAccessContext)
        }
        DownloadState((new+old.tasks).sortedByDescending { it.createdAt ?: 0 },assets)
    }
    private fun detail(r:DownloadRecord,a:VideoAsset?):String=when(r.taskStatus) {
        TaskStatus.QUEUED->"排队等待下载";TaskStatus.WAITING_WIFI->"等待 Wi-Fi；连接后返回应用可继续尝试"
        TaskStatus.RUNNING->"正在保存视频";TaskStatus.VERIFYING->"正在检查视频格式";TaskStatus.PUBLISHING->"正在写入公共下载目录"
        TaskStatus.SUCCEEDED->when(a?.availability) { FileAvailability.AVAILABLE->"视频已保存";FileAvailability.MISSING->"文件已丢失";else->"文件暂不可读" }
        TaskStatus.CANCELLED->"已取消，可以重新下载";TaskStatus.INTERRUPTED->"下载已中断，请重新下载"
        TaskStatus.FAILED->when(r.failure) {
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
        check(snapshot().firstOrNull { it.id==id }?.canRetry==true) { "资源暂时不能重试，请返回来源" }
        val r=record(id) ?: error("记录不存在")
        return enqueue(DownloadDraft(MediaCandidate(r.mediaUrl ?: error("旧记录没有地址"),MediaKind.FILE,emptySet(),r.mimeType,
            frameUrl=r.frameUrl,reliableSource=r.reliableSource),r.userAgent ?: fallbackAgent,r.sourceUrl,r.sourceTitle,r.sourceTabId,r.sourceGeneration,
            useAccessContext=useContext ?: r.useAccessContext,reliableSource=r.reliableSource),r.wifiOnly ?: defaultWifiOnly,r.displayName,r.recordId)
    }
    fun rename(id:TaskId,title:String)=synchronized(DownloadStore.transactionLock) {
        val value=title.trim();require(value.isNotBlank() && value.length<=180 && value.none { it.isISOControl() }) { "请输入 1–180 字的显示名称" }
        val data=store.load();require(data.records.any { it.recordId==id })
        store.save(data.copy(records=data.records.map { if(it.recordId==id)it.copy(displayName=value) else it },assets=data.assets.map { if(it.recordId==id)it.copy(displayName=value) else it }))
    }
    fun cancel(id:TaskId) {
        val r=record(id) ?: error("记录不存在")
        if(r.transfer==TransferType.SYSTEM) { legacy.cancel(r.systemId!!);return }
        check(r.taskStatus in activeStatuses) { "任务已经结束，请刷新状态" }
        change(id) { it.copy(taskStatus=TaskStatus.CANCELLED,cancelled=true) };stopTransfer(id)
    }
    fun forgetRecord(id:TaskId)=synchronized(DownloadStore.transactionLock) {
        val r=record(id) ?: error("记录不存在")
        if(r.transfer==TransferType.SYSTEM) { legacy.forgetRecord(r.systemId!!);return@synchronized }
        check(r.taskStatus !in activeStatuses) { "请先取消正在下载的任务" }
        files?.cleanupPending(r);files?.removeStage(id)
        val d=store.load();store.save(d.copy(records=d.records.filterNot { it.recordId==id },assets=d.assets.filterNot { it.recordId==id }))
    }
    fun deleteFile(id:TaskId)=synchronized(DownloadStore.transactionLock) {
        val r=record(id) ?: error("记录不存在")
        if(r.transfer==TransferType.SYSTEM) { legacy.deleteFile(r.systemId!!);return@synchronized }
        check(r.taskStatus !in activeStatuses) { "请先取消正在下载的任务" }
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
    companion object { val activeStatuses=setOf(TaskStatus.QUEUED,TaskStatus.WAITING_WIFI,TaskStatus.RUNNING,TaskStatus.VERIFYING,TaskStatus.PUBLISHING) }
}

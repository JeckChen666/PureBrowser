package com.example.purebrowser.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Process-scoped ownership. Services and screens use the same coordinator, not ViewModel jobs. */
class DownloadRuntime private constructor(private val app:Context) {
    val repository=DownloadRepository(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val running=ConcurrentHashMap<TaskId,Pair<TransferCancellation,Job>>()
    private val transfer=ControlledTransfer(repository,UrlConnectionTransport(),WebsiteAccessContext())
    private val hlsTransfer=com.example.purebrowser.download.hls.HlsTransfer(repository,UrlConnectionTransport(),WebsiteAccessContext())
    private val deferredWake=mutableSetOf<TaskId>()
    private var recovered=false
    init { repository.transferInFlight={ id -> running[id]?.second?.isCompleted==false };repository.stopTransfer={ id -> running[id]?.let { (token,job)->token.cancel();job.cancel() } } }
    @Synchronized fun recover() {
        if(recovered)return
        recovered=true
        repository.records().filter { it.transfer==TransferType.CONTROLLED }.forEach { r ->
            if(r.taskStatus in setOf(TaskStatus.RUNNING,TaskStatus.MUXING,TaskStatus.VERIFYING,TaskStatus.PUBLISHING)) {
                repository.change(r.recordId) { old -> if(old.taskStatus==r.taskStatus)old.copy(taskStatus=TaskStatus.INTERRUPTED,failure=FailureKind.INTERRUPTED) else old }
            }
            if(r.taskStatus==TaskStatus.SUCCEEDED)runCatching {
                // Completion may precede process death: clean only private copies, never the asset.
                repository.files?.removeStage(r.recordId);repository.files?.hlsWorkspace?.delete(r.recordId)
            }
            // Reconcile even cancelled/failed tasks whose process died before finally.
            if(r.taskStatus!=TaskStatus.SUCCEEDED)runCatching {
                repository.files?.cleanupPending(r);repository.files?.removeStage(r.recordId)
                if(r.taskStatus==TaskStatus.WAITING_WIFI || r.taskStatus==TaskStatus.QUEUED)repository.files?.hlsWorkspace?.cleanSegments(r.recordId)
                else repository.files?.hlsWorkspace?.delete(r.recordId)
                repository.change(r.recordId) { it.copy(pendingUri=null) }
            }
        }
    }
    fun wifiAvailable():Boolean {
        val manager=app.getSystemService(ConnectivityManager::class.java)
        val network=manager.activeNetwork ?: return false
        val capabilities=manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    /** Called following user submission or while the Activity resumes, never from boot. */
    fun kick() {
        scope.launch {
            recover()
            val eligible=repository.records().any { it.transfer==TransferType.CONTROLLED &&
                (it.taskStatus==TaskStatus.QUEUED || (it.taskStatus==TaskStatus.WAITING_WIFI && (! (it.wifiOnly ?: true) || wifiAvailable()))) }
            if(!eligible)return@launch
            try { ContextCompat.startForegroundService(app,Intent(app,ControlledDownloadService::class.java)) }
            catch(_:Exception) { limit() }
        }
    }
    @Synchronized fun tick():Boolean {
        running.entries.toList().forEach { (id,value) ->
            val r=repository.record(id)
            if(r==null || r.taskStatus !in DownloadRepository.activeStatuses) { value.first.cancel();value.second.cancel() }
            else if(r.taskStatus==TaskStatus.RUNNING && r.wifiOnly==true && !wifiAvailable()) {
                var waiting=false
                repository.change(id) { old ->if(old.taskStatus==TaskStatus.RUNNING) { waiting=true;old.copy(taskStatus=TaskStatus.WAITING_WIFI) } else old }
                if(waiting) { value.first.cancel();value.second.cancel() }
            }
        }
        running.entries.removeIf { it.value.second.isCompleted }
        applyDeferredWake()
        val queue=repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }.reversed()
        for(r in queue) {
            if(running.containsKey(r.recordId))continue
            if(r.wifiOnly==true && !wifiAvailable()) { repository.change(r.recordId) { old ->if(old.taskStatus==TaskStatus.QUEUED)old.copy(taskStatus=TaskStatus.WAITING_WIFI) else old };continue }
            if(running.size>=2)break
            val token=TransferCancellation()
            var claimed=false
            repository.change(r.recordId) { old ->if(old.taskStatus==TaskStatus.QUEUED) { claimed=true;old.copy(taskStatus=TaskStatus.RUNNING,failure=null,safeFailure=null) } else old }
            if(!claimed)continue
            val job=scope.launch(start=CoroutineStart.LAZY) { if(r.protocol==DownloadProtocol.HLS)hlsTransfer.run(r.recordId,token) else transfer.run(r.recordId,token) }
            running[r.recordId]=token to job
            if(repository.record(r.recordId)?.taskStatus==TaskStatus.RUNNING)job.start() else { token.cancel();job.cancel() }
        }
        return running.isNotEmpty() || repository.records().any { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }
    }
    /** Preserve only the wake intent authorized by an explicit foreground service start. */
    @Synchronized fun wakeWaiting() {
        repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.WAITING_WIFI &&
            (it.wifiOnly!=true || wifiAvailable()) }.forEach { deferredWake.add(it.recordId) }
        applyDeferredWake()
    }
    private fun applyDeferredWake() {
        for(id in deferredWake.toList()) {
            val r=repository.record(id)
            if(r==null || r.taskStatus!=TaskStatus.WAITING_WIFI || (r.wifiOnly==true && !wifiAvailable())) { deferredWake.remove(id);continue }
            if(running[id]?.second?.isCompleted==false)continue
            repository.change(id) { old ->if(old.taskStatus==TaskStatus.WAITING_WIFI)old.copy(taskStatus=TaskStatus.QUEUED) else old }
            deferredWake.remove(id)
        }
    }
    @Synchronized fun interruptAll() {
        deferredWake.clear()
        running.forEach { (id,pair) -> repository.change(id) { if(it.taskStatus in DownloadRepository.activeStatuses)it.copy(taskStatus=TaskStatus.INTERRUPTED,failure=FailureKind.INTERRUPTED) else it };pair.first.cancel();pair.second.cancel() }
    }
    fun limit() {
        interruptAll()
        repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }.forEach { repository.change(it.recordId) { old-> if(old.taskStatus==TaskStatus.QUEUED)old.copy(taskStatus=TaskStatus.FAILED,failure=FailureKind.SYSTEM_LIMIT) else old } }
    }
    companion object {
        @Volatile private var instance:DownloadRuntime?=null
        fun get(context:Context):DownloadRuntime=instance ?: synchronized(this) { instance ?: DownloadRuntime(context.applicationContext).also { instance=it } }
    }
}

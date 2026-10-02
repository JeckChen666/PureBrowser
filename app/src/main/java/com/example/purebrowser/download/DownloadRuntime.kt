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
    private var recovered=false
    init { repository.stopTransfer={ id -> running[id]?.let { (token,job)->token.cancel();job.cancel() } } }
    @Synchronized fun recover() {
        if(recovered)return
        recovered=true
        repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus in setOf(TaskStatus.RUNNING,TaskStatus.VERIFYING,TaskStatus.PUBLISHING) }.forEach { r ->
            runCatching { repository.files?.cleanupPending(r);repository.files?.removeStage(r.recordId) }
            repository.change(r.recordId) { it.copy(taskStatus=TaskStatus.INTERRUPTED,failure=FailureKind.INTERRUPTED) }
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
            else if(r.wifiOnly==true && !wifiAvailable()) {
                repository.change(id) { it.copy(taskStatus=TaskStatus.WAITING_WIFI) };value.first.cancel();value.second.cancel()
            }
        }
        running.entries.removeIf { it.value.second.isCompleted }
        val queue=repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }.reversed()
        for(r in queue) {
            if(r.wifiOnly==true && !wifiAvailable()) { repository.change(r.recordId) { it.copy(taskStatus=TaskStatus.WAITING_WIFI) };continue }
            if(running.size>=2)break
            val token=TransferCancellation()
            repository.change(r.recordId) { it.copy(taskStatus=TaskStatus.RUNNING,failure=null) }
            val job=scope.launch(start=CoroutineStart.LAZY) { transfer.run(r.recordId,token) }
            running[r.recordId]=token to job;job.start()
        }
        return running.isNotEmpty() || repository.records().any { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }
    }
    fun wakeWaiting() { repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.WAITING_WIFI && (it.wifiOnly!=true || wifiAvailable()) }.forEach { repository.change(it.recordId) { old ->if(old.taskStatus==TaskStatus.WAITING_WIFI)old.copy(taskStatus=TaskStatus.QUEUED) else old } } }
    @Synchronized fun interruptAll() {
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

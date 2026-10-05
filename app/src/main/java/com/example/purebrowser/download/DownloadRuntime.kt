package com.example.purebrowser.download

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** One process owner. No boot receiver, persistent scheduler, or cold-start auto download. */
class DownloadRuntime private constructor(private val app:Context) {
    val repository=DownloadRepository(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val running=ConcurrentHashMap<TaskId,Pair<TransferCancellation,Job>>()
    private val transfer=ControlledTransfer(repository,UrlConnectionTransport(),WebsiteAccessContext())
    private val hlsTransfer=com.example.purebrowser.download.hls.HlsTransfer(repository,UrlConnectionTransport(),WebsiteAccessContext())
    private val dualTrackTransfer=DualTrackTransfer(repository,UrlConnectionTransport(),WebsiteAccessContext())
    private val dashTransfer=com.example.purebrowser.download.dash.DashTransfer(repository,UrlConnectionTransport(),WebsiteAccessContext())
    private val deferredWake=mutableSetOf<TaskId>()
    private var recovered=false
    init {
        repository.transferInFlight={ id -> running.containsKey(id) }
        repository.captureStop={ id -> running[id]?.let { pair -> { pair.first.cancel();pair.second.cancel();Unit } } ?: {} }
        repository.stopTransfer={ id -> running[id]?.let { (token,job)->token.cancel();job.cancel() } }
    }
    /** Run before accepting a new draft, on IO. Reconciliation never starts sensitive transfers. */
    @Synchronized fun recover() {
        if(recovered)return
        val records=repository.records()
        if(!repository.store.writable) { recovered=true;return }
        records.filter { it.transfer==TransferType.CONTROLLED }.forEach { r ->
            val files=repository.files ?: return@forEach
            if(r.taskStatus==TaskStatus.SUCCEEDED || r.taskStatus==TaskStatus.CANCELLED) {
                runCatching { files.cleanupPending(r);files.clearPrivate(r.recordId) }
                return@forEach
            }
            // An explicitly submitted in-process plan is not a cold disk task. kick() may be
            // the owner's first recovery call in an integration harness.
            if(r.protocol==DownloadProtocol.DUAL_TRACK && r.taskStatus==TaskStatus.QUEUED && r.received==0L &&
                repository.hasFreshDualTrackRequest(r.recordId))return@forEach
            if(r.protocol==DownloadProtocol.DASH && r.taskStatus==TaskStatus.QUEUED && r.received==0L &&
                repository.hasFreshDashRequest(r.recordId))return@forEach
            try {
                files.cleanupPending(r)
                val valid=if(r.protocol==DownloadProtocol.DUAL_TRACK || r.protocol==DownloadProtocol.DASH) {
                    files.clearPrivate(r.recordId)
                    false // No durable URL lease or cross-track validators after process death.
                } else if(r.protocol==DownloadProtocol.HLS) {
                    files.hlsWorkspace.discardIncomplete(r.recordId)
                    files.removeStage(r.recordId) // interrupted mux/public copy is not a resumable MP4
                    files.hlsWorkspace.hasResumeData(r.recordId)
                } else files.directCheckpoints.hasValid(r.recordId,files.stage(r.recordId))
                if(!valid && !(r.protocol==DownloadProtocol.DIRECT && r.received==0L)) files.clearPrivate(r.recordId)
                repository.change(r.recordId) { old ->
                    val cold=old.taskStatus in DownloadRepository.activeStatuses
                    old.copy(pendingUri=null,resumeAvailable=valid,
                        taskStatus=if(cold)TaskStatus.INTERRUPTED else old.taskStatus,
                        pauseReason=if(cold)PauseReason.RECOVERY else old.pauseReason,
                        failure=if(cold)FailureKind.INTERRUPTED else old.failure,
                        safeFailure=if(cold && old.protocol==DownloadProtocol.DUAL_TRACK)DualTrackTransfer.REPARSE_MESSAGE else if(cold && old.protocol==DownloadProtocol.DASH)com.example.purebrowser.download.dash.DashTransfer.REPARSE_MESSAGE else old.safeFailure)
                }
            } catch(_:Exception) {
                repository.change(r.recordId) { it.copy(taskStatus=TaskStatus.INTERRUPTED,resumeAvailable=false,
                    failure=FailureKind.STORAGE,pauseReason=PauseReason.STORAGE,safeFailure="恢复检查未完成，缓存保留；请检查存储后重试") }
            }
        }
        recovered=true
    }
    fun networkAvailable():Boolean {
        val m=app.getSystemService(ConnectivityManager::class.java)
        val n=m.activeNetwork ?: return false
        return m.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    }
    fun wifiAvailable():Boolean {
        val m=app.getSystemService(ConnectivityManager::class.java)
        val n=m.activeNetwork ?: return false
        val c=m.getNetworkCapabilities(n) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    @Synchronized fun pause(id:TaskId)=repository.pause(id)
    fun resume(id:TaskId) { recover();repository.queueResume(id);kick() }
    /** App-visible submission/return or an explicitly authorized notification operation only. */
    fun kick() {
        scope.launch {
            recover();if(!repository.transfersAllowed)return@launch;wakeWaiting()
            if(repository.records().none { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED })return@launch
            try { ContextCompat.startForegroundService(app,Intent(app,ControlledDownloadService::class.java)) }
            catch(_:Exception) { limit() }
        }
    }
    @Synchronized private fun settleStopped() {
        running.entries.filter { it.value.second.isCompleted }.forEach { (id,_) ->
            synchronized(DownloadStore.transactionLock) {
                if(repository.record(id)?.taskStatus==TaskStatus.CANCELLED)runCatching { repository.files?.clearPrivate(id) }
                running.remove(id)
            }
        }
        repository.records().filter { it.taskStatus==TaskStatus.PAUSING && !running.containsKey(it.recordId) }.forEach { r ->
            repository.change(r.recordId) { if(it.taskStatus==TaskStatus.PAUSING)it.copy(taskStatus=TaskStatus.PAUSED) else it }
        }
    }
    @Synchronized fun tick():Boolean {
        settleStopped()
        if(!repository.transfersAllowed)return running.isNotEmpty()
        running.entries.toList().forEach { (id,value) ->
            val r=repository.record(id)
            if(r==null || r.taskStatus !in TaskControlRules.writing) { value.first.cancel();value.second.cancel() }
            else if(r.taskStatus==TaskStatus.RUNNING && ((!networkAvailable()) || (r.wifiOnly==true && !wifiAvailable()))) {
                val wifi=networkAvailable() && r.wifiOnly==true
                repository.change(id) { old ->if(old.taskStatus==TaskStatus.RUNNING)old.copy(
                    taskStatus=if(old.protocol==DownloadProtocol.DUAL_TRACK)TaskStatus.INTERRUPTED else if(wifi)TaskStatus.WAITING_WIFI else TaskStatus.WAITING_NETWORK,
                    pauseReason=if(old.protocol==DownloadProtocol.DUAL_TRACK)PauseReason.SOURCE_CHANGED else if(wifi)PauseReason.WIFI else PauseReason.NETWORK,
                    safeFailure=if(old.protocol==DownloadProtocol.DUAL_TRACK)DualTrackTransfer.REPARSE_MESSAGE else old.safeFailure) else old }
                value.first.cancel();value.second.cancel()
            }
        }
        wakeWaiting()
        val queue=repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }.reversed()
        for(r in queue) {
            if(running.containsKey(r.recordId))continue
            val blocked=!networkAvailable() || (r.wifiOnly==true && !wifiAvailable())
            if(blocked) {
                val wifi=networkAvailable() && r.wifiOnly==true
                repository.change(r.recordId) { old ->if(old.taskStatus==TaskStatus.QUEUED)old.copy(
                    taskStatus=if(wifi)TaskStatus.WAITING_WIFI else TaskStatus.WAITING_NETWORK,
                    pauseReason=if(wifi)PauseReason.WIFI else PauseReason.NETWORK) else old };continue
            }
            if(running.size>=2)break
            val token=TransferCancellation();var claimed=false
            repository.change(r.recordId) { old ->if(old.taskStatus==TaskStatus.QUEUED) {
                claimed=true;old.copy(taskStatus=TaskStatus.RUNNING,failure=null,safeFailure=null,pauseReason=null)
            } else old }
            if(!claimed)continue
            val job=scope.launch(start=CoroutineStart.LAZY) {
                when(r.protocol) {
                    DownloadProtocol.HLS->hlsTransfer.run(r.recordId,token)
                    DownloadProtocol.DUAL_TRACK->dualTrackTransfer.run(r.recordId,token)
                    DownloadProtocol.DASH->dashTransfer.run(r.recordId,token)
                    DownloadProtocol.DIRECT->transfer.run(r.recordId,token)
                }
            }
            running[r.recordId]=token to job
            job.invokeOnCompletion { scope.launch { settleStopped() } }
            if(repository.record(r.recordId)?.taskStatus==TaskStatus.RUNNING)job.start() else { token.cancel();job.cancel() }
        }
        return running.isNotEmpty() || repository.records().any { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }
    }
    @Synchronized fun wakeWaiting() {
        repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus in TaskControlRules.waiting &&
            it.pauseReason in setOf(PauseReason.WIFI,PauseReason.NETWORK) && networkAvailable() &&
            (it.wifiOnly!=true || wifiAvailable()) }.forEach { deferredWake.add(it.recordId) }
        applyDeferredWake()
    }
    private fun applyDeferredWake() {
        for(id in deferredWake.toList()) {
            val r=repository.record(id)
            if(r==null || r.taskStatus !in TaskControlRules.waiting || !networkAvailable() || (r.wifiOnly==true && !wifiAvailable())) {
                deferredWake.remove(id);continue
            }
            if(running.containsKey(id))continue
            try {
                if(r.protocol==DownloadProtocol.DUAL_TRACK)check(repository.wakeFreshDualTrack(id))
                    else if(r.protocol==DownloadProtocol.DASH)check(repository.wakeFreshDash(id))
                    else repository.queueResume(id)
            } catch(_:Exception) {
                repository.change(id) { old -> if(old.taskStatus in TaskControlRules.waiting)old.copy(taskStatus=TaskStatus.INTERRUPTED,
                    resumeAvailable=false,safeFailure="没有可靠续传缓存，请重新下载",failure=FailureKind.INTERRUPTED) else old }
            }
            deferredWake.remove(id)
        }
    }
    @Synchronized fun interruptAll(reason:PauseReason=PauseReason.SYSTEM,failure:FailureKind=FailureKind.INTERRUPTED) {
        deferredWake.clear()
        running.forEach { (id,pair) ->
            repository.change(id) { if(it.taskStatus in TaskControlRules.writing)it.copy(taskStatus=TaskStatus.INTERRUPTED,
                failure=failure,pauseReason=reason) else it }
            pair.first.cancel();pair.second.cancel()
        }
    }
    fun limit() {
        interruptAll(failure=FailureKind.SYSTEM_LIMIT)
        repository.records().filter { it.transfer==TransferType.CONTROLLED && it.taskStatus==TaskStatus.QUEUED }.forEach { r ->
            repository.change(r.recordId) { old -> if(old.taskStatus==TaskStatus.QUEUED)old.copy(taskStatus=TaskStatus.INTERRUPTED,
                failure=FailureKind.SYSTEM_LIMIT,pauseReason=PauseReason.SYSTEM) else old }
        }
    }
    /** Used before clearing website session data. Wait outside metadata locks for all writers. */
    suspend fun quiesceAccessTasks(all:Boolean=false) {
        if(all)synchronized(this) { repository.beginPrivacyExclusion();deferredWake.clear() }
        val ids=repository.records().filter { it.transfer==TransferType.CONTROLLED && (all || it.useAccessContext) &&
            it.taskStatus in DownloadRepository.activeStatuses }.map { it.recordId }
        ids.forEach { id ->
            repository.change(id) { old ->if(old.taskStatus in DownloadRepository.activeStatuses)old.copy(taskStatus=TaskStatus.PAUSING,
                pauseReason=PauseReason.ACCESS) else old };repository.stopTransfer(id)
        }
        repository.awaitRequestQuiescence()
        ids.mapNotNull { running[it]?.second }.joinAll()
        settleStopped()
    }
    @Synchronized fun endPrivacyExclusion() { repository.transfersAllowed=true }
    companion object {
        @Volatile private var instance:DownloadRuntime?=null
        fun get(context:Context):DownloadRuntime=instance ?: synchronized(this) {
            instance ?: DownloadRuntime(context.applicationContext).also { instance=it }
        }
    }
}

package com.example.purebrowser.download

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.purebrowser.MainActivity
import com.example.purebrowser.R
import kotlinx.coroutines.*

/** Explicit immutable notification intents target this non-exported dataSync service. */
class ControlledDownloadService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var runtime:DownloadRuntime
    private var loop:Job?=null
    @Volatile private var latestStart=0
    override fun onCreate() { super.onCreate();runtime=DownloadRuntime.get(this) }
    private fun notification(records:List<DownloadRecord> = emptyList()):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val active=records.filter { it.transfer==TransferType.CONTROLLED && it.taskStatus in TaskControlRules.writing }
        val text=when {
            active.any { it.taskStatus==TaskStatus.MUXING }->"正在合并视频，尚未保存完成"
            active.any { it.taskStatus==TaskStatus.PUBLISHING }->"正在保存视频"
            active.any { it.taskStatus==TaskStatus.VERIFYING }->"正在检查视频"
            active.any { it.taskStatus==TaskStatus.RUNNING }->"正在下载 ${active.count { it.taskStatus==TaskStatus.RUNNING }} 个视频；返回应用查看进度"
            else->"正在处理任务队列"
        }
        val b=NotificationCompat.Builder(this,"video_downloads").setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("PureBrowser 视频下载").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
        val task=records.firstOrNull { TaskControlRules.canPause(it) } ?: records.firstOrNull { TaskControlRules.canResume(it) }
        if(task!=null) {
            val pause=TaskControlRules.canPause(task)
            fun action(command:String,code:Int)=PendingIntent.getForegroundService(this,code,
                Intent(this,ControlledDownloadService::class.java).setAction(command).putExtra("taskId",task.recordId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0,if(pause)"暂停一个任务" else "继续一个任务",action(if(pause)PAUSE else RESUME,1))
            if(TaskControlRules.canCancel(task))b.addAction(0,"取消该任务",action(CANCEL,2))
        }
        return b.build()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        latestStart=startId
        try {
            val nm=getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("video_downloads","视频下载",NotificationManager.IMPORTANCE_LOW))
            ServiceCompat.startForeground(this,711,notification(),if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            loop?.cancel()
            loop=scope.launch {
                try {
                    runtime.recover()
                    val id=intent?.getStringExtra("taskId")
                    if(id!=null && Regex("[a-zA-Z0-9-]{1,100}").matches(id)) {
                        // Stale actions are harmless: capability is rechecked by the shared owner.
                        runCatching { when(intent.action) {
                            PAUSE->runtime.pause(id)
                            RESUME->runtime.repository.queueResume(id)
                            CANCEL->runtime.repository.cancel(id)
                            else->Unit
                        } }
                    }
                    runtime.wakeWaiting()
                    while(isActive && runtime.tick()) {
                        nm.notify(711,notification(runtime.repository.records()));delay(500)
                    }
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { runCatching { runtime.limit() } }
                finally { withContext(NonCancellable+Dispatchers.Main) {
                    if(latestStart==startId) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId) }
                } }
            }
        } catch(_:Exception) { scope.launch { runtime.limit();withContext(Dispatchers.Main) { stopSelf() } } }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) {
        scope.launch { runtime.limit();withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() } }
    }
    override fun onDestroy() { loop?.cancel();runtime.interruptAll();scope.cancel();super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        private const val PAUSE="com.example.purebrowser.download.PAUSE"
        private const val RESUME="com.example.purebrowser.download.RESUME"
        private const val CANCEL="com.example.purebrowser.download.CANCEL"
    }
}

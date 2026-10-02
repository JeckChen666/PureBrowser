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

/** Only explicit in-app starts; not exported, not sticky, no boot receiver. */
class ControlledDownloadService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var runtime:DownloadRuntime
    private var loop:Job?=null
    @Volatile private var latestStart=0
    override fun onCreate() { super.onCreate();runtime=DownloadRuntime.get(this) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        latestStart=startId
        try {
            val nm=getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("video_downloads","视频下载",NotificationManager.IMPORTANCE_LOW))
            val pending=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(this,"video_downloads").setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("PureBrowser 正在保存视频").setContentText("返回应用查看进度或取消")
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build()
            ServiceCompat.startForeground(this,711,notification,if(Build.VERSION.SDK_INT>=29)ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            loop?.cancel()
            loop=scope.launch {
                try {
                    runtime.recover();runtime.wakeWaiting()
                    while(isActive && runtime.tick())delay(500)
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { runCatching { runtime.limit() } }
                finally { withContext(NonCancellable+Dispatchers.Main) {
                    if(latestStart==startId) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId) }
                } }
            }
        } catch(_:Exception) { scope.launch { runtime.limit();withContext(Dispatchers.Main) { stopSelf() } } }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) { scope.launch { runtime.limit();withContext(Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() } } }
    override fun onDestroy() { loop?.cancel();runtime.interruptAll();scope.cancel();super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null
}

package com.example.purebrowser.ui.browser

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.ui.resources.DownloadConfirmation
import com.example.purebrowser.ui.resources.ResourceSheet

private data class PendingSubmission(val draft: DownloadDraft, val name: String, val wifiOnly: Boolean)

@Composable
fun BrowserPanels(model: BrowserViewModel, candidates: List<MediaCandidate>, showResources: Boolean,
                  onDismissResources: () -> Unit) {
    val context = LocalContext.current
    val defaultWifiOnly by model.defaultWifiOnly.collectAsState()
    var confirmDownload by remember { mutableStateOf<DownloadDraft?>(null) }
    var pendingPermission by remember { mutableStateOf<PendingSubmission?>(null) }
    val userAgent = remember { WebSettings.getDefaultUserAgent(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted=grants[Manifest.permission.WRITE_EXTERNAL_STORAGE]==true && grants[Manifest.permission.READ_EXTERNAL_STORAGE]==true
        val pending = pendingPermission
        pendingPermission = null
        if(granted && pending != null) model.download(pending.draft, pending.wifiOnly, pending.name)
        else if(!granted) model.notify("未取得旧版 Android 的下载目录写入权限；未创建任务，可重新尝试")
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending=pendingPermission;pendingPermission=null
        if(!granted) model.notify("通知权限未开启；系统可能隐藏下载通知，请在下载中心查看状态")
        if(pending!=null) model.download(pending.draft,pending.wifiOnly,pending.name)
    }
    if(showResources) ResourceSheet(candidates, onDismissResources, { item ->
        if(model.sniffer?.candidates?.value?.any { it.url == item.url } == true) {
            confirmDownload = model.downloadDraft(item, userAgent)
        } else model.notify("页面资源已更新，请重新打开资源面板")
        onDismissResources()
    }, { onDismissResources() })
    confirmDownload?.let { draft ->
        DownloadConfirmation(draft, defaultWifiOnly, { confirmDownload = null }) { name, wifiOnly, useContext ->
            confirmDownload = null
            if(Build.VERSION.SDK_INT <= 28 && (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
                pendingPermission = PendingSubmission(draft.copy(useAccessContext=useContext), name, wifiOnly)
                permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE,Manifest.permission.READ_EXTERNAL_STORAGE))
            } else if(Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingPermission = PendingSubmission(draft.copy(useAccessContext=useContext),name,wifiOnly)
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else model.download(draft.copy(useAccessContext=useContext), wifiOnly, name)
        }
    }
}

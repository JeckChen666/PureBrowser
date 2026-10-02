package com.example.purebrowser.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.purebrowser.data.browser.ThemeMode
import androidx.compose.ui.platform.LocalContext

@Composable
fun SettingsScreen(theme: ThemeMode, select: (ThemeMode)->Unit, wifiOnly:Boolean, setWifiOnly:(Boolean)->Unit, about: ()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("外观",style=MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { mode ->
            Surface(onClick={select(mode)},shape=MaterialTheme.shapes.medium,color=if(theme==mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().testTag("theme-${mode.name}")) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) { RadioButton(selected=theme==mode,onClick={select(mode)});Text(mode.label) }
            }
        }
        Text("只改变浏览器界面，网页配色由网站决定。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("下载",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("默认仅 Wi-Fi 下载");Text("只影响新任务，可在确认时单独更改。",style=MaterialTheme.typography.bodySmall) }
            Switch(checked=wifiOnly,onCheckedChange=setWifiOnly,modifier=Modifier.testTag("defaultWifiOnly"))
        }
        Text("文件保存在本机公共 Download/PureBrowser 目录；显示名称可修改，实际文件名保留唯一前缀。移除记录不会删除文件，删除文件需要另行确认。",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("本地优先",style=MaterialTheme.typography.titleMedium)
        Text("标签、书签、历史与设置保存在此设备。不提供应用账号、云同步或云端解析。",style=MaterialTheme.typography.bodyMedium)
        TextButton(onClick=about) { Text("关于 PureBrowser") }
    }
}
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val version = androidx.compose.runtime.remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName,0).versionName }.getOrNull() ?: "未知" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("PureBrowser",style=MaterialTheme.typography.headlineMedium)
        Text("本地视频浏览器 · $version")
        Text("应用 ID：${context.packageName}")
        Text("支持 MP4/WebM 直链、无后缀视频与限定同源网站会话。HLS、DASH、blob 仅识别，不支持分片保存、跨站敏感鉴权或 DRM。")
        Text("仅保存你拥有或获授权的内容。不绕过 DRM 或访问控制。")
        Text("视频库仅索引本应用保存的文件，不扫描整机媒体。文件分享通过 Android 系统选择器交给你选定的应用；不会由本应用自动上传。")
        Text("开源组件：AndroidX / Jetpack Compose（Apache 2.0）；Kotlin（Apache 2.0）。Android WebView 和 DownloadManager 由设备系统提供。资源规则参考记录见项目 docs/SOURCE-RESEARCH.md；未复制第三方下载引擎。")
        Text("基于 Android WebView、Jetpack Compose 和受控下载服务。应用无自有后端，不上传浏览数据。")
    }
}

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

@Composable
fun SettingsScreen(theme: ThemeMode, select: (ThemeMode)->Unit, about: ()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("外观",style=MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { mode ->
            Surface(onClick={select(mode)},shape=MaterialTheme.shapes.medium,color=if(theme==mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth().testTag("theme-${mode.name}")) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) { RadioButton(selected=theme==mode,onClick={select(mode)});Text(mode.label) }
            }
        }
        Text("只改变浏览器界面，网页配色由网站决定。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("本地优先",style=MaterialTheme.typography.titleMedium)
        Text("标签、书签、历史与设置保存在此设备。不提供应用账号、云同步或云端解析。",style=MaterialTheme.typography.bodyMedium)
        TextButton(onClick=about) { Text("关于 PureBrowser") }
    }
}
@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("PureBrowser",style=MaterialTheme.typography.headlineMedium)
        Text("本地视频浏览器 · 产品预览版")
        Text("支持普通网页浏览和公开 MP4 / WebM 等直链保存。HLS、DASH、blob 目前仅识别，不支持分片下载或登录鉴权传递。")
        Text("仅保存你拥有或获授权的内容。不绕过 DRM 或访问控制。")
        Text("基于 Android WebView、Jetpack Compose 和系统下载服务。应用无自有后端，不上传浏览数据。")
    }
}

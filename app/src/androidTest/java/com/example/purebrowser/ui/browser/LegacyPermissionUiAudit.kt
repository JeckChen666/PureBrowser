package com.example.purebrowser.ui.browser
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.Evidence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
class LegacyPermissionUiAudit {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun denyingLegacyStoragePermissionCreatesNoDownloadTask() {
        val i=InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT<=28 && InstrumentationRegistry.getArguments().getString("denyLegacy")=="true")
        lateinit var model:BrowserViewModel
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        val before=model.repository.records().map { it.recordId }.toSet()
        compose.activityRule.scenario.onActivity { model.newTab("http://127.0.0.1:8765/second.html") }
        compose.waitUntil(20000) { model.sniffer!!.candidates.value.any { it.url.endsWith("second.mp4") && Evidence.DOM in it.sources } }
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("resourcesButton").performClick()
        compose.onAllNodesWithContentDescription("保存").onFirst().performClick()
        compose.onNodeWithText("开始下载").performScrollTo().performClick()
        var clicked=false;val until=System.currentTimeMillis()+10000
        while(!clicked && System.currentTimeMillis()<until) {
            for(text in listOf("拒绝","DENY","Deny","不允许")) {
                val nodes=i.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
                for(n in nodes) { var node:AccessibilityNodeInfo?=n;while(node!=null && !node.isClickable)node=node.parent
                    if(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { clicked=true;break } }
                if(clicked)break
            };Thread.sleep(200)
        }
        assertTrue("Actual permission denial must be selected",clicked)
        compose.waitUntil(10000) { model.message.value?.contains("未创建任务")==true }
        assertEquals(before,model.repository.records().map { it.recordId }.toSet())
        assertFalse(model.submitting.value)
    }
}

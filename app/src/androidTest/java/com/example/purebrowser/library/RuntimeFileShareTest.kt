package com.example.purebrowser.library

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.*
import com.example.purebrowser.ui.browser.BrowserViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual chooser + separate test-APK UID. No fake startActivity or receiving-app storage permission. */
class RuntimeFileShareTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var model:BrowserViewModel
    private val args get()=InstrumentationRegistry.getArguments()
    private fun start() {
        assumeTrue(args.getString("videoFixture")=="true")
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
    }
    @Test fun actualSystemChooserGrantsMp4ToSeparateUidWithoutStoragePermission() {
        start()
        val id=File(compose.activity.cacheDir,"round2-restart-id.txt").readText().trim().toLong()
        compose.waitUntil(10000) { model.videoLibrary.value.any { it.systemId==id } }
        receiveViaChooser(id,args.getString("fixtureSha256")!!)
    }
    @Test fun actualSystemChooserGrantsWebmToSeparateUidWithoutStoragePermission() {
        start()
        val previous=model.repository.snapshot().map { it.id }.toSet()
        val base=args.getString("fixtureBaseUrl") ?: "http://127.0.0.1:8765"
        val draft=model.downloadDraft(MediaCandidate("$base/sample.webm",MediaKind.FILE,setOf(Evidence.REQUEST),"video/webm"),"PureBrowser-Fixture")
        compose.activityRule.scenario.onActivity { model.download(draft,false,"r2_share.webm") }
        compose.waitUntil(60000) { model.downloads.value.any { it.id !in previous && it.verified } }
        val id=model.downloads.value.first { it.id !in previous && it.verified }.id
        File(compose.activity.cacheDir,"round2-owned-task-ids.txt").appendText("$id\n")
        receiveViaChooser(id,args.getString("fixtureWebmSha256")!!)
    }
    private fun receiveViaChooser(id:Long,expectedHash:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // The instrumentation process uses the target UID, not the independent recipient UID.
        // Shell reads only that test APK's result, never the video; the receiver must read via grant.
        instrumentation.uiAutomation.executeShellCommand("run-as com.example.purebrowser.test rm -f files/fixture-file-received.json").close()
        fun report(): String? = runCatching {
            val descriptor=instrumentation.uiAutomation.executeShellCommand("run-as com.example.purebrowser.test cat files/fixture-file-received.json")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }.takeIf { it.startsWith("{") }
        }.getOrNull()
        compose.activityRule.scenario.onActivity { model.launchFile(it,id,true) }
        val deadline=System.currentTimeMillis()+15000
        var clicked=false
        while(System.currentTimeMillis()<deadline && !clicked) {
            val matches=instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("本地视频验收接收器").orEmpty()
            for(node in matches) {
                var target:AccessibilityNodeInfo?=node
                while(target!=null && !target.isClickable) target=target.parent
                if(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { clicked=true;break }
            }
            Thread.sleep(300)
        }
        assertTrue("Actual chooser must expose the test-only file recipient",clicked)
        val readDeadline=System.currentTimeMillis()+10000
        var received=report()
        while(received==null && System.currentTimeMillis()<readDeadline) { Thread.sleep(200);received=report() }
        assertNotNull("Recipient must actually read the granted file",received)
        val result=JSONObject(received!!)
        assertEquals(Intent.ACTION_SEND,result.getString("action"))
        assertTrue(result.getBoolean("readable"));assertEquals(id,result.getLong("id"))
        assertEquals(expectedHash,result.getString("sha256"))
    }
}

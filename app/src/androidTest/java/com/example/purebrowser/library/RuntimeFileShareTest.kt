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
    private fun saveAndShare(path:String,name:String,expectedHash:String) {
        start()
        val base=args.getString("fixtureBaseUrl") ?: "http://127.0.0.1:8765"
        val runtime=com.example.purebrowser.download.DownloadRuntime.get(compose.activity)
        runtime.recover()
        val id=model.repository.enqueue(com.example.purebrowser.download.DownloadDraft(
            MediaCandidate("$base/$path",MediaKind.FILE,setOf(Evidence.REQUEST)),"PureBrowser-ShareAudit",useAccessContext=false),false,name)
        try {
            runtime.kick()
            compose.waitUntil(60000) { model.downloads.value.any { it.id==id && it.verified } }
            receiveViaChooser(id,expectedHash)
        } finally {
            val end=System.currentTimeMillis()+10000
            while(model.repository.transferInFlight(id) && System.currentTimeMillis()<end)Thread.sleep(50)
            if(model.repository.stateSnapshot().assets.any { it.recordId==id })model.repository.deleteFile(id)
            else { runCatching { model.repository.cancel(id) };runCatching { model.repository.forgetRecord(id) } }
        }
    }
    @Test fun actualSystemChooserGrantsMp4ToSeparateUidWithoutStoragePermission() =
        saveAndShare("sample.mp4?token=demo%2Bsignature","share-owned.mp4",args.getString("fixtureSha256")!!)
    @Test fun actualSystemChooserGrantsWebmToSeparateUidWithoutStoragePermission() =
        saveAndShare("sample.webm","share-owned.webm",args.getString("fixtureWebmSha256")!!)
    private fun receiveViaChooser(id:String,expectedHash:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // The instrumentation process uses the target UID, not the independent recipient UID.
        // Shell reads only that test APK's result, never the video; the receiver must read via grant.
        instrumentation.uiAutomation.executeShellCommand("run-as io.github.jeckchen666.purebrowser.debug.test rm -f files/fixture-file-received.json").close()
        fun report(): String? = runCatching {
            val descriptor=instrumentation.uiAutomation.executeShellCommand("run-as io.github.jeckchen666.purebrowser.debug.test cat files/fixture-file-received.json")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }.takeIf { it.startsWith("{") }
        }.getOrNull()
        compose.activityRule.scenario.onActivity { model.launchFile(it,id,true) }
        val clicked = chooseFixtureRecipient(instrumentation, 15_000)
        assertTrue("Actual chooser must expose the test-only file recipient",clicked)
        val readDeadline=System.currentTimeMillis()+10000
        var received=report()
        while(received==null && System.currentTimeMillis()<readDeadline) { Thread.sleep(200);received=report() }
        assertNotNull("Recipient must actually read the granted file",received)
        val result=JSONObject(received!!)
        assertEquals(Intent.ACTION_SEND,result.getString("action"))
        assertTrue(result.getBoolean("readable"));assertTrue(result.getBoolean("readable"))
        assertEquals(expectedHash,result.getString("sha256"))
    }
}

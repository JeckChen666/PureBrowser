package com.example.purebrowser.library

import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.*
import com.example.purebrowser.media.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

class CrossUidShareAudit {
    @Test fun actualChooserSharesMp4AndWebmToAnIndependentUid() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("crossUidShare")=="true")
        val app=instrument.targetContext;val testPackage=instrument.context.packageName
        assertNotEquals(app.applicationInfo.uid,instrument.context.applicationInfo.uid)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val runtime=DownloadRuntime.get(app);val repo=runtime.repository
            for(ext in listOf("mp4","webm")) {
                val id=repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8765/sample.$ext" + if(ext=="mp4") "?token=demo%2Bsignature" else "",MediaKind.FILE,setOf(Evidence.REQUEST)),"PureBrowser-SharingAudit",useAccessContext=false),false,"share.$ext")
                try {
                    runtime.kick();val deadline=System.currentTimeMillis()+45000
                    while(repo.record(id)!!.taskStatus in DownloadRepository.activeStatuses && System.currentTimeMillis()<deadline)Thread.sleep(200)
                    assertEquals(TaskStatus.SUCCEEDED,repo.record(id)!!.taskStatus)
                    val uri=repo.fileUri(id)!!
                    val senderHash=app.contentResolver.openInputStream(uri)!!.use { input ->
                        MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
                    }
                    instrument.uiAutomation.executeShellCommand("run-as $testPackage rm -f files/fixture-file-received.json").close()
                    scenario.onActivity { assertNull(LocalFileActions.launch(it,id,uri,repo.mimeType(id),true)) }
                    val clicked = chooseFixtureRecipient(instrument, 20_000)
                    assertTrue("Real chooser must select the independent recipient",clicked)
                    var result:String?=null;val wait=System.currentTimeMillis()+10000
                    while(result==null && System.currentTimeMillis()<wait) {
                        result=runCatching { val fd=instrument.uiAutomation.executeShellCommand("run-as $testPackage cat files/fixture-file-received.json")
                            ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }.takeIf { it.startsWith("{") } }.getOrNull()
                        Thread.sleep(200)
                    }
                    assertNotNull("Recipient must actually read the temporary URI grant",result)
                    val receipt=JSONObject(result!!);assertTrue(receipt.getBoolean("readable"));assertEquals(senderHash,receipt.getString("sha256"))
                } finally { runCatching { repo.deleteFile(id) } }
            }
        }
    }
}

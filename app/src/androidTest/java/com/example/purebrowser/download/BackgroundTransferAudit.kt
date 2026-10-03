package com.example.purebrowser.download

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in 15-minute audit using throttled, self-generated video. Run on a dedicated test app. */
class BackgroundTransferAudit {
    @Test fun foregroundDownloadPersistsForFifteenMinutesWithScreenOff() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("background15")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val app=instrument.targetContext
            val runtime=DownloadRuntime.get(app)
            val id=runtime.repository.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8765/long.mp4",MediaKind.FILE,setOf(Evidence.DOM)),"PureBrowser-BackgroundTest",useAccessContext=false),false,"background-test.mp4")
            File(app.cacheDir,"v011-background-id.txt").writeText(id)
            runtime.kick()
            val deadline=System.currentTimeMillis()+30000
            while(runtime.repository.record(id)!!.received==0L && System.currentTimeMillis()<deadline)Thread.sleep(250)
            assertEquals(TaskStatus.RUNNING,runtime.repository.record(id)!!.taskStatus)
            val before=runtime.repository.record(id)!!.received
            val started=System.currentTimeMillis()
            instrument.uiAutomation.executeShellCommand("input keyevent 223").close()
            try {
                Thread.sleep(901000)
                val record=runtime.repository.record(id)!!
                assertEquals(TaskStatus.RUNNING,record.taskStatus)
                assertTrue(record.received>before+800000)
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                File(app.applicationInfo.sourceDir).inputStream().use { input ->
                    val buffer=ByteArray(65536)
                    while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) }
                }
                val evidence=org.json.JSONObject().put("elapsedMs",System.currentTimeMillis()-started)
                    .put("received",record.received).put("status",record.taskStatus).put("screenOffRequested",true)
                    .put("apkSha256",digest.digest().joinToString("") { b->"%02x".format(b) }).put("api",android.os.Build.VERSION.SDK_INT).toString()
                File(app.cacheDir,"v011-background-result.json").writeText(evidence)
                instrument.sendStatus(0,android.os.Bundle().apply { putString("backgroundEvidence",evidence) })
            } finally {
                runtime.repository.cancel(id)
                val cleanupDeadline=System.currentTimeMillis()+15000
                while(runtime.repository.transferInFlight(id) && System.currentTimeMillis()<cleanupDeadline)Thread.sleep(100)
                assertFalse(runtime.repository.transferInFlight(id))
                runtime.repository.forgetRecord(id)
                instrument.uiAutomation.executeShellCommand("input keyevent 224").close()
                instrument.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            }
        }
    }
}

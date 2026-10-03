package com.example.purebrowser.download

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Operator first configures an isolated AVD; tests never change global settings silently. */
class SystemRestrictionAudit {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrument.targetContext
    private fun await(timeout:Long=45000,condition:()->Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(!condition() && System.currentTimeMillis()<end)Thread.sleep(200)
        assertTrue("OS restriction condition timed out",condition())
    }
    private fun clean(runtime:DownloadRuntime,id:String) {
        val repo=runtime.repository
        runCatching { if(repo.record(id)?.taskStatus!=TaskStatus.SUCCEEDED)repo.cancel(id) }
        await { !repo.transferInFlight(id) }
        if(repo.stateSnapshot().assets.any { it.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
    }
    @Test fun deniedNotificationsStillAllowUserInitiatedForegroundDownload() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("notificationDenied")=="true")
        assumeTrue(android.os.Build.VERSION.SDK_INT>=33)
        assertEquals(PackageManager.PERMISSION_DENIED,app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime=DownloadRuntime.get(app);runtime.recover();val repo=runtime.repository
            val id=repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8765/sample.mp4?token=demo%2Bsignature",MediaKind.FILE,emptySet()),
                "PureBrowser-NotificationAudit",useAccessContext=false),false,"notification-audit.mp4")
            try {
                runtime.kick();await { repo.record(id)?.taskStatus !in DownloadRepository.activeStatuses }
                assertEquals(repo.record(id)?.safeFailure,TaskStatus.SUCCEEDED,repo.record(id)!!.taskStatus)
                assertNotNull(repo.fileUri(id))
                val evidence=JSONObject().put("notificationDenied",true).put("downloadSucceeded",true)
                    .put("api",android.os.Build.VERSION.SDK_INT).toString()
                instrument.sendStatus(0,android.os.Bundle().apply { putString("notificationEvidence",evidence) })
            } finally { clean(runtime,id) }
        }
    }
    @Test fun realOsDataSyncTimeoutStopsWriterAndPreservesOwnedCheckpoint() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("osTimeout")=="true")
        assumeTrue(android.os.Build.VERSION.SDK_INT>=35)
        // The operator sets the real system timeout to 60000 and restores it afterwards.
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime=DownloadRuntime.get(app);runtime.recover();val repo=runtime.repository
            val id=repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8769/range.mp4",MediaKind.FILE,emptySet()),
                "PureBrowser-TimeoutAudit",useAccessContext=false),false,"timeout-owned-audit.mp4")
            try {
                runtime.kick();await { repo.record(id)?.let { r->r.taskStatus==TaskStatus.RUNNING && r.resumeAvailable && r.received>1024*1024 }==true }
                val started=System.currentTimeMillis()
                instrument.uiAutomation.executeShellCommand("input keyevent 3").close()
                instrument.uiAutomation.executeShellCommand("input keyevent 223").close()
                await(180000) { repo.record(id)?.failure==FailureKind.SYSTEM_LIMIT }
                await { !repo.transferInFlight(id) }
                val record=repo.record(id)!!
                assertEquals(TaskStatus.INTERRUPTED,record.taskStatus)
                assertEquals(PauseReason.SYSTEM,record.pauseReason)
                assertTrue(repo.files!!.directCheckpoints.hasValid(id,repo.files.stage(id)))
                assertFalse(repo.stateSnapshot().assets.any { a->a.recordId==id })
                val evidence=JSONObject().put("failure",record.failure).put("status",record.taskStatus)
                    .put("elapsedMs",System.currentTimeMillis()-started).put("received",record.received)
                    .put("checkpointValid",true).put("api",android.os.Build.VERSION.SDK_INT).toString()
                File(app.cacheDir,"v013-os-timeout-result.json").writeText(evidence)
                instrument.sendStatus(0,android.os.Bundle().apply { putString("timeoutEvidence",evidence) })
            } finally {
                instrument.uiAutomation.executeShellCommand("input keyevent 224").close()
                instrument.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                clean(runtime,id)
            }
        }
    }
}

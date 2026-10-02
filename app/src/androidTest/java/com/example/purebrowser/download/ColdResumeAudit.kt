package com.example.purebrowser.download

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Two invocations with a real shell force-stop between them; owned Debug HTTP fixture only. */
class ColdResumeAudit {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun until(condition:()->Boolean) {
        val end=System.currentTimeMillis()+45000
        while(!condition() && System.currentTimeMillis()<end)Thread.sleep(50)
        assertTrue("cold-recovery condition timeout",condition())
    }
    @Test fun prepare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coldPrepare")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val rt=DownloadRuntime.get(app);rt.recover()
            val id=rt.repository.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8768/range.mp4",MediaKind.FILE,emptySet()),
                "PureBrowser-ColdAudit",useAccessContext=false),false,"cold-owned.mp4")
            app.getSharedPreferences("v013_cold_audit",0).edit().putString("id",id).commit()
            rt.kick();until { rt.repository.record(id)?.let { r->r.received>0 && r.resumeAvailable }==true }
            assertTrue(rt.repository.files!!.directCheckpoints.hasValid(id,rt.repository.files.stage(id)))
        }
    }
    @Test fun verify() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("coldVerify")=="true")
        val id=app.getSharedPreferences("v013_cold_audit",0).getString("id",null) ?: error("prepare missing")
        ActivityScenario.launch(MainActivity::class.java).use {
            val rt=DownloadRuntime.get(app);rt.recover();val repo=rt.repository
            try {
                val r=repo.record(id)!!
                assertEquals(TaskStatus.INTERRUPTED,r.taskStatus)
                assertEquals(PauseReason.RECOVERY,r.pauseReason)
                assertTrue(r.resumeAvailable)
                Thread.sleep(750);assertEquals(TaskStatus.INTERRUPTED,repo.record(id)!!.taskStatus)
                rt.resume(id)
                until { repo.record(id)?.taskStatus !in DownloadRepository.activeStatuses }
                assertEquals(repo.record(id)?.safeFailure,TaskStatus.SUCCEEDED,repo.record(id)!!.taskStatus)
                assertTrue(repo.snapshot().single { task->task.id==id }.verified)
            } finally {
                runCatching { if(repo.record(id)?.taskStatus!=TaskStatus.SUCCEEDED)repo.cancel(id) }
                until { !repo.transferInFlight(id) }
                if(repo.stateSnapshot().assets.any { a->a.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
                app.getSharedPreferences("v013_cold_audit",0).edit().remove("id").commit()
            }
        }
    }
}

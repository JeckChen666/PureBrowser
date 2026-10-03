package com.example.purebrowser.download

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Owned HTTP fixture is opt-in Debug only. This class never enters the Release APK. */
class ResumeCoordinatorAudit {
    private fun waitFor(timeout:Long=30000,condition:()->Boolean) {
        val until=System.currentTimeMillis()+timeout
        while(!condition() && System.currentTimeMillis()<until)Thread.sleep(50)
        assertTrue("task condition timed out",condition())
    }
    @Test fun pauseStopsWriterResumeSameIdAndCancelRemovesOnlyPrivateCache() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("resumeFixture")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime=DownloadRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
            runtime.recover()
            val repo=runtime.repository
            val id=repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8768/range.mp4",MediaKind.FILE,emptySet()),
                "PureBrowser-ResumeAudit",useAccessContext=false),false,"resume-audit.mp4")
            try {
                runtime.kick()
                waitFor { repo.record(id)?.let { it.taskStatus==TaskStatus.RUNNING && it.received>0 && it.resumeAvailable }==true }
                runtime.pause(id)
                waitFor { repo.record(id)?.taskStatus==TaskStatus.PAUSED && !repo.transferInFlight(id) }
                val stopped=repo.record(id)!!
                assertEquals(PauseReason.USER,stopped.pauseReason)
                assertTrue(repo.files!!.directCheckpoints.hasValid(id,repo.files.stage(id)))
                val length=repo.files.stage(id).length();Thread.sleep(500)
                assertEquals(length,repo.files.stage(id).length())
                runtime.resume(id)
                waitFor { repo.record(id)?.taskStatus==TaskStatus.RUNNING }
                assertEquals(id,repo.record(id)!!.recordId)
                repo.cancel(id)
                waitFor { !repo.transferInFlight(id) }
                assertEquals(TaskStatus.CANCELLED,repo.record(id)!!.taskStatus)
                assertFalse(repo.files.stage(id).exists())
            } finally {
                runCatching { if(repo.record(id)?.taskStatus!=TaskStatus.CANCELLED)repo.cancel(id) }
                waitFor { !repo.transferInFlight(id) }
                if(repo.stateSnapshot().assets.any { a->a.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
            }
        }
    }
}

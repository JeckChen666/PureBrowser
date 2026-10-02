package com.example.purebrowser.download
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
class CoordinatorQueueAudit {
    @Test fun atMostTwoTasksRunAndCancellationReleasesOneSlot() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("queueFixture")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime=DownloadRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext);val repo=runtime.repository
            val ids=(1..3).map { n->repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8765/slow.mp4?slot=$n",MediaKind.FILE,emptySet()),"PureBrowser-QueueAudit",useAccessContext=false),false,"slot-$n.mp4") }
            try {
                runtime.kick();var deadline=System.currentTimeMillis()+15000
                while(ids.count { repo.record(it)?.taskStatus==TaskStatus.RUNNING }<2 && System.currentTimeMillis()<deadline)Thread.sleep(200)
                assertEquals(2,ids.count { repo.record(it)?.taskStatus==TaskStatus.RUNNING })
                assertEquals(1,ids.count { repo.record(it)?.taskStatus==TaskStatus.QUEUED })
                val running=ids.first { repo.record(it)!!.taskStatus==TaskStatus.RUNNING };val queued=ids.first { repo.record(it)!!.taskStatus==TaskStatus.QUEUED }
                repo.cancel(running);deadline=System.currentTimeMillis()+10000
                while(repo.record(queued)!!.taskStatus!=TaskStatus.RUNNING && System.currentTimeMillis()<deadline)Thread.sleep(200)
                assertEquals(TaskStatus.CANCELLED,repo.record(running)!!.taskStatus)
                assertEquals(TaskStatus.RUNNING,repo.record(queued)!!.taskStatus)
                assertTrue(ids.count { repo.record(it)?.taskStatus==TaskStatus.RUNNING }<=2)
            } finally { ids.forEach { id->runCatching { if(repo.record(id)!!.taskStatus in DownloadRepository.activeStatuses)repo.cancel(id) } } }
        }
    }
}

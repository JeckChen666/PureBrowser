package com.example.purebrowser.download

import android.webkit.CookieManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebKit CookieManager, sockets, foreground service and public files. Opt-in local fixtures. */
class ServiceWorkflowAudit {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private fun setCookie(value:String) {
        val done=CountDownLatch(1)
        instrument.runOnMainSync { CookieManager.getInstance().setCookie("http://127.0.0.1:8765",value) { done.countDown() } }
        assertTrue(done.await(5,TimeUnit.SECONDS))
    }
    private fun await(runtime:DownloadRuntime,id:TaskId):DownloadRecord {
        val until=System.currentTimeMillis()+45000
        while(System.currentTimeMillis()<until) {
            val r=runtime.repository.record(id)!!
            if(r.taskStatus !in DownloadRepository.activeStatuses)return r
            Thread.sleep(200)
        }
        throw AssertionError("Task did not finish: ${runtime.repository.record(id)}")
    }
    @Test fun realWebsiteCookieSourceAndForegroundDownloadsAreBounded() {
        assumeTrue(args.getString("serviceFixture")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val app=instrument.targetContext;val runtime=DownloadRuntime.get(app);val repo=runtime.repository
            fun draft(path:String,context:Boolean)=DownloadDraft(MediaCandidate("http://127.0.0.1:8765/$path",MediaKind.UNKNOWN,setOf(Evidence.DOM),frameUrl="http://127.0.0.1:8765/frame",reliableSource=true),
                "PureBrowser-Fixture","http://127.0.0.1:8765/watch?private=synthetic","Source","test",1,useAccessContext=context)
            val ids=mutableListOf<TaskId>()
            try {
                setCookie("pb_session=demo-allowed; Path=/; HttpOnly")
                val session=repo.enqueue(draft("session",true),false,"session.mp4");ids+=session;runtime.kick()
                assertEquals(TaskStatus.SUCCEEDED,await(runtime,session).taskStatus)
                assertNotNull(repo.fileUri(session))
                val noContext=repo.enqueue(draft("session",false),false,"no-context.mp4");ids+=noContext;runtime.kick()
                assertEquals(FailureKind.ACCESS_CONDITION,await(runtime,noContext).failure)
                setCookie("pb_session=; Path=/; Max-Age=0")
                val afterLogout=repo.enqueue(draft("session",true),false,"logged-out.mp4");ids+=afterLogout;runtime.kick()
                assertEquals(FailureKind.ACCESS_CONDITION,await(runtime,afterLogout).failure)
                val source=repo.enqueue(draft("referrer",true),false,"source.mp4");ids+=source;runtime.kick()
                assertEquals(TaskStatus.SUCCEEDED,await(runtime,source).taskStatus)
                val raw=repo.store.file.readText();assertFalse(raw.contains("pb_session"));assertFalse(raw.contains("Authorization"))
                File(app.cacheDir,"v011-service-audit.json").writeText("""{"session":true,"disabled":true,"logout":true,"minimalReferer":true,"noCredentialPersistence":true}""")
            } finally {
                for(id in ids)runCatching { if(repo.stateSnapshot().assets.any { it.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id) }
                setCookie("pb_session=; Path=/; Max-Age=0")
            }
        }
    }
    @Test fun saveOneVideoForRestartAndExternalSharingAudit() {
        assumeTrue(args.getString("prepareRestart")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val app=instrument.targetContext;val runtime=DownloadRuntime.get(app)
            val id=runtime.repository.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8765/sample.mp4?token=demo%2Bsignature",MediaKind.FILE,setOf(Evidence.REQUEST)),"PureBrowser-Fixture",useAccessContext=false),false,"restart-fixture.mp4")
            runtime.kick();assertEquals(TaskStatus.SUCCEEDED,await(runtime,id).taskStatus)
            File(app.cacheDir,"v011-restart-id.txt").writeText(id)
        }
    }
    @Test fun separatelyRestartedProcessReconcilesTheSavedVideo() {
        assumeTrue(args.getString("checkRestart")=="true")
        ActivityScenario.launch(MainActivity::class.java).use {
            val app=instrument.targetContext;val runtime=DownloadRuntime.get(app)
            val id=File(app.cacheDir,"v011-restart-id.txt").readText()
            assertEquals(TaskStatus.SUCCEEDED,runtime.repository.record(id)!!.taskStatus)
            assertNotNull(runtime.repository.fileUri(id));assertTrue(runtime.repository.snapshot().first { it.id==id }.verified)
        }
    }
}

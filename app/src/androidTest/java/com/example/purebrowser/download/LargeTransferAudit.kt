package com.example.purebrowser.download

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
import java.security.MessageDigest

/** Explicitly opt-in, self-owned large fixture. Never deletes another task or enters the APK. */
class LargeTransferAudit {
    private fun await(timeout:Long=60000, condition:()->Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(!condition() && System.currentTimeMillis()<end)Thread.sleep(100)
        assertTrue("large task condition timed out",condition())
    }
    @Test fun overOneGiBActuallyPausesResumesPublishesAndMatchesFullHash() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("largeFixture")=="true")
        val expectedHash=args.getString("sourceSha256") ?: error("source SHA-256 required")
        require(Regex("[a-f0-9]{64}").matches(expectedHash))
        val sourceBytes=args.getString("sourceBytes")?.toLong() ?: error("source bytes required")
        require(sourceBytes>1024L*1024*1024)
        val app=instrument.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime=DownloadRuntime.get(app);runtime.recover();val repo=runtime.repository
            val recoverId=args.getString("recoverOwnedTask")
            val id=if(recoverId!=null) {
                require(Regex("[a-f0-9-]{36}").matches(recoverId))
                val previous=repo.record(recoverId) ?: error("owned prepared task missing")
                require(previous.displayName=="large-owned-audit.mp4" && previous.mediaUrl=="http://127.0.0.1:8769/range.mp4" && !previous.useAccessContext)
                assertEquals(TaskStatus.INTERRUPTED,previous.taskStatus)
                assertEquals(PauseReason.RECOVERY,previous.pauseReason)
                assertTrue(repo.files!!.directCheckpoints.hasValid(recoverId,repo.files.stage(recoverId)))
                val checkpointBytes=repo.files.stage(recoverId).length();Thread.sleep(1000)
                assertEquals(checkpointBytes,repo.files.stage(recoverId).length())
                runtime.resume(recoverId)
                recoverId
            } else repo.enqueue(DownloadDraft(MediaCandidate("http://127.0.0.1:8769/range.mp4",MediaKind.FILE,emptySet()),
                "PureBrowser-LargeOwnedAudit",useAccessContext=false),false,"large-owned-audit.mp4")
            val started=System.currentTimeMillis()
            try {
                runtime.kick()
                var lastProgress=0L
                await {
                    val record=repo.record(id)
                    if(System.currentTimeMillis()-lastProgress>5000) {
                        lastProgress=System.currentTimeMillis()
                        instrument.sendStatus(0,android.os.Bundle().apply { putString("largeProgress",
                            JSONObject().put("status",record?.taskStatus).put("received",record?.received)
                                .put("expected",record?.expected).put("resumable",record?.resumeAvailable).put("failure",record?.failure).toString()) })
                    }
                    record?.let { r->r.taskStatus==TaskStatus.RUNNING && r.received>4*1024*1024 && r.resumeAvailable }==true
                }
                runtime.pause(id)
                await { repo.record(id)?.taskStatus==TaskStatus.PAUSED && !repo.transferInFlight(id) }
                val paused=repo.record(id)!!
                assertEquals(PauseReason.USER,paused.pauseReason)
                val stage=repo.files!!.stage(id)
                assertTrue(repo.files.directCheckpoints.hasValid(id,stage))
                val stoppedBytes=stage.length();Thread.sleep(1000);assertEquals(stoppedBytes,stage.length())
                runtime.resume(id)
                await(900000) { repo.record(id)?.taskStatus !in DownloadRepository.activeStatuses }
                val finished=repo.record(id)!!
                assertEquals(finished.safeFailure,TaskStatus.SUCCEEDED,finished.taskStatus)
                assertEquals(id,finished.recordId)
                val uri=repo.fileUri(id) ?: error("missing published asset")
                val digest=MessageDigest.getInstance("SHA-256");var bytes=0L
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    val buffer=ByteArray(1024*1024)
                    while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n);bytes+=n }
                }
                val actualHash=digest.digest().joinToString("") { b->"%02x".format(b) }
                assertEquals(sourceBytes,bytes);assertEquals(expectedHash,actualHash)
                val asset=repo.stateSnapshot().assets.single { a->a.recordId==id }
                assertEquals(AssetLocation.MEDIASTORE_DOWNLOAD,asset.location)
                assertTrue((asset.durationMillis ?: 0)>0)
                assertFalse(stage.exists())
                val result=JSONObject().put("bytes",bytes).put("sha256",actualHash).put("pausedBytes",stoppedBytes)
                    .put("sameTaskId",true).put("recoveredAfterActualReboot",recoverId!=null).put("pauseFrozen",true).put("elapsedMs",System.currentTimeMillis()-started)
                    .put("durationMs",asset.durationMillis).put("api",android.os.Build.VERSION.SDK_INT)
                    .put("package",app.packageName).put("published",true).toString()
                File(app.cacheDir,"v013-large-result.json").writeText(result)
                instrument.sendStatus(0,android.os.Bundle().apply { putString("largeEvidence",result) })
            } finally {
                runCatching { if(repo.record(id)?.taskStatus in DownloadRepository.activeStatuses)repo.cancel(id) }
                await { !repo.transferInFlight(id) }
                if(repo.stateSnapshot().assets.any { a->a.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
            }
        }
    }
}

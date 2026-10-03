package com.example.purebrowser.download

import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.hls.*
import com.example.purebrowser.library.LocalFileActions
import com.example.purebrowser.media.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Opt-in device audits: never ship in Release, never reset an unrelated user's data. */
class HlsProductAudit {
    private fun prepare(runtime:DownloadRuntime,url:String):String {
        val draft=DownloadDraft(MediaCandidate(url,MediaKind.HLS,setOf(Evidence.DOM)),"PureBrowser-HlsAudit",useAccessContext=false)
        val resolver=HlsResolver(UrlConnectionTransport(),WebsiteAccessContext(),runtime.repository.allowLocalHttp)
        val options=resolver.resolveEntry(draft,TransferCancellation())
        val variant=(options.playlist as? HlsPlaylist.Master)?.let { HlsPlaylistParser.defaultVariant(it.variants) }
        val plan=resolver.resolvePlan(draft,options,variant,TransferCancellation())
        return runtime.repository.enqueue(draft,false,"hls-audit.mp4",hlsPlan=plan)
    }
    @Test fun longVideoSavesAndRealChooserRecipientReadsIdenticalMp4() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("hlsLong")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation();val app=instrument.targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val runtime=DownloadRuntime.get(app);val repo=runtime.repository
            val id=prepare(runtime,"http://127.0.0.1:8767/video.m3u8")
            try {
                runtime.kick();val deadline=System.currentTimeMillis()+900000
                while(repo.record(id)!!.taskStatus in DownloadRepository.activeStatuses && System.currentTimeMillis()<deadline)Thread.sleep(500)
                val r=repo.record(id)!!;assertEquals(r.safeFailure,TaskStatus.SUCCEEDED,r.taskStatus)
                val asset=repo.stateSnapshot().assets.single { it.recordId==id }
                assertTrue((asset.durationMillis ?: 0)>1_800_000);assertEquals("video/mp4",asset.mimeType)
                val uri=repo.fileUri(id)!!
                val frames=android.media.MediaMetadataRetriever()
                try {
                    frames.setDataSource(app,uri)
                    for(point in listOf(0L,900_000_000L,1_799_000_000L)) {
                        val frame=frames.getFrameAtTime(point,android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?: error("video sample did not decode")
                        assertTrue(frame.width>0 && frame.height>0)
                        File(app.cacheDir,"v012-frame-$point.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };frame.recycle()
                    }
                } finally { frames.release() }
                assertNotEquals(app.applicationInfo.uid,instrument.context.applicationInfo.uid)
                val testPackage=instrument.context.packageName
                val hash=app.contentResolver.openInputStream(uri)!!.use { input ->
                    val digest=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536)
                    while(true) { val n=input.read(b);if(n<0)break;digest.update(b,0,n) };digest.digest().joinToString("") { "%02x".format(it) }
                }
                instrument.uiAutomation.executeShellCommand("run-as $testPackage rm -f files/fixture-file-received.json").close()
                scenario.onActivity { assertNull(LocalFileActions.launch(it,id,uri,"video/mp4",true)) }
                val clicked=com.example.purebrowser.library.chooseFixtureRecipient(instrument,20_000)
                assertTrue("actual chooser must open a different UID",clicked)
                var receipt:String?=null;val wait=System.currentTimeMillis()+10000
                while(receipt==null && System.currentTimeMillis()<wait) {
                    receipt=runCatching { val fd=instrument.uiAutomation.executeShellCommand("run-as $testPackage cat files/fixture-file-received.json")
                        ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }.takeIf { it.startsWith("{") } }.getOrNull();Thread.sleep(200)
                }
                val obj=JSONObject(receipt ?: error("recipient did not read"));assertTrue(obj.getBoolean("readable"));assertEquals(hash,obj.getString("sha256"))
                val evidence=JSONObject().put("durationMs",asset.durationMillis).put("segments",r.segmentCount)
                    .put("sha256",hash).put("received",r.received).put("crossUidShare",true).toString()
                File(app.cacheDir,"v012-long-result.json").writeText(evidence)
                if(args.getString("hlsExportOwned")=="true") {
                    // Opt-in export of this generated fixture only, for full host decode. Not user media.
                    app.contentResolver.openInputStream(uri)!!.use { input ->
                        File(app.cacheDir,"v013-long-owned-output.mp4").outputStream().use { output->input.copyTo(output) }
                    }
                }
                instrument.sendStatus(0,android.os.Bundle().apply { putString("longHlsEvidence",evidence) })
            } finally { runCatching {
                if(repo.record(id)?.taskStatus in DownloadRepository.activeStatuses)repo.cancel(id)
                val until=System.currentTimeMillis()+10000
                while(repo.transferInFlight(id) && System.currentTimeMillis()<until)Thread.sleep(50)
                if(!repo.transferInFlight(id)) {
                    if(repo.stateSnapshot().assets.any { a->a.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
                }
            } }
        }
    }
    @Test fun longHlsFinishesMuxAndPublicationWhileScreenRemainsOff() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("hlsBackgroundFinish")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation();val app=instrument.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val rt=DownloadRuntime.get(app);rt.recover();val repo=rt.repository
            val id=prepare(rt,"http://127.0.0.1:8767/video.m3u8")
            try {
                rt.kick();val ready=System.currentTimeMillis()+45000
                while((repo.record(id)!!.received==0L || repo.record(id)!!.taskStatus!=TaskStatus.RUNNING) && System.currentTimeMillis()<ready)Thread.sleep(200)
                assertEquals(TaskStatus.RUNNING,repo.record(id)!!.taskStatus)
                instrument.uiAutomation.executeShellCommand("input keyevent 3").close()
                instrument.uiAutomation.executeShellCommand("input keyevent 223").close()
                val power=app.getSystemService(android.os.PowerManager::class.java)
                val asleepDeadline=System.currentTimeMillis()+10000
                while(power.isInteractive && System.currentTimeMillis()<asleepDeadline)Thread.sleep(100)
                assertFalse(power.isInteractive)
                val start=System.currentTimeMillis();val deadline=start+900000
                while(repo.record(id)!!.taskStatus in DownloadRepository.activeStatuses && System.currentTimeMillis()<deadline)Thread.sleep(500)
                assertFalse(power.isInteractive)
                val record=repo.record(id)!!;assertEquals(record.safeFailure,TaskStatus.SUCCEEDED,record.taskStatus)
                val asset=repo.stateSnapshot().assets.single { a->a.recordId==id }
                assertTrue((asset.durationMillis ?: 0)>1800000);assertNotNull(repo.fileUri(id))
                instrument.sendStatus(0,android.os.Bundle().apply { putString("backgroundHlsEvidence",JSONObject()
                    .put("elapsedMs",System.currentTimeMillis()-start).put("durationMs",asset.durationMillis)
                    .put("completedSegments",record.completedSegments).put("status",record.taskStatus)
                    .put("screenOffAtStartAndFinish",true).put("publicAssetReadable",true).toString()) })
            } finally {
                instrument.uiAutomation.executeShellCommand("input keyevent 224").close()
                instrument.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                if(repo.record(id)?.taskStatus in DownloadRepository.activeStatuses)repo.cancel(id)
                val end=System.currentTimeMillis()+15000
                while(repo.transferInFlight(id) && System.currentTimeMillis()<end)Thread.sleep(100)
                if(repo.stateSnapshot().assets.any { a->a.recordId==id })repo.deleteFile(id) else repo.forgetRecord(id)
            }
        }
    }
    @Test fun fifteenMinuteScreenOffHlsTransfer() {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("hlsBackground")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation();val app=instrument.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val runtime=DownloadRuntime.get(app);val repo=runtime.repository
            val id=prepare(runtime,"http://127.0.0.1:8767/cases/slow/video.m3u8")
            try {
                runtime.kick();val wait=System.currentTimeMillis()+45000
                while(repo.record(id)!!.received==0L && System.currentTimeMillis()<wait)Thread.sleep(250)
                val before=repo.record(id)!!;assertEquals(TaskStatus.RUNNING,before.taskStatus)
                instrument.uiAutomation.executeShellCommand("input keyevent 3").close()
                instrument.uiAutomation.executeShellCommand("input keyevent 223").close()
                val start=System.currentTimeMillis();Thread.sleep(901000)
                val after=repo.record(id)!!;assertEquals(TaskStatus.RUNNING,after.taskStatus);assertTrue(after.received>before.received)
                assertTrue(after.completedSegments>before.completedSegments)
                File(app.cacheDir,"v012-background-result.json").writeText(JSONObject().put("elapsedMs",System.currentTimeMillis()-start)
                    .put("completedSegments",after.completedSegments).put("received",after.received).put("screenOffRequested",true).toString())
            } finally {
                runCatching { repo.cancel(id) };instrument.uiAutomation.executeShellCommand("input keyevent 224").close()
                instrument.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
            }
        }
    }
    @Test fun restartPreparationOrRecovery() {
        val args=InstrumentationRegistry.getArguments();val phase=args.getString("hlsRestart")
        assumeTrue(phase=="prepare" || phase=="verify")
        val app=InstrumentationRegistry.getInstrumentation().targetContext;val marker=File(app.filesDir,"v012-restart-id.txt")
        val repo=DownloadRepository(app)
        if(phase=="prepare") {
            val draft=DownloadDraft(MediaCandidate("https://fixture.example/video.m3u8",MediaKind.HLS,emptySet()),"test",useAccessContext=false)
            val text="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n"
            val media=HlsPlaylistParser.parse(text,draft.candidate.url) as HlsPlaylist.Media
            val id=repo.enqueue(draft,false,"restart.mp4",hlsPlan=HlsDownloadPlan(draft.candidate.url,draft.candidate.url,media))
            val status=TaskStatus.valueOf(args.getString("restartStatus") ?: "RUNNING")
            var pending:String?=null
            if(status==TaskStatus.PUBLISHING && android.os.Build.VERSION.SDK_INT>=29) {
                val record=repo.record(id)!!
                val values=android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,record.name)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/PureBrowser/")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
                }
                pending=app.contentResolver.insert(android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),values)!!.toString()
                File(app.filesDir,"v012-restart-pending.txt").writeText(pending)
            }
            repo.change(id) { it.copy(taskStatus=status,pendingUri=pending) }
            repo.files!!.hlsWorkspace.segment(id,0).writeBytes(ByteArray(188))
            repo.files!!.stage(id).writeText("unfinished")
            marker.writeText(id)
        } else {
            val id=marker.readText();DownloadRuntime.get(app).recover()
            assertEquals(TaskStatus.INTERRUPTED,repo.record(id)!!.taskStatus)
            assertFalse(repo.files!!.stage(id).exists());assertFalse(repo.files!!.hlsWorkspace.segment(id,0).exists())
            assertFalse(repo.stateSnapshot().assets.any { it.recordId==id })
            val pending=File(app.filesDir,"v012-restart-pending.txt")
            if(pending.exists()) {
                val uri=android.net.Uri.parse(pending.readText())
                app.contentResolver.query(uri,arrayOf(android.provider.MediaStore.MediaColumns._ID),null,null,null)?.use { assertFalse(it.moveToFirst()) }
                pending.delete()
            }
            repo.forgetRecord(id);marker.delete()
        }
    }
}

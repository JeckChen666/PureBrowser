package com.example.purebrowser.download

import android.content.Intent
import android.content.ClipData
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.hls.*
import com.example.purebrowser.library.LocalFileActions
import com.example.purebrowser.media.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Opt-in native player journey; host inspects the actual player, then explicitly cleans this ID. */
class HlsExternalPlaybackAudit {
    @Test fun prepareOrCleanOnlyTheKnownPlaybackFixture() {
        val instrument=InstrumentationRegistry.getInstrumentation();val phase=InstrumentationRegistry.getArguments().getString("hlsPlayback")
        assumeTrue(phase=="prepare" || phase=="clean" || phase=="inspect")
        val app=instrument.targetContext;val marker=File(app.filesDir,"v012-playback-id.txt");val repo=DownloadRepository(app)
        if(phase=="inspect") {
            val uri=repo.fileUri(marker.readText())!!
            app.contentResolver.openInputStream(uri)!!.use { input -> File(app.cacheDir,"v012-native-player.mp4").outputStream().use { input.copyTo(it) } }
            return
        }
        if(phase=="clean") { val id=marker.readText();repo.deleteFile(id);marker.delete();return }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val runtime=DownloadRuntime.get(app)
            val draft=DownloadDraft(MediaCandidate("http://127.0.0.1:8766/video.m3u8",MediaKind.HLS,emptySet()),"PureBrowser-PlaybackAudit",useAccessContext=false)
            val resolver=HlsResolver(UrlConnectionTransport(),WebsiteAccessContext(),true)
            val options=resolver.resolveEntry(draft,TransferCancellation());val plan=resolver.resolvePlan(draft,options,null,TransferCancellation())
            val id=runtime.repository.enqueue(draft,false,"hls-native-player.mp4",hlsPlan=plan);marker.writeText(id);runtime.kick()
            val until=System.currentTimeMillis()+60000
            while(runtime.repository.record(id)!!.taskStatus in DownloadRepository.activeStatuses && System.currentTimeMillis()<until)Thread.sleep(200)
            assertEquals(TaskStatus.SUCCEEDED,runtime.repository.record(id)!!.taskStatus)
            val uri=runtime.repository.fileUri(id)!!
            scenario.onActivity { assertNull(LocalFileActions.launch(it,id,uri,"video/mp4",false)) }
        }
    }
}

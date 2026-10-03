package com.example.purebrowser.download
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.data.browser.*
import com.example.purebrowser.media.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
class SignedUpgradeAudit {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=i.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    @Test fun seedOnlyTheIsolatedReleaseAppForCoverInstall() {
        assumeTrue(args.getString("seedUpgrade")=="true")
        val store=LocalBrowserRepository(app);val old=store.load()
        store.save(old.copy(bookmarks=old.bookmarks+SavedPage("signed-upgrade-fixture","签名升级验收","https://developer.android.com/",1000),theme=ThemeMode.DARK))
        DownloadPreferences(app).saveWifiOnly(false)
        val repo=DownloadRepository(app)
        val draft=DownloadDraft(MediaCandidate("https://example.org/upgrade-fixture.mp4",MediaKind.FILE,emptySet()),"PureBrowser-UpgradeAudit",useAccessContext=false)
        // The test APK may seed the prior v4 runtime before covering it with the v5 APK.
        val enqueue=repo.javaClass.methods.single { it.name=="enqueue" && it.parameterTypes.firstOrNull()==DownloadDraft::class.java && it.parameterCount in 4..6 }
        val params=mutableListOf<Any?>(draft,false,"upgrade.mp4",null)
        while(params.size<enqueue.parameterCount)params.add(null)
        val id=enqueue.invoke(repo,*params.toTypedArray()) as String
        val bytes=i.context.assets.open("test-video.mp4").use { it.readBytes() }
        ControlledTransfer(repo,HttpTransport { _,_,_->object:HttpResponse {
            override val status=200;override fun header(name:String)=if(name=="Content-Length")bytes.size.toString() else null
            override fun body()=java.io.ByteArrayInputStream(bytes);override fun close() {}
        } },AccessContextProvider { null }).run(id,TransferCancellation())
        assertEquals(TaskStatus.SUCCEEDED,repo.record(id)!!.taskStatus)
        File(app.cacheDir,"signed-upgrade-id.txt").writeText(id)
        assertNotNull(repo.fileUri(id))
    }
    @Test fun newSignedVersionRetainsBookmarkThemePolicyAndRealVideo() {
        assumeTrue(args.getString("checkUpgrade")=="true")
        val data=LocalBrowserRepository(app).load()
        assertTrue(data.bookmarks.any { it.id=="signed-upgrade-fixture" });assertEquals(ThemeMode.DARK,data.theme)
        assertFalse(DownloadPreferences(app).wifiOnly())
        val id=File(app.cacheDir,"signed-upgrade-id.txt").readText();val repo=DownloadRepository(app)
        assertEquals(TaskStatus.SUCCEEDED,repo.record(id)!!.taskStatus);assertNotNull(repo.fileUri(id))
        assertTrue(repo.snapshot().first { it.id==id }.verified)
    }
}

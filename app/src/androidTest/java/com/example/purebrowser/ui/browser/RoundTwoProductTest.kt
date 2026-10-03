package com.example.purebrowser.ui.browser

import android.app.DownloadManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.download.*
import com.example.purebrowser.media.*
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Uses synthetic local media only. Host must back up private state or use a dedicated device. */
class RoundTwoProductTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: BrowserViewModel
    private val args get() = InstrumentationRegistry.getArguments()
    private val base get() = args.getString("fixtureBaseUrl") ?: "http://127.0.0.1:8765"
    private fun start() {
        assumeTrue(args.getString("videoFixture") == "true")
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
    }
    private fun main(action: ()->Unit) { compose.activityRule.scenario.onActivity { action() } }
    private fun track(id: String) {
        // Cleanup/audit inventory contains ONLY IDs created by this test run, no original task IDs.
        File(compose.activity.cacheDir,"round2-owned-task-ids.txt").appendText("$id\n")
    }
    private fun hash(uri: android.net.Uri): String = compose.activity.contentResolver.openInputStream(uri)!!.use {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).joinToString("") { b -> "%02x".format(b) }
    }
    // This full product journey exercises accessibility OnClick actions. Separate
    // BrowserDownloadFixture/BrowserNavigation/HLS journeys retain real touch input.
    // Synthetic taps during platform IME/window animations can land in another
    // window; never bypass the UI by calling ViewModel.download instead.
    private fun SemanticsNodeInteraction.activate(): SemanticsNodeInteraction {
        assertIsDisplayed().assertIsEnabled()
        performSemanticsAction(SemanticsActions.OnClick) { it() }
        return this
    }
    private fun submit(name: String, fileName: String): DownloadItem {
        val ids = model.repository.snapshot().map { it.id }.toSet()
        compose.onNodeWithTag("menuButton").activate()
        compose.onNodeWithTag("resourcesButton").activate()
        val candidate=model.sniffer!!.candidates.value.first { java.net.URI(it.url).path.endsWith("/$name") }
        compose.onNodeWithTag(com.example.purebrowser.ui.resources.resourceSaveTag(candidate.url)).performScrollTo().activate()
        compose.onNodeWithTag("download-file-name").performTextReplacement(fileName)
        compose.onNodeWithTag("download-file-name").performImeAction()
        // Done clears this field's focus. The Activity's IME insets are not a
        // reliable assertion for a separate Dialog window; submission must work
        // through its visible, enabled UI action with or without the keyboard.
        compose.onNodeWithTag("download-file-name").assertIsNotFocused()
        compose.onNodeWithText("开始下载").performScrollTo().assertIsDisplayed().activate()
        try {
            compose.waitUntil(60000) { model.downloads.value.any { it.id !in ids && it.verified } }
        } catch (failure: Throwable) {
            throw AssertionError("Submitted download did not verify: newTasks=" +
                model.downloads.value.filter { it.id !in ids }.map { "${it.status}:${it.detail}" } +
                ", confirmationVisible=" + compose.onAllNodesWithText("开始下载").fetchSemanticsNodes().size,
                failure)
        }
        return model.downloads.value.first { it.id !in ids && it.verified }.also { track(it.id) }
    }
    private fun scrollLibrary(tag: String) {
        val action=Regex("video-(open|share|rename|forget|delete|source)-(.+)").matchEntire(tag)
        if(action!=null) {
            val id=action.groupValues[2]
            compose.onNodeWithTag("videoLibraryList").performScrollToNode(hasTestTag("video-$id"))
            compose.onNodeWithTag("video-$id").activate()
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithTag("videoLibraryList").performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun twoFormatsThroughUiRenameForgetDeleteAndSourceRecovery() {
        start()
        main { model.setDefaultWifiOnly(false); model.newTab() }
        compose.onNodeWithTag("addressInput").activate().performTextReplacement("$base/product.html")
        compose.onNodeWithTag("navigateButton").activate()
        try {
            compose.waitUntil(20000) { !model.defaultWifiOnly.value && model.sniffer?.candidates?.value?.any { java.net.URI(it.url).path.endsWith("/sample.webm") && Evidence.DOM in it.sources } == true }
        } catch (failure: Throwable) {
            throw AssertionError("Product page discovery failed: wifiOnly=${model.defaultWifiOnly.value}, " +
                "page=${model.engine?.page?.value}, tabs=${model.data.value.tabs.size}, " +
                "candidates=${model.sniffer?.candidates?.value?.map { it.url to it.sources }}", failure)
        }
        val source = model.data.value.selectedId
        val mp4 = submit("sample.mp4", "r2_saved.mp4")
        val webm = submit("sample.webm", "r2_saved.webm")
        val mp4Uri = model.repository.fileUri(mp4.id)!!
        val webmUri = model.repository.fileUri(webm.id)!!
        args.getString("fixtureSha256")?.let { assertEquals(it, hash(mp4Uri)) }
        args.getString("fixtureWebmSha256")?.let { assertEquals(it, hash(webmUri)) }
        assertEquals(false, model.repository.record(mp4.id)!!.wifiOnly)
        assertTrue(model.videoLibrary.value.filter { it.recordId in setOf(mp4.id,webm.id) }.all { it.durationMillis!! > 0 && it.sizeBytes!! > 0 })
        main { model.setDefaultWifiOnly(true); model.tabs.close(source); model.newTab() }
        val unrelated = model.data.value.selectedId
        compose.waitUntil(5000) { model.defaultWifiOnly.value }
        assertEquals(false, model.repository.record(mp4.id)!!.wifiOnly) // Existing policy never mutates.
        compose.onNodeWithTag("menuButton").activate()
        compose.onNodeWithTag("downloadsButton").activate()
        compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-source-${mp4.id}"))
        compose.onNodeWithTag("download-source-${mp4.id}").activate()
        try {
            compose.waitUntil(10000) { model.engine?.page?.value?.url=="$base/product.html" }
        } catch (failure: Throwable) {
            throw AssertionError("Source recovery failed: source=${model.repository.record(mp4.id)?.sourceUrl}, " +
                "selected=${model.data.value.selectedId}, page=${model.engine?.page?.value}, " +
                "tabs=${model.data.value.tabs.map { it.id to it.url }}", failure)
        }
        assertNotEquals(unrelated, model.data.value.selectedId)
        assertTrue(model.data.value.tabs.any { it.id==unrelated && it.url=="about:blank" })
        compose.onNodeWithTag("menuButton").activate()
        compose.onNodeWithTag("downloadsButton").activate()
        compose.onNodeWithTag("downloadsLibrary").activate()
        scrollLibrary("video-rename-${webm.id}")
        compose.onNodeWithTag("video-rename-${webm.id}").activate()
        compose.onNodeWithTag("video-title-input-${webm.id}").performTextReplacement("本地 WebM 成品")
        compose.onNodeWithTag("video-title-save-${webm.id}").activate()
        compose.waitUntil(5000) { model.videoLibrary.value.any { it.recordId==webm.id && it.displayName=="本地 WebM 成品" } }
        assertEquals(webm.name, model.repository.record(webm.id)!!.name)
        // A snackbar may remain or be replaced by another legitimate notice.
        // File actions stay usable; assert their effects rather than toast timing.
        scrollLibrary("video-forget-${mp4.id}")
        compose.onNodeWithTag("video-forget-${mp4.id}").activate()
        try { compose.waitUntil(5000) { compose.onAllNodesWithTag("video-forget-dialog-${mp4.id}").fetchSemanticsNodes().isNotEmpty() } }
        catch(e: Exception) {
            val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.cacheDir,"round2-failure.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            compose.onRoot(useUnmergedTree=true).printToLog("R2-FAILURE")
            throw e
        }
        compose.onNodeWithTag("video-forget-dialog-${mp4.id}-confirm").activate()
        compose.waitUntil(5000) { model.downloads.value.none { it.id==mp4.id } && model.videoLibrary.value.none { it.recordId==mp4.id } }
        assertEquals(args.getString("fixtureSha256"), hash(mp4Uri)) // Forget preserved the exact file.
        assertTrue(DownloadRepository(compose.activity).snapshot().none { it.id==mp4.id })

        scrollLibrary("video-delete-${webm.id}")
        compose.onNodeWithTag("video-delete-${webm.id}").activate()
        compose.onNodeWithTag("video-delete-dialog-${webm.id}-confirm").activate()
        compose.waitUntil(5000) { model.downloads.value.none { it.id==webm.id } && model.videoLibrary.value.none { it.recordId==webm.id } }
        assertNotEquals(FileAvailability.AVAILABLE, AndroidDownloadBackend(compose.activity).access(webmUri.toString()).availability)
        assertNull(model.repository.record(webm.id))
        File(compose.activity.cacheDir,"round2-deleted-file-name.txt").writeText(webm.name)
        File(compose.activity.cacheDir,"round2-forgotten-id.txt").writeText(mp4.id.toString())
        main { model.setDefaultWifiOnly(false);model.flush() }
        compose.waitUntil(5000) { !model.defaultWifiOnly.value }
    }
    @Test fun realHtml401403UnknownSizeAndCancelRetryAreTruthful() {
        start()
        main { model.setDefaultWifiOnly(false); model.newTab() }
        compose.onNodeWithTag("addressInput").activate().performTextReplacement("$base/product.html")
        compose.onNodeWithTag("navigateButton").activate()
        compose.waitUntil(15000) { !model.defaultWifiOnly.value && model.sniffer?.candidates?.value?.any { java.net.URI(it.url).path.endsWith("/sample.mp4") }==true }
        fun enqueue(path: String): String {
            val candidate=MediaCandidate("$base/$path",MediaKind.FILE,setOf(Evidence.REQUEST))
            val draft=model.downloadDraft(candidate,"PureBrowser-Fixture")
            val id=model.repository.enqueue(draft.copy(useAccessContext=false),false,"r2_${path.substringBefore('?')}")
            assertEquals(candidate.url, model.repository.record(id)!!.mediaUrl)
            com.example.purebrowser.download.DownloadRuntime.get(compose.activity).kick()
            // Enqueue returns the authoritative TaskId. The assertions below still
            // await the actual UI flow's outcomes; do not discover identity by polling
            // every record while competing with its periodic disk refresh.
            return id.also(::track)
        }
        val html=enqueue("bad.mp4")
        val denied=enqueue("private.mp4")
        val expired=enqueue("expired.mp4")
        val unknown=enqueue("unknown.mp4")
        val fixtureIds=setOf(html,denied,expired,unknown)
        fun describe(items:List<DownloadItem>)=items.filter { it.id in fixtureIds }.map { "${it.id}:${it.status}/${it.systemRead}/${it.format}/${it.availability}:${it.detail}" }
        try {
            compose.waitUntil(30000) { model.downloads.value.any { it.id==html && it.failure==com.example.purebrowser.download.FailureKind.NOT_VIDEO } && model.downloads.value.any { it.id==unknown && it.verified } && listOf(denied,expired).all { id -> model.downloads.value.any { it.id==id && it.status==DownloadManager.STATUS_FAILED } } }
        } catch(error:Throwable) {
            val fresh=runCatching { describe(model.repository.snapshot()).toString() }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            throw AssertionError("Fixture IDs=$fixtureIds; flow=${describe(model.downloads.value)}; fresh=$fresh; message=${model.message.value}",error)
        }
        assertEquals(FailureKind.NOT_VIDEO,model.downloads.value.first { it.id==html }.failure)
        assertTrue(model.videoLibrary.value.none { it.recordId==html })
        assertTrue(listOf(denied,expired).all { id -> model.downloads.value.first { it.id==id }.let { !it.verified && it.canRetry && it.sourceUrl != null } })
        assertTrue(model.downloads.value.first { it.id==unknown }.verified)
        val slow=enqueue("slow.mp4")
        compose.waitUntil(10000) { model.downloads.value.any { it.id==slow && it.status==DownloadManager.STATUS_RUNNING } }
        main { model.cancelDownload(slow) }
        compose.waitUntil(5000) { model.downloads.value.any { it.id==slow && it.cancelled } }
        val before=model.downloads.value.map { it.id }.toSet()
        main { model.retryDownload(slow);model.retryDownload(slow) } // Busy guard prevents double submission.
        compose.waitUntil(5000) { model.downloads.value.any { it.id !in before } }
        val retry=model.downloads.value.single { it.id !in before };track(retry.id)
        assertEquals(model.repository.record(slow)!!.recordId,model.repository.record(retry.id)!!.retryOf)
        assertTrue(model.downloads.value.any { it.id==slow && it.cancelled })
        main { model.cancelDownload(retry.id) }
        compose.waitUntil(5000) { model.downloads.value.any { it.id==retry.id && it.cancelled } }
        // Retain the completed unknown-length sample for an actual host-driven process-restart audit.
        File(compose.activity.cacheDir,"round2-restart-id.txt").writeText(unknown.toString())
        main { model.setDefaultWifiOnly(false);model.flush() }
        compose.waitUntil(5000) { !model.defaultWifiOnly.value }
    }
}

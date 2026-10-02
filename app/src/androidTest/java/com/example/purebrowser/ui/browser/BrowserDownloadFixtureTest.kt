package com.example.purebrowser.ui.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaKind
import java.security.MessageDigest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in integration test. Run the local fixture server first; no public-site dependency. */
class BrowserDownloadFixtureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun signedVideoIsDiscoveredDownloadedAndVerified() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Start tools/fixtures/serve_video_fixture.py and opt in", args.getString("videoFixture") == "true")
        val base = args.getString("fixtureBaseUrl") ?: "http://127.0.0.1:8765"
        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        compose.activityRule.scenario.onActivity { model.newTab() }
        compose.onNodeWithTag("addressInput").performClick()
        compose.onNodeWithTag("addressInput").performTextReplacement("$base/")
        compose.onNodeWithTag("navigateButton").performClick()
        compose.waitUntil(20_000) {
            val candidates = model.sniffer!!.candidates.value
            candidates.any { it.url == "$base/sample.mp4?token=demo%2Bsignature" && Evidence.DOM in it.sources } &&
                candidates.any { it.kind == MediaKind.HLS } && candidates.any { it.kind == MediaKind.DASH }
        }
        val candidates = model.sniffer!!.candidates.value
        assertTrue(candidates.any { it.url=="$base/bad.mp4" })
        assertTrue(candidates.none { java.net.URI(it.url).path?.endsWith("/chunk.ts")==true || java.net.URI(it.url).path?.endsWith("/init.mp4")==true })
        assertEquals("$base/sample.mp4?token=demo%2Bsignature", candidates.first { it.url == "$base/sample.mp4?token=demo%2Bsignature" }.url)
        compose.onNodeWithTag("resourcesButton").performClick()
        compose.onNodeWithTag("resource-card-${candidates.first { it.url == "$base/sample.mp4?token=demo%2Bsignature" }.displayName}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(com.example.purebrowser.ui.resources.resourceSaveTag("$base/sample.mp4?token=demo%2Bsignature")).performScrollTo().performClick()
        compose.onNodeWithText("确认下载直链").assertIsDisplayed()
        // The fixture is tiny; allow both emulator transports during this integration check.
        compose.onNode(isToggleable() and hasText("仅 Wi-Fi",substring=true)).performClick()
        val previousIds=model.repository.snapshot().map{it.id}.toSet()
        compose.onNodeWithText("开始下载").performClick()
        try {
            compose.waitUntil(45_000) { model.downloads.value.any { it.id !in previousIds && it.name.endsWith("_sample.mp4") && it.verified } }
        } catch (failure: Exception) {
            throw AssertionError("Download did not complete: ${model.downloads.value.map { "${it.id}:${it.status}:${it.detail}" }}", failure)
        }
        val completed = model.downloads.value.first { it.id !in previousIds && it.name.endsWith("_sample.mp4") && it.verified }
        File(compose.activity.cacheDir,"round2-owned-task-ids.txt").appendText("${completed.id}\n")
        val uri = model.repository.fileUri(completed.id)
        assertNotNull(uri)
        val bytes = compose.activity.contentResolver.openInputStream(uri!!)?.use { it.readBytes() }
        assertNotNull(bytes)
        val expectedHash = args.getString("fixtureSha256")
        if (!expectedHash.isNullOrBlank()) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes!!).joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, actual)
        }
        val sourceTab = model.data.value.selectedId
        compose.activityRule.scenario.onActivity { model.tabs.close(sourceTab) }
        assertTrue(model.repository.snapshot().any { it.id == completed.id && it.verified })
        assertNotNull(model.repository.fileUri(completed.id))
        compose.onNodeWithTag("downloadsButton").performClick()
        compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-${completed.id}"))
        compose.onNode(hasText(completed.displayName) and hasAnyAncestor(hasTestTag("download-${completed.id}"))).assertIsDisplayed()
    }
}

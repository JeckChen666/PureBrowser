package com.example.purebrowser.ui.browser

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
            candidates.any { it.displayName == "sample.mp4" && Evidence.DOM in it.sources } &&
                candidates.any { it.kind == MediaKind.HLS } && candidates.any { it.kind == MediaKind.DASH }
        }
        val candidates = model.sniffer!!.candidates.value
        assertEquals(4, candidates.size) // Real MP4, false MP4, HLS, DASH; no TS/init fragments.
        assertEquals("$base/sample.mp4?token=demo%2Bsignature", candidates.first { it.displayName == "sample.mp4" }.url)
        compose.onNodeWithTag("resourcesButton").performClick()
        compose.onNodeWithText("sample.mp4").assertIsDisplayed()
        compose.onAllNodesWithText("下载直链").onFirst().performClick()
        compose.onNodeWithText("确认下载直链").assertIsDisplayed()
        // The fixture is tiny; allow both emulator transports during this integration check.
        compose.onNode(isToggleable()).performClick()
        val previousIds=model.downloads.value.map{it.id}.toSet()
        compose.onNodeWithText("开始下载").performClick()
        try {
            compose.waitUntil(45_000) { model.downloads.value.any { it.id !in previousIds && it.name.endsWith("_sample.mp4") && it.verified } }
        } catch (failure: Exception) {
            throw AssertionError("Download did not complete: ${model.downloads.value.map { "${it.id}:${it.status}:${it.detail}" }}", failure)
        }
        val completed = model.downloads.value.first { it.id !in previousIds && it.name.endsWith("_sample.mp4") && it.verified }
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
        compose.onNodeWithText(completed.name).assertIsDisplayed()
    }
}

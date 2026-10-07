package com.example.purebrowser.ui.resources

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.HttpResponse
import com.example.purebrowser.download.HttpTransport
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The resolver/parser are real; only the HTTP boundary is faked. No network or storage writes.
 * T117: the save action runs the manifest read and preparation itself; rows surfaced by that read
 * stay on the same screen for confirm/re-pick, and failures keep a retry affordance.
 */
class HlsCompactUiTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 45_000)
    fun unreadLadderSurfacesAfterSave_unsupportedDisabled_accessChangeInvalidatesWithoutRefetch() {
        val transport = TestTransport(mapOf(ENTRY to MASTER, CHILD to MEDIA))
        val resolver = resolver(transport)
        val draft = draft()
        val submissions = mutableListOf<Submission>()
        compose.setContent {
            PureBrowserTheme {
                HlsDownloadConfirmation(draft, false, resolver, {}, onConfirm = { frozen, name, wifi, plan ->
                    submissions.add(Submission(frozen, name, wifi, plan))
                })
            }
        }
        compose.onNodeWithText("清单响应大小 123 B").assertExists()
        compose.onNodeWithText("成品大小未知（清单响应不代表视频大小）").assertExists()
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsEnabled()
        assertEquals(emptyList<String>(), transport.urls.toList())

        // The first save reads the ladder and stops for one explicit confirm of the surfaced rows.
        click("hls-save")
        awaitTag("hls-variant-0")
        compose.onNodeWithText("清单已读取：3 个清晰度，点选后保存。").assertExists()
        compose.onNodeWithTag("hls-variant-1").assertIsSelected() // ≤1080p-best default.
        compose.onNodeWithTag("hls-variant-2").assertIsNotEnabled().assertIsNotSelected()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        assertEquals(listOf(ENTRY), transport.urls.toList())

        compose.onNodeWithTag("download-use-context").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("hls-variant-0").assertDoesNotExist()
        compose.waitForIdle()
        assertEquals(listOf(ENTRY), transport.urls.toList()) // No automatic reparse.

        click("hls-save")
        awaitTag("hls-variant-0")
        click("hls-variant-0")
        compose.onNodeWithTag("hls-variant-0").assertIsSelected()
        compose.onNodeWithTag("download-file-name").performScrollTo().performTextReplacement("chosen.webm")
        compose.onNodeWithTag("download-file-name").performImeAction()
        click("hls-save")
        compose.waitUntil(10_000) { submissions.isNotEmpty() }
        compose.onNodeWithTag("hls-save").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            val submitted = submissions.single()
            assertEquals(draft.copy(useAccessContext = false), submitted.draft)
            assertEquals("chosen.mp4", submitted.name)
            assertFalse(submitted.wifi)
            assertEquals(CHILD, submitted.plan.playlistUrl)
            assertEquals(2, submitted.plan.media.segments.size)
        }
    }

    @Test(timeout = 60_000)
    fun busyPreparationHasIndeterminateProgress_andCancelAbortsWithoutSubmittingOrPublishing() {
        val gate = Gate()
        val transport = TestTransport(mapOf(ENTRY to MEDIA), gate)
        val resolver = resolver(transport)
        val visible = mutableStateOf(true)
        var dismissCalls = 0
        var confirmCalls = 0
        compose.setContent {
            PureBrowserTheme {
                if (visible.value) HlsDownloadConfirmation(draft(), false, resolver, {
                    dismissCalls++
                    visible.value = false
                }, onConfirm = { _, _, _, _ -> confirmCalls++ })
            }
        }
        try {
            click("hls-save")
            compose.waitUntil(10_000) { gate.entered.count == 0L }
            compose.onNodeWithTag("hls-preparing").performScrollTo().assertExists()
            compose.onNodeWithTag("hls-preparation-progress").performScrollTo().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在准备保存，尚未创建下载任务"))
            compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)
            compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("取消").performScrollTo().performClick()
            compose.waitUntil(10_000) { gate.cancelled.count == 0L }
            compose.waitUntil(10_000) { gate.finished.count == 0L }
            compose.onNodeWithTag("hls-plan-ready").assertDoesNotExist()
            compose.onNodeWithTag("hls-error").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, dismissCalls)
                assertEquals(0, confirmCalls)
            }
        } finally {
            gate.release.countDown()
        }
    }

    @Test(timeout = 30_000)
    fun rejectedPlaylistKeepsSafeActionableError_andSaveStaysEnabledForRetry_withoutExposingSignedUrl() {
        val transport = TestTransport(emptyMap(), status = 403)
        val resolver = resolver(transport)
        var confirmCalls = 0
        compose.setContent {
            PureBrowserTheme {
                HlsDownloadConfirmation(draft(), false, resolver, {}, onConfirm = { _, _, _, _ -> confirmCalls++ })
            }
        }
        click("hls-save")
        awaitTag("hls-error")
        compose.onNodeWithTag("hls-error").performScrollTo()
            .assert(androidx.compose.ui.test.hasText("当前访问条件不足，请返回来源重新发现"))
        // Failure honesty: same screen, retry affordance, no dead end.
        compose.onNodeWithText("可再次点“保存视频”重试，或点选其他清晰度后保存。").assertExists()
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText(ENTRY, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("private%2Bsignature", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, confirmCalls) }
    }

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun draft() = DownloadDraft(
        MediaCandidate(ENTRY, MediaKind.HLS, setOf(Evidence.DOM), sizeBytes = 123, frameUrl = PAGE, reliableSource = true),
        "test-agent", sourceUrl = PAGE, sourceTitle = "冻结来源", sourceTabId = "frozen-tab", sourceGeneration = 7,
    )

    private fun resolver(transport: TestTransport) =
        HlsResolver(transport, AccessContextProvider { "session=test-only" }, allowLocalHttp = false)

    private data class Submission(val draft: DownloadDraft, val name: String, val wifi: Boolean, val plan: HlsDownloadPlan)
    private class Gate {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
    }
    private class TestTransport(
        private val routes: Map<String, String>,
        private val gate: Gate? = null,
        private val status: Int = 200,
    ) : HttpTransport {
        val urls = CopyOnWriteArrayList<String>()
        override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation): HttpResponse {
            cancel.check()
            urls.add(url)
            if (gate != null) {
                cancel.bind { gate.cancelled.countDown(); gate.release.countDown() }
                gate.entered.countDown()
                try {
                    check(gate.release.await(45, TimeUnit.SECONDS)) { "Test request was not released" }
                    cancel.check()
                } finally {
                    gate.finished.countDown()
                }
            }
            val bytes = routes[url].orEmpty().toByteArray(Charsets.UTF_8)
            return object : HttpResponse {
                override val status = this@TestTransport.status
                override fun header(name: String): String? = when {
                    name.equals("Content-Length", ignoreCase = true) -> bytes.size.toString()
                    name.equals("Content-Type", ignoreCase = true) -> "application/vnd.apple.mpegurl"
                    else -> null
                }
                override fun body(): InputStream = ByteArrayInputStream(bytes)
                override fun close() { cancel.clear() }
            }
        }
    }

    companion object {
        private const val PAGE = "https://media.example/watch?source=frozen"
        private const val ENTRY = "https://media.example/entry.m3u8?token=private%2Bsignature"
        private const val CHILD = "https://media.example/720.m3u8"
        private val MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.42E01E,mp4a.40.2"
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.42E01E,mp4a.40.2"
            1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L93.B0,mp4a.40.2"
            unsupported.m3u8
        """.trimIndent()
        private val MEDIA = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            part-0.ts
            #EXTINF:4,
            part-1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
    }
}

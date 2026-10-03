package com.example.purebrowser.ui.resources

import android.app.DownloadManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import com.example.purebrowser.download.AccessContextProvider
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.download.DownloadItem
import com.example.purebrowser.download.DownloadProtocol
import com.example.purebrowser.download.HttpResponse
import com.example.purebrowser.download.HttpTransport
import com.example.purebrowser.download.TaskStatus
import com.example.purebrowser.download.TransferCancellation
import com.example.purebrowser.download.hls.HlsDownloadPlan
import com.example.purebrowser.download.hls.HlsResolver
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.theme.PureBrowserTheme
import com.example.purebrowser.ui.downloads.DownloadsScreen
import com.example.purebrowser.ui.downloads.byteSummary
import com.example.purebrowser.ui.downloads.progressFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Compose confirmation + real resolver/parser/client; only the HTTP boundary is faked. */
class HlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 60_000)
    fun mediaEntryRequiresExplicitParse_reusesOneRequest_andSavesFrozenPlanAndChoices() {
        val original = draft(ENTRY)
        val defaults = mutableStateOf(true)
        val fake = FakeTransport(mapOf(ENTRY to listOf(Reply(media(2)))))
        val access = FakeAccess()
        val resolver = HlsResolver(fake, access, allowLocalHttp = false)
        val submissions = mutableListOf<Submission>()
        compose.setContent {
            PureBrowserTheme {
                HlsDownloadConfirmation(original, defaults.value, resolver, {}, canUseWifi = { true }, onConfirm = { frozen, name, wifi, plan ->
                    submissions.add(Submission(frozen, name, wifi, plan))
                })
            }
        }

        compose.onNodeWithText("确认下载 HLS").assertExists()
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("download-file-name").performScrollTo().performTextReplacement("chosen.mp4")
        compose.onNodeWithTag("download-file-name").performImeAction()
        compose.onNodeWithTag("download-file-name").assertIsNotFocused()
        compose.onNode(isToggleable() and hasText("仅 Wi-Fi")).performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { defaults.value = false }
        compose.runOnIdle { defaults.value = true }
        compose.onNode(isToggleable() and hasText("仅 Wi-Fi")).assertIsOff()
        assertEquals(0, fake.requests.size)
        assertEquals(0, access.calls.size)

        click("hls-parse-playlist")
        awaitReady()
        assertEquals(listOf(ENTRY), fake.urls())
        compose.onNodeWithTag("hls-prepare-variant").assertDoesNotExist()
        compose.onNodeWithText("清单已准备 · 2 个分片").assertExists()
        compose.onNodeWithText("清单时长：0 分 8 秒（清单声明）").assertExists()
        compose.onNodeWithText("成品大小未知；清单响应大小不是视频大小。下载完成并封装校验后才保存 MP4。").assertExists()
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            val saved = submissions.single()
            assertEquals(original, saved.draft)
            assertEquals("chosen.mp4", saved.name)
            assertFalse(saved.wifiOnly)
            assertEquals(ENTRY, saved.plan.entryUrl)
            assertEquals(ENTRY, saved.plan.playlistUrl)
            assertNull(saved.plan.variant)
            assertEquals(2, saved.plan.media.segments.size)
            assertEquals(8_000_000L, saved.plan.media.durationUs)
            assertEquals(listOf("${BASE}segment-0.ts", "${BASE}segment-1.ts"), saved.plan.media.segments.map { it.url })
        }
        assertEquals("test-session=ephemeral", fake.requests.single().headers["Cookie"])
        assertEquals(listOf(ENTRY), fake.urls()) // Neither reuse nor Save may GET the media entry again.
    }

    @Test(timeout = 60_000)
    fun masterDefaultsTo1080_manualSelectionDoesNotFetch_andPrepareReadsOnlySelectedChild() {
        val fake = FakeTransport(mapOf(ENTRY to listOf(Reply(MASTER)), LOW to listOf(Reply(media(3)))))
        val submissions = show(draft(ENTRY), fake)
        assertEquals(0, fake.requests.size)
        click("hls-parse-playlist")
        awaitVariantList()
        compose.onNodeWithTag("hls-variant-1").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("hls-variant-2").assertIsNotSelected() // Supported 2160p is not the default.
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(ENTRY), fake.urls())

        click("hls-variant-0")
        compose.onNodeWithTag("hls-variant-0").assertIsSelected()
        compose.onNodeWithTag("hls-variant-1").assertIsNotSelected()
        assertEquals(listOf(ENTRY), fake.urls())
        click("hls-prepare-variant")
        awaitReady()
        assertEquals(listOf(ENTRY, LOW), fake.urls())
        click("hls-save")
        compose.runOnIdle {
            val saved = submissions.single()
            assertEquals(LOW, saved.plan.variant?.url)
            assertEquals(720, saved.plan.variant?.height)
            assertEquals(1280, saved.plan.variant?.width)
            assertEquals(LOW, saved.plan.playlistUrl)
            assertEquals(3, saved.plan.media.segments.size)
        }
    }

    @Test(timeout = 60_000)
    fun unsupportedVariantShowsReason_isDisabled_andCannotChangeSelectionOrFetch() {
        val fake = FakeTransport(mapOf(ENTRY to listOf(Reply(MASTER))))
        val submissions = show(draft(ENTRY), fake)
        click("hls-parse-playlist")
        awaitVariantList()
        compose.onNodeWithTag("hls-variant-3").performScrollTo().assertIsNotEnabled().assertIsNotSelected().performClick()
        compose.onNodeWithText("本版只支持 H.264 与 AAC 编码").assertExists()
        compose.onNodeWithTag("hls-variant-1").assertIsSelected()
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(ENTRY), fake.urls())
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
    }

    @Test(timeout = 60_000)
    fun accessCheckboxInvalidatesReadyPlan_withoutAutomaticFetch_andReparseUsesNewChoice() {
        val fake = FakeTransport(mapOf(ENTRY to listOf(Reply(media(2)), Reply(media(3)))))
        val access = FakeAccess()
        val submissions = show(draft(ENTRY), fake, access)
        click("hls-parse-playlist")
        awaitReady()
        compose.onNodeWithTag("download-use-context").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("download-use-context").assertIsOff()
        compose.onNodeWithTag("hls-plan-ready").assertDoesNotExist()
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
        compose.waitForIdle()
        assertEquals(listOf(ENTRY), fake.urls())
        assertEquals(listOf(ENTRY), access.calls.toList())

        click("hls-parse-playlist")
        awaitReady()
        assertEquals(listOf(ENTRY, ENTRY), fake.urls())
        assertEquals(1, access.calls.size)
        assertNull(fake.requests.last().headers["Cookie"])
        assertNull(fake.requests.last().headers["Referer"])
        click("hls-save")
        compose.runOnIdle {
            assertFalse(submissions.single().draft.useAccessContext)
            assertEquals(3, submissions.single().plan.media.segments.size)
        }
    }

    @Test(timeout = 60_000)
    fun accessChangeDuringReadCancels_andLateOldResultCannotRestorePreviousPlan() {
        val gate = CloseGate()
        val old = Reply(media(2), gate)
        val fake = FakeTransport(mapOf(ENTRY to listOf(old, Reply(media(3)))))
        val submissions = show(draft(ENTRY), fake)
        try {
            click("hls-parse-playlist")
            awaitLatch(gate.entered)
            compose.onNodeWithTag("download-use-context").performScrollTo().performClick()
            awaitLatch(gate.cancelled)
            compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
            assertEquals(listOf(ENTRY), fake.urls())
            click("hls-parse-playlist")
            awaitReady()
            gate.release.countDown()
            awaitLatch(old.finished)
            compose.waitForIdle()
            compose.onNodeWithText("清单已准备 · 3 个分片").assertExists()
            compose.onNodeWithText("清单已准备 · 2 个分片").assertDoesNotExist()
            compose.onNodeWithTag("hls-error").assertDoesNotExist()
            click("hls-save")
            compose.runOnIdle {
                assertFalse(submissions.single().draft.useAccessContext)
                assertEquals(3, submissions.single().plan.media.segments.size)
            }
        } finally {
            gate.release.countDown()
        }
    }

    @Test(timeout = 60_000)
    fun dismissalCancelsRead_andLateResultCannotUpdateReplacementDraftOrSubmit() {
        val gate = CloseGate()
        val old = Reply(media(2), gate)
        val fake = FakeTransport(mapOf(ENTRY to listOf(old), NEW_ENTRY to listOf(Reply(media(3)))))
        val resolver = HlsResolver(fake, FakeAccess(), allowLocalHttp = false)
        val active = mutableStateOf<DownloadDraft?>(draft(ENTRY))
        val submissions = mutableListOf<Submission>()
        var dismissCalls = 0
        compose.setContent {
            PureBrowserTheme {
                active.value?.let { selected ->
                    HlsDownloadConfirmation(selected, true, resolver, {
                        dismissCalls++
                        active.value = null
                    }, canUseWifi = { true }, onConfirm = { frozen, name, wifi, plan -> submissions.add(Submission(frozen, name, wifi, plan)) })
                }
            }
        }
        try {
            click("hls-parse-playlist")
            awaitLatch(gate.entered)
            compose.onNodeWithText("取消").performScrollTo().performClick()
            awaitLatch(gate.cancelled)
            compose.onNodeWithText("确认下载 HLS").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, dismissCalls)
                assertTrue(submissions.isEmpty())
                active.value = draft(NEW_ENTRY).copy(sourceTabId = "replacement-tab", sourceGeneration = 42)
            }
            compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
            assertEquals(listOf(ENTRY), fake.urls())
            click("hls-parse-playlist")
            awaitReady()
            gate.release.countDown()
            awaitLatch(old.finished)
            compose.waitForIdle()
            compose.onNodeWithText("清单已准备 · 3 个分片").assertExists()
            compose.onNodeWithText("清单已准备 · 2 个分片").assertDoesNotExist()
            compose.onNodeWithTag("hls-error").assertDoesNotExist()
            click("hls-save")
            compose.runOnIdle {
                assertEquals(1, submissions.size)
                assertEquals(NEW_ENTRY, submissions.single().plan.entryUrl)
                assertEquals("replacement-tab", submissions.single().draft.sourceTabId)
                assertEquals(42L, submissions.single().draft.sourceGeneration)
                assertEquals(3, submissions.single().plan.media.segments.size)
            }
        } finally {
            gate.release.countDown()
        }
    }

    @Test(timeout = 60_000)
    fun switchingVariantCancelsChildRead_invalidatesPlan_andDoesNotFetchNewChildUntilPrepare() {
        val gate = CloseGate()
        val old = Reply(media(2), gate)
        val fake = FakeTransport(mapOf(ENTRY to listOf(Reply(MASTER)), HIGH to listOf(old), LOW to listOf(Reply(media(3)))))
        val submissions = show(draft(ENTRY), fake)
        try {
            click("hls-parse-playlist")
            awaitVariantList()
            click("hls-prepare-variant") // Default 1080p child read is intentionally held open.
            awaitLatch(gate.entered)
            click("hls-variant-0")
            awaitLatch(gate.cancelled)
            compose.onNodeWithTag("hls-variant-0").assertIsSelected()
            compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
            assertEquals(listOf(ENTRY, HIGH), fake.urls())
            click("hls-prepare-variant")
            awaitReady()
            gate.release.countDown()
            awaitLatch(old.finished)
            compose.waitForIdle()
            assertEquals(listOf(ENTRY, HIGH, LOW), fake.urls())
            compose.onNodeWithText("清单已准备 · 3 个分片").assertExists()
            compose.onNodeWithTag("hls-error").assertDoesNotExist()
            click("hls-save")
            compose.runOnIdle {
                assertEquals(LOW, submissions.single().plan.variant?.url)
                assertEquals(3, submissions.single().plan.media.segments.size)
            }
        } finally {
            gate.release.countDown()
        }
    }

    @Test(timeout = 60_000)
    fun runningShowsSegmentProgress_muxingIsIndeterminate_evenWhenByteCounterLooksComplete() {
        val running = DownloadItem(
            id = "hls-progress", name = "result.mp4", status = DownloadManager.STATUS_RUNNING,
            bytes = 4096, total = 4096, detail = "正在下载分片", taskStatus = TaskStatus.RUNNING,
            protocol = DownloadProtocol.HLS, segmentCount = 2, completedSegments = 1,
        )
        val item = mutableStateOf(running)
        assertEquals(0.5f, running.progressFraction()!!, 0.001f)
        assertFalse(running.byteSummary().contains("%"))
        compose.setContent {
            PureBrowserTheme {
                DownloadsScreen(listOf(item.value), emptySet(), {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-segments-hls-progress"))
        compose.onNodeWithTag("download-segments-hls-progress").assert(hasText("已下载分片 1 / 2（不是整体保存进度）"))
        compose.onNodeWithTag("download-progress-hls-progress").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, 0f..1f)),
        )
        val muxing = running.copy(taskStatus = TaskStatus.MUXING, completedSegments = 2, detail = "正在封装 MP4")
        assertNull(muxing.progressFraction())
        assertFalse(muxing.byteSummary().contains("%"))
        assertTrue(muxing.byteSummary().contains("KB 已传输"))
        compose.runOnIdle { item.value = muxing }
        compose.onNodeWithTag("download-segments-hls-progress").assertDoesNotExist()
        compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-progress-hls-progress"))
        compose.onNodeWithTag("download-progress-hls-progress").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "正在封装 MP4，尚未保存成品，不显示整体百分比"))
        compose.onNodeWithTag("download-bytes-hls-progress").assert(hasText(muxing.byteSummary())).assert(hasText("总大小未知", substring = true))
        compose.onNodeWithTag("download-status-hls-progress").assert(hasText("正在封装 MP4 · 尚未保存"))
        compose.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
    }

    @Test(timeout = 60_000)
    fun wifiOnlyBlocksParse_withoutFetching_orAutoRestartingWhenWifiReturns() {
        val wifi = mutableStateOf(false)
        val fake = FakeTransport(mapOf(ENTRY to listOf(Reply(media(2)))))
        show(draft(ENTRY), fake, canUseWifi = { wifi.value })
        click("hls-parse-playlist")
        compose.onNodeWithTag("hls-wifi-required").assertExists()
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
        assertEquals(0, fake.requests.size)
        compose.runOnIdle { wifi.value = true }
        compose.waitForIdle()
        assertEquals(0, fake.requests.size)
        compose.onNodeWithTag("hls-plan-ready").assertDoesNotExist()
        click("hls-parse-playlist")
        awaitReady()
        assertEquals(listOf(ENTRY), fake.urls())
        compose.onNodeWithTag("hls-wifi-required").assertDoesNotExist()
    }

    @Test(timeout = 60_000)
    fun wifiOnlyAlsoBlocksPrepare_turningItOffDoesNotFetchUntilExplicitPrepare() {
        val wifi = mutableStateOf(true)
        val fake = FakeTransport(mapOf(ENTRY to listOf(Reply(MASTER)), HIGH to listOf(Reply(media(2)))))
        val submissions = show(draft(ENTRY), fake, canUseWifi = { wifi.value })
        click("hls-parse-playlist")
        awaitVariantList()
        compose.runOnIdle { wifi.value = false }
        click("hls-prepare-variant")
        compose.onNodeWithTag("hls-wifi-required").assertExists()
        assertEquals(listOf(ENTRY), fake.urls())
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsNotEnabled()
        compose.onNode(isToggleable() and hasText("仅 Wi-Fi")).performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertEquals(listOf(ENTRY), fake.urls())
        compose.onNodeWithTag("hls-wifi-required").assertDoesNotExist()
        click("hls-prepare-variant")
        awaitReady()
        assertEquals(listOf(ENTRY, HIGH), fake.urls())
        click("hls-save")
        compose.runOnIdle { assertFalse(submissions.single().wifiOnly) }
    }

    private fun show(
        draft: DownloadDraft, fake: FakeTransport, access: FakeAccess = FakeAccess(),
        canUseWifi: () -> Boolean = { true },
    ): MutableList<Submission> {
        val resolver = HlsResolver(fake, access, allowLocalHttp = false)
        val submissions = mutableListOf<Submission>()
        compose.setContent {
            PureBrowserTheme {
                HlsDownloadConfirmation(draft, true, resolver, {}, canUseWifi = canUseWifi, onConfirm = { frozen, name, wifi, plan ->
                    submissions.add(Submission(frozen, name, wifi, plan))
                })
            }
        }
        return submissions
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
    }

    private fun awaitReady() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("hls-plan-ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("hls-save").performScrollTo().assertIsEnabled()
    }

    private fun awaitVariantList() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("hls-variant-0").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitLatch(latch: CountDownLatch) {
        compose.waitUntil(timeoutMillis = 10_000) { latch.count == 0L }
    }

    private fun draft(url: String) = DownloadDraft(
        candidate = MediaCandidate(url, MediaKind.HLS, setOf(Evidence.DOM),
            mimeType = "application/vnd.apple.mpegurl", sizeBytes = 123,
            frameUrl = "${BASE}watch", reliableSource = true),
        userAgent = "hls-ui-test", sourceUrl = "${BASE}watch?source=frozen",
        sourceTitle = "原始来源页", sourceTabId = "frozen-tab", sourceGeneration = 7,
        useAccessContext = true,
    )

    private data class Submission(val draft: DownloadDraft, val name: String, val wifiOnly: Boolean, val plan: HlsDownloadPlan)
    private data class Request(val url: String, val headers: Map<String, String>)
    private class FakeAccess : AccessContextProvider {
        val calls = CopyOnWriteArrayList<String>()
        override fun cookieFor(target: String): String {
            calls.add(target)
            return "test-session=ephemeral"
        }
    }

    /** Pause AFTER the client consumed the body, so a cancelled old resolver can finish late. */
    private class CloseGate {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun awaitRelease() {
            entered.countDown()
            check(release.await(45, TimeUnit.SECONDS)) { "Test response was not released" }
        }
    }

    private class Reply(text: String, val gate: CloseGate? = null) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val finished = CountDownLatch(1)
    }

    private class FakeTransport(routes: Map<String, List<Reply>>) : HttpTransport {
        private val replies = ConcurrentHashMap<String, ConcurrentLinkedQueue<Reply>>().apply {
            routes.forEach { (url, values) -> put(url, ConcurrentLinkedQueue(values)) }
        }
        val requests = CopyOnWriteArrayList<Request>()
        fun urls(): List<String> = requests.map { it.url }
        override fun open(url: String, headers: Map<String, String>, cancel: TransferCancellation): HttpResponse {
            cancel.check()
            val reply = checkNotNull(replies[url]?.poll()) { "Unexpected test playlist request" }
            requests.add(Request(url, headers.toMap()))
            cancel.bind { reply.gate?.cancelled?.countDown() }
            return object : HttpResponse {
                override val status = 200
                override fun header(name: String): String? = when {
                    name.equals("Content-Length", true) -> reply.bytes.size.toString()
                    name.equals("Content-Type", true) -> "application/vnd.apple.mpegurl"
                    else -> null
                }
                override fun body(): InputStream = ByteArrayInputStream(reply.bytes)
                override fun close() {
                    try {
                        reply.gate?.awaitRelease()
                    } finally {
                        cancel.clear()
                        reply.finished.countDown()
                    }
                }
            }
        }
    }

    companion object {
        private const val BASE = "https://media.example/"
        private const val ENTRY = "${BASE}entry.m3u8?token=original%2Bsignature"
        private const val NEW_ENTRY = "${BASE}replacement.m3u8?token=replacement"
        private const val LOW = "${BASE}720.m3u8"
        private const val HIGH = "${BASE}1080.m3u8"
        private val MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.42E01E,mp4a.40.2"
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.42E01E,mp4a.40.2"
            1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=3840x2160,CODECS="avc1.42E01E,mp4a.40.2"
            2160.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L93.B0,mp4a.40.2"
            unsupported.m3u8
        """.trimIndent()

        private fun media(count: Int): String = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n")
            repeat(count) { index -> append("#EXTINF:4,\nsegment-$index.ts\n") }
            append("#EXT-X-ENDLIST\n")
        }
    }
}

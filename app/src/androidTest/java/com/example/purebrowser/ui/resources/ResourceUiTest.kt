package com.example.purebrowser.ui.resources

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated T14 coverage: no MainActivity, WebView, storage, permissions or download service. */
class ResourceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test(timeout = 30_000)
    fun fileAndHlsAreSelectable_otherProtocolsExpandWithoutFakeDownloadButtons() {
        val file = fileCandidate()
        val hls = MediaCandidate("https://media.example/playlist.m3u8?token=hls-secret", MediaKind.HLS, setOf(Evidence.REQUEST))
        assertTrue(hls.canTryDownload())
        val others = listOf(
            MediaCandidate("https://media.example/manifest.mpd?token=dash-secret", MediaKind.DASH, setOf(Evidence.REQUEST)),
            MediaCandidate("blob:https://page.example/local-id", MediaKind.LOCAL, setOf(Evidence.DOM), mimeType = "video/mp4"),
        )
        val explanations = listOf(
            "这是 DASH 播放清单，音频和视频可能分开传输。目前不支持分片下载与音视频合并。",
            "这是播放器在当前页面中创建的本地媒体地址，不是独立文件直链。请播放视频后，再查看是否发现底层视频直链。",
        )
        val selected = mutableListOf<MediaCandidate>()
        compose.setContent {
            PureBrowserTheme {
                ResourceSheet(listOf(file, hls) + others, onDismiss = {}, onSelect = { selected.add(it) }, onSource = {})
            }
        }

        compose.onNodeWithText("1 个可尝试的直链 · 1 个 HLS 清单 · 2 个其他媒体资源").assertExists()
        scrollSheetTo(file.displayName)
        cardFor(file).assert(hasAnyDescendant(hasText("尝试下载") and hasClickAction()))
        scrollSheetTo(hls.displayName)
        cardFor(hls).assert(hasAnyDescendant(hasText("尝试下载") and hasClickAction()))
        others.forEach { compose.onAllNodesWithText(it.displayName).assertCountEquals(0) }

        clickSheetText("其他媒体资源（2） · 展开")
        others.zip(explanations).forEach { (candidate, explanation) ->
            scrollSheetTo(candidate.displayName)
            compose.onNodeWithText(candidate.displayName).assertIsDisplayed()
            val card = cardFor(candidate)
            card.assert(hasAnyDescendant(hasText(explanation)))
            card.assert(hasAnyDescendant(hasText("大小未知")))
            card.assert(hasAnyDescendant(hasText("查看详情") and hasClickAction()))
            card.assert(!hasAnyDescendant(hasText("尝试下载")))
            // Count every OnClick, including disabled controls: only the real details action
            // may exist. An extra disabled/no-op download button must fail this assertion.
            assertEquals("Unexpected action in ${candidate.kind} card", 1, clickActionCount(card.fetchSemanticsNode()))
        }
        compose.runOnIdle { assertTrue("Expansion must not select/download anything", selected.isEmpty()) }

        clickSheetText("其他媒体资源（2） · 收起")
        others.forEach { compose.onAllNodesWithText(it.displayName).assertCountEquals(0) }
        scrollSheetTo(file.displayName)
        compose.onNode(hasText("尝试下载") and hasClickAction() and
            hasAnyAncestor(hasTestTag("resource-card-${file.displayName}"))).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(file), selected) }
    }

    @Test(timeout = 30_000)
    fun normalCardHidesQuery_detailsKeepExactUrlAndEvidence_andSourceCallbackWorks() {
        val candidate = fileCandidate().copy(sources = setOf(Evidence.DOM, Evidence.REQUEST))
        var sourceCalls = 0
        var selectionCalls = 0
        compose.setContent {
            PureBrowserTheme {
                ResourceSheet(
                    candidates = listOf(candidate),
                    onDismiss = {},
                    onSelect = { selectionCalls++ },
                    onSource = { sourceCalls++ },
                )
            }
        }

        scrollSheetTo(candidate.displayName)
        val card = cardFor(candidate)
        card.assert(hasAnyDescendant(hasText("资源主机：media.example")))
        card.assert(hasAnyDescendant(hasText("视频直链 · video/mp4")))
        card.assert(hasAnyDescendant(hasText("响应大小 640 B")))
        compose.onAllNodesWithText(candidate.url, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("private%2Bsignature", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("网络请求 / 视频元素").assertCountEquals(0)

        clickSheetText("查看详情")
        compose.onNodeWithText("资源详情").assertExists()
        // Exact matching catches query stripping, decoding %2B/%2F, reordering, or fragment loss.
        compose.onNodeWithText(candidate.url, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("网络请求 / 视频元素").assertExists()
        compose.onNodeWithText("地址协议：https").assertExists()
        clickDialogText("返回来源页", dialogTitle = "资源详情")
        compose.onAllNodesWithText(candidate.url, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(1, sourceCalls)
            assertEquals(0, selectionCalls)
        }
    }

    @Test(timeout = 30_000)
    fun domFilePrecedesRequestFile_evenWhenDomManifestArrivesFirst() {
        val requestFile = fileCandidate("network-first.mp4").copy(sources = setOf(Evidence.REQUEST))
        val domFile = fileCandidate("dom-second.mp4")
        val domManifest = MediaCandidate("https://media.example/dom.m3u8", MediaKind.HLS, setOf(Evidence.DOM))
        val selected = mutableListOf<MediaCandidate>()
        compose.setContent {
            PureBrowserTheme {
                ResourceSheet(
                    candidates = listOf(domManifest, requestFile, domFile),
                    onDismiss = {},
                    onSelect = { selected.add(it) },
                    onSource = {},
                )
            }
        }

        compose.onNodeWithText("2 个可尝试的直链 · 1 个 HLS 清单 · 0 个其他媒体资源").assertExists()
        // Start at the top and select the first real action, not a named candidate's action.
        // This checks visible order instead of reimplementing the production sort in the test.
        scrollSheetTo("尝试下载")
        compose.onAllNodes(hasText("尝试下载") and hasClickAction()).onFirst()
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(domFile), selected) }
        scrollSheetTo(requestFile.displayName)
        compose.onNodeWithText(requestFile.displayName).assertIsDisplayed()
        scrollSheetTo(domManifest.displayName)
        cardFor(domManifest).assert(hasAnyDescendant(hasText("尝试下载") and hasClickAction()))
    }

    @Test(timeout = 30_000)
    fun selectedDraftStaysFrozen_filenameIsSafelyEdited_wifiOverrideSurvivesDefaultRefresh() {
        val original = DownloadDraft(
            candidate = fileCandidate(),
            userAgent = "resource-test-agent",
            sourceUrl = "https://page.example/watch?source=original%2Bpage",
            sourceTitle = "原始来源页",
            sourceTabId = "original-tab",
            sourceGeneration = 7,
        )
        val currentPage = mutableStateOf(original)
        val selection = mutableStateOf<DownloadDraft?>(null)
        val wifiDefault = mutableStateOf(true)
        val submissions = mutableListOf<Submission>()
        compose.setContent {
            PureBrowserTheme {
                val page = currentPage.value
                val draft = selection.value
                if (draft == null) {
                    ResourceSheet(
                        candidates = listOf(page.candidate),
                        onDismiss = {},
                        onSelect = { candidate -> selection.value = page.copy(candidate = candidate) },
                        onSource = {},
                    )
                } else {
                    DownloadConfirmation(
                        draft = draft,
                        defaultWifiOnly = wifiDefault.value,
                        onDismiss = { selection.value = null },
                        onConfirm = { name, wifi, _ -> submissions.add(Submission(draft, name, wifi)) },
                    )
                }
            }
        }

        clickSheetText("尝试下载")
        compose.onNodeWithText("确认下载直链").assertExists()
        compose.onNodeWithTag("download-file-name").assert(hasText("sample.mp4"))
        wifiControl().performScrollTo().assertIsOn().performClick().assertIsOff()
        val unsafeName = "../edited\\clip\u0001.mp4"
        val fileNameField = compose.onNodeWithTag("download-file-name")
        fileNameField.performScrollTo().performClick()
        // Text replacement returns Unit, not a node interaction to chain IME actions on.
        fileNameField.performTextReplacement(unsafeName)
        fileNameField.performImeAction()
        compose.onNodeWithTag("download-file-name").assertIsNotFocused()
        compose.onNodeWithText("安全文件名：.._edited_clip_.mp4").assertExists()

        val newPage = original.copy(
            candidate = fileCandidate("replacement.webm").copy(url = "https://other-media.example/replacement.webm?token=new-page"),
            sourceUrl = "https://other-page.example/watch",
            sourceTitle = "后来切换的页面",
            sourceTabId = "other-tab",
            sourceGeneration = 99,
            userAgent = "replacement-agent",
        )
        compose.runOnIdle {
            currentPage.value = newPage
            wifiDefault.value = false
        }
        wifiControl().assertIsOff()
        compose.runOnIdle { wifiDefault.value = true }
        wifiControl().assertIsOff()
        compose.onNodeWithText(original.candidate.displayName).assertExists()
        compose.onNodeWithText("来源页面：原始来源页").assertExists()
        compose.onNodeWithText("来源主机：page.example").assertExists()
        compose.onAllNodesWithText(newPage.candidate.displayName).assertCountEquals(0)
        compose.onNodeWithTag("download-file-name").assert(hasText(unsafeName))

        clickDialogText("开始下载")
        compose.onNodeWithText("开始下载").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submissions.size)
            val submission = submissions.single()
            assertEquals(original, submission.draft)
            assertEquals(original.candidate.url, submission.draft.candidate.url)
            assertEquals(".._edited_clip_.mp4", submission.fileName)
            assertFalse(submission.fileName.any { it == '/' || it == '\\' || it.isISOControl() })
            assertFalse(submission.wifiOnly)
            assertEquals(original, selection.value)
        }
    }

    @Test(timeout = 30_000)
    fun falseWifiDefaultAndSuggestedNameAreSubmittedWithoutAnOverride() {
        val draft = DownloadDraft(fileCandidate(), "resource-test-agent")
        val submissions = mutableListOf<Submission>()
        compose.setContent {
            PureBrowserTheme {
                DownloadConfirmation(
                    draft = draft,
                    defaultWifiOnly = false,
                    onDismiss = {},
                    onConfirm = { name, wifi, _ -> submissions.add(Submission(draft, name, wifi)) },
                )
            }
        }

        compose.onNodeWithText("确认下载直链").assertExists()
        compose.onNodeWithTag("download-file-name").assert(hasText("sample.mp4"))
        wifiControl().performScrollTo().assertIsOff()
        clickDialogText("开始下载")
        compose.runOnIdle { assertEquals(listOf(Submission(draft, "sample.mp4", false)), submissions) }
    }

    @Test(timeout = 30_000)
    fun invalidNamesCannotSubmit_andCancelOnlyDismisses() {
        var confirmCalls = 0
        var dismissCalls = 0
        compose.setContent {
            PureBrowserTheme {
                DownloadConfirmation(
                    draft = DownloadDraft(fileCandidate(), "resource-test-agent"),
                    defaultWifiOnly = true,
                    onDismiss = { dismissCalls++ },
                    onConfirm = { _, _, _ -> confirmCalls++ },
                )
            }
        }

        listOf("", ".", "..").forEach { invalidName ->
            compose.onNodeWithTag("download-file-name").performScrollTo().performTextReplacement(invalidName)
            compose.onNodeWithText("请输入有效的文件名").assertExists()
            compose.onNodeWithText("开始下载").performScrollTo().assertIsNotEnabled().performClick()
        }
        compose.onNodeWithTag("download-file-name").performScrollTo().performTextReplacement("recovered.mp4")
        compose.onNodeWithText("开始下载").assertIsEnabled()
        clickDialogText("取消")
        compose.runOnIdle {
            assertEquals(0, confirmCalls)
            assertEquals(1, dismissCalls)
        }
    }

    private fun scrollSheetTo(text: String) {
        compose.onNodeWithTag("resource-sheet").performScrollToNode(hasText(text))
    }

    private fun clickSheetText(text: String) {
        scrollSheetTo(text)
        compose.onNodeWithText(text).assertIsDisplayed().performClick()
    }

    private fun clickDialogText(text: String, dialogTitle: String = "确认下载直链") {
        // The underlying ModalBottomSheet is also a dialog. Its source action must not
        // match the detail dialog's action, so identify the dialog by its own title.
        val owningDialog = isDialog() and hasAnyDescendant(hasText(dialogTitle))
        compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(owningDialog))
            .performScrollTo().assertIsDisplayed().performClick()
    }

    // Button labels are merged into the clickable button. In the unmerged tree the Text
    // and OnClick belong to different nodes, so combined text/action matchers cannot match.
    private fun cardFor(candidate: MediaCandidate): SemanticsNodeInteraction =
        compose.onNodeWithTag("resource-card-${candidate.displayName}")

    private fun wifiControl(): SemanticsNodeInteraction =
        compose.onNode(isToggleable() and hasText("仅 Wi-Fi"))

    private fun clickActionCount(node: SemanticsNode): Int = node.children.sumOf { child ->
        (if (SemanticsActions.OnClick in child.config) 1 else 0) + clickActionCount(child)
    }

    private fun fileCandidate(name: String = "sample.mp4") = MediaCandidate(
        url = "https://media.example/$name?token=private%2Bsignature&part=%2Fraw#original",
        kind = MediaKind.FILE,
        sources = setOf(Evidence.DOM),
        mimeType = "video/mp4",
        sizeBytes = 640,
    )

    private data class Submission(val draft: DownloadDraft, val fileName: String, val wifiOnly: Boolean)

    @Test fun extensionlessDomCandidateCanBeSelectedForControlledValidation() {
        val unknown=MediaCandidate("https://media.example/mystery?token=exact",MediaKind.UNKNOWN,setOf(Evidence.DOM))
        val selected=mutableListOf<MediaCandidate>()
        compose.setContent { PureBrowserTheme { ResourceSheet(listOf(unknown),{}, {selected.add(it)}, {}) } }
        clickSheetText("尝试下载")
        compose.runOnIdle { assertEquals(listOf(unknown),selected) }
    }
}

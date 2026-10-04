package com.example.purebrowser.ui.resources

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.compose.ui.unit.dp
import com.example.purebrowser.download.DownloadDraft
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** T45 contracts use action semantics, not visible labels on now-icon-only regular actions. */
class ResourceCompactUiTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 30_000)
    fun lineActionsKeepTagsAndTouchTargets_detailsReplaceSheetAndFreezeAllEvidence() {
        val evidence = linkedSetOf(Evidence.DOM, Evidence.REQUEST, Evidence.TIMING, Evidence.DOWNLOAD)
        val original = candidate().copy(sources = evidence)
        val observations = mutableStateOf(listOf(original))
        val selections = mutableListOf<MediaCandidate>()
        var sourceCalls = 0
        compose.setContent {
            PureBrowserTheme {
                ResourceSheet(observations.value, {}, { selections.add(it) }, { sourceCalls++ })
            }
        }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("适用的同源网站会话和最小来源条件", substring = true).assertExists()
        compose.onAllNodesWithText("仅支持公开文件", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("resource-card-${original.displayName}")
            .assert(hasAnyDescendant(hasText("响应大小 640 B")))
        compose.onAllNodesWithText(original.url, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("网络请求 / 视频元素 / 资源时间线 / 下载回调").assertCountEquals(0)
        compose.onNodeWithTag(resourceSaveTag(original.url)).assert(hasContentDescription("尝试下载"))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("查看详情").assert(hasClickAction())
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()

        // Replacing observations and mutating the producer's set must not rewrite the open detail.
        compose.runOnIdle {
            evidence.clear()
            observations.value = listOf(candidate("replacement.mp4"))
        }
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.onNodeWithText("资源详情").assertExists()
        compose.onNodeWithTag("resource-sheet").performScrollToNode(hasText(original.url))
        compose.onNodeWithText(original.url, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("网络请求 / 视频元素 / 资源时间线 / 下载回调").assertExists()
        compose.onNodeWithText("地址协议：https").assertExists()
        compose.onNodeWithTag("resource-sheet").performScrollToNode(hasContentDescription("返回来源页"))
        compose.onNodeWithContentDescription("返回来源页").performClick()
        compose.onAllNodesWithText(original.url, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(1, sourceCalls)
            assertEquals(emptyList<MediaCandidate>(), selections)
        }
    }

    @Test(timeout = 30_000)
    fun detailBackReturnsToListStep_thenSheetDismisses_withoutTaskCreation() {
        val file = candidate()
        val visible = mutableStateOf(true)
        var dismissCalls = 0
        var selectCalls = 0
        compose.setContent {
            PureBrowserTheme {
                if (visible.value) ResourceSheet(listOf(file), {
                    dismissCalls++
                    visible.value = false
                }, { selectCalls++ }, {})
            }
        }
        compose.onNodeWithTag("查看详情").performClick()
        compose.onNodeWithText("资源详情").assertExists()
        Espresso.pressBack()
        compose.onNodeWithText("页面资源").assertIsDisplayed()
        compose.onNodeWithTag("resource-card-${file.displayName}").assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(1)
        compose.runOnIdle { assertEquals(0, dismissCalls) }
        Espresso.pressBack()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle {
            assertEquals(1, dismissCalls)
            assertEquals(0, selectCalls)
        }
    }

    @Test(timeout = 30_000)
    fun equalUrlsWithDifferentEvidenceAreNotDeduplicated_andUnsupportedHasNoSaveAction() {
        val file = candidate()
        val dash = MediaCandidate("https://media.example/manifest.mpd", MediaKind.DASH, setOf(Evidence.REQUEST))
        compose.setContent {
            PureBrowserTheme {
                ResourceSheet(listOf(file, file.copy(sources = setOf(Evidence.REQUEST)), dash), {}, {}, {})
            }
        }
        compose.onNodeWithText("2 个可尝试的直链 · 1 个其他媒体资源").assertExists()
        compose.onAllNodesWithTag("resource-card-${file.displayName}").assertCountEquals(2)
        compose.onNodeWithTag("resource-sheet").performScrollToNode(hasText("其他媒体资源（1） · 展开"))
        compose.onNodeWithText("其他媒体资源（1） · 展开").performClick()
        compose.onNodeWithTag("resource-sheet").performScrollToNode(hasText(dash.displayName))
        compose.onNodeWithTag("resource-card-${dash.displayName}")
            .assert(hasAnyDescendant(hasContentDescription("查看详情")))
        compose.onNodeWithTag(resourceSaveTag(dash.url)).assertDoesNotExist()
        compose.onNodeWithText(dash.unsupportedExplanation()).assertExists()
    }

    @Test(timeout = 30_000)
    fun directConfirmationKeepsFrozenSourceEditsAndAccessChoice_andSubmitsOnlyOnce() {
        val original = DownloadDraft(candidate().copy(frameUrl = PAGE, reliableSource = true), "test-agent",
            sourceUrl = PAGE, sourceTitle = "冻结来源", sourceTabId = "frozen-tab", sourceGeneration = 7,
            useAccessContext = true)
        val defaults = mutableStateOf(true)
        val submissions = mutableListOf<Triple<String, Boolean, Boolean>>()
        compose.setContent {
            PureBrowserTheme {
                DownloadConfirmation(original, defaults.value, {}, null, null, { name, wifi, access ->
                    submissions.add(Triple(name, wifi, access))
                })
            }
        }
        compose.onNodeWithText("来源页面：冻结来源").assertExists()
        compose.onNodeWithText("来源主机：media.example").assertExists()
        compose.onNodeWithTag("download-file-name").performScrollTo().performTextReplacement("../edited.mp4")
        compose.onNodeWithTag("download-file-name").performImeAction()
        compose.onNode(isToggleable() and hasText("仅 Wi-Fi")).performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("download-use-context").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { defaults.value = false }
        compose.runOnIdle { defaults.value = true }
        compose.onNode(isToggleable() and hasText("仅 Wi-Fi")).assertIsOff()
        compose.onNodeWithText("开始下载").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("开始下载").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Triple(".._edited.mp4", false, false)), submissions) }
    }

    @Test(timeout = 30_000)
    fun darkLargeFontConfirmation_keepsInvalidNameAndCancelReachable_withoutSubmission() {
        var dismissed = 0
        var submitted = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    DownloadConfirmation(DownloadDraft(candidate(), "test-agent"), true, { dismissed++ }, null, null, { _, _, _ -> submitted++ })
                }
            }
        }
        compose.onNodeWithTag("download-use-context").performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("download-file-name").performScrollTo().performTextReplacement("..")
        compose.onNodeWithTag("download-file-name").performImeAction()
        compose.onNodeWithText("请输入有效的文件名").assertExists()
        compose.onNodeWithText("开始下载").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("取消").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertEquals(0, submitted)
        }
    }

    private fun candidate(name: String = "clip.mp4") = MediaCandidate(
        "https://media.example/$name?token=private%2Bsignature&part=%2Fraw#original",
        MediaKind.FILE, setOf(Evidence.DOM), mimeType = "video/mp4", sizeBytes = 640,
    )

    companion object { private const val PAGE = "https://media.example/watch?source=frozen" }
}

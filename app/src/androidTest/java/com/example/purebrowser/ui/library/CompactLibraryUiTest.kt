package com.example.purebrowser.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.purebrowser.data.browser.SavedPage
import com.example.purebrowser.data.browser.ThemeMode
import com.example.purebrowser.download.FileAvailability
import com.example.purebrowser.download.FormatCheck
import com.example.purebrowser.download.VideoAsset
import com.example.purebrowser.theme.PureBrowserTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Fixtures exercise presentation/callbacks only, never fake actual file IO or player success. */
class CompactLibraryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneGridHasThreeActualTilesAcross() {
        showLibrary(assets = listOf(asset("1"), asset("2"), asset("3")))
        compose.onNodeWithTag("videoLibraryList").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩略图网格，3 列"),
        )
        val bounds = listOf("3", "2", "1").map { compose.onNodeWithTag("video-$it").fetchSemanticsNode().boundsInRoot }
        assertEquals(bounds[0].top, bounds[1].top, 1f)
        assertEquals(bounds[1].top, bounds[2].top, 1f)
        assertTrue(bounds[0].left < bounds[1].left && bounds[1].left < bounds[2].left)
    }

    @Test fun narrowWindowWithLargeFontUsesOneColumnAndReachableSheetActions() {
        val calls = mutableListOf<String>()
        showLibrary(width = 260, scale = 2f, calls = calls)
        compose.onNodeWithTag("videoLibraryList").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩略图网格，1 列"),
        )
        openSheet()
        sheetAction("video-share-1")
        compose.runOnIdle { assertEquals(listOf("share:1"), calls) }
    }

    @Test fun narrowDefaultFontUsesTwoColumns() {
        showLibrary(width = 300)
        compose.onNodeWithTag("videoLibraryList").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "缩略图网格，2 列"),
        )
    }

    @Test fun metadataIsUnknownRatherThanInventedAndCardHasNoInlineActions() {
        showLibrary()
        compose.onNodeWithTag("video-open-1").assertDoesNotExist()
        openSheet()
        compose.onNode(hasText("大小未知", substring = true) and hasAnyAncestor(hasTestTag("video-actions-1"))).assertExists()
        compose.onNodeWithText("时长未获取或不支持", substring = true).assertExists()
        compose.onNodeWithText("原保存时间未记录").assertExists()
        compose.onNodeWithText("实际文件名：sample-1.mp4").assertExists()
        compose.onNodeWithTag("video-source-1").assertDoesNotExist()
    }

    @Test fun missingFileStaysVisibleWithoutStaleCoverOrOpenShareDelete() {
        val calls = mutableListOf<String>()
        showLibrary(assets = listOf(asset("1").copy(availability = FileAvailability.MISSING)), calls = calls)
        compose.onNodeWithText("cover-1").assertDoesNotExist()
        openSheet()
        compose.onNodeWithTag("video-open-1").assertDoesNotExist()
        compose.onNodeWithTag("video-share-1").assertDoesNotExist()
        compose.onNodeWithTag("video-delete-1").assertDoesNotExist()
        sheetAction("video-forget-1")
        compose.onNodeWithTag("video-actions-1").assertDoesNotExist()
        compose.onNodeWithTag("video-forget-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("forget:1"), calls) }
    }

    @Test fun forgetAndPhysicalDeleteHaveSeparateConfirmationsAndCancelIsNoop() {
        val calls = mutableListOf<String>()
        showLibrary(calls = calls)
        openSheet()
        sheetAction("video-forget-1")
        compose.onNodeWithText("移除记录，保留文件？").assertExists()
        compose.onNodeWithTag("video-forget-dialog-1-dismiss").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
        openSheet()
        sheetAction("video-delete-1")
        compose.onNodeWithText("删除设备上的视频文件？").assertExists()
        compose.onNodeWithText("无法撤销", substring = true).assertExists()
        compose.onNodeWithTag("video-actions-1").assertDoesNotExist()
        compose.onNodeWithTag("video-delete-dialog-1-confirm").performClick()
        compose.runOnIdle { assertEquals(listOf("delete:1"), calls) }
    }

    @Test fun renameOnlyDispatchesDisplayTitleAndReplacesSheet() {
        val calls = mutableListOf<String>()
        showLibrary(calls = calls)
        openSheet()
        sheetAction("video-rename-1")
        compose.onNodeWithTag("video-actions-1").assertDoesNotExist()
        compose.onNodeWithText("不重命名设备文件", substring = true).assertExists()
        compose.onNodeWithTag("video-title-input-1").performTextReplacement(" ")
        compose.onNodeWithTag("video-title-save-1").assertIsNotEnabled()
        compose.onNodeWithTag("video-title-input-1").performTextReplacement(" 新显示名称 ")
        compose.onNodeWithTag("video-title-save-1").performClick()
        compose.runOnIdle { assertEquals(listOf("rename:1:新显示名称"), calls) }
    }

    @Test fun physicalDeleteCannotConfirmAfterFileBecomesMissing() {
        val assets = mutableStateOf(listOf(asset("1")))
        val calls = mutableListOf<String>()
        compose.setContent {
            PureBrowserTheme {
                VideoLibraryScreen(
                    assets = assets.value, busyIds = emptySet(), onBack = {}, onDownloads = {},
                    onOpen = {}, onShare = {}, onRename = { _, _ -> }, onForget = {},
                    onDelete = { calls += "delete:$it" }, onSource = {},
                )
            }
        }
        openSheet()
        sheetAction("video-delete-1")
        compose.runOnIdle { assets.value = listOf(asset("1").copy(availability = FileAvailability.MISSING)) }
        compose.onNodeWithTag("video-delete-dialog-1-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("video-delete-dialog-1-dismiss").performClick()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun sourceOnlyDispatchesWhenCallerProvidesEvidence() {
        val calls = mutableListOf<String>()
        showLibrary(sourceIds = setOf("1"), calls = calls)
        openSheet()
        sheetAction("video-source-1")
        compose.runOnIdle { assertEquals(listOf("source:1"), calls) }
    }

    @Test fun busyUpdateDisablesOpenShareAndDangerousActionsInExistingSheet() {
        val busy = mutableStateOf(emptySet<String>())
        val calls = mutableListOf<String>()
        compose.setContent {
            PureBrowserTheme {
                VideoLibraryScreen(
                    assets = listOf(asset("1")), busyIds = busy.value, onBack = {}, onDownloads = {},
                    onOpen = { calls += "open:$it" }, onShare = { calls += "share:$it" }, onRename = { _, _ -> },
                    onForget = { calls += "forget:$it" }, onDelete = { calls += "delete:$it" }, onSource = {},
                )
            }
        }
        openSheet()
        compose.runOnIdle { busy.value = setOf("1") }
        listOf("open", "share", "rename", "forget", "delete").forEach {
            compose.onNodeWithTag("video-$it-1").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test fun searchAndSortKeepExistingTagsAndRealIndexOrder() {
        showLibrary(assets = listOf(asset("1"), asset("2")))
        compose.onNodeWithTag("videoLibraryNewest").assertIsSelected()
        compose.onNodeWithTag("videoLibraryOldest").performClick().assertIsSelected()
        compose.onNodeWithTag("videoLibraryList").performScrollToKey("1")
        val first = compose.onNodeWithTag("video-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("video-2").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left < second.left)
        compose.onNodeWithTag("videoLibraryList").performScrollToIndex(0)
        compose.onNodeWithTag("videoLibrarySearch").performTextInput("sample-2")
        compose.onNodeWithTag("videoLibraryList").performScrollToKey("2")
        compose.onNodeWithTag("video-2").assertExists()
        compose.onNodeWithTag("video-1").assertDoesNotExist()
    }

    @Test fun compactBookmarkRowsKeepOpenEditRemoveCallbacksAtLargeFont() {
        val calls = mutableListOf<String>()
        val page = SavedPage(id = "p", title = "Long bookmark title", url = "https://example.org/path", time = 1000)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PureBrowserTheme {
                    Box(Modifier.requiredWidth(260.dp).height(560.dp)) {
                        SavedPagesScreen(listOf(page), false,
                            open = { calls += "open:$it" }, edit = { calls += "edit:${it.id}" }, remove = { calls += "remove:${it.id}" })
                    }
                }
            }
        }
        compose.onNodeWithTag("saved-p").performClick()
        compose.onNodeWithTag("edit-p").performClick()
        compose.onNodeWithTag("delete-p").performClick()
        compose.runOnIdle { assertEquals(listOf("open:${page.url}", "edit:p", "remove:p"), calls) }
        compose.onNodeWithTag("savedPageSearch").performTextInput("EXAMPLE.ORG")
        compose.onNodeWithTag("saved-p").assertExists()
        compose.onNodeWithTag("savedPageSearch").performTextReplacement("absent")
        compose.onNodeWithText("没有找到匹配网页").assertExists()
    }

    @Test fun historyShowsDateHeadingTimeAndRemoveButNoBookmarkEdit() {
        val page = SavedPage(id = "p", title = "History", url = "https://example.org", time = System.currentTimeMillis())
        compose.setContent { PureBrowserTheme { SavedPagesScreen(listOf(page), true, {}, {}, {}) } }
        compose.onNodeWithText("今天").assertExists()
        compose.onNodeWithTag("saved-p").assertExists()
        compose.onNodeWithTag("edit-p").assertDoesNotExist()
        compose.onNodeWithTag("delete-p").assertExists()
    }

    @Test fun darkThemeKeepsUnreadableRecordAndConfirmationAccessible() {
        showLibrary(assets = listOf(asset("1").copy(availability = FileAvailability.UNREADABLE)), mode = ThemeMode.DARK)
        openSheet()
        compose.onNodeWithTag("video-open-1").assertDoesNotExist()
        sheetAction("video-delete-1")
        compose.onNodeWithTag("video-delete-dialog-1-confirm").assertIsEnabled()
        compose.onNodeWithTag("video-delete-dialog-1-dismiss").performClick()
    }

    @Test fun searchAndSortRestoreWithoutChangingCallbackSignatures() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            PureBrowserTheme {
                VideoLibraryScreen(
                    assets = listOf(asset("1"), asset("2")), busyIds = emptySet(), onBack = {}, onDownloads = {},
                    onOpen = {}, onShare = {}, onRename = { _, _ -> }, onForget = {}, onDelete = {}, onSource = {},
                )
            }
        }
        compose.onNodeWithTag("videoLibraryOldest").performClick()
        compose.onNodeWithTag("videoLibrarySearch").performTextInput("sample-1")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("videoLibraryOldest").assertIsSelected()
        compose.onNodeWithTag("videoLibrarySearch").assertTextContains("sample-1")
        compose.onNodeWithTag("videoLibraryList").performScrollToKey("1")
        compose.onNodeWithTag("video-1").assertExists()
        compose.onNodeWithTag("video-2").assertDoesNotExist()
    }

    @Test fun removingSelectedRecordClosesSheetWithoutFileAction() {
        val assets = mutableStateOf(listOf(asset("1")))
        val calls = mutableListOf<String>()
        compose.setContent {
            PureBrowserTheme {
                VideoLibraryScreen(
                    assets = assets.value, busyIds = emptySet(), onBack = {}, onDownloads = {},
                    onOpen = { calls += it }, onShare = { calls += it }, onRename = { _, _ -> },
                    onForget = { calls += it }, onDelete = { calls += it }, onSource = {},
                )
            }
        }
        openSheet()
        compose.runOnIdle { assets.value = emptyList() }
        compose.onNodeWithTag("video-actions-1").assertDoesNotExist()
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    private fun openSheet() {
        compose.onNodeWithTag("videoLibraryList").performScrollToKey("1")
        compose.onNodeWithTag("video-1").performClick()
        compose.onNodeWithTag("video-actions-1").assertExists()
    }

    private fun sheetAction(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun showLibrary(
        assets: List<VideoAsset> = listOf(asset("1")), width: Int = 360, scale: Float = 1f,
        sourceIds: Set<String> = emptySet(), calls: MutableList<String> = mutableListOf(),
        mode: ThemeMode = ThemeMode.SYSTEM,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                PureBrowserTheme(mode) {
                    Box(Modifier.requiredWidth(width.dp).height(560.dp)) {
                        VideoLibraryScreen(
                            assets = assets, busyIds = emptySet(), onBack = {}, onDownloads = {},
                            onOpen = { calls += "open:$it" }, onShare = { calls += "share:$it" },
                            onRename = { id, title -> calls += "rename:$id:$title" }, onForget = { calls += "forget:$it" },
                            onDelete = { calls += "delete:$it" }, onSource = { calls += "source:$it" },
                            thumbnail = { Text("cover-${it.recordId}") }, sourceAvailableIds = sourceIds,
                        )
                    }
                }
            }
        }
    }

    private fun asset(id: String) = VideoAsset(
        recordId = id, systemId = id.toLong(), uri = "content://downloads/all_downloads/$id",
        name = "sample-$id.mp4", displayName = "视频 $id", indexedAt = id.toLong() * 1000,
        format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE,
    )
}

package com.example.purebrowser.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.DownloadManager
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityWindowInfo
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.browser.BrowserSession
import com.example.purebrowser.data.browser.*
import com.example.purebrowser.download.*
import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaCandidate
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.theme.PureBrowserTheme
import com.example.purebrowser.ui.browser.BrowserMenuSheet
import com.example.purebrowser.ui.browser.BrowserWebViewHost
import com.example.purebrowser.ui.components.BrowserAddressBar
import com.example.purebrowser.ui.components.BrowserToolbar
import com.example.purebrowser.ui.downloads.DownloadQuickSheet
import com.example.purebrowser.ui.downloads.DownloadsScreen
import com.example.purebrowser.ui.home.HomeScreen
import com.example.purebrowser.ui.library.SavedPagesScreen
import com.example.purebrowser.ui.library.VideoLibraryScreen
import com.example.purebrowser.ui.resources.ResourceSheet
import com.example.purebrowser.ui.settings.SettingsScreen
import com.example.purebrowser.ui.tabs.TabSwitcher
import com.example.purebrowser.ui.tabs.TabViewMode
import com.example.purebrowser.ui.tabs.TabViewPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.model.Statement
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded T49/M1 + partial M6 sidecar: 15 states x 4 variants = 60 instrumented cases.
 * Nothing here is a completed-download, 24-live-WebView, external-file, or release acceptance claim.
 * Production composables receive explicit synthetic snapshots; every mutating callback is a recorder.
 * WEB uses production BrowserWebViewHost + BrowserSession with self-authored, offline-only HTML.
 * No repository, coordinator, network download, MediaStore file, private browser data, or real thumbnail
 * is read by this harness. Tab layout preference IO is redirected to one audit-only preference file.
 *
 * Debug only: the existing ui-test-manifest dependency registers ComponentActivity. No manifest/deps
 * are added. Each case writes at most one PNG and JSON under targetContext.cacheDir/v014-visual-audit;
 * repeated cases overwrite their own two files (120-file bound). Failed/skipped cases have no PNG.
 * Screenshot pixels come ONLY from UiAutomation.takeScreenshot; raw full-display pixels are never
 * saved. System bars/cutout and IME are excluded. No screenshot is captured by the actual-shell tests.
 *
 * NATIVE variants use the available activity content viewport. COMPACT/WIDE constrain actual Compose
 * layout in dp, not a resized bitmap, and explicitly override LocalDensity.fontScale and configuration.
 * These are NOT OS font-setting, rotation, split-screen or IME tests. Production modal sheets/dialogs
 * use their own native window constraints; reports record their real bounds and do not call them 320dp
 * modals. A device lacking the requested root size gets a SKIPPED report, never clipped fake evidence.
 * SETTINGS deliberately leaves privacy callbacks absent: disabled fixture controls are not live policy.
 * INPUT captures a real production editor with the IME hidden, not an OS keyboard acceptance image.
 *
 * Parent integration commands (not executed by this sidecar author):
 * ./gradlew :app:compileDebugAndroidTestKotlin
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.purebrowser.ui.V014VisualAudit \
 *   -Pandroid.testInstrumentationRunnerArguments.v014VisualFixtures=true \
 *   -Pandroid.testInstrumentationRunnerArguments.v014SourceRevision=<verified-source-revision>
 * Optional v014Variant=NATIVE_LIGHT|NATIVE_DARK|COMPACT_DARK_2X|WIDE_LIGHT_13X restricts the run.
 * Existing actual journey coverage: BrowserNavigationTest, ProductWorkflowTest, HlsBrowserJourneyTest,
 * BrowserDownloadFixtureTest, DownloadLibraryUiTest. V014AdaptiveAudit adds opt-in shell preservation
 * checks. None of the fixture screenshots replaces those real transport/file/upgrade journeys.
 */
@RunWith(Parameterized::class)
class V014VisualAudit(private val state: V014VisualState, private val variant: V014VisualVariant) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val permission = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val args = InstrumentationRegistry.getArguments()
                assumeTrue("Explicit fixture screenshot opt-in required", args.getString("v014VisualFixtures") == "true")
                val filter = args.getString("v014Variant")
                assumeTrue("Variant not selected", filter == null || filter == variant.name)
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(permission).around(compose)

    private val calls = mutableListOf<String>()
    private var viewport = IntSize.Zero
    private var available = IntSize.Zero
    private var density = 1f
    private var effectiveFont = 1f
    private var clearFixtureFocus: () -> Unit = {}
    private var webSession: BrowserSession? = null
    private val webFrameReady = AtomicBoolean(false)
    private val fixedTime = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
    private val tabs = (1..24).map {
        TabRecord("v014-tab-$it", "https://v014.example.invalid/tab/$it", "FIXTURE $it — offline tab")
    }
    private val pages = (1..5).map {
        SavedPage("v014-page-$it", "FIXTURE $it — saved page", "https://v014.example.invalid/page/$it",
            fixedTime - (it - 1) * 86_400_000L)
    }
    private val browserData = BrowserData(tabs = tabs, selectedId = tabs.first().id,
        bookmarks = pages, history = pages, shortcuts = listOf(
            Shortcut("v014-site-1", "FIXTURE Docs", "https://v014.example.invalid/docs"),
            Shortcut("v014-site-2", "FIXTURE Video", "https://v014.example.invalid/video"),
        ), theme = ThemeMode.LIGHT)
    private val assets = listOf(
        asset("v014-available", FileAvailability.AVAILABLE, fixedTime),
        asset("v014-missing", FileAvailability.MISSING, fixedTime - 1000),
        asset("v014-unreadable", FileAvailability.UNREADABLE, fixedTime - 2000),
    )
    private val tasks = listOf(
        task("v014-running", TaskStatus.RUNNING, DownloadManager.STATUS_RUNNING).copy(
            bytes = 2_097_152, total = 8_388_608, canPause = true),
        task("v014-muxing", TaskStatus.MUXING, DownloadManager.STATUS_RUNNING).copy(
            protocol = DownloadProtocol.HLS, segmentCount = 8, completedSegments = 8, total = -1),
        task("v014-paused", TaskStatus.PAUSED, DownloadManager.STATUS_PAUSED).copy(
            canResume = true, pauseReason = PauseReason.USER, cacheBytes = 1_048_576),
        task("v014-failed", TaskStatus.FAILED, DownloadManager.STATUS_FAILED).copy(
            canRetry = true, failure = FailureKind.NETWORK),
        task("v014-saved", TaskStatus.SUCCEEDED, DownloadManager.STATUS_SUCCESSFUL).copy(
            verified = true, format = FormatCheck.PASSED, availability = FileAvailability.AVAILABLE),
        task("v014-missing-task", TaskStatus.SUCCEEDED, DownloadManager.STATUS_SUCCESSFUL).copy(
            verified = true, format = FormatCheck.PASSED, availability = FileAvailability.MISSING),
    )

    @Test fun productionState_deviceRenderedFixtureEvidence() {
        val directory = File(instrumentation.targetContext.cacheDir, "v014-visual-audit/${state.name}")
        check(directory.mkdirs() || directory.isDirectory)
        val stem = "FIXTURE-${variant.name}"
        val png = File(directory, "$stem.png")
        val reportFile = File(directory, "$stem.json")
        // Do not leave a stale PASS screenshot behind when a repeat fails or is unsupported.
        check(!png.exists() || png.delete())
        check(!reportFile.exists() || reportFile.delete())
        val report = baseReport()
        var preferencesContext: Context? = null
        try {
            val isolatedContext = object : ContextWrapper(instrumentation.targetContext) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    baseContext.getSharedPreferences("v014_visual_audit_only", mode)
            }
            preferencesContext = isolatedContext
            check(isolatedContext.getSharedPreferences("ignored", Context.MODE_PRIVATE).edit().clear().commit())
            val preferences = TabViewPreferences(isolatedContext).also { it.write(TabViewMode.GRID) }
            if (state == V014VisualState.WEB) {
                compose.runOnUiThread {
                    webSession = BrowserSession("v014-offline", compose.activity.applicationContext,
                        TabRecord("v014-offline"), {}, {}, { _, _ -> }, {})
                }
            }
            compose.setContent { FixtureFrame { ProductionState(preferences) } }
            compose.waitForIdle()
            compose.runOnIdle {
                report.put("availableRootPx", sizeJson(available))
                    .put("fixtureViewportPx", sizeJson(viewport))
                    .put("effectiveDensity", density).put("effectiveFontScale", effectiveFont)
            }
            val fits = (variant.widthDp == null || available.width / density >= variant.widthDp - 1f) &&
                (variant.heightDp == null || available.height / density >= variant.heightDp - 1f)
            if (!fits) {
                report.put("status", "SKIPPED").put("reason", "Native content cannot contain requested fixture viewport")
                assumeTrue("Use a sufficiently large native window for ${variant.name}", fits)
            }
            prepareState()
            hideIme()
            assertStateAndTargets()
            val stateTag = state.captureTag
            val node = compose.onNodeWithTag(stateTag).assertIsDisplayed().fetchSemanticsNode()
            report.put("productionCaptureTag", stateTag)
                .put("productionBoundsInWindowPx", rectJson(node.boundsInWindow))
                .put("modalUsesNativeWindow", state.modal)
            compose.runOnIdle { assertTrue("No business callback should run during fixture presentation", calls.isEmpty()) }
            val crop = captureFixtureOnly(png)
            report.put("status", "PASS").put("screenshot", png.name)
                .put("screenshotSha256", sha256(png)).put("cropInDisplayPx", rectJson(crop))
        } catch (failure: Throwable) {
            if (report.optString("status") != "SKIPPED") {
                report.put("status", "FAILED").put("failureType", failure.javaClass.simpleName)
                png.delete() // No potentially misleading evidence for failed assertions/capture.
            }
            throw failure
        } finally {
            // No raw assertion text, URLs from the real app, view hierarchy or logs in the report.
            try {
                compose.runOnUiThread { webSession?.destroy(); webSession = null }
                preferencesContext?.getSharedPreferences("ignored", Context.MODE_PRIVATE)?.edit()?.clear()?.commit()
            } finally {
                reportFile.writeText(report.toString(2))
            }
        }
    }

    @Composable private fun FixtureFrame(content: @Composable () -> Unit) {
        val nativeDensity = LocalDensity.current
        val nativeConfiguration = LocalConfiguration.current
        val chosenFont = variant.fontScale ?: nativeDensity.fontScale
        val config = remember(nativeConfiguration, chosenFont, variant) {
            Configuration(nativeConfiguration).apply {
                fontScale = chosenFont
                variant.widthDp?.let { screenWidthDp = it }
                variant.heightDp?.let { screenHeightDp = it }
                if (variant.widthDp != null && variant.heightDp != null) {
                    orientation = if (variant.widthDp > variant.heightDp) Configuration.ORIENTATION_LANDSCAPE
                        else Configuration.ORIENTATION_PORTRAIT
                }
            }
        }
        CompositionLocalProvider(LocalDensity provides Density(nativeDensity.density, chosenFont),
            LocalConfiguration provides config) {
            val focus = LocalFocusManager.current
            SideEffect { density = nativeDensity.density; effectiveFont = chosenFont; clearFixtureFocus = { focus.clearFocus(force = true) } }
            PureBrowserTheme(mode = variant.theme) {
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
                    contentAlignment = Alignment.TopCenter) {
                    SideEffect { available = IntSize(constraints.maxWidth, constraints.maxHeight) }
                    val width = variant.widthDp?.dp?.coerceAtMost(maxWidth) ?: maxWidth
                    val height = variant.heightDp?.dp?.coerceAtMost(maxHeight) ?: maxHeight
                    Surface(Modifier.size(width, height).onSizeChanged { viewport = it }.testTag("v014FixtureViewport"), color = MaterialTheme.colorScheme.background) {
                        Column {
                            Text("FIXTURE / SYNTHETIC — ${state.name}", modifier = Modifier.padding(4.dp),
                                style = MaterialTheme.typography.labelSmall)
                            Box(Modifier.fillMaxSize()) { content() }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun ProductionState(preferences: TabViewPreferences) {
        when (state) {
            V014VisualState.HOME, V014VisualState.WEB, V014VisualState.INPUT -> FixtureChrome()
            V014VisualState.TABS_GRID, V014VisualState.TABS_LIST, V014VisualState.TABS_SEARCH -> {
                FixtureBackground()
                TabSwitcher(tabs, tabs.first().id, { record("select") }, { record("close") },
                    { record("create") }, { record("dismiss") }, viewPreferences = preferences)
            }
            V014VisualState.RESOURCES -> {
                FixtureBackground()
                ResourceSheet(listOf(
                    MediaCandidate("https://v014.example.invalid/fixture.mp4", MediaKind.FILE, setOf(Evidence.DOM),
                        mimeType = "video/mp4", sizeBytes = 8_388_608, title = "FIXTURE — direct candidate", playing = true),
                    MediaCandidate("https://v014.example.invalid/fixture.m3u8", MediaKind.HLS, setOf(Evidence.REQUEST),
                        title = "FIXTURE — unprepared HLS candidate"),
                    MediaCandidate("blob:https://v014.example.invalid/fixture", MediaKind.LOCAL, setOf(Evidence.DOM),
                        title = "FIXTURE — unsupported local media"),
                ), { record("dismiss") }, { record("selectResource") }, { record("source") })
            }
            V014VisualState.DOWNLOAD_QUICK -> {
                FixtureBackground()
                DownloadQuickSheet(tasks, emptySet(), { record("dismiss") }, { record("openAll") }, { record("library") },
                    { record("open") }, { record("share") }, { record("retry") }, { record("cancel") }, { record("source") },
                    onPause = { record("pause") }, onResume = { record("resume") },
                    onForget = { record("forget") }, onDelete = { record("delete") })
            }
            V014VisualState.DOWNLOADS -> DownloadsScreen(tasks, emptySet(), { record("back") },
                { record("open") }, { record("share") }, { record("retry") }, { record("cancel") },
                { record("forget") }, { record("delete") }, { record("source") }, { record("library") },
                onPause = { record("pause") }, onResume = { record("resume") })
            V014VisualState.LIBRARY, V014VisualState.FILE_ACTIONS -> VideoLibraryScreen(
                assets, emptySet(), { record("back") }, { record("open") }, { record("share") },
                { _, _ -> record("rename") }, { record("forget") }, { record("delete") }, { record("source") },
                { record("downloads") }, thumbnail = { Text("FIXTURE", style = MaterialTheme.typography.labelSmall) },
                sourceAvailableIds = setOf("v014-available"))
            V014VisualState.SETTINGS -> SettingsScreen(variant.theme, { record("theme") }, true,
                { record("wifi") }, { record("about") })
            V014VisualState.MENU -> {
                FixtureBackground()
                BrowserMenuSheet(false, false, { record("dismiss") }, { record("create") }, { record("bookmark") },
                    { record("resources") }, { record("downloads") }, { record("library") },
                    { record("bookmarks") }, { record("history") }, { record("settings") })
            }
            V014VisualState.BOOKMARKS, V014VisualState.HISTORY -> SavedPagesScreen(pages,
                state == V014VisualState.HISTORY, { record("open") }, { record("edit") }, { record("remove") })
        }
    }

    @Composable private fun FixtureBackground() {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("FIXTURE ONLY\nNo real tabs, downloads or files", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable private fun FixtureChrome() {
        Column(Modifier.fillMaxSize()) {
            BrowserAddressBar(if (state == V014VisualState.HOME) "" else "https://v014.example.invalid/offline",
                state == V014VisualState.INPUT, { record("changeAddress") }, { record("focusAddress") },
                { record("submit") }, { record("cancelEdit") }, false, { record("reload") })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state) {
                    V014VisualState.HOME -> HomeScreen(browserData, emptyList(), { record("library") },
                        { record("play") }, { record("open") }, { record("add") }, { record("edit") },
                        { record("bookmarks") }, { record("history") }, { record("downloads") })
                    V014VisualState.WEB -> BrowserWebViewHost(checkNotNull(webSession), Modifier.fillMaxSize().testTag("browserWebView"))
                    else -> Text("FIXTURE address-edit state; keyboard excluded from capture", Modifier.padding(16.dp))
                }
            }
            if (state != V014VisualState.INPUT) BrowserToolbar(false, false, 24, state == V014VisualState.HOME,
                { record("back") }, { record("forward") }, { record("home") }, { record("tabs") }, { record("menu") })
        }
    }

    private fun prepareState() {
        when (state) {
            V014VisualState.TABS_GRID -> compose.onNodeWithTag("tabGridButton").performClick()
            V014VisualState.TABS_LIST -> compose.onNodeWithTag("tabListButton").performClick()
            V014VisualState.TABS_SEARCH -> compose.onNodeWithTag("tabSearchField").performTextReplacement("tab/2")
            V014VisualState.FILE_ACTIONS -> compose.onNodeWithTag("videoLibraryList")
                .performScrollToNode(hasTestTag("video-v014-available")).also {
                    compose.onNodeWithTag("video-v014-available").performClick()
                }
            V014VisualState.WEB -> {
                compose.runOnUiThread {
                    val web = checkNotNull(webSession?.mountedPreviewView())
                    web.settings.blockNetworkLoads = true
                    web.loadDataWithBaseURL("https://v014.example.invalid/offline", OFFLINE_HTML, "text/html", "UTF-8", null)
                }
                compose.waitUntil(10_000) {
                    webSession?.engine?.page?.value?.let { it.progress == 100 && it.title == "V014 FIXTURE offline page" } == true
                }
                compose.runOnUiThread {
                    checkNotNull(webSession?.mountedPreviewView()).postVisualStateCallback(14L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { webFrameReady.set(true) }
                    })
                }
                compose.waitUntil(10_000) { webFrameReady.get() }
            }
            else -> Unit
        }
        compose.waitForIdle()
    }

    private fun hideIme() {
        compose.runOnIdle { clearFixtureFocus() }
        Espresso.closeSoftKeyboard()
        compose.runOnUiThread {
            ViewCompat.getWindowInsetsController(compose.activity.window.decorView)?.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5000) {
            var hidden = false
            compose.runOnUiThread {
                hidden = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == false
            }
            hidden
        }
        compose.waitForIdle()
    }

    private fun assertStateAndTargets() {
        compose.onNodeWithTag(state.captureTag).assertIsDisplayed()
        when (state) {
            V014VisualState.HOME, V014VisualState.WEB -> {
                compose.onAllNodesWithTag("addressInput").assertCountEquals(1)
                val tags = listOf("backButton", "forwardButton", "homeButton", "tabsButton", "menuButton")
                tags.forEach { assertTouchTarget(it, icon = true) }
                compose.onNodeWithTag("backButton").assertIsNotEnabled()
                compose.onNodeWithTag("forwardButton").assertIsNotEnabled()
                tags.drop(2).forEach { compose.onNodeWithTag(it).assertIsEnabled() }
                val bounds = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
                bounds.zipWithNext().forEach { (a, b) -> assertTrue("Toolbar actions overlap/out of order", a.right <= b.left + 1f) }
                val address = compose.onNodeWithTag("addressInput").fetchSemanticsNode().boundsInRoot
                assertTrue("Address bar must stay above bottom actions", address.bottom < bounds.first().top)
                assertInsideViewport(tags + "addressInput")
            }
            V014VisualState.INPUT -> {
                compose.onAllNodesWithTag("addressInput").assertCountEquals(1)
                compose.onNodeWithTag("addressInput").assertTextEquals("https://v014.example.invalid/offline")
                compose.onNodeWithTag("homeButton").assertDoesNotExist()
                assertTouchTarget("navigateButton", icon = true)
                assertTouchTarget("cancelAddressButton", icon = true)
                assertInsideViewport(listOf("addressInput", "navigateButton", "cancelAddressButton"))
            }
            V014VisualState.TABS_GRID, V014VisualState.TABS_LIST, V014VisualState.TABS_SEARCH -> {
                listOf("tabGridButton", "tabListButton", "locateTabButton", "newTabButton", "dismissTabsButton")
                    .forEach { assertTouchTarget(it, icon = true) }
                val selectedMode = if (state == V014VisualState.TABS_LIST) "tabListButton" else "tabGridButton"
                compose.onNode(hasAnyDescendant(hasTestTag(selectedMode)) and isSelected(), useUnmergedTree = true).assertExists()
                if (state == V014VisualState.TABS_SEARCH) {
                    compose.onNodeWithTag("tabSearchField").assertTextEquals("tab/2")
                    // Query hides current; locate must clear filter and reach the unchanged current fixture.
                    compose.onNodeWithTag("tab-v014-tab-1").assertDoesNotExist()
                    compose.onNodeWithTag("locateTabButton").assertIsEnabled()
                } else {
                    compose.onNodeWithTag("tab-v014-tab-1").assertIsSelected()
                }
            }
            V014VisualState.DOWNLOAD_QUICK -> {
                compose.onNodeWithTag("downloadQuickList").performScrollToNode(hasTestTag("download-pause-v014-running"))
                assertTouchTarget("download-pause-v014-running", icon = true)
                assertTouchTarget("download-details-v014-running", icon = true)
                compose.onNodeWithTag("downloadQuickList").performScrollToIndex(0)
            }
            V014VisualState.DOWNLOADS -> {
                compose.onNodeWithTag("downloadsList").performScrollToNode(hasTestTag("download-pause-v014-running"))
                assertTouchTarget("download-pause-v014-running", icon = true)
                assertTouchTarget("download-details-v014-running", icon = true)
                compose.onNodeWithTag("downloadsList").performScrollToIndex(0)
            }
            V014VisualState.SETTINGS -> {
                compose.onNodeWithTag("theme-${variant.theme.name}").assertIsSelected()
                assertTouchTarget("theme-${variant.theme.name}")
                compose.onNodeWithTag("defaultWifiOnly").performScrollTo()
                compose.onNodeWithTag("defaultWifiOnly").assertIsOn()
                assertTouchTarget("defaultWifiOnly")
                assertInsideViewport(listOf("defaultWifiOnly"))
                compose.onNodeWithTag("theme-SYSTEM").performScrollTo()
            }
            V014VisualState.MENU -> {
                compose.onNodeWithTag("menu-bookmarkToggle").assertIsNotEnabled()
                assertTouchTarget("dismissMenuButton", icon = true)
                compose.onNodeWithTag("menu-settings").performScrollTo()
                assertTouchTarget("menu-settings")
                compose.onNodeWithTag("dismissMenuButton").performScrollTo()
            }
            V014VisualState.BOOKMARKS, V014VisualState.HISTORY -> {
                compose.onNodeWithTag("savedPagesList").performScrollToNode(hasTestTag("saved-v014-page-1"))
                assertTouchTarget("delete-v014-page-1", icon = true)
                if (state == V014VisualState.BOOKMARKS) assertTouchTarget("edit-v014-page-1", icon = true)
                compose.onNodeWithTag("savedPagesList").performScrollToIndex(0)
            }
            V014VisualState.FILE_ACTIONS -> {
                assertTouchTarget("video-actions-close-v014-available", icon = true)
                compose.onNodeWithTag("video-open-v014-available").performScrollTo()
                assertTouchTarget("video-open-v014-available")
                compose.onNodeWithTag("video-share-v014-available").performScrollTo()
                assertTouchTarget("video-share-v014-available")
                compose.onNodeWithTag("video-forget-v014-available").performScrollTo()
                assertTouchTarget("video-forget-v014-available")
                compose.onNodeWithTag("video-delete-v014-available").performScrollTo()
                assertTouchTarget("video-delete-v014-available")
                val forget = compose.onNodeWithTag("video-forget-v014-available").fetchSemanticsNode().boundsInRoot
                val delete = compose.onNodeWithTag("video-delete-v014-available").fetchSemanticsNode().boundsInRoot
                assertTrue("Keep-file and physical-delete actions must not overlap", forget.bottom <= delete.top + 1f)
                compose.onNodeWithTag("video-actions-close-v014-available").performScrollTo()
            }
            V014VisualState.LIBRARY -> {
                compose.onNodeWithTag("videoLibraryList").performScrollToNode(hasTestTag("video-v014-missing"))
                compose.onNodeWithTag("video-v014-missing").performClick()
                compose.onNodeWithTag("video-open-v014-missing").assertDoesNotExist()
                compose.onNodeWithTag("video-share-v014-missing").assertDoesNotExist()
                compose.onNodeWithTag("video-delete-v014-missing").assertDoesNotExist()
                compose.onNodeWithTag("video-forget-v014-missing").performScrollTo()
                assertTouchTarget("video-forget-v014-missing")
                compose.onNodeWithTag("video-actions-close-v014-missing").performScrollTo().performClick()
                compose.onNodeWithTag("videoLibraryList").performScrollToIndex(0)
            }
            else -> Unit
        }
        compose.waitForIdle()
    }

    private fun assertTouchTarget(tag: String, icon: Boolean = false) {
        val interaction = compose.onNodeWithTag(tag).assertIsDisplayed().assertHasClickAction()
        val node = interaction.fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val minimum = 48f * density - 1f
        assertTrue("$tag width < 48dp", bounds.width >= minimum)
        assertTrue("$tag height < 48dp", bounds.height >= minimum)
        if (icon) {
            assertEquals("$tag must expose button role", Role.Button, node.config.getOrNull(SemanticsProperties.Role))
            assertTrue("$tag must expose an accessible name", node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.any { it.isNotBlank() } == true)
        }
    }

    private fun assertInsideViewport(tags: List<String>) {
        val root = compose.onNodeWithTag("v014FixtureViewport").fetchSemanticsNode().boundsInRoot
        tags.forEach { tag ->
            val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag is clipped by constrained fixture viewport", b.left >= root.left - 1f && b.top >= root.top - 1f &&
                b.right <= root.right + 1f && b.bottom <= root.bottom + 1f)
        }
    }

    private fun captureFixtureOnly(file: File): Rect {
        compose.onNodeWithTag("v014FixtureViewport").assertIsDisplayed()
        val area = Rect()
        compose.runOnUiThread {
            val activity = compose.activity
            assertFalse("Never capture the lockscreen", (activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
            assertFalse(activity.isFinishing)
            val view = activity.findViewById<View>(android.R.id.content)
            assertTrue("Fixture activity must be visible", view.getGlobalVisibleRect(area))
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
            assertFalse("No keyboard or suggestion strip in fixture evidence", insets.isVisible(WindowInsetsCompat.Type.ime()))
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val location = IntArray(2).also(view::getLocationOnScreen)
            // Intersect with content + safe inset bounds; excludes status/nav/notifications, not just a bitmap resize.
            assertTrue(area.intersect(location[0] + safe.left, location[1] + safe.top,
                location[0] + view.width - safe.right, location[1] + view.height - safe.bottom))
        }
        instrumentation.waitForIdleSync()
        val automation = instrumentation.uiAutomation
        val service = automation.serviceInfo
        val previousFlags = service.flags
        try {
            service.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
            val active = checkNotNull(automation.rootInActiveWindow) { "Fixture window must be active" }
            try {
                assertEquals("Refuse foreign foreground window", instrumentation.targetContext.packageName, active.packageName?.toString())
            } finally { active.recycle() }
            automation.windows.forEach { window ->
                try {
                    val bounds = Rect().also(window::getBoundsInScreen)
                    if (Rect.intersects(bounds, area)) {
                        assertNotEquals("Refuse visible keyboard/suggestions", AccessibilityWindowInfo.TYPE_INPUT_METHOD, window.type)
                        val root = window.root
                        try {
                            assertEquals("Refuse foreign window inside screenshot area", instrumentation.targetContext.packageName,
                                checkNotNull(root).packageName?.toString())
                        } finally { root?.recycle() }
                    }
                } finally { window.recycle() }
            }
        } finally {
            service.flags = previousFlags
            automation.serviceInfo = service
        }
        val raw = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No device-rendered screenshot available" }
        var cropped: Bitmap? = null
        try {
            assertTrue(area.left >= 0 && area.top >= 0 && area.right <= raw.width && area.bottom <= raw.height)
            cropped = Bitmap.createBitmap(raw, area.left, area.top, area.width(), area.height())
            file.outputStream().use { assertTrue("PNG encoding failed", checkNotNull(cropped).compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            if (cropped !== raw) cropped?.recycle()
            raw.recycle()
        }
        return area
    }

    @Suppress("DEPRECATION")
    private fun baseReport(): JSONObject {
        val context = instrumentation.targetContext
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        return JSONObject().put("schema", 1).put("fixtureOnly", true)
            .put("scenario", state.name).put("variant", variant.name).put("timeUtc", Instant.now().toString())
            .put("sourceRevision", InstrumentationRegistry.getArguments().getString("v014SourceRevision") ?: "UNPROVIDED")
            .put("package", context.packageName).put("versionName", pkg.versionName)
            .put("versionCode", if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong())
            .put("buildVariant", "debug fixture harness; not signed-release evidence")
            .put("api", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("webViewPackage", WebView.getCurrentWebViewPackage()?.packageName ?: "UNAVAILABLE")
            .put("webViewVersion", WebView.getCurrentWebViewPackage()?.versionName ?: "UNAVAILABLE")
            .put("displayMetricsPx", JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels))
            .put("signingCertificateSha256", signingIdentity())
            .put("nativeDensityDpi", metrics.densityDpi).put("nativeFontScale", config.fontScale)
            .put("nativeConfigurationDp", JSONObject().put("width", config.screenWidthDp).put("height", config.screenHeightDp)
                .put("orientation", config.orientation).put("locales", config.locales.toLanguageTags()))
            .put("fixtureTheme", variant.theme.name).put("requestedFontScale", variant.fontScale ?: JSONObject.NULL)
            .put("requestedViewportDp", JSONObject().put("width", variant.widthDp ?: JSONObject.NULL).put("height", variant.heightDp ?: JSONObject.NULL))
            .put("rendering", "UiAutomation device pixels, app-safe-area crop only; no generated or resized mockup")
            .put("limits", JSONArray(listOf("Synthetic snapshots are NOT real downloads or files", "24 records are NOT 24 live WebViews",
                "Constrained root/local font are NOT OS window/font settings", "Modal dimensions remain native and are separately reported",
                "INPUT excludes IME; settings privacy callbacks absent", "No release, performance, signing/upgrade, TalkBack or transfer claim")))
            .put("status", "STARTED")
    }

    @Suppress("DEPRECATION")
    private fun signingIdentity(): JSONArray {
        val context = instrumentation.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName,
            if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES)
        val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return JSONArray(certificates.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        })
    }

    private fun record(action: String) { calls += action }
    private fun task(id: String, stage: TaskStatus, status: Int) = DownloadItem(id, "FIXTURE-$id.mp4", status, 0, -1,
        "SYNTHETIC UI ONLY — no transfer/file exists", sourceUrl = "https://v014.example.invalid/offline",
        sourceTitle = "FIXTURE source", createdAt = fixedTime, wifiOnly = true, taskStatus = stage)
    private fun asset(id: String, availability: FileAvailability, time: Long) = VideoAsset(id,
        uri = "content://v014.fixture.invalid/not-a-real-file/$id", name = "FIXTURE-$id.mp4",
        displayName = "FIXTURE $id — not a real download", indexedAt = time, sizeBytes = 8_388_608,
        mimeType = "video/mp4", format = FormatCheck.PASSED, availability = availability, durationMillis = 65_000)
    private fun sizeJson(size: IntSize) = JSONObject().put("width", size.width).put("height", size.height)
    private fun rectJson(rect: androidx.compose.ui.geometry.Rect) = JSONObject().put("left", rect.left).put("top", rect.top)
        .put("right", rect.right).put("bottom", rect.bottom)
    private fun rectJson(rect: Rect) = JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom)
    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}__{1}")
        fun cases(): List<Array<Any>> = V014VisualState.entries.flatMap { state ->
            V014VisualVariant.entries.map { variant -> arrayOf<Any>(state, variant) }
        }
        private const val OFFLINE_HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>V014 FIXTURE offline page</title><style>body{font:18px sans-serif;margin:24px;background:#f3f5f8;color:#243047}h1{font-size:24px}section{padding:16px;background:white;border-radius:12px}</style></head><body><h1>FIXTURE — offline article</h1><section>Self-authored runtime WebView content. No login, private URL, external assets or real video/download.<p>Production browser chrome surrounds this fixture.</p></section></body></html>"""
    }
}

enum class V014VisualState(val captureTag: String, val modal: Boolean = false) {
    HOME("homeScreen"), WEB("browserWebView"), INPUT("addressInput"),
    TABS_GRID("tabGrid", true), TABS_LIST("tabList", true), TABS_SEARCH("tabGrid", true),
    RESOURCES("resource-sheet", true), DOWNLOAD_QUICK("downloadQuickSheet", true), DOWNLOADS("downloadsScreen"),
    LIBRARY("videoLibraryList"), FILE_ACTIONS("video-actions-v014-available", true), SETTINGS("settingsScreen"),
    MENU("dismissMenuButton", true), BOOKMARKS("savedPagesList"), HISTORY("savedPagesList"),
}

enum class V014VisualVariant(val theme: ThemeMode, val fontScale: Float? = null,
    val widthDp: Int? = null, val heightDp: Int? = null) {
    NATIVE_LIGHT(ThemeMode.LIGHT), NATIVE_DARK(ThemeMode.DARK),
    COMPACT_DARK_2X(ThemeMode.DARK, 2f, 320, 560), WIDE_LIGHT_13X(ThemeMode.LIGHT, 1.3f, 600, 360),
}

package com.example.purebrowser.ui.browser

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

/** Same API on released v0.1.3 and new candidates. Times include Compose test settling, not frame latency. */
class BrowserUiPerformanceAudit {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun boundedTwentyFourTabOverviewAndRepeatedTools() {
        lateinit var model:BrowserViewModel
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000){model.ready.value}
        val original=model.data.value.tabs.map { it.id }.toSet()
        val times=mutableListOf<Long>()
        try {
            val added=(24-model.data.value.tabs.size).coerceAtLeast(0)
            assertTrue(model.data.value.tabs.size<=24)
            repeat(added) { compose.activityRule.scenario.onActivity { model.newTab() } }
            compose.waitForIdle()
            repeat(12) {
                val start=SystemClock.elapsedRealtime()
                compose.onNodeWithTag("tabsButton").performClick()
                compose.onNode(hasTestTag("tabList") or hasTestTag("tabGrid")).assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitUntil(5000){compose.onAllNodesWithTag("tabsButton").fetchSemanticsNodes().isNotEmpty()}
                times+=SystemClock.elapsedRealtime()-start
            }
            val pm=compose.activity.packageManager
            val version=pm.getPackageInfo(compose.activity.packageName,0).versionName
            val report=JSONObject().put("version",version).put("tabs",24).put("iterations",times.size)
                .put("settled_overview_round_trip_ms",org.json.JSONArray(times)).put("last_active_url",model.engine?.page?.value?.url)
                .put("api",android.os.Build.VERSION.SDK_INT).put("kind","same-device-instrumented-round-trip-not-frame-benchmark")
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {putString("v014-performance",report.toString())})
        } finally {
            compose.activityRule.scenario.onActivity {model.data.value.tabs.filterNot{it.id in original}.forEach{model.tabs.close(it.id)}}
        }
    }

    /** UI-action timings include Compose settling/readiness checks, not frame benchmarks. */
    @Test fun actualTabSwitchAndRepeatedMenuResourcesWithTwentyFourTabs() {
        lateinit var model: BrowserViewModel
        compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        val originalIds = model.data.value.tabs.map { it.id }.toSet()
        val originalSelection = model.data.value.selectedId
        val ownedIds = mutableListOf<String>()
        val samples = org.json.JSONArray()

        fun displayed(tag: String): Boolean = runCatching {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }.isSuccess
        fun absent(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        fun readyOnBlank(id: String) {
            compose.waitUntil(10000) {
                model.ready.value && model.data.value.selectedId == id &&
                    model.engine?.page?.value?.url == "about:blank" &&
                    absent("tabList") && absent("tabGrid") && absent("resource-sheet") &&
                    absent("menu-newTab") && displayed("homeScreen") &&
                    displayed("tabsButton") && displayed("menuButton")
            }
            compose.waitForIdle()
            compose.onNodeWithTag("homeScreen").assertIsDisplayed()
            compose.onNodeWithTag("tabsButton").assertIsDisplayed().assertHasClickAction()
            assertEquals(id, model.data.value.selectedId)
            assertEquals("about:blank", model.engine?.page?.value?.url)
        }
        fun back() {
            assertTrue("System Back must be dispatched", InstrumentationRegistry.getInstrumentation()
                .uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        }
        fun phase(sample: JSONObject, name: String, action: () -> Unit) {
            val start = SystemClock.elapsedRealtimeNanos()
            action()
            compose.waitForIdle()
            sample.put(name, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0)
        }

        try {
            // Never remove existing tabs to fit the cohort, or silently skip a crowded device.
            assertTrue("24-total cohort needs room for at least two owned tabs (initial <=22)", originalIds.size <= 22)
            assertTrue(originalSelection in originalIds)
            repeat(24 - originalIds.size) {
                compose.activityRule.scenario.onActivity {
                    val before = model.data.value.tabs.map { it.id }.toSet()
                    model.newTab("about:blank")
                    val created = model.data.value.tabs.filterNot { it.id in before }
                    ownedIds.addAll(created.map { it.id })
                    assertEquals("Each setup action must create one owned tab", 1, created.size)
                }
            }
            val cohortIds = originalIds + ownedIds
            assertEquals(24, cohortIds.size)
            assertTrue(cohortIds.size <= 50)
            assertTrue(ownedIds.size >= 2)
            readyOnBlank(ownedIds.last())
            // Detect the old permanent control only while no menu/sheet is open.
            val directResources = displayed("resourcesButton")
            val resourcesRoute = if (directResources) "permanent-resourcesButton" else "menuButton/resourcesButton"
            val containers = linkedSetOf<String>()
            repeat(12) { iteration ->
                val from = model.data.value.selectedId
                val target = if (from == ownedIds.first()) ownedIds.last() else ownedIds.first()
                assertNotEquals("A measured switch must change tabs", from, target)
                readyOnBlank(from)
                val sample = JSONObject().put("iteration", iteration + 1)
                    .put("from_tab_id", from).put("to_tab_id", target)
                val switchStart = SystemClock.elapsedRealtimeNanos()
                var containerTag = ""
                phase(sample, "overview_open_settled_ms") {
                    compose.onNodeWithTag("tabsButton").performClick()
                    compose.waitUntil(5000) { displayed("tabGrid") || displayed("tabList") }
                    containerTag = if (displayed("tabGrid")) "tabGrid" else "tabList"
                    compose.onNodeWithTag(containerTag).assertIsDisplayed()
                }
                containers.add(containerTag)
                sample.put("overview_container", containerTag)
                phase(sample, "target_scroll_settled_ms") {
                    // Both released list and final grid expose lazy ScrollToIndex. Resolve
                    // the index from real state, never from currently composed children.
                    val index = model.data.value.tabs.indexOfFirst { it.id == target }
                    assertTrue(index >= 0)
                    compose.onNodeWithTag(containerTag).performScrollToIndex(index)
                    compose.waitUntil(5000) { displayed("tab-$target") }
                    compose.onNodeWithTag("tab-$target").assertIsDisplayed().assertHasClickAction()
                }
                phase(sample, "card_select_settled_ms") {
                    compose.onNodeWithTag("tab-$target").performClick()
                    readyOnBlank(target)
                }
                sample.put("actual_switch_round_trip_settled_ms",
                    (SystemClock.elapsedRealtimeNanos() - switchStart) / 1_000_000.0)
                phase(sample, "menu_open_settled_ms") {
                    compose.onNodeWithTag("menuButton").performClick()
                    compose.waitUntil(5000) { displayed("menu-newTab") }
                    compose.onNodeWithTag("menu-newTab").assertIsDisplayed()
                }
                phase(sample, "menu_close_settled_ms") {
                    back()
                    readyOnBlank(target)
                }
                val resourcesStart = SystemClock.elapsedRealtimeNanos()
                if (!directResources) {
                    phase(sample, "resources_menu_open_settled_ms") {
                        compose.onNodeWithTag("menuButton").performClick()
                        compose.waitUntil(5000) { displayed("menu-newTab") && displayed("resourcesButton") }
                        compose.onNodeWithTag("resourcesButton").assertIsDisplayed().assertHasClickAction()
                    }
                }
                phase(sample, "resources_open_settled_ms") {
                    compose.onNodeWithTag("resourcesButton").assertIsDisplayed().performClick()
                    compose.waitUntil(5000) {
                        displayed("resource-sheet") && runCatching {
                            compose.onNodeWithText("尚未发现视频").assertIsDisplayed()
                        }.isSuccess
                    }
                    compose.onNodeWithTag("resource-sheet").assertIsDisplayed()
                    compose.onNodeWithText("尚未发现视频").assertIsDisplayed()
                }
                phase(sample, "resources_close_settled_ms") {
                    back()
                    readyOnBlank(target)
                }
                sample.put("resources_route_round_trip_settled_ms",
                    (SystemClock.elapsedRealtimeNanos() - resourcesStart) / 1_000_000.0)
                assertEquals("Cohort must remain stable", cohortIds, model.data.value.tabs.map { it.id }.toSet())
                assertEquals(24, model.data.value.tabs.size)
                samples.put(sample)
            }
            assertEquals(12, samples.length())
            val activity = compose.activity
            val pkg = activity.packageManager.getPackageInfo(activity.packageName, 0)
            val config = activity.resources.configuration
            val metrics = activity.resources.displayMetrics
            val webView = if (android.os.Build.VERSION.SDK_INT >= 26)
                android.webkit.WebView.getCurrentWebViewPackage() else null
            val report = JSONObject()
                .put("source", "actual-MainActivity-Compose-UI-actions")
                .put("test", "actualTabSwitchAndRepeatedMenuResourcesWithTwentyFourTabs")
                .put("kind", "settled-ui-action-timings-not-frame-benchmark")
                .put("timing", "elapsedRealtimeNanos; includes Compose settling and readiness assertions")
                .put("package", activity.packageName).put("version", pkg.versionName)
                .put("version_code", if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong())
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("device", JSONObject().put("manufacturer", android.os.Build.MANUFACTURER)
                    .put("model", android.os.Build.MODEL).put("fingerprint", android.os.Build.FINGERPRINT)
                    .put("width_px", metrics.widthPixels).put("height_px", metrics.heightPixels)
                    .put("density_dpi", config.densityDpi).put("font_scale", config.fontScale)
                    .put("screen_width_dp", config.screenWidthDp).put("screen_height_dp", config.screenHeightDp)
                    .put("orientation", config.orientation).put("ui_mode", config.uiMode)
                    .put("locales", config.locales.toLanguageTags()))
                .put("webview", webView?.let { JSONObject().put("package", it.packageName).put("version", it.versionName) }
                    ?: JSONObject.NULL)
                .put("tabs", 24).put("max_tabs", 50).put("owned_tabs", ownedIds.size)
                .put("iterations", samples.length()).put("resources_route", resourcesRoute)
                .put("overview_containers", org.json.JSONArray(containers.toList()))
                .put("phases", samples)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("v014-performance", report.toString())
            })
        } finally {
            compose.activityRule.scenario.onActivity {
                // Model calls are setup/cleanup only, never the measured switch action.
                try {
                    ownedIds.forEach { id ->
                        if (model.data.value.tabs.any { it.id == id }) model.tabs.close(id)
                    }
                } finally {
                    model.tabs.select(originalSelection)
                }
                assertEquals(originalIds, model.data.value.tabs.map { it.id }.toSet())
                assertEquals(originalSelection, model.data.value.selectedId)
            }
            compose.waitForIdle()
        }
    }

}

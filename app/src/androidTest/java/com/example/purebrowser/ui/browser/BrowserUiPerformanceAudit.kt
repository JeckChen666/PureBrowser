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
}

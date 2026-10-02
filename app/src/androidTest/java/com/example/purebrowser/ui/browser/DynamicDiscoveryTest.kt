package com.example.purebrowser.ui.browser

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import com.example.purebrowser.media.Evidence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class DynamicDiscoveryTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun delayedDomAndSameOriginFrameAppearWithoutOpeningResourcePanel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dynamicFixture")=="true")
        lateinit var model:BrowserViewModel
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000) { model.ready.value }
        compose.activityRule.scenario.onActivity { model.newTab() }
        compose.onNodeWithTag("addressInput").performClick().performTextReplacement("http://127.0.0.1:8765/dynamic.html")
        compose.onNodeWithTag("navigateButton").performClick()
        compose.waitUntil(20000) { model.sniffer!!.candidates.value.any { it.url.contains("token=dynamic%2Bdemo") && Evidence.DOM in it.sources } &&
            model.sniffer!!.candidates.value.any { it.url.endsWith("sample.webm") && Evidence.DOM in it.sources } }
        val candidates=model.sniffer!!.candidates.value
        assertTrue(candidates.filter { Evidence.DOM in it.sources }.all { it.reliableSource && it.frameUrl!=null })
        val first=model.data.value.selectedId
        compose.activityRule.scenario.onActivity { model.newTab("http://127.0.0.1:8765/second.html") }
        compose.waitUntil(20000) { model.sniffer!!.candidates.value.any { it.url.endsWith("second.mp4") && Evidence.DOM in it.sources } }
        assertFalse(model.sniffer!!.candidates.value.any { it.url.contains("dynamic%2Bdemo") })
        compose.activityRule.scenario.onActivity { model.tabs.select(first) }
        compose.waitUntil(10000) { model.sniffer!!.candidates.value.any { it.url.contains("dynamic%2Bdemo") } }
    }
}

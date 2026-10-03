package com.example.purebrowser.ui.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.MainActivity
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Production browser shell, no fake WebViews; each test owns its home tab. */
class V014BrowserChromeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var model:BrowserViewModel
    private var ownTab:String?=null
    @Before fun ownHome() {
        compose.activityRule.scenario.onActivity { model=ViewModelProvider(it)[BrowserViewModel::class.java] }
        compose.waitUntil(10000){model.ready.value}
        compose.activityRule.scenario.onActivity { model.newTab();ownTab=model.data.value.selectedId }
        compose.waitUntil(5000){model.engine?.page?.value?.url=="about:blank"}
    }
    @After fun cleanup() { compose.activityRule.scenario.onActivity { ownTab?.let(model.tabs::close) } }
    @Test fun addressIsUniqueAndCancelRestoresHomeWithoutNavigation() {
        compose.onNodeWithTag("addressInput").performClick().performTextReplacement("not submitted")
        compose.onNodeWithTag("addressInput").assertIsFocused()
        assertEquals(1,compose.onAllNodesWithTag("addressInput").fetchSemanticsNodes().size)
        compose.onNodeWithTag("tabsButton").assertDoesNotExist()
        compose.onNodeWithTag("cancelAddressButton").performClick()
        compose.onNodeWithTag("addressInput").assertTextEquals("搜索或输入网址")
        compose.waitUntil(5000){compose.onAllNodesWithTag("tabsButton").fetchSemanticsNodes().isNotEmpty()}
        assertEquals("about:blank",model.engine?.page?.value?.url)
    }
    @Test fun menuToolsReplaceOneAnotherAndSystemBackDoesNotCloseTab() {
        val before=model.data.value.tabs.size
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("tabsButton").assertDoesNotExist()
        compose.onNodeWithTag("resourcesButton").performClick()
        compose.onNodeWithTag("resource-sheet").assertIsDisplayed()
        compose.onNodeWithTag("dismissMenuButton").assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5000){compose.onAllNodesWithTag("resource-sheet").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        assertEquals(before,model.data.value.tabs.size)
        assertEquals(ownTab,model.data.value.selectedId)
    }
    @Test fun managementAndToolsPreserveRealWebPageScrollAndNavigationHistory() {
        val server=java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val html="<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><title>Owned long article</title><body style='min-height:3600px'>Owned offline scroll fixture</body>".toByteArray()
        val worker=kotlin.concurrent.thread(isDaemon=true) {
            while(!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout=2000
                val input=socket.getInputStream().bufferedReader()
                while(true) { val line=input.readLine() ?: break;if(line.isEmpty())break }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray());write(html);flush()
                }
            } } catch(_:Exception) { if(server.isClosed)break }
        }
        val url="http://127.0.0.1:${server.localPort}/owned-long-article"
        try {
            compose.activityRule.scenario.onActivity { model.navigate(url) }
            compose.waitUntil(15000) { model.engine?.page?.value?.let { it.url==url && it.title=="Owned long article" && it.progress==100 && it.error==null }==true }
            val second=url+"?stage=second"
            compose.activityRule.scenario.onActivity { model.navigate(second) }
            compose.waitUntil(15000) { model.engine?.page?.value?.let { it.url==second && it.progress==100 && it.canGoBack && it.error==null }==true }
            val original=model.tabs.active.value
            val laidOut=java.util.concurrent.atomic.AtomicBoolean(false)
            compose.activityRule.scenario.onActivity { original!!.mountedPreviewView()!!.evaluateJavascript("document.documentElement.scrollHeight") { value -> laidOut.set(value.toIntOrNull()?.let { it>1000 }==true) } }
            compose.waitUntil(10000) { laidOut.get() }
            var scroll=0;var historySize=0;var historyIndex=0
            compose.activityRule.scenario.onActivity { original!!.mountedPreviewView()!!.scrollTo(0,800);scroll=original.mountedPreviewView()!!.scrollY;val stack=original.mountedPreviewView()!!.copyBackForwardList();historySize=stack.size;historyIndex=stack.currentIndex }
            assertTrue(scroll>0)
            compose.onNodeWithTag("menuButton").performClick()
            compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
            compose.onNodeWithTag("routeBackButton").performClick()
            compose.onNodeWithTag("tabsButton").performClick()
            compose.onNodeWithTag("dismissTabsButton").performClick()
            compose.onNodeWithTag("menuButton").performClick()
            compose.onNodeWithTag("resourcesButton").performClick()
            compose.onNodeWithTag("resource-sheet").assertIsDisplayed()
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(5000) { compose.onAllNodesWithTag("resource-sheet").fetchSemanticsNodes().isEmpty() }
            compose.activityRule.scenario.onActivity { assertSame(original,model.tabs.active.value);assertEquals(scroll,original!!.mountedPreviewView()!!.scrollY);assertEquals(second,model.engine!!.page.value.url);val stack=original.mountedPreviewView()!!.copyBackForwardList();assertEquals(historySize,stack.size);assertEquals(historyIndex,stack.currentIndex);assertTrue(model.engine!!.page.value.canGoBack) }
        } finally {
            server.close();worker.join(2000)
            compose.activityRule.scenario.onActivity { model.data.value.history.filter{it.url==url || it.url==url+"?stage=second"}.forEach{model.deleteHistory(it.id)} }
        }
    }
    @Test fun settingsRoundTripPreservesTabAndBrowsingContext() {
        val id=model.data.value.selectedId
        val engine=model.engine
        compose.onNodeWithTag("menuButton").performClick()
        compose.onNodeWithTag("menu-settings").performScrollTo().performClick()
        compose.onNodeWithTag("addressInput").assertDoesNotExist()
        compose.onNodeWithTag("routeBackButton").performClick()
        compose.onNodeWithTag("homeScreen").assertIsDisplayed()
        assertEquals(id,model.data.value.selectedId)
        assertSame(engine,model.engine)
    }
}

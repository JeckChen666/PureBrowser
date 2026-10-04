package com.example.purebrowser.media.rules

import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.ResourceSniffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM test of the settle wiring: findings become Evidence.RULE candidates, families reuse T64. */
class SiteRulesCoordinatorTest {
    @Test fun findingsBecomeRuleCandidatesAndFamilyMarkersReuseHarvestedConfig() = runBlocking {
        val sniffer = ResourceSniffer()
        val set = RuleSet.parse("""{"version":1,"rules":[
            {"id":"p","version":1,"match":{"path":"\\/method\\/video\\.get"},"actions":[{"type":"manifestHint","kind":"unknown"}]},
            {"id":"f","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},"actions":[{"type":"playerConfig","family":"flashvars"}]}]}""")
        val coordinator = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { set },
            recentRequests = { listOf("https://api.tube.example/method/video.get?x=1") },
            domSnapshot = null,
            latestPlayerConfig = { "kvs" to """{"video_url":{"720":"https://media.example/720.mp4"}}""" },
        )
        val epoch = sniffer.beginPage()
        coordinator.onPageSettled(epoch, "https://tube.example/watch/1")
        val candidates = sniffer.candidates.value
        val urls = candidates.map { it.url }
        assertTrue(urls.contains("https://api.tube.example/method/video.get?x=1"))
        assertTrue(urls.contains("https://media.example/720.mp4"))
        assertTrue(candidates.all { Evidence.RULE in it.sources })
        assertEquals(MediaKind.UNKNOWN, candidates.first { it.url.contains("method") }.kind)
        assertEquals("720", candidates.first { it.url.endsWith("720.mp4") }.title)
        assertEquals("站点规则", coordinator.matchedNote(epoch, "https://tube.example/watch/1"))
        assertNull(coordinator.matchedNote(epoch, "https://other.example/watch/2"))
        assertNull(coordinator.matchedNote(epoch + 5, "https://tube.example/watch/1"))
    }

    @Test fun newerEpochWhileRunningIsNotLostAndStaleSettlesAreIgnored() = runBlocking {
        val sniffer = ResourceSniffer()
        val set = RuleSet.parse("""{"version":1,"rules":[
            {"id":"p","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},"actions":[{"type":"manifestHint","kind":"unknown"}]}]}""")
        var gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var settleCount = 0
        val coordinator = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { settleCount++; if (settleCount == 1) gate.await(); set },
            recentRequests = { emptyList() },
            domSnapshot = null,
            latestPlayerConfig = { null },
        )
        val first = sniffer.beginPage()
        coordinator.onPageSettled(first, "https://tube.example/one") // suspends inside ruleSet()
        val second = sniffer.beginPage()
        coordinator.onPageSettled(second, "https://tube.example/two") // queued behind the running job
        coordinator.onPageSettled(first, "https://tube.example/one") // stale re-settle: ignored
        gate.complete(Unit)
        // The Unconfined scope drained both pages inline; the queued epoch still ran.
        assertEquals("站点规则", coordinator.matchedNote(second, "https://tube.example/two"))
    }

    @Test fun nonWebSettlesAreIgnoredWithoutLoadingRules() {
        var loads = 0
        val coordinator = SiteRulesCoordinator(
            sniffer = ResourceSniffer(),
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { loads++; null },
            recentRequests = { emptyList() },
            domSnapshot = null,
            latestPlayerConfig = { null },
        )
        coordinator.onPageSettled(1L, "about:blank")
        coordinator.onPageSettled(1L, "file:///tmp/x.html")
        assertEquals(0, loads)
    }
}

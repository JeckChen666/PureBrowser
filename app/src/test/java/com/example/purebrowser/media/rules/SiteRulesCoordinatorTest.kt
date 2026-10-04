package com.example.purebrowser.media.rules

import com.example.purebrowser.media.Evidence
import com.example.purebrowser.media.MediaKind
import com.example.purebrowser.media.ResourceSniffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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

    /**
     * Virtual-time fake clock: the debounce delay runs on the test scheduler, no real sleeps. The
     * coordinator runs on a StandardTestDispatcher scope rather than runTest's backgroundScope:
     * coroutines-test 1.10 deliberately leaves background-scope tasks out of advanceUntilIdle, so a
     * background-scoped coordinator would never even run its settle pass under virtual time.
     */
    @Test fun lateSignalsReRunEvaluationDebouncedWithinBudget() = runTest {
        val sniffer = ResourceSniffer()
        val set = RuleSet.parse("""{"version":1,"rules":[
            {"id":"p","version":1,"match":{"hosts":"(^|\\.)tube\\.example$","path":"\\/method\\/video\\.get"},
             "actions":[{"type":"manifestHint","kind":"unknown"}]}]}""")
        var requests = emptyList<String>()
        var evaluations = 0
        val coordinator = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            ruleSet = { evaluations++; set },
            recentRequests = { requests },
            domSnapshot = null,
            latestPlayerConfig = { null },
            signalDebounceMs = 500,
        )
        val epoch = sniffer.beginPage()
        coordinator.onPageSettled(epoch, "https://tube.example/watch/1")
        advanceUntilIdle()
        assertEquals(1, evaluations) // settle pass: no endpoint seen yet, nothing matched
        assertTrue(sniffer.candidates.value.isEmpty())

        // The config endpoint fires after settle (late fetch); a signal burst merges into one re-run.
        requests = listOf("https://api.tube.example/method/video.get?v=1")
        coordinator.onPageSignal(epoch)
        coordinator.onPageSignal(epoch)
        coordinator.onPageSignal(epoch)
        advanceTimeBy(499)
        assertEquals(1, evaluations) // still inside the debounce window
        advanceUntilIdle()
        assertEquals(2, evaluations) // exactly one debounced extra evaluation
        val candidate = sniffer.candidates.value.firstOrNull { it.url.startsWith("https://api.tube.example/method/video.get") }
        assertNotNull(candidate)
        assertTrue(candidate!!.sources.contains(Evidence.RULE))
        assertEquals("站点规则", coordinator.matchedNote(epoch, "https://tube.example/watch/1"))

        coordinator.onPageSignal(epoch)
        advanceUntilIdle()
        assertEquals(3, evaluations) // second and final extra evaluation for this epoch

        coordinator.onPageSignal(epoch)
        coordinator.onPageSignal(epoch)
        advanceUntilIdle()
        assertEquals(3, evaluations) // per-epoch budget of two extra runs is spent
    }

    @Test fun signalsForSupersededEpochsNeverReRun() = runTest {
        val sniffer = ResourceSniffer()
        val set = RuleSet.parse("""{"version":1,"rules":[
            {"id":"p","version":1,"match":{"hosts":"(^|\\.)tube\\.example$","path":"\\/method\\/video\\.get"},
             "actions":[{"type":"manifestHint","kind":"unknown"}]}]}""")
        var evaluations = 0
        val coordinator = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            ruleSet = { evaluations++; set },
            recentRequests = { listOf("https://api.tube.example/method/video.get?v=1") },
            domSnapshot = null,
            latestPlayerConfig = { null },
            signalDebounceMs = 500,
        )
        val first = sniffer.beginPage()
        coordinator.onPageSettled(first, "https://tube.example/one")
        val second = sniffer.beginPage()
        coordinator.onPageSettled(second, "https://tube.example/two")
        coordinator.onPageSignal(first) // late signal for a page older than the settled one
        advanceUntilIdle()
        assertEquals(2, evaluations) // only the two settle passes ran
        assertEquals("站点规则", coordinator.matchedNote(second, "https://tube.example/two"))

        // The live epoch's own signals still get their debounced re-run.
        coordinator.onPageSignal(second)
        advanceUntilIdle()
        assertEquals(3, evaluations)
    }
}

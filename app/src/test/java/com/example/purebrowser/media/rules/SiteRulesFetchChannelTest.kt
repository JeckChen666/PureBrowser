package com.example.purebrowser.media.rules

import com.example.purebrowser.media.Evidence
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

/**
 * T83/T85 channel tests around the coordinator: the injected fetch adapter is policy-checked,
 * budgeted, cached per (rule, fetch, epoch) and redirect-validated; budget overflow downgrades by
 * skipping instead of failing; inline harvest content is buffered bounded per epoch. The null
 * default keeps the v0.1.7 behavior, which SiteRulesCoordinatorTest already covers untouched.
 */
class SiteRulesFetchChannelTest {
    private val set = RuleSet.parse("""{"version":3,"rules":[
        {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$","path":"\\/watch\\/([0-9]+)"},
         "actions":[{"type":"jsonExtract","pointers":["h720"]}],
         "fetch":[{"id":"cfg","url":"https://api.tube.example/v/{{m1}}","maxBytes":8192}]}]}""")

    private fun coordinator(
        sniffer: ResourceSniffer,
        budget: RuleFetchBudget = RuleFetchBudget(),
        fetcher: (suspend (FetchSpec, String) -> RuleDocument?)? = null,
    ) = SiteRulesCoordinator(
        sniffer = sniffer,
        scope = CoroutineScope(Dispatchers.Unconfined),
        ruleSet = { set },
        recentRequests = { listOf("https://tube.example/watch/7") },
        domSnapshot = null,
        latestPlayerConfig = { null },
        fetcher = fetcher,
        fetchBudget = budget,
    )

    @Test fun fetchedDocumentsSurfaceAsRuleCandidates() = runBlocking {
        val sniffer = ResourceSniffer()
        var opened: String? = null
        val c = coordinator(sniffer, fetcher = { _, url ->
            opened = url
            RuleDocument(url, 200, """{"h720":"https://cdn.tube.example/720.mp4"}""", "application/json")
        })
        val epoch = sniffer.beginPage()
        c.onPageSettled(epoch, "https://tube.example/watch/7")
        // Template placeholder bound from the path face's positional group.
        assertEquals("https://api.tube.example/v/7", opened)
        val candidate = sniffer.candidates.value.firstOrNull { it.url == "https://cdn.tube.example/720.mp4" }
        assertNotNull(candidate)
        assertTrue(candidate!!.sources.contains(Evidence.RULE))
    }

    @Test fun offFaceTargetsAreNeverOpened() = runBlocking {
        val sniffer = ResourceSniffer()
        var opened: String? = null
        // The rule's hosts face covers only www.tube.example while the template renders to
        // api.tube.example: the target check refuses the fetch before any IO happens.
        val offFaceSet = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)www\\.tube\\.example$","path":"\\/watch\\/([0-9]+)"},
             "actions":[{"type":"jsonExtract","pointers":["a"]}],
             "fetch":[{"id":"cfg","url":"https://api.tube.example/v/{{m1}}","maxBytes":8192}]}]}""")
        val c = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { offFaceSet },
            recentRequests = { listOf("https://www.tube.example/watch/7") },
            domSnapshot = null,
            latestPlayerConfig = { null },
            fetcher = { _, url -> opened = url; RuleDocument(url, 200, "{}") },
        )
        val epoch = sniffer.beginPage()
        c.onPageSettled(epoch, "https://www.tube.example/watch/7")
        assertNull("fetch target outside the rule's host face must not reach IO", opened)
        assertTrue(c.fetchOverflowed(epoch))
    }

    @Test fun redirectHopsAreFollowedOnlyInsideTheWhitelist() = runBlocking {
        val sniffer = ResourceSniffer()
        val opened = mutableListOf<String>()
        val c = coordinator(sniffer, fetcher = { _, url ->
            opened += url
            when (opened.size) {
                1 -> RuleDocument(url, 302, "", location = "https://cdn.tube.example/real.json")
                else -> RuleDocument(url, 200, """{"h720":"https://cdn.tube.example/720.mp4"}""")
            }
        })
        val epoch = sniffer.beginPage()
        c.onPageSettled(epoch, "https://tube.example/watch/7")
        assertEquals(listOf("https://api.tube.example/v/7", "https://cdn.tube.example/real.json"), opened)
        assertNotNull(sniffer.candidates.value.firstOrNull { it.url == "https://cdn.tube.example/720.mp4" })
    }

    @Test fun offWhitelistAndDowngradeRedirectsStopTheFetch() = runBlocking {
        listOf(
            "https://evil.example/steal",          // outside the rule's hosts face
            "http://api.tube.example/downgraded",  // https downgrade
            "https://169.254.169.254/meta",        // link-local target
        ).forEach { location ->
            val sniffer = ResourceSniffer()
            val opened = mutableListOf<String>()
            val c = coordinator(sniffer, fetcher = { _, url -> opened += url; RuleDocument(url, 302, "", location = location) })
            val epoch = sniffer.beginPage()
            c.onPageSettled(epoch, "https://tube.example/watch/7")
            assertEquals(location, listOf("https://api.tube.example/v/7"), opened)
            assertTrue(location, sniffer.candidates.value.none { it.url.contains("720.mp4") })
            assertTrue(location, c.fetchOverflowed(epoch))
        }
    }

    @Test fun resultsAreCachedPerRuleFetchAndEpoch() = runTest {
        val sniffer = ResourceSniffer()
        var opens = 0
        val c = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            ruleSet = { set },
            recentRequests = { listOf("https://tube.example/watch/7") },
            domSnapshot = null,
            latestPlayerConfig = { null },
            fetcher = { _, url -> opens++; RuleDocument(url, 200, """{"h720":"https://cdn.tube.example/720.mp4"}""") },
        )
        val epoch = sniffer.beginPage()
        c.onPageSettled(epoch, "https://tube.example/watch/7")
        advanceUntilIdle()
        assertEquals(1, opens)
        // Late signals re-run the evaluation debounced; the cached (rule, fetch, epoch) result
        // means the adapter is NOT opened a second time for the same epoch.
        c.onPageSignal(epoch)
        c.onPageSignal(epoch)
        advanceTimeBy(499)
        assertEquals(1, opens)
        advanceUntilIdle()
        assertEquals(1, opens)
        // A new page is a new epoch: the cache rolls over and the fetch runs again.
        val later = sniffer.beginPage()
        c.onPageSettled(later, "https://tube.example/watch/7")
        advanceUntilIdle()
        assertEquals(2, opens)
    }

    @Test fun budgetOverflowDowngradesBySkippingNotFailing() = runBlocking {
        val sniffer = ResourceSniffer()
        val opened = mutableListOf<String>()
        val tight = RuleFetchBudget(maxFetchesPerAction = 2)
        val manyFetches = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$","path":"\\/watch\\/([0-9]+)"},
             "actions":[{"type":"jsonExtract","pointers":["a"]}],
             "fetch":[
               {"id":"f1","url":"https://api.tube.example/1","maxBytes":1024},
               {"id":"f2","url":"https://api.tube.example/2","maxBytes":1024},
               {"id":"f3","url":"https://api.tube.example/3","maxBytes":1024},
               {"id":"f4","url":"https://api.tube.example/4","maxBytes":1024}]}]}""")
        val c = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { manyFetches },
            recentRequests = { listOf("https://tube.example/watch/7") },
            domSnapshot = null,
            latestPlayerConfig = { null },
            fetcher = { _, url -> opened += url; RuleDocument(url, 200, """{"a":"https://cdn.tube.example/x.mp4"}""") },
            fetchBudget = tight,
        )
        val epoch = sniffer.beginPage()
        c.onPageSettled(epoch, "https://tube.example/watch/7")
        // Exactly the budgeted fetches ran; the overflow downgraded the rest without crashing.
        assertEquals(listOf("https://api.tube.example/1", "https://api.tube.example/2"), opened)
        assertTrue(c.fetchOverflowed(epoch))
        // The in-budget fetch still produced its candidate.
        assertNotNull(sniffer.candidates.value.firstOrNull { it.url == "https://cdn.tube.example/x.mp4" })
    }

    @Test fun probeSideRequestsConsumeTheSharedEpochLedger() = runBlocking {
        val sniffer = ResourceSniffer()
        val opened = mutableListOf<String>()
        val budget = RuleFetchBudget(maxFetchesPerAction = 8, maxSharedEpochRequests = 3)
        val c = coordinator(sniffer, budget, fetcher = { _, url -> opened += url; RuleDocument(url, 200, "{}") })
        val epoch = sniffer.beginPage()
        // AutoProbeQueue-style accounting for the same epoch eats the merged ceiling first.
        repeat(3) { budget.noteExternalRequest(epoch) }
        c.onPageSettled(epoch, "https://tube.example/watch/7")
        assertTrue(opened.isEmpty())
        assertTrue(c.fetchOverflowed(epoch))
    }

    @Test fun inlineHarvestIsBufferedBoundedPerEpochAndFeedsTheEngine() = runBlocking {
        val sniffer = ResourceSniffer()
        val inlineSet = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},
             "actions":[{"type":"jsonExtract","pointers":["h720"]}]}]}""")
        val c = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { inlineSet },
            recentRequests = { emptyList() },
            domSnapshot = null,
            latestPlayerConfig = { null },
        )
        val epoch = sniffer.beginPage()
        c.onInlineData(epoch, "ldjson", """{"h720":"https://cdn.tube.example/720.mp4"}""")
        c.onInlineData(epoch, "json", """{"h720":"https://cdn.tube.example/alt.mp4"}""")
        c.onInlineData(epoch, "unknown-kind", """{"h720":"https://cdn.tube.example/nope.mp4"}""")
        c.onPageSettled(epoch, "https://tube.example/watch/1")
        val urls = sniffer.candidates.value.map { it.url }
        assertTrue(urls.contains("https://cdn.tube.example/720.mp4"))
        assertTrue(urls.contains("https://cdn.tube.example/alt.mp4"))
        assertFalse(urls.contains("https://cdn.tube.example/nope.mp4"))
    }

    @Test fun inlineBufferCapsItemsPerEpoch() = runBlocking {
        val sniffer = ResourceSniffer()
        val inlineSet = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},
             "actions":[{"type":"jsonExtract","pointers":["h720"]}]}]}""")
        val c = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { inlineSet },
            recentRequests = { emptyList() },
            domSnapshot = null,
            latestPlayerConfig = { null },
        )
        val epoch = sniffer.beginPage()
        // The item cap keeps only the first eight blocks; the ninth never reaches the engine.
        (1..9).forEach { c.onInlineData(epoch, "json", """{"h720":"https://cdn.tube.example/$it.mp4"}""") }
        c.onPageSettled(epoch, "https://tube.example/watch/1")
        val urls = sniffer.candidates.value.map { it.url }.filter { it.endsWith(".mp4") }
        assertEquals((1..8).map { "https://cdn.tube.example/$it.mp4" }, urls)
    }

    @Test fun inlineBufferCapsTotalCharsPerEpoch() = runBlocking {
        val sniffer = ResourceSniffer()
        val inlineSet = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},
             "actions":[{"type":"jsonExtract","pointers":["h720"]}]}]}""")
        val c = SiteRulesCoordinator(
            sniffer = sniffer,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ruleSet = { inlineSet },
            recentRequests = { emptyList() },
            domSnapshot = null,
            latestPlayerConfig = { null },
        )
        val epoch = sniffer.beginPage()
        // Four max-size script blocks fill the 1 MiB page total exactly…
        val big = "x".repeat(RuleEngine.MAX_REGEX_INPUT_CHARS)
        (1..4).forEach { c.onInlineData(epoch, "script", big) }
        // …so any later block is refused regardless of kind, without disturbing the buffer.
        c.onInlineData(epoch, "json", """{"h720":"https://cdn.tube.example/nope.mp4"}""")
        c.onPageSettled(epoch, "https://tube.example/watch/1")
        assertTrue(sniffer.candidates.value.none { it.url.endsWith(".mp4") })
    }
}

package com.example.purebrowser.media.rules

import org.junit.Assert.*
import org.junit.Test

/**
 * T86 pure-logic tests: the per-site session opt-in store (default UNKNOWN ≡ off, registrable-domain
 * keys), the toggle-offer gating, and the safety negatives — opt-out keeps the cookie out of rule
 * fetch headers, cross-registrable-domain session hosts never produce an offer, and a session rule
 * that also declares a WRITE-method fetch never loads at all (GET-only schema, D7).
 */
class SessionOptInTest {

    private fun store(): Pair<SessionOptIn, MutableMap<String, SessionOptInState>> {
        val map = linkedMapOf<String, SessionOptInState>()
        return SessionOptIn(read = { map[it] }, write = { domain, state -> map[domain] = state }) to map
    }

    @Test fun unknownByDefaultMeansEffectivelyOffAndHeaderStaysClean() {
        val (optIn, map) = store()
        assertTrue(map.isEmpty())
        assertEquals(SessionOptInState.UNKNOWN, optIn.stateFor("tube.example"))
        assertEquals(SessionOptInState.UNKNOWN, optIn.stateFor(null))
        assertEquals(SessionOptInState.UNKNOWN, optIn.stateFor(""))
        // The store default (UNKNOWN) keeps the session channel anonymous even for a session rule.
        val rule = SiteRule(
            "r", 1,
            RuleMatch("(^|\\.)tube\\.example$", null, Regex("(^|\\.)tube\\.example$"), null),
            emptyList(), null,
            fetch = emptyList(),
            session = SessionSpec(listOf("api.tube.example")),
        )
        assertNull(
            RuleFetchPolicy.sessionCookie(rule, "https://api.tube.example/config", optIn.isOptedIn("https://tube.example/watch/1"), cookieFor = { "sid=1" }),
        )
    }

    @Test fun optInRoundTripUnlocksOnlyItsOwnRegistrableDomain() {
        val (optIn, _) = store()
        assertTrue(optIn.setFromPage("https://www.tube.example/watch/7", SessionOptInState.OPT_IN))
        assertEquals(SessionOptInState.OPT_IN, optIn.stateFor("tube.example"))
        assertTrue(optIn.isOptedIn("https://tube.example/watch/1"))
        assertTrue(optIn.isOptedIn("https://deep.api.tube.example/x"))
        // A different registrable domain never inherits the grant.
        assertFalse(optIn.isOptedIn("https://tube.other.example/watch/1"))
        assertFalse(optIn.isOptedIn("about:blank"))
    }

    @Test fun optOutBlocksTheCookieEvenThoughTheRuleDeclaresSession() {
        val (optIn, _) = store()
        optIn.setFromPage("https://tube.example/watch/1", SessionOptInState.OPT_OUT)
        assertEquals(SessionOptInState.OPT_OUT, optIn.stateFor("tube.example"))
        val rule = SiteRule(
            "r", 1,
            RuleMatch("(^|\\.)tube\\.example$", null, Regex("(^|\\.)tube\\.example$"), null),
            emptyList(), null,
            fetch = emptyList(),
            session = SessionSpec(listOf("api.tube.example")),
        )
        assertFalse(optIn.isOptedIn("https://tube.example/watch/1"))
        assertNull(
            RuleFetchPolicy.sessionCookie(rule, "https://api.tube.example/config", optIn.isOptedIn("https://tube.example/watch/1"), cookieFor = { "sid=1" }),
        )
        // Flipping back to OPT_IN re-allows it; OPT_OUT is a decision, not a lock.
        optIn.setForDomain("tube.example", SessionOptInState.OPT_IN)
        assertEquals("sid=1", RuleFetchPolicy.sessionCookie(rule, "https://api.tube.example/config", optIn.isOptedIn("https://tube.example/watch/1"), cookieFor = { "sid=1" }))
    }

    @Test fun domainKeyUsesRegistrableDomainsNotHosts() {
        assertEquals("tube.example", SessionOptIn.domainKey("https://deep.api.tube.example/watch/1?x=2"))
        assertEquals("tube.co.uk", SessionOptIn.domainKey("https://www.tube.co.uk/a"))
        assertEquals("example.museum", SessionOptIn.domainKey("http://example.museum/"))
        assertNull(SessionOptIn.domainKey("https://192.168.1.4/index.html")) // IP literals have no key
        assertNull(SessionOptIn.domainKey("about:blank"))
        assertNull(SessionOptIn.domainKey("https://intranet/"))
    }

    @Test fun encodeDecodeRoundTripAndGarbageStayUnknown() {
        for (state in SessionOptInState.entries) assertEquals(state, SessionOptIn.decode(SessionOptIn.encode(state)))
        assertNull(SessionOptIn.decode(null))
        assertNull(SessionOptIn.decode(""))
        assertNull(SessionOptIn.decode("yes"))
        assertNull(SessionOptIn.decode("OPT_IN "))
        // The adapter contract: an unreadable persisted value can never surface as an opt-in.
        assertEquals(SessionOptInState.UNKNOWN, SessionOptIn(read = { SessionOptIn.decode("corrupt") }, write = { _, _ -> }).stateFor("tube.example"))
    }

    @Test fun coveredDomainFollowsThePageRegistrableDomain() {
        val session = SessionSpec(listOf("api.tube.example", "media.tube.example"))
        assertEquals("tube.example", SessionTogglePolicy.coveredDomain(session, "https://www.tube.example/watch/1"))
        assertNull(SessionTogglePolicy.coveredDomain(null, "https://tube.example/watch/1"))
        assertNull(SessionTogglePolicy.coveredDomain(SessionSpec(emptyList()), "https://tube.example/watch/1"))
        assertNull(SessionTogglePolicy.coveredDomain(session, "about:blank"))
        assertNull(SessionTogglePolicy.coveredDomain(session, "not a url"))
    }

    @Test fun crossRegistrableDomainSessionHostsNeverProduceAnOffer() {
        // A hosts face may legally span two registrable domains (an alternation), and the loader
        // accepts a session host inside EITHER of them — the UI-level decision is what refuses to
        // offer a toggle when the session sits outside the PAGE's own registrable domain.
        val spanning = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.(com|net)$"},
             "actions":[{"type":"manifestHint","kind":"unknown"}],
             "session":{"hosts":["api.tube.net"]}}]}""")
        assertEquals(1, spanning.rules.size) // load-time containment passed (inside the face)
        assertEquals("tube.net", SessionTogglePolicy.coveredDomain(spanning.rules.single().session, "https://www.tube.net/watch/1"))
        assertNull(SessionTogglePolicy.coveredDomain(spanning.rules.single().session, "https://www.tube.com/watch/1"))
        // And the loader still rejects a session host outside the face's registrable domains at all.
        val outside = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},
             "actions":[{"type":"manifestHint","kind":"unknown"}],
             "session":{"hosts":["other.example"]}}]}""")
        assertEquals(0, outside.rules.size)
    }

    @Test fun offerVisibilityAndDefaultCheckedSemantics() {
        // UNKNOWN: visible, default OFF (the one-line-explanation state).
        val unknown = SessionToggleOffer.forDomain("tube.example", SessionOptInState.UNKNOWN)!!
        assertTrue(unknown.visible)
        assertFalse(unknown.checkedByDefault)
        // OPT_IN: visible, default ON.
        val optedIn = SessionToggleOffer.forDomain("tube.example", SessionOptInState.OPT_IN)!!
        assertTrue(optedIn.visible)
        assertTrue(optedIn.checkedByDefault)
        // OPT_OUT: not offered at all — the strongest off, not a disabled row.
        val optedOut = SessionToggleOffer.forDomain("tube.example", SessionOptInState.OPT_OUT)!!
        assertFalse(optedOut.visible)
        assertNull(SessionToggleOffer.forDomain(null, SessionOptInState.OPT_IN))
        assertNull(SessionToggleOffer.forDomain(" ", SessionOptInState.OPT_IN))
        // The policy-level builder returns null exactly when the offer must not render.
        val session = SessionSpec(listOf("api.tube.example"))
        assertNotNull(SessionTogglePolicy.offer(session, "https://tube.example/watch/1", SessionOptInState.UNKNOWN))
        assertNotNull(SessionTogglePolicy.offer(session, "https://tube.example/watch/1", SessionOptInState.OPT_IN))
        assertNull(SessionTogglePolicy.offer(session, "https://tube.example/watch/1", SessionOptInState.OPT_OUT))
        assertNull(SessionTogglePolicy.offer(session, "https://other.example/watch/1", SessionOptInState.OPT_IN))
    }

    @Test fun sessionRulesWithWriteMethodFetchesNeverLoad() {
        // GET-only schema guard (D7): a rule that reuses a login session may not also declare a
        // write-method fetch — the loader rejects the whole rule, session and all.
        listOf("POST", "PUT", "DELETE", "PATCH").forEach { method ->
            val set = RuleSet.parse("""{"version":3,"rules":[
                {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},
                 "actions":[{"type":"manifestHint","kind":"unknown"}],
                 "session":{"hosts":["api.tube.example"]},
                 "fetch":[{"id":"write","method":"$method","url":"https://api.tube.example/x","maxBytes":1024}]}]}""")
            assertEquals(method, 0, set.rules.size)
        }
        // The GET form of the same rule still loads with its session block intact.
        val get = RuleSet.parse("""{"version":3,"rules":[
            {"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},
             "actions":[{"type":"manifestHint","kind":"unknown"}],
             "session":{"hosts":["api.tube.example"]},
             "fetch":[{"id":"read","method":"GET","url":"https://api.tube.example/x","maxBytes":1024}]}]}""")
        assertEquals(1, get.rules.size)
        assertEquals(listOf("api.tube.example"), get.rules.single().session!!.hosts)
    }
}

package com.example.purebrowser.media.rules

import org.junit.Assert.*
import org.junit.Test

/**
 * T83 pure-decision tests for the controlled fetch channel: target whitelist/https/IP-literal
 * checks, per-hop redirect re-validation, the header whitelist (Cookie can never be set), the
 * session-cookie gate (opt-in + same registrable domain), and the merged per-epoch budget ledger.
 * All hosts are generic test fixtures.
 */
class RuleFetchPolicyTest {
    private val hosts = Regex("(^|\\.)tube\\.example$")

    private fun rule(session: SessionSpec? = null): SiteRule {
        val sessionJson = if (session != null) {
            ""","session":{"hosts":${session.hosts.joinToString(",", "[", "]") { """"$it"""" }}}"""
        } else ""
        val text = """{"version":3,"rules":[{"id":"r","version":1,
            "match":{"hosts":"(^|\\.)tube\\.example$"},
            "actions":[{"type":"manifestHint","kind":"unknown"}]$sessionJson}]}"""
        return RuleSet.parse(text).rules.single()
    }

    @Test fun targetRequiresHttpsAndTheRuleHostWhitelist() {
        assertTrue(RuleFetchPolicy.target("https://tube.example/watch/1", hosts).allowed)
        assertTrue(RuleFetchPolicy.target("https://api.tube.example/config.json", hosts).allowed)
        assertEquals(FetchDeny.NOT_HTTPS, RuleFetchPolicy.target("http://tube.example/watch/1", hosts).deny)
        assertEquals(FetchDeny.NOT_HTTPS, RuleFetchPolicy.target("ftp://tube.example/x", hosts).deny)
        assertEquals(FetchDeny.HOST_NOT_WHITELISTED, RuleFetchPolicy.target("https://other.example/watch/1", hosts).deny)
        assertEquals(FetchDeny.HOST_NOT_WHITELISTED, RuleFetchPolicy.target("https://tube.example.evil.example/watch", hosts).deny)
        assertEquals(FetchDeny.HOST_NOT_WHITELISTED, RuleFetchPolicy.target("https://tube.example/x", null).deny)
        assertEquals(FetchDeny.INVALID_URL, RuleFetchPolicy.target("https://user:pass@tube.example/x", hosts).deny)
        assertEquals(FetchDeny.INVALID_URL, RuleFetchPolicy.target("not a url", hosts).deny)
        assertEquals(FetchDeny.INVALID_URL, RuleFetchPolicy.target("", hosts).deny)
    }

    @Test fun ipLiteralHostsAreRefused() {
        assertEquals(FetchDeny.IP_LITERAL, RuleFetchPolicy.target("https://8.8.8.8/config", hosts).deny)
        assertEquals(FetchDeny.IP_LITERAL, RuleFetchPolicy.target("https://192.168.1.5/config", hosts).deny)
        assertEquals(FetchDeny.IP_LITERAL, RuleFetchPolicy.target("https://[2001:db8::1]/config", hosts).deny)
        // Unbracketed IPv6 shapes do not parse as web URLs at all: still refused, never fetched.
        val malformed = RuleFetchPolicy.target("https://2001:db8::1/config", hosts)
        assertFalse(malformed.allowed)
    }

    @Test fun privateLinkLocalAndDotlessHostsAreRefused() {
        val refused = listOf(
            "10.0.0.9", "172.16.0.1", "172.31.255.255", "192.168.0.1",
            "169.254.1.1", "127.0.0.1", "0.0.0.0", "100.64.0.1",
        )
        refused.forEach { host ->
            val decision = RuleFetchPolicy.target("https://$host/x", hosts)
            assertFalse(host, decision.allowed)
            assertTrue(host, decision.deny == FetchDeny.IP_LITERAL || decision.deny == FetchDeny.PRIVATE_OR_LINK_LOCAL)
        }
        assertEquals(FetchDeny.PRIVATE_OR_LINK_LOCAL, RuleFetchPolicy.target("https://localhost/x", hosts).deny)
        assertEquals(FetchDeny.PRIVATE_OR_LINK_LOCAL, RuleFetchPolicy.target("https://intranet/x", hosts).deny)
        // A public, whitelisted subdomain still passes: the family checks do not overreach.
        assertTrue(RuleFetchPolicy.target("https://cdn.tube.example/x", hosts).allowed)
    }

    @Test fun redirectHopsAreRevalidatedPerHop() {
        // Same whitelisted host: followed, with the resolved absolute URL.
        val ok = RuleFetchPolicy.redirectHop("https://tube.example/a", "/b/c?x=1", hosts, credentialUsed = false)
        assertTrue(ok.allowed)
        assertEquals("https://tube.example/b/c?x=1", ok.resolvedUrl)
        // Another host in the whitelist face: still fine without credentials.
        assertTrue(RuleFetchPolicy.redirectHop("https://tube.example/a", "https://api.tube.example/c", hosts, credentialUsed = false).allowed)
        // HTTPS downgrade is refused outright.
        assertEquals(FetchDeny.HTTP_DOWNGRADE, RuleFetchPolicy.redirectHop("https://tube.example/a", "http://tube.example/b", hosts, credentialUsed = false).deny)
        // Cross-origin hop with credentials is refused; same-origin with credentials is fine.
        assertEquals(
            FetchDeny.CROSS_ORIGIN_WITH_CREDENTIALS,
            RuleFetchPolicy.redirectHop("https://tube.example/a", "https://api.tube.example/c", hosts, credentialUsed = true).deny,
        )
        assertTrue(RuleFetchPolicy.redirectHop("https://tube.example/a", "https://tube.example/b", hosts, credentialUsed = true).allowed)
        // A whitelisted first hop cannot launder a second hop to a private or off-face target.
        assertEquals(
            FetchDeny.PRIVATE_OR_LINK_LOCAL,
            RuleFetchPolicy.redirectHop("https://tube.example/a", "https://169.254.169.254/latest/meta-data", hosts, credentialUsed = false).deny,
        )
        assertEquals(
            FetchDeny.HOST_NOT_WHITELISTED,
            RuleFetchPolicy.redirectHop("https://tube.example/a", "https://evil.example/c", hosts, credentialUsed = false).deny,
        )
        assertEquals(FetchDeny.INVALID_URL, RuleFetchPolicy.redirectHop("https://tube.example/a", ":::", hosts, credentialUsed = false).deny)
    }

    @Test fun headerWhitelistCanNeverCarryCredentials() {
        val out = RuleFetchPolicy.headersFor(
            linkedMapOf(
                "User-Agent" to "tester\u0000",
                "Accept" to "application/json",
                "accept-language" to "en",
                "REFERER" to "https://tube.example/",
                "Cookie" to "sid=secret",
                "cookie" to "sid=secret",
                "Authorization" to "Bearer x",
                "Proxy-Authorization" to "Basic x",
                "X-Custom" to "anything",
                "Accept-Encoding" to "gzip",
            ),
        )
        assertEquals(setOf("User-Agent", "Accept", "accept-language", "REFERER"), out.keys)
        assertEquals("tester", out["User-Agent"]) // control characters stripped
        assertTrue(RuleFetchPolicy.isForbiddenHeader("Cookie"))
        assertTrue(RuleFetchPolicy.isForbiddenHeader("authorization"))
        assertTrue(RuleFetchPolicy.isForbiddenHeader("Proxy-Authorization"))
        assertFalse(RuleFetchPolicy.isAllowedHeader("Cookie"))
        assertTrue(RuleFetchPolicy.headersFor(mapOf("Accept" to "   ")).isEmpty())
        assertTrue(RuleFetchPolicy.headersFor(mapOf("User-Agent" to "x".repeat(3000)))["User-Agent"]!!.length <= 1024)
    }

    @Test fun sessionCookieNeedsOptInSessionBlockAndPolicyPass() {
        val provider = { target: String -> "sid=value; len=${target.length}" }
        val withSession = rule(session = SessionSpec(listOf("api.tube.example")))
        val target = "https://api.tube.example/config"
        // Opt-in defaults closed: no cookie ever leaves without it.
        assertNull(RuleFetchPolicy.sessionCookie(withSession, target, optIn = false, cookieFor = provider))
        // Without a session block there is nothing to reuse.
        assertNull(RuleFetchPolicy.sessionCookie(rule(session = null), target, optIn = true, cookieFor = provider))
        // The session must cover the exact target host (or a declared parent domain).
        assertEquals("sid=value; len=${target.length}", RuleFetchPolicy.sessionCookie(withSession, target, optIn = true, cookieFor = provider))
        val subdomain = "https://v2.api.tube.example/c"
        assertEquals("sid=value; len=${subdomain.length}", RuleFetchPolicy.sessionCookie(withSession, subdomain, optIn = true, cookieFor = provider))
        // Not covered: a sibling subdomain and cross-registrable hosts.
        assertNull(RuleFetchPolicy.sessionCookie(withSession, "https://tube.example/config", optIn = true, cookieFor = provider))
        assertNull(RuleFetchPolicy.sessionCookie(withSession, "https://api.other.example/config", optIn = true, cookieFor = provider))
        // A target the fetch policy would refuse never receives a cookie.
        assertNull(RuleFetchPolicy.sessionCookie(withSession, "http://api.tube.example/config", optIn = true, cookieFor = provider))
        // A blank or missing cookie is unusable (existing sessionCookieHeader primitive).
        assertNull(RuleFetchPolicy.sessionCookie(withSession, target, optIn = true, cookieFor = { "" }))
        assertNull(RuleFetchPolicy.sessionCookie(withSession, target, optIn = true, cookieFor = { null }))
    }

    @Test fun budgetLedgerEnforcesAllThreePerActionCaps() {
        val budget = RuleFetchBudget(maxFetchesPerAction = 3, maxTotalBytes = 10, maxWallMs = 1000)
        budget.beginUserAction(1L)
        assertTrue(budget.tryAcquireFetch(1L))
        budget.chargeBytes(1L, 4)
        assertTrue(budget.tryAcquireFetch(1L))
        budget.chargeBytes(1L, 6)
        // Both the byte cap and the fetch-count cap refuse the next acquire.
        assertFalse(budget.tryAcquireFetch(1L))
        // Wall clock: a ledger whose window has expired refuses everything.
        var nanos = 0L
        val wall = RuleFetchBudget(maxWallMs = 50, now = { nanos })
        wall.beginUserAction(2L)
        assertTrue(wall.tryAcquireFetch(2L))
        nanos += 51_000_000L
        assertFalse(wall.tryAcquireFetch(2L))
    }

    @Test fun budgetLedgerSharesTheEpochWithExternalProbeRequests() {
        val budget = RuleFetchBudget(maxFetchesPerAction = 8, maxSharedEpochRequests = 10)
        budget.beginUserAction(7L)
        repeat(6) { budget.noteExternalRequest(7L) } // probe-side accounting for the same epoch
        var acquired = 0
        while (budget.tryAcquireFetch(7L)) acquired++
        assertEquals(4, acquired) // 6 external + 4 rule fetches = the merged ceiling of 10
        // A new epoch rolls the whole ledger: probes and fetches start from zero again.
        budget.beginUserAction(8L)
        assertTrue(budget.tryAcquireFetch(8L))
        assertEquals(1, budget.fetchesUsed(8L))
    }

    @Test fun budgetLedgerDoesNotRechargeWithinOneEpoch() {
        val budget = RuleFetchBudget(maxFetchesPerAction = 2)
        budget.beginUserAction(5L)
        assertTrue(budget.tryAcquireFetch(5L))
        assertTrue(budget.tryAcquireFetch(5L))
        budget.beginUserAction(5L) // a re-settle of the SAME page is not a new user action
        assertFalse(budget.tryAcquireFetch(5L))
        assertEquals(2, budget.fetchesUsed(5L))
    }
}

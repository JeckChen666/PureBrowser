package com.example.purebrowser.media.rules

import com.example.purebrowser.download.RequestPolicy
import com.example.purebrowser.media.verify.ProbePolicy
import java.net.URI
import java.util.Locale

/** Why a controlled rule fetch (or one redirect hop) was denied. Denials skip, they never throw. */
enum class FetchDeny {
    INVALID_URL,
    NOT_HTTPS,
    HTTP_DOWNGRADE,
    HOST_NOT_WHITELISTED,
    IP_LITERAL,
    PRIVATE_OR_LINK_LOCAL,
    CROSS_ORIGIN_WITH_CREDENTIALS,
}

data class FetchDecision(val allowed: Boolean, val deny: FetchDeny? = null, val resolvedUrl: String? = null) {
    companion object {
        internal fun denied(reason: FetchDeny) = FetchDecision(false, reason)
    }
}

/**
 * Pure decision layer for the rules layer's controlled fetch channel (T83). It COMPOSES the
 * existing RequestPolicy primitives — validateUrl, sameOrigin, redirect, sessionCookieHeader —
 * instead of rewriting them, and adds the rules-specific rules on top: the target must be https,
 * must be matched by the rule's hosts face, IP literals and private/link-local hosts are refused,
 * and every redirect hop is re-checked. There is no IO here by construction.
 *
 * Header policy (R4 credential compensation): only User-Agent / Accept / Accept-Language / Referer
 * may ever be set by a rule fetch. Cookie, Authorization and Proxy-Authorization are not on the
 * whitelist and can never pass [headersFor]; a session cookie travels exclusively through
 * [sessionCookie], which additionally requires the rule's session block AND the user's per-site
 * opt-in.
 */
object RuleFetchPolicy {
    /** Exact ASCII-case-insensitive names a rule fetch may carry; nothing else passes. */
    val ALLOWED_HEADERS = setOf("User-Agent", "Accept", "Accept-Language", "Referer")
    private val ALLOWED_LOWER = ALLOWED_HEADERS.map { it.lowercase(Locale.ROOT) }.toSet()
    private val FORBIDDEN_HEADERS = setOf("cookie", "authorization", "proxy-authorization")

    fun isAllowedHeader(name: String): Boolean = name.trim().lowercase(Locale.ROOT) in ALLOWED_LOWER

    fun isForbiddenHeader(name: String): Boolean = name.trim().lowercase(Locale.ROOT) in FORBIDDEN_HEADERS

    /**
     * Decision for one fetch target: https-only (via the existing validateUrl primitive), host
     * matched by the rule's hosts face, no IP literals, no private or link-local hosts, no
     * dotless intranet names.
     */
    fun target(url: String, hostsPattern: Regex?): FetchDecision {
        if (url.isEmpty() || url.length > 8192 || url.any { it.isWhitespace() || it.code < 0x20 }) {
            return FetchDecision.denied(FetchDeny.INVALID_URL)
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return FetchDecision.denied(FetchDeny.INVALID_URL)
        if (uri.rawUserInfo != null) return FetchDecision.denied(FetchDeny.INVALID_URL)
        // Existing primitive: https scheme, origin shape, sane port. Its failure text is UNSUPPORTED;
        // for the rules channel that means exactly "not https / not fetchable shape".
        runCatching { RequestPolicy.validateUrl(url, allowLocalHttp = false) }
            .getOrElse { return FetchDecision.denied(FetchDeny.NOT_HTTPS) }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return FetchDecision.denied(FetchDeny.INVALID_URL)
        // Denial-reason precision: the link-local / on-host family (169.254/16, localhost, dotless
        // intranet names) reports PRIVATE_OR_LINK_LOCAL; every other IPv4/IPv6 literal — private
        // ranges included — reports IP_LITERAL. Both reasons refuse the fetch identically.
        when {
            isIpLiteral(host) && isLinkLocal(host) -> return FetchDecision.denied(FetchDeny.PRIVATE_OR_LINK_LOCAL)
            isIpLiteral(host) -> return FetchDecision.denied(FetchDeny.IP_LITERAL)
            isPrivateOrLinkLocal(host) -> return FetchDecision.denied(FetchDeny.PRIVATE_OR_LINK_LOCAL)
        }
        if (hostsPattern == null || !runCatching { hostsPattern.containsMatchIn(host) }.getOrDefault(false)) {
            return FetchDecision.denied(FetchDeny.HOST_NOT_WHITELISTED)
        }
        return FetchDecision(true, resolvedUrl = url)
    }

    /**
     * Decision for one redirect hop: [location] resolved against [current] (relative forms
     * included), the existing RequestPolicy.redirect primitive applied (https-downgrade and
     * cross-origin-with-credentials refusals), then the full [target] re-check on the next URL —
     * whitelisting is per hop, never granted once for the chain.
     */
    fun redirectHop(current: String, location: String, hostsPattern: Regex?, credentialUsed: Boolean): FetchDecision {
        val next = runCatching { URI(current).resolve(location).toString() }.getOrNull()
            ?: return FetchDecision.denied(FetchDeny.INVALID_URL)
        if (credentialUsed && !RequestPolicy.sameOrigin(current, next)) {
            return FetchDecision.denied(FetchDeny.CROSS_ORIGIN_WITH_CREDENTIALS)
        }
        // Existing primitive owns the downgrade and shape rules; classify its refusal precisely.
        runCatching { RequestPolicy.redirect(current, location, credentialUsed, allowLocalHttp = false) }
            .getOrElse {
                val downgrade = runCatching { URI(next).scheme?.lowercase(Locale.ROOT) == "http" }.getOrDefault(false) &&
                    runCatching { URI(current).scheme?.lowercase(Locale.ROOT) == "https" }.getOrDefault(false)
                return if (downgrade) FetchDecision.denied(FetchDeny.HTTP_DOWNGRADE) else FetchDecision.denied(FetchDeny.INVALID_URL)
            }
        return target(next, hostsPattern)
    }

    /**
     * Filters a caller-proposed header set down to the whitelist, stripping control characters and
     * bounding values. Forbidden names (Cookie/Authorization/Proxy-Authorization) can never pass;
     * a session cookie must go through [sessionCookie] instead.
     */
    fun headersFor(candidate: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((rawName, rawValue) in candidate) {
            val name = rawName.trim()
            if (!isAllowedHeader(name)) continue
            val value = rawValue.filterNot { it.isISOControl() }.trim().take(1024)
            if (value.isNotEmpty()) out[name] = value
        }
        return out
    }

    /** True when a declared session host covers [host]: exact match or a subdomain of it. */
    fun sessionCoversHost(session: SessionSpec, host: String): Boolean =
        session.hosts.any { it == host || host.endsWith(".$it") }

    /**
     * Session-channel cookie for [targetUrl]: only when the user opted in for this rule site, the
     * rule declares a session block covering the target host, and the target itself passes the
     * fetch policy. The cookie value is sanitized through the existing sessionCookieHeader
     * primitive; null means "no session for this fetch" and the request goes out anonymously.
     */
    fun sessionCookie(rule: SiteRule, targetUrl: String, optIn: Boolean, cookieFor: (String) -> String?): String? {
        if (!optIn) return null
        val session = rule.session ?: return null
        val host = runCatching { URI(targetUrl).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return null
        if (!sessionCoversHost(session, host)) return null
        if (!target(targetUrl, rule.match.hostsPattern).allowed) return null
        return RequestPolicy.sessionCookieHeader(cookieFor(targetUrl))
    }

    /** IPv4 quad or anything IPv6-shaped (brackets or colons). */
    internal fun isIpLiteral(host: String): Boolean {
        if (host.startsWith("[") || host.contains(':')) return true
        if (!host.contains('.')) return false
        val labels = host.split('.')
        if (labels.size != 4 || labels.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return false
        return true
    }

    /** Private, loopback, link-local, ISP-CGN and dotless intranet names never receive rule fetches.
     * IPv6-shaped hosts are NOT private by this check — they are IP literals and denied as such. */
    internal fun isPrivateOrLinkLocal(host: String): Boolean {
        if (host.contains(':') || host.startsWith("[")) return false
        if (host == "localhost" || !host.contains('.')) return true
        val octets = ipv4Octets(host) ?: return false
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 169 && b == 254 -> true // link-local
            a == 172 && b in 16..31 -> true // private
            a == 192 && b == 168 -> true // private
            a == 100 && b in 64..127 -> true // carrier-grade NAT
            else -> false
        }
    }

    /** The link-local family that keeps the PRIVATE_OR_LINK_LOCAL denial even as an IP literal. */
    internal fun isLinkLocal(host: String): Boolean {
        val octets = ipv4Octets(host) ?: return host == "localhost"
        return octets[0] == 169 && octets[1] == 254 || host == "localhost"
    }

    private fun ipv4Octets(host: String): IntArray? {
        val labels = host.split('.')
        if (labels.size != 4 || labels.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return null
        return labels.map { it.toIntOrNull() ?: return null }.toIntArray()
    }
}

/**
 * Per-user-action budget ledger for the rule fetch channel (T83, R4 "有界" compensation): at most
 * [maxFetchesPerAction] fetches, [maxTotalBytes] of response bytes and [maxWallMs] of wall clock
 * per user action, plus a MERGED per-epoch request ceiling shared with the probe queue (v0.1.8 §8
 * 合并账本: rule fetches and AutoProbeQueue probes count against one ledger instead of stacking two
 * independent request caps).
 *
 * Integration points, deliberately left as pure counters with no dependency on the queue:
 *  - the coordinator calls [beginUserAction] once per epoch before each evaluation run and
 *    [tryAcquireFetch]/[chargeBytes] around every fetch (already wired in SiteRulesCoordinator);
 *  - AutoProbeQueue's worker calls [noteExternalRequest] once per issued probe request. That hop
 *    is NOT wired here — media/verify is outside this change set — so the orchestrator should
 *    construct ONE ledger in the session layer and hand the same instance to both the coordinator
 *    and the probe queue's onResult-adjacent accounting (see the T83 note in the run report).
 */
class RuleFetchBudget(
    val maxFetchesPerAction: Int = MAX_FETCHES_PER_USER_ACTION,
    val maxTotalBytes: Long = MAX_TOTAL_BYTES_PER_USER_ACTION,
    val maxWallMs: Long = MAX_WALL_MS_PER_USER_ACTION,
    val maxSharedEpochRequests: Int = MAX_SHARED_EPOCH_REQUESTS,
    private val now: () -> Long = System::nanoTime,
) {
    companion object {
        const val MAX_FETCHES_PER_USER_ACTION = 8
        const val MAX_TOTAL_BYTES_PER_USER_ACTION = 1L shl 20 // 1 MiB
        const val MAX_WALL_MS_PER_USER_ACTION = 15_000L
        /**
         * Probe side can legally issue up to ProbePolicy.MAX_PROBES_PER_EPOCH plus
         * MAX_PLAYLIST_FETCHES_PER_EPOCH requests per epoch; the rule side adds at most
         * MAX_FETCHES_PER_USER_ACTION. The merged ceiling is that sum, so enabling the shared
         * ledger never removes capacity the two sides already had.
         */
        const val MAX_SHARED_EPOCH_REQUESTS =
            ProbePolicy.MAX_PROBES_PER_EPOCH + ProbePolicy.MAX_PLAYLIST_FETCHES_PER_EPOCH + MAX_FETCHES_PER_USER_ACTION
    }

    private val guard = Any()
    private var epoch = Long.MIN_VALUE
    private var fetches = 0
    private var externalRequests = 0
    private var bytes = 0L
    private var startNs = 0L

    /** Opens (or continues) the accounting window for one page epoch; a new epoch resets everything. */
    fun beginUserAction(epoch: Long) {
        synchronized(guard) {
            if (rollIfNeeded(epoch)) startNs = now()
        }
    }

    /** Probe-side hook: one request issued by the shared (AutoProbeQueue) channel for [epoch]. */
    fun noteExternalRequest(epoch: Long) {
        synchronized(guard) { rollIfNeeded(epoch); externalRequests++ }
    }

    /**
     * Reserves one rule fetch; false means the per-action or shared-epoch budget is exhausted and
     * the caller must downgrade (skip the rule's fetch actions) rather than retry.
     */
    fun tryAcquireFetch(epoch: Long): Boolean = synchronized(guard) {
        rollIfNeeded(epoch)
        val withinFetches = fetches < maxFetchesPerAction
        val withinBytes = bytes < maxTotalBytes
        val withinWall = (now() - startNs) / 1_000_000L < maxWallMs
        val withinShared = (fetches + externalRequests) < maxSharedEpochRequests
        if (withinFetches && withinBytes && withinWall && withinShared) {
            fetches++
            true
        } else false
    }

    /** Post-charges the response bytes actually read for a fetch of this epoch. */
    fun chargeBytes(epoch: Long, count: Long) {
        if (count <= 0) return
        synchronized(guard) { rollIfNeeded(epoch); bytes += count }
    }

    /** Observability (R4 "可见"): how many rule fetches this epoch's user action has spent. */
    fun fetchesUsed(epoch: Long): Int = synchronized(guard) { rollIfNeeded(epoch); fetches }

    private fun rollIfNeeded(epoch: Long): Boolean {
        if (this.epoch == epoch) return false
        this.epoch = epoch
        fetches = 0
        externalRequests = 0
        bytes = 0L
        startNs = now()
        return true
    }
}

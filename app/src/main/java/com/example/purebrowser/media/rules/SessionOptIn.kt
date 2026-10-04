package com.example.purebrowser.media.rules

import java.net.URI
import java.util.Locale

/**
 * Per-site login-session opt-in (T86, decision D6: UI 默认关闭、用户逐站开启). The store is keyed by
 * REGISTRABLE DOMAIN, never by host or URL, so one decision covers exactly the eTLD+1 the user was
 * looking at and can never leak into a sibling registrable domain. The default is [SessionOptInState.UNKNOWN],
 * which is equivalent to OFF: until the user flips a visible toggle, every rule fetch stays anonymous
 * (v0.1.7 behavior). Values never leave the device and never travel with a rule.
 */
enum class SessionOptInState { UNKNOWN, OPT_IN, OPT_OUT }

/**
 * Pure decision core over an injected key-value backend; the Android side is a thin
 * SharedPreferences adapter (see [SessionOptInPreferences]) and tests use a plain map. All safety
 * decisions that matter — default-off, registrable-domain granularity, opt-out wins over rule
 * declarations — live here, not in the adapter.
 */
class SessionOptIn(
    private val read: (String) -> SessionOptInState? = { null },
    private val write: (String, SessionOptInState) -> Unit = { _, _ -> },
) {
    /** State for one registrable domain; null/blank/unknown keys stay UNKNOWN (≡ off). */
    fun stateFor(domain: String?): SessionOptInState =
        domain?.takeIf { it.isNotBlank() }?.let(read) ?: SessionOptInState.UNKNOWN

    /** Effective opt-in for a page URL: true only on an explicit, persisted OPT_IN. */
    fun isOptedIn(pageUrl: String): Boolean = stateFor(domainKey(pageUrl)) == SessionOptInState.OPT_IN

    /** Records the user's toggle choice for the page's registrable domain; false when the URL has no key. */
    fun setFromPage(pageUrl: String, state: SessionOptInState): Boolean {
        val key = domainKey(pageUrl) ?: return false
        write(key, state)
        return true
    }

    /** Same write for a registrable domain the UI already resolved (e.g. the matched-rule offer). */
    fun setForDomain(domain: String, state: SessionOptInState) {
        domain.takeIf { it.isNotBlank() }?.let { write(it, state) }
    }

    companion object {
        /**
         * Registrable domain of a page URL — the store's only key shape. Anything that is not a
         * plain host under a registrable candidate (IP literals, dotless names, non-web schemes)
         * yields null and therefore can never be opted in.
         */
        fun domainKey(pageUrl: String): String? = runCatching {
            URI(pageUrl).host?.lowercase(Locale.ROOT)
        }.getOrNull()?.let { RegistrableDomains.registrableDomain(it) }

        /** Round-trip strings for the thin storage adapter; anything else decodes to null (UNKNOWN). */
        fun encode(state: SessionOptInState): String = when (state) {
            SessionOptInState.OPT_IN -> "in"
            SessionOptInState.OPT_OUT -> "out"
            SessionOptInState.UNKNOWN -> "unknown"
        }

        fun decode(raw: String?): SessionOptInState? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "in" -> SessionOptInState.OPT_IN
            "out" -> SessionOptInState.OPT_OUT
            else -> null
        }
    }
}

/**
 * What a confirmation surface needs to render the T86 toggle, as pure data: the registrable domain
 * being granted and the user's current state. A rule DECLARING a session block is never enough —
 * the offer exists only while the declaration actually covers the page's own registrable domain
 * (load-time validation already keeps session hosts inside the match face's registrable domains,
 * but a face like `tube\.(com|net)` spans two; this closes that gap at the surface).
 */
data class SessionToggleOffer(val domain: String, val state: SessionOptInState) {
    /** OPT_OUT removes the toggle entirely — the strongest off, not a disabled row. */
    val visible: Boolean get() = state != SessionOptInState.OPT_OUT

    /** Default position: ON only on an explicit prior OPT_IN; UNKNOWN starts OFF with an explanation. */
    val checkedByDefault: Boolean get() = state == SessionOptInState.OPT_IN

    companion object {
        fun forDomain(domain: String?, state: SessionOptInState): SessionToggleOffer? {
            val key = domain?.takeIf { it.isNotBlank() } ?: return null
            return SessionToggleOffer(key, state)
        }
    }
}

/** Pure UI-level gating decisions for the session toggle; no store, no rule loading, no IO. */
object SessionTogglePolicy {
    /**
     * The registrable domain a declared [session] block would act on for [pageUrl], or null when the
     * rule declares no session, the page has no registrable domain, or the session hosts live in a
     * DIFFERENT registrable domain than the page (cross-domain offers never render). This is the
     * UI-level sibling of the loader's containment check and of the coordinator's host-coverage check.
     */
    fun coveredDomain(session: SessionSpec?, pageUrl: String): String? {
        if (session == null || session.hosts.isEmpty()) return null
        val pageDomain = SessionOptIn.domainKey(pageUrl) ?: return null
        return session.hosts
            .mapNotNull { RegistrableDomains.registrableDomain(it) }
            .firstOrNull { it == pageDomain }
            ?: return null
    }

    /** Builds the offer for a confirmation surface; null when it must not render. */
    fun offer(session: SessionSpec?, pageUrl: String, state: SessionOptInState): SessionToggleOffer? {
        val offer = SessionToggleOffer.forDomain(coveredDomain(session, pageUrl), state) ?: return null
        return if (offer.visible) offer else null
    }
}

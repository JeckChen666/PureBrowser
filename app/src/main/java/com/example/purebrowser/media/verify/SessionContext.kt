package com.example.purebrowser.media.verify

/**
 * Caller-supplied page context for background auto-verification. Never persisted, never logged;
 * cookies are only read through [cookie] and redacted from every diagnostic output.
 */
class SessionContext(
    val pageUrl: String,
    val userAgent: String?,
    private val cookieFor: (String) -> String?,
) {
    /** Reader failures never break probing; callers decide eligibility by returning null. */
    fun cookie(target: String): String? =
        runCatching { cookieFor(target) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun toString() = "SessionContext(pageUrl=<redacted>, userAgent=<redacted>, cookie=<redacted>)"
}

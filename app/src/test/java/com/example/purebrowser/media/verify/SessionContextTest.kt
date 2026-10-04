package com.example.purebrowser.media.verify

import org.junit.Assert.*
import org.junit.Test

class SessionContextTest {
    private val secretCookie = "sid=secret-token; token=hush"
    private fun context(throwing: Boolean = false) = SessionContext(
        "https://cdn.example/watch?auth=secret",
        "UnitUA/1.0 (secret-build)",
    ) { if (throwing) error("cookie reader failed") else secretCookie }

    @Test fun cookieIsReadableForEligibleTargetsOnly() {
        assertEquals(secretCookie, context().cookie("https://cdn.example/hls/master.m3u8"))
    }

    @Test fun readerFailuresAndBlankValuesNeverBreakProbing() {
        assertNull(context(throwing = true).cookie("https://cdn.example/x"))
        assertNull(SessionContext("https://cdn.example/watch", null) { " " }.cookie("https://cdn.example/x"))
    }

    @Test fun toStringRedactsPageUrlUserAgentAndCookies() {
        val text = context().toString()
        listOf("secret", "hush", "cdn.example", "UnitUA", "watch").forEach {
            assertFalse(text.contains(it))
        }
        assertTrue(text.contains("SessionContext"))
    }
}

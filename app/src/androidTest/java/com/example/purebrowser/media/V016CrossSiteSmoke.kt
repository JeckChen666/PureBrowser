package com.example.purebrowser.media

import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.example.purebrowser.browser.BrowserSession
import com.example.purebrowser.data.browser.TabRecord
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * v0.1.6 T61 evidence smoke: drives the production sniffing stack (BrowserSession) against a
 * caller-supplied page via instrumentation args `url`/`label`/`timeoutMs`. With no `url` arg the
 * test is a no-op, so normal CI runs never touch external sites. Output is host-masked so raw
 * evidence stays diagnosable without printing page identities.
 */
class V016CrossSiteSmoke {
    private fun mask(url: String): String {
        val host = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1)
        return if (host == null) "<no-host>" else url.replace(host, hostHash(host))
    }
    private fun hostHash(host: String): String =
        MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    @Test fun crossSiteEvidence() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val url = InstrumentationRegistry.getArguments().getString("url") ?: return
        val label = InstrumentationRegistry.getArguments().getString("label") ?: "site"
        val timeoutMs = InstrumentationRegistry.getArguments().getString("timeoutMs")?.toLongOrNull() ?: 45_000L

        // Optional caller-supplied first-party cookies ("n1=v1; n2=v2") applied to the target host.
        InstrumentationRegistry.getArguments().getString("cookie")?.takeIf { it.isNotBlank() }?.let { raw ->
            val hostOf = Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: return@let
            raw.split(";").map { it.trim() }.filter { it.contains('=') }.forEach {
                android.webkit.CookieManager.getInstance().setCookie("https://$hostOf", it)
            }
        }

        val session = BrowserSession(
            recordId = "v016-smoke-$label", appContext = instrument.targetContext,
            record = TabRecord(id = "v016-smoke", url = url),
            message = {}, changed = {}, visited = { _, _ -> }, link = {},
        )
        val host = AtomicReference<String>()
        try {
            InstrumentationRegistry.getArguments().getString("ua")?.takeIf { it.isNotBlank() }?.let { ua ->
                session.engine.forcedUserAgent = ua
            }
            instrument.runOnMainSync {
                session.mount(FrameLayout(instrument.targetContext))
                host.set(session.engine.page.value.url)
            }
            val startLoad = System.currentTimeMillis()
            while (System.currentTimeMillis() - startLoad < 30_000) {
                val page = session.engine.page.value
                if (page.error != null) break
                // BrowserPage defaults to progress=100 before the first onPageStarted, so require a floor.
                if (System.currentTimeMillis() - startLoad > 5_000 && page.progress == 100) break
                Thread.sleep(200)
            }
            val page = session.engine.page.value
            println("SMOKE[$label] pageState=loaded=${page.error == null} err=${page.error ?: "none"} host=${hostHash(Regex("^https?://([^/]+)").find(url)?.groupValues?.get(1) ?: "?")}")

            val deadline = System.currentTimeMillis() + timeoutMs
            var candidates = session.sniffer.candidates.value
            while (System.currentTimeMillis() < deadline) {
                candidates = session.sniffer.candidates.value
                val addressable = candidates.filter { it.kind in setOf(MediaKind.FILE, MediaKind.HLS, MediaKind.DASH) }
                val settled = addressable.isNotEmpty() &&
                    addressable.all { it.probeState == ProbeState.VERIFIED || it.probeState == ProbeState.FAILED }
                if (settled && page.error == null) break
                Thread.sleep(500)
            }

            val media = candidates.filter { it.kind != MediaKind.LOCAL }
            println("SMOKE[$label] total=${candidates.size} media=${media.size} " +
                "byKind=${MediaKind.values().associateWith { k -> media.count { it.kind == k } }.filterValues { it > 0 }} " +
                "byProbe=${ProbeState.values().associateWith { p -> media.count { it.probeState == p } }.filterValues { it > 0 }}")
            media.sortedByDescending { it.probeState == ProbeState.VERIFIED }.take(6).forEach { c ->
                val title = if (c.title != null && Regex("^\\d{2,4}[pi]?$").matches(c.title!!)) "q=${c.title}" else "titleLen=${c.title?.length ?: 0}"
                println("SMOKE[$label] cand kind=${c.kind} probe=${c.probeState} mime=${c.verifiedMime ?: c.mimeType ?: "-"} " +
                    "bytes=${c.totalBytes ?: -1} resume=${c.resumable} variants=${c.variants?.size ?: 0} " +
                    "firstVarH=${c.variants?.firstOrNull()?.height ?: -1} $title sources=${c.sources.map { it.name }.toSet()} url=${mask(c.url)}")
            }

            assumeTrue("page did not load: ${page.error ?: "timeout"}", page.error == null)
            assertTrue("no media candidate observed on $label", media.isNotEmpty())
            assertTrue("no addressable FILE/HLS/DASH candidate on $label",
                media.any { it.kind in setOf(MediaKind.FILE, MediaKind.HLS, MediaKind.DASH) })
        } finally {
            instrument.runOnMainSync { session.destroy() }
        }
    }
}

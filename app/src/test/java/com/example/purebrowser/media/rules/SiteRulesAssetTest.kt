package com.example.purebrowser.media.rules

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Guards the shipped rule data against credential-type leftovers from the reverse-engineered
 * material (T71 red lines): no header/token capture markers and no entries for sites that need
 * credentials or already have a dedicated adapter. Test fixtures use generic hosts only.
 */
class SiteRulesAssetTest {
    private fun assetText(): String? = listOf(
        File("src/main/assets/rules/site-rules.json"),
        File("app/src/main/assets/rules/site-rules.json"),
    ).firstOrNull { it.isFile }?.readText()

    @Test fun shippedRulesParseAndStayWithinCaps() {
        val text = assetText()
        assertNotNull("site-rules.json not found from the test working directory", text)
        val set = RuleSet.parse(text!!)
        assertTrue("expected at least 8 rules, got ${set.rules.size}", set.rules.size in 8..RuleSet.MAX_RULES)
        assertTrue(set.version >= 1)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= RuleSet.MAX_FILE_BYTES)
        assertTrue(set.rules.all { it.actions.isNotEmpty() && !it.note.isNullOrBlank() })
        assertTrue(set.rules.all { it.match.hosts != null || it.match.path != null })
    }

    @Test fun everyActionStaysWithinTheEnumeratedWhitelist() {
        val set = RuleSet.parse(assetText()!!)
        set.rules.flatMap { it.actions }.forEach { action ->
            assertTrue(
                action is RuleAction.DomExtract || action is RuleAction.PlayerConfigExtract ||
                    action is RuleAction.ManifestHint || action is RuleAction.JsonExtract ||
                    action is RuleAction.RegexExtract || action is RuleAction.OgMeta || action is RuleAction.ParseManifest,
            )
        }
    }

    /**
     * T89 schema-v3 target rules: the four public-site entries (generic-host fixtures mirror their
     * shapes in [SiteRulesV3TargetsTest]) must be present, fetch-bearing, load-time valid and
     * session-free — the loader already enforces hosts/https/maxBytes, so their survival through
     * [RuleSet.parse] is itself the load-time validation proof.
     */
    @Test fun v3TargetRulesPresentFetchBearingAndSessionFree() {
        val set = RuleSet.parse(assetText()!!)
        assertEquals("document must declare schema v3", 3, set.version)
        val expected = listOf("archive-org-embed", "ted-talks-inline", "commons-file-videoinfo", "dailymotion-metadata", "peertube-blender-files", "peertube-framatube-files")
        val targetRules = expected.map { id ->
            val rule = set.rules.firstOrNull { it.id == id }
            assertNotNull("missing v3 target rule $id", rule)
            rule!!
        }
        targetRules.forEach { rule ->
            assertNotNull("rule ${rule.id} must carry a hosts face (fetch whitelist)", rule.match.hosts)
            assertNull("rule ${rule.id} must not declare a login session", rule.session)
        }
        // Fetch-bearing rules: https GET templates with bounded maxBytes and no redirects assumed.
        set.rules.filter { it.id in expected }.forEach { rule ->
            rule.fetch.forEach { spec ->
                assertEquals("GET", spec.method)
                assertTrue(spec.urlTemplate.startsWith("https://"))
                assertTrue("maxBytes must be bounded", spec.maxBytes in 1..RuleSet.MAX_FETCH_BYTES)
                assertEquals(rule.id, spec.ruleId)
            }
        }
        assertTrue("archive rule must be fetch-bearing", set.byId("archive-org-embed")!!.fetch.isNotEmpty())
        assertTrue("commons rule must be fetch-bearing", set.byId("commons-file-videoinfo")!!.fetch.isNotEmpty())
        assertTrue("dailymotion rule must be fetch-bearing", set.byId("dailymotion-metadata")!!.fetch.isNotEmpty())
        // The TED rule is inline-only: no fetch declaration, extraction over harvested page data.
        assertTrue("ted rule stays inline-only", set.byId("ted-talks-inline")!!.fetch.isEmpty())
    }

    @Test fun materialDerivedSetContainsNoCredentialOrExcludedEntries() {
        val text = assetText()!!.lowercase()
        // Credential-capture capabilities and the material's header/secret extraction sites.
        listOf(
            "authorization", "csrf", "guest-token", "x-ig-app-id", "asbd", "x-fc2",
            "cookie", "password", "credential", "token", "secret",
        ).forEach { marker ->
            assertFalse("forbidden credential marker in shipped rules: $marker", text.contains(marker))
        }
        // Sites excluded at transcription time: credential capture or a dedicated adapter.
        listOf("youtube", "youtu\\.be", "instagram", "twitter", "fc2\\.com", "graphql").forEach { pattern ->
            assertFalse("excluded site marker in shipped rules: $pattern", Regex(pattern).containsMatchIn(text))
        }
        // X the service: match x.com as a host boundary, not as a suffix of an unrelated word.
        assertFalse(Regex("(^|[^a-z0-9])x\\.com").containsMatchIn(text))
    }
}

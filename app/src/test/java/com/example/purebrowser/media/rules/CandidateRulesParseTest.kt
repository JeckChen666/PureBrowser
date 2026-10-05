package com.example.purebrowser.media.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T100 deterministic pass: every rule the T99 transcriber emitted into
 * `tools/rules-transcriber/output/candidates.json` must load under the REAL production loader
 * ([RuleSet.parse]) with its fetch declarations intact. The resource file is generated verbatim
 * from the candidate pool (all entries, needs_review or not) so the batch review's "loads under
 * the app's real validators" gate is executable and repeatable on the JVM.
 *
 * Live behavioral verification (page match, fetch reachability, extraction) is deliberately NOT
 * asserted here — that is the emulator batch recorded in docs/V0.1.9-T100-RULES-EVIDENCE.md.
 */
class CandidateRulesParseTest {
    private fun candidatesText(): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("rules/candidates-v3.json")
        assertNotNull("rules/candidates-v3.json missing from test resources", stream)
        return stream!!.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test fun allTranscribedCandidatesLoadUnderTheRealValidator() {
        val set = RuleSet.parse(candidatesText())
        assertEquals("candidate document must declare schema v3", 3, set.version)
        assertEquals(
            "every emitted candidate must survive the production loader (64 transcribed)",
            64,
            set.rules.size,
        )
        set.rules.forEach { rule ->
            assertTrue("rule ${rule.id} lost its actions", rule.actions.isNotEmpty())
            assertTrue("rule ${rule.id} must carry a hosts face", rule.match.hosts != null)
        }
    }

    @Test fun everyFetchBearingCandidateKeepsItsDeclaration() {
        val set = RuleSet.parse(candidatesText())
        val fetchRules = set.rules.filter { it.fetch.isNotEmpty() }
        assertTrue("expected the transcribed batch to be fetch-bearing", fetchRules.size >= 50)
        fetchRules.forEach { rule ->
            rule.fetch.forEach { spec ->
                assertEquals("GET", spec.method)
                assertTrue("rule ${rule.id} fetch template must stay https", spec.urlTemplate.startsWith("https://"))
                assertTrue("rule ${rule.id} maxBytes must stay bounded", spec.maxBytes in 1..RuleSet.MAX_FETCH_BYTES)
                assertEquals("rule ${rule.id} fetch spec lost its owner", rule.id, spec.ruleId)
            }
        }
    }

    /**
     * The two extraction families the batch uses (jsonExtract / regexExtract) must survive the
     * load-time safety policies verbatim: pointer grammar caps and the RegexGuard shape check.
     */
    @Test fun extractionActionsKeepTheirDeclaredShape() {
        val set = RuleSet.parse(candidatesText())
        val json = set.rules.flatMap { it.actions }.filterIsInstance<RuleAction.JsonExtract>()
        val regex = set.rules.flatMap { it.actions }.filterIsInstance<RuleAction.RegexExtract>()
        assertTrue("expected jsonExtract candidates", json.isNotEmpty())
        assertTrue("expected regexExtract candidates", regex.isNotEmpty())
        json.forEach { action ->
            assertTrue(action.pointers.isNotEmpty())
            assertTrue(action.pointers.size <= RuleSet.MAX_JSON_POINTERS)
            action.pointers.forEach { assertTrue("pointer $it failed the grammar", JsonPointerPolicy.isValid(it)) }
        }
        regex.forEach { action -> assertTrue(RegexGuard.isSafe(action.pattern)) }
    }
}

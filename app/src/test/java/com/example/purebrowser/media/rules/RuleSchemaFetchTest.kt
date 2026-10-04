package com.example.purebrowser.media.rules

import org.junit.Assert.*
import org.junit.Test

/**
 * T82 schema matrix for the v3 fetch/session/extract tier: fetch declaration validation, named
 * group + template placeholder binding, session registrable-domain containment (including the
 * co.uk/gov.cn suffix table), the raised caps with the global compiled-regex ceiling, and the
 * version gate that keeps schema ≤ 2 documents byte-for-byte v0.1.7.
 */
class RuleSchemaFetchTest {
    private fun v3(rules: String) = """{"version":3,"rules":[$rules]}"""
    private fun v2(rules: String) = """{"version":2,"rules":[$rules]}"""

    private fun base(extras: String = "", actions: String = """[{"type":"manifestHint","kind":"unknown"}]""") =
        """{"id":"r","version":1,"match":{"hosts":"(^|\\.)tube\\.example$","path":"\\/watch\\/(?<vid>[0-9]+)"},"actions":$actions$extras}"""

    @Test fun fetchSpecParsesWithNamedGroupBoundTemplate() {
        val set = RuleSet.parse(v3(base(
            ""","fetch":[{"id":"cfg","url":"https://api.tube.example/v/{{match.vid}}?src={{pageUrl}}","maxBytes":4096}]""",
        )))
        val rule = set.rules.single()
        val spec = rule.fetch.single()
        assertEquals("cfg", spec.id)
        assertEquals("GET", spec.method)
        assertEquals("https://api.tube.example/v/{{match.vid}}?src={{pageUrl}}", spec.urlTemplate)
        assertEquals(4096, spec.maxBytes)
        assertEquals("r", spec.ruleId)
        assertNull(rule.session)
    }

    @Test fun missingOrBadMaxBytesRejectsTheRule() {
        listOf(
            """{"id":"cfg","url":"https://api.tube.example/a","maxBytes":0}""",
            """{"id":"cfg","url":"https://api.tube.example/a","maxBytes":-1}""",
            """{"id":"cfg","url":"https://api.tube.example/a","maxBytes":262145}""",
            """{"id":"cfg","url":"https://api.tube.example/a"}""",
            """{"id":"cfg","url":"https://api.tube.example/a","maxBytes":"4096"}""",
        ).forEach { fetch ->
            assertEquals(fetch, 0, RuleSet.parse(v3(base(""","fetch":[$fetch]"""))).rules.size)
        }
        // Boundary values are legal: 1 and the 256 KiB ceiling.
        listOf(1, 262_144).forEach { maxBytes ->
            val set = RuleSet.parse(v3(base(""","fetch":[{"id":"cfg","url":"https://api.tube.example/a","maxBytes":$maxBytes}]""")))
            assertEquals(maxBytes, set.rules.single().fetch.single().maxBytes)
        }
    }

    @Test fun nonGetMethodRejectsTheRule() {
        listOf("POST", "post", "PUT", "HEAD", "DELETE").forEach { method ->
            val set = RuleSet.parse(v3(base(""","fetch":[{"id":"cfg","method":"$method","url":"https://api.tube.example/a","maxBytes":1024}]""")))
            assertEquals(method, 0, set.rules.size)
        }
        // GET (either case) and an omitted method both stay GET-only legal.
        listOf("\"GET\"", "\"get\"", "null").forEach { method ->
            val set = RuleSet.parse(v3(base(""","fetch":[{"id":"cfg","method":$method,"url":"https://api.tube.example/a","maxBytes":1024}]""")))
            assertEquals("GET", set.rules.single().fetch.single().method)
        }
    }

    @Test fun fetchWithoutHostsFaceRejectsTheRule() {
        val pathOnly = """{"id":"p","version":1,"match":{"path":"\\/watch\\/[0-9]+"},
            "actions":[{"type":"jsonExtract","pointers":["files"]}],
            "fetch":[{"id":"cfg","url":"https://a.example/x","maxBytes":1024}]}"""
        assertEquals(0, RuleSet.parse(v3(pathOnly)).rules.size)
    }

    @Test fun templateMustRenderHttps() {
        listOf(
            "http://api.tube.example/v",
            "//api.tube.example/v",
            "{{pageUrl}}/api",
            "",
        ).forEach { url ->
            assertEquals(url, 0, RuleSet.parse(v3(base(""","fetch":[{"id":"cfg","url":"$url","maxBytes":1024}]"""))).rules.size)
        }
    }

    @Test fun templatePlaceholderWhitelistRejectsUnknownTokens() {
        val bad = listOf(
            "{{page}}",            // not pageUrl
            "{{pageUrlL}}",
            "{{m0}}",              // positional range is m1..m9
            "{{m10}}",
            "{{match}}",           // needs a group name
            "{{match.vid.x}}",     // dotted names are not declared groups
            "{{other.vid}}",       // only match.* binds
            "{{ vid }}",           // whitespace is not a valid token
            "{{env.HOME}}",
            "{{pageUrl|upper}}",   // no expressions
        )
        for (token in bad) {
            val template = "https://api.tube.example/v/$token"
            val set = RuleSet.parse(v3(base(""","fetch":[{"id":"cfg","url":"$template","maxBytes":1024}]""")))
            assertEquals(token, 0, set.rules.size)
        }
    }

    @Test fun positionalAndCrossGroupPlaceholdersValidateAgainstThePathFace() {
        // One named group only: {{m1}} is that group, {{m2}} does not exist yet.
        val one = """{"id":"cfg","url":"https://api.tube.example/a/{{m1}}","maxBytes":1024}"""
        assertEquals(1, RuleSet.parse(v3(base(""","fetch":[$one]"""))).rules.size)
        val two = """{"id":"cfg","url":"https://api.tube.example/a/{{m2}}","maxBytes":1024}"""
        assertEquals(0, RuleSet.parse(v3(base(""","fetch":[$two]"""))).rules.size)
        // A named placeholder must be a group the path face declares.
        val unknownName = """{"id":"cfg","url":"https://api.tube.example/a/{{match.other}}","maxBytes":1024}"""
        assertEquals(0, RuleSet.parse(v3(base(""","fetch":[$unknownName]"""))).rules.size)
        // More than four placeholders is malformed.
        val five = """{"id":"cfg","url":"https://api.tube.example/a?b={{m1}}&c={{m1}}&d={{m1}}&e={{m1}}&f={{m1}}","maxBytes":1024}"""
        assertEquals(0, RuleSet.parse(v3(base(""","fetch":[$five]"""))).rules.size)
        // Unclosed braces are malformed, not silently literal.
        val stray = """{"id":"cfg","url":"https://api.tube.example/a/{{m1","maxBytes":1024}"""
        assertEquals(0, RuleSet.parse(v3(base(""","fetch":[$stray]"""))).rules.size)
    }

    @Test fun templateRendersPageUrlAndGroupsVerbatim() {
        val path = """\/watch\/(?<vid>[0-9]+)\/([a-z]{2})"""
        fun render(template: String, match: MatchResult?, pageUrl: String) =
            RuleTemplatePolicy.render(template, pageUrl, match)
        val mr = Regex(path).find("https://tube.example/watch/12345/en")!!
        // Positional binding follows the engine's group numbering: the named group is m1, the
        // unnamed language group is m2.
        assertEquals(
            "https://api.tube.example/v/12345/en?u=https://tube.example/watch/12345/en",
            render("https://api.tube.example/v/{{match.vid}}/{{m2}}?u={{pageUrl}}", mr, "https://tube.example/watch/12345/en"),
        )
        assertEquals("12345", render("https://api.tube.example/v/{{m1}}", mr, "https://tube.example/watch/12345/en")!!.substringAfterLast('/'))
        // Unresolvable binding yields null instead of a half-rendered URL.
        assertNull(render("https://api.tube.example/v/{{match.missing}}", mr, "https://tube.example/watch/12345/en"))
        assertNull(render("https://api.tube.example/v/{{m1}}", null, "https://tube.example/watch/12345/en"))
    }

    @Test fun sessionStaysInsideTheMatchRegistrableDomains() {
        fun session(hosts: String, matchHosts: String = """(^|\\.)tube\\.example$"""): Int {
            val rule = """{"id":"s","version":1,"match":{"hosts":"$matchHosts"},
                "actions":[{"type":"manifestHint","kind":"unknown"}],
                "session":{"hosts":[$hosts]}}"""
            return RuleSet.parse(v3(rule)).rules.size
        }
        assertEquals(1, session(""""api.tube.example""""))
        assertEquals(1, session(""""tube.example""""))
        assertEquals(1, session(""""deep.api.tube.example"""")) // same registrable domain, deeper host
        assertEquals(0, session(""""other.example""""))       // different registrable domain
        assertEquals(0, session(""""tube.example.org""""))    // suffix collision is still cross-domain
        assertEquals(0, session(""""evil-tube.example""""))   // not matched by the hosts face
    }

    @Test fun etldPlusOneSuffixTableHandlesCoUkAndGovCnShapes() {
        fun ok(matchHosts: String, sessionHost: String): Boolean {
            val rule = """{"id":"s","version":1,"match":{"hosts":"$matchHosts"},
                "actions":[{"type":"manifestHint","kind":"unknown"}],
                "session":{"hosts":["$sessionHost"]}}"""
            return RuleSet.parse(v3(rule)).rules.isNotEmpty()
        }
        // The hosts face must match the session host AND share its registrable domain: faces are
        // written at registrable granularity, sessions pick hosts inside them.
        assertTrue(ok("""(^|\\.)site\\.co\\.uk$""", "api.site.co.uk"))
        assertFalse(ok("""(^|\\.)site\\.co\\.uk$""", "api.other.co.uk"))      // face mismatch and cross-registrable
        assertFalse(ok("""(^|\\.)site\\.co\\.uk$""", "site.co.uk.evil.example"))
        assertTrue(ok("""(^|\\.)news\\.gov\\.cn$""", "api.news.gov.cn"))
        assertFalse(ok("""(^|\\.)gov\\.cn$""", "api.gov.cn"))                 // matched by face, but outside its registrable domain
        assertTrue(ok("""(^|\\.)mall\\.com\\.cn$""", "cdn.mall.com.cn"))
        assertFalse(ok("""(^|\\.)mall\\.com\\.cn$""", "mall.cn"))
        // Multi-label suffixes do not leak into plain-TLD domains.
        assertTrue(ok("""(^|\\.)tube\\.example$""", "api.tube.example"))
        assertFalse(ok("""(^|\\.)tube\\.co\\.uk$""", "api.tube.example"))
    }

    @Test fun sessionShapeAndCapsAreValidated() {
        fun withSession(session: String): Int = RuleSet.parse(v3(base(""","session":$session"""))).rules.size
        assertEquals(0, withSession("""{"hosts":[]}"""))                 // empty declaration is useless
        assertEquals(0, withSession("""{"hosts":"api.tube.example"}""")) // not a list
        assertEquals(0, withSession("""{"hosts":["api.tube.example","b.tube.example","c.tube.example","d.tube.example","e.tube.example"]}""")) // over the cap
        assertEquals(0, withSession("""{"hosts":["api.tube.example:443"]}""")) // port is not a host
        assertEquals(0, withSession("""{"hosts":["(^|\\.)tube\\.example$"]}""")) // regex metacharacters refused
        assertEquals(0, withSession(""""not-an-object""""))
        assertEquals(1, withSession("""{"hosts":["api.tube.example"]}"""))
    }

    @Test fun alternationHostsFacesExpandForSessionContainment() {
        val rule = """{"id":"alt","version":1,"match":{"hosts":"(^|\\.)site\\.(com|net)$"},
            "actions":[{"type":"manifestHint","kind":"unknown"}],
            "session":{"hosts":["api.site.net"]}}"""
        assertEquals(1, RuleSet.parse(v3(rule)).rules.size)
        val cross = """{"id":"alt","version":1,"match":{"hosts":"(^|\\.)site\\.(com|net)$"},
            "actions":[{"type":"manifestHint","kind":"unknown"}],
            "session":{"hosts":["api.site.org"]}}"""
        assertEquals(0, RuleSet.parse(v3(cross)).rules.size)
    }

    @Test fun v3CapRaisesApplyOnlyToV3Documents() {
        fun rulesJson(prefix: String, count: Int) = (1..count).joinToString(",") {
            """{"id":"$prefix$it","version":1,"match":{"hosts":"h$it.example"},"actions":[{"type":"manifestHint","kind":"unknown"}],"note":"${"y".repeat(60)}"}"""
        }
        // v1/v2 documents keep the v0.1.7 rule cap of 64…
        assertEquals(64, RuleSet.parse(v2(rulesJson("a", 70))).rules.size)
        // …while v3 documents accept up to 2048 with unique ids.
        assertEquals(2048, RuleSet.parse(v3(rulesJson("b", 2100))).rules.size)
        // And the file-size gate: a >64 KiB document is empty under v2, parsed under v3.
        val big = rulesJson("c", 900) // ~72 KiB of short strings
        assertTrue(big.length > 64 * 1024)
        assertEquals(0, RuleSet.parse(v2(big)).rules.size)
        assertEquals(900, RuleSet.parse(v3(big)).rules.size)
        // Both tiers reject an oversized document outright at their own ceiling.
        assertEquals(RuleSet.EMPTY, RuleSet.parse("""{"version":3,"note":"${"x".repeat(600_000)}","rules":[]}"""))
    }

    @Test fun globalCompiledRegexCeilingTruncatesTheLoad() {
        // Each rule compiles hosts + path + 4 capture endpoints = 6 regexes; 4096 / 6 = 682 rules.
        val one = { i: Int ->
            """{"id":"x$i","version":1,"match":{"hosts":"h$i.example","path":"\\/p$i\\/"},
                "actions":[{"type":"manifestHint","kind":"unknown"}],
                "captureEndpoints":["\\/a$i","\\/b$i","\\/c$i","\\/d$i"]}"""
        }
        val text = v3((1..700).joinToString(",") { one(it) })
        val set = RuleSet.parse(text)
        assertTrue("expected truncation at the regex ceiling, got ${set.rules.size}", set.rules.size in 1..682)
        assertEquals(682, set.rules.size)
    }

    @Test fun lowerSchemaVersionsIgnoreFetchAndSessionAndNewActions() {
        val set = RuleSet.parse(v2(base(
            ""","fetch":[{"id":"cfg","url":"https://api.tube.example/a","maxBytes":1024}],
                "session":{"hosts":["api.tube.example"]}""",
            actions = """[{"type":"jsonExtract","pointers":["files"]}]""",
        )))
        // The v2 action set is empty after the unknown jsonExtract is skipped: rule dropped, keys inert.
        assertTrue(set.rules.isEmpty())
        val legacy = RuleSet.parse(v2(base(actions = """[{"type":"manifestHint","kind":"hls"}]""")))
        val rule = legacy.rules.single()
        assertEquals(emptyList<FetchSpec>(), rule.fetch)
        assertNull(rule.session)
        assertEquals(listOf<RuleAction>(RuleAction.ManifestHint(com.example.purebrowser.media.MediaKind.HLS)), rule.actions)
    }

    @Test fun newActionsParseIntoTheirWhitelistedShapes() {
        val set = RuleSet.parse(v3("""
            {"id":"a","version":1,"match":{"hosts":"(^|\\.)tube\\.example$"},"actions":[
                {"type":"jsonExtract","pointers":["files[0].url","hls.url"]},
                {"type":"regexExtract","pattern":"https:\\/\\/[^\"']+\\.mp4","groupNames":[]},
                {"type":"ogMeta","properties":["og:video:secure_url","twitter:player:stream"]},
                {"type":"parseManifest","kind":"hls"}]}"""))
        val actions = set.rules.single().actions
        assertEquals(
            listOf(
                RuleAction.JsonExtract(listOf("files[0].url", "hls.url")),
                RuleAction.RegexExtract("https:\\/\\/[^\"']+\\.mp4", emptyList()),
                RuleAction.OgMeta(listOf("og:video:secure_url", "twitter:player:stream")),
                RuleAction.ParseManifest(com.example.purebrowser.media.MediaKind.HLS),
            ),
            actions,
        )
    }

    @Test fun jsonPointerGrammarIsRestricted() {
        fun pointers(vararg list: String): Int {
            val joined = list.joinToString(",") { """"$it"""" }
            return RuleSet.parse(v3(base(actions = """[{"type":"jsonExtract","pointers":[$joined]}]"""))).rules.size
        }
        assertEquals(1, pointers("a", "a.b", "a.b[0].c", "a_b.c-d", "x[999]"))
        assertEquals(0, pointers("a[..]"))
        assertEquals(0, pointers("a[b]"))
        assertEquals(0, pointers("a.b[0][1]"))
        assertEquals(0, pointers(".a"))
        assertEquals(0, pointers("a."))
        assertEquals(0, pointers("../x"))
        assertEquals(0, pointers("a".repeat(65)))
        assertEquals(0, pointers(*(1..9).map { "p$it" }.toTypedArray())) // over the 8-pointer cap
    }

    @Test fun regexExtractGuardsCatastrophicShapesAtLoad() {
        fun extract(pattern: String): Int = RuleSet.parse(v3(base(
            actions = """[{"type":"regexExtract","pattern":"$pattern"}]""",
        ))).rules.size
        assertEquals(1, extract("""https:\\/\\/cdn[^']+\\.mp4"""))
        assertEquals(1, extract("""(video_url).([0-9]+)"""))
        assertEquals(0, extract("(a+)+"))            // classic nested unbounded quantifier
        assertEquals(0, extract("""([a-z]+)*x"""))   // bounded target after a nested star
        assertEquals(0, extract("""(a{2,})+"""))     // open-ended brace inside a quantified group
        assertEquals(1, extract("""(a{2})+"""))      // {2} is bounded: legal
        assertEquals(1, extract("""[a-z]+"""))       // unquantified group shapes are fine
        assertEquals(0, extract("(["))               // must still compile
        // groupNames must name real groups in the pattern.
        assertEquals(0, RuleSet.parse(v3(base(actions = """[{"type":"regexExtract","pattern":"(a)","groupNames":["missing"]}]"""))).rules.size)
        assertEquals(1, RuleSet.parse(v3(base(actions = """[{"type":"regexExtract","pattern":"(?<u>a)","groupNames":["u"]}]"""))).rules.size)
        // More than four group names is malformed.
        assertEquals(0, RuleSet.parse(v3(base(actions = """[{"type":"regexExtract","pattern":"(a)(b)(c)(d)(e)","groupNames":["a","b","c","d","e"]}]"""))).rules.size)
    }

    @Test fun ogMetaPropertiesAreBoundedAndPlain() {
        fun props(vararg list: String): Int {
            val joined = list.joinToString(",") { """"$it"""" }
            return RuleSet.parse(v3(base(actions = """[{"type":"ogMeta","properties":[$joined]}]"""))).rules.size
        }
        assertEquals(1, props("og:video", "og:video:secure_url"))
        assertEquals(0, props("og video"))     // spaces are not property syntax
        assertEquals(0, props("1og:video"))    // must start with a letter
        assertEquals(0, props(*(1..9).map { "og:video$it" }.toTypedArray())) // over the 8-property cap
    }

    @Test fun parseManifestAcceptsOnlyHlsOrDash() {
        fun kind(value: String): Int = RuleSet.parse(v3(base(
            actions = """[{"type":"parseManifest","kind":"$value"}]""",
        ))).rules.size
        assertEquals(1, kind("hls"))
        assertEquals(1, kind("dash"))
        assertEquals(0, kind("file"))
        assertEquals(0, kind("mkv"))
    }
}

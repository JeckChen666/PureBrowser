"""Validator port tests (bug-compatible transliteration of RuleData.kt)."""

from transcriber import validate

GOOD_RULE = {
    "id": "test-good",
    "version": 1,
    "match": {
        "hosts": r"(^|\.)example\.com$",
        "path": r"/watch/(?<id>[0-9]+)",
    },
    "actions": [{"type": "jsonExtract", "pointers": ["url", "formats[0].height"]}],
    "fetch": [{"id": "api", "url": "https://api.example.com/v1/{{match.id}}", "maxBytes": 131072}],
    "note": "test",
}


# ------------------------------------------------------- RegexSourceScan
def test_capturing_group_count():
    assert validate.capturing_group_count(r"(?<a>x)(y)(?:z)(?<=w)(?<!v)") == 2
    assert validate.capturing_group_count(r"[()]\((a)") == 1  # class contents skipped
    assert validate.capturing_group_count("no groups") == 0


def test_named_group_names():
    assert validate.named_group_names(r"(?<id>\d+)(?<slug>\w+)") == {"id", "slug"}
    assert validate.named_group_names(r"(?<=id)x") == set()


# ------------------------------------------------------------- RegexGuard
def test_regex_guard_nested_quantifiers():
    assert not validate.regex_guard_is_safe(r"(a*)*")
    assert not validate.regex_guard_is_safe(r"(a+)+")
    assert not validate.regex_guard_is_safe(r"([a-z]+){2,}")
    assert validate.regex_guard_is_safe(r"(ab)+")
    assert validate.regex_guard_is_safe(r"a{2,3}")
    assert validate.regex_guard_is_safe(r"[a+]+b")  # class contents are ignored
    assert not validate.regex_guard_is_safe("(a(")  # fails to compile


# ------------------------------------------------------- JsonPointerPolicy
def test_json_pointer_policy():
    assert validate.json_pointer_is_valid("a.b[0].c")
    assert validate.json_pointer_is_valid("formats[12].url")
    assert validate.json_pointer_is_valid("_under-score9")
    assert not validate.json_pointer_is_valid("")
    assert not validate.json_pointer_is_valid("a b")
    assert not validate.json_pointer_is_valid("a..b")
    assert not validate.json_pointer_is_valid("a[1000]")
    assert not validate.json_pointer_is_valid("x" * 65)


# --------------------------------------------------- RuleTemplatePolicy
def test_placeholders_whitelist():
    path = r"/watch/(?<id>[0-9]+)/(?<q>\w+)"
    assert validate.validate_placeholders("https://a/{{pageUrl}}", None)
    assert validate.validate_placeholders("https://a/{{m1}}/{{m2}}", path)
    assert validate.validate_placeholders("https://a/{{match.id}}", path)
    assert not validate.validate_placeholders("https://a/{{m3}}", path)  # unbound
    assert not validate.validate_placeholders("https://a/{{match.nope}}", path)
    assert not validate.validate_placeholders("https://a/{{evil}}", path)
    assert not validate.validate_placeholders("https://a/{{m1}}{{m2}}{{m3}}{{m4}}{{m5}}", path)
    assert not validate.validate_placeholders("https://a/{{m1}} {{oops", path)  # stray braces
    assert not validate.validate_placeholders("https://a/" + "x" * 512, path)  # overlong


# ------------------------------------------------------ RegistrableDomains
def test_registrable_domain():
    assert validate.registrable_domain("a.b.co.uk") == "b.co.uk"
    assert validate.registrable_domain("video.example.com") == "example.com"
    assert validate.registrable_domain("example.com") == "example.com"
    assert validate.registrable_domain("http://x") is None
    assert validate.registrable_domain("x") is None


# ----------------------------------------------------------- rule checks
def test_good_rule_validates():
    assert validate.validate_rule(GOOD_RULE) == []


def test_rejects_bad_id():
    rule = dict(GOOD_RULE, id="x" * 65)
    assert "id missing/overlong" in validate.validate_rule(rule)
    rule = dict(GOOD_RULE, id="")
    assert validate.validate_rule(rule)


def test_rejects_no_match_face():
    rule = dict(GOOD_RULE, match={})
    assert "no match face" in validate.validate_rule(rule)


def test_rejects_non_matching_pattern():
    rule = dict(GOOD_RULE)
    rule["match"] = {"hosts": "(unclosed"}
    assert "hosts pattern fails to compile" in validate.validate_rule(rule)


def test_rejects_no_actions_and_overlong_note():
    rule = dict(GOOD_RULE, actions=[])
    assert "no actions" in validate.validate_rule(rule)
    rule = dict(GOOD_RULE, note="n" * 181)
    assert "note overlong" in validate.validate_rule(rule)


def test_fetch_https_get_only():
    rule = dict(GOOD_RULE)
    rule["fetch"] = [{"id": "api", "url": "http://api.example.com/{{m1}}", "maxBytes": 100}]
    assert "fetch url not https" in validate.validate_rule(rule)
    rule["fetch"] = [{"id": "api", "method": "POST", "url": "https://a/{{m1}}", "maxBytes": 100}]
    assert "non-GET fetch method" in validate.validate_rule(rule)


def test_fetch_maxbytes_bounds():
    for bad in (0, -1, 262145, None, "100"):
        rule = dict(GOOD_RULE)
        rule["fetch"] = [{"id": "api", "url": "https://a/{{m1}}", "maxBytes": bad}]
        assert "fetch maxBytes missing/out of range" in validate.validate_rule(rule)


def test_fetch_requires_hosts_face():
    rule = dict(GOOD_RULE)
    rule["match"] = {"path": r"/watch/(?<id>[0-9]+)"}
    rule["fetch"] = [{"id": "api", "url": "https://a/{{m1}}", "maxBytes": 100}]
    assert "fetch without hosts face" in validate.validate_rule(rule)


def test_fetch_duplicate_ids_and_unbound_placeholders():
    rule = dict(GOOD_RULE)
    rule["fetch"] = [
        {"id": "api", "url": "https://a/{{m1}}", "maxBytes": 100},
        {"id": "api", "url": "https://b/{{m1}}", "maxBytes": 100},
    ]
    assert "duplicate fetch id" in validate.validate_rule(rule)
    rule["fetch"] = [{"id": "api", "url": "https://a/{{match.nope}}", "maxBytes": 100}]
    assert "fetch placeholders invalid/unbound" in validate.validate_rule(rule)


def test_path_face_length_cap():
    rule = dict(GOOD_RULE)
    rule["match"] = {"hosts": r"(^|\.)example\.com$", "path": "/x" + "y" * 600}
    # overlong pattern is dropped by _pattern_text -> hosts-only rule with fetch
    reasons = validate.validate_rule(rule)
    assert "fetch placeholders invalid/unbound" in reasons  # {{match.id}} lost its face


def test_regex_extract_action_checks():
    rule = dict(GOOD_RULE, actions=[{"type": "regexExtract", "pattern": "(a*)*"}])
    assert "regexExtract pattern unsafe (RegexGuard)" in validate.validate_rule(rule)
    rule = dict(GOOD_RULE, actions=[{"type": "regexExtract", "pattern": "x" * 257}])
    assert "regexExtract pattern missing/overlong" in validate.validate_rule(rule)
    rule = dict(GOOD_RULE, actions=[{"type": "regexExtract", "pattern": "(?<u>[a-z]+)",
                                     "groupNames": ["nope"]}])
    assert "regexExtract groupNames invalid" in validate.validate_rule(rule)
    rule = dict(GOOD_RULE, actions=[{"type": "regexExtract", "pattern": "(?<u>[a-z]+)",
                                     "groupNames": ["u"]}])
    assert validate.validate_rule(rule) == []


def test_unknown_action_type_rejected():
    rule = dict(GOOD_RULE, actions=[{"type": "execPython"}])
    assert "unknown action type" in validate.validate_rule(rule)


# ------------------------------------------------------ document checks
def test_validate_document_caps():
    rules = [dict(GOOD_RULE, id="r%d" % i) for i in range(5)]
    assert validate.validate_document(rules) == []
    dup = [dict(GOOD_RULE, id="same"), dict(GOOD_RULE, id="same")]
    assert "duplicate rule id" in validate.validate_document(dup)
    many = [dict(GOOD_RULE, id="r%d" % i) for i in range(2049)]
    assert "too many rules" in validate.validate_document(many)

"""T108 root cause 2: fetch.hosts extension — validator port tests.

The port mirrors the app-side load-time contract the orchestrator must wire:
  - fetch URL host must satisfy registrable_domain(fetch_host) IN
    { registrable_domain of match.hosts literals } UNION { declared fetch.hosts }
  - IP literals and private/link-local hosts are always denied
  - importer-tier documents may not use the extension (allow flag = False)
"""

from transcriber import validate


def rule_with(fetch_spec, hosts=r"(^|\.)example\.com$", path=r"/watch/(?<id>[0-9]+)"):
    return {
        "id": "t",
        "version": 1,
        "match": {"hosts": hosts, "path": path},
        "actions": [{"type": "jsonExtract", "pointers": ["url"]}],
        "fetch": [dict({"id": "api", "maxBytes": 131072}, **fetch_spec)],
    }


# ------------------------------------------------- membership (no extension)
def test_same_registrable_fetch_host_allowed():
    rule = rule_with({"url": "https://api.example.com/v1/{{match.id}}"})
    assert validate.validate_rule(rule) == []


def test_subdomain_of_match_registrable_allowed():
    rule = rule_with({"url": "https://cdn.api.example.com/v1/{{match.id}}"})
    assert validate.validate_rule(rule) == []


def test_cross_origin_fetch_host_rejected_without_declaration():
    rule = rule_with({"url": "https://api.othercdn.net/v1/{{match.id}}"})
    assert "fetch host outside match face and declared fetch hosts" in validate.validate_rule(rule)


# ----------------------------------------------------- declared fetch.hosts
def test_declared_extra_domain_allows_cross_origin_fetch():
    rule = rule_with({"url": "https://api.othercdn.net/v1/{{match.id}}",
                      "hosts": ["othercdn.net"]})
    assert validate.validate_rule(rule) == []


def test_declared_entry_must_be_subdomain_not_string_garbage():
    rule = rule_with({"url": "https://deep.api.othercdn.net/v1/{{match.id}}",
                      "hosts": ["othercdn.net"]})
    assert validate.validate_rule(rule) == []
    bad = rule_with({"url": "https://api.othercdn.net/v1/{{match.id}}",
                     "hosts": ["https://othercdn.net"]})
    assert "fetch hosts malformed" in validate.validate_rule(bad)


def test_declaration_of_unrelated_domain_does_not_cover_fetch_host():
    rule = rule_with({"url": "https://api.third.org/v1/{{match.id}}",
                      "hosts": ["othercdn.net"]})
    assert "fetch host outside match face and declared fetch hosts" in validate.validate_rule(rule)


def test_fetch_hosts_shape_caps():
    too_many = rule_with({"url": "https://api.othercdn.net/v1/{{match.id}}",
                          "hosts": ["a.com", "b.com", "c.com", "d.com", "e.com"]})
    assert "fetch hosts malformed" in validate.validate_rule(too_many)
    not_list = rule_with({"url": "https://api.othercdn.net/v1/{{match.id}}", "hosts": "othercdn.net"})
    assert "fetch hosts malformed" in validate.validate_rule(not_list)


# --------------------------------------------------------- IP / private
def test_ip_literal_fetch_target_denied_even_when_declared():
    rule = rule_with({"url": "https://127.0.0.1/x/{{match.id}}", "hosts": ["127.0.0.1"]})
    reasons = validate.validate_rule(rule)
    assert "fetch host ip-literal/private denied" in reasons
    rule = rule_with({"url": "https://192.168.1.5/x/{{match.id}}", "hosts": ["192.168.1.5"]})
    assert "fetch host ip-literal/private denied" in validate.validate_rule(rule)


def test_private_declaration_rejected():
    # dotless/localhost entries fail the plain-host shape gate first
    rule = rule_with({"url": "https://api.example.com/v1/{{match.id}}",
                      "hosts": ["localhost"]})
    assert "fetch hosts malformed" in validate.validate_rule(rule)
    rule = rule_with({"url": "https://api.example.com/v1/{{match.id}}",
                      "hosts": ["intranet"]})
    assert "fetch hosts malformed" in validate.validate_rule(rule)


# ------------------------------------------------------ importer tier flag
def test_importer_tier_rejects_extension():
    rule = rule_with({"url": "https://api.othercdn.net/v1/{{match.id}}",
                      "hosts": ["othercdn.net"]})
    assert validate.validate_rule(rule) == []
    reasons = validate.validate_rule(rule, allow_fetch_hosts_extension=False)
    assert "fetch.hosts extension not permitted (importer tier)" in reasons


# ----------------------------------------------------- placeholder authority
def test_placeholder_authority_host_uncheckable():
    # authority carries a placeholder: no static verdict possible (runtime
    # policy decides); the port must not reject for host membership
    rule = rule_with({"url": "https://{{match.baseurl}}/player/{{match.id}}",
                      "path": r"/watch/(?<baseurl>[a-z0-9.-]+)/(?<id>[0-9]+)"})
    reasons = validate.validate_rule(rule)
    assert "fetch host outside match face and declared fetch hosts" not in reasons


def test_template_literal_host_extraction():
    assert validate.template_literal_host("https://api.example.com/v1/x") == "api.example.com"
    assert validate.template_literal_host("https://cdn.x.com:8081/y") == "cdn.x.com"
    assert validate.template_literal_host("https://{{match.h}}/y") is None
    assert validate.template_literal_host("http://api.example.com/") is None


# -------------------------------------------------- alternation domains
def test_pattern_domain_literals_alternation_expansion():
    assert validate.pattern_domain_literals(r"(?:msnbc|nbcnews|today)\.com") == [
        "msnbc.com", "nbcnews.com", "today.com"]
    assert validate.pattern_domain_literals(r"(^|\.)(?:a\.com|b\.net)$") == ["a.com", "b.net"]
    assert validate.pattern_domain_literals(r"(?:[a-z]+\.)?example\.com") == ["example.com"]
    # port groups and lookarounds are transparent, not poisonous
    assert validate.pattern_domain_literals(r"player\.example\.com(?::\d+)?") == ["player.example.com"]
    assert validate.pattern_domain_literals(r"(?!www\.)(?<id>[^.]+)\.example\.com") == [
        "example.com"]
    # escaped slash terminates the host face
    assert validate.pattern_domain_literals(r"player\.radiozet\.pl\/Podcasty") == [
        "player.radiozet.pl"]

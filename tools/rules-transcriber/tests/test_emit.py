"""Emission tests: face derivation, fetch templates, end-to-end candidates."""

import ast
import textwrap

from transcriber import analyzer, emit, validate


def make_record(source: str) -> analyzer.ExtractorRecord:
    tree = ast.parse(textwrap.dedent(source))
    resolver = analyzer._StaticResolver({}, {})
    records = analyzer.analyze_module("sample.py", tree, resolver)
    assert len(records) == 1
    analyzer.classify(records[0])
    return records[0]


SIMPLE_SRC = """
    from .common import InfoExtractor

    class SampleIE(InfoExtractor):
        _VALID_URL = r'https?://(?:www\\.)?example\\.com/watch/(?P<id>[0-9]+)'
        _TESTS = [{'url': 'https://www.example.com/watch/123'}]

        def _real_extract(self, url):
            video_id = self._match_id(url)
            data = self._download_json(
                'https://api.example.com/v1/%s' % video_id, video_id)
            return {'id': data['id'], 'url': data['url'], 'title': data.get('title')}
    """


# ------------------------------------------------------------- face split
def test_split_faces_basic():
    host, path, err = emit.split_faces(r"^https?://(?:www\.)example\.com/watch/(?<id>\d+)$")
    assert err == ""
    assert host == r"(?:www\.)example\.com"
    assert path == r"/watch/(?<id>\d+)$"  # anchors are stripped later, by the emitter


def test_split_faces_group_question_mark_is_not_delimiter():
    host, path, err = emit.split_faces(r"https?://(?:www\.|m\.)example\.com/v/(?<id>\d+)")
    assert err == ""
    assert host == r"(?:www\.|m\.)example\.com"
    assert path == r"/v/(?<id>\d+)"


def test_split_faces_no_scheme():
    _h, _p, err = emit.split_faces(r"example\.com/x")
    assert err == "no scheme separator"


# --------------------------------------------- alternation-aware splitting
def test_split_url_pattern_branches():
    branches, err = emit.split_url_pattern(
        r"https?://(?:(?:t|www)\.example\.com/(?:opus|dynamic)/(?<id>\d+)/?"
        r"|api\.example\.com/feed/(?<id>\d+))")
    assert err == ""
    hosts = [h for h, _p in branches]
    paths = [p for _h, p in branches]
    assert r"(?:t|www)\.example\.com" in hosts
    assert r"api\.example\.com" in hosts
    assert any(p.startswith("/(?:opus|dynamic)/") for p in paths)
    assert any(p.startswith("/feed/") for p in paths)
    assert not any(")" == p[:1] for p in paths)  # no stray-paren corruption


def test_split_url_pattern_single_branch_matches_split_faces():
    pattern = r"https?://(?:www\.)example\.com/watch/(?P<id>[0-9]+)"
    host, path, err = emit.split_faces(pattern)
    branches, err2 = emit.split_url_pattern(pattern)
    assert err == err2 == ""
    assert branches == [(host, path)]


def test_split_url_pattern_no_scheme():
    branches, err = emit.split_url_pattern(r"example\.com/x")
    assert branches == []
    assert err == "no scheme separator"


def test_emit_candidate_alt_branch_pattern_end_to_end():
    # NOTE: branch group names must stay distinct — java.util.regex refuses
    # duplicate names, so per-branch (?P<id>) reuse cannot become a rule face.
    src = """
    from .common import InfoExtractor

    class AltIE(InfoExtractor):
        _VALID_URL = r'https?://(?:(?:t|www)\\.example\\.com/(?:opus|dynamic)/(?P<id>\\d+)/?|api\\.example\\.com/feed/(?P<fid>\\d+))'
        _TESTS = [{'url': 'https://t.example.com/opus/123'}]

        def _real_extract(self, url):
            video_id = self._match_id(url)
            data = self._download_json('https://cdn.example.com/x/' + video_id, video_id)
            return {'id': video_id, 'url': data['url']}
    """
    rec = make_record(src)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    rule = candidate.rule
    # both branch hosts (t/www/api .example.com) collapse to the registrable
    assert rule["match"]["hosts"] == r"(^|\.)example\.com$"
    assert rule["match"]["path"].startswith("(?:")
    assert "/(?:opus|dynamic)/(?<id>\\d+)/?" in rule["match"]["path"]
    assert emit.THEME_ALT_HOSTS in candidate.themes
    assert validate.validate_rule(rule) == []
    assert emit.fetch_qa_ok(rule, rec.test_url)


def test_emit_duplicate_branch_group_names_rejected():
    src = """
    from .common import InfoExtractor

    class DupIE(InfoExtractor):
        _VALID_URL = r'https?://(?:(?:t|www)\\.example\\.com/a/(?P<id>\\d+)|api\\.example\\.com/b/(?P<id>\\d+))'

        def _real_extract(self, url):
            video_id = self._match_id(url)
            data = self._download_json('https://cdn.example.com/x/' + video_id, video_id)
            return {'id': video_id, 'url': data['url']}
    """
    rec = make_record(src)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is None
    assert "duplicate or colliding group name" in reason


def test_domain_literals():
    # T108: literal-label alternations expand (www. prefix now enumerated too,
    # collapsing to the same registrable domain downstream)
    assert emit.domain_literals(r"(?:www\.)?example\.com") == ["example.com", "www.example.com"]
    assert emit.registrable_domains(r"(?:www\.)?example\.com") == ["example.com"]
    assert emit.domain_literals(r"(?:(?:www|m)\.)?example\.com") == [
        "example.com", "m.example.com", "www.example.com"]
    assert emit.domain_literals(r"video\.example\.com") == ["video.example.com"]
    assert emit.domain_literals(r"(?:[a-z]+\.)?example\.com") == ["example.com"]
    assert emit.domain_literals(r"(?:nbcnews|today|msnbc)\.com") == [
        "msnbc.com", "nbcnews.com", "today.com"]
    assert emit.domain_literals(r"(?P<host>[^/]+)") == []


def test_registrable_domains_and_hosts_face():
    assert emit.registrable_domains(r"(?:www\.)?example\.com") == ["example.com"]
    assert emit.hosts_face(["example.com"]) == r"(^|\.)example\.com$"
    multi = emit.hosts_face(["a.com", "b.net"])
    assert multi == r"(^|\.)(?:a\.com|b\.net)$"


def test_strip_anchors():
    assert emit.strip_anchors("^/a$") == "/a"
    assert emit.strip_anchors("/a") == "/a"


def test_rename_first_unnamed():
    assert emit.rename_first_unnamed(r"/x(\d+)/y", "id") == r"/x(?<id>\d+)/y"
    assert emit.rename_first_unnamed(r"/x(?<id>\d+)", "id") is None
    assert emit.rename_first_unnamed(r"/x(?:\d+)", "id") is None  # non-capturing only


# ------------------------------------------------------ template derivation
def test_derive_template_percent():
    rec = make_record(SIMPLE_SRC)
    ctx = emit.TemplateCtx(rec=rec, path_src=r"/watch/(?<id>[0-9]+)")
    template = emit.derive_template(rec.download_calls[0].url_expr, ctx)
    assert template == "https://api.example.com/v1/{{match.id}}"


def test_derive_template_fstring_and_format():
    src = SIMPLE_SRC.replace(
        "'https://api.example.com/v1/%s' % video_id, video_id)",
        "f'https://api.example.com/v2/{video_id}', video_id)")
    rec = make_record(src)
    ctx = emit.TemplateCtx(rec=rec, path_src=r"/watch/(?<id>[0-9]+)")
    assert emit.derive_template(rec.download_calls[0].url_expr, ctx) == \
        "https://api.example.com/v2/{{match.id}}"

    src2 = SIMPLE_SRC.replace(
        "'https://api.example.com/v1/%s' % video_id, video_id)",
        "'https://api.example.com/v3/{}'.format(video_id), video_id)")
    rec2 = make_record(src2)
    ctx2 = emit.TemplateCtx(rec=rec2, path_src=r"/watch/(?<id>[0-9]+)")
    assert emit.derive_template(rec2.download_calls[0].url_expr, ctx2) == \
        "https://api.example.com/v3/{{match.id}}"


def test_derive_template_group_positional_and_named():
    src = """
    from .common import InfoExtractor

    class GroupIE(InfoExtractor):
        _VALID_URL = r'https?://example\\.com/(?P<slug>\\w+)'

        def _real_extract(self, url):
            mobj = self._match_valid_url(url)
            data = self._download_json(
                'https://example.com/api/%s' % mobj.group('slug'), mobj.group('slug'))
            return {'id': mobj.group('slug'), 'url': data['url']}
    """
    rec = make_record(src)
    ctx = emit.TemplateCtx(rec=rec, path_src=r"/(?<slug>\w+)")
    assert emit.derive_template(rec.download_calls[0].url_expr, ctx) == \
        "https://example.com/api/{{match.slug}}"


def test_derive_template_quote_unwrapped_and_class_const():
    src = """
    from urllib.parse import quote
    from .common import InfoExtractor

    class ConstIE(InfoExtractor):
        _API = 'https://api.example.com'
        _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

        def _real_extract(self, url):
            video_id = self._match_id(url)
            data = self._download_json(self._API + '/v/' + quote(video_id), video_id)
            return {'id': video_id, 'url': data['url']}
    """
    rec = make_record(src)
    ctx = emit.TemplateCtx(rec=rec, path_src=r"/(?<id>\w+)")
    assert emit.derive_template(rec.download_calls[0].url_expr, ctx) == \
        "https://api.example.com/v/{{match.id}}"


# ------------------------------------------------------------- emission
def test_emit_candidate_end_to_end():
    rec = make_record(SIMPLE_SRC)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    rule = candidate.rule
    assert rule["match"]["hosts"] == r"(^|\.)example\.com$"
    assert rule["match"]["path"] == r"/watch/(?<id>[0-9]+)"
    assert rule["fetch"] == [{"id": "api", "url": "https://api.example.com/v1/{{match.id}}",
                              "maxBytes": 131072}]
    assert rule["actions"][0]["type"] == "jsonExtract"
    assert "url" in rule["actions"][0]["pointers"]
    assert candidate.needs_review
    assert validate.validate_rule(rule) == []
    assert emit.fetch_qa_ok(rule, rec.test_url)
    assert rule["id"] == "ytdlp-sample"


def test_emit_candidate_webpage_regex_action():
    src = """
    from .common import InfoExtractor

    class PageIE(InfoExtractor):
        _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'
        _TESTS = [{'url': 'https://example.com/abc'}]

        def _real_extract(self, url):
            video_id = self._match_id(url)
            webpage = self._download_webpage('https://example.com/e/%s' % video_id, video_id)
            m = self._search_regex(r"(https://cdn\\.example\\.com/[^\\\"']+\\.mp4)", webpage, 'url')
            return {'id': video_id, 'url': m}
    """
    rec = make_record(src)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    assert candidate.rule["actions"][0]["type"] == "regexExtract"
    assert emit.THEME_WEBPAGE_REGEX in candidate.themes
    assert validate.validate_rule(candidate.rule) == []


def test_emit_rejects_non_https():
    rec = make_record(SIMPLE_SRC.replace("https://api", "http://api"))
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is None
    assert reason == "fetch url not https"


def test_emit_http_upgrade_provisional():
    rec = make_record(SIMPLE_SRC.replace("https://api", "http://api"))
    candidate, reason = emit.emit_candidate(rec, set(), allow_http_upgrade=True)
    assert candidate is not None, reason
    assert candidate.provisional_http is True
    assert candidate.rule["fetch"][0]["url"].startswith("https://api.example.com")
    assert emit.THEME_HTTP_UPGRADE in candidate.themes
    assert emit.REASON_HTTP_UNVERIFIED in candidate.reasons
    assert validate.validate_rule(candidate.rule) == []


def test_emit_declares_fetch_hosts_for_cross_origin_api():
    src = """
    from .common import InfoExtractor

    class CrossIE(InfoExtractor):
        _VALID_URL = r'https?://(?:www\\.)?example\\.com/watch/(?P<id>[0-9]+)'
        _TESTS = [{'url': 'https://www.example.com/watch/5'}]

        def _real_extract(self, url):
            video_id = self._match_id(url)
            data = self._download_json('https://cdn.othercdn.net/embed/' + video_id, video_id)
            return {'id': video_id, 'url': data['url']}
    """
    rec = make_record(src)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    fetch_spec = candidate.rule["fetch"][0]
    assert fetch_spec["hosts"] == ["othercdn.net"]
    assert emit.THEME_FETCH_HOSTS in candidate.themes
    reasons = validate.validate_rule(candidate.rule)
    assert reasons == []
    # importer tier must refuse the extension
    blocked = validate.validate_rule(candidate.rule, allow_fetch_hosts_extension=False)
    assert "fetch.hosts extension not permitted (importer tier)" in blocked


def test_emit_rejects_page_url_fetch():
    src = SIMPLE_SRC.replace(
        "'https://api.example.com/v1/%s' % video_id, video_id)", "url, video_id)")
    rec = make_record(src)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is None
    assert "page URL" in reason


def test_emit_rejects_when_test_url_unmatched():
    rec = make_record(SIMPLE_SRC)
    rec.test_url = "https://elsewhere.org/nope/1"
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is None
    assert "test URL" in reason


def test_slug_uniqueness():
    used: set[str] = set()
    first = emit.slug("Sample", used)
    second = emit.slug("Sample", used)
    assert first == "ytdlp-sample" and second == "ytdlp-sample-2"


def test_render_template():
    assert emit.render_template(
        "https://a/{{match.id}}", "https://x/watch/7", r"/watch/(?<id>[0-9]+)"
    ) == "https://a/7"
    assert emit.render_template(
        "https://a/{{m1}}", "https://x/watch/7", r"/watch/([0-9]+)"
    ) == "https://a/7"
    assert emit.render_template(
        "https://a/{{match.nope}}", "https://x/watch/7", r"/watch/(?<id>[0-9]+)"
    ) is None
    assert emit.render_template(
        "https://a/{{m4}}", "https://x/watch/7", r"/watch/([0-9]+)"
    ) is None

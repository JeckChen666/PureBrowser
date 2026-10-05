"""Classification tests over synthetic mini-extractors (no corpus needed)."""

import ast
import textwrap

from transcriber import analyzer


def build(source: str) -> list[analyzer.ExtractorRecord]:
    tree = ast.parse(textwrap.dedent(source))
    resolver = analyzer._StaticResolver({}, {})
    records = analyzer.analyze_module("sample.py", tree, resolver)
    for rec in records:
        analyzer.classify(rec)
    return records


def one(source: str) -> analyzer.ExtractorRecord:
    records = build(source)
    assert len(records) == 1, records
    return records[0]


SIMPLE_SRC = """
    import json
    from .common import InfoExtractor

    class SampleIE(InfoExtractor):
        _VALID_URL = r'https?://(?:www\\.)?example\\.com/watch/(?P<id>[0-9]+)'
        _TESTS = [{'url': 'https://www.example.com/watch/123'}]

        def _real_extract(self, url):
            video_id = self._match_id(url)
            data = self._download_json(
                'https://api.example.com/v1/%s' % video_id, video_id)
            formats = data['formats']
            return {'id': data['id'], 'url': formats[0]['url'], 'title': data.get('title')}
    """


def test_simple_json_extractor():
    rec = one(SIMPLE_SRC)
    assert rec.classification == analyzer.SIMPLE
    assert rec.classify_reason.startswith("single GET")
    assert rec.valid_url == r"https?://(?:www\.)?example\.com/watch/(?P<id>[0-9]+)"
    assert rec.test_url == "https://www.example.com/watch/123"
    assert len(rec.download_calls) == 1
    assert rec.download_calls[0].func == "_download_json"
    assert not rec.post
    assert rec.json_var == "data"
    assert "id" in rec.json_pointers or "url" in rec.json_pointers


def test_post_api():
    rec = one(SIMPLE_SRC.replace(
        "'https://api.example.com/v1/%s' % video_id, video_id)",
        "'https://api.example.com/v1', video_id, data={'v': video_id})"))
    assert rec.classification == analyzer.POST_API
    assert rec.post


def test_js_complex_over_loc_cap():
    padding = "\n".join("            x%d = %d" % (i, i) for i in range(60))
    src = SIMPLE_SRC.replace("            formats = data['formats']",
                             padding + "\n            formats = data['formats']")
    rec = one(src)
    assert rec.classification == analyzer.JS_COMPLEX
    assert "LOC" in rec.classify_reason


def test_js_complex_tokens():
    rec = one(SIMPLE_SRC.replace(
        "formats = data['formats']",
        "signature = data['sig']  # identifier triggers token"))
    assert rec.classification == analyzer.JS_COMPLEX
    assert rec.classify_reason.startswith("challenge/signature")


def test_js_complex_multiple_downloads():
    rec = one(SIMPLE_SRC.replace(
        "formats = data['formats']",
        "extra = self._download_json('https://api.example.com/x', video_id)"))
    assert rec.classification == analyzer.JS_COMPLEX
    assert "multiple download" in rec.classify_reason


def test_js_complex_xml_downloader():
    rec = one(SIMPLE_SRC.replace("_download_json", "_download_xml"))
    assert rec.classification == analyzer.JS_COMPLEX


def test_other_no_real_extract():
    truncated = SIMPLE_SRC.split("def _real_extract")[0].rstrip() + "\n        pass\n"
    rec = one(truncated)
    assert rec.classification == analyzer.OTHER
    assert "no own _real_extract" in rec.classify_reason


def test_other_not_working():
    src = SIMPLE_SRC.replace("_TESTS", "_WORKING = False\n        _TESTS")
    rec = one(src)
    assert rec.classification == analyzer.OTHER
    assert "not working" in rec.classify_reason


def test_other_unresolvable_valid_url():
    rec = one(SIMPLE_SRC.replace(
        r"_VALID_URL = r'https?://(?:www\.)?example\.com/watch/(?P<id>[0-9]+)'",
        "_VALID_URL = SomethingIE._VALID_URL % {'id': '[0-9]+'}"))
    assert rec.classification == analyzer.OTHER
    assert "resolvable" in rec.classify_reason


def test_other_no_download_call():
    rec = one(SIMPLE_SRC.replace(
        "data = self._download_json(\n                'https://api.example.com/v1/%s' % video_id, video_id)",
        "data = {'x': 1}"))
    assert rec.classification == analyzer.OTHER
    assert "no direct download call" in rec.classify_reason


def test_json_loads_wrapper_marks_json_var():
    src = """
    import json
    from .common import InfoExtractor

    class WrapperIE(InfoExtractor):
        _VALID_URL = r'https?://example\.com/v/(?P<id>\\w+)'

        def _real_extract(self, url):
            video_id = self._match_id(url)
            webpage = self._download_webpage('https://example.com/e/%s' % video_id, video_id)
            data = json.loads(webpage)
            return {'id': video_id, 'url': data['url']}
    """
    rec = one(src)
    assert rec.classification == analyzer.SIMPLE
    assert rec.json_var == "data"
    assert "url" in rec.json_pointers


def test_tuple_unpack_assignment_tracked():
    src = """
    from .common import InfoExtractor

    class PairIE(InfoExtractor):
        _VALID_URL = r'https?://example\.com/(?P<id>\w+)'

        def _real_extract(self, url):
            mobj = self._match_valid_url(url)
            kind, slug = mobj.group('kind', 'id')
            data = self._download_json(
                'https://example.com/api/%s/%s' % (kind, slug), slug)
            return {'id': slug, 'url': data['url']}
    """
    rec = one(src)
    assert rec.classification == analyzer.SIMPLE
    assert "kind" in rec.assigns and "slug" in rec.assigns


def test_class_consts_inherited_from_same_module_base():
    src = """
    from .common import InfoExtractor

    class BaseIE(InfoExtractor):
        _API = 'https://api.example.com'

    class ChildIE(BaseIE):
        _VALID_URL = r'https?://example\.com/(?P<id>\w+)'

        def _real_extract(self, url):
            video_id = self._match_id(url)
            data = self._download_json(self._API + '/v/' + video_id, video_id)
            return {'id': video_id, 'url': data['url']}
    """
    records = build(src)
    child = [r for r in records if r.cls == "ChildIE"][0]
    assert child.class_consts.get("_API") == "https://api.example.com"
    assert child.classification == analyzer.SIMPLE


def test_search_regex_patterns_captured():
    src = """
    from .common import InfoExtractor

    class PageIE(InfoExtractor):
        _VALID_URL = r'https?://example\.com/(?P<id>\w+)'

        def _real_extract(self, url):
            video_id = self._match_id(url)
            webpage = self._download_webpage('https://example.com/e/%s' % video_id, video_id)
            m = self._search_regex(r'(https://cdn\\.example\\.com/[^"\\\\s]+\\.mp4)', webpage, 'url')
            return {'id': video_id, 'url': m}
    """
    rec = one(src)
    assert rec.classification == analyzer.SIMPLE
    assert rec.search_regex_patterns


def test_multi_domain_alternation_resolves():
    src = SIMPLE_SRC.replace(
        r"(?:www\.)?example\.com", r"(?:(?:www|m)\.)?example\.com")
    rec = one(src)
    assert rec.classification == analyzer.SIMPLE
    assert r"(?:(?:www|m)\.)?example\.com" in rec.valid_url

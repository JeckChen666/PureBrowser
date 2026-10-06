"""T108 root cause 1: recursive AST pointer synthesis tests."""

import textwrap

from transcriber import analyzer, emit, validate


def make_record(source: str) -> analyzer.ExtractorRecord:
    import ast

    tree = ast.parse(textwrap.dedent(source))
    module_consts = analyzer._module_consts(tree)
    resolver = analyzer._StaticResolver({}, {"sample.py": module_consts})
    records = analyzer.analyze_module("sample.py", tree, resolver)
    assert len(records) == 1
    analyzer.classify(records[0])
    return records[0]


def pointers_for(source: str) -> dict[str, str]:
    rec = make_record(source)
    assert rec.json_var, "expected a _download_json variable"
    return dict(rec.json_pointer_prov)


# ------------------------------------------------------------- direct chains
def test_direct_top_level_chain():
    prov = pointers_for("""
        from .common import InfoExtractor

        class AIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

            def _real_extract(self, url):
                video_id = self._match_id(url)
                data = self._download_json('https://api.example.com/v/' + video_id, video_id)
                return {'id': data['id'], 'url': data['url'], 'height': data.get('height')}
        """)
    assert prov.get("url") == "direct"
    assert prov.get("id") == "direct"
    assert prov.get("height") == "direct"


def test_nested_subscript_chain_and_get():
    prov = pointers_for("""
        from .common import InfoExtractor

        class BIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

            def _real_extract(self, url):
                video_id = self._match_id(url)
                info = self._download_json('https://api.example.com/v/' + video_id, video_id)
                return {'id': video_id, 'url': info['data']['formats'][0]['url']}
        """)
    assert "data.formats[0].url" in prov
    assert "nested" in prov["data.formats[0].url"]


# ------------------------------------------------- intermediate + loop envs
def test_intermediate_assign_then_loop():
    prov = pointers_for("""
        from .common import InfoExtractor

        class CIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

            def _real_extract(self, url):
                video_id = self._match_id(url)
                info = self._download_json('https://api.example.com/v/' + video_id, video_id)
                formats = info['formats']
                for f in formats:
                    f.get('url')
                    f['height']
                return {'id': video_id}
        """)
    assert "formats[0].url" in prov
    assert "formats[0].height" in prov
    assert "loop" in prov["formats[0].url"]


def test_loop_over_get_chain():
    prov = pointers_for("""
        from .common import InfoExtractor

        class DIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

            def _real_extract(self, url):
                video_id = self._match_id(url)
                data = self._download_json('https://api.example.com/v/' + video_id, video_id)
                for item in data['response']['items']:
                    item['videoUrl']
                    item.get('quality')
                return {'id': video_id}
        """)
    assert "response.items[0].videoUrl" in prov
    assert "response.items[0].quality" in prov


def test_traverse_obj_paths():
    prov = pointers_for("""
        from .common import InfoExtractor
        from ..utils.traversal import traverse_obj

        class EIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

            def _real_extract(self, url):
                video_id = self._match_id(url)
                api = self._download_json('https://api.example.com/v/' + video_id, video_id)
                m3u8_url = traverse_obj(api, (
                    'data', 'nodes', lambda _, v: v['id'] == 'player', 'video', 'url'))
                title = traverse_obj(api, ('meta', 0, 'title'))
                return {'id': video_id, 'url': m3u8_url, 'title': title}
        """)
    assert "data.nodes[0].video.url" in prov
    assert "traversal" in prov["data.nodes[0].video.url"]
    assert "meta[0].title" in prov


def test_try_get_tuple_path():
    prov = pointers_for("""
        from .common import InfoExtractor
        from ..utils import try_get

        class FIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

            def _real_extract(self, url):
                video_id = self._match_id(url)
                data = self._download_json('https://api.example.com/v/' + video_id, video_id)
                stream_url = try_get(data, ('stream', 0, 'hls'))
                return {'id': video_id, 'url': stream_url}
        """)
    assert "stream[0].hls" in prov
    assert "traversal" in prov["stream[0].hls"]


def test_depth_bound_and_no_access():
    prov = pointers_for("""
        from .common import InfoExtractor

        class GIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'

            def _real_extract(self, url):
                video_id = self._match_id(url)
                data = self._download_json('https://api.example.com/v/' + video_id, video_id)
                deep = data['a']['b']['c']['d']['e']['f']['g']['h']
                return {'id': video_id, 'url': deep}
        """)
    # the 8-key chain is beyond the depth-6 bound; prefixes are subsumed by the
    # deepest recordable chain, so nothing from this family survives
    assert all(not p.startswith("a.b.c.d.e.f.g") for p in prov)
    assert prov == {}


# ------------------------------------------------------- emission behaviors
def test_pointer_uncertain_fallback_emitted_with_marker():
    rec = make_record("""
        from .common import InfoExtractor

        class HIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'
            _TESTS = [{'url': 'https://example.com/abc'}]

            def _real_extract(self, url):
                video_id = self._match_id(url)
                data = self._download_json('https://api.example.com/v/' + video_id, video_id)
                return self.playlist_result(self._entries(data), video_id)
        """)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    assert candidate.rule["actions"][0]["pointers"] == ["url"]  # WILDCARD-free default
    assert emit.REASON_POINTER_UNCERTAIN in candidate.reasons
    assert any("pointer-uncertain" in t for t in candidate.themes)
    assert validate.validate_rule(candidate.rule) == []


def test_nested_pointers_surface_in_rule():
    rec = make_record("""
        from .common import InfoExtractor

        class JIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'
            _TESTS = [{'url': 'https://example.com/abc'}]

            def _real_extract(self, url):
                video_id = self._match_id(url)
                info = self._download_json('https://api.example.com/v/' + video_id, video_id)
                url_out = info['files']['mp4'][0]['url']
                height_out = info['files']['mp4'][0].get('height')
                return {'id': video_id, 'url': url_out, 'height': height_out}
        """)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    pointers = candidate.rule["actions"][0]["pointers"]
    assert pointers[0] == "files.mp4[0].url"  # url-ish leaf ranked first
    assert "files.mp4[0].height" in pointers
    assert any("nested pointer" in t for t in candidate.themes)
    assert validate.validate_rule(candidate.rule) == []


# ----------------------------------------------- template derivation extras
def test_groups_unpack_and_urljoin_derivation():
    rec = make_record("""
        from urllib.parse import urljoin
        from .common import InfoExtractor

        class KIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?<uploader>\\w+)/replay/(?<date>[0-9-]+)'
            _TESTS = [{'url': 'https://example.com/foo/replay/2024-01-01'}]

            def _real_extract(self, url):
                uploader, date = self._match_valid_url(url).groups()
                data = self._download_json(
                    urljoin('https://api.example.com/cache/', uploader + '/replay/' + date),
                    uploader)
                return {'id': uploader, 'url': data['url']}
        """)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    assert candidate.rule["fetch"][0]["url"] == \
        "https://api.example.com/cache/{{match.uploader}}/replay/{{match.date}}"


def test_module_level_string_const_resolution():
    rec = make_record("""
        from .common import InfoExtractor

        SERIES_API = 'https://api.example.com/v2/series/%s'

        class LIE(InfoExtractor):
            _VALID_URL = r'https?://example\\.com/(?P<id>\\w+)'
            _TESTS = [{'url': 'https://example.com/abc'}]

            def _real_extract(self, url):
                display_id = self._match_id(url)
                data = self._download_json(SERIES_API % display_id, display_id)
                return {'id': display_id, 'url': data['url']}
        """)
    candidate, reason = emit.emit_candidate(rec, set())
    assert candidate is not None, reason
    assert candidate.rule["fetch"][0]["url"] == "https://api.example.com/v2/series/{{match.id}}"

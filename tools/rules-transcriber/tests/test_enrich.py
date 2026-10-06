"""T108 live-enrichment tests (mocked fetcher — no network in tests)."""

from transcriber import emit, enrich, validate

SAMPLE_JSON = """
{
  "data": {
    "formats": [
      {"url": "https://cdn.example.com/v/720.mp4", "height": 720},
      {"url": "https://cdn.example.com/v/1080.mp4", "height": 1080}
    ],
    "hls": "https://cdn.example.com/v/master.m3u8",
    "title": "sample"
  }
}
"""


def make_candidate(pointers=("url",), themes=(), test_url="https://example.com/watch/7",
                   measured_none=False):
    rule = {
        "id": "ytdlp-sample",
        "version": 1,
        "match": {
            "hosts": r"(^|\.)example\.com$",
            "path": r"/watch/(?<id>[0-9]+)",
        },
        "actions": [{"type": "jsonExtract", "pointers": list(pointers)}],
        "fetch": [{"id": "api", "url": "https://api.example.com/v1/{{match.id}}",
                   "maxBytes": 131072}],
    }
    return {
        "rule": rule,
        "needs_review": True,
        "reasons": ["auto-transcribed behavior needs human review"],
        "themes": list(themes),
        "source": {"module": "sample.py", "class": "SampleIE", "ie": "Sample",
                   "test_url": test_url},
    }


class FakeFetcher:
    def __init__(self, responses):
        self.responses = responses  # url -> (code, body)
        self.calls: list[str] = []

    def __call__(self, url):
        self.calls.append(url)
        return self.responses.get(url, (404, ""))


# ------------------------------------------------------------- JSON walk
def test_measured_pointers_walk_nested_formats():
    import json

    hits = enrich.measured_pointers(json.loads(SAMPLE_JSON))
    pointers = [h["pointer"] for h in hits]
    assert "data.formats[0].url" in pointers
    assert "data.hls" in pointers
    by_pointer = {h["pointer"]: h for h in hits}
    assert by_pointer["data.hls"]["kind"] == "hls"
    assert by_pointer["data.formats[0].url"]["quality"] == "height=720"
    assert by_pointer["data.formats[0].url"]["kind"] == "direct"
    for hit in hits:
        assert validate.json_pointer_is_valid(hit["pointer"])


def test_measured_pointers_drop_artwork_keys():
    import json

    payload = {
        "logo_url": "https://cdn.example.com/logo.png",
        "url": "https://cdn.example.com/v/1080.mp4",
        "events": [{"thumb_url": "https://cdn.example.com/t.jpg",
                    "link": "https://example.com/e/1"}],
    }
    pointers = [h["pointer"] for h in enrich.measured_pointers(payload)]
    assert "url" in pointers
    assert "logo_url" not in pointers
    assert "events[0].thumb_url" not in pointers
    assert not enrich.pointer_is_media_pointer("poster[0].image_url")


def test_measured_pointers_capped_and_ranked():
    import json

    payload = {"f%d" % i: {"url": "https://x.example.com/%d.mp4" % i} for i in range(20)}
    hits = enrich.measured_pointers(payload)
    assert len(hits) <= validate.MAX_JSON_POINTERS


# ------------------------------------------------------- enrichment driver
def test_enrich_replaces_pointers_and_marks_measured():
    entry = make_candidate(themes=[emit.THEME_POINTER_UNCERTAIN],
                           )  # pointer-uncertain: highest priority
    entry["reasons"].append(emit.REASON_POINTER_UNCERTAIN)
    fetcher = FakeFetcher({"https://api.example.com/v1/7": (200, SAMPLE_JSON)})
    summary = enrich.enrich([entry], budget=10, fetcher=fetcher)
    assert summary["requests"] == 1
    assert summary["measured"] == 1
    assert entry["measured"] is True
    assert entry["rule"]["actions"][0]["pointers"] == \
        [h["pointer"] for h in enrich.measured_pointers(__import__("json").loads(SAMPLE_JSON))]
    assert emit.THEME_MEASURED in entry["themes"]
    assert all("pointer-uncertain" not in t for t in entry["themes"])
    assert emit.REASON_POINTER_UNCERTAIN not in entry["reasons"]
    assert validate.validate_rule(entry["rule"]) == []
    assert entry["source"]["measured"]["fetch_host"] == "api.example.com"


def test_enrich_non_json_response_leaves_candidate_untouched():
    entry = make_candidate()
    fetcher = FakeFetcher({"https://api.example.com/v1/7": (200, "<html>nope</html>")})
    summary = enrich.enrich([entry], budget=10, fetcher=fetcher)
    assert summary["measured"] == 0
    assert "measured" not in entry
    assert entry["rule"]["actions"][0]["pointers"] == ["url"]


def test_enrich_http_error_skips_candidate():
    entry = make_candidate()
    fetcher = FakeFetcher({})
    summary = enrich.enrich([entry], budget=10, fetcher=fetcher)
    assert summary["measured"] == 0
    assert "measured" not in entry


def test_enrich_budget_is_respected():
    entries = [make_candidate() for _ in range(5)]
    for i, entry in enumerate(entries):
        entry["rule"]["fetch"][0]["url"] = "https://api.example.com/v1/{{match.id}}"
        entry["source"]["test_url"] = "https://example.com/watch/%d" % i
    fetcher = FakeFetcher({
        "https://api.example.com/v1/%d" % i: (200, SAMPLE_JSON) for i in range(5)})
    summary = enrich.enrich(entries, budget=3, fetcher=fetcher)
    assert summary["requests"] == 3
    assert len(fetcher.calls) == 3


# ------------------------------------------------ provisional http upgrades
def make_provisional():
    entry = make_candidate()
    entry["rule"]["fetch"][0]["url"] = "https://api.example.com/v1/{{match.id}}"
    entry["themes"].append(emit.THEME_HTTP_UPGRADE)
    entry["reasons"].append(emit.REASON_HTTP_UNVERIFIED)
    return entry


def test_provisional_http_promoted_only_when_measured():
    ok = make_provisional()
    ok["source"]["test_url"] = "https://example.com/watch/7"
    bad = make_provisional()
    bad["source"]["test_url"] = "https://example.com/watch/8"
    candidates: list[dict] = []
    fetcher = FakeFetcher({"https://api.example.com/v1/7": (200, SAMPLE_JSON)})
    summary = enrich.enrich(candidates, provisional=[ok, bad], budget=10, fetcher=fetcher)
    assert summary["promoted_provisional"] == 1
    assert len(candidates) == 1
    assert candidates[0] is ok
    assert ok["measured"] is True
    assert emit.REASON_HTTP_UNVERIFIED not in ok["reasons"]
    assert [e["rule"]["id"] for e in summary["failed_provisional_entries"]] == ["ytdlp-sample"]


def test_provisional_unrenderable_template_fails_closed():
    entry = make_provisional()
    entry["source"]["test_url"] = None  # nothing to render against
    candidates: list[dict] = []
    fetcher = FakeFetcher({})
    summary = enrich.enrich(candidates, provisional=[entry], budget=10, fetcher=fetcher)
    assert summary["promoted_provisional"] == 0
    assert summary["requests"] == 0
    assert len(summary["failed_provisional_entries"]) == 1

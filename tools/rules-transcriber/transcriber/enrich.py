"""Live enrichment pass (T108 root cause 1): turn pointer guessing into
measurement.

For candidates whose fetch template binds against the extractor's own test
URL, fetch the API endpoint (curl, hard request budget) and run a generic
recursive URL+quality walk over the actual JSON — the transcriber-side
counterpart of the app's PlayerConfigParser generic walk — then replace the
AST-synthesized jsonExtract pointers with VERIFIED ones (measured=true).

The same pass admits provisional http->https endpoint upgrades: they only stay
in the pool when the https endpoint serves parseable JSON with URL values.

Files written by the caller (enrich-log.json) may contain response hosts;
console/report output never does. stdlib + curl only.
"""

from __future__ import annotations

import json
import re
import subprocess
from urllib.parse import urlparse

from . import emit, validate

DEFAULT_BUDGET = 80
FETCH_TIMEOUT_S = 12
MAX_BODY_BYTES = 2 * 1024 * 1024
MAX_WALK_DEPTH = 6
MAX_LIST_PROBES = 4
MAX_POINTER_RESULTS = validate.MAX_JSON_POINTERS
USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")

MEDIA_EXT_RE = re.compile(
    r"\.(?:m3u8|mpd|mp4|m4v|webm|mov|mkv|flv|ts|m4s|mp3|m4a|aac|ogg|opus|wav)"
    r"(?:[?#]|$)", re.IGNORECASE)
URL_KEY_RE = re.compile(
    r"(?:url|file|manifest|src|source|stream|playback|content|media|video|audio|link)", re.IGNORECASE)
# keys that carry a URL but never a playable media address (logos, artwork,
# social/share links, site chrome); kept out of measured pointers because the
# engine would surface them as bogus candidates
NEGATIVE_KEY_RE = re.compile(
    r"(?:logo|icon|thumb|poster|avatar|image|share|social|website|homepage"
    r"|schedule|subtitle|caption|tracklist|playlist_url)", re.IGNORECASE)
QUALITY_KEYS = ("height", "width", "quality", "label", "resolution",
                "bitrate", "tbr", "abr", "fps", "format", "level")
KEY_SEG_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_-]*\Z")

KIND_BY_EXT = {
    "m3u8": "hls", "mpd": "dash",
}


def curl_fetch(url: str, timeout: int = FETCH_TIMEOUT_S) -> tuple[int | None, str]:
    """GET via curl; returns (http_code, body). Code None on transport failure."""
    try:
        proc = subprocess.run(
            ["curl", "-sS", "-L", "--max-redirs", "4", "--max-time", str(timeout),
             "--max-filesize", str(MAX_BODY_BYTES), "-A", USER_AGENT,
             "-H", "Accept: application/json, text/plain, */*",
             "-w", "\n__HTTP_CODE__%{http_code}", url],
            capture_output=True, timeout=timeout + 8,
        )
    except (subprocess.TimeoutExpired, OSError):
        return None, ""
    body = proc.stdout.decode("utf-8", errors="replace")
    marker = body.rfind("\n__HTTP_CODE__")
    if marker < 0:
        return None, ""
    code_text = body[marker + len("\n__HTTP_CODE__"):].strip()
    body = body[:marker]
    code = int(code_text) if code_text.isdigit() else None
    return code, body


# ------------------------------------------------------ generic JSON walk

def _kind_for(value: str) -> str:
    m = MEDIA_EXT_RE.search(value)
    if not m:
        return "url"
    ext = m.group(0)[1:].split(".")[0].lower()
    return KIND_BY_EXT.get(ext, "direct")


def _quality_of(container: dict) -> str | None:
    for key in QUALITY_KEYS:
        value = container.get(key)
        if isinstance(value, bool):
            continue
        if isinstance(value, (int, float, str)) and str(value).strip():
            return "%s=%s" % (key, value)
    return None


def _is_media_url(key: str, value: str) -> bool:
    if not value.startswith(("http://", "https://")):
        return False
    if len(value) > 2048 or " " in value:
        return False
    return bool(MEDIA_EXT_RE.search(value)) or bool(URL_KEY_RE.search(key))


def _walk(node, parts: list[str], out: list[dict], seen: set[str], depth: int) -> None:
    if depth > MAX_WALK_DEPTH or len(out) >= 32:
        return
    if isinstance(node, dict):
        for key, value in node.items():
            if not KEY_SEG_RE.match(str(key)):
                continue
            if isinstance(value, str) and _is_media_url(str(key), value):
                pointer = ".".join(parts + [str(key)])
                if pointer in seen or not validate.json_pointer_is_valid(pointer):
                    continue
                seen.add(pointer)
                out.append({
                    "pointer": pointer,
                    "kind": _kind_for(value),
                    "quality": _quality_of(node),
                    "url": value,
                })
            elif isinstance(value, (dict, list)):
                _walk(value, parts + [str(key)], out, seen, depth + 1)
    elif isinstance(node, list):
        for index, value in enumerate(node[:MAX_LIST_PROBES]):
            if not parts:
                continue  # root array: no key to index off
            indexed = parts[:-1] + ["%s[%d]" % (parts[-1], index)]
            if isinstance(value, str) and MEDIA_EXT_RE.search(value) and value.startswith(("http://", "https://")):
                pointer = ".".join(indexed)
                if pointer not in seen and validate.json_pointer_is_valid(pointer):
                    seen.add(pointer)
                    out.append({"pointer": pointer, "kind": _kind_for(value),
                                "quality": None, "url": value})
            elif isinstance(value, (dict, list)):
                _walk(value, indexed, out, seen, depth + 1)


def _rank(hit: dict) -> tuple[int, int]:
    key = hit["pointer"].split(".")[-1].split("[")[0]
    ext = bool(MEDIA_EXT_RE.search(hit["url"]))
    keyish = bool(URL_KEY_RE.search(key))
    if ext and keyish:
        rank = 0
    elif ext:
        rank = 1
    elif keyish:
        rank = 2
    else:
        rank = 3
    return rank, hit["pointer"].count(".") + hit["pointer"].count("[")


def pointer_is_media_pointer(pointer: str) -> bool:
    """A measured pointer must not key on artwork/site-chrome URL fields."""
    return not NEGATIVE_KEY_RE.search(pointer)


def measured_pointers(payload) -> list[dict]:
    """Verified pointer list for a parsed JSON payload (best first, capped)."""
    out: list[dict] = []
    _walk(payload, [], out, set(), 0)
    out = [hit for hit in out if pointer_is_media_pointer(hit["pointer"])]
    out.sort(key=_rank)
    return out[:MAX_POINTER_RESULTS]


# ------------------------------------------------------ enrichment driver

def _rendered_fetch_url(entry: dict) -> str | None:
    rule = entry["rule"]
    test_url = (entry.get("source") or {}).get("test_url")
    if not test_url:
        return None
    spec = rule.get("fetch", [None])[0]
    if not spec:
        return None
    rendered = emit.render_template(spec.get("url", ""), test_url, rule.get("match", {}).get("path"))
    if rendered is None or not rendered.lower().startswith("https://"):
        return None
    return rendered


def enrich(candidates: list[dict], provisional: list[dict] | None = None,
           budget: int = DEFAULT_BUDGET, fetcher=curl_fetch) -> dict:
    """In-place enrichment. Returns a summary dict; per-attempt details go to
    summary['log'] for the caller to persist (files may contain hosts)."""
    provisional = provisional or []
    log: list[dict] = []
    used = 0
    measured = 0
    promoted = 0
    failed_provisional: list[dict] = []

    # priority: provisional http upgrades (they gate inclusion) > pointer-
    # uncertain candidates > nested/loop pointers > remaining jsonExtract
    def priority(entry: dict) -> int:
        themes = entry.get("themes") or []
        if any("pointer-uncertain" in t for t in themes):
            return 1
        if any("nested pointer" in t or "array traversal" in t for t in themes):
            return 2
        return 3

    ordered = [(entry, True) for entry in provisional]
    ordered += sorted(
        ((entry, False) for entry in candidates
         if entry["rule"]["actions"][0].get("type") == "jsonExtract"),
        key=lambda pair: priority(pair[0]),
    )

    for entry, is_provisional in ordered:
        if used >= budget:
            break
        rule = entry["rule"]
        url = _rendered_fetch_url(entry)
        if url is None:
            if is_provisional:
                failed_provisional.append(entry)
            continue
        host = urlparse(url).hostname or ""
        used += 1
        code, body = fetcher(url)
        attempt = {
            "module": entry["source"].get("module"),
            "class": entry["source"].get("class"),
            "rule_id": rule.get("id"),
            "host": host,
            "status": code,
            "provisional_http": is_provisional,
        }
        pointers: list[dict] = []
        if code == 200 and body:
            try:
                payload = json.loads(body)
            except ValueError:
                payload = None
            if payload is not None:
                pointers = measured_pointers(payload)
        if pointers:
            attempt["outcome"] = "measured"
            measured += 1
            _apply_measured(entry, url, host, code, pointers)
            if is_provisional:
                candidates.append(entry)
                promoted += 1
        else:
            attempt["outcome"] = ("no-media-urls" if code == 200 else
                                  "http-%s" % code if code else "transport-error")
            if is_provisional:
                failed_provisional.append(entry)
        log.append(attempt)

    return {
        "requests": used,
        "budget": budget,
        "measured": measured,
        "promoted_provisional": promoted,
        "failed_provisional": [e["rule"]["id"] for e in failed_provisional],
        "failed_provisional_entries": failed_provisional,
        "log": log,
    }


def _apply_measured(entry: dict, url: str, host: str, code: int, pointers: list[dict]) -> None:
    action = entry["rule"]["actions"][0]
    action["pointers"] = [hit["pointer"] for hit in pointers]
    entry["measured"] = True
    entry["source"]["measured"] = {
        "fetch_host": host,
        "status": code,
        "pointers": [
            {
                "pointer": hit["pointer"],
                "kind": hit["kind"],
                "quality": hit["quality"],
                "sample_host": (urlparse(hit["url"]).hostname or ""),
            }
            for hit in pointers
        ],
    }
    themes = entry.setdefault("themes", [])
    if emit.THEME_MEASURED not in themes:
        themes.append(emit.THEME_MEASURED)
    entry["themes"] = [t for t in themes if "pointer-uncertain" not in t]
    entry["reasons"] = [r for r in entry.get("reasons", [])
                        if r not in (emit.REASON_POINTER_UNCERTAIN, emit.REASON_HTTP_UNVERIFIED)]
    entry["reasons"].append("pointers measured against live endpoint (still needs behavior review)")

"""Port of the PureBrowser rule-loader's load-time validators (T99).

Transliterated from app/src/main/java/com/example/purebrowser/media/rules/RuleData.kt
(parseRule / parseFetch / parseAction / RuleTemplatePolicy / JsonPointerPolicy /
RegexGuard) and RegistrableDomains.kt (RegexSourceScan, registrable-domain helpers),
bug-compatible by design so a candidate that passes here loads there.

Kotlin ``Regex`` is java.util.regex; we use Python ``re`` as a conservative compile
proxy (normalize.py already rejects Python-only/Java-only divergences).

stdlib only.
"""

from __future__ import annotations

import re

# ---------------------------------------------------------------- constants
SCHEMA_FETCH_VERSION = 3
MAX_RULES = 64
MAX_PATTERN_LENGTH = 512
MAX_ACTIONS_PER_RULE = 4
MAX_FILE_BYTES = 64 * 1024
MAX_CAPTURE_ENDPOINTS_PER_RULE = 4
MAX_CAPTURE_ENDPOINTS = 32
MAX_RULES_V3 = 2048
MAX_FILE_BYTES_V3 = 512 * 1024
MAX_COMPILED_REGEXES = 4096
MAX_FETCHES_PER_RULE = 4
MAX_FETCH_BYTES = 262_144
MAX_SESSION_HOSTS = 4
MAX_JSON_POINTERS = 8
MAX_OG_PROPERTIES = 8
MAX_REGEX_GROUP_NAMES = 4
MAX_REGEX_PATTERN_LENGTH = 256
MAX_TEMPLATE_LENGTH = 512
MAX_PLACEHOLDERS = 4
MAX_ID_LENGTH = 64
MAX_NOTE_LENGTH = 180

_PLACEHOLDER_RE = re.compile(r"\{\{([^{}]{1,64})\}\}")
_MATCH_NAME_RE = re.compile(r"match\.([A-Za-z][A-Za-z0-9_]*)\Z")
_JSON_SEGMENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_-]*(\[[0-9]{1,3}\])?\Z")
_JSON_FULL_RE = re.compile(
    r"[A-Za-z_][A-Za-z0-9_-]*(\[[0-9]{1,3}\])?(\.[A-Za-z_][A-Za-z0-9_-]*(\[[0-9]{1,3}\])?)*\Z"
)


# ------------------------------------------------- RegexSourceScan (ported)
def capturing_group_count(pattern: str) -> int:
    count = 0
    i = 0
    while i < len(pattern):
        c = pattern[i]
        if c == "\\":
            i += 1
        elif c == "[":
            close = pattern.find("]", i + 1)
            i = len(pattern) if close < 0 else close
        elif c == "(":
            if _is_capturing_open(pattern, i):
                count += 1
        i += 1
    return count


def named_group_names(pattern: str) -> set[str]:
    out: set[str] = set()
    i = 0
    while i < len(pattern):
        c = pattern[i]
        if c == "\\":
            i += 1
        elif c == "[":
            close = pattern.find("]", i + 1)
            i = len(pattern) if close < 0 else close
        elif c == "(" and pattern.startswith("(?<", i):
            name = _name_after(pattern, i + 3)
            if name is not None:
                out.add(name)
        i += 1
    return out


def _name_after(pattern: str, start: int) -> str | None:
    j = start
    while j < len(pattern) and pattern[j] != ">":
        j += 1
    if j == start or j >= len(pattern):
        return None
    name = pattern[start:j]
    return name if re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", name) else None


def _is_capturing_open(pattern: str, open_: int) -> bool:
    nxt = pattern[open_ + 1] if open_ + 1 < len(pattern) else None
    if nxt is None:
        return True
    if nxt != "?":
        return True
    after = pattern[open_ + 2] if open_ + 2 < len(pattern) else None
    if after != "<":
        return False
    fourth = pattern[open_ + 3] if open_ + 3 < len(pattern) else None
    return fourth is not None and fourth not in ("=", "!")


# ------------------------------------------------------ RegexGuard (ported)
def python_check_pattern(pattern: str) -> str:
    """Java-style `(?<name>` -> Python `(?P<name>` outside character classes.

    Used ONLY to make Python's `re` a workable compile proxy for the Kotlin
    (java.util.regex) patterns our rules carry.
    """
    out: list[str] = []
    i, n = 0, len(pattern)
    while i < n:
        c = pattern[i]
        if c == "\\" and i + 1 < n:
            out.append(pattern[i : i + 2])
            i += 2
            continue
        if c == "[":
            close = pattern.find("]", i + 1)
            end = n if close < 0 else close + 1
            out.append(pattern[i:end])
            i = end
            continue
        if pattern.startswith("(?<", i):
            j = i + 3
            while j < n and pattern[j] != ">":
                j += 1
            if j < n and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", pattern[i + 3 : j]):
                out.append("(?P<%s>" % pattern[i + 3 : j])
                i = j + 1
                continue
        out.append(c)
        i += 1
    return "".join(out)


def regex_compiles(pattern: str) -> bool:
    try:
        re.compile(python_check_pattern(pattern))
        return True
    except re.error:
        return False


def regex_guard_is_safe(pattern: str) -> bool:
    return regex_compiles(pattern) and _no_nested_unbounded_quantifier(pattern)


def _has_unbounded_quantifier(source: str) -> bool:
    i = 0
    while i < len(source):
        c = source[i]
        if c == "\\":
            i += 2
        elif c == "[":
            close = source.find("]", i)
            i = len(source) if close < 0 else close
        elif c in ("*", "+"):
            return True
        elif c == "{":
            close = source.find("}", i)
            if close < 0:
                return True
            if source[i + 1 : close].endswith(","):
                return True
            i = close
        i += 1
    return False


def _quantifier_at(pattern: str, index: int) -> bool:
    c = pattern[index] if 0 <= index < len(pattern) else None
    if c is None:
        return False
    if c in ("*", "+"):
        return True
    if c != "{":
        return False
    close = pattern.find("}", index)
    if close < 0:
        return True
    return pattern[index + 1 : close].endswith(",")


def _no_nested_unbounded_quantifier(pattern: str) -> bool:
    i = 0
    while i < len(pattern):
        c = pattern[i]
        if c == "\\":
            i += 2
        elif c == "[":
            close = pattern.find("]", i + 1)
            i = len(pattern) if close < 0 else close
        elif c == "(":
            depth = 0
            j = i
            close = -1
            while j < len(pattern):
                cj = pattern[j]
                if cj == "\\":
                    j += 1
                elif cj == "[":
                    cc = pattern.find("]", j)
                    j = len(pattern) if cc < 0 else cc
                elif cj == "(":
                    depth += 1
                elif cj == ")":
                    depth -= 1
                    if depth == 0:
                        close = j
                        break
                j += 1
            if close < 0:
                return True  # malformed tail: compile check already rejected it
            inner = pattern[i + 1 : close]
            if _quantifier_at(pattern, close + 1) and _has_unbounded_quantifier(inner):
                return False
            i = close
        i += 1
    return True


# ------------------------------------------------- JsonPointerPolicy (ported)
def json_pointer_is_valid(pointer: str) -> bool:
    if not (1 <= len(pointer) <= 64):
        return False
    if not _JSON_FULL_RE.match(pointer):
        return False
    return all(_JSON_SEGMENT_RE.match(seg) for seg in pointer.split("."))


# ------------------------------------------- RuleTemplatePolicy (ported)
def validate_placeholders(template: str, path: str | None) -> bool:
    if len(template) > MAX_TEMPLATE_LENGTH:
        return False
    found = _PLACEHOLDER_RE.findall(template)
    if len(found) > MAX_PLACEHOLDERS:
        return False
    remainder = _PLACEHOLDER_RE.sub("", template)
    if "{{" in remainder or "}}" in remainder:
        return False
    group_count = capturing_group_count(path) if path else 0
    names = named_group_names(path) if path else set()
    for token in found:
        token = token.strip()
        if token == "pageUrl":
            continue
        if len(token) == 2 and token[0] == "m" and "1" <= token[1] <= "9":
            if int(token[1]) > group_count:
                return False
        else:
            m = _MATCH_NAME_RE.match(token)
            if not m:
                return False
            if m.group(1) not in names:
                return False
    return True


# ------------------------------------------ RegistrableDomains (ported)
_MULTI_LABEL_SUFFIXES = {
    "co.uk", "org.uk", "ac.uk", "gov.uk",
    "com.au", "net.au", "org.au",
    "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
    "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
    "com.br", "com.mx", "com.tr", "com.sg", "com.hk", "com.ar", "com.pl", "com.ua",
    "co.kr", "co.in", "co.nz", "co.za",
}
_LABEL_RE = re.compile(r"[a-z0-9]([a-z0-9-]*[a-z0-9])?\Z")
_PLAIN_HOST_RE = re.compile(r"([a-z0-9-]+\.)+[a-z]{2,}\Z")


def is_plain_host(host: str) -> bool:
    value = host.lower()
    if not (4 <= len(value) <= 253) or value.endswith(".") or value.startswith("."):
        return False
    return bool(_PLAIN_HOST_RE.match(value))


def registrable_domain(host: str) -> str | None:
    value = host.lower().rstrip(".")
    if not is_plain_host(value):
        return None
    labels = value.split(".")
    if any(not _LABEL_RE.match(lb) for lb in labels):
        return None
    last_two = ".".join(labels[-2:])
    if last_two in _MULTI_LABEL_SUFFIXES:
        return ".".join(labels[-3:])
    return last_two


# ------------------------------------------------------ rule validation
def validate_rule(rule: dict) -> list[str]:
    """One schema-v3 rule as plain JSON data; [] when it would load cleanly."""
    reasons: list[str] = []

    def reject(reason: str) -> None:
        if reason not in reasons:
            reasons.append(reason)

    rid = rule.get("id")
    if not isinstance(rid, str) or not rid.strip() or len(rid) > MAX_ID_LENGTH:
        reject("id missing/overlong")
        return reasons
    version = rule.get("version")
    if not isinstance(version, int) or version < 1:
        reject("bad version")
    match_raw = rule.get("match")
    if not isinstance(match_raw, dict):
        reject("match missing")
        return reasons
    hosts = _pattern_text(match_raw.get("hosts"))
    path = _pattern_text(match_raw.get("path"))
    if hosts is None and path is None:
        reject("no match face")
        return reasons
    budget = 0
    if hosts is not None:
        if not _compiles(hosts, re.IGNORECASE):
            reject("hosts pattern fails to compile")
            return reasons
        budget += 1
    if path is not None:
        if not _compiles(path, re.IGNORECASE):
            reject("path pattern fails to compile")
            return reasons
        budget += 1

    actions_raw = rule.get("actions")
    if not isinstance(actions_raw, list) or not actions_raw:
        reject("no actions")
        return reasons
    if len(actions_raw) > MAX_ACTIONS_PER_RULE:
        reject("too many actions")
    seen_action_kinds: set[str] = set()
    for raw in actions_raw:
        if len(seen_action_kinds) >= MAX_ACTIONS_PER_RULE:
            break
        if not isinstance(raw, dict):
            reject("malformed action")
            continue
        kind = raw.get("type")
        if kind == "jsonExtract":
            pointers = _string_list(raw.get("pointers"), MAX_JSON_POINTERS, json_pointer_is_valid)
            if pointers is None or not pointers:
                reject("jsonExtract pointers invalid")
            seen_action_kinds.add(kind)
        elif kind == "regexExtract":
            pattern = raw.get("pattern")
            if not isinstance(pattern, str) or not pattern.strip() or len(pattern) > MAX_REGEX_PATTERN_LENGTH:
                reject("regexExtract pattern missing/overlong")
            elif not regex_guard_is_safe(pattern):
                reject("regexExtract pattern unsafe (RegexGuard)")
            else:
                names = raw.get("groupNames")
                if names is not None:
                    listed = _string_list(names, MAX_REGEX_GROUP_NAMES, lambda n: n in named_group_names(pattern))
                    if listed is None:
                        reject("regexExtract groupNames invalid")
                budget += 1
            seen_action_kinds.add(kind)
        elif kind in ("domExtract", "playerConfig", "manifestHint", "ogMeta", "parseManifest"):
            seen_action_kinds.add(kind)  # shape not produced by the transcriber; tolerated
        else:
            reject("unknown action type")

    if budget > MAX_COMPILED_REGEXES:
        reject("regex budget exceeded")

    note = rule.get("note")
    if note is not None and (not isinstance(note, str) or len(note) > MAX_NOTE_LENGTH):
        reject("note overlong")

    fetch_raw = rule.get("fetch")
    if fetch_raw is not None:
        fetch_reasons = _validate_fetch(fetch_raw, hosts, path)
        reasons.extend(fetch_reasons)
    return reasons


def _validate_fetch(fetch_raw, hosts: str | None, path: str | None) -> list[str]:
    reasons: list[str] = []
    if not isinstance(fetch_raw, list):
        return ["fetch block malformed"]
    if hosts is None:
        reasons.append("fetch without hosts face")
    ids: set[str] = set()
    if len(fetch_raw) > MAX_FETCHES_PER_RULE:
        reasons.append("too many fetch specs")
    for spec in fetch_raw:
        if not isinstance(spec, dict):
            reasons.append("fetch spec malformed")
            continue
        fid = spec.get("id")
        if not isinstance(fid, str) or not fid.strip() or len(fid) > MAX_ID_LENGTH:
            reasons.append("fetch id missing/overlong")
        elif fid in ids:
            reasons.append("duplicate fetch id")
        else:
            ids.add(fid)
        method = (spec.get("method") or "GET").upper()
        if method != "GET":
            reasons.append("non-GET fetch method")
        template = spec.get("url")
        if not isinstance(template, str) or not template.strip() or len(template) > MAX_TEMPLATE_LENGTH:
            reasons.append("fetch url missing/overlong")
            continue
        if not template.lower().startswith("https://"):
            reasons.append("fetch url not https")
        if not validate_placeholders(template, path):
            reasons.append("fetch placeholders invalid/unbound")
        max_bytes = spec.get("maxBytes")
        if not isinstance(max_bytes, int) or isinstance(max_bytes, bool) or not (0 < max_bytes <= MAX_FETCH_BYTES):
            reasons.append("fetch maxBytes missing/out of range")
    return reasons


def _pattern_text(value) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip()
    if not text or len(text) > MAX_PATTERN_LENGTH:
        return None
    return text


def _compiles(pattern: str, flags: int) -> bool:
    try:
        re.compile(python_check_pattern(pattern), flags)
        return True
    except re.error:
        return False


def _string_list(value, cap: int, check) -> list[str] | None:
    if not isinstance(value, list):
        return None
    out: list[str] = []
    for item in value:
        if len(out) >= cap:
            return None  # over the cap is malformed, not silently truncated
        if not isinstance(item, str):
            return None
        text = item.strip()
        if not text or not check(text):
            return None
        out.append(text)
    return out


def validate_document(rules: list[dict], schema_version: int = SCHEMA_FETCH_VERSION) -> list[str]:
    """Document-level caps: size, rule count, id uniqueness, aggregate regex budget."""
    reasons: list[str] = []
    max_rules = MAX_RULES_V3 if schema_version >= SCHEMA_FETCH_VERSION else MAX_RULES
    if len(rules) > max_rules:
        reasons.append("too many rules")
    ids: set[str] = set()
    regex_budget = 0
    for rule in rules:
        rid = rule.get("id")
        if isinstance(rid, str):
            if rid in ids:
                reasons.append("duplicate rule id")
            ids.add(rid)
        match_raw = rule.get("match") or {}
        regex_budget += sum(
            1 for face in ("hosts", "path") if isinstance(match_raw.get(face), str) and match_raw[face].strip()
        )
        for action in rule.get("actions") or []:
            if isinstance(action, dict) and action.get("type") == "regexExtract":
                regex_budget += 1
        regex_budget += len(rule.get("captureEndpoints") or [])
    if regex_budget > MAX_COMPILED_REGEXES:
        reasons.append("aggregate regex budget exceeded")
    text_len = sum(len(str(r)) for r in rules)
    if text_len > MAX_FILE_BYTES_V3:
        reasons.append("document size exceeds cap")
    return reasons

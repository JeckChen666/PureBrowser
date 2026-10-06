"""Port of the PureBrowser rule-loader's load-time validators (T99; T108 adds
the fetch.hosts extension semantics).

Transliterated from app/src/main/java/com/example/purebrowser/media/rules/RuleData.kt
(parseRule / parseFetch / parseAction / RuleTemplatePolicy / JsonPointerPolicy /
RegexGuard) and RegistrableDomains.kt (RegexSourceScan, registrable-domain helpers),
bug-compatible by design so a candidate that passes here loads there.

T108 extension ported ahead of the app-side wiring (v0.2.0 plan section 3):
a fetch spec may declare `hosts` (extra fetch domains); load-time contract:
every fetch URL host must satisfy registrable_domain(fetch_host) IN
{registrable domains of the match.hosts literals} UNION {declared entries};
IP literals / private hosts are always denied; importer-tier documents must
call validate_rule(..., allow_fetch_hosts_extension=False).

Kotlin ``Regex`` is java.util.regex; we use Python ``re`` as a conservative compile
proxy (normalize.py already rejects Python-only/Java-only divergences).

stdlib only.
"""

from __future__ import annotations

import ipaddress
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
MAX_FETCH_HOSTS = 4

_PLACEHOLDER_RE = re.compile(r"\{\{([^{}]{1,64})\}\}")
_MATCH_NAME_RE = re.compile(r"match\.([A-Za-z][A-Za-z0-9_]*)\Z")
_JSON_SEGMENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_-]*(\[[0-9]{1,3}\])?\Z")
_JSON_FULL_RE = re.compile(
    r"[A-Za-z_][A-Za-z0-9_-]*(\[[0-9]{1,3}\])?(\.[A-Za-z_][A-Za-z0-9_-]*(\[[0-9]{1,3}\])?)*\Z"
)


# -------------------------------------------------- token scan (shared)
def scan_tokens(pattern: str) -> list[tuple[int, str, bool, bool]]:
    """(index, char, in_class, escaped) for every char, class- and escape-aware.

    ``[``-class contents are marked in_class=True so metacharacter scans can skip
    them; a leading ``]`` inside a class is literal; POSIX ``[:alpha:]`` is not
    special-cased (Python compile rejects those patterns anyway).
    """
    out: list[tuple[int, str, bool, bool]] = []
    i, n = 0, len(pattern)
    in_class = False
    while i < n:
        c = pattern[i]
        if c == "\\" and i + 1 < n:
            out.append((i, c, in_class, True))
            out.append((i + 1, pattern[i + 1], in_class, True))
            i += 2
            continue
        if not in_class and c == "[":
            in_class = True
            out.append((i, c, False, False))
            i += 1
            if i < n and pattern[i] == "^":
                out.append((i, pattern[i], True, False))
                i += 1
            if i < n and pattern[i] == "]":  # leading ] is literal inside a class
                out.append((i, pattern[i], True, False))
                i += 1
            continue
        if in_class and c == "]":
            in_class = False
            out.append((i, c, True, False))
            i += 1
            continue
        out.append((i, c, in_class, False))
        i += 1
    return out


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


# ------------------------------- hosts-face domain extraction (T108 upgrade)
_DOMAIN_COMBO_CAP = 64
_DOMAIN_LABEL_RE = re.compile(r"[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\Z")

# lexed pieces of a hosts-face pattern
_LIT, _GRP_OPEN, _GRP_CLOSE, _ALT, _CLS, _OPT = "lit", "(", ")", "|", "[]", "?"
# escapes that denote whole character classes / boundaries, not literal chars
_CLASS_ESCAPES = set("wWdDsSbB")


def _lex_host(pattern: str) -> list[tuple[str, str]] | None:
    """Token stream for _host_alternatives: literals (escape pairs resolved),
    group parens with any (?prefix consumed, top-level markers for classes,
    alternations and quantifiers. None when the shape is not worth parsing."""
    out: list[tuple[str, str]] = []
    tokens = scan_tokens(pattern)
    by_index = {t[0]: t for t in tokens}
    i = 0
    n = len(pattern)
    while i < n:
        _idx, c, in_class_flag, escaped = by_index.get(i, (i, pattern[i], False, False))
        if escaped:
            payload = pattern[i + 1] if pattern[i] == "\\" else pattern[i]
            if payload == "/":
                break  # escaped slash: host ends here, rest is path
            if payload in _CLASS_ESCAPES:
                out.append((_CLS, ""))
            else:
                out.append((_LIT, payload))
            i += 2 if pattern[i] == "\\" else 1
            continue
        if in_class_flag or c == "[":
            close = pattern.find("]", i + 1)
            out.append((_CLS, ""))
            i = n if close < 0 else close + 1
            continue
        if c == "(":
            body = pattern[i + 1:]
            prefix = 0
            if body.startswith("?P<") or body.startswith("?<"):
                gt = body.find(">")
                if gt < 0:
                    return None
                prefix = gt + 1
            elif body.startswith("?:"):
                prefix = 2
            elif body.startswith("?=") or body.startswith("?!") or body.startswith("?<=") or body.startswith("?<!"):
                # lookaround: skip its (balanced) body entirely — transparent
                nesting = 0
                j = i
                skip_to = -1
                while j < n:
                    tok_j = by_index.get(j)
                    jc_raw = pattern[j]
                    if tok_j is not None and tok_j[3]:  # escaped
                        j += 2
                        continue
                    if jc_raw == "[":
                        cc = pattern.find("]", j)
                        j = n if cc < 0 else cc + 1
                        continue
                    if jc_raw == "(":
                        nesting += 1
                    elif jc_raw == ")":
                        nesting -= 1
                        if nesting == 0:
                            skip_to = j + 1
                            break
                    j += 1
                if skip_to < 0:
                    return None
                i = skip_to
                continue
            elif body.startswith("?"):
                return None  # flags/conditionals
            out.append((_GRP_OPEN, ""))
            i += 1 + prefix
            continue
        if c == ")":
            out.append((_GRP_CLOSE, ""))
            i += 1
            continue
        if c == "|":
            out.append((_ALT, ""))
            i += 1
            continue
        if c in ("^", "$"):
            i += 1
            continue  # anchors contribute nothing to domain literals
        if c in ("*", "+", "?"):
            out.append((_OPT, c))
            i += 1
            continue
        if c == "{":
            close = pattern.find("}", i)
            body_q = pattern[i + 1:close] if close >= 0 else ""
            digits = body_q.replace(" ", "")
            if close < 0 or not re.fullmatch(r"\d*(,\d*)?", digits):
                out.append((_LIT, c))
                i += 1
                continue
            out.append((_OPT, "*" if digits.startswith(",") or digits.endswith(",") else "+"))
            i = close + 1
            continue
        # literal run (alnum, '-' and raw '.' — hosts segments virtually always
        # escape dots, a raw '.' only widens)
        run: list[str] = []
        j = i
        while j < n:
            jidx, jc, jin_class, jesc = by_index.get(j, (j, pattern[j], False, False))
            if jesc:
                payload = pattern[j + 1] if pattern[j] == "\\" else pattern[j]
                if payload == "/":
                    break  # escaped slash ends the host face
                if payload in _CLASS_ESCAPES:
                    break  # class escape ends the literal run (transparent)
                run.append(payload)
                j += 2 if pattern[j] == "\\" else 1
                continue
            if jin_class or jc == "[":
                break
            if jc.isalnum() or jc in ("-", "."):
                run.append(jc)
                j += 1
                continue
            break
        if not run:
            run = [c]
            j = i + 1
        out.append((_LIT, "".join(run)))
        i = j
    return out


def _split_branches(tokens: list[tuple[str, str]]) -> list[list[tuple[str, str]]]:
    """Split a token stream on top-level alternation markers."""
    branches: list[list[tuple[str, str]]] = [[]]
    nesting = 0
    for tok in tokens:
        kind = tok[0]
        if kind == _GRP_OPEN:
            nesting += 1
        elif kind == _GRP_CLOSE:
            nesting -= 1
        elif kind == _ALT and nesting == 0:
            branches.append([])
            continue
        branches[-1].append(tok)
    return branches


def _alts_from_tokens(tokens: list[tuple[str, str]], depth: int) -> list[str] | None:
    """Alternatives (concrete literal strings) for a token stream; None=opaque."""
    if depth > 6:
        return None
    parts: list[list[str]] = []
    i = 0
    while i < len(tokens):
        kind, text = tokens[i]
        if kind == _LIT:
            parts.append([text])
            i += 1
            continue
        if kind == _CLS:
            parts.append([""])  # transparent under widening semantics
            i += 1
            continue
        if kind == _OPT:
            if parts:
                parts[-1] = sorted(set(parts[-1]) | {""})
            i += 1
            continue
        if kind == _GRP_OPEN:
            # collect until matching close
            nesting = 0
            j = i
            close = -1
            while j < len(tokens):
                if tokens[j][0] == _GRP_OPEN:
                    nesting += 1
                elif tokens[j][0] == _GRP_CLOSE:
                    nesting -= 1
                    if nesting == 0:
                        close = j
                        break
                j += 1
            if close < 0:
                i += 1  # unclosed group (face cut mid-group): transparent open
                continue
            alts: set[str] = set()
            opaque = False
            for branch in _split_branches(tokens[i + 1:close]):
                sub = _alts_from_tokens(branch, depth + 1)
                if sub is None:
                    opaque = True
                    continue
                alts.update(sub)
            if opaque:
                alts.add("")
            if close + 1 < len(tokens) and tokens[close + 1][0] == _OPT:
                alts.add("")
            parts.append(sorted(alts))
            i = close + 1
            if i < len(tokens) and tokens[i][0] == _OPT:
                i += 1
            continue
        if kind == _GRP_CLOSE:
            i += 1  # stray close paren: transparent (tolerated, old-scanner parity)
            continue
        if kind == _ALT:
            i += 1  # stray alternator at this nesting: skip its empty branch
            continue
        return None
    out: set[str] = {""}
    for part in parts:
        if not part:
            part = [""]
        # alternatives with characters no domain may contain (ports ':8080',
        # path-ish junk) are dropped rather than poisoning their siblings
        part = [alt if alt == "" or not re.search(r"[^A-Za-z0-9.\-]", alt) else "" for alt in part]
        part = sorted(set(part)) or [""]
        if len(out) * len(part) > _DOMAIN_COMBO_CAP:
            return None
        out = {a + b for a in out for b in part}
        if len(out) > _DOMAIN_COMBO_CAP:
            return None
    return sorted(out)


def pattern_domain_literals(pattern: str) -> list[str]:
    """Concrete dotted domains in a hosts-face pattern, with literal-label
    alternation expansion (T108 root cause 2). Order-preserving dedupe."""
    tokens = _lex_host(pattern)
    if tokens is None:
        return []
    alts: set[str] = set()
    for branch in _split_branches(tokens):
        sub = _alts_from_tokens(branch, 0)
        if sub is not None:
            alts.update(sub)
    seen: set[str] = set()
    out: list[str] = []
    for text in sorted(alts):
        text = text.strip(".")  # leading/trailing dots are face punctuation
        if not text:
            continue
        labels = text.split(".")
        if (
            len(labels) >= 2
            and labels[0]
            and all(_DOMAIN_LABEL_RE.match(lb) for lb in labels)
            and labels[-1].isalpha()
            and len(labels[-1]) >= 2
        ):
            low = text.lower()
            if low not in seen:
                seen.add(low)
                out.append(low)
    return out


# ---------------------------- fetch.hosts extension (T108 root cause 2)
_TEMPLATE_AUTHORITY_RE = re.compile(r"https://([^/?#\s]+)", re.IGNORECASE)


def template_literal_host(template: str) -> str | None:
    """Literal host of a fetch URL template; None when the authority carries a
    placeholder (runtime-resolved, cannot be statically whitelisted) or the
    template has no https literal prefix."""
    m = _TEMPLATE_AUTHORITY_RE.match(template)
    if not m:
        return None
    authority = m.group(1)
    if "{" in authority or "}" in authority:
        return None
    host = authority.rsplit("@", 1)[-1].split(":")[0]
    return host.lower() or None


def host_is_ip_literal(host: str) -> bool:
    try:
        ipaddress.ip_address(host.strip("[]"))
        return True
    except ValueError:
        return False


def host_is_private_or_link_local(host: str) -> bool:
    text = host.strip("[]")
    try:
        addr = ipaddress.ip_address(text)
    except ValueError:
        return host == "localhost" or "." not in host  # dotless intranet names
    return addr.is_private or addr.is_loopback or addr.is_link_local or addr.is_reserved


def _allowed_fetch_registrables(hosts_pattern: str | None, declared: list[str] | None) -> set[str] | None:
    """Registrable domains a fetch host may live under: the match face's
    registrable domains union the explicitly declared fetch.hosts entries.
    None when the face yields nothing usable."""
    allowed: set[str] = set()
    if hosts_pattern is not None:
        for domain in pattern_domain_literals(hosts_pattern):
            reg = registrable_domain(domain)
            if reg:
                allowed.add(reg)
    for extra in declared or []:
        reg = registrable_domain(extra)
        if reg:
            allowed.add(reg)
    return allowed or None


# ------------------------------------------------------ rule validation
def validate_rule(rule: dict, allow_fetch_hosts_extension: bool = True) -> list[str]:
    """One schema-v3 rule as plain JSON data; [] when it would load cleanly.

    ``allow_fetch_hosts_extension`` mirrors the app-side tier flag the
    orchestrator must wire: built-in rules may declare ``fetch.hosts`` extra
    fetch domains; importer-tier documents must not (reject on sight).
    """
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
        fetch_reasons = _validate_fetch(fetch_raw, hosts, path,
                                        allow_fetch_hosts_extension=allow_fetch_hosts_extension)
        reasons.extend(fetch_reasons)
    return reasons


def _validate_fetch(fetch_raw, hosts: str | None, path: str | None,
                    allow_fetch_hosts_extension: bool = True) -> list[str]:
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

        # ---- fetch.hosts extension + host membership (T108 root cause 2) ----
        declared_raw = spec.get("hosts")
        declared: list[str] | None = None
        if declared_raw is not None:
            if not allow_fetch_hosts_extension:
                reasons.append("fetch.hosts extension not permitted (importer tier)")
            elif not isinstance(declared_raw, list) or not (1 <= len(declared_raw) <= MAX_FETCH_HOSTS):
                reasons.append("fetch hosts malformed")
            else:
                declared = []
                for entry in declared_raw:
                    if not isinstance(entry, str) or not is_plain_host(entry):
                        reasons.append("fetch hosts malformed")
                        declared = None
                        break
                    if host_is_ip_literal(entry) or host_is_private_or_link_local(entry):
                        reasons.append("fetch host ip-literal/private denied")
                        declared = None
                        break
                    declared.append(entry)
        fetch_host = template_literal_host(template)
        if fetch_host is not None:
            if host_is_ip_literal(fetch_host) or host_is_private_or_link_local(fetch_host):
                reasons.append("fetch host ip-literal/private denied")
                continue
            allowed = _allowed_fetch_registrables(hosts, declared)
            fetch_reg = registrable_domain(fetch_host)
            if allowed is not None and fetch_reg is not None and fetch_reg not in allowed:
                reasons.append("fetch host outside match face and declared fetch hosts")
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

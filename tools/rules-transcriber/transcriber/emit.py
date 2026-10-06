"""v3 rule-candidate emission from SIMPLE-class extractor records.

Derives: hosts face (registrable-domain suffix match) from the scheme://host
alternation of the normalized _VALID_URL; path face (anchor-stripped remainder
with named groups); a controlled GET fetch template using {{m1..}}/{{match.<name>}}
placeholders; jsonExtract candidate point paths (heuristic, needs review) or a
regexExtract pattern taken from the extractor's own first _search_regex literal.

Reject reasons are STRUCTURAL text only (no site names) so REPORT.md can
aggregate them safely. stdlib only.
"""

from __future__ import annotations

import ast
import re
import urllib.parse
from dataclasses import dataclass, field

from . import analyzer, normalize, validate

MAX_HOST_DOMAINS = 8
FETCH_ID = "api"
FETCH_MAX_BYTES = 131_072
ID_RENAME = "id"

THEME_FORMATS = "formats-array traversal"
THEME_POSITIONAL = "positional group placeholder"
THEME_WEBPAGE_REGEX = "webpage body regex extraction"
THEME_CONSTANT_URL = "constant fetch URL (no path capture)"
THEME_WIDENED_HOSTS = "hosts face widened to registrable domain"
THEME_HEADERS = "upstream headers not expressible"
THEME_RENAMED = "python group renamed for java"
THEME_DEFAULT_POINTER = "default url pointer"
THEME_JSON_HEURISTIC = "json pointer heuristics"
THEME_POINTER_UNCERTAIN = "pointer-uncertain (no AST-visible response access)"
THEME_POINTER_DEPTH = "nested pointer synthesized from AST (depth > 1)"
THEME_LOOP_CONCRETIZED = "array traversal concretized to [0] (loop over response array)"
THEME_FETCH_HOSTS = "cross-origin fetch host declared via fetch.hosts extension"
THEME_URL_TRANSFORM = "fetch url transform approximated (runtime string op dropped)"
THEME_HTTP_UPGRADE = "http endpoint upgraded to https (requires live verification)"
THEME_MEASURED = "pointers measured from live API response"
THEME_ALT_HOSTS = "url pattern alternation split into merged faces"
REASON_POINTER_UNCERTAIN = "pointer-uncertain"
REASON_HTTP_UNVERIFIED = "http-endpoint-upgrade-needs-live-verification"


@dataclass
class Candidate:
    rule: dict
    needs_review: bool = True
    reasons: list[str] = field(default_factory=list)
    themes: list[str] = field(default_factory=list)
    source: dict = field(default_factory=dict)
    provisional_http: bool = False


def slug(ie_name: str, used: set[str]) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", ie_name.lower()).strip("-")[:50] or "extractor"
    out = base
    n = 2
    while out in used:
        out = "%s-%d" % (base, n)
        n += 1
    used.add(out)
    return "ytdlp-" + out


# ------------------------------------------------------------- face split

def _outside_seq(pattern: str) -> list[tuple[int, str]]:
    return [(i, c) for i, c, in_class, escaped in normalize._tokens(pattern) if not in_class and not escaped]


def split_faces(pattern: str) -> tuple[str, str, str]:
    """(host_segment, path_segment, error). Splits on '://' then the first
    path-ish delimiter outside character classes."""
    outside = _outside_seq(pattern)
    sep_idx = -1
    for i, c in outside:
        if c == ":" and pattern[i : i + 3] == "://":
            sep_idx = i
            break
    if sep_idx < 0:
        return "", "", "no scheme separator"
    host_start = sep_idx + 3
    host_end = len(pattern)
    # A literal '?' or ';' in a matched URL always appears escaped (\?) or as a
    # plain ';' in the pattern; a RAW '?' outside classes is regex syntax
    # ((?:, quantifier), so only '/', '#' and ';' end the host segment.
    for i, c in outside:
        if i >= host_start and c in "/#;":
            host_end = i
            break
    return pattern[host_start:host_end], pattern[host_end:], ""


# ---------------------------------------------- alternation-aware splitting
def _depth0_bar_positions(text: str) -> list[int]:
    """Top-level '|' positions (outside classes/escapes, depth-0 groups)."""
    out: list[int] = []
    depth = 0
    for i, c, in_class, escaped in normalize._tokens(text):
        if in_class or escaped:
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "|" and depth == 0:
            out.append(i)
    return out


def _scheme_sep_at(text: str) -> int:
    for i, c, in_class, escaped in normalize._tokens(text):
        if not in_class and not escaped and c == ":" and text[i : i + 3] == "://":
            return i
    return -1


def _outer_group(text: str) -> tuple[str, str] | None:
    """(inner, tail) when text is ONE balanced group wrapping (nearly) all of
    itself; only anchor/slash-only tails are tolerated."""
    if not text.startswith("("):
        return None
    depth = 0
    for i, c, in_class, escaped in normalize._tokens(text):
        if in_class or escaped:
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                tail = text[i + 1:]
                if tail and not re.fullmatch(r"[/?^$]*", tail):
                    return None  # partial-prefix group: not a branch wrapper
                inner = text[1:i]
                if inner.startswith("?:"):
                    inner = inner[2:]
                elif inner.startswith("?<") or inner.startswith("?P<"):
                    gt = inner.find(">")
                    if gt < 0:
                        return None
                    inner = inner[gt + 1:]
                elif inner.startswith("?"):
                    return None  # lookaround/flags wrapper: do not unwrap
                return inner, tail
    return None


def _url_branches(text: str, depth: int = 0) -> list[str]:
    """Alternation branches of a post-scheme URL pattern segment."""
    if depth > 6:
        return [text]
    sep = _scheme_sep_at(text)
    if sep >= 0:
        return _url_branches(text[sep + 3:], depth + 1)
    bars = _depth0_bar_positions(text)
    if bars:
        parts: list[str] = []
        start = 0
        for pos in bars:
            parts.extend(_url_branches(text[start:pos], depth + 1))
            start = pos + 1
        parts.extend(_url_branches(text[start:], depth + 1))
        return parts
    unwrapped = _outer_group(text)
    if unwrapped is not None:
        inner, tail = unwrapped
        return [branch + tail for branch in _url_branches(inner, depth + 1)]
    return [text]


def split_url_pattern(pattern: str) -> tuple[list[tuple[str, str]], str]:
    """[(host_segment, path_segment), ...] per alternation branch, error.

    Handles the yt-dlp idiom of one scheme prefix followed by an alternation of
    complete host+path branches (T108: the old first-'/' cut corrupted path
    faces of such patterns and dropped concrete domains in later branches)."""
    sep_idx = _scheme_sep_at(pattern)
    if sep_idx < 0:
        return [], "no scheme separator"
    rest = pattern[sep_idx + 3:]
    if not rest:
        return [], "empty host segment"
    out: list[tuple[str, str]] = []
    for branch in _url_branches(rest):
        outside = _outside_seq(branch)
        host_end = len(branch)
        for i, c in outside:
            if c in "/#;":
                host_end = i
                break
        if branch[:host_end]:
            out.append((branch[:host_end], branch[host_end:]))
    if not out:
        return [], "empty host segment"
    return out, ""


def domain_literals(host_seg: str) -> list[str]:
    """Concrete dotted domains, with literal-label alternation expansion
    (T108 root cause 2). Canonical implementation: validate.pattern_domain_literals."""
    return validate.pattern_domain_literals(host_seg)


def registrable_domains(host_seg: str) -> list[str]:
    regs: list[str] = []
    seen: set[str] = set()
    for domain in domain_literals(host_seg):
        reg = validate.registrable_domain(domain)
        if reg and reg not in seen:
            seen.add(reg)
            regs.append(reg)
    return regs[:MAX_HOST_DOMAINS]


def hosts_face(regs: list[str]) -> str:
    if len(regs) == 1:
        return r"(^|\.)" + re.escape(regs[0]) + "$"
    return r"(^|\.)(?:" + "|".join(re.escape(r) for r in regs) + ")$"


def strip_anchors(path_src: str) -> str:
    out = path_src
    if out.startswith("^"):
        out = out[1:]
    if out.endswith("$") and not out.endswith(r"\$"):
        out = out[:-1]
    return out


def rename_first_unnamed(path_src: str, name: str) -> str | None:
    """Rename the first capturing group to (?<name>...); None when none exists."""
    for i, c in _outside_seq(path_src):
        if c != "(":
            continue
        nxt = path_src[i + 1 : i + 2]
        if nxt != "?":
            return path_src[:i] + "(?<%s>" % name + path_src[i + 1 :]
        after = path_src[i + 2 : i + 3]
        if after == "<":
            fourth = path_src[i + 3 : i + 4]
            if fourth not in ("=", "!"):
                return None  # already named
        continue  # (?: (?= (?! (?<= (?<! — non-capturing
    return None


# ---------------------------------------------------------- template engine

@dataclass
class TemplateCtx:
    rec: analyzer.ExtractorRecord
    path_src: str | None
    token_map: dict[str, str | None] = field(default_factory=dict)
    notes: list[str] = field(default_factory=list)  # approximation flags for themes
    _positional_names: list[str | None] | None = None

    def positional_group_name(self, number: int) -> str | None:
        """Name of the Nth capturing group of the (renamed) path face."""
        if self._positional_names is None:
            self._positional_names = _positional_group_names(self.path_src)
        if 1 <= number <= len(self._positional_names):
            return self._positional_names[number - 1]
        return None


def _positional_group_names(path_src: str | None) -> list[str | None]:
    """Capturing-group names of a path face in positional order (None=unnamed)."""
    if not path_src:
        return []
    out: list[str | None] = []
    outside = _outside_seq(path_src)
    for i, c in outside:
        if c != "(":
            continue
        nxt = path_src[i + 1 : i + 2]
        if nxt != "?":
            out.append(None)
            continue
        after = path_src[i + 2 : i + 3]
        if after == "<":
            j = path_src.find(">", i + 3)
            name = path_src[i + 3 : j] if j > 0 else ""
            if name and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", name):
                out.append(name)
                continue
            out.append(None)
            continue
        # (?: (?= (?! (?<= (?<! — non-capturing
    return out


def _id_placeholder(path_src: str | None) -> str | None:
    if path_src and "(?<id>" in path_src:
        return "{{match.id}}"
    return None


def _var_placeholder(var: str, ctx: TemplateCtx) -> str | None:
    if var in ctx.token_map:
        return ctx.token_map[var]
    ctx.token_map[var] = None  # cycle guard
    expr = ctx.rec.assigns.get(var)
    if expr is None:
        return None
    ph = _expr_placeholder(expr, ctx)
    if ph is None:
        # constants and constant-shaped expressions resolve as literals
        ph = derive_template(expr, ctx)
    ctx.token_map[var] = ph
    return ph


def _expr_placeholder(expr: ast.AST, ctx: TemplateCtx) -> str | None:
    """Placeholder for an expression that evaluates to the id at run time."""
    if isinstance(expr, ast.Name):
        return _var_placeholder(expr.id, ctx)
    if isinstance(expr, ast.Call) and isinstance(expr.func, ast.Attribute) and expr.func.attr == "group":
        if expr.args and isinstance(expr.args[0], ast.Constant):
            val = expr.args[0].value
            if isinstance(val, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", val):
                return "{{match.%s}}" % val.replace("_", "")
            if isinstance(val, int) and 1 <= val <= 9:
                # prefer the path face's own name for the positional group
                name = ctx.positional_group_name(val)
                if name:
                    return "{{match.%s}}" % name.replace("_", "")
                return "{{m%d}}" % val
        return None
    if isinstance(expr, ast.Call) and isinstance(expr.func, ast.Attribute) and expr.func.attr == "_match_id":
        return _id_placeholder(ctx.path_src) or "{{m1}}"
    return None


_PCT_TOKEN = re.compile(r"%(?:\((\w+)\)s|s|%%)")


def _substitute_pct(template: str, values: list[str], keys: list[str] | None) -> str | None:
    by_name = dict(zip(keys or [], values))
    out: list[str] = []
    vi = 0
    pos = 0
    for m in _PCT_TOKEN.finditer(template):
        out.append(template[pos : m.start()])
        if m.group(0) == "%%":
            out.append("%")
        elif m.group(1) is not None:
            name = m.group(1)
            if name not in by_name:
                return None
            out.append(by_name[name])
        else:
            if vi >= len(values):
                return None
            out.append(values[vi])
            vi += 1
        pos = m.end()
    out.append(template[pos:])
    return "".join(out)


_FMT_TOKEN = re.compile(r"\{(\w*)\}")


def _substitute_format(template: str, args: list[str], kwargs: dict[str, str]) -> str | None:
    unescaped = re.sub(r"\{\{|\}\}", lambda m: m.group(0)[0], template)
    out: list[str] = []
    ai = 0
    pos = 0
    for m in _FMT_TOKEN.finditer(unescaped):
        out.append(unescaped[pos : m.start()])
        name = m.group(1)
        if name:
            if name not in kwargs:
                return None
            out.append(kwargs[name])
        else:
            if ai >= len(args):
                return None
            out.append(args[ai])
            ai += 1
        pos = m.end()
    out.append(unescaped[pos:])
    return "".join(out)


def derive_template(expr: ast.AST | None, ctx: TemplateCtx) -> str | None:
    """URL template with whitelisted placeholders, or None when not derivable."""
    if expr is None:
        return None
    ph = _expr_placeholder(expr, ctx)
    if ph is not None:
        return ph
    if isinstance(expr, ast.Constant):
        return expr.value if isinstance(expr.value, str) else None
    if isinstance(expr, ast.Attribute):
        # self._CLASS_CONST / Klass._CONST string constants become literals
        const = ctx.rec.class_consts.get(expr.attr)
        if const is not None:
            return const
        return None
    if isinstance(expr, ast.Name):
        if expr.id == "url":
            return "{{pageUrl}}"
        module_const = ctx.rec.module_string_consts.get(expr.id)
        if module_const is not None:
            return module_const
        return _var_placeholder(expr.id, ctx)
    if isinstance(expr, ast.IfExp):
        # only when both branches derive to the SAME template is the
        # conditional statically safe to collapse
        body = derive_template(expr.body, ctx)
        orelse = derive_template(expr.orelse, ctx)
        if body is not None and body == orelse:
            return body
        return None
    if isinstance(expr, ast.Call) and isinstance(expr.func, ast.Name) and expr.func.id in ("quote", "quote_plus"):
        # URL-quoting of an id: unwrap; ids are verbatim-inserted by the engine
        return derive_template(expr.args[0], ctx) if expr.args else None
    if (
        isinstance(expr, ast.Call)
        and (isinstance(expr.func, ast.Name) and expr.func.id == "urljoin"
             or isinstance(expr.func, ast.Attribute) and expr.func.attr == "urljoin")
        and len(expr.args) == 2
    ):
        # urljoin(literal_base, id-shaped tail) == base + tail for the shapes
        # the transcriber emits (absolute https base, relative tail)
        base = derive_template(expr.args[0], ctx)
        tail = derive_template(expr.args[1], ctx)
        if base is None or tail is None or not base.lower().startswith("https://"):
            return None
        return base + tail
    if (
        isinstance(expr, ast.Call)
        and isinstance(expr.func, ast.Attribute)
        and expr.func.attr == "replace"
        and len(expr.args) == 2
        and all(isinstance(a, ast.Constant) and isinstance(a.value, str) for a in expr.args)
    ):
        # '<id>'.replace(a, b) on a placeholder-shaped inner: the engine inserts
        # ids verbatim; record the approximation and keep the inner template
        inner = derive_template(expr.func.value, ctx)
        if inner is not None and "{{" in inner:
            ctx.notes.append(THEME_URL_TRANSFORM)
            return inner
        return None
    if isinstance(expr, ast.BinOp) and isinstance(expr.op, ast.Add):
        left = derive_template(expr.left, ctx)
        right = derive_template(expr.right, ctx)
        if left is None or right is None:
            return None
        return left + right
    if isinstance(expr, ast.JoinedStr):
        parts: list[str] = []
        for value in expr.values:
            if isinstance(value, ast.Constant) and isinstance(value.value, str):
                parts.append(value.value)
            elif isinstance(value, ast.FormattedValue):
                token = derive_template(value.value, ctx)
                if token is None:
                    return None
                parts.append(token)
            else:
                return None
        return "".join(parts)
    if isinstance(expr, ast.BinOp) and isinstance(expr.op, ast.Mod):
        template = derive_template(expr.left, ctx)
        if template is None or "{{" in template or "}}" in template:
            return None
        right = expr.right
        if isinstance(right, ast.Dict):
            keys: list[str] = []
            values: list[str] = []
            for k, v in zip(right.keys, right.values):
                if not (isinstance(k, ast.Constant) and isinstance(k.value, str)):
                    return None
                token = derive_template(v, ctx)
                if token is None:
                    return None
                keys.append(k.value)
                values.append(token)
            return _substitute_pct(template, values, keys)
        elts = right.elts if isinstance(right, ast.Tuple) else [right]
        values = []
        for elt in elts:
            token = derive_template(elt, ctx)
            if token is None:
                return None
            values.append(token)
        return _substitute_pct(template, values, None)
    if isinstance(expr, ast.Call) and isinstance(expr.func, ast.Attribute) and expr.func.attr == "format":
        template = derive_template(expr.func.value, ctx)
        if template is None or "{{" in template or "}}" in template:
            return None
        args = []
        for arg in expr.args:
            token = derive_template(arg, ctx)
            if token is None:
                return None
            args.append(token)
        kwargs = {}
        for kw in expr.keywords:
            token = derive_template(kw.value, ctx)
            if token is None:
                return None
            kwargs[kw.arg] = token
        return _substitute_format(template, args, kwargs)
    return None


# ------------------------------------------------------------- emission

def emit_candidate(rec: analyzer.ExtractorRecord, used_ids: set[str],
                   allow_http_upgrade: bool = False) -> tuple[Candidate | None, str]:
    """Returns (candidate, None) or (None, structural_reject_reason).

    With allow_http_upgrade, upstream http://-only API endpoints are upgraded
    to https:// and flagged provisional — they only stay in the pool when the
    live enrichment pass measures the https endpoint (T108)."""
    reasons = ["auto-transcribed behavior needs human review"]
    themes: list[str] = []
    provisional_http = False

    try:
        norm_url, meta = normalize.normalize_pattern(rec.valid_url)
    except normalize.UnsupportedRegex as exc:
        return None, "pattern unsupported (%s)" % exc.reason
    if meta.renamed_groups:
        themes.append(THEME_RENAMED)

    branches, err = split_url_pattern(norm_url)
    if err:
        return None, err
    host_segs = [host for host, _path in branches]
    if not host_segs:
        return None, "empty host segment"
    regs: list[str] = []
    seen_regs: set[str] = set()
    for host_seg in host_segs:
        for reg in registrable_domains(host_seg):
            if reg not in seen_regs:
                seen_regs.add(reg)
                regs.append(reg)
    regs = regs[:MAX_HOST_DOMAINS]
    if not regs:
        return None, "no concrete host domain"
    # Capturing groups in host segments shift the extractor's positional group
    # numbers relative to the stand-alone path face; adjust below.
    host_caps = sum(validate.capturing_group_count(host_seg) for host_seg in host_segs)
    host_names: set[str] = set()
    for host_seg in host_segs:
        host_names |= validate.named_group_names(host_seg)
    # The canonical id group living in a host segment cannot bind to the path
    # face at all — reject rather than silently misbind.
    if rec.id_group_name and rec.id_group_name in host_names:
        return None, "id group captured in host segment"
    if any(
        len(d.split(".")) > 2 and validate.registrable_domain(d) != d
        for host_seg in host_segs
        for d in domain_literals(host_seg)
    ):
        themes.append(THEME_WIDENED_HOSTS)
    if len(host_segs) > 1:
        themes.append(THEME_ALT_HOSTS)
    hosts = hosts_face(regs)

    branch_paths = [strip_anchors(path_seg) for _host, path_seg in branches]
    branch_paths = [p for p in branch_paths if p]
    if not branch_paths:
        path = None
    elif len(branch_paths) == 1:
        path = branch_paths[0]
    else:
        path = "(?:" + "|".join(branch_paths) + ")"
    if path is not None and len(path) > validate.MAX_PATTERN_LENGTH:
        return None, "path face over 512 chars"
    if path and validate.capturing_group_count(path) >= 1:
        renamed = rename_first_unnamed(path, ID_RENAME)
        if renamed is not None:
            path = renamed

    ctx = TemplateCtx(rec=rec, path_src=path)
    dl = rec.download_calls[0]
    template = derive_template(dl.url_expr, ctx)
    if template is None:
        return None, "fetch url not derivable"
    if template.startswith("{{pageUrl}}"):
        return None, "fetch target is the page URL itself (schema needs https literal prefix)"
    if not template.lower().startswith("https://"):
        low = template.lower()
        if not (allow_http_upgrade and low.startswith("http://") and len(template) >= 8):
            return None, "fetch url not https"
        template = "https://" + template[7:]
        themes.append(THEME_HTTP_UPGRADE)
        reasons.append(REASON_HTTP_UNVERIFIED)
        provisional_http = True
    for note in ctx.notes:
        if note not in themes:
            themes.append(note)
    if host_caps and re.search(r"\{\{m[1-9]\}\}", template):
        if len(host_segs) > 1:
            # positional groups across alternation branches cannot be shifted
            # deterministically into the merged path face
            return None, "positional placeholder across alternation branches"

        def shift(m: re.Match) -> str:
            new = int(m.group(1)) - host_caps
            return "{{m%d}}" % new if new >= 1 else "{{m0}}"

        template = re.sub(r"\{\{m([1-9])\}\}", shift, template)
        themes.append(THEME_POSITIONAL)
        if "{{m0}}" in template:
            return None, "positional placeholder consumed by host segment"

    placeholders = re.findall(r"\{\{([^{}]{1,64})\}\}", template)
    group_count = validate.capturing_group_count(path) if path else 0
    names = validate.named_group_names(path) if path else set()
    if not placeholders:
        themes.append(THEME_CONSTANT_URL)
    for token in placeholders:
        token = token.strip()
        m = re.fullmatch(r"m([1-9])", token)
        if m:
            if int(m.group(1)) > group_count:
                return None, "positional placeholder unbound"
            if int(m.group(1)) > 1:
                themes.append(THEME_POSITIONAL)
        elif token not in ("pageUrl",):
            name_m = re.fullmatch(r"match\.([A-Za-z][A-Za-z0-9_]*)", token)
            if not name_m or name_m.group(1) not in names:
                return None, "named placeholder not in path face"

    if dl.has_headers:
        themes.append(THEME_HEADERS)

    # ---- action ----------------------------------------------------------
    action: dict
    if rec.json_var:
        prov_map = rec.json_pointer_prov
        pointers = [p for p in prov_map if validate.json_pointer_is_valid(p)]
        if not pointers:
            # AST cannot see any response access: conservative WILDCARD-free
            # default pointer, explicitly marked for review (T108 root cause 1)
            pointers = ["url"]
            themes.append(THEME_DEFAULT_POINTER)
            themes.append(THEME_POINTER_UNCERTAIN)
            reasons.append(REASON_POINTER_UNCERTAIN)
        else:
            themes.append(THEME_JSON_HEURISTIC)
            if any(p.startswith("formats") for p in pointers):
                themes.append(THEME_FORMATS)
            if any("nested" in prov_map.get(p, "") for p in pointers):
                themes.append(THEME_POINTER_DEPTH)
            if any(set(prov_map.get(p, "").split(",")) & {"loop", "traversal"} for p in pointers):
                themes.append(THEME_LOOP_CONCRETIZED)
        action = {"type": "jsonExtract", "pointers": pointers[: validate.MAX_JSON_POINTERS]}
    else:
        pattern = _pick_regex_pattern(rec)
        if pattern is None:
            return None, "no safe extraction pattern for webpage body"
        action = {"type": "regexExtract", "pattern": pattern}
        themes.append(THEME_WEBPAGE_REGEX)

    # ---- fetch spec (with T108 fetch.hosts cross-origin declaration) ------
    fetch_spec: dict = {"id": FETCH_ID, "url": template, "maxBytes": FETCH_MAX_BYTES}
    fetch_host = validate.template_literal_host(template)
    if fetch_host is not None:
        fetch_reg = validate.registrable_domain(fetch_host)
        if fetch_reg and fetch_reg not in regs:
            fetch_spec["hosts"] = [fetch_reg]
            themes.append(THEME_FETCH_HOSTS)

    rule = {
        "id": slug(rec.ie_name, used_ids),
        "version": 1,
        "match": {"hosts": hosts, **({"path": path} if path else {})},
        "actions": [action],
        "fetch": [fetch_spec],
        "note": "Auto-transcribed (T99/T108) from yt-dlp behavior: single GET fetch; needs review.",
    }

    # ---- QA: derived faces must match the extractor's own test URL --------
    if rec.test_url and not _faces_match_test(hosts, path, rec.test_url):
        return None, "derived faces fail test URL"

    source = {
        "ie": rec.ie_name,
        "module": rec.module,
        "class": rec.cls,
        "download": dl.func,
        "loc": rec.loc,
        "test_url": rec.test_url,
        "json_pointer_provenance": dict(rec.json_pointer_prov),
        "normalization": {
            "ignorecase": meta.ignorecase,
            "verbose_stripped": meta.verbose_stripped,
            "anchors_converted": meta.anchors_converted,
            "renamed_groups": meta.renamed_groups,
        },
        "original_valid_url": rec.valid_url,
    }
    return Candidate(rule=rule, reasons=reasons, themes=themes, source=source,
                     provisional_http=provisional_http), ""


def _pick_regex_pattern(rec: analyzer.ExtractorRecord) -> str | None:
    for pattern in rec.search_regex_patterns:
        if len(pattern) > validate.MAX_REGEX_PATTERN_LENGTH:
            continue
        try:
            norm, _meta = normalize.normalize_pattern(pattern)
        except normalize.UnsupportedRegex:
            continue
        if len(norm) > validate.MAX_REGEX_PATTERN_LENGTH:
            continue
        if not validate.regex_guard_is_safe(norm):
            continue
        return norm
    return None


def _faces_match_test(hosts: str, path: str | None, test_url: str) -> bool:
    try:
        host = urllib.parse.urlparse(test_url).hostname or ""
        if not host or not re.search(validate.python_check_pattern(hosts), host, re.IGNORECASE):
            return False
        if path is not None and not re.search(validate.python_check_pattern(path), test_url, re.IGNORECASE):
            return False
        return True
    except (re.error, ValueError):
        return False


def render_template(template: str, page_url: str, path: str | None) -> str | None:
    """Mini-port of RuleTemplatePolicy.render: bind placeholders against one
    path-face match of the page URL; None when a group cannot be resolved."""
    try:
        match = re.search(validate.python_check_pattern(path), page_url, re.IGNORECASE) if path else None
        out = template
        for m in re.finditer(r"\{\{([^{}]{1,64})\}\}", template):
            token = m.group(1).strip()
            if token == "pageUrl":
                value = page_url
            elif re.fullmatch(r"m[1-9]", token):
                try:
                    value = match.group(int(token[1])) if match else None
                except IndexError:
                    return None
                if value is not None and not value:
                    value = None
            else:
                name_m = re.fullmatch(r"match\.([A-Za-z][A-Za-z0-9_]*)", token)
                if not name_m:
                    return None
                try:
                    value = match.group(name_m.group(1)) if match else None
                except (IndexError, re.error):
                    return None
                if value is not None and not value:
                    value = None
            if value is None:
                return None
            out = out.replace(m.group(0), value)
        if "{{" in out or "}}" in out:
            return None
        return out
    except re.error:
        return None


def fetch_qa_ok(rule: dict, test_url: str | None) -> bool:
    """Bind the fetch template against the test URL; every placeholder must resolve."""
    if not test_url:
        return True
    for spec in rule.get("fetch", []):
        rendered = render_template(spec.get("url", ""), test_url, rule.get("match", {}).get("path"))
        if rendered is None:
            return False
        if not rendered.lower().startswith("https://"):
            return False
    return True

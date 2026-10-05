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


@dataclass
class Candidate:
    rule: dict
    needs_review: bool = True
    reasons: list[str] = field(default_factory=list)
    themes: list[str] = field(default_factory=list)
    source: dict = field(default_factory=dict)


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


_DOMAIN_LABEL = re.compile(r"[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?\Z")


def domain_literals(host_seg: str) -> list[str]:
    """Concrete dotted domain literals built from escaped dots, outside classes."""
    run: list[str] = []
    expect_label = True
    out: list[str] = []

    def flush() -> None:
        nonlocal run
        text = "".join(run)
        run = []
        if not text:
            return
        labels = text.split(".")
        if (
            len(labels) >= 2
            and all(_DOMAIN_LABEL.match(lb) for lb in labels)
            and labels[-1].isalpha()
            and len(labels[-1]) >= 2
        ):
            out.append(text.lower())

    for _i, c, in_class, escaped in normalize._tokens(host_seg):
        if in_class:
            flush()
            expect_label = True
        elif escaped:
            if c == "\\":
                continue  # first half of an escape pair; next token has the char
            if c == "." and run and run[-1] != "." and not expect_label:
                run.append(".")
                expect_label = True
            else:
                flush()
                expect_label = True
        elif c.isalnum() or c == "-":
            run.append(c)
            expect_label = False
        else:
            flush()
            expect_label = True
    flush()
    seen: set[str] = set()
    return [d for d in out if not (d in seen or seen.add(d))]


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
        return _var_placeholder(expr.id, ctx)
    if isinstance(expr, ast.Call) and isinstance(expr.func, ast.Name) and expr.func.id in ("quote", "quote_plus"):
        # URL-quoting of an id: unwrap; ids are verbatim-inserted by the engine
        return derive_template(expr.args[0], ctx) if expr.args else None
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

def emit_candidate(rec: analyzer.ExtractorRecord, used_ids: set[str]) -> tuple[Candidate | None, str]:
    """Returns (candidate, None) or (None, structural_reject_reason)."""
    reasons = ["auto-transcribed behavior needs human review"]
    themes: list[str] = []

    try:
        norm_url, meta = normalize.normalize_pattern(rec.valid_url)
    except normalize.UnsupportedRegex as exc:
        return None, "pattern unsupported (%s)" % exc.reason
    if meta.renamed_groups:
        themes.append(THEME_RENAMED)

    host_seg, path_seg, err = split_faces(norm_url)
    if err:
        return None, err
    if not host_seg:
        return None, "empty host segment"
    regs = registrable_domains(host_seg)
    if not regs:
        return None, "no concrete host domain"
    # Capturing groups in the host segment shift the extractor's positional
    # group numbers relative to the stand-alone path face; adjust below.
    host_caps = validate.capturing_group_count(host_seg)
    host_names = validate.named_group_names(host_seg)
    # The canonical id group living in the host segment cannot bind to the
    # path face at all — reject rather than silently misbind.
    if rec.id_group_name and rec.id_group_name in host_names:
        return None, "id group captured in host segment"
    if any(len(d.split(".")) > 2 and validate.registrable_domain(d) != d for d in domain_literals(host_seg)):
        themes.append(THEME_WIDENED_HOSTS)
    hosts = hosts_face(regs)

    path = strip_anchors(path_seg) or None
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
        return None, "fetch url not https"
    if host_caps and re.search(r"\{\{m[1-9]\}\}", template):
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
        pointers = [p for p in rec.json_pointers if validate.json_pointer_is_valid(p)]
        if not pointers:
            pointers = ["url"]
            themes.append(THEME_DEFAULT_POINTER)
        else:
            themes.append(THEME_JSON_HEURISTIC)
        if any(p.startswith("formats") for p in pointers):
            themes.append(THEME_FORMATS)
        action = {"type": "jsonExtract", "pointers": pointers[: validate.MAX_JSON_POINTERS]}
    else:
        pattern = _pick_regex_pattern(rec)
        if pattern is None:
            return None, "no safe extraction pattern for webpage body"
        action = {"type": "regexExtract", "pattern": pattern}
        themes.append(THEME_WEBPAGE_REGEX)

    rule = {
        "id": slug(rec.ie_name, used_ids),
        "version": 1,
        "match": {"hosts": hosts, **({"path": path} if path else {})},
        "actions": [action],
        "fetch": [{"id": FETCH_ID, "url": template, "maxBytes": FETCH_MAX_BYTES}],
        "note": "Auto-transcribed (T99) from yt-dlp behavior: single GET fetch; needs review.",
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
        "normalization": {
            "ignorecase": meta.ignorecase,
            "verbose_stripped": meta.verbose_stripped,
            "anchors_converted": meta.anchors_converted,
            "renamed_groups": meta.renamed_groups,
        },
        "original_valid_url": rec.valid_url,
    }
    return Candidate(rule=rule, reasons=reasons, themes=themes, source=source), ""


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

"""Python-regex -> Kotlin/Java-safe normalization for transcribed match faces.

The PureBrowser loader compiles rule patterns with Kotlin ``Regex`` (java.util.regex).
This module converts a Python pattern string into a Kotlin-compatible source while
recording metadata, and REJECTS constructs that have no safe Java equivalent
(possessive quantifiers, backreferences, conditionals, scoped inline flags, ...).

stdlib only.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field


class UnsupportedRegex(Exception):
    """Raised when a pattern cannot be made Kotlin-safe; .reason is structural."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass
class NormMeta:
    """Metadata recorded during normalization (never part of the emitted rule)."""

    ignorecase: bool = False            # original pattern carried (?i)
    verbose_stripped: bool = False      # (?x) comments/whitespace removed
    anchors_converted: bool = False     # \A/\Z -> ^/$
    interval_fixed: bool = False        # {,n} -> {0,n}
    renamed_groups: dict[str, str] = field(default_factory=dict)
    kept_flag_prefix: str = ""          # e.g. "(?s)" left at position 0 (Java-legal)


# ---------------------------------------------------------------- token scan

def _tokens(pattern: str) -> list[tuple[int, str, bool, bool]]:
    """(index, char, in_class, escaped) for every char, class- and escape-aware.

    Shared scanner (canonical home: validate.scan_tokens; re-exported here).
    """
    from .validate import scan_tokens

    return scan_tokens(pattern)


def _outside(pattern: str):
    """Indices of significant (non-escaped, non-class) characters."""
    return [(i, c) for i, c, in_class, escaped in _tokens(pattern) if not in_class and not escaped]


# ---------------------------------------------------------------- inline flags

_FLAG_CHARS = set("aiLmsux")


def _find_inline_flags(pattern: str) -> list[tuple[int, int, str, str]]:
    """[(start, end, flags, kind)] for ``(?flags)`` groups and ``(?flags:...)`` scoped."""
    found: list[tuple[int, int, str, str]] = []
    outside = {i: c for i, c in _outside(pattern)}
    idx = sorted(outside)
    for pos, i in enumerate(idx):
        if outside[i] != "(":
            continue
        j = i + 1
        if j not in outside or outside[j] != "?":
            continue
        k = j + 1
        while k in outside and outside[k] in _FLAG_CHARS:
            k += 1
        if k == j + 1:
            continue  # (?=, (?<, (?: ... not flags
        flags = pattern[j + 1 : k]
        if k in outside and outside[k] == ")":
            found.append((i, k + 1, flags, "group"))
        elif k in outside and outside[k] == ":":
            found.append((i, k + 1, flags, "scoped"))
    return found


def _strip_verbose(pattern: str) -> str:
    """Remove unescaped whitespace and ``#``-comments outside character classes."""
    tokens = _tokens(pattern)
    drop: set[int] = set()
    i = 0
    n = len(pattern)
    comment_until_newline = False
    for idx, c, in_class, escaped in tokens:
        if escaped or in_class:
            continue
        if comment_until_newline:
            if c == "\n":
                comment_until_newline = False
                drop.add(idx)  # verbose mode ignores this newline too
            else:
                drop.add(idx)
            continue
        if c == "#":
            comment_until_newline = True
            drop.add(idx)
            continue
        if c in " \t\r\n\f\v":
            drop.add(idx)
    return "".join(c for i, c in enumerate(pattern) if i not in drop)


# ---------------------------------------------------------------- main entry

def normalize_pattern(source: str) -> tuple[str, NormMeta]:
    """Convert a Python regex source to a Kotlin(Java)-compatible source.

    Raises UnsupportedRegex with a structural reason when no safe conversion
    exists. Never raises for anything else; compile failures raise Unsupported too.
    """
    if not source:
        raise UnsupportedRegex("empty pattern")
    meta = NormMeta()
    p = source

    # -- inline flags: leading groups parsed, everything else rejected --------
    flags = _find_inline_flags(p)
    if any(kind == "scoped" for _, _, _, kind in flags):
        raise UnsupportedRegex("scoped inline flags (?f:...) unsupported in Java")
    leading_end = 0
    kept_flags = ""
    for start, end, fl, _kind in flags:
        if start != leading_end:
            raise UnsupportedRegex("mid-pattern inline flags unsupported in Java")
        if "L" in fl:
            raise UnsupportedRegex("locale flag unsupported")
        if "x" in fl:
            meta.verbose_stripped = True
        if "i" in fl:
            meta.ignorecase = True
        kept_flags += "".join(ch for ch in fl if ch in "sm")
        leading_end = end
    if flags:
        p = p[leading_end:]
    if meta.verbose_stripped:
        p = _strip_verbose(p)
        # verbose strip may expose adjacent flag positions; re-check none remain
        if _find_inline_flags(p):
            raise UnsupportedRegex("inline flags survived verbose strip")
    if kept_flags:
        p = "(?%s)" % kept_flags + p
        meta.kept_flag_prefix = "(?%s)" % kept_flags

    # -- Python named groups -> Java named groups -----------------------------
    p = p.replace("(?P<", "(?<")

    tokens = _tokens(p)
    outside_positions = {i for i, _c in _outside(p)}

    # -- rejected constructs (outside classes) --------------------------------
    # Escape pairs arrive as two tokens (backslash + char); check the char half.
    i = 0
    while i < len(tokens):
        idx, c, in_class, escaped = tokens[i]
        nxt_idx, nxt_c = tokens[i + 1][:2] if i + 1 < len(tokens) else (-1, "")
        if not in_class:
            if escaped and c.isdigit():
                raise UnsupportedRegex("backreference")
            if escaped and c == "g" and p[nxt_idx : nxt_idx + 1] in "<{":
                raise UnsupportedRegex("group escape")
        if not in_class and not escaped:
            if c in "*+?" and nxt_c == "+":
                raise UnsupportedRegex("possessive quantifier")
            if c == "}" and nxt_c == "+":
                raise UnsupportedRegex("possessive interval")
            if c == "(" and nxt_c == "?":
                after = p[idx + 2 : idx + 9]
                if after.startswith("("):
                    raise UnsupportedRegex("conditional group")
                if after.startswith("#"):
                    raise UnsupportedRegex("comment group")
                if after.startswith("P=") or after.startswith("P>"):
                    raise UnsupportedRegex("named backreference")
                if after.startswith("'"):
                    raise UnsupportedRegex("quoted named group")
        i += 1

    # -- anchor + interval rewrites (index-based, outside classes) ------------
    edits: list[tuple[int, int, str]] = []  # (start, end, replacement)
    for i in range(len(tokens) - 1):
        idx, c, in_class, escaped = tokens[i]
        nxt_idx, nxt_c, nxt_in_class, _nxt_esc = tokens[i + 1]
        if not in_class and escaped and c == "\\" and not nxt_in_class:
            if nxt_c == "A":
                edits.append((idx, nxt_idx + 1, "^"))
                meta.anchors_converted = True
            elif nxt_c == "Z":
                edits.append((idx, nxt_idx + 1, "$"))
                meta.anchors_converted = True
    # {,n} -> {0,n}, only when '{' sits outside a character class
    for m in re.finditer(r"\{,(\d+)\}", p):
        if m.start() in outside_positions:
            edits.append((m.start(), m.end(), "{0,%s}" % m.group(1)))
            meta.interval_fixed = True
    p = _apply_edits(p, edits)

    # -- named group legality (Java names: [A-Za-z][A-Za-z0-9]*) --------------
    p = _fix_group_names(p, meta)

    # -- compile check (Python as conservative proxy for Java) ----------------
    # Python re only accepts (?P<name>...); our Kotlin-safe output uses Java's
    # (?<name>...). Re-Pythonify named groups outside classes for the check only.
    try:
        re.compile(_python_check_source(p))
    except re.error as exc:
        raise UnsupportedRegex("compile failed: %s" % exc.msg) from exc
    return p, meta


def _python_check_source(pattern: str) -> str:
    """Java-style `(?<name>` -> Python `(?P<name>` outside character classes."""
    from .validate import python_check_pattern

    return python_check_pattern(pattern)


def _outside_text(pattern: str) -> str:
    return "".join(c for _i, c in _outside(pattern))


def _apply_edits(pattern: str, edits: list[tuple[int, int, str]]) -> str:
    if not edits:
        return pattern
    edits = sorted(edits, key=lambda e: e[0])
    out: list[str] = []
    prev = 0
    for start, end, repl in edits:
        if start < prev:
            continue  # overlapping edit: first wins
        out.append(pattern[prev:start])
        out.append(repl)
        prev = end
    out.append(pattern[prev:])
    return "".join(out)


_JAVA_NAME = re.compile(r"[A-Za-z][A-Za-z0-9]*\Z")


def _fix_group_names(pattern: str, meta: NormMeta) -> str:
    """Underscore names are illegal in Java but legal in our template policy:
    rename deterministically, reject collisions/invalid names/duplicates."""
    renames: dict[str, str] = {}
    seen: set[str] = set()
    edits: list[tuple[int, int, str]] = []
    outside_positions = {i for i, _c in _outside(pattern)}
    for m in re.finditer(r"\(\?<([A-Za-z][A-Za-z0-9_]*)>", pattern):
        if m.start() not in outside_positions:
            continue  # inside a character class: literal text, leave it alone
        name = m.group(1)
        if name in renames:
            new = renames[name]
        elif _JAVA_NAME.match(name):
            new = name
        else:
            new = name.replace("_", "") or "g"
            if not _JAVA_NAME.match(new):
                new = "g" + new
            while new in seen or new in renames.values():
                new += "x"
        if new in seen:
            raise UnsupportedRegex("duplicate or colliding group name")
        renames[name] = new
        seen.add(new)
        if new != name:
            edits.append((m.start(1), m.end(1), new))
            meta.renamed_groups[name] = new
    return _apply_edits(pattern, edits)

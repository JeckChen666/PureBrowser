"""Regex normalization table tests (Python -> Kotlin/Java-safe)."""

import re

import pytest

from transcriber import normalize
from transcriber.normalize import NormMeta, UnsupportedRegex, normalize_pattern
from transcriber.validate import python_check_pattern


@pytest.mark.parametrize("source,expected,attr", [
    # (?x) verbose: comments and free whitespace disappear
    ("(?x) ^https?://  example\\.com  # scheme\n /watch", r"^https?://example\.com/watch", None),
    # \A / \Z anchors become ^ / $
    (r"\Ahttps?://example\.com/\Z", r"^https?://example\.com/$", None),
    # (?i) removed (ignore-case recorded as metadata)
    (r"(?i)EXAMPLE\.COM", r"EXAMPLE\.COM", "ignorecase"),
    # leading (?s) is Java-legal and kept
    (r"(?s)a.+b", r"(?s)a.+b", None),
    # Python named groups become Java named groups
    (r"(?P<id>\d+)", r"(?<id>\d+)", None),
    # {,n} becomes {0,n}
    (r"x{,5}", r"x{0,5}", "interval_fixed"),
    # escaped dots/classes are never touched
    (r"[a.b]+\.(com|net)", r"[a.b]+\.(com|net)", None),
])
def test_normalization_table(source, expected, attr):
    out, meta = normalize_pattern(source)
    assert out == expected
    if attr == "ignorecase":
        assert meta.ignorecase
    elif attr == "interval_fixed":
        assert meta.interval_fixed
    assert re.compile(python_check_pattern(out))  # compiles via proxy


@pytest.mark.parametrize("source,reason_part", [
    (r"a*+b", "possessive quantifier"),
    (r"a{2,3}+", "possessive interval"),
    (r"(a)\1", "backreference"),
    (r"(a)\g<1>", "group escape"),
    (r"(?(1)a|b)", "conditional group"),
    (r"a(?#comment)b", "comment group"),
    (r"(?P<n>a)(?P=n)", "named backreference"),
    (r"(?P<n>a)|(?P<n>b)", "duplicate or colliding"),  # Python allows, Java does not
    (r"a(?i)b", "mid-pattern"),  # inline flag not at position 0
    (r"(?i:ab)", "scoped inline flags"),
    (r"(?L)ab", "locale"),
    (r"a[\q]b", "compile failed"),
])
def test_unsupported_constructs(source, reason_part):
    with pytest.raises(UnsupportedRegex) as exc:
        normalize_pattern(source)
    assert reason_part in exc.value.reason


def test_underscore_group_renamed():
    out, meta = normalize_pattern(r"(?P<video_id>[0-9]+)")
    assert out == r"(?<videoid>[0-9]+)"
    assert meta.renamed_groups == {"video_id": "videoid"}


def test_verbose_flag_recorded():
    _out, meta = normalize_pattern(r"(?x) a b")
    assert meta.verbose_stripped


def test_anchors_recorded():
    _out, meta = normalize_pattern(r"\Aa\Z")
    assert meta.anchors_converted


def test_meta_defaults():
    meta = NormMeta()
    assert not meta.ignorecase and not meta.verbose_stripped
    assert meta.renamed_groups == {}


def test_python_check_source_roundtrip():
    # Java named groups compile through the Python proxy
    assert re.compile(python_check_pattern(r"(?<id>\d+)"))

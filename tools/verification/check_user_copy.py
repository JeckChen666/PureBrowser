#!/usr/bin/env python3
"""T118 user-copy verifier: banned primary-copy terms must not appear in string literals.

Scans the T118 copy-rewrite scope (resource panels, settings/about, downloads surfaces,
download service notification, CopyMapping) for developer/protocol wording that primary
copy must not use: 清单|档位|直链|MPEG-TS|fMP4|ETag|受控下载器|候选.

Such terms are allowed ONLY inside a marked 技术详情 block, i.e. between the comment
markers `// tech-detail:begin` and `// tech-detail:end`, or on a line whose trailing
comment is `// tech-detail`. Diagnostic/log/code layers outside the scope list are not
scanned (their terminology intentionally stays technical for debuggability).

Usage: python3 tools/verification/check_user_copy.py [--quiet]
Exit code 0 = clean, 1 = findings, 2 = scope file missing.
Registered for T119 acceptance (JVM/lint gate companion).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# T118 copy scope (dialog files owned by the T117 dialog track are NOT scanned here).
SCOPE = [
    "app/src/main/java/com/example/purebrowser/ui/CopyMapping.kt",
    "app/src/main/java/com/example/purebrowser/ui/resources/ResourcePresentation.kt",
    "app/src/main/java/com/example/purebrowser/ui/resources/ResourceCenter.kt",
    "app/src/main/java/com/example/purebrowser/ui/resources/ResourceDetail.kt",
    "app/src/main/java/com/example/purebrowser/ui/resources/ResourceUi.kt",
    "app/src/main/java/com/example/purebrowser/ui/settings/SettingsScreen.kt",
    "app/src/main/java/com/example/purebrowser/ui/downloads/DownloadActions.kt",
    "app/src/main/java/com/example/purebrowser/ui/downloads/DownloadCenterScreen.kt",
    "app/src/main/java/com/example/purebrowser/ui/downloads/DownloadQuickSheet.kt",
    "app/src/main/java/com/example/purebrowser/ui/downloads/DownloadTaskDetail.kt",
    "app/src/main/java/com/example/purebrowser/ui/downloads/DownloadTaskRow.kt",
    "app/src/main/java/com/example/purebrowser/ui/downloads/DownloadUiSupport.kt",
    "app/src/main/java/com/example/purebrowser/ui/browser/BrowserChromeRules.kt",
    "app/src/main/java/com/example/purebrowser/ui/browser/BrowserMenuSheet.kt",
    "app/src/main/java/com/example/purebrowser/ui/browser/BrowserScreen.kt",
    "app/src/main/java/com/example/purebrowser/download/ControlledDownloadService.kt",
]

BANNED = re.compile(r"清单|档位|直链|MPEG-TS|fMP4|ETag|受控下载器|候选")
REGION_BEGIN = "tech-detail:begin"
REGION_END = "tech-detail:end"
LINE_MARKER = "tech-detail"


def extract_strings(text: str):
    """Yield (line_no, literal) for every string literal, skipping comments.

    Tracks line/block comments and double-quoted (single- and triple-quoted) Kotlin
    strings with escape handling; char literals are skipped so 'x' never opens a string.
    """
    i, n, line = 0, len(text), 1
    while i < n:
        ch = text[i]
        if ch == "\n":
            line += 1
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            end = text.find("\n", i)
            yield ("comment", line, text[i : end if end != -1 else n], "")
            i = end if end != -1 else n
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            end = text.find("*/", i + 2)
            end = end + 2 if end != -1 else n
            segment = text[i:end]
            yield ("comment", line, segment, "")
            line += segment.count("\n")
            i = end
            continue
        if ch == '"':
            if text.startswith('"""', i):
                end = text.find('"""', i + 3)
                end = end + 3 if end != -1 else n
                segment = text[i:end]
                yield ("string", line, segment, "")
                line += segment.count("\n")
                i = end
                continue
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    break
                j += 1
            closing = j if j < n and text[j] == '"' else min(j + 1, n)
            line_end = text.find("\n", closing)
            line_rest = text[closing : line_end if line_end != -1 else n]
            yield ("string", line, text[i:closing], line_rest)
            i = closing
            continue
        if ch == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'" or text[j] == "\n":
                    break
                j += 1
            i = j + 1 if j < n and text[j] == "'" else min(j + 1, n)
            continue
        i += 1


def check_file(path: Path):
    findings = []
    in_tech_region = False
    for kind, line_no, payload, line_rest in extract_strings(path.read_text(encoding="utf-8")):
        if kind == "comment":
            if REGION_BEGIN in payload:
                in_tech_region = True
            elif REGION_END in payload:
                in_tech_region = False
            continue
        allowed = in_tech_region or f"// {LINE_MARKER}" in line_rest
        if allowed:
            continue
        for match in BANNED.finditer(payload):
            findings.append((line_no, match.group(0), payload.strip()[:120]))
    return findings


def main() -> int:
    quiet = "--quiet" in sys.argv
    total = 0
    missing = []
    for rel in SCOPE:
        path = REPO_ROOT / rel
        if not path.exists():
            missing.append(rel)
            continue
        for line_no, term, literal in check_file(path):
            total += 1
            if not quiet:
                print(f"FAIL {rel}:{line_no} banned term '{term}' in primary copy: {literal}")
    if missing:
        print("Missing scope files (update SCOPE in check_user_copy.py):")
        for rel in missing:
            print(f"  {rel}")
        return 2
    if not quiet:
        print(f"check_user_copy: {len(SCOPE)} files scanned, {total} banned-term findings")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())

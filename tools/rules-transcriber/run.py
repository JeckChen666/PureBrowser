#!/usr/bin/env python3.11
"""T99 CLI: analyze a pinned yt-dlp checkout and emit v3 rule candidates.

Usage:
    python3.11 run.py [--src /tmp/ytdlp-src] [--out output]

Stdlib only. No network access, no git, no gradle. Outputs are locally gitignored.
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from transcriber import main  # noqa: E402


def parse_args(argv: list[str]) -> argparse.Namespace:
    here = os.path.dirname(os.path.abspath(__file__))
    parser = argparse.ArgumentParser(description="yt-dlp -> PureBrowser v3 rules transcriber")
    parser.add_argument("--src", default="/tmp/ytdlp-src", help="yt-dlp source root")
    parser.add_argument("--out", default=os.path.join(here, "output"), help="output directory")
    return parser.parse_args(argv)


def main_cli(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    summary = main.run(args.src, args.out)
    # Aggregate stdout only — no site names ever reach the console.
    print("done; candidates written to", os.path.basename(args.out))
    print("validated:", summary.get("validated"), "emitted:", summary.get("emitted"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main_cli())

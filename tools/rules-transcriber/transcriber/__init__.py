"""T99 rules transcriber: AST analysis of the pinned open-source yt-dlp corpus
into PureBrowser schema-v3 site-rule candidates. Stdlib only (Python 3.11).

Pipeline: analyzer -> normalize -> emit -> validate -> outputs.
All outputs land in output/ which is locally gitignored.
"""

__all__ = ["analyzer", "normalize", "emit", "validate", "main"]

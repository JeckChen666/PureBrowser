"""Pipeline orchestration and output writers (T99; T108 recursive pointers,
fetch.hosts extension, live enrichment).

Outputs (all inside the locally-gitignored output/ directory):
  candidates.json    validated v3 rule candidates + provenance + review metadata
  classification.csv one row per discovered extractor class
  rejected.json      structural reject reasons for every non-emitted extractor
  enrich-log.json    per-attempt live-fetch log (hosts in file only; live runs)
  REPORT.md          aggregate counts + needs-review THEMES (no site names)
"""

from __future__ import annotations

import csv
import io
import json
import os
import re
from collections import Counter

from . import analyzer, emit, enrich, validate

SCHEMA_POINTER = (
    "app/src/main/java/com/example/purebrowser/media/rules/RuleData.kt "
    "(SiteRule.parse) + examples in app/src/main/assets/rules/site-rules.json"
)

FETCH_HOSTS_SCHEMA_NOTE = (
    "Schema extension (v0.2.0 plan section 3 / T108): a fetch spec may declare "
    "`fetch[].hosts`: a JSON array of 1-4 explicit extra fetch domains (plain "
    "registrable-style host strings, no IP literals / private hosts). "
    "Load-time contract: every fetch URL's host must satisfy registrable_domain"
    "(fetch_host) IN { registrable_domain(d) for d in match.hosts literals } "
    "UNION { registrable_domain(d) for d in fetch[].hosts }. Importer-tier "
    "documents must NOT use the extension (reject on sight); app-side wiring "
    "belongs to the orchestrator."
)


def read_ytdlp_version(src_root: str) -> str:
    version_path = os.path.join(src_root, "yt_dlp", "version.py")
    try:
        with open(version_path, encoding="utf-8") as fh:
            for line in fh:
                m = re.match(r"__version__\s*=\s*'([^']+)'", line)
                if m:
                    return m.group(1)
    except OSError:
        pass
    return "unknown"


def run(src_root: str, out_dir: str, live_enrich: bool = False,
        enrich_budget: int = enrich.DEFAULT_BUDGET, fetcher=None) -> dict:
    os.makedirs(out_dir, exist_ok=True)
    ytdlp_version = read_ytdlp_version(src_root)
    records = analyzer.analyze_tree(src_root)

    counts = Counter(rec.classification for rec in records)
    used_ids: set[str] = set()
    candidates: list[dict] = []
    provisional: list[dict] = []
    rejected: list[dict] = []
    emitted = 0
    validated = 0
    validation_failures: list[dict] = []

    for rec in records:
        if rec.classification != analyzer.SIMPLE:
            rejected.append({
                "module": rec.module,
                "class": rec.cls,
                "ie": rec.ie_name,
                "stage": "classify",
                "reason": "%s: %s" % (rec.classification, rec.classify_reason),
            })
            continue
        try:
            candidate, reason = emit.emit_candidate(rec, used_ids, allow_http_upgrade=True)
        except Exception as exc:  # defensive: one bad extractor never stops the run
            candidate, reason = None, "emitter error (%s)" % type(exc).__name__
            _ = exc
        if candidate is None:
            rejected.append({
                "module": rec.module, "class": rec.cls, "ie": rec.ie_name,
                "stage": "emit", "reason": reason,
            })
            continue
        emitted += 1
        failures = validate.validate_rule(candidate.rule)
        if failures:
            validation_failures.append({
                "module": rec.module, "class": rec.cls, "ie": rec.ie_name,
                "stage": "validate", "reason": "; ".join(failures),
            })
            rejected.append(validation_failures[-1])
            continue
        if not emit.fetch_qa_ok(candidate.rule, rec.test_url):
            rejected.append({
                "module": rec.module, "class": rec.cls, "ie": rec.ie_name,
                "stage": "render-qa", "reason": "fetch template does not bind against test URL",
            })
            continue
        entry = {
            "rule": candidate.rule,
            "needs_review": candidate.needs_review,
            "reasons": candidate.reasons,
            "themes": candidate.themes,
            "source": candidate.source,
        }
        if candidate.provisional_http:
            provisional.append(entry)
        else:
            validated += 1
            candidates.append(entry)

    enrichment = {"requests": 0, "measured": 0, "promoted_provisional": 0, "log": []}
    if live_enrich:
        enrichment = enrich.enrich(candidates, provisional, budget=enrich_budget,
                                   fetcher=fetcher or enrich.curl_fetch)
        validated += enrichment["promoted_provisional"]
        with open(os.path.join(out_dir, "enrich-log.json"), "w", encoding="utf-8") as fh:
            json.dump({"budget": enrichment["budget"], "requests": enrichment["requests"],
                       "measured": enrichment["measured"],
                       "attempts": enrichment["log"]}, fh, indent=1, ensure_ascii=False)
    failed_provisional = enrichment.get("failed_provisional_entries", []) if live_enrich else provisional
    for entry in failed_provisional:
        rejected.append({
            "module": entry["source"].get("module"), "class": entry["source"].get("class"),
            "ie": entry["source"].get("ie"), "stage": "http-upgrade",
            "reason": ("http endpoint upgrade not live-verified"
                       if live_enrich else
                       "http endpoint upgrade requires live enrichment (not run)"),
        })

    doc_failures = validate.validate_document([c["rule"] for c in candidates])

    _write_candidates(out_dir, candidates, ytdlp_version, doc_failures)
    _write_csv(out_dir, records)
    _write_rejected(out_dir, rejected)
    summary = _write_report(out_dir, ytdlp_version, records, counts, candidates,
                            rejected, validation_failures, doc_failures, emitted,
                            enrichment)
    summary["validated"] = validated
    summary["emitted"] = emitted
    return summary


def _write_candidates(out_dir: str, candidates: list[dict], version: str, doc_failures: list[str]) -> None:
    doc = {
        "schema": "purebrowser-rules-v3-candidates/1",
        "generated_by": "tools/rules-transcriber (T99; T108 recursive pointers + fetch.hosts)",
        "yt_dlp_version": version,
        "rule_schema_pointer": SCHEMA_POINTER,
        "schema_notes": {
            "fetch.hosts": FETCH_HOSTS_SCHEMA_NOTE,
            "measured": (
                "Entries carrying measured=true have their jsonExtract pointers "
                "verified against a live fetch of the rendered template against "
                "source.test_url (see enrich-log.json); source.measured holds the "
                "verified pointer list with kind/quality and sample hosts."
            ),
        },
        "validation": "ported load-time validators: transcriber/validate.py (fetch.hosts extension semantics included)",
        "document_level_failures": doc_failures,
        "consumption": (
            "Each entry's `rule` is a schema-v3 SiteRule JSON object exactly as the "
            "loader expects; merge into rules[] of a version:3 document after human "
            "review of needs_review/reasons. `themes` flags the review focus; "
            "measured=true entries have live-verified pointers. T109 should treat "
            "`source.test_url` as the manual smoke-test URL and prioritize "
            "measured=true entries."
        ),
        "rules": candidates,
    }
    with open(os.path.join(out_dir, "candidates.json"), "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)


def _write_csv(out_dir: str, records: list[analyzer.ExtractorRecord]) -> None:
    buf = io.StringIO()
    writer = csv.DictWriter(buf, fieldnames=list(records[0].as_csv_row().keys()))
    writer.writeheader()
    for rec in records:
        writer.writerow(rec.as_csv_row())
    with open(os.path.join(out_dir, "classification.csv"), "w", encoding="utf-8") as fh:
        fh.write(buf.getvalue())


def _write_rejected(out_dir: str, rejected: list[dict]) -> None:
    with open(os.path.join(out_dir, "rejected.json"), "w", encoding="utf-8") as fh:
        json.dump({"count": len(rejected), "rejected": rejected}, fh, indent=1, ensure_ascii=False)


def _write_report(out_dir, version, records, counts, candidates, rejected,
                  validation_failures, doc_failures, emitted: int,
                  enrichment: dict | None = None) -> dict:
    enrichment = enrichment or {}
    emit_reasons = Counter(
        r["reason"] for r in rejected if r["stage"] in ("emit", "render-qa", "http-upgrade")
    )
    theme_counts = Counter(t for c in candidates for t in c["themes"])
    measured_count = sum(1 for c in candidates if c.get("measured"))
    fetch_hosts_declared = sum(
        1 for c in candidates
        if any(isinstance(spec.get("hosts"), list) for spec in c["rule"].get("fetch", []))
    )
    prov = Counter()
    for c in candidates:
        for flags in (c["source"].get("json_pointer_provenance") or {}).values():
            for flag in flags.split(","):
                prov[flag] += 1
    lines = [
        "# T99/T108 yt-dlp rules transcriber report",
        "",
        "Corpus: yt-dlp %s (pinned stable, cloned to /tmp/ytdlp-src, not committed)." % version,
        "",
        "## Totals",
        "",
        "- extractor classes discovered: %d" % len(records),
        "- SIMPLE: %d" % counts.get(analyzer.SIMPLE, 0),
        "- POST_API: %d" % counts.get(analyzer.POST_API, 0),
        "- JS_COMPLEX: %d" % counts.get(analyzer.JS_COMPLEX, 0),
        "- OTHER: %d" % counts.get(analyzer.OTHER, 0),
        "- candidates constructed: %d" % emitted,
        "- candidates passing ported load-time validators + render QA: %d" % len(candidates),
        "- rejected before/at validation: %d (see rejected.json; names withheld to files)" % len(rejected),
        "",
        "## T108 root-cause fixes",
        "",
        "- recursive pointer synthesis: %d pointers across candidates "
        "(provenance: %s)" % (sum(prov.values()), ", ".join("%s=%d" % kv for kv in prov.most_common())),
        "- fetch.hosts extension declared on %d candidates (cross-origin API hosts, "
        "see schema_notes in candidates.json for the app-side wiring contract)" % fetch_hosts_declared,
        "- live enrichment: %d/%d requests budget used, %d candidates measured=true"
        % (enrichment.get("requests", 0), enrichment.get("budget", 0), measured_count),
        "",
        "## Rejection reasons (aggregate, structural)",
        "",
    ]
    for reason, n in emit_reasons.most_common(15):
        lines.append("- %s: %d" % (reason, n))
    if validation_failures:
        lines += ["", "Validation failures:"]
        for entry in validation_failures[:10]:
            lines.append("- %s" % entry["reason"])
    lines += [
        "",
        "## Needs-review themes",
        "",
        "(Aggregate review focus areas; site names withheld to files.)",
        "",
    ]
    for theme, n in theme_counts.most_common(12):
        lines.append("- %s: %d" % (theme, n))
    if doc_failures:
        lines += ["", "## Document-level validation", ""] + ["- %s" % f for f in doc_failures]
    lines += [
        "",
        "## Files",
        "",
        "- candidates.json — validated v3 candidates + provenance + review metadata",
        "- classification.csv — per-extractor classification (names in file only)",
        "- rejected.json — structural reject reasons per extractor",
        "- enrich-log.json — live enrichment attempt log (live runs only; hosts in file only)",
        "- REPORT.md — this aggregate report",
        "",
        "## T109 consumption",
        "",
        "Read output/candidates.json; each `.rules[] entry.rule` is a schema-v3 rule.",
        "Prioritize measured=true entries for the ≥30-site verification pool; every",
        "measured pointer list was verified against the live endpoint.",
        "Schema source of truth: %s" % SCHEMA_POINTER,
    ]
    with open(os.path.join(out_dir, "REPORT.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    return {"emit_reasons": emit_reasons, "theme_counts": theme_counts}

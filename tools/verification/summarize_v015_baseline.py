#!/usr/bin/env python3
"""Summarize recorded code13 observations, never infer output success or paired gain.

Inputs are an authorized candidate registration, the recorded lab environment,
individual case records and their sanitized UI evidence. No network/device access.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

STAGES = {
    'discovery': {'valid_file_candidate', 'blob_only_no_executable_source',
                  'no_media_observed', 'page_unavailable', 'not_tested'},
    'plan': {'offered_file_download_unverified', 'unsupported',
             'not_tested', 'page_unavailable'},
    'usableOutput': {'not_tested', 'not_started_no_plan', 'transfer_failed',
                     'download_reported_complete_unverified', 'page_unavailable'},
}


def summarize(registration, directory):
    directory = Path(directory).resolve()
    environment = json.loads((directory / 'environment.json').read_text())
    if environment.get('baselineCode') != 13 or environment.get('baselineVersion') != '0.1.4':
        raise ValueError('Published code13 environment required')
    installed = directory / 'installed.apk'
    if hashlib.sha256(installed.read_bytes()).hexdigest() != environment['baselineAPKsha256']:
        raise ValueError('Installed baseline APK hash mismatch')
    rows = []
    seen = set()
    for sample in registration['samples']:
        sid = sample['sampleId']
        if not isinstance(sid, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}', sid):
            raise ValueError('Unsafe sample ID')
        if sid in seen:
            raise ValueError('Duplicate sample ID')
        seen.add(sid)
        path = directory / sid / 'input.json'
        if not path.exists():
            rows.append({'sampleId': sid, 'observed': False,
                         'stageStatus': {k: 'not_tested' for k in STAGES}})
            continue
        row = json.loads(path.read_text())
        if row['sampleId'] != sid or row['publicPage'] != sample['playbackPage'] or row['versionCode'] != 13:
            raise ValueError('Case identity/version differs from registration')
        stages = row['stageStatus']
        if set(stages) != set(STAGES) or any(stages[k] not in STAGES[k] for k in STAGES):
            raise ValueError('Unsupported stage status; no implicit success permitted')
        evidence = row.get('evidence', [])
        if not evidence:
            raise ValueError('Observed case must cite sanitized UI evidence')
        evidence_hashes = []
        for relative in evidence:
            evidence_path = (directory / relative).resolve()
            if not evidence_path.is_relative_to(directory) or not evidence_path.is_file():
                raise ValueError('Missing/out-of-root evidence')
            evidence_hashes.append({'path': relative, 'sha256': hashlib.sha256(evidence_path.read_bytes()).hexdigest()})
        rows.append({'sampleId': sid, 'serviceGroup': sample['serviceGroup'], 'observed': True,
                     'stageStatus': stages, 'notes': row.get('notes', []), 'evidence': evidence_hashes})
    return {
        'schemaVersion': 1, 'scope': 'published-code13 preliminary observations, not frozen paired acceptance',
        'environment': environment, 'sampleCount': len(rows),
        'observedCount': sum(r['observed'] for r in rows), 'rows': rows,
        'strictCorpusFrozen': False, 'candidatePaired': False,
        'successRates': None, 'crossSiteGains': None, 'releaseApproved': False,
        'limits': ['No metadata/URL/prefix/visible completion label is a verified usable output.',
                   'Upload-version/credits checks and same-condition candidate observations remain separate.',
                   'Unavailable page/access conditions are not attributed to either app version.'],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--registration', type=Path, default=Path(__file__).with_name('v015-public-sample-candidates.json'))
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = summarize(json.loads(args.registration.read_text()), args.baseline)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: report[k] for k in ['sampleCount', 'observedCount', 'strictCorpusFrozen', 'candidatePaired', 'successRates', 'releaseApproved']}))


if __name__ == '__main__':
    main()

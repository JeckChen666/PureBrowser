#!/usr/bin/env python3
"""Fail-closed, local-only staging of an already built/tested final v0.1.4 APK.

No build, signing, installation, Git mutation, upload, or secret lookup is performed.
The output is the Git-ignored app/build/reports/v0.1.4/release directory; an
existing destination is never replaced. Counts below are minimum scope gates,
NOT successful results. Published counts are read from the pinned JSON reports.

Evidence contract (paths relative to the evidence JSON):
  {
    "schema_version": 1, "source_commit": "<full verified HEAD>",
    "current": {
      "api37": {"path": "api37/result.json", "sha256": "<report hash>",
                "source_commit": "<full HEAD>", "api": 37},
      "api28": { ... , "api": 28}, "visual": { ... }, "phone": { ... },
      "jvm": { ... }, "final_signed_smoke": { ... }
    },
    "inherited_v013": {
      "large_file": {"path": "large.json", "sha256": "<report hash>",
                     "source_commit": "<local historical commit>", "version": "0.1.3"},
      "long_hls": { ... }, "background": { ... }
    }
  }

Each current entry is an explicit verified source binding, not inferred from a
filename/date. Bare historical runner results lack a Git binding: the parent
must verify the frozen inputs before binding them, or rerun them. Do NOT label
old candidate evidence as a final-APK rerun. Optional environment_path AND
environment_sha256 pin the native environment.json; source_inputs_sha256 must
then match its source_sha256. Report-native source_commit/source_revision, when
present, must agree with the binding. All current bindings must equal HEAD.

Runner reports need ok/complete/source_unchanged=true, positive integer
expected=completed=passed, nonpassing=[], and no failure/skip/error/timeout.
JVM reports need ok/complete/source_unchanged=true and a jvm object with
integer tests/failures/errors/skipped (all tests pass, zero nonpassing).
Inherited summaries also need ok/complete/source_unchanged=true; their bindings
must be local ancestor commits. They are explicitly historical evidence, NOT
claims of engine equivalence or new v0.1.4 large/long/background reruns.

Final smoke additionally needs source_commit, application_id, version,
version_code, certificate_sha256, apk_sha256, installed_apk_sha256,
installed_apk_matches=true, api, and final_smoke (runner accounting above).
The smoke hash must match the exact signed APK, not a pre-freeze candidate.
Raw logs, report contents, private paths, keys, and passwords are never staged.

Usage:
  python3 tools/release/freeze_v014.py --apk <final-signed.apk> \\
    --source-commit <full-HEAD> --evidence <verified-evidence.json>
  python3 -m unittest discover -s tools/verification -p test_v014_release_identity.py
"""
import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'io.github.jeckchen666.purebrowser'
VERSION = '0.1.4'
CERTIFICATE = '52fe690aadfd21a1baa4d06d8f75ec7aefa37d59ef43a057b6da4e9cfa84fc90'
APK_NAME = 'PureBrowser-v0.1.4.apk'
DESTINATION = Path('app/build/reports/v0.1.4/release')
ANDROID = '{http://schemas.android.com/apk/res/android}'
MIN_COUNTS = {'api37': 301, 'api28': 162, 'visual': 60, 'phone': 15, 'jvm': 156,
              'final_signed_smoke': 1}
INHERITED = {'large_file', 'long_hls', 'background'}
HEX40 = re.compile(r'[0-9a-f]{40}')
HEX64 = re.compile(r'[0-9a-f]{64}')
COMPONENTS = {
    'activity': {'com.example.purebrowser.MainActivity'},
    'service': {'com.example.purebrowser.download.ControlledDownloadService'},
    'provider': {'androidx.core.content.FileProvider', 'androidx.startup.InitializationProvider'},
    'receiver': {'androidx.profileinstaller.ProfileInstallReceiver'},
}
SECRET_BYTES = re.compile(
    rb'-----BEGIN (?:[A-Z0-9 ]*PRIVATE KEY|CERTIFICATE)-----|'
    rb'\bAKIA[A-Z0-9]{16}\b|\bgh[pousr]_[A-Za-z0-9]{30,}|'
    rb'\bgithub_pat_[A-Za-z0-9_]{40,}|\bsk-(?:proj-)?[A-Za-z0-9_-]{32,}|'
    rb'(?i:(?:storePassword|keyPassword|PB_SIGNING_[A-Z_]*PASSWORD)\s*[=:]\s*["\']?[^\s"\']{4,})'
)
TEST_DEX = re.compile(rb'L(?:androidx/test/|org/junit/|android/test/|'
                      rb'com/example/purebrowser/[^;\x00]*(?:Test|Audit|Fixture)(?:\$[^;\x00]*)?;)')


class Refusal(ValueError):
    """An unverified input cannot become a staged release."""


def require(condition, reason):
    if not condition:
        raise Refusal(reason)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def run(command, cwd=None):
    # Never forward signing credentials to read-only SDK/Git processes.
    env = {k: v for k, v in os.environ.items()
           if not k.startswith('PB_SIGNING_') and not re.search(r'PASSWORD|TOKEN|SECRET', k)}
    result = subprocess.run([str(x) for x in command], cwd=cwd, env=env,
                            capture_output=True, text=True, timeout=120)
    # No subprocess output in errors: reports/CLI output can contain private data.
    require(result.returncode == 0, f'{Path(command[0]).name} check failed')
    return result.stdout.strip()


def git(root, *args):
    return run(['git', *args], cwd=root)


def verify_commit(root, commit, current=True):
    require(isinstance(commit, str) and HEX40.fullmatch(commit), 'Full lowercase Git commit required')
    require(git(root, 'rev-parse', '--verify', commit + '^{commit}') == commit,
            'Commit is not a verified local Git commit')
    if current:
        require(git(root, 'rev-parse', 'HEAD') == commit, 'Source commit is not local HEAD')
        require(not git(root, 'status', '--porcelain', '--untracked-files=all'),
                'Source changed: tracked or untracked working-tree inputs are not frozen')
    else:
        git(root, 'merge-base', '--is-ancestor', commit, 'HEAD')


def discover_sdk(root):
    candidates = []
    properties = root / 'local.properties'
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith('sdk.dir='):
                candidates.append(Path(line.split('=', 1)[1].replace('\\:', ':').replace('\\ ', ' ')))
    candidates += [Path(os.environ[key]) for key in ('ANDROID_SDK_ROOT', 'ANDROID_HOME')
                   if os.environ.get(key)]
    candidates += [Path.home() / 'Library/Android/sdk', Path.home() / 'Android/Sdk']
    for sdk in candidates:
        sdk = sdk.expanduser().resolve()
        analyzers = sorted(sdk.glob('cmdline-tools/*/bin/apkanalyzer'))
        latest = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
        if latest in analyzers:
            analyzers.remove(latest)
            analyzers.insert(0, latest)
        signers = [p for p in sdk.glob('build-tools/*/apksigner')
                   if re.fullmatch(r'\d+(?:\.\d+){2}', p.parent.name)]
        signers.sort(key=lambda p: tuple(map(int, p.parent.name.split('.'))), reverse=True)
        if analyzers and signers and all(os.access(p, os.X_OK) for p in (analyzers[0], signers[0])):
            return analyzers[0], signers[0]
    raise Refusal('Official SDK apkanalyzer/apksigner not found locally')


def validate_identity(identity, commit, apk_hash=None):
    require(identity.get('application_id') == PACKAGE, 'Wrong application ID')
    require(identity.get('version') == VERSION, 'Wrong final version')
    code = identity.get('version_code')
    require(type(code) is int and 12 < code <= 2100000000, 'Final versionCode must be >12')
    require(identity.get('certificate_sha256') == CERTIFICATE, 'Wrong signing certificate')
    require(identity.get('source_commit') == commit, 'Wrong source revision')
    if apk_hash is not None:
        require(identity.get('apk_sha256') == apk_hash, 'Wrong APK identity')


def parse_xml(text):
    require('<!DOCTYPE' not in text and '<!ENTITY' not in text, 'Unsafe XML input')
    try:
        return ET.fromstring(text)
    except ET.ParseError:
        raise Refusal('SDK XML decode failed') from None


def validate_manifest(text):
    manifest = parse_xml(text)
    require(manifest.tag == 'manifest' and manifest.get('package') == PACKAGE, 'Wrong manifest package')
    require(not manifest.findall('instrumentation'), 'Instrumentation in release APK')
    applications = manifest.findall('application')
    require(len(applications) == 1, 'Missing or ambiguous application')
    app = applications[0]
    for key in ('debuggable', 'testOnly', 'usesCleartextTraffic'):
        require(app.get(ANDROID + key, 'false') == 'false', f'Unsafe application {key}')
    network = app.get(ANDROID + 'networkSecurityConfig', '')
    require(network == '@xml/network_security_config' or
            re.fullmatch(r'@ref/0x[0-9a-fA-F]{8}', network), 'Missing network security policy')
    seen = set()
    for tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider'):
        for node in app.findall(tag):
            name = node.get(ANDROID + 'name', '')
            require(name in COMPONENTS.get(tag, set()) and (tag, name) not in seen,
                    'Unknown, duplicate, debug, or test component')
            seen.add((tag, name))
            if name == 'androidx.profileinstaller.ProfileInstallReceiver':
                require(node.get(ANDROID + 'permission') == 'android.permission.DUMP',
                        'Profile installer must remain DUMP-protected')
            elif tag != 'activity':
                require(node.get(ANDROID + 'exported') == 'false', 'Unexpected exported component')
    require(('activity', 'com.example.purebrowser.MainActivity') in seen, 'Missing release activity')
    # Reject hidden debug/test declarations in authorities, metadata or permissions too.
    require(not re.search(r'(?i)(androidx\.test|org\.junit|\.debug\b|testonly|test[_-]?ca|'
                          r'fixture|localhost|127\.0\.0\.1)',
                          text.replace('android:testOnly="false"', '')), 'Test/debug manifest declaration')
    return manifest, network


def validate_network_xml(text):
    root = parse_xml(text)
    require(root.tag == 'network-security-config', 'Wrong network security resource')
    # Closed policy: no domain exceptions, user/custom CA, pins, or debug overrides.
    require(not root.attrib and len(root) == 1 and root[0].tag == 'base-config',
            'Network exceptions or test CA configuration')
    base = root[0]
    require(base.attrib == {'cleartextTrafficPermitted': 'false'} and len(base) == 0,
            'HTTP exception, trust override, or test CA')


def resource_key(table, resource_id):
    """Bind an apkanalyzer numeric manifest reference to its ARSC key name.

    apkanalyzer resources value accepts names, not numeric resource IDs. Parse
    only the table/package/type headers and key pool; let the official SDK do
    all value and binary XML decoding. Unsupported encodings fail closed.
    """
    def u16(offset):
        require(0 <= offset <= len(table) - 2, 'Truncated resource table')
        return struct.unpack_from('<H', table, offset)[0]

    def u32(offset):
        require(0 <= offset <= len(table) - 4, 'Truncated resource table')
        return struct.unpack_from('<I', table, offset)[0]

    def chunks(start, end):
        require(0 <= start <= end <= len(table), 'Invalid resource chunk bounds')
        while start < end:
            header, size = u16(start + 2), u32(start + 4)
            require(8 <= header <= size and start + size <= end, 'Invalid resource chunk')
            yield start, u16(start), header, size
            start += size
        require(start == end, 'Unaligned resource table')

    def pool_string(pool, index):
        require(u16(pool) == 1, 'Invalid resource key pool')
        header, size, count = u16(pool + 2), u32(pool + 4), u32(pool + 8)
        require(index < count and 28 <= header <= size and header + count * 4 <= size,
                'Missing resource key')
        start = pool + u32(pool + 20) + u32(pool + header + index * 4)
        end = pool + size
        require(end <= len(table) and start < end, 'Invalid resource key offset')
        if u32(pool + 16) & 0x100:  # UTF-8: UTF-16 length, then byte length
            def length8(offset):
                require(offset < end, 'Truncated UTF-8 resource key')
                value = table[offset]
                if value & 0x80:
                    require(offset + 1 < end, 'Truncated UTF-8 key length')
                    return ((value & 0x7f) << 8) | table[offset + 1], offset + 2
                return value, offset + 1
            _, start = length8(start)
            length, start = length8(start)
            require(start + length < end and table[start + length] == 0, 'Invalid UTF-8 resource key')
            return table[start:start + length].decode('utf-8')
        length = u16(start)
        start += 2
        if length & 0x8000:
            length = ((length & 0x7fff) << 16) | u16(start)
            start += 2
        require(start + length * 2 + 2 <= end and u16(start + length * 2) == 0,
                'Invalid UTF-16 resource key')
        return table[start:start + length * 2].decode('utf-16le')

    require(u16(0) == 2 and u32(4) == len(table), 'Invalid resource table root')
    keys = set()
    for package, kind, header, size in chunks(u16(2), len(table)):
        if kind != 0x0200 or u32(package + 8) != resource_id >> 24:
            continue
        require(header >= 284, 'Invalid resource package header')
        key_pool = package + u32(package + 276)
        type_offset = u32(package + 284) if header >= 288 else 0
        for chunk, child_kind, child_header, child_size in chunks(package + header, package + size):
            if child_kind != 0x0201 or table[chunk + 8] + type_offset != (resource_id >> 16) & 0xff:
                continue
            require(child_header >= 20 and table[chunk + 9] == 0, 'Unsupported sparse resource table')
            index, count = resource_id & 0xffff, u32(chunk + 12)
            if index >= count:
                continue
            require(child_header + count * 4 <= child_size, 'Invalid resource offsets')
            offset = u32(chunk + child_header + index * 4)
            if offset == 0xffffffff:
                continue
            entry = chunk + u32(chunk + 16) + offset
            require(chunk + child_header <= entry and entry + 8 <= chunk + child_size,
                    'Invalid resource entry')
            keys.add(pool_string(key_pool, u32(entry + 4)))
    require(len(keys) == 1, 'Missing or ambiguous network resource key')
    return keys.pop()


def inspect_apk(apk, commit, analyzer, signer):
    def analyze(*args):
        return run([analyzer, *args, apk])

    signature = run([signer, 'verify', '--verbose', '--print-certs', '--Werr', apk])
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', signature, re.M)
    require(certs == [CERTIFICATE] and re.search(r'^Number of signers: 1$', signature, re.M),
            'Unknown or multiple signing certificates')
    require(re.search(r'^Verified using v[23](?:\.1)? scheme .*: true$', signature, re.M),
            'Missing APK v2/v3 signature')
    package = analyze('manifest', 'application-id')
    version = analyze('manifest', 'version-name')
    code_text = analyze('manifest', 'version-code')
    require(re.fullmatch(r'[0-9]+', code_text), 'Invalid versionCode')
    require(analyze('manifest', 'debuggable') == 'false', 'Debuggable APK')
    manifest, network = validate_manifest(analyze('manifest', 'print'))
    require(manifest.get(ANDROID + 'versionName') == version and
            manifest.get(ANDROID + 'versionCode') == code_text, 'Inconsistent manifest identity')
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate APK ZIP entries')
        require('META-INF/version-control-info.textproto' in names, 'Missing embedded Git revision')
        proto = archive.read('META-INF/version-control-info.textproto').decode('utf-8')
        # Require exactly the root Git repository block, not a matching substring.
        require(re.fullmatch(r'\s*repositories\s*\{\s*system:\s*GIT\s*'
                             r'local_root_path:\s*"\$PROJECT_DIR"\s*revision:\s*"' +
                             re.escape(commit) + r'"\s*\}\s*', proto),
                'Wrong or ambiguous embedded Git revision')
        xml_paths = []
        for entry in archive.infolist():
            name = entry.filename
            path = PurePosixPath(name)
            require(not name.startswith('/') and '..' not in path.parts and '\\' not in name,
                    'Unsafe APK entry path')
            require(not entry.flag_bits & 1 and entry.file_size <= 64 * 1024 * 1024,
                    'Encrypted or oversized APK entry')
            if entry.is_dir():
                continue
            require(not re.search(r'(?i)(?:^|/)(?:\.env(?:\..*)?|local\.properties|'
                                  r'[^/]*(?:fixture|test[_-]?ca|keystore|secret|password)[^/]*)$|'
                                  r'\.(?:jks|keystore|p12|pfx|key|pem|crt|cer|der)$', name),
                    'Test CA, secret, or signing material in APK')
            if name.startswith(('assets/', 'res/raw/')):
                require(not re.search(r'(?i)(?:test|debug|sample|mock|localhost)|'
                                      r'\.(?:mp4|webm|m3u8|ts|wav|mp3|aac)$', name),
                        'Test/media fixture asset in APK')
            data = archive.read(entry)
            require(not SECRET_BYTES.search(data), 'Potential secret or bundled CA in APK')
            if name.endswith('.dex'):
                require(not TEST_DEX.search(data), 'Test/fixture classes in production DEX')
            if name.startswith('res/') and name.endswith('.xml'):
                xml_paths.append(name)
        require(xml_paths, 'No compiled XML resources')
        # Inspect every XML, including R8/AGP-shortened paths and qualified variants.
        network_paths = set()
        for name in xml_paths:
            text = analyze('resources', 'xml', '--file', '/' + name)
            node = parse_xml(text)
            if node.tag == 'network-security-config':
                validate_network_xml(text)
                network_paths.add(name)
            require(not any(n.tag in ('debug-overrides', 'trust-anchors', 'certificates')
                            for n in node.iter()), 'Bundled trust/test CA override')
        require(network_paths, 'Network security policy not decoded')
        # Resolve the manifest's actual resource ID as well as the named policy.
        reference = network.split('/', 1)[1]
        resource_name = (resource_key(archive.read('resources.arsc'), int(reference, 16))
                         if reference.startswith('0x') else reference)
        require(resource_name == 'network_security_config', 'Wrong manifest network resource key')
        configs = analyze('resources', 'configs', '--type', 'xml').splitlines()
        require(configs, 'Missing XML resource configurations')
        resolved = set()
        for config in configs:
            value = analyze('resources', 'value', '--type', 'xml', '--name', resource_name,
                            '--config', config).lstrip('/')
            if value:
                require(value in network_paths, 'Manifest references a non-policy network XML')
                resolved.add(value)
        require(resolved, 'Manifest network security reference could not be resolved')
    identity = {'application_id': package, 'version': version, 'version_code': int(code_text),
                'source_commit': commit, 'certificate_sha256': CERTIFICATE,
                'apk_sha256': sha256(apk), 'apk_bytes': apk.stat().st_size,
                'apk_filename': APK_NAME, 'non_debuggable': True,
                'apk_embedded_revision_matches_source_commit': True}
    validate_identity(identity, commit)
    return identity


def load_json(path):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    try:
        value = json.loads(Path(path).read_text(), object_pairs_hook=pairs,
                           parse_constant=lambda _: (_ for _ in ()).throw(Refusal('Nonfinite JSON number')))
    except (UnicodeError, json.JSONDecodeError):
        raise Refusal('Invalid evidence JSON') from None
    require(isinstance(value, dict), 'Evidence must be a JSON object')
    return value


def reject_nonpassing(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in ('ok', 'complete', 'source_unchanged', 'installed_apk_matches'):
                require(item is True, f'Report {key} is not true')
            if key in ('failed', 'skipped', 'incomplete', 'source_changed', 'timed_out', 'aborted'):
                require(item is False or type(item) is int and item == 0,
                        f'Report contains {key}')
            if key in ('failures', 'errors', 'skips', 'nonpassing'):
                require(item == [] or type(item) is int and item == 0, f'Report contains {key}')
            if key == 'shell_exit':
                require(type(item) is int and item == 0, 'Failed report shell exit')
            if key in ('status', 'result', 'outcome') and isinstance(item, str):
                require(item.lower() in ('passed', 'pass', 'success', 'ok'), 'Nonpassing report status')
            reject_nonpassing(item)
    elif isinstance(value, list):
        for item in value:
            reject_nonpassing(item)


def validate_result(report, minimum=1, jvm=False):
    reject_nonpassing(report)
    for key in ('ok', 'complete', 'source_unchanged'):
        require(report.get(key) is True, f'Missing successful report {key}')
    counts = report.get('jvm') if jvm else report
    require(isinstance(counts, dict), 'Missing JVM accounting')
    if jvm:
        total = counts.get('tests')
        for key in ('failures', 'errors', 'skipped'):
            require(type(counts.get(key)) is int and counts[key] == 0, 'Missing/nonzero JVM nonpassing count')
    else:
        total = counts.get('expected')
        require(type(total) is int and counts.get('nonpassing') == [], 'Missing runner accounting')
        require(all(type(counts.get(k)) is int and counts[k] == total for k in ('completed', 'passed')),
                'Incomplete or inconsistent report counts')
    require(type(total) is int and total >= minimum, 'Report does not cover required scope')
    return {'passed': total, 'failures': 0, 'errors': 0, 'skips': 0}


def pinned_json(base, entry, path_key='path', hash_key='sha256'):
    path = entry.get(path_key)
    digest = entry.get(hash_key)
    require(isinstance(path, str) and isinstance(digest, str) and HEX64.fullmatch(digest),
            'Missing report path or SHA-256 pin')
    source = (base / path).resolve()
    require(source.is_file() and sha256(source) == digest, 'Missing or changed pinned report')
    return load_json(source), source, digest


def verify_report_revision(value, revision):
    if isinstance(value, dict):
        for key, item in value.items():
            if key in ('source_commit', 'source_revision', 'git_revision', 'git_commit', 'revision'):
                require(item == revision, 'Report has wrong source revision')
            verify_report_revision(item, revision)
    elif isinstance(value, list):
        for item in value:
            verify_report_revision(item, revision)


def validate_evidence(path, identity, root):
    bundle_digest = sha256(path)
    bundle = load_json(path)
    require(sha256(path) == bundle_digest, 'Evidence bundle changed during read')
    require(type(bundle.get('schema_version')) is int and bundle['schema_version'] == 1,
            'Unknown evidence schema')
    commit = identity['source_commit']
    require(bundle.get('source_commit') == commit, 'Wrong evidence source commit')
    current, inherited = bundle.get('current'), bundle.get('inherited_v013')
    require(isinstance(current, dict) and set(current) == set(MIN_COUNTS), 'Missing/unknown current report gate')
    require(isinstance(inherited, dict) and set(inherited) == INHERITED, 'Missing/unknown inherited v013 gate')
    records, pins = {}, {Path(path).resolve(): bundle_digest}
    for historical, entries in ((False, current), (True, inherited)):
        for role, entry in sorted(entries.items()):
            require(isinstance(entry, dict), 'Report binding must be an object')
            revision = entry.get('source_commit')
            if historical:
                require(entry.get('version') == '0.1.3' and revision != commit,
                        'Inherited report must be explicitly v0.1.3, not a new rerun')
                verify_commit(root, revision, current=False)
            else:
                require(revision == commit, 'Current report binding has wrong revision')
            report, report_path, digest = pinned_json(Path(path).resolve().parent, entry)
            require(report_path not in pins, 'One report cannot satisfy multiple evidence gates')
            pins[report_path] = digest
            reject_nonpassing(report)
            verify_report_revision(report, revision)
            # Never ignore a contradictory artifact identity in an evidence report.
            # Native debug runner accounting normally has no release identity fields.
            if not historical:
                for field, expected in (('application_id', PACKAGE), ('version', VERSION),
                                        ('certificate_sha256', CERTIFICATE)):
                    if field in report:
                        require(report[field] == expected, f'{role}: wrong report {field}')
            for key in ('ok', 'complete', 'source_unchanged'):
                require(report.get(key) is True, f'{role}: missing successful {key}')
            record = {'report_sha256': digest, 'source_commit': revision,
                      'scope': 'inherited v0.1.3; not rerun on final v0.1.4 APK' if historical else
                               'v0.1.4 frozen-source evidence; not a final signed APK rerun'}
            if 'environment_path' in entry or 'environment_sha256' in entry:
                environment, env_path, env_digest = pinned_json(Path(path).resolve().parent, entry,
                                                               'environment_path', 'environment_sha256')
                pins[env_path] = env_digest
                reject_nonpassing(environment)
                verify_report_revision(environment, revision)
                fingerprint = entry.get('source_inputs_sha256')
                require(isinstance(fingerprint, str) and HEX64.fullmatch(fingerprint) and
                        environment.get('source_sha256') == fingerprint, 'Wrong source input fingerprint')
                if 'api' in entry:
                    require(str(environment.get('api')) == str(entry['api']), 'Wrong environment API')
                record.update(environment_sha256=env_digest, source_inputs_sha256=fingerprint)
            if historical:
                # No private URLs, paths, measurements, or raw old report contents copied.
                record['version'] = '0.1.3'
            elif role == 'final_signed_smoke':
                validate_identity(report, commit, identity['apk_sha256'])
                require(report['version_code'] == identity['version_code'], 'Wrong smoke versionCode')
                require(report.get('installed_apk_matches') is True and
                        report.get('installed_apk_sha256') == identity['apk_sha256'],
                        'Final installed APK hash is not verified')
                require(type(report.get('api')) is int and report['api'] >= 28, 'Missing smoke device API')
                smoke = report.get('final_smoke')
                require(isinstance(smoke, dict), 'Missing final smoke accounting')
                # Native parse_results has no per-subresult source flag; top-level binding is required.
                record.update(validate_result(dict(smoke, source_unchanged=report['source_unchanged'])))
                record.update(api=report['api'], scope='current final signed APK; installed hash verified')
            else:
                record.update(validate_result(report, MIN_COUNTS[role], jvm=role == 'jvm'))
                if role in ('api37', 'api28'):
                    api = 37 if role == 'api37' else 28
                    require(type(entry.get('api')) is int and entry['api'] == api, 'Wrong cohort API')
                    if 'api' in report:
                        require(str(report['api']) == str(api), 'Wrong report API')
                    record['api'] = api
            records[('inherited_v013/' if historical else 'current/') + role] = record
    return records, pins


def write_checksums(directory):
    files = sorted(p for p in directory.iterdir() if p.name != 'SHA256SUMS.txt')
    require(all(p.is_file() and not p.is_symlink() for p in files), 'Unexpected staging entries')
    (directory / 'SHA256SUMS.txt').write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in files))


def stage(apk, commit, evidence, root=ROOT):
    root = Path(root).resolve()
    apk, evidence = Path(apk).resolve(), Path(evidence).resolve()
    require(apk.is_file() and evidence.is_file(), 'APK or evidence JSON missing')
    verify_commit(root, commit)
    destination = root / DESTINATION
    require(not destination.exists() and not destination.is_symlink(), 'Release destination already exists')
    require(destination.resolve() == destination, 'Release parent is a symlink')
    require(git(root, 'check-ignore', '--no-index', str(destination / 'BUILD-IDENTITY.json')),
            'Release staging must be outside Git tracking')
    require(not git(root, 'ls-files', '--', str(destination)), 'Release destination contains tracked files')
    analyzer, signer = discover_sdk(root)
    initial_hash = sha256(apk)
    identity = inspect_apk(apk, commit, analyzer, signer)
    require(identity['apk_sha256'] == initial_hash, 'APK changed during audit')
    records, pins = validate_evidence(evidence, identity, root)
    # Nothing is written until all release gates have passed.
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.release-pending-', dir=destination.parent))
    try:
        shutil.copyfile(apk, temporary / APK_NAME)
        require(sha256(temporary / APK_NAME) == initial_hash and sha256(apk) == initial_hash,
                'APK changed during staging')
        identity.update(release_type='pre-release', staged_at=dt.datetime.now(dt.timezone.utc).isoformat(),
                        sdk_tools={'apkanalyzer': analyzer.parent.parent.name, 'apksigner': signer.parent.name},
                        evidence=records, evidence_bundle_sha256=pins[evidence],
                        security_audit={'https_only_policy': True, 'known_production_components_only': True,
                                        'no_test_assets_classes_ca_or_detected_secrets': True})
        (temporary / 'BUILD-IDENTITY.json').write_text(json.dumps(identity, indent=2, sort_keys=True) + '\n')
        (temporary / 'SOURCE-COMMIT.txt').write_text(commit + '\n')
        (temporary / 'SIGNING-CERTIFICATE.txt').write_text(
            f'PureBrowser Release certificate SHA-256: {CERTIFICATE}\n'
            f'Application ID: {PACKAGE}\nVersion: {VERSION} / versionCode {identity["version_code"]}\n')
        lines = [f'# PureBrowser {VERSION} verification', '', f'Source commit: `{commit}`.',
                 f'Final signed APK SHA-256: `{initial_hash}`.', '',
                 '## Current v0.1.4 evidence (counts from pinned reports)', '',
                 '| Gate | Passed | Failures/errors/skips | API | Scope |',
                 '| --- | ---: | --- | --- | --- |']
        for key, record in sorted(records.items()):
            if key.startswith('current/'):
                lines.append(f'| {key.split("/")[1]} | {record["passed"]} | 0/0/0 | '
                             f'{record.get("api", "not specified")} | {record["scope"]} |')
        lines += ['', 'API37 full regression includes carried safety tests; it is not a claim of',
                  '301 newly authored tests. Visual/phone cohorts are separate scopes, not',
                  'unique tests to add to the API37 total. Only final_signed_smoke is asserted',
                  'to run on this exact final signed APK.', '', '## Inherited v0.1.3 evidence', '']
        for key, record in sorted(records.items()):
            if key.startswith('inherited_v013/'):
                lines.append(f'- {key.split("/")[1]}: passed historical report SHA-256 '
                             f'`{record["report_sha256"]}`, source `{record["source_commit"]}`; '
                             'NOT rerun on v0.1.4.')
        lines += ['', 'Historical engine evidence is contextual; this staging helper does not prove',
                  'engine equivalence. OEM long-term stress and broad real-device coverage are',
                  'not established by these reports. No missing report was marked passed.',
                  'APK scanning rejects known test/secret patterns; it is not a proof that',
                  'arbitrary unknown or encoded secrets are absent.', '']
        (temporary / 'VERIFICATION-SUMMARY.md').write_text('\n'.join(lines))
        write_checksums(temporary)
        verify_commit(root, commit)
        require(all(sha256(p) == digest for p, digest in pins.items()), 'Evidence changed during staging')
        require(sha256(apk) == initial_hash, 'APK changed before publication')
        # mkdir is an exclusive reservation: unlike rename, cannot replace another
        # concurrent stager's empty directory. Roll back only our reservation.
        destination.mkdir()
        try:
            for file in sorted(temporary.iterdir()):
                os.replace(file, destination / file.name)
        except BaseException:
            shutil.rmtree(destination)
            raise
        return destination
    finally:
        shutil.rmtree(temporary)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--apk', type=Path, required=True, help='Already signed FINAL APK; never builds/signs')
    parser.add_argument('--source-commit', required=True, help='Full verified local clean HEAD commit')
    parser.add_argument('--evidence', type=Path, required=True, help='Hash-pinned evidence JSON; schema above')
    args = parser.parse_args(argv)
    try:
        output = stage(args.apk, args.source_commit, args.evidence)
    except Refusal as error:
        print(f'REFUSED: {error}. No release staged.', file=sys.stderr)
        return 1
    except (OSError, UnicodeError, zipfile.BadZipFile, subprocess.SubprocessError):
        # Intentionally never echo raw input, report values, passwords, or CLI output.
        print('REFUSED: release inputs did not satisfy the freeze gates. No release staged.', file=sys.stderr)
        return 1
    print(f'Staged verified local release: {output}')
    return 0


if __name__ == '__main__':
    sys.exit(main())

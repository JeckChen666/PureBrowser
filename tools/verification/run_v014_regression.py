#!/usr/bin/env python3
"""v0.1.4 frozen UI + inherited safety regression; never runs on a user's AVD.

Build first: ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
This runner does not clear app data, change denial-test requirements, or hide skips.
"""
import argparse
import datetime
import hashlib
import json
import re
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'io.github.jeckchen666.purebrowser.debug'
PERMISSION = 'android.permission.POST_NOTIFICATIONS'
FOCUSED = [
    'com.example.purebrowser.download.ServiceWorkflowAudit#realWebsiteCookieSourceAndForegroundDownloadsAreBounded',
    'com.example.purebrowser.download.PrivacyBoundaryTest',
    'com.example.purebrowser.ui.browser.BrowserDownloadFixtureTest',
    'com.example.purebrowser.ui.browser.ProductWorkflowTest',
    'com.example.purebrowser.ui.browser.RoundTwoProductTest',
    'com.example.purebrowser.ui.browser.HlsBrowserJourneyTest',
]


def parse_results(raw, expected):
    """Android instrumentation's shell exit status alone cannot prove success."""
    current = {}
    results = []
    for line in raw.splitlines():
        match = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if match:
            current[match[1]] = match[2]
        match = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if match:
            code = int(match[1])
            # sendStatus(0, evidence) is progress, not a completed JUnit test.
            # Unidentified errors still fail closed instead of disappearing.
            if code != 1 and (code != 0 or {'class', 'test'} <= current.keys()):
                results.append(dict(current, code=code))
            current = {}
    passed = sum(r['code'] == 0 for r in results)
    failures = [r for r in results if r['code'] != 0]
    complete = (re.search(r'^INSTRUMENTATION_CODE: -1\s*$', raw, re.M) is not None
                and not re.search(r'INSTRUMENTATION_(FAILED|ABORTED)|Process crashed', raw))
    return {'expected': expected, 'completed': len(results), 'passed': passed,
            'nonpassing': failures, 'complete': complete,
            'ok': complete and len(results) == expected and passed == expected}


def source_identity():
    paths = sorted(p for p in (ROOT/'app/src').rglob('*') if p.is_file())
    paths += sorted(p for p in ROOT.glob('*.kts'))
    paths += sorted(p for p in (ROOT/'gradle').rglob('*') if p.is_file())
    paths += sorted((ROOT/'tools/fixtures').glob('*.py'))
    paths += [Path(__file__), Path(__file__).with_name('v014-cohort.json'), Path(__file__).with_name('v014-cohort-counts.json')]
    paths += [ROOT/'gradle.properties', ROOT/'app/build.gradle.kts']
    digest = hashlib.sha256()
    for path in paths:
        digest.update(str(path.relative_to(ROOT)).encode())
        digest.update(b'\0')
        digest.update(path.read_bytes())
        digest.update(b'\0')
    return digest.hexdigest()


def build_current(output):
    with (output/'build.txt').open('w') as log:
        subprocess.run([str(ROOT/'gradlew'), ':app:assembleDebug', ':app:assembleDebugAndroidTest',
                        '--console=plain'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                       check=True, timeout=600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', default='emulator-5560')
    parser.add_argument('--scope', choices=['focused', 'workflow', 'browser', 'product', 'share', 'full'], default='focused')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--reset-lab', action='store_true', help='Explicitly clear ONLY dedicated QA Debug private data; never public files or formal/original packages')
    parser.add_argument('--timeout', type=int, default=1800)
    args = parser.parse_args()
    if not 60 <= args.timeout <= 3600:
        parser.error('timeout must be 60..3600 seconds')
    output = args.output.resolve()
    assert not (output/'instrumentation.txt').exists(), 'Use a new output directory; preserve prior evidence'
    output.mkdir(parents=True, exist_ok=True)
    prefix = ['adb', '-s', args.device]
    def adb(*command):
        return subprocess.check_output(prefix + list(command), timeout=120 if command[0] == 'install' else 30).decode().strip()
    assert adb('get-state') == 'device'
    avd = adb('emu', 'avd', 'name').splitlines()[0]
    assert avd == 'PureBrowser_API37_ReleaseLab', 'Refusing non-dedicated AVD; never reset user data'
    assert adb('shell', 'getprop', 'sys.boot_completed') == '1'
    assert 'mFinished=false' not in adb('shell', 'dumpsys', 'activity'), 'Another instrumentation is running'
    assert int(adb('shell', 'getprop', 'ro.build.version.sdk')) >= 33
    apks = [ROOT/'app/build/outputs/apk/debug/app-debug.apk',
            ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    source_hash = source_identity()
    build_current(output)
    assert source_identity() == source_hash, 'Sources changed while building; rerun against a frozen worktree'
    for apk in apks:
        assert apk.is_file(), f'Build first: {apk}'
        adb('install', '-r', str(apk))
    if args.reset_lab:
        assert adb('shell', 'pm', 'clear', PACKAGE) == 'Success'
    dump = adb('shell', 'dumpsys', 'package', PACKAGE)
    granted = re.search(re.escape(PERMISSION) + r': granted=(true|false)', dump)
    assert granted, 'Cannot determine original notification permission'
    original_grant = granted[1] == 'true'
    old_timeout = adb('shell', 'settings', 'get', 'system', 'screen_off_timeout')
    reverse = adb('reverse', '--list')
    mappings = {line.split()[1]: line.split()[2] for line in reverse.splitlines() if len(line.split()) == 3}
    added = []
    servers = []
    logs = []
    metadata = {'started_at': datetime.datetime.now().astimezone().isoformat(), 'scope': args.scope, 'device': args.device, 'avd': avd,
                'source_sha256': source_hash, 'reset_dedicated_debug_private_data': args.reset_lab,
                'apk_sha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in apks},
                'original_notification_grant': original_grant, 'original_screen_timeout': old_timeout,
                'api': adb('shell', 'getprop', 'ro.build.version.sdk'),
                'webview': adb('shell', 'dumpsys', 'webviewupdate'),
                'app_version': re.search(r'versionName=([^\s]+)', dump)[1]}
    try:
        adb('shell', 'pm', 'grant', PACKAGE, PERMISSION)
        assert re.search(re.escape(PERMISSION) + r': granted=true', adb('shell', 'dumpsys', 'package', PACKAGE))
        adb('shell', 'settings', 'put', 'system', 'screen_off_timeout', '2147483647')
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        for port, script, extra in [
            (8765, 'serve_video_fixture.py', []),
            (8766, 'serve_hls_fixture.py', ['--max-seconds', '7200']),
            (8768, 'serve_resume_fixture.py', ['--max-seconds', '7200']),
        ]:
            name = f'tcp:{port}'
            assert name not in mappings or mappings[name] == name, 'Conflicting reverse mapping'
            if name not in mappings:
                adb('reverse', name, name)
                added.append(name)
            with socket.socket() as probe:
                # Allow reuse after our prior server closes (TIME_WAIT), never a
                # second active listener. listen() also rejects a conflicting bind.
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('127.0.0.1', port))  # Refuse an existing server, even if it looks healthy.
                probe.listen(1)
            log = (output/f'fixture-{port}.txt').open('w')
            logs.append(log)
            servers.append(subprocess.Popen(['python3', '-u', str(ROOT/'tools/fixtures'/script),
                                             '--port', str(port)] + extra, stdout=log, stderr=log))
        deadline = time.monotonic() + 45
        endpoints = {8765: '/sample.mp4?token=demo%2Bsignature', 8766: '/master.m3u8', 8768: '/range.mp4'}
        hashes = {}
        for port, path in endpoints.items():
            while True:
                assert all(p.poll() is None for p in servers), 'Fixture exited; inspect fixture logs'
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}{path}', timeout=5) as response:
                        hashes[port] = hashlib.sha256(response.read()).hexdigest()
                    break
                except (OSError, TimeoutError):
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Fixture {port} did not become healthy') from None
                    time.sleep(.25)
        with urllib.request.urlopen('http://127.0.0.1:8765/sample.webm', timeout=10) as response:
            webm_hash = hashlib.sha256(response.read()).hexdigest()
        cohort = (FOCUSED if args.scope == 'focused' else
                  [FOCUSED[0], 'com.example.purebrowser.ui.browser.RoundTwoProductTest']
                  if args.scope == 'workflow' else
                  [FOCUSED[0], 'com.example.purebrowser.ui.browser.BrowserDownloadFixtureTest']
                  if args.scope == 'browser' else
                  [FOCUSED[0], 'com.example.purebrowser.ui.browser.ProductWorkflowTest']
                  if args.scope == 'product' else
                  [FOCUSED[0], 'com.example.purebrowser.library.CrossUidShareAudit', 'com.example.purebrowser.library.RuntimeFileShareTest']
                  if args.scope == 'share' else
                  json.loads((Path(__file__).with_name('v014-cohort.json')).read_text()))
        expected = {'focused': 11, 'workflow': 3, 'browser': 2, 'product': 2, 'share': 4, 'full': 302}[args.scope]
        command = prefix + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(cohort)]
        for flag in ['videoFixture', 'hlsFixture', 'crossUidShare', 'serviceFixture',
                     'resumeFixture', 'queueFixture', 'dynamicFixture', 'v014DisposableProfile']:
            command += ['-e', flag, 'true']
        command += ['-e', 'fixtureSha256', hashes[8765], '-e', 'fixtureWebmSha256', webm_hash,
                    PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner']
        metadata.update(fixture_sha256=hashes[8765], webm_sha256=webm_hash, classes=cohort)
        (output/'environment.json').write_text(json.dumps(metadata, indent=2)+'\n')
        started = time.monotonic()
        try:
            with (output/'instrumentation.txt').open('w') as log:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout)
            summary = parse_results((output/'instrumentation.txt').read_text(), expected)
            summary.update(shell_exit=result.returncode, elapsed_seconds=round(time.monotonic()-started, 3))
            summary['source_unchanged'] = source_identity() == source_hash
            summary['ok'] = summary['ok'] and result.returncode == 0 and summary['source_unchanged']
        except subprocess.TimeoutExpired:
            adb('shell', 'am', 'force-stop', PACKAGE)
            summary = dict(parse_results((output/'instrumentation.txt').read_text(), expected), ok=False, timed_out=True)
        (output/'result.json').write_text(json.dumps(summary, indent=2)+'\n')
        print(json.dumps(summary, indent=2), flush=True)
        if not summary['ok']:
            raise SystemExit(1)
    finally:
        for process in servers:
            process.terminate()
        for process in servers:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=10)
        for log in logs:
            log.close()
        for name in added:
            adb('reverse', '--remove', name)
        if not original_grant:
            adb('shell', 'pm', 'revoke', PACKAGE, PERMISSION)
        adb('shell', 'settings', 'delete' if old_timeout == 'null' else 'put', 'system', 'screen_off_timeout',
            *([] if old_timeout == 'null' else [old_timeout]))
        restored = adb('shell', 'dumpsys', 'package', PACKAGE)
        metadata['restored_notification_grant'] = bool(re.search(re.escape(PERMISSION) + r': granted=true', restored))
        metadata['restored_screen_timeout'] = adb('shell', 'settings', 'get', 'system', 'screen_off_timeout')
        (output/'environment.json').write_text(json.dumps(metadata, indent=2)+'\n')
        assert metadata['restored_notification_grant'] == original_grant
        assert metadata['restored_screen_timeout'] == old_timeout


if __name__ == '__main__':
    main()

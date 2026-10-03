"""Host-only release gates. All staging uses temporary directories and mocked SDK/Git.

No Gradle, device, shared release output, signing credentials, or Git mutations.
"""
import copy
import importlib.util
import json
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / 'release/freeze_v014.py'
SPEC = importlib.util.spec_from_file_location('freeze_v014', MODULE_PATH)
freeze = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(freeze)
COMMIT = 'a' * 40
HISTORICAL = 'b' * 40
APK_HASH = 'c' * 64


def result(count=1):
    return {'ok': True, 'complete': True, 'source_unchanged': True,
            'expected': count, 'completed': count, 'passed': count,
            'nonpassing': [], 'shell_exit': 0}


def identity(apk_hash=APK_HASH):
    return {'application_id': freeze.PACKAGE, 'version': freeze.VERSION,
            'version_code': 13, 'certificate_sha256': freeze.CERTIFICATE,
            'source_commit': COMMIT, 'apk_sha256': apk_hash, 'apk_filename': freeze.APK_NAME}


def manifest(**attributes):
    attrs = {'networkSecurityConfig': '@xml/network_security_config', **attributes}
    attrs = ' '.join(f'android:{k}="{v}"' for k, v in attrs.items())
    return (f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{freeze.PACKAGE}" android:versionName="0.1.4" android:versionCode="13">'
            f'<application {attrs}><activity android:name="com.example.purebrowser.MainActivity" '
            'android:exported="true" /></application></manifest>')


NETWORK = '<network-security-config><base-config cleartextTrafficPermitted="false" /></network-security-config>'


def write_bundle(base, release_identity=None):
    release_identity = release_identity or identity()
    bundle = {'schema_version': 1, 'source_commit': COMMIT, 'current': {}, 'inherited_v013': {}}
    for role, minimum in freeze.MIN_COUNTS.items():
        report = result(minimum + 2)  # Deliberately not the minimum: publish REAL report counts.
        if role == 'jvm':
            report = {'ok': True, 'complete': True, 'source_unchanged': True,
                      'jvm': {'tests': minimum + 2, 'failures': 0, 'errors': 0, 'skipped': 0}}
        if role == 'final_signed_smoke':
            report = {**release_identity, 'ok': True, 'complete': True, 'source_unchanged': True,
                      'installed_apk_matches': True, 'installed_apk_sha256': release_identity['apk_sha256'],
                      'api': 37, 'final_smoke': result(7)}
        path = base / (role + '.json')
        path.write_text(json.dumps(report))
        entry = {'path': path.name, 'sha256': freeze.sha256(path), 'source_commit': COMMIT}
        if role in ('api37', 'api28'):
            entry['api'] = 37 if role == 'api37' else 28
        bundle['current'][role] = entry
    for role in freeze.INHERITED:
        path = base / (role + '.json')
        path.write_text(json.dumps({'ok': True, 'complete': True, 'source_unchanged': True,
                                    'source_commit': HISTORICAL, 'private_url': 'must-not-be-staged'}))
        bundle['inherited_v013'][role] = {
            'path': path.name, 'sha256': freeze.sha256(path), 'source_commit': HISTORICAL, 'version': '0.1.3'}
    path = base / 'evidence.json'
    path.write_text(json.dumps(bundle))
    return path, bundle


def rewrite_report(base, bundle, group, role, mutate):
    entry = bundle[group][role]
    path = base / entry['path']
    value = json.loads(path.read_text())
    mutate(value)
    path.write_text(json.dumps(value))
    entry['sha256'] = freeze.sha256(path)
    (base / 'evidence.json').write_text(json.dumps(bundle))


class AccountingTests(unittest.TestCase):
    def test_counts_are_from_report_not_minimum(self):
        self.assertEqual(304, freeze.validate_result(result(304), 301)['passed'])

    def test_nonpassing_and_incomplete_reports_refused(self):
        mutations = [
            {'ok': False}, {'complete': False}, {'source_unchanged': False}, {'source_changed': True},
            {'timed_out': True}, {'nonpassing': [{'code': -3}]}, {'completed': 2}, {'passed': 2},
            {'failures': 1}, {'errors': 1}, {'skips': 1}, {'skipped': 1}, {'shell_exit': 255},
            {'status': 'failed'}, {'status': 'incomplete'}, {'expected': True}, {'completed': True},
            {'ok': 'true'}, {'nested': {'source_changed': True}}, {'nested': {'failed': True}},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(freeze.Refusal):
                freeze.validate_result({**result(3), **mutation})
        for field in ('ok', 'complete', 'source_unchanged', 'expected', 'completed', 'passed', 'nonpassing'):
            with self.subTest(missing=field), self.assertRaises(freeze.Refusal):
                report = result(3)
                del report[field]
                freeze.validate_result(report)

    def test_wrong_or_zero_scope_refused(self):
        for count in (0, -1, 300):
            with self.subTest(count=count), self.assertRaises(freeze.Refusal):
                freeze.validate_result(result(count), 301)

    def test_jvm_real_accounting_required(self):
        report = {'ok': True, 'complete': True, 'source_unchanged': True,
                  'jvm': {'tests': 158, 'failures': 0, 'errors': 0, 'skipped': 0}}
        self.assertEqual(158, freeze.validate_result(report, 156, jvm=True)['passed'])
        for key in ('failures', 'errors', 'skipped'):
            for value in (1, False, None):
                bad = copy.deepcopy(report)
                bad['jvm'][key] = value
                with self.subTest(key=key, value=value), self.assertRaises(freeze.Refusal):
                    freeze.validate_result(bad, 156, jvm=True)


class IdentityTests(unittest.TestCase):
    def test_final_identity_refusal(self):
        freeze.validate_identity(identity(), COMMIT, APK_HASH)
        mutations = [{'application_id': freeze.PACKAGE + '.debug'}, {'version': '0.1.4-rc.2'},
                     {'version_code': 12}, {'version_code': '13'}, {'version_code': True},
                     {'certificate_sha256': 'd' * 64}, {'source_commit': HISTORICAL},
                     {'apk_sha256': 'e' * 64}]
        for mutation in mutations:
            with self.subTest(mutation=mutation), self.assertRaises(freeze.Refusal):
                freeze.validate_identity({**identity(), **mutation}, COMMIT, APK_HASH)

    def test_full_local_clean_head_required(self):
        for commit in ('HEAD', COMMIT[:8], '--help', 'A' * 40):
            with self.subTest(commit=commit), patch.object(freeze, 'git') as git, self.assertRaises(freeze.Refusal):
                freeze.verify_commit(Path('/unused'), commit)
            git.assert_not_called()
        with patch.object(freeze, 'git', side_effect=[COMMIT, HISTORICAL]), self.assertRaises(freeze.Refusal):
            freeze.verify_commit(Path('/unused'), COMMIT)
        for status in (' M app/src/main/Main.kt', '?? unexpected.txt'):
            with patch.object(freeze, 'git', side_effect=[COMMIT, COMMIT, status]), self.assertRaises(freeze.Refusal):
                freeze.verify_commit(Path('/unused'), COMMIT)

    def test_manifest_policy_components_and_flags(self):
        freeze.validate_manifest(manifest())
        for text in (manifest(debuggable='true'), manifest(testOnly='true'),
                     manifest(usesCleartextTraffic='true'), manifest(networkSecurityConfig=''),
                     manifest().replace('com.example.purebrowser.MainActivity', 'example.TestActivity'),
                     manifest().replace('</manifest>', '<instrumentation /></manifest>'),
                     manifest().replace('</application>', '<receiver android:name="example.DebugReceiver" /></application>'),
                     manifest().replace(f'package="{freeze.PACKAGE}"', 'package="evil.package"')):
            with self.subTest(text=text), self.assertRaises(freeze.Refusal):
                freeze.validate_manifest(text)
        receiver = '<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:exported="true" />'
        with self.assertRaises(freeze.Refusal):
            freeze.validate_manifest(manifest().replace('</application>', receiver + '</application>'))
        protected = receiver.replace('android:exported', 'android:permission="android.permission.DUMP" android:exported')
        freeze.validate_manifest(manifest().replace('</application>', protected + '</application>'))

    def test_network_no_http_exceptions_or_test_ca(self):
        freeze.validate_network_xml(NETWORK)
        for text in (NETWORK.replace('false', 'true'),
                     NETWORK.replace(' />', '><trust-anchors><certificates src="user" /></trust-anchors></base-config>'),
                     NETWORK.replace('</network-security-config>', '<debug-overrides /></network-security-config>'),
                     NETWORK.replace('</network-security-config>', '<domain-config /></network-security-config>'),
                     '<!DOCTYPE x [<!ENTITY a "x">]>' + NETWORK):
            with self.subTest(text=text), self.assertRaises(freeze.Refusal):
                freeze.validate_network_xml(text)

    def test_resource_id_key_binding(self):
        # Minimal little-endian ARSC with a UTF-8 key pool and one XML entry.
        key = b'network_security_config'
        string = bytes([len(key), len(key)]) + key + b'\0'
        string += b'\0' * (-len(string) % 4)
        pool = struct.pack('<HHI6I', 1, 28, 32 + len(string), 1, 0, 0x100, 32, 0, 0) + string
        # struct above includes the sole zero string offset (last integer).
        entry = struct.pack('<HHI', 8, 0, 0)
        typed = struct.pack('<HHIBBHII', 0x201, 20, 24 + len(entry), 12, 0, 0, 1, 24)
        typed += struct.pack('<I', 0) + entry
        package = bytearray(288)
        struct.pack_into('<HHII', package, 0, 0x200, 288, 288 + len(pool) + len(typed), 0x7f)
        struct.pack_into('<I', package, 276, 288)
        table = struct.pack('<HHII', 2, 12, 12 + len(package) + len(pool) + len(typed), 1)
        table += package + pool + typed
        self.assertEqual('network_security_config', freeze.resource_key(table, 0x7f0c0000))
        for bad in (table[:20], table[:-1], b'not ARSC'):
            with self.subTest(length=len(bad)), self.assertRaises(freeze.Refusal):
                freeze.resource_key(bad, 0x7f0c0000)
        with self.assertRaises(freeze.Refusal):
            freeze.resource_key(table, 0x7f0c0001)


class ApkAuditTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'input.apk'
        self.revision = COMMIT
        self.signature = ('Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n'
                          'Number of signers: 1\nSigner #1 certificate SHA-256 digest: ' + freeze.CERTIFICATE)
        self.outputs = {('manifest', 'application-id'): freeze.PACKAGE,
                        ('manifest', 'version-name'): '0.1.4', ('manifest', 'version-code'): '13',
                        ('manifest', 'debuggable'): 'false', ('manifest', 'print'): manifest(),
                        ('resources', 'xml', '--file', '/res/policy.xml'): NETWORK,
                        ('resources', 'configs', '--type', 'xml'): 'default',
                        ('resources', 'value', '--type', 'xml', '--name', 'network_security_config',
                         '--config', 'default'): 'res/policy.xml'}

    def make_apk(self, extra=None, duplicate=False):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('META-INF/version-control-info.textproto',
                             'repositories { system: GIT local_root_path: "$PROJECT_DIR" revision: "' + self.revision + '" }')
            archive.writestr('res/policy.xml', b'official CLI decodes binary XML')
            archive.writestr('classes.dex', b'production code')
            for name, data in (extra or {}).items():
                archive.writestr(name, data)
            if duplicate:
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore')
                    archive.writestr('classes.dex', b'duplicate')

    def command(self, args, **kwargs):
        if Path(args[0]).name == 'apksigner':
            self.assertEqual(['verify', '--verbose', '--print-certs', '--Werr'], args[1:-1])
            return self.signature
        return self.outputs[tuple(args[1:-1])]

    def inspect(self):
        with patch.object(freeze, 'run', side_effect=self.command):
            return freeze.inspect_apk(self.apk, COMMIT, Path('/sdk/apkanalyzer'), Path('/sdk/apksigner'))

    def test_official_cli_mock_success(self):
        self.make_apk()
        audited = self.inspect()
        self.assertEqual(freeze.sha256(self.apk), audited['apk_sha256'])
        self.assertEqual(13, audited['version_code'])

    def test_bad_embedded_revision_and_duplicate_entries_refused(self):
        self.revision = HISTORICAL
        self.make_apk()
        with self.assertRaises(freeze.Refusal):
            self.inspect()
        self.revision = COMMIT
        self.make_apk(duplicate=True)
        with self.assertRaises(freeze.Refusal):
            self.inspect()

    def test_wrong_cli_identity_refused(self):
        for key, wrong in [(('manifest', 'application-id'), 'evil'),
                           (('manifest', 'version-name'), '0.1.4-rc.2'),
                           (('manifest', 'version-code'), '12'),
                           (('manifest', 'debuggable'), 'true')]:
            original = self.outputs[key]
            self.outputs[key] = wrong
            self.make_apk()
            with self.subTest(key=key), self.assertRaises(freeze.Refusal):
                self.inspect()
            self.outputs[key] = original
        self.signature = self.signature.replace(freeze.CERTIFICATE, '0' * 64)
        with self.assertRaises(freeze.Refusal):
            self.inspect()

    def test_http_ca_secret_assets_and_test_dex_refused(self):
        payloads = [{'assets/sample.mp4': b'media'}, {'assets/test_ca.der': b'ca'},
                    {'assets/.env': b'password'}, {'private.p12': b'key'},
                    {'res/text.txt': b'-----BEGIN RSA PRIVATE KEY-----'},
                    {'classes2.dex': b'Lcom/example/purebrowser/ui/VisualAudit;'},
                    {'classes2.dex': b'Landroidx/test/runner/AndroidJUnitRunner;'},
                    {'../escape': b'unsafe'}]
        for payload in payloads:
            self.make_apk(payload)
            with self.subTest(payload=list(payload)), self.assertRaises(freeze.Refusal):
                self.inspect()
        self.make_apk()
        self.outputs[('resources', 'xml', '--file', '/res/policy.xml')] = NETWORK.replace('false', 'true')
        with self.assertRaises(freeze.Refusal):
            self.inspect()


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name).resolve()
        self.path, self.bundle = write_bundle(self.base)

    def verify(self):
        with patch.object(freeze, 'verify_commit'):
            return freeze.validate_evidence(self.path, identity(), self.base)

    def test_separate_current_and_inherited_counts(self):
        records, pins = self.verify()
        self.assertEqual(303, records['current/api37']['passed'])
        self.assertEqual(158, records['current/jvm']['passed'])
        self.assertEqual(7, records['current/final_signed_smoke']['passed'])
        self.assertNotIn('passed', records['inherited_v013/large_file'])
        self.assertIn('not rerun', records['inherited_v013/long_hls']['scope'])
        self.assertEqual(10, len(pins))

    def test_missing_gate_and_wrong_bundle_revision_refused(self):
        for mutate in (lambda b: b['current'].pop('api37'),
                       lambda b: b['inherited_v013'].pop('background'),
                       lambda b: b.update(source_commit=HISTORICAL),
                       lambda b: b['current']['jvm'].update(source_commit=HISTORICAL),
                       lambda b: b['inherited_v013']['large_file'].update(version='0.1.4')):
            bad = copy.deepcopy(self.bundle)
            mutate(bad)
            self.path.write_text(json.dumps(bad))
            with self.assertRaises(freeze.Refusal):
                self.verify()

    def test_missing_changed_wrong_revision_or_failed_report_refused(self):
        for role, mutation in [('api37', {'complete': False}), ('api28', {'source_unchanged': False}),
                               ('visual', {'nonpassing': [{'code': -3}]}), ('phone', {'source_commit': HISTORICAL}),
                               ('api37', {'application_id': 'wrong.package'}),
                               ('api28', {'certificate_sha256': 'd' * 64}),
                               ('visual', {'revision': HISTORICAL}),
                               ('jvm', {'source_changed': True}),
                               ('final_signed_smoke', {'apk_sha256': 'd' * 64}),
                               ('final_signed_smoke', {'application_id': freeze.PACKAGE + '.debug'}),
                               ('final_signed_smoke', {'certificate_sha256': 'd' * 64}),
                               ('final_signed_smoke', {'installed_apk_sha256': 'd' * 64}),
                               ('final_signed_smoke', {'version_code': 14})]:
            self.path, self.bundle = write_bundle(self.base)
            rewrite_report(self.base, self.bundle, 'current', role, lambda r: r.update(mutation))
            with self.subTest(role=role, mutation=mutation), self.assertRaises(freeze.Refusal):
                self.verify()
        self.path, self.bundle = write_bundle(self.base)
        (self.base / 'api37.json').write_text('{}')  # stale hash
        with self.assertRaises(freeze.Refusal):
            self.verify()
        (self.base / 'api37.json').unlink()
        with self.assertRaises(freeze.Refusal):
            self.verify()

    def test_inherited_failure_is_not_marked_passed(self):
        rewrite_report(self.base, self.bundle, 'inherited_v013', 'large_file', lambda r: r.update(ok=False))
        with self.assertRaises(freeze.Refusal):
            self.verify()

    def test_environment_pin_and_source_fingerprint(self):
        fingerprint = 'd' * 64
        environment = self.base / 'environment.json'
        environment.write_text(json.dumps({'api': '37', 'source_sha256': fingerprint}))
        entry = self.bundle['current']['api37']
        entry.update(environment_path=environment.name, environment_sha256=freeze.sha256(environment),
                     source_inputs_sha256=fingerprint)
        self.path.write_text(json.dumps(self.bundle))
        records, _ = self.verify()
        self.assertEqual(fingerprint, records['current/api37']['source_inputs_sha256'])
        entry['source_inputs_sha256'] = 'e' * 64
        self.path.write_text(json.dumps(self.bundle))
        with self.assertRaises(freeze.Refusal):
            self.verify()

    def test_duplicate_and_nonfinite_json_rejected(self):
        for text in ('{"ok":true,"ok":false}', '{"value":NaN}', '[]'):
            self.path.write_text(text)
            with self.subTest(text=text), self.assertRaises(freeze.Refusal):
                freeze.load_json(self.path)


class StagingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name).resolve()
        self.apk = self.base / 'input.apk'
        self.apk.write_bytes(b'already signed APK, CLI mocked')
        self.identity = identity(freeze.sha256(self.apk))
        self.identity.update(apk_bytes=self.apk.stat().st_size, non_debuggable=True,
                             apk_embedded_revision_matches_source_commit=True)
        self.evidence, self.bundle = write_bundle(self.base, self.identity)
        self.destination = self.base / freeze.DESTINATION
        for target, kwargs in [('verify_commit', {}), ('git', {'return_value': 'ignored'}),
                               ('discover_sdk', {'return_value': (Path('/sdk/latest/bin/apkanalyzer'),
                                                                 Path('/sdk/36.0.0/apksigner'))}),
                               ('inspect_apk', {'return_value': self.identity})]:
            mock = patch.object(freeze, target, **kwargs)
            value = mock.start()
            self.addCleanup(mock.stop)
            setattr(self, 'mock_' + target, value)
        self.mock_git.side_effect = lambda root, verb, *args: '' if verb == 'ls-files' else 'ignored'

    def stage(self):
        return freeze.stage(self.apk, COMMIT, self.evidence, root=self.base)

    def test_stage_exact_files_checksums_no_self_reference_or_private_report(self):
        self.assertEqual(self.destination, self.stage())
        expected = {freeze.APK_NAME, 'SOURCE-COMMIT.txt', 'SIGNING-CERTIFICATE.txt',
                    'BUILD-IDENTITY.json', 'SHA256SUMS.txt', 'VERIFICATION-SUMMARY.md'}
        self.assertEqual(expected, {p.name for p in self.destination.iterdir()})
        sums = (self.destination / 'SHA256SUMS.txt').read_text().splitlines()
        self.assertEqual(5, len(sums))
        for line in sums:
            digest, filename = line.split('  ')
            self.assertNotEqual('SHA256SUMS.txt', filename)
            self.assertEqual(digest, freeze.sha256(self.destination / filename))
        summary = (self.destination / 'VERIFICATION-SUMMARY.md').read_text()
        self.assertIn('| api37 | 303 |', summary)
        self.assertIn('| jvm | 158 |', summary)
        self.assertIn('NOT rerun', summary)
        for file in self.destination.iterdir():
            self.assertNotIn(b'must-not-be-staged', file.read_bytes())

    def test_existing_destination_is_never_overwritten(self):
        self.destination.mkdir(parents=True)
        sentinel = self.destination / 'keep.txt'
        sentinel.write_text('keep')
        with self.assertRaises(freeze.Refusal):
            self.stage()
        self.assertEqual('keep', sentinel.read_text())
        self.mock_inspect_apk.assert_not_called()

    def test_failed_report_has_no_release_output(self):
        rewrite_report(self.base, self.bundle, 'current', 'api37', lambda r: r.update(complete=False))
        with self.assertRaises(freeze.Refusal):
            self.stage()
        self.assertFalse(self.destination.exists())

    def test_source_change_during_staging_has_no_release_output(self):
        self.mock_verify_commit.side_effect = [None, None, None, None, freeze.Refusal('Source changed')]
        with self.assertRaises(freeze.Refusal):
            self.stage()
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.destination.parent.glob('.release-pending-*')))

    def test_report_change_during_copy_refused(self):
        original = freeze.shutil.copyfile
        def copy_then_change(source, dest):
            original(source, dest)
            (self.base / 'phone.json').write_text('{}')
        with patch.object(freeze.shutil, 'copyfile', side_effect=copy_then_change), self.assertRaises(freeze.Refusal):
            self.stage()
        self.assertFalse(self.destination.exists())
        self.assertFalse(list(self.destination.parent.glob('.release-pending-*')))

    def test_tracked_or_not_ignored_destination_refused(self):
        for verb in ('check-ignore', 'ls-files'):
            self.mock_git.side_effect = lambda root, cmd, *args: ('tracked' if cmd == verb == 'ls-files' else '')
            with self.subTest(verb=verb), self.assertRaises(freeze.Refusal):
                self.stage()
            self.assertFalse(self.destination.exists())


if __name__ == '__main__':
    unittest.main()

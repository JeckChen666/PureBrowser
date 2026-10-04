import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from summarize_v015_baseline import summarize


class BaselineSummaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'installed.apk').write_bytes(b'fixture-not-an-APK')
        self.environment = {'baselineCode': 13, 'baselineVersion': '0.1.4',
                            'baselineAPKsha256': hashlib.sha256(b'fixture-not-an-APK').hexdigest()}
        self.write_environment()
        self.registration = {'samples': [{'sampleId': 'S1', 'serviceGroup': 'fixture',
                                          'playbackPage': 'https://public.example/movie'}]}

    def write_environment(self):
        (self.root / 'environment.json').write_text(json.dumps(self.environment))

    def record(self, evidence='S1/page.json'):
        (self.root / 'S1').mkdir(exist_ok=True)
        (self.root / 'S1/page.json').write_text('{}')
        row = {'sampleId': 'S1', 'publicPage': 'https://public.example/movie', 'versionCode': 13,
               'stageStatus': {'discovery': 'valid_file_candidate', 'plan': 'offered_file_download_unverified',
                               'usableOutput': 'download_reported_complete_unverified'},
               'evidence': [evidence]}
        (self.root / 'S1/input.json').write_text(json.dumps(row))
        return row

    def test_untested_rows_are_retained_without_statistics(self):
        result = summarize(self.registration, self.root)
        self.assertEqual(0, result['observedCount'])
        self.assertEqual(1, result['sampleCount'])
        self.assertIsNone(result['successRates'])
        self.assertFalse(result['releaseApproved'])

    def test_visible_completion_is_not_usable_output_or_gain(self):
        self.record()
        result = summarize(self.registration, self.root)
        self.assertEqual(1, result['observedCount'])
        self.assertIsNone(result['successRates'])
        self.assertIsNone(result['crossSiteGains'])
        self.assertFalse(result['candidatePaired'])

    def test_apk_identity_mismatch_rejected(self):
        (self.root / 'installed.apk').write_bytes(b'different')
        with self.assertRaisesRegex(ValueError, 'hash mismatch'):
            summarize(self.registration, self.root)

    def test_missing_or_out_of_root_evidence_rejected(self):
        self.record('../outside.json')
        with self.assertRaisesRegex(ValueError, 'evidence'):
            summarize(self.registration, self.root)

    def test_false_success_status_rejected(self):
        row = self.record()
        row['stageStatus']['usableOutput'] = 'success'
        (self.root / 'S1/input.json').write_text(json.dumps(row))
        with self.assertRaisesRegex(ValueError, 'status'):
            summarize(self.registration, self.root)

    def test_sample_path_traversal_rejected(self):
        self.registration['samples'][0]['sampleId'] = '../outside'
        with self.assertRaisesRegex(ValueError, 'sample ID'):
            summarize(self.registration, self.root)

    def test_sample_identity_mismatch_rejected(self):
        row = self.record()
        row['publicPage'] = 'https://public.example/another-movie'
        (self.root / 'S1/input.json').write_text(json.dumps(row))
        with self.assertRaisesRegex(ValueError, 'identity'):
            summarize(self.registration, self.root)


if __name__ == '__main__':
    unittest.main()

"""Fail-closed runner accounting: failure, skipped, partial, and aborted runs never pass."""
import unittest
import subprocess
import tempfile
from pathlib import Path
from unittest.mock import patch
from run_v013_regression import build_current, parse_results


def case(code=0, name='test'):
    return (f'INSTRUMENTATION_STATUS: class=example.Test\n'
            f'INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 1\n'
            f'INSTRUMENTATION_STATUS: class=example.Test\n'
            f'INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: {code}\n')


class AccountingTest(unittest.TestCase):
    def test_build_failure_stops_before_old_apk_can_be_used(self):
        with tempfile.TemporaryDirectory() as directory, patch(
                'run_v013_regression.subprocess.run',
                side_effect=subprocess.CalledProcessError(1, ['gradlew'])) as run:
            with self.assertRaises(subprocess.CalledProcessError):
                build_current(Path(directory))
            self.assertTrue(run.call_args.kwargs['check'])

    def test_complete_success(self):
        result = parse_results(case()+'INSTRUMENTATION_CODE: -1\n', 1)
        self.assertTrue(result['ok'])
        self.assertEqual(1, result['completed'])

    def test_evidence_status_is_not_a_completed_test(self):
        progress = 'INSTRUMENTATION_STATUS: evidence={"readable":true}\nINSTRUMENTATION_STATUS_CODE: 0\n'
        result = parse_results(progress + case() + progress + 'INSTRUMENTATION_CODE: -1\n', 1)
        self.assertTrue(result['ok'])
        self.assertEqual(1, result['completed'])

    def test_unidentified_error_still_fails_closed(self):
        raw = case() + 'INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1\n'
        self.assertFalse(parse_results(raw, 1)['ok'])

    def test_failures_and_skips_rejected(self):
        for code in [-1, -2, -3, -4]:
            with self.subTest(code=code):
                self.assertFalse(parse_results(case(code)+'INSTRUMENTATION_CODE: -1\n', 1)['ok'])

    def test_partial_success_rejected(self):
        self.assertFalse(parse_results(case()+'INSTRUMENTATION_CODE: -1\n', 2)['ok'])

    def test_no_final_status_rejected(self):
        self.assertFalse(parse_results(case(), 1)['ok'])

    def test_crash_rejected_even_with_completion(self):
        self.assertFalse(parse_results(case()+'Process crashed\nINSTRUMENTATION_CODE: -1\n', 1)['ok'])

    def test_failed_instrumentation_rejected(self):
        self.assertFalse(parse_results('INSTRUMENTATION_FAILED: missing package\n', 1)['ok'])


if __name__ == '__main__':
    unittest.main()

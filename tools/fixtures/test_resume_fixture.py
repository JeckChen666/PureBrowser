"""Host-only protocol checks for the bounded range fixture."""
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request


class StreamingRangeFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.file = Path(self.temp.name)/'owned.mp4'
        self.data = bytes(range(256))*2048
        self.file.write_bytes(self.data)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            self.port = sock.getsockname()[1]
        self.process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('serve_resume_fixture.py')),
            '--port', str(self.port), '--file', str(self.file), '--delay', '0', '--block-size', '65536',
            '--max-seconds', '30'], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.base = f'http://127.0.0.1:{self.port}'
        for _ in range(100):
            try:
                self.get('/metrics')
                break
            except OSError:
                time.sleep(.05)
        else:
            self.fail('fixture failed to start')

    def tearDown(self):
        self.process.terminate()
        self.process.communicate(timeout=5)
        self.temp.cleanup()

    def get(self, endpoint, **headers):
        with urllib.request.urlopen(urllib.request.Request(self.base+endpoint, headers=headers), timeout=5) as response:
            return response.status, response.headers, response.read()

    def test_full_and_open_bounded_ranges_and_if_range(self):
        tag = '"'+hashlib.sha256(self.data).hexdigest()+'"'
        status, headers, body = self.get('/range.mp4')
        self.assertEqual((status, body), (200, self.data))
        self.assertEqual(headers['ETag'], tag)
        for value, start, end in [('bytes=12345-', 12345, len(self.data)-1), ('bytes=7-41', 7, 41)]:
            status, headers, body = self.get('/range.mp4', Range=value, **{'If-Range': tag})
            self.assertEqual(status, 206)
            self.assertEqual(headers['Content-Range'], f'bytes {start}-{end}/{len(self.data)}')
            self.assertEqual(body, self.data[start:end+1])
        self.assertEqual(self.get('/range.mp4', Range='bytes=7-', **{'If-Range': '"stale"'})[0], 200)
        metrics = json.loads(self.get('/metrics')[2])
        self.assertEqual(metrics['if_range_matches'], 2)
        self.assertEqual(metrics['range_starts'], [12345, 7])

    def test_rejects_invalid_range_and_changed_source_and_fault_endpoints(self):
        for value in ['bytes=999999-', 'bytes=40-7', 'bytes=-10', 'bytes=0-1,4-5']:
            with self.assertRaises(urllib.error.HTTPError) as result:
                self.get('/range.mp4', Range=value)
            self.assertEqual(result.exception.code, 416)
            result.exception.close()
        self.assertEqual(self.get('/ignore-range.mp4', Range='bytes=7-')[0], 200)
        self.assertEqual(self.get('/wrong-range.mp4', Range='bytes=7-')[1]['Content-Range'], f'bytes 8-{len(self.data)-1}/{len(self.data)}')
        self.file.write_bytes(b'changed')
        with self.assertRaises(urllib.error.HTTPError) as result:
            self.get('/range.mp4')
        self.assertEqual(result.exception.code, 409)
        result.exception.close()


if __name__ == '__main__':
    unittest.main()

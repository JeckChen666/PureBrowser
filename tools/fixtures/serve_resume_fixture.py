#!/usr/bin/env python3
"""Owned, bounded, streaming MP4 range fixture; no proxy or URL/header logging."""
import argparse
import hashlib
import http.server
import json
from pathlib import Path
import re
import threading
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8768)
    parser.add_argument('--file', type=Path, default=ROOT/'app/src/androidTest/assets/test-video.mp4')
    parser.add_argument('--delay', type=float, default=0.03)
    parser.add_argument('--block-size', type=int, default=256)
    parser.add_argument('--bytes-per-second', type=int, default=0, help='0 disables bandwidth throttle')
    parser.add_argument('--drop-after-bytes', type=int, default=8192, help='owned /drop.mp4 socket fault')
    parser.add_argument('--max-seconds', type=int, default=3600)
    parser.add_argument('--max-requests', type=int, default=4096)
    args = parser.parse_args()
    if not (1 <= args.port <= 65535 and 0 <= args.delay <= 1 and
            256 <= args.block_size <= 1024*1024 and 0 <= args.bytes_per_second <= 256*1024*1024 and
            1 <= args.drop_after_bytes <= 8*1024**3 and 1 <= args.max_seconds <= 7200 and 1 <= args.max_requests <= 50000):
        parser.error('invalid bounded arguments')
    path = args.file.resolve(strict=True)
    if not path.is_file():
        parser.error('fixture must be a regular file')
    initial = path.stat()
    size = initial.st_size
    if not 0 < size <= 8*1024**3:
        parser.error('fixture must be nonempty and at most 8 GiB')
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024*1024), b''):
            digest.update(block)
    fingerprint = (initial.st_dev, initial.st_ino, size, initial.st_mtime_ns, initial.st_ctime_ns)
    validator = '"'+digest.hexdigest()+'"'
    counts = {'requests': 0, 'range_requests': 0, 'bytes_sent': 0,
              'range_starts': [], 'if_range_matches': 0}
    lock = threading.Lock()
    slots = threading.BoundedSemaphore(4)
    deadline = time.monotonic()+args.max_seconds

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if time.monotonic() >= deadline or not slots.acquire(blocking=False):
                self.send_error(503)
                return
            try:
                self.serve()
            finally:
                slots.release()

        def serve(self):
            endpoint = urlsplit(self.path).path
            if endpoint == '/metrics':
                with lock:
                    data = json.dumps(dict(counts, source_bytes=size, source_sha256=digest.hexdigest())).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            if endpoint not in {'/range.mp4', '/ignore-range.mp4', '/wrong-range.mp4', '/changed.mp4', '/drop.mp4'}:
                self.send_error(404)
                return
            with lock:
                counts['requests'] += 1
                allowed = counts['requests'] <= args.max_requests
            if not allowed:
                self.send_error(503)
                return
            try:
                source = path.open('rb')
            except OSError:
                self.send_error(409)
                return
            with source:
                # Never serve a changed source under the original strong validator.
                import os
                st = os.fstat(source.fileno())
                if (st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns) != fingerprint:
                    self.send_error(409)
                    return
                range_value = self.headers.get('Range')
                match = re.fullmatch(r'bytes=(\d+)-(\d*)', range_value or '')
                if range_value and not match:
                    self.unsatisfiable()
                    return
                offset, end = 0, size-1
                tag = '"changed-resource"' if endpoint == '/changed.mp4' and match else validator
                if_range = self.headers.get('If-Range')
                ranged = bool(match) and endpoint != '/ignore-range.mp4' and (not if_range or if_range == tag)
                if ranged:
                    offset = int(match.group(1))
                    end = min(int(match.group(2)), size-1) if match.group(2) else size-1
                    if offset >= size or end < offset:
                        self.unsatisfiable()
                        return
                    with lock:
                        counts['range_requests'] += 1
                        if len(counts['range_starts']) < 100:
                            counts['range_starts'].append(offset)
                        if if_range == tag:
                            counts['if_range_matches'] += 1
                self.send_response(206 if ranged else 200)
                self.send_header('Content-Type', 'video/mp4')
                self.send_header('ETag', tag)
                self.send_header('Accept-Ranges', 'bytes')
                self.send_header('Content-Length', str(end-offset+1))
                if ranged:
                    claimed = offset+1 if endpoint == '/wrong-range.mp4' else offset
                    self.send_header('Content-Range', f'bytes {claimed}-{end}/{size}')
                self.end_headers()
                source.seek(offset)
                remaining, sent, started = end-offset+1, 0, time.monotonic()
                try:
                    while remaining and time.monotonic() < deadline:
                        data = source.read(min(args.block_size, remaining))
                        if not data:
                            break
                        self.wfile.write(data)
                        self.wfile.flush()
                        remaining -= len(data)
                        sent += len(data)
                        with lock:
                            counts['bytes_sent'] += len(data)
                        if args.bytes_per_second:
                            wait = sent/args.bytes_per_second-(time.monotonic()-started)
                            if wait > 0:
                                time.sleep(min(wait, 1))
                        if args.delay:
                            time.sleep(args.delay)
                        if endpoint == '/drop.mp4' and sent >= args.drop_after_bytes:
                            # A real short socket response under the original full Content-Length.
                            self.close_connection = True
                            return
                except (BrokenPipeError, ConnectionResetError, TimeoutError):
                    pass

        def unsatisfiable(self):
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{size}')
            self.send_header('Content-Length', '0')
            self.end_headers()

    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.daemon_threads = True
    server.timeout = 0.25
    print(f'Owned streaming resume fixture port={args.port}; size={size}; bounded lifetime; no URL/header logging', flush=True)
    try:
        while time.monotonic() < deadline:
            server.handle_request()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()

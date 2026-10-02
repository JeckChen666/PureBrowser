#!/usr/bin/env python3
"""Owned short MP4 range fixture. Development only; no proxy, raw-header logging or credentials."""
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
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not 0 <= args.delay <= 1: parser.error('invalid bounded arguments')
    payload = args.file.read_bytes()
    if len(payload) > 128*1024*1024: parser.error('fixture must not exceed 128 MiB')
    validator = '"'+hashlib.sha256(payload).hexdigest()+'"'
    counts = {'requests': 0, 'range_requests': 0, 'bytes_sent': 0}
    lock = threading.Lock()
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_GET(self):
            path = urlsplit(self.path).path
            if path == '/metrics':
                with lock: data = json.dumps(counts).encode()
                self.send_response(200); self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data); return
            if path not in {'/range.mp4', '/ignore-range.mp4', '/wrong-range.mp4', '/changed.mp4'}:
                self.send_error(404); return
            with lock:
                counts['requests'] += 1
                sequence = counts['requests']
            offset = 0
            match = re.fullmatch(r'bytes=(\d+)-', self.headers.get('Range', ''))
            tag = '"changed-resource"' if path == '/changed.mp4' and match else validator
            ranged = bool(match) and path != '/ignore-range.mp4'
            if ranged:
                offset = int(match.group(1))
                with lock: counts['range_requests'] += 1
                if offset >= len(payload):
                    self.send_response(416); self.send_header('Content-Range', f'bytes */{len(payload)}'); self.send_header('Content-Length', '0'); self.end_headers(); return
            self.send_response(206 if ranged else 200)
            self.send_header('Content-Type', 'video/mp4'); self.send_header('ETag', tag)
            self.send_header('Accept-Ranges', 'bytes'); self.send_header('Content-Length', str(len(payload)-offset))
            if ranged:
                claimed = offset+1 if path == '/wrong-range.mp4' else offset
                self.send_header('Content-Range', f'bytes {claimed}-{len(payload)-1}/{len(payload)}')
            self.end_headers()
            try:
                for index in range(offset, len(payload), 256):
                    data = payload[index:index+256]; self.wfile.write(data); self.wfile.flush()
                    with lock: counts['bytes_sent'] += len(data)
                    if args.delay: time.sleep(args.delay)
            except (BrokenPipeError, ConnectionResetError): pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    server.daemon_threads = True
    print(f'Owned resume fixture port={args.port}; size={len(payload)}; no URL/header logging', flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == '__main__': main()

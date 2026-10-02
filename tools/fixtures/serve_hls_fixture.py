#!/usr/bin/env python3
"""Bounded, synthetic HLS fixture server. Standard library only; never a proxy.

See docs/HLS-FIXTURES.md. No production auth, TLS exceptions, or runtime ffmpeg.
"""
import argparse
from collections import Counter
from http.cookies import CookieError, SimpleCookie
import http.server
import ipaddress
import json
from pathlib import Path
import re
import socket
import ssl
import threading
import time
from urllib.parse import parse_qs, unquote, urlencode, urlsplit

DEFAULT_DIRECTORY = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/hls"
SIGNATURE = "fixture+v012"  # Deliberately public, NOT a security mechanism.
COOKIE = "pb_hls_fixture"
SESSION = "synthetic-v012"
SEGMENT = re.compile(r"segment-[0-9]{3,5}\.ts\Z")
PLAYLIST_LIMIT = 2 * 1024 * 1024
SEGMENT_LIMIT = 128 * 1024 * 1024
SCOPES = {"session", "referer", "protected", "signed"}
CASES = {"slow", "transient", "missing", "truncated", "html"}


def bounded_int(low, high):
    def parse(value):
        number = int(value)
        if not low <= number <= high:
            raise argparse.ArgumentTypeError(f"must be between {low} and {high}")
        return number
    return parse


def local_target(value):
    parsed = urlsplit(value)
    if (not value.startswith("/") or value.startswith("//") or parsed.scheme
            or parsed.netloc or parsed.fragment or "\\" in unquote(value)
            or any(ord(c) < 32 or ord(c) == 127 for c in value)
            or unquote(value).startswith("//")):
        raise ValueError("same-origin target must be a root-relative URL")
    return value


def public_target(value):
    """Validate operator configuration only; do not fetch or follow the target."""
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.fragment or "\\" in value
            or any(ord(c) <= 32 or ord(c) == 127 for c in value)):
        raise ValueError("foreign target must be public HTTPS without userinfo or fragment")
    try:
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 443,
                                       type=socket.SOCK_STREAM)
    except (OSError, ValueError):
        raise ValueError("foreign target DNS/port validation failed") from None
    if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
        raise ValueError("foreign target must resolve only to public addresses")
    return value


class Fixture:
    def __init__(self, args):
        self.args = args
        self.root = args.directory.resolve(strict=True)
        self.lock = threading.Lock()
        self.requests = 0
        self.outcomes = Counter()
        self.attempts = Counter()
        self.stop = threading.Event()
        self.deadline = time.monotonic() + args.max_seconds
        self.video = self.read("video.m3u8", PLAYLIST_LIMIT).decode("utf-8")
        self.master = self.read("master.m3u8", PLAYLIST_LIMIT).decode("utf-8")
        self.segments = set()
        for line in self.video.splitlines():
            if line and not line.startswith("#"):
                if not SEGMENT.fullmatch(line):
                    raise ValueError("video playlist must use flat segment-NNN.ts names")
                self.segments.add(line)
        if not self.segments or len(self.segments) > 10000 or "#EXT-X-ENDLIST" not in self.video:
            raise ValueError("requires a finite TS playlist with 1..10000 segments")
        for name in self.segments:
            path = self.path(name)
            if not path.is_file() or not 0 < path.stat().st_size <= SEGMENT_LIMIT:
                raise ValueError("segment is missing, empty, or exceeds 128 MiB")

    def path(self, name):
        path = (self.root / name).resolve()
        if path.parent != self.root:
            raise ValueError("asset must stay inside the fixture directory")
        return path

    def read(self, name, limit):
        with self.path(name).open("rb") as stream:
            data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValueError("asset exceeds fixture size limit")
        return data

    def playlist(self, case=None, signed=False):
        lines = []
        first = True
        for line in self.video.splitlines():
            if SEGMENT.fullmatch(line):
                if first and case == "missing":
                    line = "missing.ts"
                if signed:
                    line += "?" + urlencode({"sig": SIGNATURE})
                first = False
            lines.append(line)
        return "\n".join(lines) + "\n"

    def record(self, route, status):
        with self.lock:
            self.outcomes[f"{route}:{status}"] += 1
        # Only fixed route labels and integer status. No path, query, headers or peer.
        print(f"hls-fixture route={route} status={status}", flush=True)


class BoundedServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    request_queue_size = 8

    def __init__(self, address, fixture):
        self.fixture = fixture
        self.slots = threading.BoundedSemaphore(fixture.args.max_workers)
        super().__init__(address, Handler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(5)
        return connection, address

    def process_request(self, request, client_address):
        if self.fixture.stop.is_set() or not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # Never emit request data, exception text, or potentially secret file paths.
        print("hls-fixture handler-error (details redacted)", flush=True)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"  # No unbounded keepalive sessions.
    server_version = "SyntheticHLS/0.1.2"
    sys_version = ""

    def log_message(self, *args):
        pass  # Base class logs raw request lines, including queries: never use it.

    def send_error(self, code, message=None, explain=None):
        # Includes errors raised by the HTTP parser, without reflecting request data.
        self.respond(code, b"fixture request rejected\n", "text/plain", route="http-error")

    def respond(self, status, data=b"", mime="text/plain", headers=(), route="control"):
        if isinstance(data, str):
            data = data.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.send_header("X-Content-Type-Options", "nosniff")
        for key, value in headers:
            self.send_header(key, value)
        self.end_headers()
        self.server.fixture.record(route, status)
        self.close_connection = True
        if self.command != "HEAD":
            self.wfile.write(data)

    def has_session(self):
        cookie = SimpleCookie()
        try:
            cookie.load(self.headers.get("Cookie", ""))
        except CookieError:
            return False
        return COOKIE in cookie and cookie[COOKIE].value == SESSION

    def has_referer(self):
        ref = urlsplit(self.headers.get("Referer", ""))
        scheme = "https" if self.server.fixture.args.cert else "http"
        return (ref.scheme == scheme and ref.netloc == self.headers.get("Host")
                and ref.path == "/dynamic.html" and ref.username is None)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        fixture = self.server.fixture
        with fixture.lock:
            fixture.requests += 1
            allowed = fixture.requests <= fixture.args.max_requests
        try:
            if not allowed or fixture.stop.is_set() or time.monotonic() >= fixture.deadline:
                self.respond(503, "fixture budget exhausted\n", route="budget")
                return
            self.dispatch()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass
        except (ValueError, UnicodeError):
            self.respond(400, "invalid fixture request\n", route="invalid")
        except OSError:
            self.respond(404, "fixture asset unavailable\n", route="asset-missing")
        finally:
            if fixture.requests >= fixture.args.max_requests:
                fixture.stop.set()

    def dispatch(self):
        fixture = self.server.fixture
        parsed = urlsplit(self.path)
        path = parsed.path
        query = parse_qs(parsed.query, max_num_fields=16)
        if path in ("/", "/dynamic.html"):
            self.respond(200, PAGE, "text/html; charset=utf-8", route="page")
            return
        if path in ("/session/login", "/session/logout"):
            value = SESSION if path.endswith("login") else ""
            secure = "; Secure" if fixture.args.cert else ""
            age = fixture.args.max_seconds if value else 0
            self.respond(204, headers=[("Set-Cookie", f"{COOKIE}={value}; Path=/; "
                          f"Max-Age={age}; HttpOnly; SameSite=Lax{secure}")], route="session-control")
            return
        if path == "/proof/stats.json":
            with fixture.lock:
                data = {"requests": fixture.requests, "outcomes": dict(fixture.outcomes)}
            self.respond(200, json.dumps(data), "application/json", route="stats")
            return
        if path in ("/redirect/same.m3u8", "/redirect/public.m3u8", "/redirect/credential.m3u8"):
            credential = path.endswith("/credential.m3u8")
            if credential and not (self.has_session() and self.has_referer()):
                self.respond(403, "synthetic cookie and Referer required\n", route="credential-gate")
                return
            same = path.endswith("/same.m3u8")
            target = fixture.args.same_origin_target if same else fixture.args.foreign_target
            if not target:
                self.respond(409, "configure an operator-owned public HTTPS target\n", route="redirect-disabled")
                return
            if not same:
                own = urlsplit(("https" if fixture.args.cert else "http") + "://" + self.headers.get("Host", ""))
                foreign = urlsplit(target)
                if (foreign.scheme, foreign.hostname, foreign.port or 443) == (
                        own.scheme, own.hostname, own.port or (443 if own.scheme == "https" else 80)):
                    self.respond(409, "foreign target must use a different origin\n", route="redirect-not-foreign")
                    return
            self.respond(302, headers=[("Location", target)],
                         route="redirect-same" if same else "redirect-credential" if credential else "redirect-public")
            return
        if path == "/proof/reject-credentials.m3u8":
            # Deploy this same standalone server on an operator-controlled PUBLIC HTTPS
            # host to witness a foreign-origin hop. Does not echo header values.
            dirty = any(self.headers.get(h) for h in ("Cookie", "Authorization", "Proxy-Authorization", "Referer"))
            if dirty:
                self.respond(403, "credential/context received: rejected\n", route="proof-credentials-rejected")
            else:
                # Absolute local path: proof endpoint is not a media subdirectory.
                data = fixture.playlist().replace("\nsegment-", "\n/segment-")
                self.respond(200, data, "application/vnd.apple.mpegurl", route="proof-clean")
            return
        if path.startswith("/unsupported/"):
            self.unsupported(path)
            return
        if path == "/cases/playlist-html.m3u8":
            self.respond(200, "<!doctype html><title>Not HLS</title>",
                         "application/vnd.apple.mpegurl", route="playlist-html")
            return
        parts = path.strip("/").split("/")
        scope = parts[0] if len(parts) == 2 and parts[0] in SCOPES else None
        case = parts[1] if len(parts) == 3 and parts[0] == "cases" and parts[1] in CASES else None
        if not (len(parts) == 1 or scope or case):
            self.respond(404, "unknown fixture\n", route="unknown")
            return
        name = parts[-1]
        route = scope or case or "asset"
        if scope in ("session", "protected") and not self.has_session():
            self.respond(403, "synthetic session required\n", route=route)
            return
        if scope in ("referer", "protected") and not self.has_referer():
            self.respond(403, "fixture Referer required\n", route=route)
            return
        # A sig on a variant from the root master is gated too. Duplicate sigs fail.
        signed = scope == "signed" or "sig" in query
        if signed and query.get("sig") != [SIGNATURE]:
            self.respond(403, "synthetic signature required\n", route="signed-gate")
            return
        if name == "master.m3u8":
            self.respond(200, fixture.master, "application/vnd.apple.mpegurl", route=route)
        elif name == "video.m3u8":
            self.respond(200, fixture.playlist(case, signed), "application/vnd.apple.mpegurl", route=route)
        elif name in fixture.segments:
            if case == "transient" and self.command != "HEAD":
                with fixture.lock:
                    fixture.attempts[name] += 1
                    first = fixture.attempts[name] == 1
                if first:
                    self.respond(503, "synthetic first-attempt failure\n", headers=[("Retry-After", "1")], route=route)
                    return
            self.segment(name, case, route)
        elif name == "LICENSE.txt" and not scope and not case:
            self.respond(200, fixture.read(name, PLAYLIST_LIMIT), route="license")
        else:
            self.respond(404, "missing fixture resource\n", route=route)

    def segment(self, name, case, route):
        fixture = self.server.fixture
        if case == "html":
            self.respond(200, "<!doctype html><title>Synthetic login, not media</title>",
                         "video/mp2t", route=route)
            return
        with fixture.path(name).open("rb") as stream:
            size = stream.seek(0, 2)
            stream.seek(0)
            if not 0 < size <= SEGMENT_LIMIT:
                raise ValueError("invalid segment size")
            self.send_response(200)
            self.send_header("Content-Type", "video/mp2t")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            fixture.record(route, 200)
            self.close_connection = True
            if self.command == "HEAD":
                return
            remaining = size // 2 if case == "truncated" else size
            # Streaming avoids loading long fixtures into RAM. Slow mode is bounded
            # by both its own deadline and the process lifetime; cancellation wakes it.
            segment_deadline = min(fixture.deadline, time.monotonic() + fixture.args.slow_seconds)
            while remaining and not fixture.stop.is_set():
                if case == "slow" and time.monotonic() >= segment_deadline:
                    break
                block = stream.read(min(1024 if case == "slow" else 65536, remaining))
                if not block:
                    break
                self.wfile.write(block)
                self.wfile.flush()
                remaining -= len(block)
                if case == "slow":
                    fixture.stop.wait(0.1)

    def unsupported(self, path):
        fixture = self.server.fixture
        name = path.removeprefix("/unsupported/")
        data = fixture.playlist().replace("\nsegment-", "\n/segment-")
        if name in ("encryption.m3u8", "sample-aes.m3u8"):
            method = "AES-128" if name == "encryption.m3u8" else "SAMPLE-AES"
            data = data.replace("#EXTINF:", f'#EXT-X-KEY:METHOD={method},URI="key.bin"\n#EXTINF:', 1)
        elif name == "live.m3u8":
            data = data.replace("#EXT-X-ENDLIST\n", "").replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "")
        elif name == "fmp4.m3u8":
            data = ('#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n'
                    '#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MAP:URI="init.mp4"\n'
                    '#EXTINF:2,\npart-000.m4s\n#EXT-X-ENDLIST\n')
        elif name == "discontinuity.m3u8":
            marker = "#EXTINF:"
            position = data.find(marker, data.find(marker) + len(marker))
            if position < 0:
                position = data.find(marker)
            data = data[:position] + "#EXT-X-DISCONTINUITY\n" + data[position:]
        elif name in ("key.bin", "init.mp4", "part-000.m4s"):
            self.respond(410, "negative parser fixture: media/key intentionally absent\n", route="unsupported-payload")
            return
        else:
            self.respond(404, "unknown negative fixture\n", route="unsupported-unknown")
            return
        self.respond(200, data, "application/vnd.apple.mpegurl", route="unsupported")


PAGE = """<!doctype html><html lang="en"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="same-origin"><title>Synthetic HLS v0.1.2</title>
<style>body{font:16px system-ui;max-width:50rem;margin:2rem auto;padding:1rem}
video{width:100%}button,a{margin:.4rem}</style><h1>Controlled synthetic HLS</h1>
<p>8 seconds, generated testsrc2 + sine; no external player scripts or private media.
Native HLS playback depends on the browser. Candidate discovery does not imply playback.</p>
<button id="login">Synthetic login</button><button id="logout">Logout</button>
<button data-src="/session/video.m3u8">Session</button>
<button data-src="/referer/video.m3u8">Referer</button>
<button data-src="/protected/video.m3u8">Both gates</button>
<button data-src="/redirect/credential.m3u8">Credential foreign hop (must reject)</button>
<video controls preload="metadata"></video><p id="state">Dynamic source pending…</p>
<a href="/master.m3u8">Master</a><a href="/cases/transient/video.m3u8">503 then success</a>
<script>
const v=document.querySelector('video'),state=document.querySelector('#state');
function source(s){v.src=s;state.textContent='Source assigned: '+s;}
setTimeout(()=>source('/signed/master.m3u8?sig=fixture%2Bv012'),500);
document.querySelector('#login').onclick=async()=>{
 const r=await fetch('/session/login',{credentials:'same-origin'});
 state.textContent=r.ok?'Synthetic cookie set':'Login failed';};
document.querySelector('#logout').onclick=()=>fetch('/session/logout',{credentials:'same-origin'});
document.querySelectorAll('[data-src]').forEach(b=>b.onclick=()=>source(b.dataset.src));
</script></html>"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=bounded_int(1, 65535), default=8766)
    parser.add_argument("--bind", default="127.0.0.1", help="loopback by default; expose only deliberately")
    parser.add_argument("--directory", type=Path, default=DEFAULT_DIRECTORY)
    parser.add_argument("--cert", type=Path, help="local certificate; never uploaded or served")
    parser.add_argument("--key", type=Path, help="local private key; keep outside the repository")
    parser.add_argument("--same-origin-target", default="/video.m3u8")
    parser.add_argument("--foreign-target", help="operator-controlled public HTTPS URL; disabled by default")
    parser.add_argument("--max-seconds", type=bounded_int(1, 7200), default=3600)
    parser.add_argument("--max-requests", type=bounded_int(1, 50000), default=10000)
    parser.add_argument("--max-workers", type=bounded_int(1, 16), default=8)
    parser.add_argument("--slow-seconds", type=bounded_int(1, 120), default=30)
    args = parser.parse_args()
    if bool(args.cert) != bool(args.key):
        parser.error("HTTPS requires both --cert and --key")
    try:
        args.same_origin_target = local_target(args.same_origin_target)
        if args.foreign_target:
            args.foreign_target = public_target(args.foreign_target)
        fixture = Fixture(args)
        server = BoundedServer((args.bind, args.port), fixture)
        if args.cert:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(args.cert, args.key)
            server.socket = context.wrap_socket(server.socket, server_side=True,
                                               do_handshake_on_connect=False)
    except (OSError, ValueError, UnicodeError):
        parser.error("fixture startup failed; check assets, targets, bind and local TLS files (details redacted)")

    def watch_budget():
        fixture.stop.wait(max(0, fixture.deadline - time.monotonic()))
        fixture.stop.set()
        server.shutdown()

    watcher = threading.Thread(target=watch_budget, daemon=True)
    watcher.start()
    print(f"Synthetic HLS fixture ready: {'HTTPS' if args.cert else 'HTTP'} port={args.port}; "
          f"bounded {args.max_seconds}s/{args.max_requests} requests. No credentials logged.", flush=True)
    try:
        server.serve_forever(poll_interval=0.1)
    except KeyboardInterrupt:
        pass
    finally:
        fixture.stop.set()
        server.server_close()
        watcher.join(timeout=2)


if __name__ == "__main__":
    main()

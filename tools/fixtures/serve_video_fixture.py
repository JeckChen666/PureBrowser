#!/usr/bin/env python3
"""Local, synthetic WebView/download regression page; no third-party video or credentials.
Requires ffmpeg on the development machine. Not part of the Android application.
"""
import argparse
import functools
import hashlib
import http.server
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.parse import parse_qs, urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--tls-cert",type=Path,help="Optional trusted test certificate; keep keys outside the repository")
    parser.add_argument("--tls-key",type=Path)
    args = parser.parse_args()
    if bool(args.tls_cert) != bool(args.tls_key):parser.error("TLS needs both certificate and key")
    if not shutil.which("ffmpeg"):
        parser.error("ffmpeg is required to generate a synthetic test video")
    with tempfile.TemporaryDirectory(prefix="purebrowser-fixture-") as temp:
        root = Path(temp)
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=12",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "2", "-c:v", "libx264",
            "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", str(root / "sample.mp4"),
        ], check=True)
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(root / "sample.mp4"),
                        "-c:v", "libvpx-vp9", "-c:a", "libopus", str(root / "sample.webm")], check=True)
        (root / "product.html").write_text('''<!doctype html><html lang="zh"><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>本地视频保存验收</title>
<style>body{font:16px sans-serif;padding:16px;color:#16333c}video{width:100%;border-radius:12px}</style>
<h2>公开直链保存验收</h2><p>MP4 与 WebM：本机生成的两秒画面和声音。</p>
<video controls preload="metadata" src="/sample.mp4?token=demo%2Bsignature"></video>
<video controls preload="metadata" src="/sample.webm"></video>
<script>['/bad.mp4','/sample.m3u8','/manifest.mpd'].forEach(u=>fetch(u).catch(()=>{}));</script>
<p>其他资源用于格式与协议边界验收，不会自动下载。</p></html>''', encoding="utf-8")
        (root / "index.html").write_text('''<!doctype html><html lang="zh"><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>PureBrowser 本地测试</title>
<style>body{font:16px sans-serif;padding:16px;color:#16333c}video{width:100%;border-radius:12px}</style>
<h2>本地生成的视频</h2><p>两秒测试画面和音频；媒体地址包含签名参数。</p>
<video controls preload="metadata" src="/sample.mp4?token=demo%2Bsignature"></video>
<p>资源面板应有 MP4、HLS、DASH 候选；不应列出 TS 和 init 分片。</p>
<img src="/bad.mp4" alt="格式错误响应测试" width="1" height="1">
<script>['/bad.mp4','/sample.m3u8','/manifest.mpd','/chunk.ts','/init.mp4'].forEach(u=>fetch(u).catch(()=>{}));</script>
</html>''', encoding="utf-8")
        (root / "dynamic.html").write_text('''<!doctype html><meta charset="UTF-8"><title>动态视频验收</title>
        <button onclick="fetch('/login').then(()=>{document.querySelector('video').src='/session'})">模拟授权登录</button>
        <video controls></video><iframe src="/same-frame.html"></iframe>
        <script>setTimeout(()=>{let v=document.createElement('video');v.controls=true;v.src='/sample.mp4?token=dynamic%2Bdemo';document.body.append(v)},2500)</script>''',encoding="utf-8")
        (root / "same-frame.html").write_text('<!doctype html><title>同源 frame</title><video controls src="/sample.webm"></video>')
        shutil.copy2(root / "sample.mp4", root / "second.mp4")
        (root / "second.html").write_text('<!doctype html><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>第二个页面</title><h2>独立标签 B</h2><video controls preload="metadata" src="/second.mp4"></video>', encoding="utf-8")
        (root / "index.html").write_text((root / "index.html").read_text() + '<a href="/second.html">打开第二个页面</a>', encoding="utf-8")
        (root / "sample.m3u8").write_text("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nchunk.ts\n#EXT-X-ENDLIST\n")
        (root / "manifest.mpd").write_text('<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"></MPD>')
        (root / "chunk.ts").write_bytes(b"not-a-complete-video")
        (root / "init.mp4").write_bytes(b"\x00\x00\x00\x18ftypisom")
        (root / "bad.mp4").write_text("<html><body>Login required. Not a video.</body></html>")

        class Handler(http.server.SimpleHTTPRequestHandler):
            def do_GET(self):
                parsed = urlsplit(self.path)
                if parsed.path == "/sample.mp4" and parse_qs(parsed.query).get("token") != ["demo+signature"]:
                    self.send_error(403, "Signed query missing")
                    return
                if parsed.path in ("/expired.mp4", "/private.mp4"):
                    self.send_error(403 if parsed.path == "/expired.mp4" else 401, "Public fixture access denied")
                    return
                if parsed.path == "/long.mp4":
                    import struct
                    data=(root / "sample.mp4").read_bytes() + struct.pack(">I4s",1048576,b"free") + bytes(1048576-8)
                    self.send_response(200);self.send_header("Content-Type","video/mp4")
                    self.send_header("Content-Length",str(len(data)));self.end_headers()
                    try:
                        for start in range(0,len(data),1024):
                            self.wfile.write(data[start:start+1024]);self.wfile.flush();time.sleep(1)
                    except (BrokenPipeError,ConnectionResetError): pass
                    return
                if parsed.path in ("/session", "/referrer"):
                    allowed=(self.headers.get("Cookie","").find("pb_session=demo-allowed")>=0) if parsed.path=="/session" else (self.headers.get("Referer")=="http://127.0.0.1:8765/watch")
                    if not allowed:
                        self.send_response(403);self.end_headers();return
                    data=(root / "sample.mp4").read_bytes()
                    self.send_response(200);self.send_header("Content-Type","video/mp4");self.send_header("Content-Length",str(len(data)));self.end_headers();self.wfile.write(data);return
                if parsed.path == "/login":
                    self.send_response(200);self.send_header("Set-Cookie","pb_session=demo-allowed; Path=/; HttpOnly; SameSite=Strict")
                    self.end_headers();self.wfile.write(b"synthetic login");return
                if parsed.path == "/logout":
                    self.send_response(200);self.send_header("Set-Cookie","pb_session=; Path=/; Max-Age=0")
                    self.end_headers();return
                if parsed.path in ("/unknown.mp4", "/slow.mp4"):
                    data = (root / "sample.mp4").read_bytes()
                    if parsed.path == "/slow.mp4": data += bytes(2 * 1024 * 1024)
                    self.send_response(200)
                    self.send_header("Content-Type", "video/mp4")
                    self.send_header("Connection", "close")
                    if parsed.path == "/slow.mp4": self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    try:
                        for start in range(0, len(data), 4096):
                            self.wfile.write(data[start:start+4096]); self.wfile.flush()
                            if parsed.path == "/slow.mp4": time.sleep(0.2)
                    except (BrokenPipeError, ConnectionResetError): pass
                    self.close_connection = True
                    return
                super().do_GET()

            def guess_type(self, path):
                if path.endswith("bad.mp4"):
                    # Deliberately false video MIME: exercise bytes/header checks, not just metadata.
                    return "video/mp4"
                return super().guess_type(path)

            def log_message(self, fmt, *values):
                # Avoid logging request URLs/query values; this fixture never contains real credentials.
                print("fixture request handled", flush=True)

        print("Fixture directory:", root, flush=True)
        print("Sample SHA256:", hashlib.sha256((root / "sample.mp4").read_bytes()).hexdigest(), flush=True)
        print("WebM SHA256:", hashlib.sha256((root / "sample.webm").read_bytes()).hexdigest(), flush=True)
        print(f"Emulator URL: http://10.0.2.2:{args.port}/", flush=True)
        server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), functools.partial(Handler, directory=str(root)))
        if args.tls_cert:
            import ssl
            tls=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);tls.minimum_version=ssl.TLSVersion.TLSv1_2
            tls.load_cert_chain(args.tls_cert,args.tls_key)
            server.socket=tls.wrap_socket(server.socket,server_side=True)
            print("HTTPS fixture enabled; no client TLS bypass is provided.",flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()


if __name__ == "__main__":
    main()

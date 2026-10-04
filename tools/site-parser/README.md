# Pinned local site-parser bundle

The Android app does **not** include Node, Python, FFmpeg, a cloud parser or an automatically updated remote plugin. `youtube-worker.js` is a vendored browser bundle built from these pinned packages:

- youtubei.js 18.1.0 — MIT
- @bufbuild/protobuf 2.16.0 — Apache-2.0 AND BSD-3-Clause
- fflate 0.8.3 — MIT
- meriyah 7.3.3 — ISC
- esbuild 0.28.2 — build tool only, not an app runtime

`package-lock.json` fixes resolved tarball integrity; `provenance.json` records the runtime bundle SHA-256. Original runtime notices are distributed as the tracked files in `app/src/main/assets/site-parser/licenses/`. The current pinned build emits no `.LEGAL.txt`; `--legal-comments=linked` does not replace these separately packaged license notices. The About screen can display the license texts. PureBrowser's root Apache-2.0 license is unchanged. PeerTube integration is original public-API interoperability, not copied AGPL server code.

Rebuild from this directory:

```sh
npm ci --ignore-scripts --no-audit --no-fund
npm run build
```

Inspect the diff, recompute the bundle hash in `provenance.json`, and rerun runtime/security tests before accepting changed output. Gradle only packages the tracked bundle; it does not run npm or download parser code during app execution.

## Execution boundary

The bundle runs in a local Web Worker without native/DOM/storage/network authority. The host WebView has network loads blocked and a no-connect CSP. Fetches use a polled metadata queue, not `addJavascriptInterface`. Native code validates exact HTTPS YouTube metadata paths, target video identity, methods, allowed headers, request/body/response counts and sizes. IO uses the repository privacy-lease guard. No website Cookie/Authorization is imported; redirects are not followed. Public player-derived evaluation stays in that worker with a cancellation/timeout boundary. It does not solve CAPTCHA, supply external proof tokens, or bypass access controls.

Formats/URLs are transient, untrusted data validated before plan construction. The main controlled transfer and codec/output checks remain authoritative. Metadata or a successful 64-byte Range probe is **not** evidence of a complete video download. Current real Sintel full-range attempts returned HTTP403; this remains a release-blocking limitation, not a claimed working YouTube downloader.

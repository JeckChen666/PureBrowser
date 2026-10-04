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

## Independent v0.1.5 round-2 cpn diagnostic preparation (2026-10-04)

**Not a production fix, root-cause finding, download acceptance, or permission to retry the
previous round.** The cross-site old-version baseline remains the main line. This independent
preparation does not edit the existing P1 diagnostic, public transfer/plan/schema, product audit,
manifest, sharing, dataset or main-line execution documents.

### Pinned primary-source check

The locally installed **18.1.0** primary sources used here are:

- `dist/src/Innertube.js#getBasicInfo`: calls the existing player endpoint once, then generates
  a 16-character cpn and passes it to that result's `VideoInfo`.
- `dist/src/utils/Utils.js#generateRandomString`: alphabet `A-Z a-z 0-9 - _`.
- `dist/src/core/mixins/MediaInfo.js#cpn`: exposes that same stored nonce.
- `dist/src/utils/FormatUtils.js#download`: deciphers a format, then adds that supplied cpn to
  the resulting media URL. Its chunk/query-range downloader is **not invoked** by this diagnostic.

Their hashes and the worker input hash are recorded in `provenance.json`. This is only evidence
that cpn is a normal library playback parameter; it does **not** establish why earlier complete
requests were refused. No dependency/version/client/license change and no credential/proof-token
service is introduced. The existing fixed IOS `getBasicInfo` call is retained.

### Entry points and explicit opt-in

Production `YouTubeResolver.resolve(videoId)` still emits the original transient media model and
unchanged decipher URLs: **no cpn field, no appended cpn, no automatic diagnostic**.

The separate entry is:

```kotlin
resolver.resolveForCpnDiagnostic(videoId, testOnlyCpnOptIn = false)
// false rejects BEFORE asset/WebView/metadata IO; true is test-only authorization.
```

Only the literal worker boolean `testOnlyCpnOptIn === true` emits `diagnosticCpn`; `false`, an
omitted flag, `1` or the string `"true"` do not. The worker copies **that one `info.cpn`**; it does
not create a nonce, call `getBasicInfo` again, re-decipher, rotate clients or rewrite any track URL.
Both worker and native host reject missing/non-string/non-16-character/non-URL-safe values,
including trailing newlines. This nonce lives only in worker/native memory. It is not a player
request override, visitor-session replacement, Cookie, Auth or external proof token.

`YouTubeCpnDiagnosticSession` is a non-data-class, redacted, one-use in-memory capability.
Its `consumeVideoPair(track, testOnlyCpnOptIn = false)` requires explicit opt-in and the **identical
video-track object from that resolution**, rejects pre-existing cpn/query-range/credential
parameters, and clears its nonce reference on consumption. The paired cpn URL is constructed by
appending a single query parameter to the exact original decipher URL. No copy/serialization,
public nonce getter, task/plan/store integration, URL/cpn logging or value-bearing `toString` is
provided. This test-only source path is compiled in the app for instrumentation use, but is not
wired to production UI, plans or download dispatch.

Independent files:

- `app/src/androidTest/java/com/example/purebrowser/download/V015YouTubeCpnDiagnosticSupport.kt`
- `app/src/androidTest/java/com/example/purebrowser/download/V015YouTubeCpnDiagnosticTest.kt`
- `app/src/test/java/com/example/purebrowser/media/site/V015YouTubeCpnDiagnosticWorkerFixtures.mjs`

The only real-site method is
`com.example.purebrowser.download.V015YouTubeCpnDiagnosticTest#sintelSameSessionCpnBoundedDiagnostic`.
It requires **both** instrumentation arguments `v015YouTubeCpnDiagnostic=true` and
`testOnlyCpnOptIn=true`; otherwise it is skipped before IO. Main-line reviewers must compile the
current complete source/APK cohort and explicitly select this **one named method**, not the old
P1 diagnostic, an entire class/product suite, or a full-transfer attempt. No device command is
issued by this preparation.

### Exact comparison and budgets

- One fresh resolution of the existing authorized Sintel ID; one selected video (lowest available
  height), **no audio media request**, no task, enqueue, mux, publish, full save or file report.
- Sequential arms: `BASELINE_NO_CPN`, then `SAME_SESSION_CPN_ONLY`. Same decipher URL, track,
  declared length, anonymous fixed UA (`PureBrowser authorized Sintel audit`), identity encoding,
  and **identical full-resource header** `Range: bytes=0-(declaredLength-1)`. No short Range,
  query-range, other query editing, tokens/Cookie/Auth/client rotation, chunking or refusal bypass.
- **At most 2 attempted media `HttpTransport.open` calls per entire round, INCLUDING redirect
  follow-ups.** Errors consume budget. No implicit third request to finish the comparison.
- Independent redirect cap: **2 per variant**, restricted to validated HTTPS googlevideo URLs,
  no downgraded scheme/userinfo/fragment/alternate port, no added/dropped/replaced cpn or range.
  The stricter global two-open budget means at most **one followed redirect** can actually occur;
  if baseline consumes both opens, the second arm is not run and the comparison is incomplete.
- **At most 64 body bytes per media response; at most 128 per round**, with no 65th-byte/EOF probe.
  Redirect/refusal/error/invalid-full-response bodies are not read. Closing/disconnecting at the
  limit is an observation boundary, not a short-range request or complete-response validation.
- **12,000 ms deadline per variant, including its opens/redirects/reads**, enforced by scheduled
  cancellation/disconnect. Caller-supplied cancellation is supported; there is no retry on timeout,
  cancellation, network/HTTP/contract/EOF failure. At most **24,000 ms** for both media variants,
  exclusive of the existing single resolver's **45,000 ms** metadata-stage timeout. Normal metadata
  retains its separate existing <=20 requests / <=4 MiB per-response limit; these are not media probes.
- Two consecutive 401/403/410 refusals stop with `REPEATED_ACCESS_DENIAL_STOP`; any other unsuccessful
  first observation stops without spending an alternative-range/retry request. Redirect budget
  exhaustion reports an incomplete comparison. A consumed session cannot obtain a new budget.

The native diagnostic uses a fixed test-local anonymous GET transport, rejects ambient
CookieHandler, supplies no Cookie/Authorization or credential provider, disables automatic redirects
and caching, and retains the existing
repository privacy-lease guard. It does not modify the shared production transport. No native
network transport is used by any fixture.

Reports are bounded scalar observations: status/hop count, enum reason, declared track/response
length, numeric expiry/remaining time and expiry state, parse-to-request interval, byte count and
prefix class (`MP4_FTYP_AT_OFFSET_4`, `OTHER`, `NOT_OBSERVED`). URL/query/header values, nonce,
media bytes, throwable message/cause/stack, response/error body and cookie values are excluded.
`completeResponseVerified`, `completeTransferAttempted` and `downloadSucceeded` are **always false**.
Even two accepted full-response headers and two 64-byte reads only set `both64ByteObservations`;
this is not proof of a valid complete movie, success-rate evidence or a resolved cause.

### Offline preparation validation and handoff

- **20 worker cases** (flag negatives/default, same-result/same-decipher control, nonce type/length/
  alphabet/newline negatives, default-path secrecy, fixed-primary-source assertions) run with:
  `node app/src/test/java/com/example/purebrowser/media/site/V015YouTubeCpnDiagnosticWorkerFixtures.mjs`.
  Uses authored in-memory `Innertube`/format objects; **zero network requests**. This tests the worker
  source behavior, not real YouTube/library interoperability or Android WebView execution.
- **21 Kotlin `fixture*` methods**, independent from the real-site method, cover default opt-in,
  exact control variables/headers, original-track identity, one-shot capability, URL/credential
  negatives, repeated/one-sided refusals, full/partial response contracts, EOF, redirect limits/
  policy/nonce preservation, cancellation at open/read, scalar timing/expiry and redaction.
  All are authored pure-memory transport fixtures, not real-site samples.
- Preparation ran **20/20 worker cases** and **21/21 Kotlin methods on the host** (reflection, cached
  Kotlin/JUnit/API artifacts and cached SDK primary JSON implementation with annotations removed
  in temporary files only). An isolated direct Kotlin compiler check of the changed resolver and
  two new Kotlin files against cached existing classes/API jars passed. These are **not** a Gradle
  build, complete-current-source APK compilation, Android instrumentation results or device acceptance.
- `npm run build` uses the already installed pinned dependencies; bundle/provenance bytes/hash are
  updated. The pre-existing direct-eval bundler warning remains; packaged license notices stay intact.
- This preparation performs **0 adb calls, 0 emulator starts, 0 Gradle runs, 0 real media requests,
  0 complete-transfer attempts**. Main line owns complete-source compile/review and, only after that,
  one explicitly authorized bounded device comparison in the unified environment. If refusal persists,
  **do not make a full attempt this round**. Even positive prefix observations need separate review;
  do not silently promote this opt-in path to production or claim the root cause is established.

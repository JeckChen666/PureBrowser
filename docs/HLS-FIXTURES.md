# Controlled HLS fixtures — v0.1.2

Updated **2026-10-02 (Asia/Shanghai)**. These are developer-only, synthetic
fixtures for the **unencrypted, ended, multiplexed MPEG-TS / H.264 + AAC** slice.
They are not release configuration, a public-media downloader, an auth bypass,
or evidence that the Android release gates have passed.

## Ownership and provenance

This extension owns exactly:

- `tools/fixtures/serve_hls_fixture.py`
- `tools/fixtures/generate_hls_fixture.sh`
- `docs/HLS-FIXTURES.md`
- `app/src/androidTest/assets/hls/master.m3u8`
- `app/src/androidTest/assets/hls/LICENSE.txt`

The existing `video.m3u8` and `segment-000.ts` through `segment-003.ts` were left
**unchanged**. No production/test Kotlin, build files, release configuration,
certificates, or private keys were edited or added. No app build or install was
run by this fixture work.

The project's own short media is an 8-second **testsrc2 pattern + 440 Hz sine**:
320×180, 24 fps, H.264 High level 1.2, yuv420p, two B-frames, AAC-LC at 48 kHz,
mono. The existing four segments were probed for these properties. The new
recipe uses one continuous encode, closed 48-frame GOPs, keyframes every two
seconds, and the HLS MPEG-TS muxer. **Do not encode each segment independently or
reset its timestamps.** B-frame PTS order is not decode order; continuity checks
must use DTS (or presentation timestamps after decoding).

`LICENSE.txt` dedicates the project's synthetic media and playlists under
**CC0-1.0**; it does not relicense the encoder, application, or external films.
No third-party footage or recording is needed. Generator output includes
`PROVENANCE.txt` (recipe/version/encoder version) and `SHA256SUMS` when an installed
SHA-256 utility is available. Encoder versions may change the byte hashes;
reproducibility is the documented source/parameters, not a promise of identical
bytes across FFmpeg/libx264 builds.

Existing baseline SHA-256 values observed before extension work:

```text
545f6e6fddb3d8bd9f90f5516d8615dbf4bd0177b4ded1ce9d51cfef83074a9a  segment-000.ts
33c69a2b3fa0ceb2955bbd4d59cf2b469f9551d8bc76f3aa65c5311e5883c075  segment-001.ts
eebd351c5fdac50a727122199f49b6ba62d2e3ded3c1d1b45a93504675d5e86e  segment-002.ts
ea4fbbb61e5ff823f19f8ea039958e4d4d80a0e79d5932e0f1fa5f41cde9011d  segment-003.ts
406bb899f087ae5a41ea4bcc8aa68cdc533c9084923ac6a6bf3826d8239b9b5c  video.m3u8
```

## Generate offline; serve without FFmpeg

Run from the repository root. Python **3.9+**, standard library only, is enough
to serve the checked-in assets. FFmpeg with libx264/AAC is needed **only** by the
developer generator. It is not an Android or server runtime dependency.

```sh
# Existing short assets; loopback, port 8766, finite 3600-second / 10000-request run.
python3 tools/fixtures/serve_hls_fixture.py

# NEW output directory required: refuses existing paths, including asset folders.
bash tools/fixtures/generate_hls_fixture.sh --output /tmp/pb-hls-short
python3 tools/fixtures/serve_hls_fixture.py --directory /tmp/pb-hls-short

# 1801 seconds = 30 minutes + 1 second, stereo AAC, 901 segments.
# Last segment is 1 second; other segments are separate, continuous 2-second TS.
bash tools/fixtures/generate_hls_fixture.sh \
  --output /tmp/pb-hls-long-stereo --long --channels 2
python3 tools/fixtures/serve_hls_fixture.py \
  --port 8767 --directory /tmp/pb-hls-long-stereo --max-seconds 7200 \
  --max-requests 20000

# Mono alternative: omit --channels 2 or explicitly use --channels 1.
bash tools/fixtures/generate_hls_fixture.sh \
  --output /tmp/pb-hls-long-mono --long --channels 1
```

Long output is generated locally, **not committed**, and does not count as a
third-party licensed/public HTTPS sample. Leave a server already running on
8767 alone; choose another unused port for independent checks. `--directory`
requires `video.m3u8`, `master.m3u8`, and its segment files. Only known fixture
media names and `LICENSE.txt` are served; no directory listings, arbitrary file
serving, uploads, proxying, or outgoing media requests exist.

Default binding is `127.0.0.1`. Desktop entry page:
`http://127.0.0.1:8766/dynamic.html`. Android emulator host alias:
`http://10.0.2.2:8766/dynamic.html`. Physical devices require deliberate reachable
binding/routing or a separately configured test tunnel. **A URL is not a reason
to weaken app cleartext, TLS, or release networking policy.**

### Optional trusted HTTPS

Use a test certificate for the actual hostname/IP SAN, already trusted by the
client under its existing policy. Keep the certificate and particularly its
private key **outside the repository**, restrict key permissions, and never
upload either file. The fixture tool has no certificate generation/upload code.

```sh
python3 tools/fixtures/serve_hls_fixture.py --port 8766 \
  --cert "$HOME/.local/share/pb-fixture/server-cert.pem" \
  --key "$HOME/.local/share/pb-fixture/server-key.pem"
```

Both flags are mandatory together; TLS minimum is 1.2. Synthetic cookies get
`Secure` on HTTPS. Do not use certificate-error overrides, `curl -k`, or modify
Android release trust/configuration to make this fixture work. For a CLI-only
check, an independently trusted test CA can be supplied with `curl --cacert`.

### Process and log bounds

Default limits: 3600 seconds, 10000 handled GET/HEAD requests, eight workers,
five-second socket timeout, no keepalive. Configurable hard ceilings:
`--max-seconds 7200`, `--max-requests 50000`, `--max-workers 16`.
A request-count or time budget stops the server; Ctrl-C also stops it. Worker
saturation closes new connections instead of creating unbounded threads.
Manifests are capped at 2 MiB, at most 10000 flat segment names, and each segment
at 128 MiB. Segment responses stream from disk instead of buffering the whole
long fixture. Malformed/unsupported HTTP requests still have the process-time
bound, even when rejected before a GET/HEAD handler.

Logs contain only **fixed route labels and integer status**, e.g.
`hls-fixture route=signed-gate status=403`. They never print raw request paths,
queries, cookies, Authorization/Referer values, peer addresses, or exception
contents. `/proof/stats.json` exposes only total requests and redacted
route/status counters. Counter state, synthetic login, and fail-first history
reset on server restart; they are not persistent user sessions.

## Endpoint matrix / test scope

All paths below are on the fixture origin. The master has two signed relative
variants of the **same 320×180 rendition**, with identical bandwidth metadata.
They exercise URL resolution/query preservation, **not a real ABR ladder** or
1080p selection. The public constant `sig=fixture%2Bv012` is a synthetic gate,
not cryptographic signing. A variant query is not automatically inherited by a
segment: the server deliberately writes a signature on each segment URL.

| # | Entry / resource | Fixture contract / expected client assertion |
|---|---|---|
| 1 | `/video.m3u8` | Unencrypted ended TS/AVC/AAC VOD; four short segments. |
| 2 | `/master.m3u8` | Resolve relative signed variant URLs; two aliases of one rendition. |
| 3 | `/dynamic.html` (also `/`) | JS assigns a signed master to a native `<video>` after 500 ms; buttons switch gated sources. No CDN JS player. Discovery is distinct from playback. |
| 4 | `/session/video.m3u8` | 403 without synthetic cookie; 200 with it, including every segment request. |
| 5 | `/referer/video.m3u8` | 403 without same-origin `/dynamic.html` Referer; 200 with it, including segments. |
| 6 | `/protected/video.m3u8` | Requires both cookie and Referer throughout. |
| 7 | `/signed/master.m3u8?sig=fixture%2Bv012` | Gate master, variant and segments; missing/wrong/duplicate `sig` gives 403. Preserve encoded `+` correctly. |
| 8 | `/redirect/same.m3u8` | 302 to configured root-relative same-origin target (default `/video.m3u8`). |
| 9 | `/redirect/public.m3u8` | 302 to configured public HTTPS target; 409 if not configured. Public candidate has no fixture auth gate. |
| 10 | `/redirect/credential.m3u8` | Requires cookie + Referer then returns foreign 302. Credential-bearing candidate must be rejected by client before a foreign request; sink/counter procedure below. |
| 11 | `/cases/slow/video.m3u8` | Segment bytes paced at about 40 KiB/s per active response; exercise cancellation/no premature publication. Per-segment deadline is `--slow-seconds` (default 30, maximum 120); a larger asset exceeding it closes early. |
| 12 | `/cases/transient/video.m3u8` | Each segment's first GET returns 503 + `Retry-After: 1`, later GETs 200. HEAD doesn't consume failure. State resets on restart; client must still honor bounded retries. |
| 13 | `/cases/missing/video.m3u8` | First URI is `missing.ts`, which returns 404; permanent failure, not success/publication. |
| 14 | `/cases/truncated/video.m3u8` | Each segment advertises its full Content-Length, sends half, then closes; detect incomplete transport. |
| 15 | `/cases/html/video.m3u8`, `/cases/playlist-html.m3u8` | HTML masquerades as TS or playlist MIME; inspect content and fail closed, not an auth/login success. |
| 16 | `/unsupported/{encryption,sample-aes,live,fmp4,discontinuity}.m3u8` | Negative policy/parser fixtures: AES-128/SAMPLE-AES KEY, absent ENDLIST/VOD, EXT-X-MAP/m4s, or DISCONTINUITY. Reject outside v0.1.2 supported slice. |

Unsupported encryption playlists reference synthetic unencrypted media; **no
keys or encrypted content exist**. `key.bin`, `init.mp4`, and `part-000.m4s` under
`/unsupported/` return 410. These exercise early refusal based on manifest tags;
they do not constitute playable encrypted/fMP4/discontinuous recordings or a
live broadcast. No DRM test or security bypass is attempted.

Synthetic login/logout endpoints are `/session/login` and `/session/logout`.
Cookie: `pb_hls_fixture=synthetic-v012`, `Path=/`, HttpOnly, SameSite=Lax;
only HTTPS adds Secure. A valid Referer has the same scheme and Host as the
request and path `/dynamic.html`. All values are invented local test context.
This is **not** an authentication implementation suitable for real users.

Example checks (do not mistake HTTP success for app acceptance):

```sh
curl -i http://127.0.0.1:8766/session/video.m3u8                 # 403
curl -i -H 'Cookie: pb_hls_fixture=synthetic-v012' \
  http://127.0.0.1:8766/session/video.m3u8                      # 200
curl -i -H 'Referer: http://127.0.0.1:8766/dynamic.html' \
  http://127.0.0.1:8766/referer/video.m3u8                      # 200
curl -i 'http://127.0.0.1:8766/signed/video.m3u8?sig=fixture%2Bv012'
curl -i http://127.0.0.1:8766/cases/transient/segment-000.ts    # first: 503
curl -I http://127.0.0.1:8766/cases/transient/segment-000.ts    # HEAD: 200
curl -o /tmp/pb-fixture-segment.ts \
  http://127.0.0.1:8766/cases/transient/segment-000.ts          # later GET: 200
curl -s http://127.0.0.1:8766/proof/stats.json
```

### Foreign redirect and credential-rejection proof

Redirect targets are operator configuration, **never request-supplied open
redirect parameters**. `--same-origin-target` accepts only a root-relative URL.
`--foreign-target` accepts only HTTPS, no userinfo/fragment, with every resolved
address globally routed. No target is fetched by this server. Private/loopback
hosts, including another localhost server, are deliberately ineligible as
public foreign targets. A same-origin target supplied as "foreign" is refused.
DNS validation is a fixture configuration guard, not a substitute for the
application's own redirect/DNS/TLS checks. If local DNS/proxy tooling maps a
public name to a non-public address, configuration fails closed; do not weaken
this guard or the app's policy.

For **controlled** proof, host this same server briefly on an operator-owned
public HTTPS origin with trusted TLS and only synthetic media. Bind explicitly
only when authorized. Set the source server's `--foreign-target` to that
origin's `/proof/reject-credentials.m3u8` and optionally configure a same-origin
signed target:

```sh
# Replace the placeholder with YOUR own authorized public HTTPS fixture host.
python3 tools/fixtures/serve_hls_fixture.py \
  --same-origin-target '/signed/video.m3u8?sig=fixture%2Bv012' \
  --foreign-target 'https://YOUR-CONTROLLED-PUBLIC-HOST/proof/reject-credentials.m3u8'
```

The sink rejects any Cookie, Authorization, Proxy-Authorization, or Referer with
403 and increments `proof-credentials-rejected:403`; it never echoes values.
A header-clean arrival serves the VOD and increments `proof-clean:200`.

1. Record both sink counters at `/proof/stats.json` before the run.
2. In the source page, set the synthetic cookie and choose **Credential foreign
   hop**. Ensure source `redirect-credential:302` increments (not just a gate 403).
3. Assert the application reports foreign credential-context rejection and
   **neither sink counter increases**. A clean arrival still proves the client
   followed a forbidden credential-origin hop: stripping headers alone is not
   a passing rejection result. A dirty arrival proves a leak attempt and fails.
4. Separately test a credential-free public redirect under normal client policy;
   its permitted sink arrival may increment `proof-clean:200`.

Without the controlled sink, source-side 302/logs alone **cannot prove absence
of an outgoing foreign request**. No such Android/public-origin proof was run
by this extension. Never send private credentials to a third-party sample host.

## Local validation completed by this extension

- Python syntax/CLI and Bash syntax validated without app builds/installs.
- **46 local server assertions passed**, using temporary unused ports (not the
  user's ongoing port 8767 server): gates, signed relative variants, dynamic
  HTML, redirects/config validation, negative fixtures, redaction, counters,
  HEAD behavior, slow/truncated transport, directory override, time/request
  shutdown, and local HTTPS with an explicitly trusted temporary certificate.
- The public-Location configuration assertion used a literal globally routed
  address **only as a response header**; no foreign connection was followed and
  no playable fixture at that address was asserted.
- Generated **8-second mono** and **1801-second stereo** media in `/tmp`;
  verified four/901 segments, summed EXTINF duration, ENDLIST, final long
  one-second segment, H.264 High/B-frames + AAC-LC channel counts, video keyframe
  starts and monotonic per-stream DTS. Short boundary continuity was checked
  throughout; long checks sampled adjacent boundaries at beginning/middle/end.
- Generator refused an existing output directory; checked-in media remained
  unchanged. Temporary validation certificate/key were deleted, not uploaded.

These checks **do not** establish Android UI/instrumentation results, complete
long-media decoding, sync/seeking/cross-app playback, lockscreen/background
behavior, process-death reconciliation, credential-hop client rejection, or
public sample acceptance. Those remain separate concentrated/release tests.

## Read-only public HTTPS research — gate NOT satisfied

Research date: **2026-10-02**. Only public publisher pages and manifests were
read. **No external media segments were fetched, codec-probed, downloaded,
remuxed, played, or tested in the application.** Codec/container descriptions
below are manifest declarations / `.ts` URIs, not payload verification.
No encrypted sample, protected account, DRM, or access-control bypass was used.
A 403 was treated as unavailable, not evaded.

**Insufficient evidence for ≥3 independent HTTPS environments with ≥6 explicitly
licensed eligible samples, including a ≥30-minute sample. This release gate
is unfulfilled; candidate-only status is appropriate.** Reachability is not
permission. Duplicate renditions of one film are not six independent films.
Known names/remembered licenses and an open-source player license are not
substitutes for an explicitly verified media license.

### Publisher/source and manifest evidence

| Environment / primary source | Read-only result | License evidence / qualification |
|---|---|---|
| [Mux's own test-stream index](https://test-streams.mux.dev/) | Publishes Big Buck Bunny adaptive and 480p HLS URLs. Adaptive master advertises `avc1` + `mp4a.40.2`; inspected 720p playlist: 64 `.ts` entries, ENDLIST, duration 634.584 s, no KEY/MAP. 480p: 64 `.ts`, ENDLIST, 634.600 s. | Two URLs of the **same film**, not two independent licensed works. Licensor-page verification blocked; not counted as license-cleared gate samples. |
| [Unified Streaming's own demo index](https://demo.unified-streaming.com/) | Tears of Steel master advertises multiplexed AVC/AAC variants; first inspected video+audio rendition: 184 `.ts` entries, ENDLIST, 734 s, no KEY/MAP. Master also includes audio-only variants, which are not eligible video samples. | Explicit film-level CC BY 3.0 evidence found in Xiph's primary sample README, crediting Blender Foundation (see below). This is one short license-backed research candidate, not an acceptance-tested sample or six distinct films. |
| [Bitmovin's own stream-test page](https://bitmovin.com/demos/stream-test/) and [publisher documentation](https://developer.bitmovin.com/playback/docs/getting-started-with-the-web-player) | Publisher pages and tried BBB/Sintel HTTPS manifests returned 403 in this environment, including current CDN and older Akamai URLs. No stream property conclusion. | Neither availability nor explicit media authorization verified here. Excluded, not padded into the count. |
| [Apple's own streaming examples](https://developer.apple.com/streaming/examples/) | Index available; current advanced examples include out-of-scope codec/container types. No qualifying media/license pair established. | No verified grant for an eligible TS/AVC/AAC film; not counted. |

Inspected manifest URLs (not an authorized-download list):

- [Mux BBB master](https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8)
- [Mux BBB 480p rendition](https://test-streams.mux.dev/x36xhzz/url_6/193039199_mp4_h264_aac_hq_7.m3u8)
- [Unified Tears of Steel master](https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8)
- [Mux Tears of Steel IMSC master](https://test-streams.mux.dev/tos_ismc/main.m3u8):
  **excluded**. First video rendition has EXT-X-MAP and `.m4s` media, plus a
  separate audio group in the master. It is fMP4/separate-audio, not this slice.

### Explicit license evidence status

Two **primary sample-publisher provenance records were actually readable**:

- [Xiph's Tears of Steel README](https://media.xiph.org/tearsofsteel/README.txt)
  returned 200. It identifies the 2012 Mango Blender Open Movie Project,
  credits Blender Foundation, and explicitly states **Creative Commons
  Attribution 3.0**. This supplies film-level license evidence for the Tears of
  Steel candidate above, but is not proof of testing a particular hosted encode.
- [Xiph's Sintel README](https://media.xiph.org/sintel/README.txt) returned 200.
  It credits Blender Foundation and explicitly states **Creative Commons
  Attribution 3.0**, with a logo/trademark exception. It does not establish an
  accessible eligible Sintel HLS endpoint in the investigated Bitmovin environment.

These are direct records from the sample distributor, not a third-party stream
list or an open-source player's software license. The Xiph pages themselves
provide raw/movie assets, not another demonstrated eligible HLS environment.
No raw movie files were fetched. Attribution and the documented logo exception
must be retained when using these works; the local fixture's CC0 dedication
never applies to them. The retrieved README evidence does **not** close the
three-environment/six-sample/30-minute gap.

The intended **original licensor** references were:

- [Big Buck Bunny project: About](https://peach.blender.org/about/)
- [Sintel project: Sharing](https://durian.blender.org/sharing/)
- [Tears of Steel project: Sharing](https://mango.blender.org/sharing/)
- Corresponding film pages on [Blender Studio](https://studio.blender.org/films/).

These project/film pages returned **403** during direct HTTPS reads here.
They are provided as **verification targets**, not as successfully read license
evidence. The successful Xiph README reads are distinguished from these blocked
original-licensor pages. No unread license claim is promoted to verified status,
and no copyright license is invented to complete the required count. Publisher
manifest declarations alone establish neither authorship nor permission.

Remaining work: obtain the missing explicit media-license evidence and map
license/provenance to each hosted film/edition; verify at least six genuinely eligible
samples across three independent HTTPS environments, including a licensed
≥30-minute AVC+AAC ended TS sample. Then perform separate authorized Android
acceptance. The project's own 30-minute synthetic fixture helps long-duration
regression but cannot replace the independent public HTTPS gate.

## 集成设备实绩（2026-10-02）
主协调器在RC3正式签名包/API36实测Unified Streaming Tears of Steel通过：184片、734000ms、45210240B，CC BY 3.0来源已登记。这是1个独立影片/环境，不是6个样本或3个环境。修复后自制1801秒样本保存与跨UID分享已通过；不冒充真实HTTPS样本或人工声画同步验收。新增负例两AAC音轨与H264分辨率变化TS由同样testsrc2/sine命令生成，亦属自有Apache2测试素材，非Release资产。

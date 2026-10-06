# Compatibility corpus v0.1.3 — T39 research ledger, not acceptance

**Status: T39 release gates remain PENDING.** This bounded sidecar owns only
`docs/history/COMPATIBILITY-CORPUS-V0.1.3.md` and
`tools/fixtures/authorized-corpus-v0.1.3.json`. No existing documentation,
application/common code, test fixtures, release state or infrastructure was changed by this work.

Research snapshot: **October 2, 2026, 16:29–16:38 UTC** (October 3, 2026,
00:29–00:38 in Asia/Shanghai). The precise UTC request timestamps are in the JSON.
Availability is a dated observation, not a promise that public demo URLs stay available.

## 1. What is actually established

| Measure | Evidence-backed result | NOT a claim of |
|---|---:|---|
| Researched ledger entries | **30** | 30 distinct authorized videos or 30 passes |
| Identified proposed work groups | **25**, plus 1 unresolved excluded stream | Full-payload distinctness; unrelated source footage must still be checked |
| Work-level CC grant documented | **15 entries / 11 proposed work groups** | Container/codec correctness or blanket rights to logos, music and personalities |
| NASA policy-only leads | **14 entries**, all authorization-pending | Universal public-domain status for NASA-hosted media |
| Actual independent media deployments researched | **5** | Five authorized final-build-tested environments |
| Deployments containing CC-established candidates | **4** | A fifth cleared/tested deployment |
| Clear ended TS/AVC+AAC HLS hypotheses | **2 work groups** | Payload-probed MPEG-TS or Android compatibility |
| Excluded HLS entries | **3** | Successful downloads, or tested rejection messages |
| Media HEAD rate-limit failures | **6** | APK failures or permission denial |
| New media payload downloads / codec probes / APK runs | **0 / 0 / 0** | Any new application test pass |
| Final v0.1.3 candidate passed works | **0 in this sidecar** | Inheritance of v0.1.2 results |
| Real OEM devices / completed 7-day testers | **0 / 0 in this sidecar** | Replacement by emulators or automation |

The sole historical real-HTTPS APK report remains **Tears of Steel on Unified
Streaming, v0.1.2-rc.3, API 36 emulator**: repository documentation reports 184
segments, 734,000 ms and 45,210,240 output bytes. This sidecar read those existing
records; it did **not** inspect the APK/log artifact or rerun the test. This is
one movie in one environment, not evidence for final v0.1.3 or a physical OEM.
See existing `docs/V0.1.2-CANDIDATE.md` and `docs/HLS-FIXTURES.md`; neither was edited.

## 2. Research method and evidence boundaries

- Mandatory primary web browsing/search was attempted; the browser-search tool
  returned no source text. The actual evidence comes from bounded, read-only
  HTTPS requests to the publishers' own pages, APIs, license declarations and
  manifests, not third-party stream lists or remembered licenses.
- Locally used Python `urllib.request`: GET only public text/JSON/manifests;
  HEAD only direct MP4/WebM files. **No media GET/Range request, TS segment,
  encryption key, raw movie, ZIP archive, playback or remux was requested.**
- Each text request had a 22-second timeout and a 512 KiB retained-body cap,
  with only one additional byte read to detect overflow. Oversized metadata is
  incomplete, not parseable evidence of authorization. In N02 the metadata hit
  this cap; its duration/codec fields remain unknown.
- The JSON has **96 referenced request records** with actual status/error,
  final URL when available, UTC timestamp, selected non-sensitive headers and
  text SHA-256. A truncated record's hash is explicitly a prefix-plus-sentinel
  hash, not a full document hash. No raw response bodies, cookies, client IPs,
  authentication/session data or the ad manifest's analytics session URL are
  saved in the artifacts.
- HEAD sizes, ETags and `Accept-Ranges: bytes` are metadata only. They do not
  establish strong resource identity, safe append/206, successful complete
  transfer, decoded audio, duration, seek/sync, resume, lockscreen or sharing.
  A WebM extension does not prove a video track; an audio codec field does not
  prove audible sound or synchronization.
- HTTP URLs explicitly listed by NASA/W3C were mapped to the same host/path
  over HTTPS and separately HEAD-checked. The original listed NASA URLs are
  retained as provenance, **not** executable HTTP media endpoints. No host was
  deployed, aliased or fabricated to create additional environments.
- The JSON is **research data, not an executable fixture runner**:
  `automatic_network_execution_allowed=false`. Rights-pending, 429, MIME-
  discrepant and excluded rows must not be blindly batch-downloaded.

## 3. Independent actual deployments

| Deployment ID | Operator and actual media origin | Primary publisher evidence | Counting boundary |
|---|---|---|---|
| `mux-test-streams` | Mux; `https://test-streams.mux.dev` | [Mux's own test-stream index](https://test-streams.mux.dev/) | All paths/qualities on this test site are one environment. |
| `unified-demo` | Unified Streaming; `https://demo.unified-streaming.com` | [Vendor demonstration index](https://demo.unified-streaming.com/) plus fetched Tears master/children | Separate operation from Mux; same film mirrored elsewhere is still one work. |
| `w3c-media` | W3C; `https://media.w3.org` | [W3C media-events demo](https://www.w3.org/2010/05/video/mediaevents.html) and [its script](https://www.w3.org/2010/05/video/script.js) | Demo page/script and media subdomain do not create two environments. |
| `wikimedia-commons` | Wikimedia Commons; `https://upload.wikimedia.org` | Author file-page grants and Commons discovery API | Eight file pages, languages/edits and CDN caches are one deployment. |
| `nasa-image-library` | NASA; `https://images-assets.nasa.gov` | Official discovery API, collection JSON, per-item metadata and HEAD | AFRC/HQ/JPL/GSFC/JSC labels and API/asset hosts do not create more deployments. **Item rights remain pending.** |

These are independently operated publisher/media environments, not a claim of
independent network/CDN failure domains. Only four currently have work-level
CC-established candidates. NASA is a fifth **research environment**, not a
fifth authorized/tested environment. Xiph, Blender's download indexes and
unreachable NASA SVS pages below do not add eligible environments.

## 4. Identity and license rules

The JSON key `identity.work_id` is the deduplication key across hosts and formats.
`family_id` collapses trailers/excerpts with their parent film. Resolution,
bitrate, MP4/WebM encodes, subtitles, language, HTTPS upgrades, mirrors and
selected HLS renditions never add distinct films. A NASA ID or Commons file page
is a **provisional editorial-work identity**; compare the actual media for
alternate edits/reused reels before gate counting. An unresolved identity adds
zero work count.

License states are orthogonal to research/format/APK/device states:

1. `established_for_identified_work`: primary licensor/uploader copyright grant
   documented for the named work; actual payload identity and attribution still
   require checking. This is not permission to ignore non-copyright rights.
2. `publisher_policy_established_item_authorization_pending`: NASA policy is
   readable but full per-item provenance/third-party rights are not established.
   Not an authorized-download list.
3. `not_established`: no explicit media grant found; an openly playable test
   link or an open-source player license is insufficient.
4. `excluded_*`: manifest features outside the HLS slice, observed from text
   only. A rejection test has not been run.
5. Every `verification.apk_status` is `not_run_by_this_sidecar`; every final
   v0.1.3 and physical-device status is pending. The historical report is separate.

Primary grants used:

- **Big Buck Bunny:** [Peach project About/license](https://peach.blender.org/about/),
  [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Preserve the film's
  credits and Blender Foundation/source attribution. Logo/trademark exceptions
  are not waived.
- **Sintel:** [Durian project Sharing](https://durian.blender.org/sharing/),
  [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/), corroborated by the
  [Xiph distribution README](https://media.xiph.org/sintel/README.txt). Preserve
  movie credits and the licensor's Blender Foundation/Durian/Sintel attribution;
  logos/trademarks and non-project material are excluded from the grant.
- **Tears of Steel:** [Mango project Sharing](https://mango.blender.org/sharing/),
  [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/), corroborated by the
  [Xiph README](https://media.xiph.org/tearsofsteel/README.txt). Preserve Blender
  Foundation/Mango attribution and the project exceptions.
- **Commons W01–W08:** each linked file page records the uploader's self-published
  copyright grant and named author, corroborated by the API. W01–W07 use
  [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/); W08 uses
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Retain title,
  author, source, license, changes and component credits; adaptations have
  ShareAlike obligations. The Commons structured-data CC0 notice is **not** the
  video's license. The project's Apache-2.0/own-fixture license does not apply.
- **NASA:** [NASA media usage policy](https://www.nasa.gov/nasa-brand-center/images-and-media/)
  permits relevant informational/personal use of NASA material but expressly
  warns about third-party copyright and endorsement/logo limits. A NASA URL,
  center label or codec field cannot license partner footage or commercial
  music. **None of N01–N14 is marked unconditionally public-domain/CC-cleared.**

This is a provenance/permission ledger for the stated testing purpose, not a
blanket legal warranty or permission to republish third-party likenesses/logos.

## 5. CC-established Blender candidates and manifest exclusions

All exact master, inspected child and direct URLs, source/license URLs and text
hashes are in the JSON. The rows below are eight ledger entries, but only
**three** movie identities (BBB, Sintel, Tears of Steel).

| ID | Work / environment | Read-only observation | APK / counting |
|---|---|---|---|
| C01 | Big Buck Bunny / Mux | Adaptive master declares AVC + AAC-LC for inspected 720p/1080p. Each child has 64 TS URIs and ENDLIST; durations 634.584 / 634.567 s. No KEY/MAP/discontinuity. | Manifest-fit hypothesis only; no new APK test. Same BBB as C04/C05/X02. |
| C02 | Tears of Steel / Unified Streaming | Low and highest-video child: 184 TS URIs, 734 s, ENDLIST; no KEY/MAP/discontinuity. Master includes both AVC/AAC video and audio-only variants. | Only inherited v0.1.2 emulator report; v0.1.3 pending. Audio-only variants add no video. |
| C03 | Sintel trailer / W3C | HTTPS MP4 HEAD 200, 4,372,373 B; HTTPS WebM HEAD 200, 3,091,780 B. | One Sintel identity, not two container samples counted as films. No payload/codec probe. |
| C04 | BBB full film / W3C | HTTPS MP4 HEAD 200, 249,224,577 B. | Additional actual environment, not an additional BBB work. |
| C05 | BBB trailer / W3C | HTTPS MP4 HEAD 200, 11,053,871 B. | Same parent-film family; zero additional film count. |
| X01 | Tears of Steel IMSC / Mux | Video child has EXT-X-MAP + .m4s, 123 segments, ENDLIST, 734.169 s. Master has external AUDIO + SUBTITLES and stpp.ttml.im1t. | **Excluded:** fMP4, separate audio, subtitle/codec features outside current TS/multiplexed AVC+AAC slice. No tested app rejection. |
| X02 | BBB SAMPLE-AES / Mux | EXT-X-KEY METHOD=SAMPLE-AES in fetched manifest. | **Excluded:** all encrypted HLS is out of scope. No key/segment retrieval or decryption. |
| X03 | Ad-insertion stream / Mux | AES-128, discontinuities and unknown EXT-X-YOSPACE-ANALYTICS-URL. Labelled EVENT **with ENDLIST present**. Media/ad identity and grant unresolved. | **Excluded:** encryption, discontinuities, unknown tag and unestablished rights—not falsely described as live/no-ENDLIST. Zero distinct-work credit. |

HLS compatibility descriptions are aligned with the as-read restrictions in
`HlsPlaylistParser.kt`; this sidecar did not execute that parser or the APK.
Manifest CODECS and .ts URIs do not prove actual MPEG-TS, a single video/audio
track, AAC profile, codec stability, encryption-free payloads or decode success.
Freeze/revalidate both master and selected child; inspecting a low rendition alone
cannot validate the default/highest rendition.

## 6. Author-published CC WebM candidates — Wikimedia

All eight file pages returned 200 and supplied an explicit author grant. Media
HEAD observations are separate. No 429 was retried or worked around; later
availability checks must respect publisher limits. W01's HTTP MIME differs from
its video-page metadata; neither audio-only nor video support is assumed.

| ID | Primary author/file page | License | Media HEAD observation / remaining blocker |
|---|---|---|---|
| W01 | [The Impact of Wikipedia - Q Miceli](https://commons.wikimedia.org/wiki/File:The_Impact_of_Wikipedia_-_Q_Miceli.webm) | CC-BY-SA-3.0 | 200, 57,252,528 B; HTTP audio/webm vs video file-page metadata. |
| W02 | [Judy Tuan at the San Francisco Wikipedia Hackathon January 2012](https://commons.wikimedia.org/wiki/File:Judy_Tuan_at_the_San_Francisco_Wikipedia_Hackathon_January_2012.webm) | CC-BY-SA-3.0 | 429; availability pending, no media request retry. |
| W03 | [Art + Feminism Edit-a-thon at the Museum of Modern Art March 7, 2015 (English Subtitles)](https://commons.wikimedia.org/wiki/File:Art_%2B_Feminism_Edit-a-thon_at_the_Museum_of_Modern_Art_March_7,_2015_(English_Subtitles).webm) | CC-BY-SA-3.0 | 429; availability pending, no media request retry. |
| W04 | [Wikimedia Hackathon Jerusalem 2016 (no subtitles)](https://commons.wikimedia.org/wiki/File:Wikimedia_Hackathon_Jerusalem_2016_(no_subtitles).webm) | CC-BY-SA-3.0 | 200/video-webm, 64,072,512 B; payload and APK remain unprobed. |
| W05 | [This is Wikipedia](https://commons.wikimedia.org/wiki/File:This_is_Wikipedia.webm) | CC-BY-SA-3.0 | 429; availability pending, no media request retry. |
| W06 | [Wikipedia in Education (7 of 12) Social media & connectivity](https://commons.wikimedia.org/wiki/File:Wikipedia_in_Education_(7_of_12)_Social_media_%26_connectivity.webm) | CC-BY-SA-3.0 | 429; availability pending, no media request retry. |
| W07 | [What is Creative Commons?](https://commons.wikimedia.org/wiki/File:What_is_Creative_Commons%3F.webm) | CC-BY-SA-3.0 | 429; availability pending, no media request retry. |
| W08 | [What's an edit that you made on Wikipedia that you're most proud of?](https://commons.wikimedia.org/wiki/File:What%27s_an_edit_that_you_made_on_Wikipedia_that_you%27re_most_proud_of%3F.webm) | CC-BY-SA-4.0 | 429; availability pending, no media request retry. |

W07's file page supplies multiple component credits, including Kate Orange music,
CC BY/CC BY-SA components, and CC0/public-domain effects/art. Preserve that entire
credit chain and WMF/CC trademark notices (the JSON keeps component source URLs);
the author grant is not a reason to strip them. Other file pages' supplied credits
must also accompany redistribution. All rows remain untested on Android.

## 7. NASA direct MP4 leads — file-level authorization pending

These are 14 separately identified publisher records, not 14 new authorized
passes. Each listed original MP4 has an independently observed HTTPS HEAD
200 / video/mp4. Below, size means **HEAD Content-Length** and duration means
**publisher JSON**, not measured or decoded media. The JSON holds the fetched
API/collection/metadata URL, original HTTP listing, verified HTTPS counterpart,
header fingerprint and explicit pending permissions for each item.

| ID | Primary per-item metadata / title | HEAD bytes | Publisher duration | Rights / special blocker |
|---|---|---:|---|---|
| N01 | [Apollo Digest Series:  Spacecraft for Apollo](https://images-assets.nasa.gov/video/NDTV000908_Apollo_Digest_Series_Spacecraft%20for%20Apollo/metadata.json) | 708555933 | 0:04:41 | Policy only; footage/music/creator provenance not fully cleared. |
| N02 | [Official Celebration of Apollo 17 50th Anniversary](https://images-assets.nasa.gov/video/Official%20Celebration%20of%20Apollo%2017%2050th%20Anniversary/metadata.json) | 270997032 | Unknown (incomplete JSON) | Policy only; metadata truncated at 512 KiB, not parsed. |
| N03 | [Apollo 14 Launch Coverage](https://images-assets.nasa.gov/video/Apollo%2014%20Launch%20Coverage/metadata.json) | 84530297 | 0:03:08 | Policy only; footage/music/creator provenance not fully cleared. |
| N04 | [Apollo 1 Monument Dedicated at Arlington National Cemetery](https://images-assets.nasa.gov/video/Apollo%201%20Monument%20Dedicated%20at%20Arlington%20National%20Cemetery/metadata.json) | 11158563724 | 0:19:31 | Policy only; >1 GiB size lead, no transfer/device acceptance. |
| N05 | [Apollo Digest Series:  Testing Apollo](https://images-assets.nasa.gov/video/NDTV000907_Apollo_Digest_Series_Testing_Apollo/metadata.json) | 820482453 | 0:05:28 | Policy only; footage/music/creator provenance not fully cleared. |
| N06 | [NASA Chopper Ready for a Spin on Mars](https://images-assets.nasa.gov/video/JPL-20190606-TECHf-0001-Mars%20Chopper%20Ready%20for%20a%20Spin%20on%20Mars/metadata.json) | 446089778 | 0:01:35 | Policy only; footage/music/creator provenance not fully cleared. |
| N07 | [Mars Sample Return Media Reel](https://images-assets.nasa.gov/video/Mars%20Sample%20Return%20Media%20Reel/metadata.json) | 217634932 | 0:06:57 | Policy only; explicit ESA partner credit needs separate grant review. |
| N08 | [Mars Helicopter Post Flight Briefing](https://images-assets.nasa.gov/video/JPL-20210419-TECHf-0002-Mars%20Helicopter%20Post%20Flight%20Briefing/metadata.json) | 6706400502 | 1:27:33 | Policy only; >1 GiB / >60 min direct MP4 is NOT HLS. |
| N09 | [Perseverance Rover’s Descent and Touchdown on Mars: Onboard Camera Views](https://images-assets.nasa.gov/video/JPL-20210222-M2020f-0001-Perseverance%20Rover%E2%80%99s%20Descent%20and%20Touchdown%20on%20Mars/metadata.json) | 260207405 | 0:03:25 | Policy only; footage/music/creator provenance not fully cleared. |
| N10 | [JPL-20200429-TECHf-0002-Mars Helicopter Animation](https://images-assets.nasa.gov/video/JPL-20200429-TECHf-0002-Mars%20Helicopter%20Animation/metadata.json) | 1636447628 | 0:02:43 | Policy only; >1 GiB size lead, no transfer/device acceptance. |
| N11 | [NASA’s Perseverance Rover Microphone Captures Sounds from Mars - B](https://images-assets.nasa.gov/video/JPL-20210222-M2020f-0003b-M2020_Audio_B_002/metadata.json) | 46124511 | 21.06 s | Policy only; footage/music/creator provenance not fully cleared. |
| N12 | [Flight Day 19: Looking Toward Earth](https://images-assets.nasa.gov/video/FD%2019%20Earth/metadata.json) | 12231199 | 0:01:11 | Policy only; footage/music/creator provenance not fully cleared. |
| N13 | [How Do We Know the Earth Isn't Flat](https://images-assets.nasa.gov/video/How%20Do%20We%20Know%20the%20Earth%20Isn't%20Flat/metadata.json) | 444794217 | 0:01:54 | Policy only; footage/music/creator provenance not fully cleared. |
| N14 | [Webb Beauty - The Last Sunshield Deploy on Earth](https://images-assets.nasa.gov/video/Webb_Beauty_Last%20Sunshield%20Deploy%20on%20Earth/metadata.json) | 195553714 | 0:02:39 | Policy only; footage/music/creator provenance not fully cleared. |

Three prospective >1 GiB direct-file leads exist: **N04 11,158,563,724 B**, **N08
6,706,400,502 B**, **N10 1,636,447,628 B**. All require item rights clearance and
explicit storage/time/device budgeting before any full download. They are not
accepted fixtures. N08's 1:27:33 MP4 and mp4a metadata do not fill the >60-minute
**audible ended TS/AVC+AAC HLS** gate. No qualifying >60-minute HLS source was
established here; a new explicitly authorized independent work/deployment is needed.

## 8. Other investigated sources — not counted

- [Blender movie index](https://download.blender.org/demo/movies/),
  [BBB index](https://download.blender.org/demo/movies/BBB/) and
  [Tears index](https://download.blender.org/demo/movies/ToS/) were readable.
  Many entries are ZIP-packaged MP4/WebM, MOV/MKV or other assets. Do not invent
  unzipped URLs or count archives as direct MP4/WebM. No archive was downloaded.
- [Xiph media index](https://media.xiph.org/) and the film READMEs supply useful
  provenance, but inspected film assets are raw/lossless images/video and
  separate audio—not another demonstrated eligible delivery deployment.
- [NASA SVS 5450](https://svs.gsfc.nasa.gov/5450/) failed with TLS EOF;
  [SVS help](https://svs.gsfc.nasa.gov/help/) timed out in the TLS handshake.
  These are network failures, not license/content conclusions. TLS verification
  was not disabled; SVS does not count as another demonstrated environment.
- Mux's linked [ARTE China master](https://test-streams.mux.dev/test_001/stream.m3u8)
  has no CODECS declarations; no explicit media grant was established and no
  child was requested. It is not an authorized candidate or codec-fit proof.
- NASA's Apollo discovery response describes a compilation containing a **CBS
  Special Report**. Even a NASA photographer/host label does not convey CBS
  rights. It was not admitted as an authorized long sample.

## 9. Freeze and promotion checklist — for a later authorized acceptance run

1. Clear work/component rights, preserve source/license URLs and attribution;
   remove or quarantine any identity, permission or availability uncertainty.
2. Deduplicate actual works, including trailers, translated/subtitled copies and
   overlapping editorial reels. Add at least **five new work identities** even
   if every NASA lead clears; the current maximum proposed identified population
   is only 25. Relative to the current 11 CC-established groups, at least 19
   further cleared distinct groups are required to reach 30.
3. Establish at least five **authorized** actual environments. The NASA lane
   remains policy-only; four currently carry CC-established candidates. No
   deployment is final-v0.1.3-tested by this sidecar.
4. Freeze manifest hashes plus the selected rendition, actual payload identity,
   duration/track/container checks and final APK fingerprint. Revalidate changed
   masters/children; a changed hash is source drift, not a prior pass.
5. Only then run the controlled final-build corpus. Record APK SHA-256,
   versionName/versionCode/signing identity, device/OEM/model/Android/WebView,
   timestamps, network, selected endpoint/rendition, downloaded size/hash,
   completion/container validation, external playback/share and precise failure
   or correct-rejection evidence. Results are empty now; do not prepopulate PASS.
6. Keep unsupported cases outside the successful positive-download denominator.
   A future observed rejection is a rejection pass, not a saved-video pass.
   No media test for X03/ARTE is authorized by this ledger.
7. Record start/middle/end, seeking and human audible sync checks separately from
   automated header/parser/decoder checks. Short clips and a metadata audio field
   do not establish long audible playback reliability.

## 10. Pending external needs and release boundary

| Gate / need | Current status and required evidence |
|---|---|
| Two distinct OEM **physical** devices | **PENDING EXTERNAL.** Obtain at least two consenting real-device owners from different manufacturers. Record models, OS, WebView and signed candidate identity. API 28/36/37 emulators cannot satisfy this. |
| >1 GiB direct download | **PENDING.** N04/N08/N10 are uncleared HEAD-only leads. Obtain item authorization, enough storage, physical-device full-transfer/background/lockscreen/playback/share evidence. |
| >60 min audible ended TS AVC+AAC HLS | **PENDING SOURCE + TEST.** Obtain an explicitly authorized real HTTPS deployment/work. Do not concatenate short films, relabel MP4 as HLS or manufacture a hosting alias. Inspect manifest then validate payload/audio and physical-device behavior. |
| 30 distinct authorized works / five environments | **PENDING.** 30 ledger rows are only 25 provisional identities and 11 CC-established groups across four rights-established lanes. All frozen positive works must pass the final candidate, not an older build. |
| Five consenting testers continuously seven days | **PENDING EXTERNAL.** No participants or dates enrolled. Arrange actual seven-day use and privacy-respecting consent/evidence; log crashes/ANRs, blockers, wrong success, data loss/deletion and credential concerns. Seven emulator sessions or accelerated automation cannot substitute. |
| Resilience and upgrades | **PENDING OTHER WORK/DEVICE EVIDENCE.** Network/Wi-Fi limits, low storage, pause/resume, process reclamation, force-stop/reopen, reboot, expiry and remux interruption need real results. Test official signed upgrade chain only when genuine compatible artifacts exist; do not fabricate a stable v0.1.2 release. |
| Prior long-media human checks | **PENDING WHERE NOT RECORDED.** Inherited v0.1.2 automated/synthetic/emulator evidence is not human sound-sync/real-OEM acceptance. |

No cloud application, backend, accounts, payment, deployment, signing, build,
installation or release was performed. This work does not close T39 or authorize
a v0.1.3 stable tag/Release. The owning coordinator must keep the candidate/release
boundary until all gates have actual evidence.

## 11. Local sidecar validation

Validation is limited to JSON/schema-like invariants and document/ledger
consistency: 30 unique entry IDs, 25 non-null distinct work keys, 11 CC-established
work keys, five deployment IDs, referential integrity of request evidence, HTTPS
candidate media URLs, manifest observations, explicit pending gates, empty new
APK results and no sensitive response headers/raw bodies. This is **not** a
Gradle, instrumented, APK, network-resume or device test suite. Only these two
new files are owned by this work; unrelated changes in the shared workspace
were left alone.

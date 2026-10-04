// Dependency-free, memory-only worker control-variable fixtures. No fetch/network/device/Gradle.
// Run: node app/src/test/java/com/example/purebrowser/media/site/V015YouTubeCpnDiagnosticWorkerFixtures.mjs
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = new URL('../../../../../../../../../', import.meta.url);
const source = readFileSync(new URL('tools/site-parser/youtube-worker.js', root), 'utf8');
const worker = source.replace("import {Innertube,Platform,Log} from 'youtubei.js/web';", '');
const valid = 'AbCdEf012345-_xy'; // Authored fixture only; real code MUST take info.cpn.
let count = 0;
async function run(options) {
  const { flag } = options;
  const cpn = Object.hasOwn(options, 'cpn') ? options.cpn : valid;
  const messages = [], calls = { create: 0, info: 0, decipher: 0 };
  const makeFormat = (id, video) => ({ itag: id, has_video: video, has_audio: !video,
    mime_type: video ? 'video/mp4; codecs="avc1.4d401e"' : 'audio/mp4; codecs="mp4a.40.2"',
    height: video ? 360 : 0, content_length: 4096, approx_duration_ms: 888000, is_original: true,
    async decipher(player) {
      assert.equal(player, 'same-fixture-player'); calls.decipher++;
      return `https://fixture.googlevideo.com/videoplayback?itag=${id}&sig=private-fixture`;
    },
  });
  const info = { cpn, playability_status: { status: 'OK' }, basic_info: { title: 'Authored fixture' },
    streaming_data: { adaptive_formats: [makeFormat(134, true), makeFormat(140, false)] } };
  const sandbox = { Log: { setLevel() {}, Level: { NONE: 0 } }, Platform: { shim: {} },
    Innertube: { async create(options) {
      calls.create++; assert.equal(typeof options.fetch, 'function');
      return { session: { player: 'same-fixture-player' }, async getBasicInfo(id, options) {
        calls.info++; assert.equal(id, 'eRsGyueVLvQ'); assert.deepEqual({ ...options }, { client: 'IOS' }); return info;
      } };
    } }, postMessage(message) { messages.push(message); },
    onmessage: null, URL, Request, Response, atob,
  };
  vm.createContext(sandbox); vm.runInContext(worker, sandbox);
  await sandbox.onmessage({ data: { videoId: 'eRsGyueVLvQ', ...(flag === undefined ? {} : { testOnlyCpnOptIn: flag }) } });
  assert.deepEqual(calls, { create: 1, info: 1, decipher: 2 });
  assert.equal(messages.length, 1);
  return JSON.parse(JSON.stringify(messages[0]));
}
for (const flag of [undefined, false, 'true', 1]) {
  const message = await run({ flag });
  assert.ok(message.result); assert.equal(Object.hasOwn(message.result, 'diagnosticCpn'), false);
  assert.ok(!JSON.stringify(message).includes(valid)); count++;
}
const base = (await run({ flag: false })).result;
const opted = (await run({ flag: true })).result;
assert.equal(opted.diagnosticCpn, valid);
delete opted.diagnosticCpn;
assert.deepEqual(opted, base); // same getBasicInfo, one decipher per format, no rewritten URLs
assert.ok([...opted.videos, ...opted.audios].every(track => !track.url.includes('cpn=')));
count++;
for (const cpn of [undefined, null, 16, '', 'A'.repeat(15), 'A'.repeat(17),
  'A'.repeat(15) + '=', 'A'.repeat(15) + '+', 'A'.repeat(15) + '/', 'A'.repeat(15) + '\n', 'A'.repeat(15) + 'é', 'A'.repeat(16) + '\n', 'A'.repeat(16) + '\r\n']) {
  const message = await run({ flag: true, cpn });
  assert.equal(message.result, undefined);
  assert.equal(message.failure, 'YouTube 当前解析或访问条件不受支持；未创建下载任务');
  assert.ok(!JSON.stringify(message).includes('https://')); count++;
}
const ignoredInvalid = await run({ flag: false, cpn: 'bad-fixture-secret' });
assert.ok(ignoredInvalid.result); assert.ok(!JSON.stringify(ignoredInvalid).includes('bad-fixture-secret')); count++;
// Lock evidence to the actual pinned local primary-source implementation (no library downloader invoked).
const upstream = name => readFileSync(fileURLToPath(new URL(`tools/site-parser/node_modules/youtubei.js/dist/src/${name}`, root)), 'utf8');
const pkg = JSON.parse(readFileSync(new URL('tools/site-parser/node_modules/youtubei.js/package.json', root), 'utf8'));
assert.equal(pkg.version, '18.1.0');
assert.match(upstream('Innertube.js'), /async getBasicInfo[\s\S]*?const cpn = generateRandomString\(16\);[\s\S]*?new VideoInfo\(\[watch_response\], session.actions, cpn\)/);
assert.match(upstream('utils/Utils.js'), /ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_/);
assert.match(upstream('utils/FormatUtils.js'), /await format\.decipher\(player\)/);
assert.match(upstream('utils/FormatUtils.js'), /format_url}&cpn=\$\{cpn\}/);
count++;
console.log(`CpnDiagnostic worker fixtures: ${count}/${count} passed; network opens=0; media body bytes=0`);

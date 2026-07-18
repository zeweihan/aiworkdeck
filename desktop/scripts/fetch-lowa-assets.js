// Fetch the LOWA (LibreOffice WASM) runtime + an OFL CJK font into the editor
// bundle, so the packaged desktop app renders Chinese documents OFFLINE without
// reaching cdn.zetaoffice.net at runtime (Epic #43, Track A).
//
// WHY bake instead of proxy: desktop/main/zetaoffice-server.js used to proxy
// every /lowa/* request to the CDN on demand, so a first render needed network
// and the CDN being up. This script downloads the runtime at BUILD time into
// frontend/dist/zetaoffice/lowa/; the server now serves those local files first
// and only falls back to the CDN proxy for anything not bundled. It also bakes a
// deterministic OFL-licensed CJK font (Noto Sans SC) as cjk.ttc, replacing the
// previous best-effort copy of whatever system font the CI runner happened to
// have (non-deterministic, and absent offline).
//
// CONTENT-ENCODING: the CDN serves soffice.wasm and soffice.data pre-compressed
// with Brotli (content-encoding: br) and ignores Accept-Encoding: identity — the
// bytes on the wire ARE brotli. The old proxy "just worked" because it forwarded
// content-encoding and the browser decompressed. We keep that: the br files are
// stored as received (also keeps the bundle ~53 MB instead of ~165 MB raw) and a
// sidecar lowa/.encodings.json records the encoding to REPLAY when serving each
// file, so zetaoffice-server.js sets Content-Encoding correctly. The sidecar is
// written from the encoding ACTUALLY received per file (not hardcoded), so a
// self-hosted engine shipped as raw bytes (identity) records no encoding and the
// server serves it as plain wasm/data. The decoded-content magic check below is
// what catches truncation / HTML error bodies / corrupt brotli.
//
// SOURCE (self-hosting a custom-built LOWA): LOWA_BASE_URL overrides where the 4
// runtime files (soffice.js/.wasm/.data/.data.js.metadata) come from. Default is
// the ZetaOffice CDN, so the build is byte-for-byte unchanged when it's unset.
// Set it to self-host a LOWA you built yourself (e.g. one compiled --with-lang to
// include zh-CN — issue #66). The base may be:
//   - an https/http URL  (e.g. an Aliyun OSS bucket: https://<bucket>.oss-<region>.aliyuncs.com/lowa/)
//   - a file:// directory (e.g. file:///abs/path/to/lowa-build-output/  — verify locally first)
// A self-built soffice.data/.wasm is usually raw bytes (identity); that's fine —
// the encoding is detected per file and the server serves identity as-is. The CJK
// font (cjk.ttc) is unaffected by LOWA_BASE_URL; it always comes from FONT_URL.
// See desktop/scripts/lowa-selfhost.md for the end-to-end self-hosting flow.
//
// Targets (under the dedicated Vite build output, dist/zetaoffice/, which
// electron-builder ships via extraResources — package.json):
//   dist/zetaoffice/lowa/soffice.js
//   dist/zetaoffice/lowa/soffice.wasm                (brotli)
//   dist/zetaoffice/lowa/soffice.data                (brotli)
//   dist/zetaoffice/lowa/soffice.data.js.metadata
//   dist/zetaoffice/lowa/.encodings.json             (sidecar: { file: encoding })
//   dist/zetaoffice/cjk.ttc          (Noto Sans SC Regular, OFL — content is OTF;
//                                      the .ttc name matches editor-main.js's font
//                                      default and fontconfig sniffs by content)
//   dist/zetaoffice/cjk-serif.otf    (Noto Serif SC Regular, OFL — 宋体类映射目标)
//   dist/zetaoffice/cjk-kai.ttf      (LXGW WenKai / 霞鹜文楷 Regular, OFL — 楷体类)
//   dist/zetaoffice/cjk-fangsong.ttf (Zhuque Fangsong / 朱雀仿宋 Regular, OFL — 仿宋类)
//
// Usage:  node desktop/scripts/fetch-lowa-assets.js   (idempotent; keeps a file
//         that already validates). Runs in CI before electron-builder, and
//         locally to verify the bundle. Resolves output paths relative to this
//         script, so cwd does not matter.

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const zlib = require('zlib');
const { fileURLToPath } = require('url');

const LOWA_CDN = 'https://cdn.zetaoffice.net/zetaoffice_latest/';

// Where the LOWA runtime files come from. Default = the ZetaOffice CDN (so an
// unset env keeps the build byte-identical). LOWA_BASE_URL self-hosts a custom
// build — an https/http URL (e.g. Aliyun OSS) or a file:// directory. We force a
// trailing slash so `base + 'soffice.js'` always joins correctly.
const LOWA_BASE = withTrailingSlash(process.env.LOWA_BASE_URL || LOWA_CDN);
const usingCustomBase = LOWA_BASE !== LOWA_CDN;

function withTrailingSlash(s) { return s.endsWith('/') ? s : s + '/'; }

// Content-encodings we know how to decode (for the magic check) AND replay (via
// the sidecar). Anything else trips the build rather than shipping bytes the
// server can't serve correctly. null = identity (raw bytes).
const ALLOWED_ENCODINGS = new Set([null, 'br', 'gzip']);
// CJK fonts (all OFL-1.1), pinned versions. 宋体/黑体/微软雅黑/仿宋/楷体 are
// proprietary and CANNOT ship; instead we bundle one open font per Chinese
// typeface CATEGORY and zetaOfficeBoot.js's fontconfig aliases map the
// proprietary names onto them (黑体类→Noto Sans SC, 宋体类→Noto Serif SC,
// 楷体类→LXGW WenKai, 仿宋类→Zhuque Fangsong). SubsetOTF/SC = the
// Simplified-Chinese subset rather than the all-CJK OTF.
const FONT_SANS_URL = 'https://cdn.jsdelivr.net/gh/googlefonts/noto-cjk@Sans2.004/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf';
const FONT_SERIF_URL = 'https://cdn.jsdelivr.net/gh/googlefonts/noto-cjk@Serif2.002/Serif/SubsetOTF/SC/NotoSerifSC-Regular.otf';
const FONT_KAI_URL = 'https://github.com/lxgw/LxgwWenKai/releases/download/v1.520/LXGWWenKai-Regular.ttf';
// Zhuque only publishes a zip; zipEntry extracts the single ttf inside.
const FONT_FANGSONG_URL = 'https://github.com/TrionesType/zhuque/releases/download/v0.212/ZhuqueFangsong-v0.212.zip';

const distRoot = path.join(__dirname, '../../frontend/dist/zetaoffice');

// serve: 'lowa'   -> served by /lowa/*; stored as received; encoding replayed.
//        'static' -> served by the plain static handler (no encoding replay), so
//                    it MUST be identity on disk (we request identity + assert).
// encoding: the content-encoding EXPECTED from the DEFAULT CDN; asserted only on
//           the default path so a CDN change still trips the build. With a custom
//           LOWA_BASE_URL the expected encoding is unknown, so we accept whatever
//           the source actually sends (any ALLOWED_ENCODINGS) and record that.
// magic: shape check applied to the DECOMPRESSED content.
const ASSETS = [
  { url: LOWA_BASE + 'soffice.js',               dest: 'lowa/soffice.js',               serve: 'lowa',   encoding: null, magic: 'js' },
  { url: LOWA_BASE + 'soffice.wasm',             dest: 'lowa/soffice.wasm',             serve: 'lowa',   encoding: 'br', magic: 'wasm' },
  { url: LOWA_BASE + 'soffice.data',             dest: 'lowa/soffice.data',             serve: 'lowa',   encoding: 'br', magic: 'blob' },
  { url: LOWA_BASE + 'soffice.data.js.metadata', dest: 'lowa/soffice.data.js.metadata', serve: 'lowa',   encoding: null, magic: 'json' },
  { url: FONT_SANS_URL,                          dest: 'cjk.ttc',                       serve: 'static', encoding: null, magic: 'font' },
  { url: FONT_SERIF_URL,                         dest: 'cjk-serif.otf',                 serve: 'static', encoding: null, magic: 'font' },
  { url: FONT_KAI_URL,                           dest: 'cjk-kai.ttf',                   serve: 'static', encoding: null, magic: 'font' },
  { url: FONT_FANGSONG_URL,                      dest: 'cjk-fangsong.ttf',              serve: 'static', encoding: null, magic: 'font',
    zipEntry: 'ZhuqueFangsong-Regular.ttf' },
];

// Minimal single-entry zip extraction (Zhuque ships a zip with one ttf inside;
// no unzip dependency in the build scripts). Parses the End-of-Central-Directory
// record, walks the central directory to the named entry, and inflates it.
function unzipEntry(buf, entryName) {
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65536); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('zip: EOCD not found');
  const count = buf.readUInt16LE(eocd + 10);
  let off = buf.readUInt32LE(eocd + 16);
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(off) !== 0x02014b50) throw new Error('zip: bad central directory entry');
    const method = buf.readUInt16LE(off + 10);
    const compSize = buf.readUInt32LE(off + 20);
    const nameLen = buf.readUInt16LE(off + 28);
    const extraLen = buf.readUInt16LE(off + 30);
    const commentLen = buf.readUInt16LE(off + 32);
    const localOff = buf.readUInt32LE(off + 42);
    const name = buf.toString('utf8', off + 46, off + 46 + nameLen);
    if (name === entryName) {
      const lNameLen = buf.readUInt16LE(localOff + 26);
      const lExtraLen = buf.readUInt16LE(localOff + 28);
      const dataStart = localOff + 30 + lNameLen + lExtraLen;
      const data = buf.subarray(dataStart, dataStart + compSize);
      if (method === 0) return Buffer.from(data);
      if (method === 8) return zlib.inflateRawSync(data);
      throw new Error('zip: unsupported compression method ' + method);
    }
    off += 46 + nameLen + extraLen + commentLen;
  }
  throw new Error('zip: entry not found: ' + entryName);
}

// Fetch a URL (http(s) or file://); resolves {encoding, body:Buffer}. file://
// has no content-encoding, so it's always identity (null) — a self-built engine
// dropped into a local directory is served raw.
function fetchUrl(url, headers) {
  if (url.startsWith('file://')) {
    return Promise.resolve({ encoding: null, body: fs.readFileSync(fileURLToPath(url)) });
  }
  return get(url, headers);
}

// Transient CDN failures (mid-handshake TLS disconnects, 5xx) can strike several
// times in a row during a release; retry each download with backoff before
// failing the build. Deterministic failures (encoding mismatch, magic check on a
// complete body) happen after this layer and are NOT retried.
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function fetchUrlWithRetry(url, headers, attempts = 3) {
  for (let i = 1; ; i++) {
    try { return await fetchUrl(url, headers); }
    catch (e) {
      if (i >= attempts) throw e;
      const delay = 2000 * i;
      console.warn('\n  ! ' + url + ' failed (attempt ' + i + '/' + attempts + '): ' +
        (e.message || e) + '; retrying in ' + (delay / 1000) + 's');
      await sleep(delay);
    }
  }
}

// GET with up to 5 redirects; resolves {encoding, body:Buffer}.
function get(url, headers, redirects = 0) {
  const mod = url.startsWith('http://') ? http : https;
  return new Promise((resolve, reject) => {
    const req = mod.request(url, { method: 'GET', headers }, (res) => {
      const sc = res.statusCode || 0;
      if (sc >= 300 && sc < 400 && res.headers.location && redirects < 5) {
        res.resume();
        return resolve(get(new URL(res.headers.location, url).toString(), headers, redirects + 1));
      }
      if (sc !== 200) { res.resume(); return reject(new Error('GET ' + url + ' -> HTTP ' + sc)); }
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => resolve({ encoding: res.headers['content-encoding'] || null, body: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('error', reject);
    req.end();
  });
}

// Decompress (if br/gzip) then check magic bytes / shape. Catches truncation and
// HTML error bodies; corrupt brotli throws on decompress.
function decode(body, encoding) {
  if (encoding === 'br') return zlib.brotliDecompressSync(body);
  if (encoding === 'gzip') return zlib.gunzipSync(body);
  return body;
}

function checkMagic(raw, magic, dest) {
  const fail = (why) => { throw new Error('validation failed for ' + dest + ': ' + why); };
  if (raw.length < 16) fail('too small (' + raw.length + ' bytes)');
  const tag = raw.toString('latin1', 0, 4);
  if (raw[0] === 0x3c) fail('looks like HTML (starts with "<")');
  switch (magic) {
    case 'wasm':
      if (!(raw[0] === 0x00 && raw[1] === 0x61 && raw[2] === 0x73 && raw[3] === 0x6d)) fail('not a WASM module (\\0asm)');
      break;
    case 'font':
      if (tag !== 'OTTO' && tag !== 'ttcf' && tag !== 'true' &&
          !(raw[0] === 0x00 && raw[1] === 0x01 && raw[2] === 0x00 && raw[3] === 0x00)) fail('not a TTF/OTF/TTC font');
      break;
    case 'json':
      if (raw.toString('utf8', 0, 64).trim()[0] !== '{') fail('not JSON');
      break;
    case 'js':
      if (raw.length < 1024) fail('suspiciously small JS (' + raw.length + ' bytes)');
      break;
    case 'blob':
      if (raw.length < 1024) fail('suspiciously small blob (' + raw.length + ' bytes)');
      break;
  }
}

// Encoding each baked file was stored with, from the sidecar a previous run
// wrote (basename -> encoding). Authoritative for idempotent re-runs on the
// default CDN: a brotli blob must be decoded as br to validate, and 'blob'/'js'
// magic is too lenient to infer that from the bytes alone. Missing/stale -> the
// file just re-downloads.
function loadExistingEncodings() {
  try { return JSON.parse(fs.readFileSync(path.join(distRoot, 'lowa/.encodings.json'), 'utf8')); }
  catch (e) { return {}; }
}
const existingEncodings = loadExistingEncodings();

// If an on-disk file already decodes + validates, return {enc} (the encoding it
// was stored with, for re-recording); else null (re-download).
function cachedOk(destPath, a) {
  const enc = existingEncodings[path.basename(a.dest)] || null;
  try {
    checkMagic(decode(fs.readFileSync(destPath), enc), a.magic, destPath);
    return { enc };
  } catch (e) { return null; }
}

// Returns { size, enc } — enc is the content-encoding the bytes are stored with
// (null = identity), used to build the sidecar.
async function fetchAsset(a) {
  const destPath = path.join(distRoot, a.dest);
  // Cache only on the default path; a custom LOWA_BASE_URL may point at a
  // different engine than whatever is already on disk, so always re-fetch.
  if (!usingCustomBase && fs.existsSync(destPath)) {
    const hit = cachedOk(destPath, a);
    if (hit) {
      const mb = (fs.statSync(destPath).size / 1048576).toFixed(1);
      console.log('  = ' + a.dest + ' (cached, ' + mb + ' MB on disk)');
      return { size: fs.statSync(destPath).size, enc: hit.enc };
    }
  }
  process.stdout.write('  ↓ ' + a.dest + ' …');
  // 'static' assets are served without encoding replay, so insist on identity.
  const reqHeaders = a.serve === 'static' ? { 'Accept-Encoding': 'identity' } : {};
  const { encoding, body } = await fetchUrlWithRetry(a.url, reqHeaders);
  const enc = encoding || null;
  // Default CDN: assert the encoding we expect, so a CDN change trips the build.
  // Custom LOWA_BASE_URL: the encoding is unknown, so accept whatever the source
  // sends as long as we can decode + replay it (checked next).
  if (!usingCustomBase && enc !== a.encoding) {
    throw new Error(a.dest + ': expected content-encoding ' + JSON.stringify(a.encoding) +
      ' but CDN sent ' + JSON.stringify(enc) + ' (serving would corrupt; aborting)');
  }
  if (!ALLOWED_ENCODINGS.has(enc)) {
    throw new Error(a.dest + ': content-encoding ' + JSON.stringify(enc) +
      ' is not supported (expected identity, br, or gzip)');
  }
  // 'static' files are served without encoding replay, so they must be identity.
  if (a.serve === 'static' && enc !== null) {
    throw new Error(a.dest + ': static asset must be identity but source sent ' + JSON.stringify(enc));
  }
  // Zip-packaged asset: store the extracted entry, not the archive.
  let out = body;
  if (a.zipEntry) out = unzipEntry(decode(body, enc), a.zipEntry);
  checkMagic(decode(out, a.zipEntry ? null : enc), a.magic, a.dest);
  fs.mkdirSync(path.dirname(destPath), { recursive: true });
  fs.writeFileSync(destPath + '.part', out);
  fs.renameSync(destPath + '.part', destPath);
  console.log(' ' + (out.length / 1048576).toFixed(1) + ' MB' + (enc ? ' (' + enc + ')' : ''));
  return { size: out.length, enc: a.zipEntry ? null : enc };
}

async function main() {
  console.log('Fetching LOWA runtime + CJK font into ' + distRoot);
  if (usingCustomBase) console.log('  LOWA source: ' + LOWA_BASE + ' (LOWA_BASE_URL)');

  // Build the sidecar from the encoding ACTUALLY stored per file, so a
  // self-hosted identity engine records nothing (served raw) while the CDN's
  // brotli files record 'br'. zetaoffice-server.js replays these on serve;
  // without it the browser would get brotli bytes as raw wasm and fail to boot.
  let total = 0;
  const encodings = {};
  for (const a of ASSETS) {
    const { size, enc } = await fetchAsset(a);
    total += size;
    if (a.serve === 'lowa' && enc) encodings[path.basename(a.dest)] = enc;
  }
  fs.writeFileSync(path.join(distRoot, 'lowa/.encodings.json'), JSON.stringify(encodings) + '\n');

  console.log('LOWA + font baked: ' + (total / 1048576).toFixed(1) + ' MB total under ' + distRoot);
  console.log('  encodings: ' + JSON.stringify(encodings));
}

main().catch((e) => { console.error('fetch-lowa-assets failed:', e.message || e); process.exit(1); });

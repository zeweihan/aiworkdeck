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
// stored compressed (also keeps the bundle ~53 MB instead of ~165 MB raw) and a
// sidecar lowa/.encodings.json tells the server to replay Content-Encoding: br.
// Each download asserts the encoding it actually received matches what we expect,
// so a CDN change trips the build instead of silently shipping garbage.
//
// Targets (under the dedicated Vite build output, dist/zetaoffice/, which
// electron-builder ships via extraResources — package.json):
//   dist/zetaoffice/lowa/soffice.js
//   dist/zetaoffice/lowa/soffice.wasm                (brotli)
//   dist/zetaoffice/lowa/soffice.data                (brotli)
//   dist/zetaoffice/lowa/soffice.data.js.metadata
//   dist/zetaoffice/lowa/.encodings.json             (sidecar: { file: encoding })
//   dist/zetaoffice/cjk.ttc   (Noto Sans SC Regular, OFL — content is OTF; the
//                               .ttc name matches editor-main.js's fontUrl default
//                               and fontconfig sniffs by content, not extension)
//
// Usage:  node desktop/scripts/fetch-lowa-assets.js   (idempotent; keeps a file
//         that already validates). Runs in CI before electron-builder, and
//         locally to verify the bundle. Resolves output paths relative to this
//         script, so cwd does not matter.

const fs = require('fs');
const path = require('path');
const https = require('https');
const zlib = require('zlib');

const LOWA_CDN = 'https://cdn.zetaoffice.net/zetaoffice_latest/';
// Noto Sans SC Regular (OFL-1.1), pinned release tag, served via jsDelivr which
// resolves the repo's Git-LFS blob to the real font bytes. SubsetOTF/SC = the
// Simplified-Chinese subset (full SC coverage, ~8 MB) rather than the all-CJK
// OTF (~16 MB). License: googlefonts/noto-cjk LICENSE (OFL-1.1).
const FONT_URL = 'https://cdn.jsdelivr.net/gh/googlefonts/noto-cjk@Sans2.004/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf';

const distRoot = path.join(__dirname, '../../frontend/dist/zetaoffice');

// serve: 'lowa'   -> served by /lowa/*; stored as received; encoding replayed.
//        'static' -> served by the plain static handler (no encoding replay), so
//                    it MUST be identity on disk (we request identity + assert).
// encoding: the content-encoding we EXPECT (asserted against the response).
// magic: shape check applied to the DECOMPRESSED content.
const ASSETS = [
  { url: LOWA_CDN + 'soffice.js',               dest: 'lowa/soffice.js',               serve: 'lowa',   encoding: null, magic: 'js' },
  { url: LOWA_CDN + 'soffice.wasm',             dest: 'lowa/soffice.wasm',             serve: 'lowa',   encoding: 'br', magic: 'wasm' },
  { url: LOWA_CDN + 'soffice.data',             dest: 'lowa/soffice.data',             serve: 'lowa',   encoding: 'br', magic: 'blob' },
  { url: LOWA_CDN + 'soffice.data.js.metadata', dest: 'lowa/soffice.data.js.metadata', serve: 'lowa',   encoding: null, magic: 'json' },
  { url: FONT_URL,                              dest: 'cjk.ttc',                       serve: 'static', encoding: null, magic: 'font' },
];

// GET with up to 5 redirects; resolves {encoding, body:Buffer}.
function get(url, headers, redirects = 0) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, { method: 'GET', headers }, (res) => {
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

// True if an on-disk file already decodes + validates (idempotent re-runs).
function cachedOk(destPath, a) {
  try {
    checkMagic(decode(fs.readFileSync(destPath), a.encoding), a.magic, destPath);
    return true;
  } catch (e) { return false; }
}

async function fetchAsset(a) {
  const destPath = path.join(distRoot, a.dest);
  if (fs.existsSync(destPath) && cachedOk(destPath, a)) {
    const mb = (fs.statSync(destPath).size / 1048576).toFixed(1);
    console.log('  = ' + a.dest + ' (cached, ' + mb + ' MB on disk)');
    return fs.statSync(destPath).size;
  }
  process.stdout.write('  ↓ ' + a.dest + ' …');
  // 'static' assets are served without encoding replay, so insist on identity.
  const reqHeaders = a.serve === 'static' ? { 'Accept-Encoding': 'identity' } : {};
  const { encoding, body } = await get(a.url, reqHeaders);
  const enc = encoding || null;
  if (enc !== a.encoding) {
    throw new Error(a.dest + ': expected content-encoding ' + JSON.stringify(a.encoding) +
      ' but CDN sent ' + JSON.stringify(enc) + ' (serving would corrupt; aborting)');
  }
  checkMagic(decode(body, enc), a.magic, a.dest);
  fs.mkdirSync(path.dirname(destPath), { recursive: true });
  fs.writeFileSync(destPath + '.part', body);
  fs.renameSync(destPath + '.part', destPath);
  console.log(' ' + (body.length / 1048576).toFixed(1) + ' MB' + (enc ? ' (' + enc + ')' : ''));
  return body.length;
}

async function main() {
  console.log('Fetching LOWA runtime + CJK font into ' + distRoot);
  let total = 0;
  for (const a of ASSETS) total += await fetchAsset(a);

  // Sidecar so zetaoffice-server.js replays Content-Encoding for the files the
  // CDN pre-compressed (otherwise the browser gets brotli bytes as raw wasm).
  const encodings = {};
  for (const a of ASSETS) {
    if (a.serve === 'lowa' && a.encoding) encodings[path.basename(a.dest)] = a.encoding;
  }
  fs.writeFileSync(path.join(distRoot, 'lowa/.encodings.json'), JSON.stringify(encodings) + '\n');

  console.log('LOWA + font baked: ' + (total / 1048576).toFixed(1) + ' MB total under ' + distRoot);
  console.log('  encodings: ' + JSON.stringify(encodings));
}

main().catch((e) => { console.error('fetch-lowa-assets failed:', e.message || e); process.exit(1); });

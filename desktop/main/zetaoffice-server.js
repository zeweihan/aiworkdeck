// zetaoffice-server.js — shared local HTTP server for the embedded LibreOffice
// (ZetaOffice / LOWA) editor page. Epic #43.
//
// WHY a local http server (not file://): the editor needs cross-origin isolation
// (SharedArrayBuffer). COEP require-corp is injected by Electron on the dedicated
// partition (desktop/main/zetaoffice-session.js), but COEP also forbids loading
// LOWA cross-origin from the CDN
// (ERR_BLOCKED_BY_RESPONSE.NotSameOriginAfterDefaultedToSameOriginByCoep), and
// injecting CORP on cross-origin responses proved unreliable in Electron (#53).
// Serving the page + LOWA same-origin (/lowa/*) sidesteps all of that — every
// resource is same-origin so require-corp is trivially satisfied. LOWA is baked
// into the bundle at build time (Track A) and served locally so the app renders
// offline; the same-origin proxy to the CDN remains only as a fallback for any
// file not bundled.
//
// This module is the single source of that server. It was first proven in the
// ⌘⇧L verification window (zetaoffice-verify.js, #53) and is now shared by both
// the verification window (separate BrowserWindow) and the embedded <webview>
// in the main renderer. The server is memoized: the first caller starts it, the
// rest reuse the same origin. Dormant until something calls startEditorServer().

const path = require('path')
const http = require('node:http')
const https = require('node:https')
const { readFile, stat } = require('node:fs/promises')
const { createReadStream, readFileSync } = require('node:fs')
const { app } = require('electron')

const LOWA_CDN = 'https://cdn.zetaoffice.net/zetaoffice_latest/'

// Chromium keys both the HTTP disk cache and the V8 WASM code cache on
// origin+URL. A random port (listen(0)) changes the origin every app launch,
// so the 150MB soffice.wasm was re-downloaded from disk AND recompiled on
// every cold start. A fixed port keeps the origin stable across launches;
// if it's taken (second app instance, dev + packaged side by side) we fall
// back to a random port — everything still works, just without cross-launch
// cache reuse.
const FIXED_PORT = 47613

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.data': 'application/octet-stream',
  '.ttc': 'font/collection',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
}

let serverPromise = null

// dist/zetaoffice (built by `npm run build:zetaoffice`): editor.html + the
// client bundle + worker scripts (zeta.js / office_thread.js). Shipped via
// electron-builder extraResources (desktop/package.json).
function editorRoot() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'frontend/dist/zetaoffice')
    : path.join(__dirname, '../../frontend/dist/zetaoffice')
}

// 增量更新（设计 §4.3）：壳层文件（editor.html/客户端 bundle）可经 overlay 组件
// zetaoffice-wrapper 打补丁——静态服务双根查找，overlay 优先、内置兜底。
// LOWA 引擎大文件不进补丁，永远命中内置根；dev 态无 overlay。
function editorRoots() {
  const roots = []
  if (app.isPackaged) {
    try {
      const overlay = require('./services/overlay')
      const dir = overlay.componentDir(
        { packaged: true, dataDir: path.join(app.getPath('home'), '.aiworkdeck'), appVersion: app.getVersion() },
        'zetaoffice-wrapper'
      )
      if (dir) roots.push(dir)
    } catch (e) { /* overlay 异常时静默回内置 */ }
  }
  roots.push(editorRoot())
  return roots
}

// lowa/.encodings.json (written by desktop/scripts/fetch-lowa-assets.js): map of
// basename -> content-encoding to REPLAY when serving a baked file, because the
// CDN ships soffice.wasm/.data brotli-compressed and the bytes are stored as-is.
// Without this the browser would receive brotli bytes as raw wasm and fail to
// boot. Empty in dev (nothing baked); then /lowa/* falls back to the CDN proxy.
function loadLowaEncodings(root) {
  try { return JSON.parse(readFileSync(path.join(root, 'lowa', '.encodings.json'), 'utf8')) }
  catch (e) { return {} }
}

// Proxy a full https URL to res, following up to 4 redirects. Streams the body
// and forwards the headers that matter for WASM (content-type/encoding/length).
function proxyUrl(url, res, redirects = 0) {
  https.get(url, (up) => {
    const sc = up.statusCode || 0
    if (sc >= 300 && sc < 400 && up.headers.location && redirects < 4) {
      up.resume()
      const next = new URL(up.headers.location, url).toString()
      return proxyUrl(next, res, redirects + 1)
    }
    const h = {}
    for (const k of ['content-type', 'content-length', 'content-encoding', 'etag', 'last-modified', 'cache-control']) {
      if (up.headers[k]) h[k] = up.headers[k]
    }
    res.writeHead(sc || 200, h)
    up.pipe(res)
  }).on('error', (e) => { try { res.writeHead(502).end('lowa proxy error: ' + e.message) } catch (x) {} })
}

/**
 * Start (or reuse) the local editor server. Memoized — safe to call from both
 * the verify window and the embedded webview wiring.
 * @returns {Promise<{port:number, origin:string}>}
 */
function startEditorServer() {
  if (serverPromise) return serverPromise
  const root = editorRoot()
  const roots = editorRoots()
  const lowaEncodings = loadLowaEncodings(root)
  serverPromise = new Promise((resolve, reject) => {
    const s = http.createServer(async (req, res) => {
      try {
        let urlPath = decodeURIComponent((req.url || '/').split('?')[0])
        // Same-origin LOWA: serve the baked runtime (dist/zetaoffice/lowa/*,
        // produced at build time by desktop/scripts/fetch-lowa-assets.js) so the
        // app renders offline; fall back to the CDN proxy only for files not
        // bundled. Local-first is what makes the packaged app independent of
        // cdn.zetaoffice.net.
        if (urlPath.startsWith('/lowa/')) {
          const rel = urlPath.slice('/lowa/'.length)
          const lowaDir = path.join(root, 'lowa')
          const localPath = path.normalize(path.join(lowaDir, rel))
          if (localPath === lowaDir || localPath.startsWith(lowaDir + path.sep)) {
            try {
              const st = await stat(localPath)
              if (st.isFile()) {
                // ETag + no-cache (NOT immutable: /lowa/soffice.wasm keeps the
                // same URL across engine upgrades, so the browser must
                // revalidate — a localhost 304 costs ~1ms and still lets
                // Chromium reuse the cached body plus its WASM code cache).
                const etag = '"' + st.size + '-' + Math.round(st.mtimeMs) + '"'
                const cacheHeaders = { ETag: etag, 'Cache-Control': 'public, no-cache' }
                if (req.headers['if-none-match'] === etag) {
                  res.writeHead(304, cacheHeaders)
                  res.end()
                  return
                }
                const headers = {
                  'Content-Type': TYPES[path.extname(localPath)] || 'application/octet-stream',
                  'Content-Length': st.size,
                  ...cacheHeaders,
                }
                // Replay the brotli encoding for files the CDN pre-compressed.
                const enc = lowaEncodings[path.basename(localPath)]
                if (enc) headers['Content-Encoding'] = enc
                res.writeHead(200, headers)
                createReadStream(localPath).pipe(res)
                return
              }
            } catch (e) { /* not bundled locally — fall through to the CDN proxy */ }
          }
          return proxyUrl(LOWA_CDN + rel, res)
        }
        if (urlPath === '/') urlPath = '/editor.html'
        // 双根查找：overlay（补丁壳层）优先，内置兜底（增量更新设计 §4.3）
        let body = null
        let served = null
        for (const r of roots) {
          const filePath = path.normalize(path.join(r, urlPath))
          if (filePath !== r && !filePath.startsWith(r + path.sep)) { res.writeHead(403).end('forbidden'); return }
          try {
            body = await readFile(filePath)
            served = filePath
            break
          } catch (e) { /* 该根没有此文件，试下一根 */ }
        }
        if (body === null) throw new Error('not found in any root')
        // No COOP/COEP here — Electron injects them on the partition.
        res.writeHead(200, { 'Content-Type': TYPES[path.extname(served)] || 'application/octet-stream' })
        res.end(body)
      } catch (e) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found')
      }
    })
    let fellBack = false
    s.on('listening', () => {
      const port = s.address().port
      resolve({ port, origin: 'http://127.0.0.1:' + port })
    })
    s.on('error', (e) => {
      if (!fellBack && e && e.code === 'EADDRINUSE') {
        fellBack = true
        s.listen(0, '127.0.0.1')
        return
      }
      serverPromise = null
      reject(e)
    })
    s.listen(FIXED_PORT, '127.0.0.1')
  })
  return serverPromise
}

/**
 * Build the editor page URL for a given origin. LOWA goes through the
 * same-origin proxy (?lowa=); zeta.js / office_thread.js / cjk.ttc resolve to
 * their relative defaults served next to the page.
 * @param {string} origin e.g. http://127.0.0.1:54321
 * @param {{verify?:boolean, uilang?:string}} [opts] verify=1 shows the standalone
 *   test panel; uilang 走 editor-main.js 的既有 ?uilang= 契约（LO 画布 UI 语言，
 *   引擎双语资源已随包，缺省时 editor-main.js 落回 zh-CN）。
 */
function editorUrl(origin, opts = {}) {
  const q = new URLSearchParams()
  if (opts.verify) q.set('verify', '1')
  if (opts.uilang) q.set('uilang', opts.uilang)
  q.set('lowa', origin + '/lowa/')
  return origin + '/editor.html?' + q.toString()
}

module.exports = { startEditorServer, editorUrl }

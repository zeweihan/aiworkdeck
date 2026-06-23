// zetaoffice-server.js — shared local HTTP server for the embedded LibreOffice
// (ZetaOffice / LOWA) editor page. Epic #43.
//
// WHY a local http server (not file://): the editor needs cross-origin isolation
// (SharedArrayBuffer). COEP require-corp is injected by Electron on the dedicated
// partition (desktop/main/zetaoffice-session.js), but COEP also forbids loading
// LOWA cross-origin from the CDN
// (ERR_BLOCKED_BY_RESPONSE.NotSameOriginAfterDefaultedToSameOriginByCoep), and
// injecting CORP on cross-origin responses proved unreliable in Electron (#53).
// Serving the page + proxying LOWA same-origin (/lowa/*) sidesteps all of that —
// every resource is same-origin so require-corp is trivially satisfied. This is
// also the production direction (self-host LOWA in the bundle).
//
// This module is the single source of that server. It was first proven in the
// ⌘⇧L verification window (zetaoffice-verify.js, #53) and is now shared by both
// the verification window (separate BrowserWindow) and the embedded <webview>
// in the main renderer. The server is memoized: the first caller starts it, the
// rest reuse the same origin. Dormant until something calls startEditorServer().

const path = require('path')
const http = require('node:http')
const https = require('node:https')
const { readFile } = require('node:fs/promises')
const { app } = require('electron')

const LOWA_CDN = 'https://cdn.zetaoffice.net/zetaoffice_latest/'

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
  serverPromise = new Promise((resolve, reject) => {
    const s = http.createServer(async (req, res) => {
      try {
        let urlPath = decodeURIComponent((req.url || '/').split('?')[0])
        // Same-origin LOWA proxy: /lowa/<path> -> the LOWA CDN.
        if (urlPath.startsWith('/lowa/')) {
          return proxyUrl(LOWA_CDN + urlPath.slice('/lowa/'.length), res)
        }
        if (urlPath === '/') urlPath = '/editor.html'
        const filePath = path.normalize(path.join(root, urlPath))
        if (!filePath.startsWith(root)) { res.writeHead(403).end('forbidden'); return }
        // No COOP/COEP here — Electron injects them on the partition.
        const body = await readFile(filePath)
        res.writeHead(200, { 'Content-Type': TYPES[path.extname(filePath)] || 'application/octet-stream' })
        res.end(body)
      } catch (e) {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found')
      }
    })
    s.on('error', (e) => { serverPromise = null; reject(e) })
    s.listen(0, '127.0.0.1', () => {
      const port = s.address().port
      resolve({ port, origin: 'http://127.0.0.1:' + port })
    })
  })
  return serverPromise
}

/**
 * Build the editor page URL for a given origin. LOWA goes through the
 * same-origin proxy (?lowa=); zeta.js / office_thread.js / cjk.ttc resolve to
 * their relative defaults served next to the page.
 * @param {string} origin e.g. http://127.0.0.1:54321
 * @param {{verify?:boolean}} [opts] verify=1 shows the standalone test panel.
 */
function editorUrl(origin, opts = {}) {
  const q = new URLSearchParams()
  if (opts.verify) q.set('verify', '1')
  q.set('lowa', origin + '/lowa/')
  return origin + '/editor.html?' + q.toString()
}

module.exports = { startEditorServer, editorUrl }

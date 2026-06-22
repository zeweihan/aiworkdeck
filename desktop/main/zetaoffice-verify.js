// zetaoffice-verify.js — dedicated "LibreOffice 验证" window for a packaged
// build. Epic #43. Lets the maintainer install and SEE the embedded LibreOffice
// editor boot + render Chinese + run an AI command (redline) inside the real app,
// WITHOUT touching the WPS document flow (zero blast radius on the shipping
// product). Triggered by a global shortcut (registered in main.js).
//
// LOWA needs cross-origin isolation (SharedArrayBuffer). The main window can't be
// isolated (webSecurity:false + cross-origin WPS/AI), so this opens a SEPARATE
// BrowserWindow on a dedicated partition whose session gets COOP/COEP injected
// (desktop/main/zetaoffice-session.js, #47).
//
// LOWA is served SAME-ORIGIN: the local server proxies /lowa/* -> the LOWA CDN.
// Loading soffice.{js,wasm,data} cross-origin from the CDN directly is blocked by
// COEP (ERR_BLOCKED_BY_RESPONSE.NotSameOriginAfterDefaultedToSameOriginByCoep) —
// injecting CORP on cross-origin responses via onHeadersReceived proved
// unreliable in Electron. Proxying makes every LOWA resource same-origin, so COEP
// require-corp is satisfied with no CORP gymnastics. (This is also the production
// direction: self-host LOWA in the bundle.)
//
// Dormant until the shortcut fires; nothing imports it at startup.

const path = require('path')
const http = require('node:http')
const https = require('node:https')
const { readFile } = require('node:fs/promises')
const { app, BrowserWindow } = require('electron')
const { installZetaOfficeIsolation, ZETAOFFICE_PARTITION } = require('./zetaoffice-session')

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

let server = null
let verifyWin = null

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

function startServer(root) {
  if (server) return Promise.resolve(server.address().port)
  return new Promise((resolve, reject) => {
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
    s.on('error', reject)
    s.listen(0, '127.0.0.1', () => { server = s; resolve(s.address().port) })
  })
}

/**
 * Open (or focus) the LibreOffice verification window.
 */
async function openZetaOfficeVerifyWindow() {
  if (verifyWin && !verifyWin.isDestroyed()) { verifyWin.focus(); return verifyWin }

  installZetaOfficeIsolation(ZETAOFFICE_PARTITION)
  const port = await startServer(editorRoot())
  const origin = 'http://127.0.0.1:' + port

  verifyWin = new BrowserWindow({
    width: 1280,
    height: 860,
    title: 'AI Workdeck · LibreOffice 验证 (experimental)',
    webPreferences: {
      partition: ZETAOFFICE_PARTITION,
      contextIsolation: true,
      nodeIntegration: false,
    },
  })
  verifyWin.on('closed', () => { verifyWin = null })
  // LOWA via the same-origin proxy (?lowa=). zeta.js / office_thread.js / cjk.ttc
  // are served locally next to the page (their relative defaults resolve here).
  verifyWin.loadURL(origin + '/editor.html?verify=1&lowa=' + encodeURIComponent(origin + '/lowa/'))
  verifyWin.webContents.openDevTools({ mode: 'detach' })
  return verifyWin
}

module.exports = { openZetaOfficeVerifyWindow }

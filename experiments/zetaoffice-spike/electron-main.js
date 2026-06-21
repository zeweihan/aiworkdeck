// SPDX-License-Identifier: MIT
//
// Standalone Electron launcher for the ZetaOffice spike (#39).
//
// Why standalone (not wired into desktop/main/main.js): the spike is pure
// frontend WASM and needs NO backend — running it through the product main
// process would drag in backend startup (and the bundled-JRE SIGBUS on the
// maintainer's machine) and force a global COEP policy that would break the
// main app's cross-origin subresources. This launcher touches nothing in the
// product and doubles as the reference for how the product will later inject
// COOP/COEP.
//
// The embedded static server intentionally does NOT set COOP/COEP. Instead we
// inject them in Electron via session.webRequest.onHeadersReceived — exactly
// the mechanism the product (desktop/main/main.js) will use when the editor
// migrates. That makes this an authentic test of the Electron integration path,
// not just of the browser path that serve.mjs already proved.
//
// Run (uses the project's already-installed Electron, no extra download):
//   cd experiments/zetaoffice-spike
//   ../../desktop/node_modules/.bin/electron .        # or: npx electron .
//
// Then: click "Boot ZetaOffice", watch the canvas render a Writer doc, type
// Chinese with your IME, and click the selection / redline / perf probes.

const { app, BrowserWindow, session } = require('electron')
const http = require('node:http')
const { readFile } = require('node:fs/promises')
const { extname, join, normalize } = require('node:path')

const PORT = 8799
const ROOT = __dirname

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.wasm': 'application/wasm',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.svg': 'image/svg+xml'
}

function startServer() {
  return new Promise((resolve, reject) => {
    const server = http.createServer(async (req, res) => {
      let urlPath = decodeURIComponent((req.url || '/').split('?')[0])
      if (urlPath === '/') urlPath = '/index.html'
      const filePath = normalize(join(ROOT, urlPath))
      if (!filePath.startsWith(ROOT)) { res.writeHead(403).end('forbidden'); return }
      try {
        const body = await readFile(filePath)
        // No COOP/COEP here on purpose — Electron injects them (see below).
        res.writeHead(200, { 'Content-Type': TYPES[extname(filePath)] || 'application/octet-stream' })
        res.end(body)
      } catch {
        res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found: ' + urlPath)
      }
    })
    server.on('error', reject)
    server.listen(PORT, () => resolve(server))
  })
}

async function main() {
  await app.whenReady()
  await startServer()

  // THE product-relevant bit: make the renderer cross-origin isolated by
  // injecting COOP/COEP on every response. This is what desktop/main/main.js
  // will add (scoped to a dedicated session/partition) when the LibreOffice
  // editor ships, so the same WASM that needs SharedArrayBuffer can boot.
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    const headers = Object.assign({}, details.responseHeaders)
    headers['Cross-Origin-Opener-Policy'] = ['same-origin']
    headers['Cross-Origin-Embedder-Policy'] = ['require-corp']
    headers['Cross-Origin-Resource-Policy'] = ['cross-origin']
    callback({ responseHeaders: headers })
  })

  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    title: 'ZetaOffice Spike (Electron) · #39',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false
    }
  })
  win.loadURL('http://localhost:' + PORT + '/')
  win.webContents.openDevTools({ mode: 'detach' })
}

main().catch((e) => { console.error('[zeta-spike electron] fatal:', e); app.quit() })

app.on('window-all-closed', () => app.quit())

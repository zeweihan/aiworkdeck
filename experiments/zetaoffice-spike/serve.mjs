// Minimal static server for the ZetaOffice spike.
//
// LibreOffice WASM uses pthreads -> SharedArrayBuffer, which browsers only
// expose when the page is cross-origin isolated. That requires these two
// response headers on every document/script we serve:
//   Cross-Origin-Opener-Policy: same-origin
//   Cross-Origin-Embedder-Policy: require-corp
// Without them, `crossOriginIsolated` is false and ZetaOffice will not boot.
//
// In the Electron product these same headers must be injected via
// session.webRequest.onHeadersReceived — see README.
//
// Usage: node serve.mjs [port]   (default 8777)

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('.', import.meta.url))
const PORT = Number(process.argv[2]) || Number(process.env.PORT) || 8777

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

const server = createServer(async (req, res) => {
  // Cross-origin isolation — mandatory for SharedArrayBuffer / WASM threads.
  res.setHeader('Cross-Origin-Opener-Policy', 'same-origin')
  res.setHeader('Cross-Origin-Embedder-Policy', 'require-corp')
  res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin')

  let urlPath = decodeURIComponent((req.url || '/').split('?')[0])
  if (urlPath === '/') urlPath = '/index.html'

  // /shared/<file> → the REAL product executor client modules
  // (frontend/src/composables), so the spike can drive product code against a
  // real LibreOffice. Scoped + read-only; basename only (no nested traversal).
  let filePath
  if (urlPath.startsWith('/shared/')) {
    const base = urlPath.slice('/shared/'.length).replace(/[^A-Za-z0-9._-]/g, '')
    const SHARED_ROOT = normalize(join(ROOT, '../../frontend/src/composables'))
    filePath = normalize(join(SHARED_ROOT, base))
    if (!filePath.startsWith(SHARED_ROOT)) { res.writeHead(403).end('forbidden'); return }
  } else {
    // Contain path traversal to ROOT.
    filePath = normalize(join(ROOT, urlPath))
    if (!filePath.startsWith(ROOT)) { res.writeHead(403).end('forbidden'); return }
  }

  try {
    const body = await readFile(filePath)
    res.writeHead(200, { 'Content-Type': TYPES[extname(filePath)] || 'application/octet-stream' })
    res.end(body)
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('not found: ' + urlPath)
  }
})

server.listen(PORT, () => {
  console.log(`ZetaOffice spike server on http://localhost:${PORT}`)
  console.log('COOP/COEP set -> crossOriginIsolated should be true in the page.')
})

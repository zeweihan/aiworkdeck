// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Opt-in, synthetic LOWA experiment. Run with Electron; see desktop/README.md.
// This measures engine instances, not the entire app or Java/Python processes.
const { app, BrowserWindow } = require('electron')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const http = require('node:http')

const root = path.resolve(process.env.AWD_EDITOR_DIR || path.join(__dirname, '../../frontend/dist/zetaoffice'))
const output = path.resolve(process.env.AWD_MEMORY_OUTPUT || path.join(os.tmpdir(), 'awd-editor-memory.json'))
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-memory-'))
app.setPath('userData', profile)
app.on('window-all-closed', () => {})
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const windows = []
const report = { platform: process.platform, electron: process.versions.electron, totalBytes: os.totalmem(), editorRoot: root, samples: [] }
let server

function sample(stage) {
  const processes = app.getAppMetrics().map(p => ({ pid: p.pid, type: p.type, memory: p.memory }))
  const row = { stage, processes, workingSetMiB: processes.reduce((n, p) => n + p.memory.workingSetSize / 1024, 0) }
  report.samples.push(row)
  fs.writeFileSync(output, JSON.stringify(report, null, 2))
  console.log(JSON.stringify({ stage, workingSetMiB: row.workingSetMiB }))
}

async function openEngine(origin) {
  const win = new BrowserWindow({ show: false, width: 1280, height: 850, webPreferences: { backgroundThrottling: false } })
  windows.push(win)
  await win.loadURL(origin + '/editor.html?verify=1&lowa=/lowa/')
  const deadline = Date.now() + 240000
  while (Date.now() < deadline) {
    if (await win.webContents.executeJavaScript('!!window.__loExecutor')) return win
    await sleep(1000)
  }
  throw new Error('LOWA did not become ready in 240 seconds')
}

app.whenReady().then(async () => {
  for (const file of ['editor.html', 'lowa/soffice.js']) {
    if (!fs.existsSync(path.join(root, file))) throw new Error('Missing editor asset: ' + file)
  }
  const encFile = path.join(root, 'lowa/.encodings.json')
  const encodings = fs.existsSync(encFile) ? JSON.parse(fs.readFileSync(encFile, 'utf8')) : {}
  server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent(new URL(req.url, 'http://localhost').pathname)
    const file = path.resolve(root, '.' + (urlPath === '/' ? '/editor.html' : urlPath))
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404); res.end(); return
    }
    const types = { '.html': 'text/html', '.js': 'application/javascript', '.wasm': 'application/wasm', '.css': 'text/css', '.json': 'application/json' }
    const headers = { 'Cross-Origin-Opener-Policy': 'same-origin', 'Cross-Origin-Embedder-Policy': 'require-corp', 'Content-Type': types[path.extname(file)] || 'application/octet-stream' }
    if (urlPath.startsWith('/lowa/') && encodings[path.basename(file)]) headers['Content-Encoding'] = encodings[path.basename(file)]
    res.writeHead(200, headers)
    fs.createReadStream(file).pipe(res)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const origin = 'http://127.0.0.1:' + server.address().port
  sample('shell-only')
  const active = await openEngine(origin)
  await sleep(15000); sample('one-ready-engine')
  const spare = await openEngine(origin)
  await sleep(15000); sample('two-ready-engines')
  spare.destroy()
  await sleep(15000); sample('spare-destroyed')
  // Prove the surviving engine can still edit, export and reload a synthetic doc.
  const proof = await active.webContents.executeJavaScript(`(async () => {
    const run = (action, payload) => window.__loExecutor.executeCommand(action, payload);
    const marker = 'AWD_MEMORY_AUDIT_SYNTHETIC';
    const edit = await run('insert_at_cursor', { text: marker });
    if (!edit.success) throw new Error(JSON.stringify(edit));
    const exported = await run('export_document', { name: 'memory-audit.docx' });
    if (!exported.success || !exported.bytes?.length) throw new Error('Export failed');
    const loaded = await run('load_document', { name: 'memory-audit.docx', bytes: exported.bytes });
    if (!loaded.success) throw new Error(JSON.stringify(loaded));
    const text = await run('get_document_text', {});
    if (!text.success || !JSON.stringify(text).includes(marker)) throw new Error('Reloaded text missing');
    return { edit: true, exportBytes: exported.bytes.length, reload: true, markerPresent: true };
  })()`)
  report.functionalProof = proof
  active.destroy()
  await sleep(5000); sample('all-engines-closed')
  console.log('Report: ' + output)
}).catch(error => {
  report.error = error.message
  fs.writeFileSync(output, JSON.stringify(report, null, 2))
  console.error(error)
  process.exitCode = 1
}).finally(() => {
  for (const win of windows) if (!win.isDestroyed()) win.destroy()
  if (server) server.close()
  app.exit(process.exitCode || 0)
})

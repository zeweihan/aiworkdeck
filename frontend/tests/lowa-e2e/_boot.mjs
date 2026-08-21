// lowa-e2e 共用的启动件：preflight、COOP/COEP 静态服务、无头 Chrome、打开
// editor.html?verify=1 并等到 window.__loExecutor 就绪。run.mjs（键盘链路回归）
// 与 big-doc.mjs（大文档基线组）共用这一份，行为与抽出前的 run.mjs 一致。
//
// Env:  LOWA_ENGINE_DIR   serve the LOWA runtime (soffice.*) from an external
//                         dir instead of dist/zetaoffice/lowa — survives
//                         `npm run build:zetaoffice`, which EMPTIES dist and
//                         deletes the fetched engine.
//       PUPPETEER_EXECUTABLE_PATH  Chrome binary (default: mac Google Chrome).
//       LOWA_E2E_PORT     server port (default 8901).

import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const here = path.dirname(fileURLToPath(import.meta.url))
export const distDir = path.resolve(here, '../../dist/zetaoffice')
export const engineDir = process.env.LOWA_ENGINE_DIR || path.join(distDir, 'lowa')
export const PORT = Number(process.env.LOWA_E2E_PORT || 8901)
export const ORIGIN = 'http://127.0.0.1:' + PORT
export const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

// ---------- preflight ----------
// extra: 额外必须存在的 [说明, 路径] 对（big-doc.mjs 用它要求夹具已生成）。
export function preflight(extra = []) {
  for (const [what, p] of [
    ['dist/zetaoffice (npm run build:zetaoffice)', path.join(distDir, 'editor.html')],
    ['LOWA engine (fetch-lowa-assets.js or LOWA_ENGINE_DIR)', path.join(engineDir, 'soffice.js')],
    ['Chrome (PUPPETEER_EXECUTABLE_PATH)', CHROME],
    ...extra,
  ]) {
    if (!fs.existsSync(p)) { console.error('缺少 ' + what + ': ' + p); process.exit(2) }
  }
}

export async function loadPuppeteer() {
  try { return (await import('puppeteer-core')).default }
  catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }
}

// ---------- COOP/COEP static server ----------
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm',
  '.data': 'application/octet-stream', '.json': 'application/json',
  '.ttc': 'font/collection', '.ttf': 'font/ttf', '.otf': 'font/otf',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
}

/**
 * @param {object} [opts]
 * @param {(urlPath:string, content:Buffer)=>Buffer} [opts.patchServed] 内存改写
 *        被服务的资产（注入测试专用 worker 动作等）；源码与 dist 不动。
 * @param {Record<string,string>} [opts.extraFiles] 额外路由：url 路径 -> 磁盘文件。
 */
export async function startServer({ patchServed = (u, c) => c, extraFiles = {} } = {}) {
  const encPath = path.join(engineDir, '.encodings.json')
  const encodings = fs.existsSync(encPath) ? JSON.parse(fs.readFileSync(encPath, 'utf8')) : {}
  const server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent(req.url.split('?')[0])
    const fromEngine = urlPath.startsWith('/lowa/')
    const fp = extraFiles[urlPath] ? extraFiles[urlPath] : fromEngine
      ? path.join(engineDir, urlPath.slice('/lowa/'.length))
      : path.join(distDir, urlPath === '/' ? 'editor.html' : urlPath)
    if (!fs.existsSync(fp) || fs.statSync(fp).isDirectory()) { res.writeHead(404); res.end(); return }
    const headers = {
      'Cross-Origin-Opener-Policy': 'same-origin',
      'Cross-Origin-Embedder-Policy': 'require-corp',
      'Cache-Control': 'no-store',
      'Content-Type': MIME[path.extname(fp)] || 'application/octet-stream',
    }
    if (fromEngine && encodings[path.basename(fp)]) headers['Content-Encoding'] = encodings[path.basename(fp)]
    const body = patchServed(urlPath, fs.readFileSync(fp))
    res.writeHead(200, headers)
    res.end(body)
  })
  await new Promise((r) => server.listen(PORT, r))
  console.log('serving ' + distDir + ' (engine: ' + engineDir + ') on ' + ORIGIN)
  return server
}

// ---------- browser ----------
export async function launchBrowser(puppeteer) {
  // protocolTimeout：worker 里一条几十秒的同步 UNO 操作会连带冻住页面主线程
  // （em-pthread 代理），page.evaluate 在那期间拿不到回包；默认 180s 会把大文档组
  // 改造前的 apply_house_style 基线直接打成 ProtocolError。
  return puppeteer.launch({ executablePath: CHROME, headless: 'new', protocolTimeout: 600000, args: ['--no-sandbox', '--disable-dev-shm-usage'] })
}

/** 打开 editor.html?verify=1 并等引擎就绪（window.__loExecutor）。 */
export async function openEditor(browser, { clipboard = true } = {}) {
  if (clipboard) {
    await browser.defaultBrowserContext().overridePermissions(ORIGIN, ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write'])
  }
  const page = await browser.newPage()
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  console.log('booting engine (~90s)...')
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  return page
}

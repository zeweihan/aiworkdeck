#!/usr/bin/env node
// LOWA 编辑器"真人模拟"端到端回归 / human-simulation e2e for the LibreOffice
// WASM editor. Boots the REAL engine headlessly and drives the REAL overlay
// keyboard path (CDP key events + IME composition), asserting document/cursor/
// clipboard state after every interaction — the same chain a human exercises.
//
// WHY this exists: primitive-level tests kept passing while real users hit
// "Backspace 只能删一个"、"Delete/Cmd+Z 没反应" — bugs that only live in the
// key-event → overlay → worker → UNO chain. This suite IS that chain.
//
// Run:  npm run test:lowa-e2e        (from frontend/)
// Env:  LOWA_ENGINE_DIR   serve the LOWA runtime (soffice.*) from an external
//                         dir instead of dist/zetaoffice/lowa — survives
//                         `npm run build:zetaoffice`, which EMPTIES dist and
//                         deletes the fetched engine.
//       PUPPETEER_EXECUTABLE_PATH  Chrome binary (default: mac Google Chrome).
//       LOWA_E2E_PORT     server port (default 8901).
//
// Prereqs: dist/zetaoffice built (npm run build:zetaoffice) + engine files
// (node ../desktop/scripts/fetch-lowa-assets.js, or LOWA_ENGINE_DIR).
//
// Test-only worker actions (debug_*) are injected IN-MEMORY by the server into
// the served office_thread.js / editor bundle — source and dist stay clean.

import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const distDir = path.resolve(here, '../../dist/zetaoffice')
const engineDir = process.env.LOWA_ENGINE_DIR || path.join(distDir, 'lowa')
const PORT = Number(process.env.LOWA_E2E_PORT || 8901)
const ORIGIN = 'http://127.0.0.1:' + PORT
const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'

// ---------- preflight ----------
for (const [what, p] of [
  ['dist/zetaoffice (npm run build:zetaoffice)', path.join(distDir, 'editor.html')],
  ['LOWA engine (fetch-lowa-assets.js or LOWA_ENGINE_DIR)', path.join(engineDir, 'soffice.js')],
  ['Chrome (PUPPETEER_EXECUTABLE_PATH)', CHROME],
]) {
  if (!fs.existsSync(p)) { console.error('缺少 ' + what + ': ' + p); process.exit(2) }
}
let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core：cd frontend && npm i -D puppeteer-core'); process.exit(2) }

// ---------- test-only worker actions, injected in-memory ----------
const DEBUG_ACTIONS = `
  debug_set_record_changes(p) {
    xModel.setPropertyValue('RecordChanges', !!p.on);
    return { success: true, recordChanges: xModel.getPropertyValue('RecordChanges') };
  },
  debug_char_prop(p) {
    const vc = ctrl.getViewCursor();
    return { success: true, value: vc.getPropertyValue(String(p.prop)), selected: (vc.getString() || '').slice(0, 40) };
  },
`
function patchServed(urlPath, content) {
  if (urlPath === '/office_thread.js') {
    const s = content.toString('utf8')
    if (!s.includes('const EXEC = {')) throw new Error('office_thread.js: EXEC anchor missing')
    return Buffer.from(s.replace('const EXEC = {', 'const EXEC = {\n' + DEBUG_ACTIONS), 'utf8')
  }
  if (/^\/assets\/editor-.*\.js$/.test(urlPath)) {
    const s = content.toString('utf8')
    return Buffer.from(
      s.replace("'get_hyperlink_at_cursor'", "'get_hyperlink_at_cursor','debug_set_record_changes','debug_char_prop'")
        .replace('"get_hyperlink_at_cursor"', '"get_hyperlink_at_cursor","debug_set_record_changes","debug_char_prop"'),
      'utf8')
  }
  return content
}

// ---------- COOP/COEP static server ----------
const encPath = path.join(engineDir, '.encodings.json')
const encodings = fs.existsSync(encPath) ? JSON.parse(fs.readFileSync(encPath, 'utf8')) : {}
const MIME = {
  '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm',
  '.data': 'application/octet-stream', '.json': 'application/json',
  '.ttc': 'font/collection', '.ttf': 'font/ttf', '.otf': 'font/otf',
}
const server = http.createServer((req, res) => {
  const urlPath = decodeURIComponent(req.url.split('?')[0])
  const fromEngine = urlPath.startsWith('/lowa/')
  const fp = fromEngine
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

// ---------- assertions ----------
let passed = 0, failed = 0
function check(label, cond, detail) {
  if (cond) { passed++; console.log('  PASS ' + label) }
  else { failed++; console.log('  FAIL ' + label + (detail ? '  [' + detail + ']' : '')) }
}

// ---------- drive ----------
const META = 4, SHIFT = 8, ALT = 1
const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage'] })
try {
  await browser.defaultBrowserContext().overridePermissions(ORIGIN, ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write'])
  const page = await browser.newPage()
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  console.log('booting engine (~90s)...')
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  await page.evaluate(() => { window.__overlayInput = document.querySelector('input[aria-hidden]') })

  const cdp = await page.createCDPSession()
  const key = async (k, code, vk, modifiers = 0) => {
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers })
    await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers })
    await new Promise((r) => setTimeout(r, 350))
  }
  const exec = (a, p) => page.evaluate((a2, p2) => window.__loExecutor.executeCommand(a2, p2 || {}), a, p)
  const doc = async () => (await exec('get_document_text')).paragraphs.map((x) => x.text).join('|')
  const cursor = async () => { const r = await exec('get_cursor_context'); return { b: r.before || '', a: r.after || '' } }
  const focus = () => page.evaluate(() => window.__overlayInput.focus())
  // hard reset: revisions off so leftovers (incl. redline remnants) truly vanish
  const reset = async (text, rcOn = true) => {
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: text || '' })
    await exec('goto', { type: 'end' })
    await exec('debug_set_record_changes', { on: rcOn })
    await focus()
  }

  // NOTE: ShowChangesInMargin (tdf#34355) is ON since the margin-redlines change —
  // tracked DELETIONS leave the inline text (they render in the page margin), so
  // the cursor context no longer contains the struck-through originals. The old
  // expectations (deleted chars still inline) date from inline-strikethrough mode.
  console.log('== 1) Backspace over pre-existing text (revision-mode jam regression #164) ==')
  await reset('合同条款abc') // inserted with rc OFF -> "original" text; rc back ON
  for (let i = 0; i < 3; i++) await key('Backspace', 'Backspace', 8)
  let c = await cursor()
  check('3×Backspace 删除移入页边（正文不留）', c.b === '合同条款' && c.a === '', JSON.stringify(c))

  console.log('== 2) Delete key forward-deletes ==')
  await reset('合同条款abc')
  for (let i = 0; i < 3; i++) await key('ArrowLeft', 'ArrowLeft', 37)
  for (let i = 0; i < 2; i++) await key('Delete', 'Delete', 46)
  c = await cursor()
  check('2×Delete 前删移入页边（正文不留）', c.b === '合同条款' && c.a === 'c', JSON.stringify(c))

  console.log('== 3) IME CJK commit + Backspace hard-deletes own insert ==')
  await reset('')
  await cdp.send('Input.imeSetComposition', { text: '中文', selectionStart: 2, selectionEnd: 2 })
  await cdp.send('Input.insertText', { text: '中文' })
  await new Promise((r) => setTimeout(r, 500))
  check('IME 上屏', await doc() === '中文', await doc())
  await key('Backspace', 'Backspace', 8)
  check('上屏后退格真删', await doc() === '中', await doc())

  console.log('== 4) undo / redo ==')
  await reset('')
  await exec('insert_at_cursor', { text: 'abc中文' })
  await focus()
  await key('z', 'KeyZ', 90, META)
  check('Cmd+Z 撤销插入', await doc() === '', await doc())
  await key('z', 'KeyZ', 90, META | SHIFT)
  check('Cmd+Shift+Z 重做', await doc() === 'abc中文', await doc())

  console.log('== 5) select all / bold toggle ==')
  await key('a', 'KeyA', 65, META)
  check('Cmd+A 全选', (await exec('get_selection')).text === 'abc中文')
  await key('b', 'KeyB', 66, META)
  let w = await exec('debug_char_prop', { prop: 'CharWeight' })
  check('Cmd+B 加粗', w.value === 150, JSON.stringify(w.value))
  await key('b', 'KeyB', 66, META)
  w = await exec('debug_char_prop', { prop: 'CharWeight' })
  check('Cmd+B 再按取消', w.value === 100, JSON.stringify(w.value))

  console.log('== 6) clipboard copy / paste / cut ==')
  await key('a', 'KeyA', 65, META)
  await key('c', 'KeyC', 67, META)
  await new Promise((r) => setTimeout(r, 500))
  check('Cmd+C 系统剪贴板', await page.evaluate(() => navigator.clipboard.readText()) === 'abc中文')
  await page.evaluate(() => navigator.clipboard.writeText('粘贴段一\n粘贴段二'))
  await key('a', 'KeyA', 65, META)
  await key('v', 'KeyV', 86, META)
  await new Promise((r) => setTimeout(r, 500))
  check('Cmd+V 多段粘贴覆盖选区', await doc() === '粘贴段一|粘贴段二', await doc())
  await key('a', 'KeyA', 65, META)
  await key('x', 'KeyX', 88, META)
  await new Promise((r) => setTimeout(r, 500))
  check('Cmd+X 剪切入剪贴板', await page.evaluate(() => navigator.clipboard.readText()) === '粘贴段一\n粘贴段二')
  check('Cmd+X 后文档已空', await doc() === '', await doc())

  console.log('== 7) line / word / doc navigation ==')
  await reset('甲方 乙方 丙方')
  await key('Home', 'Home', 36)
  c = await cursor()
  check('Home 行首', c.b === '' && c.a === '甲方 乙方 丙方', JSON.stringify(c))
  await key('End', 'End', 35, SHIFT)
  check('Shift+End 选至行尾', (await exec('get_selection')).text === '甲方 乙方 丙方')
  await key('End', 'End', 35)
  await key('ArrowLeft', 'ArrowLeft', 37, ALT)
  c = await cursor()
  check('Option+← 上一词', c.a !== '' && c.b.length < '甲方 乙方 丙方'.length, JSON.stringify(c))
  await key('ArrowLeft', 'ArrowLeft', 37, META)
  c = await cursor()
  check('Cmd+← 行首', c.b === '', JSON.stringify(c))
  await key('ArrowDown', 'ArrowDown', 40, META)
  c = await cursor()
  check('Cmd+↓ 文尾', c.a === '', JSON.stringify(c))

  console.log('== 8) Tab / Shift+Enter / Esc / PageUp/Down ==')
  await reset('条款')
  await key('Tab', 'Tab', 9)
  check('Tab 制表符', (await doc()).includes('\t'), JSON.stringify(await doc()))
  await reset('第一行')
  await key('Enter', 'Enter', 13, SHIFT)
  const t = await exec('get_document_text')
  check('Shift+Enter 软回车（不分段）', t.totalParagraphs === 1 && t.paragraphs[0].text.includes('\n'), JSON.stringify(t.paragraphs))
  await reset('选区文本')
  await key('a', 'KeyA', 65, META)
  await key('Escape', 'Escape', 27)
  check('Esc 取消选区', !(await exec('get_selection')).hasSelection)
  const pu = await exec('ui_command', { name: 'page_up' })
  const pd = await exec('ui_command', { name: 'page_down' })
  check('PageUp/PageDown 命令可用', pu.success && pd.success)

  console.log('== 9) get_clauses 条款识别（条款≠段落，一条横跨多段） ==')
  await reset('技术服务合同\n第一条 服务内容\n乙方提供服务。\n具体范围双方另行约定。\n第二条 服务费用\n费用总计人民币10万元。\n第三条 违约责任\n任何一方违约应赔偿。', false)
  const cl = await exec('get_clauses')
  check('识别出 3 条条款（8 个段落）', cl.success && cl.clauseCount === 3 && cl.totalParagraphs === 8, JSON.stringify(cl))
  check('第一条横跨 3 段', cl.clauses && cl.clauses[0] && cl.clauses[0].no === '第一条' && cl.clauses[0].paragraphCount === 3,
    JSON.stringify(cl.clauses && cl.clauses[0]))
  check('首部段落不计入条款', cl.preambleParagraphs === 1, JSON.stringify(cl.preambleParagraphs))

  console.log('\n结果 / result: ' + passed + ' passed, ' + failed + ' failed')
} finally {
  await browser.close()
  server.close()
}
process.exit(failed ? 1 : 0)

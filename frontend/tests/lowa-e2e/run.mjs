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
  debug_list_comments() {
    const out = [];
    const en = xModel.getTextFields().createEnumeration();
    while (en.hasMoreElements()) {
      const f = en.nextElement();
      if (f.supportsService && f.supportsService('com.sun.star.text.textfield.Annotation')) {
        const item = {};
        try { item.author = f.getPropertyValue('Author'); } catch (e) {}
        try { item.content = f.getPropertyValue('Content'); } catch (e) {}
        out.push(item);
      }
    }
    return { success: true, count: out.length, comments: out };
  },
  debug_fresh_document() {
    // 组 13 探针专用：跳过前面各组累积的残留（批注字段等会让 select_all +
    // replace_selection 在其上抛 RuntimeException），换一份全新空白文档再准备
    // 新旧版本文本，和既有 probe_modules() 用同一条 private:factory 路径。
    try {
      const loaded = desktop.loadComponentFromURL('private:factory/swriter', '_blank', 0, [mkProp('Hidden', true)]);
      if (!loaded) return { success: false, message: 'loadComponentFromURL returned null' };
      xModel = loaded;
      ctrl = loaded.getCurrentController();
      try { xModel.setPropertyValue('RecordChanges', false); } catch (e) {}
      return { success: true };
    } catch (e) { return { success: false, message: errStr(e) }; }
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
      s.replace("'get_hyperlink_at_cursor'", "'get_hyperlink_at_cursor','debug_set_record_changes','debug_char_prop','debug_list_comments','debug_fresh_document'")
        .replace('"get_hyperlink_at_cursor"', '"get_hyperlink_at_cursor","debug_set_record_changes","debug_char_prop","debug_list_comments","debug_fresh_document"'),
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

  // NOTE: ShowChangesInMargin (tdf#34355) is OFF again — the engine paints
  // margin deletions over the neighboring TABLE CELL's content (法律文书大量
  // 用表格，重叠不可读), so deletions are back to inline strikethrough. The
  // struck-through originals therefore stay in the cursor context and the
  // cursor steps OVER them.
  console.log('== 1) Backspace over pre-existing text (revision-mode jam regression #164) ==')
  await reset('合同条款abc') // inserted with rc OFF -> "original" text; rc back ON
  for (let i = 0; i < 3; i++) await key('Backspace', 'Backspace', 8)
  let c = await cursor()
  check('3×Backspace 光标逐字越过原文（划线留正文）', c.b === '合同条款' && c.a === 'abc', JSON.stringify(c))

  console.log('== 2) Delete key forward-deletes ==')
  await reset('合同条款abc')
  for (let i = 0; i < 3; i++) await key('ArrowLeft', 'ArrowLeft', 37)
  for (let i = 0; i < 2; i++) await key('Delete', 'Delete', 46)
  c = await cursor()
  check('2×Delete 前删越过原文（划线留正文）', c.b === '合同条款ab' && c.a === 'c', JSON.stringify(c))

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

  console.log('== 10) 修订作者署名（AI Workdeck vs 用户名） ==')
  await reset('署名测试。', true) // rc ON：以下插入都会落成修订
  // AI 命令：宿主 handleEditorCommand 会打 __agent 标记 → 署名 AI Workdeck
  await exec('insert_at_cursor', { text: 'AI改动', __agent: true })
  // 用户操作（IME 提交等）不带标记 → 署当前用户名。load_document 空字节调用
  // 只用来注入 authorName（与宿主 loadDocument 的传参路径一致）。
  await exec('load_document', { authorName: '测试用户' })
  await exec('insert_at_cursor', { text: '用户改动' })
  const rv = await exec('debug_revisions')
  const authors = (rv.redlines || []).map((r) => r.author)
  check('AI 修订署名 AI Workdeck', rv.success && authors.includes('AI Workdeck'), JSON.stringify(rv))
  check('用户修订署用户名', authors.includes('测试用户'), JSON.stringify(authors))

  console.log('== 11) 修订颗粒度：一字之差只标一字，不整段删增 ==')
  // 口径说明：按 author 过滤只看本场景的修订（场景 10 已把作者设为"测试用户"），
  // 防前面场景跨 reset 残留的删除记录混入。行内显示（ShowChangesInMargin=false）
  // 下删除文本留在正文流：正文断言按「插入在前、划删原文在后」的行内形态核对，
  // 这个形态本身就证明了颗粒度（整段删增会让整句成对出现）。
  const mine = (rv2) => (rv2.redlines || []).filter((r) => r.author === '测试用户')
  const delTexts = (rv2) => mine(rv2).filter((r) => r.type === 'Delete').map((r) => r.text)
  await reset('我爱你', true)
  await exec('find_replace', { findText: '我爱你', replaceText: '我恨你', replaceAll: true })
  let rv11 = await exec('debug_revisions')
  check('find_replace 我爱你→我恨你 只删"爱"（非整句）',
    rv11.success && delTexts(rv11).includes('爱') && delTexts(rv11).every((t2) => (t2 || '').length === 1), JSON.stringify(rv11))
  check('插入也只落一处修订', mine(rv11).filter((r) => r.type === 'Insert').length === 1, JSON.stringify(mine(rv11)))
  check('正文呈现插删并存（恨插入、爱划删留正文）', (await doc()) === '我恨爱你', await doc())
  await reset('甲方应于三十日内向乙方支付服务费。', true)
  await exec('modify_paragraph', { index: 0, newText: '甲方应于六十日内向乙方支付全部服务费。' })
  rv11 = await exec('debug_revisions')
  check('modify_paragraph 散点小改只删"三"（非整段重写）',
    rv11.success && delTexts(rv11).includes('三') && delTexts(rv11).every((t2) => (t2 || '').length === 1), JSON.stringify(rv11))
  check('两处插入各自成修订（六 / 全部）', mine(rv11).filter((r) => r.type === 'Insert').length === 2, JSON.stringify(mine(rv11)))
  check('改后段落实文正确（划删"三"留正文）', (await doc()) === '甲方应于六三十日内向乙方支付全部服务费。', await doc())

  console.log('== 12) add_comment 批注：解释文字挂批注、不进正文 ==')
  await reset('本合同自签署之日起生效。', true)
  const ft = await exec('find_text_locations', { keyword: '签署之日' })
  check('find_text 拿到锚点', ft.success && ft.count === 1 && !!ft.matches[0].anchorId, JSON.stringify(ft))
  const cm = await exec('add_comment', { anchor: ft.matches[0].anchorId, comment: '建议明确签署日期的认定方式', __agent: true })
  check('add_comment 成功且附着目标文本', cm.success && cm.annotatedText === '签署之日', JSON.stringify(cm))
  const lc = await exec('debug_list_comments')
  check('批注可读回、署名 AI Workdeck、内容完整',
    lc.success && lc.count === 1 && lc.comments[0].author === 'AI Workdeck' && lc.comments[0].content === '建议明确签署日期的认定方式',
    JSON.stringify(lc))
  check('批注文字不进正文', await doc() === '本合同自签署之日起生效。', await doc())
  check('缺锚点被拒绝', !(await exec('add_comment', { comment: '孤儿批注' })).success)

  // ---------- 组 13：compare_document 生产 action（版本记录第 2 期）----------
  console.log('\n[13] compare_document 生产 action')
  {
    // 组 12 在文档里留了一处批注（字段）；select_all 选区跨过该字段时
    // replace_selection 的 vc.setString('') 会抛 RuntimeException（与
    // CompareDocuments 本身无关）。测试不依赖前面各组的残留状态，换一份全新
    // 空白文档来准备新旧版本文本，和既有 probe_modules() 同一手法。
    const fresh = await exec('debug_fresh_document')
    check('换新文档成功', fresh && fresh.success === true, JSON.stringify(fresh))

    const setText = async (t) => {
      await exec('debug_set_record_changes', { on: false })
      await exec('ui_command', { name: 'select_all' })
      await exec('replace_selection', { text: t })
    }
    // page.evaluate()'s return-value serialization (CDP, effectively JSON) turns
    // a Uint8Array into a keyless/lengthless plain object — export_document's
    // `bytes` silently arrives empty in Node. Convert to a plain Array INSIDE
    // the page before it crosses that boundary so it survives round-trip.
    const exportBytes = () => page.evaluate(async () => {
      const r = await window.__loExecutor.executeCommand('export_document', {})
      return r && r.bytes ? Array.from(r.bytes) : null
    })

    await setText('甲方应于三十日内支付合同价款。')
    const oldBytes = await exportBytes()

    await setText('甲方应于六十日内支付合同价款。')
    const newBytes = await exportBytes()

    check('导出两版字节非空', !!oldBytes && !!newBytes && oldBytes.length > 0 && newBytes.length > 0)

    // 当前文档载入"新版本"，再与"旧版本"比较
    await exec('load_document', { bytes: newBytes, name: 'v2.docx', authorName: '测试用户' })
    const cmp = await exec('compare_document', { baseBytes: oldBytes })
    check('compare_document 成功且产出修订', cmp && cmp.success === true && cmp.redlineCount > 0,
      JSON.stringify(cmp))

    const rev = await exec('debug_revisions')
    const cmpRedlines = (rev.redlines || []).filter((r) => r.author === '版本对比')
    check('修订署名统一为"版本对比"', rev && rev.success === true && cmpRedlines.length === rev.count, JSON.stringify(rev))
    // 方向断言：探针（第 0 期）已实证产出「旧→新」的删/插修订——删除侧应含"三"
    check('方向正确：redlines 含 Delete「三」',
      cmpRedlines.filter((r) => r.type === 'Delete').map((r) => r.text).includes('三'), JSON.stringify(cmpRedlines))
    check('正文停在新版+行内划删原文', (await doc()) === '甲方应于六三十日内支付合同价款。', await doc())
  }

  console.log('\n结果 / result: ' + passed + ' passed, ' + failed + ' failed')
} finally {
  await browser.close()
  server.close()
}
process.exit(failed ? 1 : 0)

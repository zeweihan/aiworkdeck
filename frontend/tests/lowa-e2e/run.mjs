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
  debug_table_info(p) {
    // 组 14/15 探针：读第 N 张表的结构与标准格式落点（边框宽/表头加粗居中/数字
    // 居右/垂直居中）。枚举值比较在 worker 侧做完、返回布尔，避免跨界序列化歧义；
    // 比较走 enumEq（LO 对枚举型属性可能读回裸 short）。
    const ts = xModel.getTextTables();
    if (!ts.getCount()) return { success: false, message: 'no tables' };
    const t = ts.getByIndex(Number(p && p.index) || 0);
    const out = { success: true, count: ts.getCount() };
    try { out.rows = t.getRows().getCount(); out.cols = t.getColumns().getCount(); } catch (e) {}
    try { out.borderWidth = t.getPropertyValue('TableBorder2').TopLine.LineWidth; } catch (e) { out.borderErr = errStr(e); }
    try {
      const a1 = t.getCellByName('A1');
      out.a1Text = a1.getString();
      const c1 = a1.createTextCursor(); c1.gotoStart(false); c1.gotoEnd(true);
      out.a1Bold = c1.getPropertyValue('CharWeight') > 100;
      out.a1SizePt = c1.getPropertyValue('CharHeight');
      out.a1Centered = enumEq(c1.getPropertyValue('ParaAdjust'), css.style.ParagraphAdjust.CENTER);
      out.a1VCenter = enumEq(a1.getPropertyValue('VertOrient'), css.text.VertOrientation.CENTER);
    } catch (e) { out.a1Err = errStr(e); }
    try {
      const b2 = t.getCellByName('B2');
      out.b2Text = b2.getString();
      const c2 = b2.createTextCursor(); c2.gotoStart(false); c2.gotoEnd(true);
      out.b2AlignRight = enumEq(c2.getPropertyValue('ParaAdjust'), css.style.ParagraphAdjust.RIGHT);
    } catch (e) { out.b2Err = errStr(e); }
    return out;
  },
  debug_fresh_calc() {
    // 组 16 探针：换一份全新空白 Calc 文档（sheet_* 原语在真 Calc 模型上验证），
    // 与 debug_fresh_document 同一条 private:factory 路径。
    try {
      const loaded = desktop.loadComponentFromURL('private:factory/scalc', '_blank', 0, [mkProp('Hidden', true)]);
      if (!loaded) return { success: false, message: 'loadComponentFromURL returned null' };
      xModel = loaded;
      ctrl = loaded.getCurrentController();
      return { success: true };
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
  debug_sheet_cell_info(p) {
    // 组 16 探针：读单元格格式落点。枚举比较在 worker 侧做完返回布尔（enumEq，
    // LO 对枚举属性可能读回裸 short）；VertJustify 是 long 常量组直接比数值。
    try {
      const sheet = ctrl.getActiveSheet();
      const range = sheet.getCellRangeByName(String(p.cell || 'A1'));
      const cell = range.getCellByPosition(0, 0);
      const out = { success: true, text: cell.getString(), value: cell.getValue() };
      try { out.bold = cell.getPropertyValue('CharWeight') > 100; } catch (e) {}
      try { out.sizePt = cell.getPropertyValue('CharHeight'); } catch (e) {}
      try { out.hCenter = enumEq(cell.getPropertyValue('HoriJustify'), css.table.CellHoriJustify.CENTER); } catch (e) {}
      try { out.hRight = enumEq(cell.getPropertyValue('HoriJustify'), css.table.CellHoriJustify.RIGHT); } catch (e) {}
      try { out.vCenter = unoEnumVal(cell.getPropertyValue('VertJustify')) === 2; } catch (e) {}
      try { out.bg = cell.getPropertyValue('CellBackColor'); } catch (e) {}
      try {
        const key = cell.getPropertyValue('NumberFormat');
        out.numberFormat = xModel.getNumberFormats().getByKey(key).getPropertyValue('FormatString');
      } catch (e) {}
      try { out.error = cell.getError(); } catch (e) {}
      try { out.formula = cell.getFormula(); } catch (e) {}
      try { out.borderTopWidth = cell.getPropertyValue('TopBorder2').LineWidth; } catch (e) {}
      try { out.rowHeightMm = range.getRows().getByIndex(0).getPropertyValue('Height'); } catch (e) {}
      try { out.colWidthMm = range.getColumns().getByIndex(0).getPropertyValue('Width'); } catch (e) {}
      return out;
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
      s.replace("'get_hyperlink_at_cursor'", "'get_hyperlink_at_cursor','debug_set_record_changes','debug_char_prop','debug_list_comments','debug_fresh_document','debug_table_info','debug_fresh_calc','debug_sheet_cell_info'")
        .replace('"get_hyperlink_at_cursor"', '"get_hyperlink_at_cursor","debug_set_record_changes","debug_char_prop","debug_list_comments","debug_fresh_document","debug_table_info","debug_fresh_calc","debug_sheet_cell_info"'),
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

  // ---------- 组 14：富格式原语（行距/段距/缩进/编号/建表/格式读取）----------
  console.log('\n[14] 富格式原语：段落格式 / 编号 / 插表 / 格式读取')
  {
    await exec('debug_fresh_document')
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '本段用于段落格式测试。' })
    await exec('select_paragraph', { index: 0 })
    // 先定字号：firstLineIndentChars 按光标处字号折算（2 字符 × 12 磅 = 24 磅）
    await exec('format_selection', { fontSize: 12 })
    const pf = await exec('set_paragraph_format', {
      alignment: 'justify', lineSpacingMode: 'atLeast', lineSpacingValue: 16,
      spaceBeforePt: 0, spaceAfterPt: 18, firstLineIndentChars: 2,
    })
    check('set_paragraph_format 扩展参数成功', pf.success === true, JSON.stringify(pf))
    let fm = await exec('get_formatting')
    check('读回：两端对齐', fm.success && fm.paragraph.alignment === 'justify', JSON.stringify(fm.paragraph))
    check('读回：行距最小值 16 磅', fm.paragraph.lineSpacing && fm.paragraph.lineSpacing.mode === 'atLeast' && Math.abs(fm.paragraph.lineSpacing.valuePt - 16) < 0.2, JSON.stringify(fm.paragraph.lineSpacing))
    check('读回：段前 0 段后 18 磅', Math.abs(fm.paragraph.spaceBeforePt) < 0.2 && Math.abs(fm.paragraph.spaceAfterPt - 18) < 0.2, JSON.stringify(fm.paragraph))
    check('读回：首行缩进 2 字符（≈24 磅）', Math.abs(fm.paragraph.firstLineIndentPt - 24) < 1, JSON.stringify(fm.paragraph.firstLineIndentPt))

    const num = await exec('set_numbering', { preset: 'decimal', level: 1 })
    check('set_numbering decimal 成功', num.success === true, JSON.stringify(num))
    fm = await exec('get_formatting')
    check('读回：段落带编号', fm.paragraph.isNumbered === true, JSON.stringify(fm.paragraph))
    const numOff = await exec('set_numbering', { preset: 'none' })
    fm = await exec('get_formatting')
    check('set_numbering none 去编号', numOff.success === true && fm.paragraph.isNumbered !== true, JSON.stringify(fm.paragraph))

    await exec('goto', { type: 'end' })
    const it = await exec('insert_table', { rows: [['项目', '金额'], ['咨询费', '10000']], headerRow: true })
    check('insert_table 成功', it.success === true, JSON.stringify(it))
    const ti = await exec('debug_table_info', {})
    check('表结构 2×2', ti.success && ti.rows === 2 && ti.cols === 2, JSON.stringify(ti))
    check('Grid 边框 1.5 磅（53/100mm）', Math.abs((ti.borderWidth || 0) - 53) <= 1, JSON.stringify(ti.borderWidth))
    check('表头加粗居中 + 垂直居中 + 10 号', ti.a1Bold === true && ti.a1Centered === true && ti.a1VCenter === true && Math.abs(ti.a1SizePt - 10) < 0.2, JSON.stringify(ti))
    check('数字单元格居右', ti.b2Text === '10000' && ti.b2AlignRight === true, JSON.stringify(ti))
    const ft14 = await exec('format_table', { fontSizePt: 9, cellVerticalAlign: 'center' })
    check('format_table 光标外要求 tableIndex', ft14.success === false, JSON.stringify(ft14))
    const ft14b = await exec('format_table', { tableIndex: 0, fontSizePt: 9 })
    check('format_table 按 tableIndex 改字号', ft14b.success === true, JSON.stringify(ft14b))
  }

  // ---------- 组 15：流式写入去 markdown 化（标准格式落字）----------
  console.log('\n[15] 流式写入：markdown 剥离 + 标准格式')
  {
    await exec('debug_fresh_document')
    // 模拟 SSE token 到达：故意在行中、标记中、表格分隔行中间切块
    await exec('stream_insert', { text: '# 合同审' })
    await exec('stream_insert', { text: '查报告\n\n本报告**重' })
    await exec('stream_insert', { text: '点**关注下列问题：\n\n| 项目 | 金额 |\n| --- | ---' })
    await exec('stream_insert', { text: ' |\n| 咨询费 | 10000 |\n结论：整体**通过**。' })
    const sf = await exec('stream_flush', {})
    check('stream_flush 成功', sf.success === true, JSON.stringify(sf))
    const t15 = await exec('get_document_text')
    const paras15 = t15.paragraphs.map((x) => x.text)
    const text15 = paras15.join('\n')
    check('正文无 markdown 标记（#/**/|）', paras15.every((s) => !/[#|]|\*\*/.test(s)), JSON.stringify(paras15))
    check('标题与正文文本落地', text15.includes('合同审查报告') && text15.includes('本报告重点关注下列问题：') && text15.includes('结论：整体通过。'), JSON.stringify(paras15))
    // 尾段是写入器留下的光标停靠空段（Word 文档天然以段落标记收尾），中间不允许空段
    check('无额外空行段落', paras15.slice(0, -1).every((s) => s.trim() !== ''), JSON.stringify(paras15))
    const ti = await exec('debug_table_info', {})
    check('markdown 表转真表（2×2、Grid 1.5 磅）', ti.success && ti.rows === 2 && ti.cols === 2 && Math.abs((ti.borderWidth || 0) - 53) <= 1, JSON.stringify(ti))
    check('表头「项目」加粗居中', ti.a1Text === '项目' && ti.a1Bold === true && ti.a1Centered === true, JSON.stringify(ti))
    check('数字「10000」居右', ti.b2Text === '10000' && ti.b2AlignRight === true, JSON.stringify(ti))
    // 主标题：16 磅加粗居中；正文：12 磅两端对齐、首行缩进 2 字符、段后 18 磅
    await exec('select_paragraph', { index: 0 })
    let fm = await exec('get_formatting')
    check('主标题 16 磅加粗居中', fm.character.bold === true && Math.abs(fm.character.sizePt - 16) < 0.2 && fm.paragraph.alignment === 'center', JSON.stringify({ c: fm.character, p: fm.paragraph.alignment }))
    check('标准字体（楷体_GB2312 / Arial）', fm.character.fontAsian === '楷体_GB2312' && fm.character.fontWestern === 'Arial', JSON.stringify(fm.character))
    await exec('select_paragraph', { index: 1 })
    fm = await exec('get_formatting')
    check('正文 12 磅两端对齐缩进 2 字符段后 18 磅',
      Math.abs(fm.character.sizePt - 12) < 0.2 && fm.paragraph.alignment === 'justify'
      && Math.abs(fm.paragraph.firstLineIndentPt - 24) < 1 && Math.abs(fm.paragraph.spaceAfterPt - 18) < 0.2,
      JSON.stringify({ c: fm.character.sizePt, p: fm.paragraph }))
    // 表格后首段段前 18 磅（规范：其余段落段前 0）
    await exec('select_paragraph', { index: 2 })
    fm = await exec('get_formatting')
    check('表后首段段前 18 磅', Math.abs(fm.paragraph.spaceBeforePt - 18) < 0.2, JSON.stringify(fm.paragraph.spaceBeforePt))
    // 行内加粗落成真格式：选中「重点」应为粗体
    const ftx = await exec('find_text_locations', { keyword: '重点' })
    if (ftx.success && ftx.count > 0) {
      await exec('set_selection', { anchor: ftx.matches[0].anchorId })
      const w15 = await exec('debug_char_prop', { prop: 'CharWeight' })
      check('**重点** 转真粗体', w15.value === 150, JSON.stringify(w15.value))
    } else {
      check('**重点** 转真粗体', false, 'find_text_locations 未命中: ' + JSON.stringify(ftx))
    }
    // 二次流式（往非空文档续写）：# 不再当主标题，而是小标题（正文款加粗）
    await exec('stream_insert', { text: '# 补充说明\n补充正文。\n' })
    await exec('stream_flush', {})
    const ft2 = await exec('find_text_locations', { keyword: '补充说明' })
    check('续写场景 # 落为小标题文本', ft2.success && ft2.count === 1, JSON.stringify(ft2.count))
    if (ft2.success && ft2.count > 0) {
      await exec('set_selection', { anchor: ft2.matches[0].anchorId })
      const fm2 = await exec('get_formatting')
      check('小标题=正文字号但加粗', fm2.character.bold === true && Math.abs(fm2.character.sizePt - 12) < 0.2, JSON.stringify(fm2.character))
    }
  }

  // ---------- 组 16：Calc 电子表格原语（sheet_*）----------
  console.log('\n[16] Calc sheet_* 原语：读写 / 选区 / 格式 / 边框 / 行高列宽')
  {
    // 文档类型守卫：Writer 文档上 sheet_* 应报"不是电子表格"，而不是抛 UNO 异常
    const guard = await exec('sheet_get_overview')
    check('Writer 文档上 sheet_* 被明确拒绝', guard.success === false && /电子表格/.test(guard.message || ''), JSON.stringify(guard))

    const fresh = await exec('debug_fresh_calc')
    check('换新 Calc 文档成功', fresh && fresh.success === true, JSON.stringify(fresh))

    const ov = await exec('sheet_get_overview')
    check('sheet_get_overview 列出工作表', ov.success === true && ov.sheetCount >= 1 && !!ov.activeSheet, JSON.stringify(ov))

    const wr = await exec('sheet_write_cells', {
      startCell: 'A1',
      rows: [['项目', '金额'], ['咨询费', 10000], ['律师费', '2500.5'], ['合计', '=SUM(B2:B3)'], ['编号', '001']],
    })
    check('sheet_write_cells 写入 5×2', wr.success === true && wr.range === 'A1:B5' && wr.cellsWritten === 10, JSON.stringify(wr))
    check('写入验证回路返回首行', Array.isArray(wr.firstRowAfterWrite) && wr.firstRowAfterWrite[0] === '项目', JSON.stringify(wr.firstRowAfterWrite))

    const rd = await exec('sheet_read_range', { range: 'A1:B5' })
    check('读回：文本/数值/数字串转数值/公式结果', rd.success === true
      && rd.rows[0][0] === '项目' && rd.rows[1][1] === 10000 && rd.rows[2][1] === 2500.5 && rd.rows[3][1] === 12500.5,
      JSON.stringify(rd.rows))
    check('前导 0 编号保持文本', rd.rows[4][1] === '001', JSON.stringify(rd.rows[4]))
    check('公式串可见', (rd.formulas || []).some((f) => f.cell === 'B4' && /SUM/i.test(f.formula)), JSON.stringify(rd.formulas))

    const rdAll = await exec('sheet_read_range', {})
    check('缺省读已用区域', rdAll.success === true && rdAll.range === 'A1:B5', JSON.stringify(rdAll.range))

    const sel = await exec('sheet_select_range', { range: 'A1:B5' })
    check('sheet_select_range 成功', sel.success === true && sel.range === 'A1:B5' && sel.topLeftText === '项目', JSON.stringify(sel))

    const fc = await exec('sheet_format_cells', { range: 'A1:B1', bold: true, fontSize: 12, hAlign: 'center', vAlign: 'center', background: '#EEEEEE' })
    check('表头格式设置成功', fc.success === true, JSON.stringify(fc))
    const nf = await exec('sheet_format_cells', { range: 'B2:B4', numberFormat: '#,##0.00', hAlign: 'right' })
    check('数字格式+右对齐设置成功', nf.success === true, JSON.stringify(nf))
    let ci = await exec('debug_sheet_cell_info', { cell: 'A1' })
    check('A1 加粗/水平垂直居中/12 号/底色', ci.bold === true && ci.hCenter === true && ci.vCenter === true
      && Math.abs(ci.sizePt - 12) < 0.2 && ci.bg === 0xEEEEEE, JSON.stringify(ci))
    ci = await exec('debug_sheet_cell_info', { cell: 'B2' })
    check('B2 数字格式 #,##0.00 且右对齐', ci.numberFormat === '#,##0.00' && ci.hRight === true, JSON.stringify(ci))

    const bd = await exec('sheet_set_borders', { range: 'A1:B5', preset: 'all', widthPt: 1.5 })
    check('边框设置成功', bd.success === true, JSON.stringify(bd))
    ci = await exec('debug_sheet_cell_info', { cell: 'A1' })
    check('A1 上边框 1.5 磅（53/100mm）', Math.abs((ci.borderTopWidth || 0) - 53) <= 1, JSON.stringify(ci.borderTopWidth))

    const rc = await exec('sheet_set_row_col', { range: 'A1:B1', rowHeightPt: 24, colWidthPt: 90 })
    check('行高列宽设置成功', rc.success === true, JSON.stringify(rc))
    ci = await exec('debug_sheet_cell_info', { cell: 'A1' })
    check('行高 24 磅（≈847/100mm）', Math.abs((ci.rowHeightMm || 0) - 847) <= 5, JSON.stringify(ci.rowHeightMm))
    check('列宽 90 磅（≈3175/100mm）', Math.abs((ci.colWidthMm || 0) - 3175) <= 10, JSON.stringify(ci.colWidthMm))

    const bad = await exec('sheet_read_range', { range: 'not-a-range' })
    check('非法区域被拒绝', bad.success === false, JSON.stringify(bad))
    const badSheet = await exec('sheet_read_range', { sheet: '不存在的表' })
    check('不存在的工作表被拒绝', badSheet.success === false && /工作表不存在/.test(badSheet.message || ''), JSON.stringify(badSheet))
  }

  // ---------- 组 17：Calc 常用公式抽查（按使用频率排序）----------
  console.log('\n[17] Calc 公式抽查：高频函数 / 中文参数 / 分隔符与跨表写法')
  {
    await exec('debug_fresh_calc')
    const ov17 = await exec('sheet_get_overview')
    const SHEET = ov17.activeSheet
    // 数据区 A1:C5
    await exec('sheet_write_cells', {
      startCell: 'A1',
      rows: [
        ['项目', '金额', '类别'],
        ['咨询费', 10000, '服务'],
        ['律师费', 2500.5, '服务'],
        ['差旅费', 800, '报销'],
        ['印花税', 120, '税费'],
      ],
    })
    // 公式区 E1:E22 —— 按常用频率排序的抽查清单（Excel 习惯写法：逗号分隔）
    const FORMULAS = [
      ['SUM', '=SUM(B2:B5)', 13420.5],
      ['AVERAGE', '=AVERAGE(B2:B5)', 3355.125],
      ['IF+中文+逗号', '=IF(B2>5000,"高","低")', '高'],
      ['COUNT', '=COUNT(B2:B5)', 4],
      ['COUNTA', '=COUNTA(A2:A5)', 4],
      ['VLOOKUP+中文键', '=VLOOKUP("律师费",A2:B5,2,0)', 2500.5],
      ['SUMIF+中文条件', '=SUMIF(C2:C5,"服务",B2:B5)', 12500.5],
      ['COUNTIF+中文条件', '=COUNTIF(C2:C5,"服务")', 2],
      ['MAX', '=MAX(B2:B5)', 10000],
      ['MIN', '=MIN(B2:B5)', 120],
      ['ROUND', '=ROUND(3.14159,2)', 3.14],
      ['IFERROR', '=IFERROR(VLOOKUP("不存在",A2:B5,2,0),"未找到")', '未找到'],
      ['INDEX+MATCH', '=INDEX(A2:A5,MATCH(120,B2:B5,0))', '印花税'],
      ['TEXT 数字格式', '=TEXT(B2,"#,##0.00")', '10,000.00'],
      ['& 拼接', '="共"&COUNT(B2:B5)&"项"', '共4项'],
      ['CONCATENATE', '=CONCATENATE(A2,"-",C2)', '咨询费-服务'],
      ['LEFT 取中文', '=LEFT(A2,2)', '咨询'],
      ['LEN 中文计数', '=LEN(A2)', 3],
      ['DATE/YEAR', '=YEAR(DATE(2026,8,1))', 2026],
      ['TODAY 比较', '=IF(TODAY()>DATE(2026,1,1),"ok","bad")', 'ok'],
      ['SUMPRODUCT', '=SUMPRODUCT(B2:B3,B2:B3)', 106252500.25],
      ['TEXTJOIN', '=TEXTJOIN("、",1,A2:A4)', '咨询费、律师费、差旅费'],
    ]
    const wr17 = await exec('sheet_write_cells', { startCell: 'E1', rows: FORMULAS.map((f) => [f[1]]) })
    check('公式批量写入成功', wr17.success === true && wr17.cellsWritten === FORMULAS.length, JSON.stringify(wr17))
    const rd17 = await exec('sheet_read_range', { range: 'E1:E' + FORMULAS.length })
    for (let i = 0; i < FORMULAS.length; i++) {
      const got = rd17.rows[i][0]
      const wantVal = FORMULAS[i][2]
      const ok = typeof wantVal === 'number' ? Math.abs(Number(got) - wantVal) < 1e-9 : got === wantVal
      check('公式 ' + FORMULAS[i][0] + ' = ' + JSON.stringify(wantVal), ok, FORMULAS[i][1] + ' -> ' + JSON.stringify(got))
    }
    // 公式方言归一契约（真机实证：API 文法要分号与 Sheet.A1；worker 把 Excel
    // 习惯写法归一化，字符串字面量内不动）
    const DIALECT = [
      ['分号写法原样可用', '=IF(B2>5000;"高";"低")', '高'],
      ['跨表 Excel 感叹号归一为点号', '=' + SHEET + '!B2', 10000],
      ['跨表 Calc 点号原样可用', '=' + SHEET + '.B2', 10000],
      ['字符串内逗号不受归一影响', '=IF(B2>5000,"高,优","低")', '高,优'],
      ['字符串内中文标点不受归一影响', '=SUBSTITUTE("甲、乙","、","/")', '甲/乙'],
    ]
    const wrD = await exec('sheet_write_cells', { startCell: 'G1', rows: DIALECT.map((f) => [f[1]]) })
    check('方言公式写入无报错', wrD.success === true && !wrD.formulaErrors, JSON.stringify(wrD))
    const rdD = await exec('sheet_read_range', { range: 'G1:G' + DIALECT.length })
    for (let i = 0; i < DIALECT.length; i++) {
      const got = rdD.rows[i][0]
      const want = DIALECT[i][2]
      const ok = typeof want === 'number' ? Math.abs(Number(got) - want) < 1e-9 : got === want
      check(DIALECT[i][0], ok, DIALECT[i][1] + ' -> ' + JSON.stringify(got))
    }
    // 不支持的新函数（引擎 LO 24.2 无 XLOOKUP）：写入结果必须把错误报给 AI 自纠
    const wrX = await exec('sheet_write_cells', { startCell: 'H1', rows: [['=XLOOKUP("律师费",A2:A5,B2:B5)']] })
    check('XLOOKUP 出错被上报（formulaErrors+note）',
      wrX.success === true && Array.isArray(wrX.formulaErrors) && wrX.formulaErrors[0].cell === 'H1' && /XLOOKUP/i.test(wrX.note || ''),
      JSON.stringify(wrX))
    const rdX = await exec('sheet_read_range', { range: 'H1' })
    check('出错公式读回不伪装成 0', rdX.rows[0][0] !== 0 && rdX.rows[0][0] !== '' && String(rdX.rows[0][0]).length > 0, JSON.stringify(rdX.rows))
  }

  console.log('\n结果 / result: ' + passed + ' passed, ' + failed + ' failed')
} finally {
  await browser.close()
  server.close()
}
process.exit(failed ? 1 : 0)

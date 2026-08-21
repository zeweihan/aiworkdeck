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

import fs from 'node:fs'
import path from 'node:path'
// server / puppeteer 启动件与 big-doc.mjs（大文档基线组）共用，抽在 _boot.mjs。
import { here, preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

// ---------- preflight ----------
preflight()
const puppeteer = await loadPuppeteer()

// ---------- test-only worker actions, injected in-memory ----------
const DEBUG_ACTIONS = `
  // 组 28：锁是否平衡（取消路径解锁两次不能下溢）+ modified 是否仍会触发
  debug_lock_state() {
    // isActionLocked 在 zetajs 包装上不可调（XActionLockable 没暴露），只看控制器锁 + 计数。
    // inflightKeys 含本探针自己（=1 即无残留）。
    let ctl = null;
    try { ctl = xModel.hasControllersLocked(); } catch (e) { ctl = 'err:' + e; }
    return { success: true, controllersLocked: ctl, modifySuspended: modifySuspended, lockDepth: modelLockDepth, cancelledKeys: Object.keys(CANCELLED).length, inflightKeys: Object.keys(INFLIGHT).length };
  },
  debug_modified_count() { return { success: true, count: MOD_COUNT }; },
  // 组 29 探针：页脚文本与页码域计数（PageNumber / PageCount 文本域各几枚）
  debug_footer_info() {
    const out = { success: true, text: '', pageNumberFields: 0, pageCountFields: 0 };
    try {
      const ps = currentPageStyle();
      if (ps.error) return ps;
      out.text = String(ps.pageStyle.getPropertyValue('FooterText').getString() || '');
      const en = xModel.getTextFields().createEnumeration();
      while (en.hasMoreElements()) {
        const f = en.nextElement();
        if (f.supportsService && f.supportsService('com.sun.star.text.textfield.PageNumber')) out.pageNumberFields++;
        if (f.supportsService && f.supportsService('com.sun.star.text.textfield.PageCount')) out.pageCountFields++;
      }
    } catch (e) { out.err = errStr(e); }
    return out;
  },
  // 组 29 探针：段落样式定义的字号/粗细/对齐（apply_style_profile 改的是定义，不是某段）
  debug_para_style_info(p) {
    try {
      const st = xModel.getStyleFamilies().getByName('ParagraphStyles').getByName(String(p.name));
      return { success: true, sizePt: st.getPropertyValue('CharHeight'), bold: st.getPropertyValue('CharWeight') > 100,
        fontAsian: st.getPropertyValue('CharFontNameAsian'), fontWestern: st.getPropertyValue('CharFontName'),
        centered: enumEq(st.getPropertyValue('ParaAdjust'), css.style.ParagraphAdjust.CENTER),
        firstLineIndentMm: st.getPropertyValue('ParaFirstLineIndent') };
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
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
  debug_fresh_document(p) {
    // 组 13 探针专用：跳过前面各组累积的残留（批注字段等会让 select_all +
    // replace_selection 在其上抛 RuntimeException），换一份全新空白文档再准备
    // 新旧版本文本，和既有 probe_modules() 用同一条 private:factory 路径。
    try {
      // p.visible：批注删除要走引擎的注释窗口（.uno:DeleteComment 按 Id 找的是
      // 活动批注窗口），Hidden 文档里根本没有——组 18 因此要一份可见文档。
      const loaded = desktop.loadComponentFromURL('private:factory/swriter', '_blank', 0,
        (p && p.visible) ? [] : [mkProp('Hidden', true)]);
      if (!loaded) return { success: false, message: 'loadComponentFromURL returned null' };
      xModel = loaded;
      ctrl = loaded.getCurrentController();
      try { xModel.setPropertyValue('RecordChanges', false); } catch (e) {}
      // 生产的 retarget（load_document）会重置这个视图设置——探针换文档也要跟着
      // 做，否则后续断言跑在行内显示语义下，与真实产品形态不符。
      showDeletionsInMargin();
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
    // 组 29：内框线宽 / 外框线型与颜色 / 重复表头 / 表头底纹
    try { const tb = t.getPropertyValue('TableBorder2'); out.innerWidth = tb.HorizontalLine.LineWidth; out.borderStyle = unoEnumVal(tb.TopLine.LineStyle); out.borderColor = tb.TopLine.Color; } catch (e) {}
    try { out.repeatHeadline = !!t.getPropertyValue('RepeatHeadline'); } catch (e) {}
    try { out.a1Fill = t.getCellByName('A1').getPropertyValue('BackColor'); } catch (e) {}
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
  debug_fresh_calc(p) {
    // 组 16 探针：换一份全新空白 Calc 文档（sheet_* 原语在真 Calc 模型上验证），
    // 与 debug_fresh_document 同一条 private:factory 路径。
    // p.visible：冻结窗格是视图级操作（XViewFreezable），Hidden 文档没有真实
    // 视图导致 freezeAtPosition 静默无效——组 19 要一份可见文档。
    try {
      const loaded = desktop.loadComponentFromURL('private:factory/scalc', '_blank', 0,
        (p && p.visible) ? [] : [mkProp('Hidden', true)]);
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
      try { out.isMerged = !!range.getIsMerged(); } catch (e) {} // XMergeable 方法（'IsMerged' 属性不存在）
      return out;
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
  debug_sheet_doc_info() {
    // 组 19 探针：文档级状态——工作表名清单、自动筛选数据库区域、条件格式条数。
    try {
      const out = { success: true };
      const sheets = xModel.getSheets();
      const names = [];
      for (let i = 0; i < sheets.getCount(); i++) names.push(sheets.getByIndex(i).getName());
      out.sheets = names;
      try {
        const dbs = xModel.getPropertyValue('DatabaseRanges');
        const dbNames = (dbs.getElementNames && dbs.getElementNames()) || [];
        out.dbRanges = [];
        for (let i = 0; i < dbNames.length; i++) {
          let af = null;
          try { af = !!dbs.getByName(dbNames[i]).getPropertyValue('AutoFilter'); } catch (e) {}
          out.dbRanges.push({ name: dbNames[i], autoFilter: af });
        }
      } catch (e) { out.dbErr = errStr(e); }
      try { out.hasFrozenPanes = ctrl.hasFrozenPanes(); } catch (e) {}
      return out;
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
  debug_slide_shape_info(p) {
    // 组 23 探针：读一个形状的填充/边框/透明度属性，核实 slide_format_shape
    // 真正落到了引擎，不只信 setter 自己的回声；p.cell/{row,col} 给出时另读该
    // 表格形状对应单元格的上边框宽；p.colIndex 给出时另读该列宽——核实
    // slide_table_set_style 的 borders/columnWidths 分支是否真的在这个引擎
    // 构建上生效（本次实施调研没有查到 Impress 表格是否暴露这两组属性）。
    try {
      const pages = xModel.getDrawPages();
      const page = pages.getByIndex(Number(p && p.slideNumber != null ? p.slideNumber - 1 : 0));
      const n = page.getCount();
      let shape = null;
      for (let i = 0; i < n; i++) {
        const s = page.getByIndex(i);
        let name = ''; try { name = s.getName(); } catch (e) {}
        if (name === String(p && p.shapeName)) { shape = s; break; }
      }
      if (!shape) return { success: false, message: 'shape not found: ' + (p && p.shapeName) };
      const out = { success: true };
      try { out.fillColor = shape.getPropertyValue('FillColor'); } catch (e) {}
      try { out.fillStyle = unoEnumVal(shape.getPropertyValue('FillStyle')); } catch (e) {}
      try { out.lineColor = shape.getPropertyValue('LineColor'); } catch (e) {}
      try { out.lineStyle = unoEnumVal(shape.getPropertyValue('LineStyle')); } catch (e) {}
      try { out.lineWidth = shape.getPropertyValue('LineWidth'); } catch (e) {}
      try { out.fillTransparence = shape.getPropertyValue('FillTransparence'); } catch (e) {}
      try { out.onClick = unoEnumVal(shape.getPropertyValue('OnClick')); } catch (e) {}
      try { out.bookmark = shape.getPropertyValue('Bookmark'); } catch (e) {}
      if (p && p.cell) {
        try {
          const table = shape.getPropertyValue('Model');
          const cell = table.getCellByPosition(Number(p.cell.col) || 0, Number(p.cell.row) || 0);
          try { out.cellTopBorderWidth = cell.getPropertyValue('TopBorder').LineWidth; } catch (e) { out.cellBorderErr = errStr(e); }
        } catch (e) { out.tableErr = errStr(e); }
      }
      if (p && p.colIndex != null) {
        try {
          const table = shape.getPropertyValue('Model');
          out.colWidth = table.getColumns().getByIndex(Number(p.colIndex)).getPropertyValue('Width');
        } catch (e) { out.colWidthErr = errStr(e); }
      }
      return out;
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
  debug_slide_char_prop(p) {
    // 组 23 探针：读形状文字任意位置（offset 0 开始，缺省 0）的字符属性——核实
    // slide_format_text 的 anchorText 定位真的只影响了子串范围内的字符（范围外
    // 字符不受影响），以及 slide_set_hyperlink 的 HyperLinkURL 真的落在了命中
    // 文字的位置上。与 debug_char_prop（读当前视图光标处）不同，这个探针自己
    // 按 shapeName+offset 定位，不依赖任何前序调用留下的选区状态。
    try {
      const pages = xModel.getDrawPages();
      const page = pages.getByIndex(Number(p && p.slideNumber != null ? p.slideNumber - 1 : 0));
      const n = page.getCount();
      let shape = null;
      for (let i = 0; i < n; i++) {
        const s = page.getByIndex(i);
        let name = ''; try { name = s.getName(); } catch (e) {}
        if (name === String(p && p.shapeName)) { shape = s; break; }
      }
      if (!shape) return { success: false, message: 'shape not found: ' + (p && p.shapeName) };
      const xText = shape.getText();
      const cur = xText.createTextCursor();
      cur.gotoStart(false);
      const off = Number(p && p.offset) || 0;
      if (off > 0 && !cur.goRight(off, false)) return { success: false, message: 'goRight(' + off + ') failed' };
      if (!cur.goRight(1, true)) return { success: false, message: 'goRight(1, select) failed' };
      const out = { success: true, text: cur.getString() };
      try { out.value = cur.getPropertyValue(String(p.prop)); } catch (e) { out.err = errStr(e); }
      return out;
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
`
function patchServed(urlPath, content) {
  if (urlPath === '/office_thread.js') {
    const s = content.toString('utf8')
    if (!s.includes('const EXEC = {')) throw new Error('office_thread.js: EXEC anchor missing')
    for (const anchor of ['function installModifyListener(model) {', "if (model.isModified()) post('modified');"]) {
      if (!s.includes(anchor)) throw new Error('office_thread.js: anchor missing: ' + anchor)
    }
    return Buffer.from(s.replace('const EXEC = {', 'const EXEC = {\n' + DEBUG_ACTIONS)
      .replace('function installModifyListener(model) {', 'let MOD_COUNT = 0;\nfunction installModifyListener(model) {')
      .replace("if (model.isModified()) post('modified');", "if (model.isModified()) { MOD_COUNT++; post('modified'); }"), 'utf8')
  }
  if (/^\/assets\/editor-.*\.js$/.test(urlPath)) {
    const s = content.toString('utf8')
    return Buffer.from(
      s.replace("'get_hyperlink_at_cursor'", "'get_hyperlink_at_cursor','debug_set_record_changes','debug_char_prop','debug_list_comments','debug_fresh_document','debug_table_info','debug_fresh_calc','debug_sheet_cell_info','debug_sheet_doc_info','debug_slide_shape_info','debug_slide_char_prop','debug_lock_state','debug_modified_count','debug_footer_info','debug_para_style_info'")
        .replace('"get_hyperlink_at_cursor"', '"get_hyperlink_at_cursor","debug_set_record_changes","debug_char_prop","debug_list_comments","debug_fresh_document","debug_table_info","debug_fresh_calc","debug_sheet_cell_info","debug_sheet_doc_info","debug_slide_shape_info","debug_slide_char_prop","debug_lock_state","debug_modified_count","debug_footer_info","debug_para_style_info"'),
      'utf8')
  }
  return content
}

// ---------- COOP/COEP static server ----------
const server = await startServer({ patchServed })

// ---------- assertions ----------
let passed = 0, failed = 0
function check(label, cond, detail) {
  if (cond) { passed++; console.log('  PASS ' + label) }
  else { failed++; console.log('  FAIL ' + label + (detail ? '  [' + detail + ']' : '')) }
}

// ---------- drive ----------
const META = 4, SHIFT = 8, ALT = 1
const browser = await launchBrowser(puppeteer)
try {
  const page = await openEditor(browser)
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

  // NOTE: ShowChangesInMargin (tdf#34355) is ON again — engine 24.2.8-zhcn-r3
  // carries the frmpaint table-anchor patch, so in-table deletions render in
  // the true page margin (stock LO painted them over the neighboring cell).
  // Deletions leave the inline text, so the cursor context no longer contains
  // the struck-through originals.
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

  console.log('== 10) 修订作者署名（AI WorkDeck vs 用户名） ==')
  await reset('署名测试。', true) // rc ON：以下插入都会落成修订
  // AI 命令：宿主 handleEditorCommand 会打 __agent 标记 → 署名 AI WorkDeck
  await exec('insert_at_cursor', { text: 'AI改动', __agent: true })
  // 用户操作（IME 提交等）不带标记 → 署当前用户名。load_document 空字节调用
  // 只用来注入 authorName（与宿主 loadDocument 的传参路径一致）。
  await exec('load_document', { authorName: '测试用户' })
  await exec('insert_at_cursor', { text: '用户改动' })
  const rv = await exec('debug_revisions')
  const authors = (rv.redlines || []).map((r) => r.author)
  check('AI 修订署名 AI WorkDeck', rv.success && authors.includes('AI WorkDeck'), JSON.stringify(rv))
  check('用户修订署用户名', authors.includes('测试用户'), JSON.stringify(authors))

  console.log('== 11) 修订颗粒度：一字之差只标一字，不整段删增 ==')
  // 口径说明：按 author 过滤只看本场景的修订（场景 10 已把作者设为"测试用户"），
  // 防前面场景跨 reset 残留的删除记录混入（页边模式下删除文本不在正文流、
  // 不随 reset 硬清）；删除文本经 debug_revisions 的 RedlineText/区间双路取回。
  const mine = (rv2) => (rv2.redlines || []).filter((r) => r.author === '测试用户')
  const delTexts = (rv2) => mine(rv2).filter((r) => r.type === 'Delete').map((r) => r.text)
  await reset('我爱你', true)
  await exec('find_replace', { findText: '我爱你', replaceText: '我恨你', replaceAll: true })
  let rv11 = await exec('debug_revisions')
  check('find_replace 我爱你→我恨你 只删"爱"（非整句）',
    rv11.success && delTexts(rv11).includes('爱') && delTexts(rv11).every((t2) => (t2 || '').length === 1), JSON.stringify(rv11))
  check('插入也只落一处修订', mine(rv11).filter((r) => r.type === 'Insert').length === 1, JSON.stringify(mine(rv11)))
  check('正文呈现新句', (await doc()) === '我恨你', await doc())
  await reset('甲方应于三十日内向乙方支付服务费。', true)
  await exec('modify_paragraph', { index: 0, newText: '甲方应于六十日内向乙方支付全部服务费。' })
  rv11 = await exec('debug_revisions')
  check('modify_paragraph 散点小改只删"三"（非整段重写）',
    rv11.success && delTexts(rv11).includes('三') && delTexts(rv11).every((t2) => (t2 || '').length === 1), JSON.stringify(rv11))
  check('两处插入各自成修订（六 / 全部）', mine(rv11).filter((r) => r.type === 'Insert').length === 2, JSON.stringify(mine(rv11)))
  check('改后段落实文正确', (await doc()) === '甲方应于六十日内向乙方支付全部服务费。', await doc())

  console.log('== 12) add_comment 批注：解释文字挂批注、不进正文 ==')
  await reset('本合同自签署之日起生效。', true)
  const ft = await exec('find_text_locations', { keyword: '签署之日' })
  check('find_text 拿到锚点', ft.success && ft.count === 1 && !!ft.matches[0].anchorId, JSON.stringify(ft))
  const cm = await exec('add_comment', { anchor: ft.matches[0].anchorId, comment: '建议明确签署日期的认定方式', __agent: true })
  check('add_comment 成功且附着目标文本', cm.success && cm.annotatedText === '签署之日', JSON.stringify(cm))
  const lc = await exec('debug_list_comments')
  check('批注可读回、署名 AI WorkDeck、内容完整',
    lc.success && lc.count === 1 && lc.comments[0].author === 'AI WorkDeck' && lc.comments[0].content === '建议明确签署日期的认定方式',
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
    check('正文停在新版可读文本', (await doc()) === '甲方应于六十日内支付合同价款。', await doc())
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

  // ---------- 组 18：审阅面板原语（修订/批注清单·定位·逐条处置）----------
  console.log('\n[18] 审阅面板：list/goto/resolve 修订与批注')
  {
    // 组 16/17 把模型换成了 Calc——审阅原语是 Writer 专属，先换回全新 Writer
    // 可见文档：批注删除依赖引擎的注释窗口，Hidden 文档里不存在
    check('换回 Writer 文档', (await exec('debug_fresh_document', { visible: true })).success === true)
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '甲方应于三十日内向乙方支付服务费。' })
    await exec('load_document', { authorName: '审阅测试' })   // 只注入作者名
    await exec('debug_set_record_changes', { on: true })
    // 两处修订：替换产生「删三 + 插六」
    await exec('find_replace', { findText: '三十日', replaceText: '六十日', replaceAll: true })
    let lr = await exec('list_revisions')
    check('list_revisions 列出修订（含类型/作者/段落上下文）',
      lr.success && lr.count >= 2 && lr.revisions.every((r) => !!r.type && !!r.author) &&
      lr.revisions.some((r) => (r.paragraph || '').includes('乙方')), JSON.stringify(lr).slice(0, 300))
    check('删除型修订带回文本「三」', lr.revisions.some((r) => r.type === 'Delete' && r.text === '三'), JSON.stringify(lr.revisions))
    check('插入型修订带回文本「六」', lr.revisions.some((r) => r.type === 'Insert' && r.text === '六'), JSON.stringify(lr.revisions))

    const insIdx = lr.revisions.findIndex((r) => r.type === 'Insert')
    check('goto_revision 定位插入型（选中新增文本）',
      (await exec('goto_revision', { index: insIdx })).selected === '六', JSON.stringify(await exec('goto_revision', { index: insIdx })))

    // 逐条接受：插入型被接受后该条消失，正文保留新字
    const n0 = lr.count
    const acc = await exec('resolve_revision', { index: insIdx, action: 'accept' })
    check('resolve_revision 接受插入型（条数真的减少）', acc.success === true && acc.remaining === n0 - 1, JSON.stringify(acc))
    check('接受后正文保留「六十日」', (await doc()).includes('六十日'), await doc())

    // 逐条拒绝：删除型被拒绝后原字回到正文
    lr = await exec('list_revisions')
    const delIdx = lr.revisions.findIndex((r) => r.type === 'Delete')
    const rej = await exec('resolve_revision', { index: delIdx, action: 'reject' })
    check('resolve_revision 拒绝删除型（条数真的减少）', rej.success === true && rej.remaining === lr.count - 1, JSON.stringify(rej))
    check('拒绝删除后原字「三」回到正文', (await doc()).includes('三'), await doc())

    // 越界索引必须失败而不是静默成功
    check('越界索引被拒绝', (await exec('resolve_revision', { index: 99, action: 'accept' })).success === false)

    // 批量：再造两处修订后全部拒绝，回到原文
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '乙方应在验收后付款。' })
    await exec('debug_set_record_changes', { on: true })
    await exec('find_replace', { findText: '验收后', replaceText: '验收合格后', replaceAll: true })
    const beforeAll = (await exec('list_revisions')).count
    const all = await exec('resolve_all_revisions', { action: 'reject' })
    check('resolve_all_revisions 全部拒绝', all.success === true && all.remaining === 0 && all.resolved === beforeAll, JSON.stringify(all))
    check('全部拒绝后回到原文', (await doc()) === '乙方应在验收后付款。', await doc())

    // 批注：清单/定位/已解决/删除
    const ftc = await exec('find_text_locations', { keyword: '验收' })
    await exec('add_comment', { anchor: ftc.matches[0].anchorId, comment: '需确认验收标准', __agent: true })
    let lc2 = await exec('list_comments')
    check('list_comments 带作者/内容/锚定文本',
      lc2.success && lc2.count === 1 && lc2.comments[0].author === 'AI WorkDeck' &&
      lc2.comments[0].content === '需确认验收标准' && lc2.comments[0].anchorText === '验收', JSON.stringify(lc2))
    check('goto_comment 成功', (await exec('goto_comment', { index: 0 })).success === true)
    check('set_comment_resolved 标记已解决', (await exec('set_comment_resolved', { index: 0, resolved: true })).resolved === true)
    check('已解决状态可读回', (await exec('list_comments')).comments[0].resolved === true)
    // delete_comment 在宿主加载出来的文档上下文里删不掉（引擎按活动批注窗口找
    // Id）——面板因此不放删除按钮。这里锁的是**不许假成功**：要么真删掉、要么
    // 明确报失败，绝不返回 success 却留着批注。
    const del = await exec('delete_comment', { index: 0 })
    const left = (await exec('list_comments')).count
    check('delete_comment 不假成功（真删或诚实报错）',
      (del.success === true && left === 0) || (del.success === false && left === 1),
      JSON.stringify(del) + ' left=' + left)
  }

  // ---------- 组 19：Calc 结构操作（工作表/行列/合并/排序/筛选/冻结/条件格式）----------
  console.log('\n[19] Calc 结构操作：工作表管理 / 插删行列 / 合并 / 排序 / 筛选 / 冻结 / 条件格式')
  {
    await exec('debug_fresh_calc', { visible: true }) // 冻结窗格需要真实视图
    await new Promise((r) => setTimeout(r, 800))
    // 工作表管理：add / rename / move / delete
    const add = await exec('sheet_manage_sheets', { op: 'add', name: '汇总' })
    check('add 新建工作表', add.success === true && add.sheets.includes('汇总'), JSON.stringify(add))
    const ren = await exec('sheet_manage_sheets', { op: 'rename', name: '汇总', newName: '费用汇总' })
    check('rename 重命名', ren.success === true && ren.sheets.includes('费用汇总') && !ren.sheets.includes('汇总'), JSON.stringify(ren))
    const mv = await exec('sheet_manage_sheets', { op: 'move', name: '费用汇总', position: 0 })
    check('move 移到首位', mv.success === true && mv.sheets[0] === '费用汇总', JSON.stringify(mv))
    const del = await exec('sheet_manage_sheets', { op: 'delete', name: '费用汇总' })
    check('delete 删除', del.success === true && !del.sheets.includes('费用汇总'), JSON.stringify(del))
    const delLast = await exec('sheet_manage_sheets', { op: 'delete', name: del.sheets[0] })
    check('拒绝删除最后一张表', delLast.success === false && /最后一张/.test(delLast.message || ''), JSON.stringify(delLast))

    // 插入/删除行列
    await exec('sheet_write_cells', { startCell: 'A1', rows: [['表头', '数'], ['甲', 3], ['乙', 1], ['丙', 2]] })
    const ir = await exec('sheet_edit_rows_cols', { op: 'insert_rows', start: '2', count: 1 })
    check('第 2 行前插一行', ir.success === true, JSON.stringify(ir))
    let rd = await exec('sheet_read_range', { range: 'A1:A3' })
    check('插行后内容后移（A2 空、A3=甲）', rd.rows[1][0] === '' && rd.rows[2][0] === '甲', JSON.stringify(rd.rows))
    const dr = await exec('sheet_edit_rows_cols', { op: 'delete_rows', start: '2', count: 1 })
    check('删除该行还原', dr.success === true, JSON.stringify(dr))
    const ic = await exec('sheet_edit_rows_cols', { op: 'insert_cols', start: 'B', count: 1 })
    rd = await exec('sheet_read_range', { range: 'A1:C1' })
    check('B 列前插一列（原 B 移到 C）', ic.success === true && rd.rows[0][1] === '' && rd.rows[0][2] === '数', JSON.stringify(rd.rows))
    await exec('sheet_edit_rows_cols', { op: 'delete_cols', start: 'B', count: 1 })
    const badOp = await exec('sheet_edit_rows_cols', { op: 'insert_rows', start: 'x' })
    check('非法行号被拒绝', badOp.success === false, JSON.stringify(badOp))

    // 排序（hasHeader=true 表头不动）：数 3/1/2 → 1/2/3
    const st = await exec('sheet_sort_range', { range: 'A1:B4', byColumn: 'B', ascending: true, hasHeader: true })
    check('sheet_sort_range 成功', st.success === true, JSON.stringify(st))
    rd = await exec('sheet_read_range', { range: 'A1:B4' })
    check('按 B 列升序（表头不动）', rd.rows[0][0] === '表头' && rd.rows[1][1] === 1 && rd.rows[2][1] === 2 && rd.rows[3][1] === 3, JSON.stringify(rd.rows))
    check('行随排序整体移动（乙=1 在前）', rd.rows[1][0] === '乙' && rd.rows[3][0] === '甲', JSON.stringify(rd.rows))
    const stDesc = await exec('sheet_sort_range', { range: 'A1:B4', byColumn: 'B', ascending: false, hasHeader: true })
    const rdDesc = await exec('sheet_read_range', { range: 'B2:B4' })
    check('降序排序', stDesc.success === true && rdDesc.rows[0][0] === 3 && rdDesc.rows[2][0] === 1, JSON.stringify(rdDesc.rows))
    const stBad = await exec('sheet_sort_range', { range: 'A1:B4', byColumn: 'Z' })
    check('区域外排序列被拒绝', stBad.success === false, JSON.stringify(stBad))

    // 合并单元格
    const mg = await exec('sheet_merge_cells', { range: 'A6:C6', merge: true })
    check('合并 A6:C6', mg.success === true && mg.merged === true, JSON.stringify(mg))
    let ci = await exec('debug_sheet_cell_info', { cell: 'A6:C6' })
    check('IsMerged 读回 true', ci.isMerged === true, JSON.stringify(ci.isMerged))
    const un = await exec('sheet_merge_cells', { range: 'A6:C6', merge: false })
    ci = await exec('debug_sheet_cell_info', { cell: 'A6:C6' })
    check('取消合并后 IsMerged=false', un.success === true && ci.isMerged === false, JSON.stringify(ci.isMerged))

    // 自动筛选（命名数据库区域，set 语义）
    const af = await exec('sheet_set_autofilter', { range: 'A1:B4', enabled: true })
    check('开启自动筛选', af.success === true && af.autoFilter === true, JSON.stringify(af))
    let di = await exec('debug_sheet_doc_info')
    check('筛选数据库区域已建且 AutoFilter=true', (di.dbRanges || []).some((d) => d.autoFilter === true), JSON.stringify(di.dbRanges))
    const afOff = await exec('sheet_set_autofilter', { range: 'A1:B4', enabled: false })
    di = await exec('debug_sheet_doc_info')
    check('关闭后数据库区域移除', afOff.success === true && !(di.dbRanges || []).some((d) => /__awd_af_/.test(d.name)), JSON.stringify(di.dbRanges))

    // 冻结窗格
    const fz = await exec('sheet_freeze_panes', { rows: 1, cols: 0 })
    check('冻结首行', fz.success === true && fz.hasFrozenPanes === true, JSON.stringify(fz))
    const unfz = await exec('sheet_freeze_panes', { rows: 0, cols: 0 })
    check('取消冻结', unfz.success === true && unfz.hasFrozenPanes === false, JSON.stringify(unfz))

    // 条件格式：B 列 >2 标红底
    const cf = await exec('sheet_conditional_format', { range: 'B2:B4', rule: 'greater', value1: '2', background: '#FF0000' })
    check('条件格式设置成功（1 条规则）', cf.success === true && cf.entries === 1 && /__awd_cf_/.test(cf.styleName || ''), JSON.stringify(cf))
    const cf2 = await exec('sheet_conditional_format', { range: 'B2:B4', rule: 'between', value1: '1', value2: '2', background: '#00FF00' })
    check('再设替换而非叠加（仍 1 条）', cf2.success === true && cf2.entries === 1, JSON.stringify(cf2))
    const cfClear = await exec('sheet_conditional_format', { range: 'B2:B4', clear: true })
    check('清除条件格式', cfClear.success === true && cfClear.cleared === true, JSON.stringify(cfClear))
    const cfBad = await exec('sheet_conditional_format', { range: 'B2:B4', rule: 'greater', value1: '2' })
    check('缺外观参数被拒绝', cfBad.success === false && /外观/.test(cfBad.message || ''), JSON.stringify(cfBad))
  }

  // ---------- 组 20：Word 表格单元格级原语（doc_table_*）----------
  console.log('\n[20] Word 表格原语：读表 / 改一格 / 增删行列')
  {
    // 文档类型守卫：组 19 留下的是 Calc 文档，doc_table_* 是 Writer 专属
    const guard = await exec('table_read', {})
    check('Calc 文档上 doc_table_* 被明确拒绝', guard.success === false && /Word 文档/.test(guard.message || ''), JSON.stringify(guard))

    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    const it = await exec('insert_table', { rows: [['项目', '金额'], ['咨询费', '10000'], ['差旅费', '2000']], headerRow: true })
    check('准备一张 3×2 表', it.success === true, JSON.stringify(it))

    // 读表
    const rd0 = await exec('table_read', { tableIndex: 0 })
    check('table_read 读回 3 行 2 列', rd0.success === true && rd0.rows === 3 && rd0.cols === 2, JSON.stringify({ r: rd0.rows, c: rd0.cols }))
    check('table_read 二维内容正确',
      JSON.stringify(rd0.cells) === JSON.stringify([['项目', '金额'], ['咨询费', '10000'], ['差旅费', '2000']]),
      JSON.stringify(rd0.cells))
    const rdBad = await exec('table_read', { tableIndex: 5 })
    check('越界表格序号被拒绝且报出表格张数', rdBad.success === false && /共 1 张表/.test(rdBad.message || ''), JSON.stringify(rdBad))
    check('失败返回带 error 字段（后端桥据此判失败）', typeof rdBad.error === 'string' && rdBad.error.length > 0, JSON.stringify(Object.keys(rdBad)))

    // 改一格
    const sc = await exec('table_set_cell', { tableIndex: 0, cell: 'B2', text: '12000' })
    check('table_set_cell 改 B2（返回旧值）', sc.success === true && sc.oldText === '10000', JSON.stringify(sc))
    let rd = await exec('table_read', { tableIndex: 0 })
    check('读回新值 12000', rd.cells[1][1] === '12000', JSON.stringify(rd.cells))
    const scBadRef = await exec('table_set_cell', { tableIndex: 0, cell: '2B', text: 'x' })
    check('非法单元格坐标被拒绝', scBadRef.success === false && /B2/.test(scBadRef.message || ''), JSON.stringify(scBadRef))
    const scOOR = await exec('table_set_cell', { tableIndex: 0, cell: 'Z9', text: 'x' })
    check('表外单元格被拒绝且报出行列数', scOOR.success === false && /3 行 × 2 列/.test(scOOR.message || ''), JSON.stringify(scOOR))
    // 改完光标停在该格：后续原语可省略 tableIndex
    const scCursor = await exec('table_set_cell', { cell: 'A1', text: '费用项目' })
    check('光标已在表内，可省略 tableIndex', scCursor.success === true && scCursor.oldText === '项目', JSON.stringify(scCursor))

    // 插入行
    const ar = await exec('table_add_row', { tableIndex: 0, position: 2 })
    check('第 2 行前插一行', ar.success === true && ar.rows === 4 && ar.insertedAt === 2, JSON.stringify(ar))
    rd = await exec('table_read', { tableIndex: 0 })
    check('插行后内容后移（A2 空、A3=咨询费）', rd.cells[1][0] === '' && rd.cells[2][0] === '咨询费', JSON.stringify(rd.cells))
    const arEnd = await exec('table_add_row', { tableIndex: 0 })
    check('不传 position 追加到表尾', arEnd.success === true && arEnd.rows === 5 && arEnd.insertedAt === 5, JSON.stringify(arEnd))
    const arBad = await exec('table_add_row', { tableIndex: 0, position: 99 })
    check('越界行号被拒绝且报出可用范围', arBad.success === false && /1\.\.6/.test(arBad.message || ''), JSON.stringify(arBad))

    // 插入列
    const ac = await exec('table_add_col', { tableIndex: 0, position: 'B' })
    check('B 列前插一列', ac.success === true && ac.cols === 3, JSON.stringify(ac))
    rd = await exec('table_read', { tableIndex: 0 })
    check('插列后原 B 列移到 C', rd.cells[0][1] === '' && rd.cells[0][2] === '金额', JSON.stringify(rd.cells[0]))
    const acBad = await exec('table_add_col', { tableIndex: 0, position: '甲' })
    check('非法列定位被拒绝', acBad.success === false, JSON.stringify(acBad))

    // 删除行/列（修订关闭 → 真删）
    const dr = await exec('table_delete_row', { tableIndex: 0, position: 2 })
    check('删掉刚插的空行', dr.success === true && dr.removedRows === 1 && dr.rows === 4, JSON.stringify(dr))
    const dc = await exec('table_delete_col', { tableIndex: 0, position: 'B' })
    check('删掉刚插的空列', dc.success === true && dc.removedCols === 1 && dc.cols === 2, JSON.stringify(dc))
    rd = await exec('table_read', { tableIndex: 0 })
    check('删完回到原内容（B1=金额、B2=12000）', rd.cells[0][1] === '金额' && rd.cells[1][1] === '12000', JSON.stringify(rd.cells))
    const drNoPos = await exec('table_delete_row', { tableIndex: 0 })
    check('删除行缺 position 被拒绝', drNoPos.success === false && /position/.test(drNoPos.message || ''), JSON.stringify(drNoPos))
    const drAll = await exec('table_delete_row', { tableIndex: 0, position: 1, count: 9 })
    check('拒绝删光全部行', drAll.success === false, JSON.stringify(drAll))
    const dcAll = await exec('table_delete_col', { tableIndex: 0, position: 'A', count: 9 })
    check('拒绝删光全部列', dcAll.success === false, JSON.stringify(dcAll))

    // 修订模式：改一格只对差异字符落修订（不是整格删了重打）
    await exec('debug_set_record_changes', { on: true })
    const rlBefore = (await exec('debug_revisions')).count
    const scRc = await exec('table_set_cell', { tableIndex: 0, cell: 'B2', text: '13000' })
    check('修订模式下改格走最小修订', scRc.success === true && scRc.via === 'minimalRedline', JSON.stringify(scRc))
    const rlAfter = await exec('debug_revisions')
    check('产生了修订记录', rlAfter.count > rlBefore, JSON.stringify({ before: rlBefore, after: rlAfter.count }))
    const rlTexts = (rlAfter.redlines || []).map((x) => x.text || '').join('/')
    check('修订只覆盖差异字符（不含整格旧值 12000）', !/12000/.test(rlTexts), rlTexts)
    rd = await exec('table_read', { tableIndex: 0 })
    check('正文读回新值 13000', rd.cells[1][1] === '13000', JSON.stringify(rd.cells))

    // 修订模式下删行：真删或落成删除修订都算生效，返回值要说清是哪种
    const drRc = await exec('table_delete_row', { tableIndex: 0, position: 3 })
    check('修订模式下删行生效（真删或删除修订）', drRc.success === true, JSON.stringify(drRc))
    console.log('    (删行修订口径：removedRows=' + drRc.removedRows + ' redlineDelta=' + drRc.redlineDelta
      + ' trackedAsRevision=' + drRc.trackedAsRevision + ')')
    await exec('debug_set_record_changes', { on: false })
  }

  // ---------- 组 21：Impress 冒烟（slide_*，r4 引擎首验）----------
  // 覆盖 docs/superpowers/specs/2026-08-07-impress-bridge-design.md Phase 0/1
  // 验收：模块具备性 → 打开真 pptx → overview/get_page → 改文字/跨页替换 →
  // 备注往返 → export/reload 往返保真 → 非演示文稿上的守卫。
  console.log('\n[21] Impress 冒烟：pptx 打开 / overview / 形状 / 替换 / 备注 / 往返')
  {
    const pm = await exec('probe_modules')
    check('probe_modules：simpress 可用（r4 引擎）', pm.success === true && pm.simpress === true, JSON.stringify(pm))
    check('probe_modules：sdraw 可用（r4 引擎）', pm.success === true && pm.sdraw === true, JSON.stringify(pm))

    const fixturePath = path.join(here, 'fixtures/impress-smoke.pptx')
    const pptxBytes = Array.from(fs.readFileSync(fixturePath))
    const ld = await exec('load_document', { bytes: pptxBytes, name: 'impress-smoke.pptx', authorName: '测试用户' })
    check('load_document 打开 pptx 成功', ld.success === true, JSON.stringify(ld))
    check('load_document 返回 kind=impress', ld.kind === 'impress', JSON.stringify(ld))

    const dk = await exec('get_doc_kind')
    check('get_doc_kind = impress', dk.success === true && dk.kind === 'impress', JSON.stringify(dk))

    const ov = await exec('slide_get_overview')
    check('slide_get_overview 页数=2', ov.success === true && ov.slideCount === 2, JSON.stringify(ov))
    check('第 1 页标题正确', ov.slides && ov.slides[0] && ov.slides[0].titleText === '冒烟测试标题一', JSON.stringify(ov.slides && ov.slides[0]))
    check('第 2 页标题正确', ov.slides && ov.slides[1] && ov.slides[1].titleText === '第二页标题', JSON.stringify(ov.slides && ov.slides[1]))

    const p1 = await exec('slide_get_page', { slideNumber: 1 })
    check('slide_get_page(1) 形状清单非空', p1.success === true && Array.isArray(p1.shapes) && p1.shapes.length > 0, JSON.stringify(p1))
    check('形状清单含文本', p1.shapes.some((s) => (s.text || '').includes('普通文本框内容')), JSON.stringify(p1.shapes))
    const tbShape = p1.shapes.find((s) => (s.text || '').includes('普通文本框内容'))
    check('普通文本框可定位（有 shapeName）', !!(tbShape && tbShape.name), JSON.stringify(tbShape))

    const sst = await exec('slide_set_shape_text', { slideNumber: 1, shapeName: tbShape.name, text: '改后的文本框内容' })
    check('slide_set_shape_text 成功且返回旧文字', sst.success === true && sst.previousText === '普通文本框内容', JSON.stringify(sst))
    const p1b = await exec('slide_get_page', { slideNumber: 1 })
    check('读回：文本框内容已改', p1b.shapes.some((s) => s.name === tbShape.name && s.text === '改后的文本框内容'), JSON.stringify(p1b.shapes))

    const rep = await exec('slide_replace_text', { searchText: '第二页', replaceText: '第贰页', all: true })
    check('slide_replace_text 跨页替换成功', rep.success === true && rep.replaced >= 1, JSON.stringify(rep))
    const ov2 = await exec('slide_get_overview')
    check('替换后第 2 页标题已变', ov2.slides[1].titleText === '第贰页标题', JSON.stringify(ov2.slides[1]))

    const wn = await exec('slide_write_notes', { slideNumber: 1, text: '改后的第一页备注' })
    check('slide_write_notes 成功且返回旧备注', wn.success === true && wn.previousText === '第一页备注', JSON.stringify(wn))
    const rn = await exec('slide_read_notes', { slideNumber: 1 })
    check('slide_read_notes 读回改后备注', rn.success === true && rn.notes[0].text === '改后的第一页备注', JSON.stringify(rn))
    const rnAll = await exec('slide_read_notes', {})
    check('slide_read_notes 缺省读全篇（2 页）', rnAll.success === true && rnAll.notes.length === 2, JSON.stringify(rnAll))

    // 导出保存 → 重新打开：往返保真冒烟。bytes 字段经 CDP 返回值 JSON 化会变成
    // 空对象（组 13 已踩过），实际字节要在 page.evaluate 内部先转 Array 再带出。
    const exp = await exec('export_document', { name: 'impress-smoke.pptx' })
    check('export_document 导出成功且非空字节', exp.success === true && exp.size > 0, JSON.stringify({ success: exp.success, size: exp.size }))
    // export_document 缺省文件名回退 .docx（Writer 过滤器），对 Impress 文档会
    // 用错导出过滤器——第二次取字节的调用必须带上同一个 pptx 文件名。
    const expBytes = await page.evaluate(async () => {
      const r = await window.__loExecutor.executeCommand('export_document', { name: 'impress-smoke.pptx' })
      return r && r.bytes ? Array.from(r.bytes) : null
    })
    check('导出字节可安全带出浏览器边界', Array.isArray(expBytes) && expBytes.length > 0, String(expBytes && expBytes.length))

    const ld2 = await exec('load_document', { bytes: expBytes, name: 'impress-smoke-roundtrip.pptx', authorName: '测试用户' })
    check('重新打开导出的 pptx 成功', ld2.success === true && ld2.kind === 'impress', JSON.stringify(ld2))
    const ov3 = await exec('slide_get_overview')
    check('往返后页数仍为 2', ov3.success === true && ov3.slideCount === 2, JSON.stringify(ov3))
    check('往返后第 2 页标题仍是改后文字', ov3.success === true && ov3.slides[1].titleText === '第贰页标题', JSON.stringify(ov3.slides && ov3.slides[1]))
    const p1c = await exec('slide_get_page', { slideNumber: 1 })
    check('往返后第 1 页文本框内容仍在', p1c.success === true && p1c.shapes.some((s) => (s.text || '') === '改后的文本框内容'), JSON.stringify(p1c.shapes))
    const rn3 = await exec('slide_read_notes', { slideNumber: 1 })
    check('往返后备注仍在', rn3.success === true && rn3.notes[0].text === '改后的第一页备注', JSON.stringify(rn3))

    // 守卫：换回全新 Writer 文档，slide_* 必须明确报错，不能抛 UNO 异常
    await exec('debug_fresh_document')
    const guard = await exec('slide_get_overview')
    check('Writer 文档上 slide_get_overview 被明确拒绝', guard.success === false && /演示文稿/.test(guard.message || ''), JSON.stringify(guard))
    check('守卫失败带 error 字段', typeof guard.error === 'string' && guard.error.length > 0, JSON.stringify(Object.keys(guard)))
  }

  // ---------- 组 22：Impress 结构（slide_*，Phase 2 首验）----------
  // 覆盖 spec Phase 2 验收口径：插页到中间位置 → 顺序断言 → 移动页 → 顺序断言 →
  // 加文本框/形状 → get_page 断言 → 删形状 → 断言消失；每步用结构计数/顺序双口径
  // 复核，不只信 dispatch 返回值。重新打开一份干净的 impress-smoke.pptx，避免
  // 组 21 对第 2 页标题的改动（第贰页标题）污染本组的固定文案断言。
  console.log('\n[22] Impress 结构：插删移页 / 版式 / 文本框 / 形状 / 位置尺寸 / 守卫')
  {
    const fixturePath = path.join(here, 'fixtures/impress-smoke.pptx')
    const pptxBytes = Array.from(fs.readFileSync(fixturePath))
    const ld = await exec('load_document', { bytes: pptxBytes, name: 'impress-structure.pptx', authorName: '测试用户' })
    check('重新打开 pptx 成功（组 22 独立起手）', ld.success === true && ld.kind === 'impress', JSON.stringify(ld))

    // 插页到第 1 页之后（成为第 2 页），带标题与版式 1（标题+内容）
    const ap1 = await exec('slide_add_page', { position: 1, layout: 1, title: '新插入页' })
    check('slide_add_page(position=1) 成功且落在第 2 页', ap1.success === true && ap1.slideNumber === 2, JSON.stringify(ap1))
    let ov = await exec('slide_get_overview')
    check('插页后页数=3', ov.success === true && ov.slideCount === 3, JSON.stringify(ov))
    check('第 2 页标题=新插入页（顺序断言）', ov.slides && ov.slides[1] && ov.slides[1].titleText === '新插入页', JSON.stringify(ov.slides))

    // 缺省追加到末尾
    const ap2 = await exec('slide_add_page', { title: '追加页', layout: 1 })
    check('slide_add_page 缺省追加到末尾（第 4 页）', ap2.success === true && ap2.slideNumber === 4, JSON.stringify(ap2))
    ov = await exec('slide_get_overview')
    check('追加后页数=4', ov.success === true && ov.slideCount === 4, JSON.stringify(ov))
    check('第 4 页标题=追加页', ov.slides && ov.slides[3] && ov.slides[3].titleText === '追加页', JSON.stringify(ov.slides))
    // 回归锚点：真机曾实测到"追加页"会连带清空相邻既有页的占位符内容（当时的根因
    // 是 insertNewByIndex/挪位相关的引擎行为，office_thread.js slide_add_page 注释
    // 有详述）——用"四页标题都在、都非空"钉死"没有内容丢失"这条底线。**不**断言其余
    // 三页的相对顺序：挪到真正最后一页要靠 movePageTo 的"交换法"规避一个真机确认的
    // .uno:MovePage* 卡死问题（同一注释详述），交换法允许被交换的那一对既有页之间
    // 相对顺序也跟着换一次——这是已知、可接受的副作用，不是数据丢失。
    const titlesAfterAppend = ov.slides.map((s) => s.titleText)
    check('追加页后四页标题齐全无丢失', ['冒烟测试标题一', '新插入页', '第二页标题', '追加页'].every((t) => titlesAfterAppend.includes(t)), JSON.stringify(titlesAfterAppend))

    // position 越界应明确拒绝，不是静默截断
    const apBad = await exec('slide_add_page', { position: 99 })
    check('slide_add_page position 越界被拒绝', apBad.success === false && typeof apBad.error === 'string' && apBad.error.length > 0, JSON.stringify(apBad))

    // 移动"新插入页"到第 2 位——用查表定位当前位置而不是假设固定顺序（上面已
    // 注明：交换法可能已经调换过它与"第二页标题"的相对顺序）。
    const fromPos = ov.slides.findIndex((s) => s.titleText === '新插入页') + 1
    const mv = await exec('slide_move_page', { slideNumber: fromPos, toPosition: 2 })
    check('slide_move_page 移动成功', mv.success === true && mv.from === fromPos && mv.to === 2, JSON.stringify(mv))
    ov = await exec('slide_get_overview')
    check('移动后：新插入页落在第 2 页', ov.slides[1].titleText === '新插入页', JSON.stringify(ov.slides.map((s) => s.titleText)))
    check('移动后：四页标题仍齐全（无丢失）', ['冒烟测试标题一', '新插入页', '第二页标题', '追加页'].every((t) => ov.slides.map((s) => s.titleText).includes(t)), JSON.stringify(ov.slides.map((s) => s.titleText)))

    // 删除"新插入页"（刚移动到第 2 页）
    const dp = await exec('slide_delete_page', { slideNumber: 2 })
    check('slide_delete_page 成功', dp.success === true && dp.slideCount === 3, JSON.stringify(dp))
    ov = await exec('slide_get_overview')
    check('删除后不再含"新插入页"', !ov.slides.some((s) => s.titleText === '新插入页'), JSON.stringify(ov.slides.map((s) => s.titleText)))
    check('删除后页数=3', ov.slideCount === 3, JSON.stringify(ov))

    // 连续删到只剩一页，第三次删除应被拒绝（不允许删到 0 页）
    await exec('slide_delete_page', { slideNumber: 2 })
    const dpLast = await exec('slide_delete_page', { slideNumber: 1 })
    ov = await exec('slide_get_overview')
    check('删到只剩一页后再删被拒绝', dpLast.success === false || ov.slideCount >= 1, JSON.stringify({ dpLast, slideCount: ov.slideCount }))
    if (ov.slideCount === 1) {
      const dpGuard = await exec('slide_delete_page', { slideNumber: 1 })
      check('只剩一页时删除被拒绝（拒绝删到 0 页）', dpGuard.success === false && /最后一页/.test(dpGuard.message || ''), JSON.stringify(dpGuard))
      check('拒绝删除带 error 字段', typeof dpGuard.error === 'string' && dpGuard.error.length > 0, JSON.stringify(Object.keys(dpGuard)))
    }

    // 剩余唯一一页上验证版式设置
    const sl = await exec('slide_set_layout', { slideNumber: 1, layout: 20 })
    check('slide_set_layout 设置版式成功', sl.success === true && Number(sl.layout) === 20, JSON.stringify(sl))
    const ovL = await exec('slide_get_overview')
    const masterName = ovL.slides[0].masterName
    const slM = await exec('slide_set_layout', { slideNumber: 1, masterName: masterName })
    check('slide_set_layout 按现有母版名重设母版成功', slM.success === true && slM.masterName === masterName, JSON.stringify(slM))
    const slBad = await exec('slide_set_layout', { slideNumber: 1, masterName: '不存在的母版名__xyz' })
    check('slide_set_layout 母版名不存在被拒绝', slBad.success === false && typeof slBad.error === 'string', JSON.stringify(slBad))

    // 插入文本框，读回位置/文字
    const atb = await exec('slide_add_text_box', { slideNumber: 1, text: '插入的文本框', left: 50, top: 60, width: 200, height: 50 })
    check('slide_add_text_box 成功且返回 shapeName', atb.success === true && !!atb.shapeName, JSON.stringify(atb))
    let page1 = await exec('slide_get_page', { slideNumber: 1 })
    const tb = page1.shapes.find((s) => s.name === atb.shapeName)
    check('新文本框可读回且文字正确', !!tb && tb.text === '插入的文本框', JSON.stringify(tb))
    check('新文本框位置尺寸接近预期（±1pt）', !!tb && Math.abs(tb.left - 50) < 1 && Math.abs(tb.top - 60) < 1 && Math.abs(tb.width - 200) < 1 && Math.abs(tb.height - 50) < 1, JSON.stringify(tb))

    // 插入矩形/椭圆/三角形形状
    const rect = await exec('slide_add_shape', { slideNumber: 1, shapeType: 'rectangle', left: 10, top: 10, width: 80, height: 40, fillColor: '#FF0000', text: '矩形' })
    check('slide_add_shape rectangle 成功', rect.success === true && !!rect.shapeName, JSON.stringify(rect))
    const ell = await exec('slide_add_shape', { slideNumber: 1, shapeType: 'ellipse', left: 120, top: 10, width: 60, height: 60 })
    check('slide_add_shape ellipse 成功', ell.success === true && !!ell.shapeName, JSON.stringify(ell))
    const tri = await exec('slide_add_shape', { slideNumber: 1, shapeType: 'triangle', left: 200, top: 10, width: 60, height: 60 })
    check('slide_add_shape triangle 成功', tri.success === true && !!tri.shapeName, JSON.stringify(tri))
    const badShape = await exec('slide_add_shape', { slideNumber: 1, shapeType: 'star' })
    check('slide_add_shape 未知 shapeType 被拒绝', badShape.success === false && typeof badShape.error === 'string', JSON.stringify(badShape))

    page1 = await exec('slide_get_page', { slideNumber: 1 })
    const rectShape = page1.shapes.find((s) => s.name === rect.shapeName)
    check('矩形形状读回 kind=rectangle 且文字正确', !!rectShape && rectShape.kind === 'rectangle' && rectShape.text === '矩形', JSON.stringify(rectShape))
    const ellShape = page1.shapes.find((s) => s.name === ell.shapeName)
    check('椭圆形状读回 kind=ellipse', !!ellShape && ellShape.kind === 'ellipse', JSON.stringify(ellShape))
    const shapeCountBeforeDelete = page1.shapes.length

    // 删除矩形，断言消失
    const del = await exec('slide_delete_shape', { slideNumber: 1, shapeName: rect.shapeName })
    check('slide_delete_shape 成功且返回删除的名字', del.success === true && del.deleted === rect.shapeName, JSON.stringify(del))
    page1 = await exec('slide_get_page', { slideNumber: 1 })
    check('删除后形状计数减一', page1.shapes.length === shapeCountBeforeDelete - 1, JSON.stringify({ before: shapeCountBeforeDelete, after: page1.shapes.length }))
    check('删除后矩形不再出现', !page1.shapes.some((s) => s.name === rect.shapeName), JSON.stringify(page1.shapes.map((s) => s.name)))
    const delBad = await exec('slide_delete_shape', { slideNumber: 1, shapeName: '不存在的形状__xyz' })
    check('slide_delete_shape 形状不存在被拒绝', delBad.success === false && typeof delBad.error === 'string', JSON.stringify(delBad))

    // 调整椭圆位置尺寸，读回验证 before/after 与实际生效值
    const geo = await exec('slide_set_shape_geometry', { slideNumber: 1, shapeName: ell.shapeName, left: 300, top: 200, width: 90, height: 45 })
    check('slide_set_shape_geometry 成功且带 before/after', geo.success === true && geo.before && geo.after, JSON.stringify(geo))
    check('geometry after 值接近目标（±1pt）',
      Math.abs(geo.after.left - 300) < 1 && Math.abs(geo.after.top - 200) < 1 && Math.abs(geo.after.width - 90) < 1 && Math.abs(geo.after.height - 45) < 1,
      JSON.stringify(geo.after))
    page1 = await exec('slide_get_page', { slideNumber: 1 })
    const ellAfter = page1.shapes.find((s) => s.name === ell.shapeName)
    check('读回：椭圆位置尺寸已生效', !!ellAfter && Math.abs(ellAfter.left - 300) < 1 && Math.abs(ellAfter.top - 200) < 1, JSON.stringify(ellAfter))
    const geoBadShape = await exec('slide_set_shape_geometry', { slideNumber: 1, shapeName: '不存在的形状__xyz', left: 0 })
    check('slide_set_shape_geometry 形状不存在被拒绝', geoBadShape.success === false && typeof geoBadShape.error === 'string', JSON.stringify(geoBadShape))

    // 守卫：换回全新 Writer 文档，Phase 2 写类原语必须明确报错，不能抛 UNO 异常
    await exec('debug_fresh_document')
    const guardAdd = await exec('slide_add_page', {})
    check('Writer 文档上 slide_add_page 被明确拒绝', guardAdd.success === false && /演示文稿/.test(guardAdd.message || ''), JSON.stringify(guardAdd))
    check('Writer 守卫失败带 error 字段', typeof guardAdd.error === 'string' && guardAdd.error.length > 0, JSON.stringify(Object.keys(guardAdd)))
  }

  // ---------- 组 23：Impress 格式与表格（slide_*，Phase 3 首验）----------
  // 覆盖 spec Phase 3 验收口径：建表 → 写格 → 读回二维数组一致 → 文字设字体字号
  // 加粗 → get_page 读回格式 → 设超链接 → 链接仍在；另加 slide_format_shape /
  // slide_table_set_style（任务方直接点名的两项，不在 spec 20 个原语表里）。
  // 凡是本次实施调研没有查实的 UNO 能力（表格单元格边框、表格列宽），用
  // debug_slide_shape_info 真机读回，按 applied 分支走——支持就断言数值，
  // 不支持就打印说明而不是断言失败（"能做多少做多少，做不了的明说"）。
  console.log('\n[23] Impress 格式与表格：文字格式 / 形状样式 / 表格 / 表格样式 / 超链接 / 守卫')
  {
    const fixturePath = path.join(here, 'fixtures/impress-smoke.pptx')
    const pptxBytes = Array.from(fs.readFileSync(fixturePath))
    const ld = await exec('load_document', { bytes: pptxBytes, name: 'impress-format.pptx', authorName: '测试用户' })
    check('重新打开 pptx 成功（组 23 独立起手）', ld.success === true && ld.kind === 'impress', JSON.stringify(ld))

    // ---- slide_format_text：整形状格式化 + 读回 ----
    let page1 = await exec('slide_get_page', { slideNumber: 1 })
    const tbShape = page1.shapes.find((s) => (s.text || '').includes('普通文本框内容'))
    check('定位到普通文本框', !!(tbShape && tbShape.name), JSON.stringify(tbShape))

    const ft1 = await exec('slide_format_text', {
      slideNumber: 1, shapeName: tbShape.name,
      fontName: 'Arial', fontSize: 20, bold: true, italic: true,
      underline: 'wave', strikethrough: true, color: '#FF0000', alignment: 'center',
    })
    check('slide_format_text 整形状格式化成功', ft1.success === true, JSON.stringify(ft1))
    check('slide_format_text 返回 applied 齐全',
      ft1.applied && ft1.applied.fontName === 'Arial' && ft1.applied.fontSize === 20 && ft1.applied.bold === true
        && ft1.applied.italic === true && ft1.applied.underline === 'wave' && ft1.applied.strikethrough === true
        && ft1.applied.alignment === 'center',
      JSON.stringify(ft1.applied))

    page1 = await exec('slide_get_page', { slideNumber: 1 })
    const tbAfter = page1.shapes.find((s) => s.name === tbShape.name)
    check('get_page 读回格式：字体/字号/加粗/斜体/下划线/删除线/对齐',
      !!tbAfter && tbAfter.format && tbAfter.format.fontName === 'Arial' && Math.abs(tbAfter.format.fontSize - 20) < 0.01
        && tbAfter.format.bold === true && tbAfter.format.italic === true && tbAfter.format.underline === true
        && tbAfter.format.strikethrough === true && tbAfter.format.alignment === 'center',
      JSON.stringify(tbAfter && tbAfter.format))
    check('get_page 读回格式：颜色', !!tbAfter && tbAfter.format && tbAfter.format.color === '#ff0000', JSON.stringify(tbAfter && tbAfter.format))

    // ---- slide_format_text：anchorText 只影响子串范围，范围外字符不受影响 ----
    const titleShape = page1.shapes.find((s) => s.kind === 'title')
    check('第 1 页标题形状存在', !!titleShape, JSON.stringify(page1.shapes.map((s) => s.kind)))
    const baseline0 = await exec('debug_slide_char_prop', { slideNumber: 1, shapeName: titleShape.name, offset: 0, prop: 'CharWeight' })
    check('anchorText 测试前置：读到标题首字符基线格式', baseline0.success === true, JSON.stringify(baseline0))

    // 标题原文"冒烟测试标题一"：'标题一' 从 offset 4 开始，长度 3
    const ft2 = await exec('slide_format_text', { slideNumber: 1, shapeName: titleShape.name, anchorText: '标题一', bold: true, color: '#00CC00' })
    check('slide_format_text anchorText 命中成功', ft2.success === true && ft2.applied && ft2.applied.bold === true, JSON.stringify(ft2))
    const insideAnchor = await exec('debug_slide_char_prop', { slideNumber: 1, shapeName: titleShape.name, offset: 4, prop: 'CharWeight' })
    check('anchorText 范围内字符已加粗', typeof insideAnchor.value === 'number' && insideAnchor.value > 100, JSON.stringify(insideAnchor))
    const outsideAnchor = await exec('debug_slide_char_prop', { slideNumber: 1, shapeName: titleShape.name, offset: 0, prop: 'CharWeight' })
    check('anchorText 范围外字符未受影响（与基线一致）', outsideAnchor.value === baseline0.value, JSON.stringify({ before: baseline0.value, after: outsideAnchor.value }))

    // ---- slide_format_text：负向用例 ----
    const ftNoShape = await exec('slide_format_text', { slideNumber: 1, bold: true })
    check('slide_format_text 缺 shapeName 被拒绝', ftNoShape.success === false && typeof ftNoShape.error === 'string', JSON.stringify(ftNoShape))
    const ftBadShape = await exec('slide_format_text', { slideNumber: 1, shapeName: '不存在的形状__xyz', bold: true })
    check('slide_format_text 形状不存在被拒绝', ftBadShape.success === false && typeof ftBadShape.error === 'string', JSON.stringify(ftBadShape))
    const ftBadUnderline = await exec('slide_format_text', { slideNumber: 1, shapeName: tbShape.name, underline: 'squiggly' })
    check('slide_format_text 未知 underline 被拒绝', ftBadUnderline.success === false && typeof ftBadUnderline.error === 'string', JSON.stringify(ftBadUnderline))
    const ftBadAlign = await exec('slide_format_text', { slideNumber: 1, shapeName: tbShape.name, alignment: 'diagonal' })
    check('slide_format_text 未知 alignment 被拒绝', ftBadAlign.success === false && typeof ftBadAlign.error === 'string', JSON.stringify(ftBadAlign))
    const ftNoParam = await exec('slide_format_text', { slideNumber: 1, shapeName: tbShape.name })
    check('slide_format_text 无格式参数被拒绝', ftNoParam.success === false && typeof ftNoParam.error === 'string', JSON.stringify(ftNoParam))
    const ftNoAnchor = await exec('slide_format_text', { slideNumber: 1, shapeName: tbShape.name, anchorText: '找不到的子串__xyz', bold: true })
    check('slide_format_text anchorText 未命中被拒绝', ftNoAnchor.success === false && typeof ftNoAnchor.error === 'string', JSON.stringify(ftNoAnchor))

    // ---- slide_format_shape：新增矩形形状 + 填充/边框/透明度 + 读回 ----
    const rect2 = await exec('slide_add_shape', { slideNumber: 1, shapeType: 'rectangle', left: 250, top: 250, width: 80, height: 40 })
    check('新增矩形用于格式测试', rect2.success === true && !!rect2.shapeName, JSON.stringify(rect2))

    const fs1 = await exec('slide_format_shape', { slideNumber: 1, shapeName: rect2.shapeName, fillColor: '#3366CC', lineColor: '#000000', lineWidthPt: 3, fillTransparency: 25 })
    check('slide_format_shape 成功', fs1.success === true, JSON.stringify(fs1))
    check('slide_format_shape 返回 applied 齐全',
      fs1.applied && fs1.applied.fillColor === '#3366CC' && fs1.applied.lineColor === '#000000'
        && fs1.applied.lineWidthPt === 3 && fs1.applied.fillTransparency === 25,
      JSON.stringify(fs1.applied))
    const shapeInfo = await exec('debug_slide_shape_info', { slideNumber: 1, shapeName: rect2.shapeName })
    check('填充色读回正确（0x3366CC）', shapeInfo.success === true && shapeInfo.fillColor === 0x3366CC, JSON.stringify(shapeInfo))
    check('边框色读回正确（黑色）', shapeInfo.lineColor === 0x000000, JSON.stringify(shapeInfo))
    check('边框粗细读回接近 3pt（≈106/100mm）', Math.abs(shapeInfo.lineWidth - Math.round(3 * 2540 / 72)) <= 1, JSON.stringify(shapeInfo))
    check('填充透明度读回=25', shapeInfo.fillTransparence === 25, JSON.stringify(shapeInfo))

    const fsNoFill = await exec('slide_format_shape', { slideNumber: 1, shapeName: rect2.shapeName, noFill: true, noLine: true })
    check('slide_format_shape noFill/noLine 成功', fsNoFill.success === true && fsNoFill.applied.noFill === true && fsNoFill.applied.noLine === true, JSON.stringify(fsNoFill))
    const shapeInfo2 = await exec('debug_slide_shape_info', { slideNumber: 1, shapeName: rect2.shapeName })
    check('noFill/noLine 读回 fillStyle/lineStyle=0（NONE）', shapeInfo2.fillStyle === 0 && shapeInfo2.lineStyle === 0, JSON.stringify(shapeInfo2))

    const fsNoParam = await exec('slide_format_shape', { slideNumber: 1, shapeName: rect2.shapeName })
    check('slide_format_shape 无参数被拒绝', fsNoParam.success === false && typeof fsNoParam.error === 'string', JSON.stringify(fsNoParam))
    const fsBadShape = await exec('slide_format_shape', { slideNumber: 1, shapeName: '不存在的形状__xyz', fillColor: '#FFFFFF' })
    check('slide_format_shape 形状不存在被拒绝', fsBadShape.success === false && typeof fsBadShape.error === 'string', JSON.stringify(fsBadShape))

    // ---- slide_add_table / slide_table_read / slide_table_set_cell ----
    const tableData = [['姓名', '职位'], ['张三', '合伙人'], ['李四', '律师']]
    const at = await exec('slide_add_table', { slideNumber: 1, rowsJson: tableData, left: 300, top: 300, width: 200, height: 100 })
    check('slide_add_table 成功', at.success === true && !!at.shapeName && at.rows === 3 && at.cols === 2, JSON.stringify(at))

    const tr1 = await exec('slide_table_read', { slideNumber: 1, shapeName: at.shapeName })
    check('slide_table_read 行列数正确', tr1.success === true && tr1.rows === 3 && tr1.cols === 2, JSON.stringify(tr1))
    check('slide_table_read 内容与写入一致', JSON.stringify(tr1.cells) === JSON.stringify(tableData), JSON.stringify(tr1.cells))

    // 该页此刻只有这一张表格——不传 shapeName 应能默认命中同一张
    const tr1Default = await exec('slide_table_read', { slideNumber: 1 })
    check('slide_table_read 缺省（该页唯一表格）命中同一张', tr1Default.success === true && tr1Default.shapeName === at.shapeName, JSON.stringify(tr1Default))

    const tsc = await exec('slide_table_set_cell', { slideNumber: 1, shapeName: at.shapeName, row: 1, col: 1, text: '高级合伙人' })
    check('slide_table_set_cell 成功且返回旧文字', tsc.success === true && tsc.previous === '合伙人', JSON.stringify(tsc))
    const tr2 = await exec('slide_table_read', { slideNumber: 1, shapeName: at.shapeName })
    check('slide_table_set_cell 读回已生效', tr2.cells[1][1] === '高级合伙人', JSON.stringify(tr2.cells))

    const tscBadRow = await exec('slide_table_set_cell', { slideNumber: 1, shapeName: at.shapeName, row: 99, col: 0, text: 'x' })
    check('slide_table_set_cell row 越界被拒绝', tscBadRow.success === false && typeof tscBadRow.error === 'string', JSON.stringify(tscBadRow))
    const trBadShape = await exec('slide_table_read', { slideNumber: 1, shapeName: '不存在的形状__xyz' })
    check('slide_table_read 形状不存在被拒绝', trBadShape.success === false && typeof trBadShape.error === 'string', JSON.stringify(trBadShape))
    const tsc2 = await exec('slide_table_set_cell', { slideNumber: 1, shapeName: tbShape.name, row: 0, col: 0, text: 'x' })
    check('slide_table_set_cell 非表格形状被拒绝', tsc2.success === false && typeof tsc2.error === 'string', JSON.stringify(tsc2))

    // 第二张表：验证"该页有多张表格时不传 shapeName 必须报错"（不能悄悄挑一张）
    const at2 = await exec('slide_add_table', { slideNumber: 1, rows: 2, cols: 2, left: 20, top: 380 })
    check('第二张表格插入成功', at2.success === true, JSON.stringify(at2))
    const trAmbiguous = await exec('slide_table_read', { slideNumber: 1 })
    check('该页有多张表格时缺省 shapeName 被拒绝', trAmbiguous.success === false && typeof trAmbiguous.error === 'string', JSON.stringify(trAmbiguous))

    // ---- slide_table_set_style：表头加粗（应生效）+ 边框/列宽（据实机支持与否分支）----
    const styleRes = await exec('slide_table_set_style', { slideNumber: 1, shapeName: at.shapeName, headerBold: true, borderWidthPt: 2, borderColor: '#FF0000', columnWidthsPt: [150, 90] })
    check('slide_table_set_style 调用成功', styleRes.success === true, JSON.stringify(styleRes))
    check('表头加粗已生效（headerBold.applied）', styleRes.headerBold && styleRes.headerBold.applied === true && styleRes.headerBold.cellsFailed === 0, JSON.stringify(styleRes.headerBold))

    if (styleRes.borders && styleRes.borders.applied) {
      const cellInfo = await exec('debug_slide_shape_info', { slideNumber: 1, shapeName: at.shapeName, cell: { row: 0, col: 0 } })
      check('表格边框读回接近 2pt（引擎支持单元格边框属性）', Math.abs(cellInfo.cellTopBorderWidth - Math.round(2 * 2540 / 72)) <= 1, JSON.stringify(cellInfo))
    } else {
      console.log('  [note] Impress 表格单元格边框在本引擎构建上未生效（' + JSON.stringify(styleRes.borders) + '），符合"能做多少做多少"的预期不确定性，不判失败')
    }
    if (styleRes.columnWidths && styleRes.columnWidths.applied) {
      const colInfo = await exec('debug_slide_shape_info', { slideNumber: 1, shapeName: at.shapeName, colIndex: 0 })
      check('表格列宽读回接近 150pt（引擎支持列宽属性）', Math.abs(colInfo.colWidth - Math.round(150 * 2540 / 72)) <= 2, JSON.stringify(colInfo))
    } else {
      console.log('  [note] Impress 表格列宽在本引擎构建上未生效（' + JSON.stringify(styleRes.columnWidths) + '），符合"能做多少做多少"的预期不确定性，不判失败')
    }
    const styleNoParam = await exec('slide_table_set_style', { slideNumber: 1, shapeName: at.shapeName })
    check('slide_table_set_style 无参数被拒绝', styleNoParam.success === false && typeof styleNoParam.error === 'string', JSON.stringify(styleNoParam))

    // ---- slide_set_hyperlink：命中 + 读回（字符级或形状级，视引擎支持而定）+ export/reload 后仍在 ----
    // 真机实测（r4）：Impress 的 drawing.Text 不支持 HyperLinkURL 字符属性
    // （UnknownPropertyException），slide_set_hyperlink 会自动退化为形状级
    // OnClick=DOCUMENT+Bookmark——按返回的 via 字段分支验证，不假设哪条路径会走通。
    // 注意：fixture 里第 1 页还有一个内容占位符，文字是"占位符正文内容"——同样
    // 以"内容"结尾，若拿"内容"当 searchText 会先命中占位符而不是这个文本框
    // （slide_set_hyperlink 按页内形状顺序命中第一处，这是设计如此，不是 bug）。
    // 用"文本框内容"这个只在目标文本框里出现的子串消歧。
    const shl = await exec('slide_set_hyperlink', { slideNumber: 1, searchText: '文本框内容', url: 'https://www.aiworkdeck.com' })
    check('slide_set_hyperlink 成功', shl.success === true && shl.shapeName === tbShape.name, JSON.stringify(shl))
    check('slide_set_hyperlink via 字段是已知取值之一', shl.via === 'HyperLinkURL' || shl.via === 'shape-click-action', JSON.stringify(shl))
    if (shl.via === 'HyperLinkURL') {
      // "普通文本框内容" 中 '文本框内容' 从 offset 2 开始
      const baselineLink = await exec('debug_slide_char_prop', { slideNumber: 1, shapeName: tbShape.name, offset: 0, prop: 'HyperLinkURL' })
      const linkAt2 = await exec('debug_slide_char_prop', { slideNumber: 1, shapeName: tbShape.name, offset: 2, prop: 'HyperLinkURL' })
      check('超链接落在命中文字上（字符级）', linkAt2.value === 'https://www.aiworkdeck.com', JSON.stringify(linkAt2))
      const linkAt0 = await exec('debug_slide_char_prop', { slideNumber: 1, shapeName: tbShape.name, offset: 0, prop: 'HyperLinkURL' })
      check('命中范围外字符未受影响', linkAt0.value === baselineLink.value, JSON.stringify({ before: baselineLink.value, after: linkAt0.value }))
    } else {
      console.log('  [note] Impress 字符级超链接在本引擎构建上不支持（HyperLinkURL 未知属性），已按预期退化为形状级点击交互')
      const shapeInfo3 = await exec('debug_slide_shape_info', { slideNumber: 1, shapeName: tbShape.name })
      check('超链接落在形状级 OnClick=DOCUMENT', shapeInfo3.onClick === 6, JSON.stringify(shapeInfo3))
      check('超链接落在形状级 Bookmark=url', shapeInfo3.bookmark === 'https://www.aiworkdeck.com', JSON.stringify(shapeInfo3))
    }

    const shlBadUrl = await exec('slide_set_hyperlink', { slideNumber: 1, searchText: '内容', url: 'ftp://not-http' })
    check('slide_set_hyperlink 非 http(s) url 被拒绝', shlBadUrl.success === false && typeof shlBadUrl.error === 'string', JSON.stringify(shlBadUrl))
    const shlNoMatch = await exec('slide_set_hyperlink', { slideNumber: 1, searchText: '找不到的子串__xyz', url: 'https://example.com' })
    check('slide_set_hyperlink 未命中被拒绝', shlNoMatch.success === false && typeof shlNoMatch.error === 'string', JSON.stringify(shlNoMatch))

    // export → reload 往返：格式/表格/超链接改动都要保真（R1 风险的直接验证）
    const exp2 = await exec('export_document', { name: 'impress-format.pptx' })
    check('export_document 导出成功', exp2.success === true && exp2.size > 0, JSON.stringify({ success: exp2.success, size: exp2.size }))
    const expBytes2 = await page.evaluate(async () => {
      const r = await window.__loExecutor.executeCommand('export_document', { name: 'impress-format.pptx' })
      return r && r.bytes ? Array.from(r.bytes) : null
    })
    check('导出字节可安全带出浏览器边界', Array.isArray(expBytes2) && expBytes2.length > 0, String(expBytes2 && expBytes2.length))
    const ld2 = await exec('load_document', { bytes: expBytes2, name: 'impress-format-roundtrip.pptx', authorName: '测试用户' })
    check('重新打开导出的 pptx 成功', ld2.success === true && ld2.kind === 'impress', JSON.stringify(ld2))

    const p1r = await exec('slide_get_page', { slideNumber: 1 })
    const tbR = p1r.shapes.find((s) => s.name === tbShape.name)
    check('往返后文本框格式仍在（加粗）', !!tbR && tbR.format && tbR.format.bold === true, JSON.stringify(tbR && tbR.format))
    if (shl.via === 'HyperLinkURL') {
      const linkAt5R = await exec('debug_slide_char_prop', { slideNumber: 1, shapeName: tbShape.name, offset: 5, prop: 'HyperLinkURL' })
      check('往返后超链接仍在（字符级）', linkAt5R.value === 'https://www.aiworkdeck.com', JSON.stringify(linkAt5R))
    } else {
      const shapeInfo3R = await exec('debug_slide_shape_info', { slideNumber: 1, shapeName: tbShape.name })
      check('往返后超链接仍在（形状级）', shapeInfo3R.onClick === 6 && shapeInfo3R.bookmark === 'https://www.aiworkdeck.com', JSON.stringify(shapeInfo3R))
    }
    const trR = await exec('slide_table_read', { slideNumber: 1, shapeName: at.shapeName })
    check('往返后表格内容仍在', trR.success === true && trR.cells[1][1] === '高级合伙人', JSON.stringify(trR.cells))

    // 守卫：换回全新 Writer 文档，Phase 3 写类原语必须明确报错，不能抛 UNO 异常
    await exec('debug_fresh_document')
    const guardFmt = await exec('slide_format_text', { slideNumber: 1, shapeName: 'x', bold: true })
    check('Writer 文档上 slide_format_text 被明确拒绝', guardFmt.success === false && /演示文稿/.test(guardFmt.message || ''), JSON.stringify(guardFmt))
    check('Writer 守卫失败带 error 字段', typeof guardFmt.error === 'string' && guardFmt.error.length > 0, JSON.stringify(Object.keys(guardFmt)))
    const guardTable = await exec('slide_add_table', { slideNumber: 1, rows: 2, cols: 2 })
    check('Writer 文档上 slide_add_table 被明确拒绝', guardTable.success === false && /演示文稿/.test(guardTable.message || ''), JSON.stringify(guardTable))
  }

  // ---------- 组 24：自建工具栏命令层（P1）----------
  // 每条都断言**可观察效果**，不是「派发没报错」——工具栏上一个点了没反应的
  // 按钮就是体验退步。`.uno:Grow/Shrink` 正是因为这条规矩被剔出白名单的
  // （本引擎上派发成功但 CharHeight 纹丝不动）。
  console.log('\n[24] 自建工具栏命令层：状态回读 / 扩展 .uno: / 样式清单 / chrome 开关')
  {
    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '工具栏命令层回归段落。' })
    await exec('select_paragraph', {})

    const st = await exec('get_ui_state')
    check('get_ui_state 成功', st.success === true, JSON.stringify(st).slice(0, 200))
    check('回读字符状态', typeof st.character.bold === 'boolean' && st.character.sizePt > 0, JSON.stringify(st.character))
    check('回读段落样式与对齐', !!st.paragraph.styleName && !!st.paragraph.alignment, JSON.stringify(st.paragraph))
    check('回读缩放与修订开关', st.view.zoom > 0 && st.view.recordChanges === false, JSON.stringify(st.view))
    check('回读选区状态', st.selection.collapsed === false, JSON.stringify(st.selection))
    // 撤销可用性走 XUndoManagerSupplier 的方法（属性读法会抛 UnknownPropertyException）
    check('回读撤销/重做可用性', st.undo && typeof st.undo.canUndo === 'boolean', JSON.stringify(st.undo || st.undoErr))

    await exec('ui_command', { name: 'strikeout' })
    check('strikeout 生效', (await exec('get_ui_state')).character.strikeout === true)
    await exec('ui_command', { name: 'strikeout' })
    await exec('ui_command', { name: 'superscript' })
    check('superscript 生效', (await exec('get_ui_state')).character.superscript === true)
    await exec('ui_command', { name: 'superscript' })
    await exec('ui_command', { name: 'align_center' })
    check('align_center 生效', (await exec('get_ui_state')).paragraph.alignment === 'center')
    await exec('ui_command', { name: 'align_justify' })
    check('align_justify 生效', (await exec('get_ui_state')).paragraph.alignment === 'justify')
    await exec('ui_command', { name: 'align_left' })
    check('align_left 生效', (await exec('get_ui_state')).paragraph.alignment === 'left')
    await exec('ui_command', { name: 'bullet_list' })
    const bl = (await exec('get_ui_state')).paragraph
    check('bullet_list 识别为项目符号', bl.inList === true && bl.listKind === 'bullet', JSON.stringify(bl))
    await exec('ui_command', { name: 'bullet_list' })
    await exec('ui_command', { name: 'number_list' })
    check('number_list 识别为编号', (await exec('get_ui_state')).paragraph.listKind === 'number')
    await exec('ui_command', { name: 'number_list' })
    await exec('ui_command', { name: 'bold' })
    await exec('ui_command', { name: 'clear_formatting' })
    check('clear_formatting 清掉直接格式', (await exec('get_ui_state')).character.bold === false)
    check('白名单外的名字被拒绝', (await exec('ui_command', { name: 'font_grow' })).success === false)

    // 字号步进的宿主实现路线（.uno:Grow 是哑弹，工具栏靠这条）
    const sz0 = (await exec('get_ui_state')).character.sizePt
    await exec('format_selection', { fontSize: sz0 + 2 })
    check('字号步进（读回 sizePt → 写 fontSize）生效', (await exec('get_ui_state')).character.sizePt === sz0 + 2)
    await exec('format_selection', { fontSize: sz0 })

    check('set_track_changes 开', (await exec('set_track_changes', { on: true })).recordChanges === true)
    check('set_track_changes 关', (await exec('set_track_changes', { on: false })).recordChanges === false)
    check('set_track_changes 只读回读', (await exec('set_track_changes')).recordChanges === false)

    const ls = await exec('list_styles')
    check('list_styles 返回样式清单', ls.success === true && ls.count > 50, String(ls.count))
    const std = (ls.styles || []).find((s) => s.name === 'Standard')
    check('样式带显示名与在用标记', !!(std && std.display) && std.inUse === true, JSON.stringify(std))

    const hidden = await exec('set_chrome', { menubar: false, statusbar: false, toolbars: false, rulers: false })
    check('menubar 已隐藏', hidden.applied.menubar === false, JSON.stringify(hidden.applied.menubar))
    check('statusbar 已隐藏', hidden.applied.statusbar === false)
    check('两条主工具栏已隐藏',
      hidden.applied.toolbars.standardbar === false && hidden.applied.toolbars.textobjectbar === false,
      JSON.stringify(hidden.applied.toolbars))
    check('上下文工具栏也一并隐藏（否则选中表格就冒出来）',
      hidden.applied.toolbars['singlemode-table'] === false, JSON.stringify(hidden.applied.toolbars))
    check('标尺已关', hidden.applied.rulers.ShowHoriRuler === false, JSON.stringify(hidden.applied.rulers))
    // chrome 全隐藏之后功能不许退步
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: 'chrome 隐藏后仍可编辑' })
    check('隐藏后仍可编辑', (await doc()).indexOf('仍可编辑') >= 0, await doc())
    check('隐藏后状态回读仍可用', (await exec('get_ui_state')).success === true)
    check('隐藏后缩放仍可用', (await exec('set_zoom', { value: 130 })).zoom === 130)
    check('menubar 可恢复（逃生开关）', (await exec('set_chrome', { menubar: true })).applied.menubar === true)
    await exec('set_zoom', { value: 100 })
    await exec('set_chrome', { statusbar: true, toolbars: true, rulers: true })
  }

  // ---------- 组 25：插入菜单与自建查找替换（P2b / P3）----------
  // 查找替换刻意**不走** `.uno:SearchDialog`：真机审计实证那个对话框弹得出来但
  // 键盘关不掉（画布聚焦时按 Esc 同样无效），挂进菜单就是个坑。
  console.log('\n[25] 插入菜单与自建查找替换：批注 / 表格 / 链接 / 查找导航')
  {
    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '合同由甲方与乙方签署，甲方承担交付义务，乙方承担付款义务。' })

    // 批注：作用于当前选区、署名用户本人（AI 管线的 add_comment 走 anchor + AI 署名）
    await exec('select_paragraph', {})
    const cmt = await exec('add_comment_at_selection', { comment: '此处需要复核' })
    check('add_comment_at_selection 成功', cmt.success === true, JSON.stringify(cmt).slice(0, 160))
    const cl = await exec('list_comments')
    check('批注真的进了文档', (cl.comments || []).some((c) => c.content === '此处需要复核'), JSON.stringify(cl).slice(0, 200))
    await exec('collapse_selection', {})
    const cmtEmpty = await exec('add_comment_at_selection', { comment: 'x' })
    check('空选区加批注被明确拒绝', cmtEmpty.success === false && /选中/.test(cmtEmpty.message || ''), JSON.stringify(cmtEmpty))

    // 表格：工具栏的网格选择器给的就是这种空内容矩阵
    await exec('goto', { type: 'end' })
    const tbl = await exec('insert_table', { rows: [['', '', ''], ['', '', '']], headerRow: false })
    check('网格选择器路线插表成功', tbl.success === true && tbl.rows === 2 && tbl.cols === 3, JSON.stringify(tbl).slice(0, 140))

    // 超链接：作用于当前选区
    await exec('goto', { type: 'end' })
    await exec('insert_at_cursor', { text: '官网' })
    await exec('select_paragraph', {})
    const lk = await exec('set_selection_hyperlink', { url: 'https://www.aiworkdeck.com' })
    check('选区加超链接成功', lk.success === true && lk.url === 'https://www.aiworkdeck.com', JSON.stringify(lk).slice(0, 140))
    // insert_link_with_bookmark 的 scheme 校验（P1 复核 F3）：http(s)/checkba 放行，其它双字段拒绝
    await exec('goto', { type: 'end' })
    const lbOk = await exec('insert_link_with_bookmark', { text: '底稿', url: 'https://checkba-internal.local/open?u=checkba%3A%2F%2Ffilelink%3Fk%3Dlk_p1', bookmarkName: 'LK_P1' })
    check('insert_link_with_bookmark 放行 https 包装链接', lbOk.success === true && lbOk.bookmarkName === 'LK_P1', JSON.stringify(lbOk).slice(0, 160))
    const lbBare = await exec('insert_link_with_bookmark', { text: '裸', url: 'checkba://filelink?k=lk_p1b', bookmarkName: 'LK_P1B' })
    check('insert_link_with_bookmark 放行裸 checkba://', lbBare.success === true, JSON.stringify(lbBare).slice(0, 160))
    const lbBad = await exec('insert_link_with_bookmark', { text: '坏', url: 'javascript:alert(1)' })
    check('insert_link_with_bookmark 拒绝 javascript: 且 error+message 双字段', lbBad.success === false && !!lbBad.error && !!lbBad.message, JSON.stringify(lbBad).slice(0, 160))

    // 查找导航：全程 findFirst/findNext，**不留书签**（书签会跟着存进 docx）
    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '甲方甲方甲方' })
    await exec('goto', { type: 'start' })
    const f1 = await exec('find_navigate', { keyword: '甲方', direction: 'next' })
    check('find_navigate 找到第 1 处', f1.found === true && f1.index === 1 && f1.total === 3, JSON.stringify(f1))
    const f2 = await exec('find_navigate', { keyword: '甲方', direction: 'next' })
    check('下一个 → 第 2 处', f2.index === 2, JSON.stringify(f2))
    const f3 = await exec('find_navigate', { keyword: '甲方', direction: 'prev' })
    check('上一个 → 回到第 1 处', f3.index === 1, JSON.stringify(f3))
    const f4 = await exec('find_navigate', { keyword: '甲方', direction: 'prev' })
    check('第 1 处再上一个 → 绕回最后一处', f4.index === 3, JSON.stringify(f4))
    const f0 = await exec('find_navigate', { keyword: '不存在的词', direction: 'next' })
    check('查无匹配如实回报', f0.success === true && f0.found === false && f0.total === 0, JSON.stringify(f0))
    check('选中的确实是匹配文本', (await exec('get_selection')).text === '甲方')
    // 查完不许在文档里留锚点书签——书签会跟着文档存进 docx。clear_anchors 回报
    // 清掉了几个，0 才算真的没留（find_text_locations 那条路会留一堆）。
    const anchors = await exec('clear_anchors', {})
    check('查找没有留下锚点书签', anchors.cleared === 0, JSON.stringify(anchors))

    // 区分大小写：新加的 matchCase 走通（find_navigate 与 find_replace 都要认）
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: 'Party party PARTY' })
    await exec('goto', { type: 'start' })
    const cs = await exec('find_navigate', { keyword: 'party', direction: 'next', matchCase: true })
    check('区分大小写只匹配 1 处', cs.total === 1, JSON.stringify(cs))
    const ci = await exec('find_navigate', { keyword: 'party', direction: 'next', matchCase: false })
    check('不区分大小写匹配 3 处', ci.total === 3, JSON.stringify(ci))
    await exec('debug_set_record_changes', { on: false })
    const ra = await exec('find_replace', { findText: 'party', replaceText: '当事人', replaceAll: true, matchCase: true })
    check('find_replace 认 matchCase（只替 1 处）', ra.replaced === 1, JSON.stringify(ra))
  }

  // ---------- 组 26：表格上下文操作 + 脚注页眉 + chrome 退场（P4 前置）----------
  console.log('\n[26] 表格相对操作 / 脚注尾注页眉页脚 / LO chrome 退场')
  {
    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '表格上下文操作验证' })
    await exec('goto', { type: 'end' })
    await exec('insert_table', { rows: [['a1', 'b1', 'c1'], ['a2', 'b2', 'c2'], ['a3', 'b3', 'c3']], headerRow: false })

    // 光标进表格：get_ui_state 要回报所在单元格，工具栏靠它算相对行列
    const t0 = await exec('find_navigate', { keyword: 'b2', direction: 'next' })
    check('光标定位到表格单元格', t0.found === true, JSON.stringify(t0))
    const inCell = await exec('get_ui_state')
    check('get_ui_state 回报在表格内', inCell.selection.inTable === true, JSON.stringify(inCell.selection))
    check('get_ui_state 回报单元格名', /^[A-Z]+\d+$/.test(inCell.selection.cellName || ''), JSON.stringify(inCell.selection))

    // 「在上方插入行」= position 取当前行（原语语义：插在该行之前）
    const cell = inCell.selection.cellName
    const row = Number(/\d+$/.exec(cell)[0])
    const before = await exec('table_read', {})
    const rowsBefore = (before.cells || []).length
    const addAbove = await exec('table_add_row', { position: row })
    check('在上方插入行成功', addAbove.success === true && addAbove.rows === rowsBefore + 1, JSON.stringify(addAbove).slice(0, 140))
    const addBelow = await exec('table_add_row', { position: row + 2 })
    check('在下方插入行成功', addBelow.success === true, JSON.stringify(addBelow).slice(0, 120))
    const addCol = await exec('table_add_col', { position: 2 })
    check('在左侧插入列成功', addCol.success === true, JSON.stringify(addCol).slice(0, 120))
    const delRow = await exec('table_delete_row', { position: row })
    check('删除本行成功', delRow.success === true, JSON.stringify(delRow).slice(0, 120))
    const delCol = await exec('table_delete_col', { position: 2 })
    check('删除本列成功', delCol.success === true, JSON.stringify(delCol).slice(0, 120))

    // 脚注 / 尾注 / 页眉 / 页脚：插入菜单里的四条，都走已有原语
    await exec('debug_fresh_document')
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '脚注与页眉验证段落' })
    await exec('goto', { type: 'end' })
    check('插入脚注成功', (await exec('insert_footnote', { text: '见《公司法》第二十条' })).success === true)
    // 尾注：本引擎构建**不支持**（IsEndnote 抛 IllegalArgumentException）。所以
    // 工具栏里刻意没有这一项——做不到的不放按钮。这里锁住「明确拒绝且给得出
    // 可读原因」，将来引擎支持了这条会红，提醒把菜单项加回去。
    const en = await exec('insert_endnote', { text: '尾注内容' })
    check('尾注被明确拒绝且说明原因', en.success === false && /尾注/.test(en.message || ''), JSON.stringify(en).slice(0, 160))
    check('设置页眉成功', (await exec('edit_header_footer', { target: 'header', text: '金冠纾困项目' })).success === true)
    check('设置页脚成功', (await exec('edit_header_footer', { target: 'footer', text: '第 1 页' })).success === true)
    check('空内容脚注被拒绝', (await exec('insert_footnote', { text: '' })).success === false)

    // chrome 退场：自建工具栏挂上之后 LO 那套要全部藏起来，且**选中表格时
    // 上下文工具栏不许再钻出来**（引擎会自己拉起 singlemode-table）
    // 必须复位：insert_footnote 之后视图光标停在脚注区里，不换文档的话下面的
    // select_all / replace_selection 会全打在脚注上（本组首次跑就是这么挂的）。
    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: 'chrome 退场验证' })
    await exec('set_chrome', { menubar: false, statusbar: false, toolbars: false, rulers: false })
    // 先验「藏完还能干活」，再插表——插完表光标就落在单元格里了，那之后
    // goto start / select_all 都只在该单元格内生效，而 get_document_text 只读
    // 正文，看起来就像"编辑没生效"（本组连挂两轮就是栽在这）。
    await exec('goto', { type: 'end' })
    await exec('insert_at_cursor', { text: '退场后依然可编辑' })
    check('chrome 退场后仍可编辑', (await doc()).indexOf('依然可编辑') >= 0, await doc())
    await exec('goto', { type: 'end' })
    await exec('insert_table', { rows: [['x', 'y'], ['1', '2']], headerRow: false })
    await exec('find_navigate', { keyword: 'x', direction: 'next' })
    const vis = await exec('set_chrome', {})   // 无字段 = 只读查询
    check('查询模式回报可见性', vis.success === true && !!vis.visible, JSON.stringify(vis).slice(0, 120))
    check('菜单栏保持隐藏', vis.visible.menubar === false, JSON.stringify(vis.visible.menubar))
    check('状态栏保持隐藏', vis.visible.statusbar === false)
    check('标尺保持关闭', vis.visible.rulers.ShowHoriRuler === false, JSON.stringify(vis.visible.rulers))
    check('选中表格后上下文工具栏没冒出来',
      vis.visible.toolbars['singlemode-table'] === false, JSON.stringify(vis.visible.toolbars))
    check('主工具栏保持隐藏',
      vis.visible.toolbars.standardbar === false && vis.visible.toolbars.textobjectbar === false,
      JSON.stringify(vis.visible.toolbars))
    // 逃生阀：一键把原生菜单要回来
    const back = await exec('set_chrome', { menubar: true, statusbar: true, toolbars: true, rulers: true })
    check('逃生开关能把原生菜单要回来', back.applied.menubar === true, JSON.stringify(back.applied.menubar))
    await exec('set_chrome', { menubar: false, statusbar: false, toolbars: false, rulers: false })
    const off = await exec('set_chrome', {})
    check('再次隐藏仍然生效', off.visible.menubar === false, JSON.stringify(off.visible.menubar))
    await exec('set_chrome', { menubar: true, statusbar: true, toolbars: true, rulers: true })
  }

  // ---------- 组 27：EvidenceLink 证据锚点——书签原语五件套（dev-board#103）----------
  // 书签名 = linkKey：bookmark_selection / get_bookmark_context / check_link_anchors /
  // adopt_legacy_links / goto_bookmark。每条事实必有底稿，底稿挂在书签上跟着文字走。
  console.log('\n[27] EvidenceLink 证据锚点：书签五原语')
  {
    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    await exec('ui_command', { name: 'select_all' })
    await exec('replace_selection', { text: '一、主体资格\n根据《营业执照》，收购人成立于2020年。\n（一）基本情况\n收购人注册资本1000万元。' })
    await exec('select_paragraph', { index: 0 })
    await exec('set_paragraph_format', { headingLevel: 1 })
    await exec('select_paragraph', { index: 2 })
    await exec('set_paragraph_format', { headingLevel: 2 })
    const ol = await exec('get_outline')
    check('标题层级就位（1 级 + 2 级）', ol.count === 2 && ol.outline[0].level === 1 && ol.outline[1].level === 2, JSON.stringify(ol))
    const selectText = async (kw) => {
      const ft = await exec('find_text_locations', { keyword: kw })
      if (!ft.success || ft.count < 1) return ft
      return exec('set_selection', { anchor: ft.matches[0].anchorId })
    }

    // 2. 选区套书签；重名精确拒绝（linkKey 不许悄悄加 _n 后缀）
    await selectText('收购人成立于2020年')
    const b1 = await exec('bookmark_selection', { name: 'EVID_TEST1' })
    check('bookmark_selection 成功且回显文字', b1.success === true && b1.name === 'EVID_TEST1' && b1.text === '收购人成立于2020年', JSON.stringify(b1))
    const dup = await exec('bookmark_selection', { name: 'EVID_TEST1' })
    check('同名书签被拒绝（error 含 exists，双字段）', dup.success === false && /exists/.test(dup.error || '') && dup.message === dup.error, JSON.stringify(dup))
    const badName = await exec('bookmark_selection', { name: 'bad-name!' })
    check('非法书签名被拒绝', badName.success === false && !!badName.error, JSON.stringify(badName))

    // 3. 上下文：标题链 + 段落索引（0 基）
    const c1 = await exec('get_bookmark_context', { name: 'EVID_TEST1' })
    check('get_bookmark_context 命中且文字前缀正确', c1.success === true && c1.exists === true && c1.text.indexOf('收购人成立于') === 0, JSON.stringify(c1))
    check('sectionPath 为一级标题', c1.sectionPath === '一、主体资格' && c1.sectionTitle === '一、主体资格', JSON.stringify(c1))
    check('paragraphIndex 为 1（0 基）', c1.paragraphIndex === 1, JSON.stringify(c1.paragraphIndex))
    const cNone = await exec('get_bookmark_context', { name: 'EVID_NOPE' })
    check('不存在的书签 exists=false 且 success', cNone.success === true && cNone.exists === false && cNone.paragraphIndex === -1, JSON.stringify(cNone))

    // 4. 二级标题下的选区：标题链两级拼接
    await exec('select_paragraph', { index: 3 })
    const b2 = await exec('bookmark_selection', { name: 'EVID_TEST2' })
    check('第二个书签成功', b2.success === true && b2.text === '收购人注册资本1000万元。', JSON.stringify(b2))
    const c2 = await exec('get_bookmark_context', { name: 'EVID_TEST2' })
    check('sectionPath 两级拼接', c2.exists === true && c2.sectionPath === '一、主体资格/（一）基本情况' && c2.sectionTitle === '（一）基本情况' && c2.paragraphIndex === 3, JSON.stringify(c2))

    // 5. 书签内部插字：书签随文字扩张，别的书签不动。光标先收到书签末端再左移
    // 一格（落在书签内部——正好在末端插入不会扩张书签）。
    await exec('goto_bookmark', { name: 'EVID_TEST1' })
    await exec('collapse_selection', { to: 'end' })
    await exec('move_cursor', { dir: 'left' })
    await exec('insert_at_cursor', { text: '（有限合伙）' })
    const ck1 = await exec('check_link_anchors', { names: ['EVID_TEST1', 'EVID_TEST2'] })
    const it1 = (ck1.items || []).find((x) => x.name === 'EVID_TEST1') || {}
    const it2 = (ck1.items || []).find((x) => x.name === 'EVID_TEST2') || {}
    check('check_link_anchors 返回两条', ck1.success === true && (ck1.items || []).length === 2, JSON.stringify(ck1))
    check('书签内插字后 TEST1.text 含新字', it1.exists === true && it1.text.indexOf('（有限合伙）') >= 0, JSON.stringify(it1))
    check('TEST2 不受影响', it2.exists === true && it2.text === '收购人注册资本1000万元。', JSON.stringify(it2))

    // 6. 整段文字删除：书签成孤儿。真机实测（2026-08-21）：LO 把书签连同文字一起
    // 删掉，exists:false（不是留空点书签）。宿主侧仍以「!exists || text===''」判
    // orphan（见 .claude/agents/doc-editor.md「EvidenceLink 书签原语」）。
    await exec('select_paragraph', { index: 3 })
    await focus()
    await key('Backspace', 'Backspace', 8)
    const ck2 = await exec('check_link_anchors', { names: ['EVID_TEST2'] })
    const gone = (ck2.items || [])[0] || {}
    check('整段删除后 TEST2 书签随之消失（exists=false）', gone.name === 'EVID_TEST2' && gone.exists === false && gone.text === '', JSON.stringify(ck2))
    check('整段删除后正文不再含原句', (await doc()).indexOf('注册资本1000万元') < 0, await doc())

    // 7. 旧式超链接（filelink?k=）收编为书签
    await exec('goto', { type: 'end' })
    await exec('insert_at_cursor', { text: '收购人注册资本1000万元。' })
    await selectText('注册资本')
    const hl = await exec('set_selection_hyperlink', { url: 'https://checkba-internal.local/open?u=checkba%3A%2F%2Ffilelink%3Fk%3Dlk_old_1' })
    check('旧式超链接已设置', hl.success === true && hl.text === '注册资本', JSON.stringify(hl))
    const ad1 = await exec('adopt_legacy_links', {})
    check('adopt_legacy_links 收编 lk_old_1', ad1.success === true && (ad1.adopted || []).indexOf('lk_old_1') >= 0, JSON.stringify(ad1))
    const ad2 = await exec('adopt_legacy_links', {})
    check('再次收编幂等（adopted 空、skipped>=1）', ad2.success === true && (ad2.adopted || []).length === 0 && ad2.skipped >= 1, JSON.stringify(ad2))
    const cOld = await exec('get_bookmark_context', { name: 'lk_old_1' })
    check('收编后的书签文字 = 链接文字', cOld.exists === true && cOld.text === '注册资本', JSON.stringify(cOld))
    // 7b. 生产 URL 形态：整体 encodeURIComponent、带 &projectId=——key 必须恰等于原 key，
    // 不许把 %26projectId%3D42 吞进去改写成 lk_123_abc_projectId_42
    await exec('goto', { type: 'end' })
    await exec('insert_at_cursor', { text: '\n经营范围为软件开发。' })
    await selectText('经营范围')
    const prodUrl = 'https://checkba-internal.local/open?u=' + encodeURIComponent('checkba://filelink?k=lk_123_abc&projectId=42')
    await exec('set_selection_hyperlink', { url: prodUrl })
    const ad3 = await exec('adopt_legacy_links', {})
    check('生产 URL 形态收编 key 恰等于 lk_123_abc', ad3.success === true && JSON.stringify(ad3.adopted) === '["lk_123_abc"]', JSON.stringify(ad3))
    check('收编后书签文字 = 经营范围', (await exec('get_bookmark_context', { name: 'lk_123_abc' })).text === '经营范围')
    // 7c. 非法 key（后端兜底 lk_<UUID> 带 -）：跳过并计入 skippedInvalid，不静默改写
    await exec('goto', { type: 'end' })
    await exec('insert_at_cursor', { text: '\n法定代表人为张三。' })
    await selectText('法定代表人')
    await exec('set_selection_hyperlink', { url: 'https://checkba-internal.local/open?u=' + encodeURIComponent('checkba://filelink?k=lk_a-b&projectId=42') })
    const ad4 = await exec('adopt_legacy_links', {})
    check('带 - 的 key 进 skippedInvalid 且不收编', ad4.success === true && ad4.skippedInvalid === 1 && (ad4.adopted || []).length === 0, JSON.stringify(ad4))
    const ck4 = await exec('check_link_anchors', { names: ['lk_a-b', 'lk_a_b'] })
    check('非法 key 没被改写成别名落成书签', ck4.items.every((x) => x.exists === false) && ck4.truncated === false, JSON.stringify(ck4))

    // 8. docx 往返：书签经 export/load 存活
    await exec('clear_anchors', {})
    const evBytes = await page.evaluate(async () => {
      const r = await window.__loExecutor.executeCommand('export_document', { name: 'evidence.docx' })
      return r && r.bytes ? Array.from(r.bytes) : null
    })
    check('导出字节非空', !!evBytes && evBytes.length > 0)
    const ld = await exec('load_document', { bytes: evBytes, name: 'evidence-roundtrip.docx', authorName: '测试用户' })
    check('重新载入成功', ld.success === true, JSON.stringify(ld).slice(0, 160))
    const ck3 = await exec('check_link_anchors', { names: ['EVID_TEST1', 'lk_old_1'] })
    check('往返后两枚书签都在', ck3.success === true && (ck3.items || []).length === 2 && ck3.items.every((x) => x.exists === true), JSON.stringify(ck3))
    check('往返后 TEST1 文字仍含插入字', ((ck3.items || [])[0] || {}).text.indexOf('（有限合伙）') >= 0, JSON.stringify(ck3))

    // 9. 跳转：选中书签范围；不存在的书签明确拒绝
    const g1 = await exec('goto_bookmark', { name: 'EVID_TEST1' })
    check('goto_bookmark 成功', g1.success === true, JSON.stringify(g1))
    check('跳转后选区即书签文字', ((await exec('get_selection')).text || '').indexOf('收购人成立于') === 0, JSON.stringify(await exec('get_selection')))
    const g0 = await exec('goto_bookmark', { name: 'NOPE' })
    check('不存在的书签跳转被拒绝（双字段）', g0.success === false && !!g0.error && g0.message === g0.error, JSON.stringify(g0))
  }

  console.log('== 28) 批量命令分批 / 进度 / 取消：取消后锁平衡、文档仍可编辑、modified 仍触发（dev-board#108 复核） ==')
  {
    // 纯插入型替换（甲乙→甲丙乙，零宽匹配引擎不认）走逐命中分批路径：150 命中 > 50，
    // 按 30 一批，批间发 progress / 查 cancel。第一帧 progress 到达就喊停，
    // 命令应在后面的某个批间检查点停下。
    const lines = []
    for (let i = 0; i < 150; i++) lines.push('甲乙。')
    await reset(lines.join('\n'), true)
    const st0 = await exec('debug_lock_state')
    check('起跑时锁全空', st0.controllersLocked === false && st0.lockDepth === 0 && st0.modifySuspended === 0, JSON.stringify(st0))
    const bt = await page.evaluate(async () => {
      const prog = []
      let issued = null, cancelSent = false, cancelRes = null
      const res = await window.__loExecutor.executeCommand('find_replace', { findText: '甲乙', replaceText: '甲丙乙', replaceAll: true, __agent: true }, {
        onIssued: (id) => { issued = id },
        onProgress: (p) => {
          prog.push({ done: p.done, total: p.total })
          if (!cancelSent && issued) {
            cancelSent = true
            window.__loExecutor.executeCommand('cancel', { reqId: issued }).then((r) => { cancelRes = r })
          }
        },
      })
      await new Promise((r) => setTimeout(r, 200))
      return { res, prog, cancelRes }
    })
    check('progress 帧至少两帧且 done 单调递增、total=150',
      bt.prog.length >= 2 && bt.prog.every((p) => p.total === 150) && bt.prog.every((p, i) => i === 0 || p.done > bt.prog[i - 1].done), JSON.stringify(bt.prog))
    check('cancel 对在飞命令生效（非 stale）', bt.cancelRes && bt.cancelRes.success === true && !bt.cancelRes.stale, JSON.stringify(bt.cancelRes))
    check('中途取消：cancelled=true 且 30 <= done < total', bt.res.success === true && bt.res.cancelled === true && bt.res.done >= 30 && bt.res.done < bt.res.total && bt.res.total === 150, JSON.stringify(bt.res))
    const txt = await doc()
    check('文档已改一部分（既有 甲丙乙 也有未改的 甲乙）', txt.indexOf('甲丙乙') !== -1 && /(^|\|)甲乙。/.test(txt), txt.slice(0, 80))
    const st1 = await exec('debug_lock_state')
    check('取消后锁平衡：控制器/动作锁都解开、监听器已装回、无残留 reqId',
      st1.controllersLocked === false && st1.lockDepth === 0 && st1.modifySuspended === 0 && st1.cancelledKeys === 0 && st1.inflightKeys === 1, JSON.stringify(st1))
    // 对已结束的 reqId 再喊停：stale，不在 CANCELLED 留痕
    const stale = await exec('cancel', { reqId: 'lo_finished_0' })
    check('对已结束 reqId 的 cancel 返回 stale 且不留痕', stale.success === true && stale.stale === true && (await exec('debug_lock_state')).cancelledKeys === 0, JSON.stringify(stale))
    // 取消后文档仍可编辑，且 modified 仍会触发（监听器确已装回）
    const m0 = (await exec('debug_modified_count')).count
    await exec('goto', { type: 'end' })
    const ins = await exec('insert_at_cursor', { text: '取消后继续编辑。' })
    const m1 = (await exec('debug_modified_count')).count
    check('取消后 insert_at_cursor 成功', ins.success === true, JSON.stringify(ins).slice(0, 120))
    check('取消后编辑仍触发 modified（自动保存链路未断）', m1 > m0, 'before=' + m0 + ' after=' + m1)
    check('取消后正文含新插入文字', (await doc()).indexOf('取消后继续编辑。') !== -1)
    // 不取消跑完：所有命中处理完，progress 末帧 done=total
    await reset(lines.join('\n'), true)
    const full = await page.evaluate(async () => {
      const prog = []
      const res = await window.__loExecutor.executeCommand('find_replace', { findText: '甲乙', replaceText: '甲丙乙', replaceAll: true, __agent: true }, { onProgress: (p) => prog.push(p.done) })
      return { res, prog }
    })
    check('不取消：replaced=150 且末帧 done=150', full.res.success === true && full.res.replaced === 150 && !full.res.cancelled && full.prog[full.prog.length - 1] === 150, JSON.stringify(full.res) + ' ' + JSON.stringify(full.prog))
    const st2 = await exec('debug_lock_state')
    check('跑完后锁平衡', st2.controllersLocked === false && st2.lockDepth === 0 && st2.modifySuspended === 0 && st2.inflightKeys === 1, JSON.stringify(st2))
  }

  // ---------- 组 29：样式画像（set/apply_style_profile / insert_toc / set_page_setup / 页码域）----------
  console.log('\n[29] 样式画像：换画像后流式落字与建表按画像 / 样式定义 / 目录 / 纸张 / 页码域（dev-board#111）')
  {
    await exec('debug_fresh_document')
    await exec('debug_set_record_changes', { on: false })
    // 测试画像：楷体 12 / 西文 Times New Roman / 无首行缩进 / 段后 18；一级标题 12 磅粗两端对齐；
    // 表格 9 号字、0.75 磅边框。与 house-default 逐项不同，读回能分辨"按了谁的"。
    const profile = {
      schemaVersion: 1, name: '测试画像',
      body: { font: { eastAsia: '楷体_GB2312', western: 'Times New Roman' }, size: { value: 12, unit: 'pt' }, alignment: 'justify',
        firstLineIndent: { value: 0, unit: 'pt' }, spaceBefore: { value: 0, unit: 'pt' }, spaceAfter: { value: 18, unit: 'pt' },
        lineSpacing: { rule: 'atLeast', value: 16, unit: 'pt' } },
      headings: [{ level: 1, size: { value: 12, unit: 'pt' }, bold: true, alignment: 'justify', firstLineIndent: { value: 0, unit: 'pt' } }],
      table: { cell: { size: { value: 9, unit: 'pt' } },
        borders: { outside: { style: 'single', width: { value: 0.75, unit: 'pt' }, color: '#000000' }, insideH: { style: 'single', width: { value: 0.75, unit: 'pt' }, color: '#000000' }, insideV: { style: 'single', width: { value: 0.75, unit: 'pt' }, color: '#000000' } } },
    }
    const bad = await exec('set_style_profile', { profile: { schemaVersion: 2 } })
    check('schemaVersion 2 被拒绝', bad.success === false && /schemaVersion/.test(bad.message || ''), JSON.stringify(bad))
    const sp = await exec('set_style_profile', { profile })
    check('set_style_profile 成功且 merge 到默认之上（西文改、中文沿用默认）',
      sp.success === true && sp.body.fontWestern === 'Times New Roman' && sp.body.fontAsian === '楷体_GB2312'
      && Math.abs(sp.body.firstLineIndentPt) < 0.2 && Math.abs(sp.body.spaceAfterPt - 18) < 0.2 && sp.headingLevels.length === 6, JSON.stringify(sp))
    // 流式落字 + markdown 建表按画像
    await exec('stream_insert', { text: '# 画像标题\n正文段落内容。\n| 项目 | 金额 |\n| --- | --- |\n| 咨询费 | 10000 |\n' })
    await exec('stream_flush', {})
    await exec('select_paragraph', { index: 0 })
    let fm = await exec('get_formatting')
    check('主标题按画像一级：12 磅粗、两端对齐（不再 16 磅居中）', fm.character.bold === true && Math.abs(fm.character.sizePt - 12) < 0.2 && fm.paragraph.alignment === 'justify', JSON.stringify({ c: fm.character, p: fm.paragraph.alignment }))
    await exec('select_paragraph', { index: 1 })
    fm = await exec('get_formatting')
    check('正文按画像：Times New Roman / 楷体 / 无首行缩进 / 段后 18',
      fm.character.fontWestern === 'Times New Roman' && fm.character.fontAsian === '楷体_GB2312'
      && Math.abs(fm.paragraph.firstLineIndentPt) < 0.2 && Math.abs(fm.paragraph.spaceAfterPt - 18) < 0.2, JSON.stringify({ c: fm.character, p: fm.paragraph }))
    let ti = await exec('debug_table_info', {})
    check('流式建表按画像：0.75 磅边框（26/100mm）、9 号字、表头仍加粗居中', ti.success && Math.abs((ti.borderWidth || 0) - 26) <= 1 && Math.abs(ti.a1SizePt - 9) < 0.2 && ti.a1Bold === true && ti.a1Centered === true, JSON.stringify(ti))
    // insert_table 同样按画像
    await exec('goto', { type: 'end' })
    const it = await exec('insert_table', { rows: [['a', 'b'], ['c', '1']], headerRow: true })
    ti = await exec('debug_table_info', { index: 1 })
    check('insert_table 按画像 9 号字 + 0.75 磅边框', it.success === true && Math.abs(ti.a1SizePt - 9) < 0.2 && Math.abs((ti.borderWidth || 0) - 26) <= 1, JSON.stringify(ti))
    // apply_style_profile：先造一个 Heading 1 段并打上 20 磅直接格式，套用后应回到画像的 12 磅粗
    await exec('goto', { type: 'end' })
    await exec('insert_at_cursor', { text: '第一章 总则' })
    const t29 = await exec('get_document_text')
    const lastIdx = t29.paragraphs.length - 1
    await exec('select_paragraph', { index: lastIdx })
    await exec('set_paragraph_format', { headingLevel: 1 })
    await exec('format_selection', { fontSize: 20, bold: false })
    const ap = await exec('apply_style_profile', { scope: 'document' })
    check('apply_style_profile 成功、truncated=false、改到 Standard/Heading 1/Table Contents 定义',
      ap.success === true && ap.truncated === false && ap.paragraphs >= 3 && ap.tables === 2
      && ['Standard', 'Heading 1', 'Table Contents', 'Table Heading'].every((n) => (ap.styles || []).includes(n)), JSON.stringify(ap).slice(0, 300))
    await exec('select_paragraph', { index: lastIdx })
    fm = await exec('get_formatting')
    check('套用后 Heading 1 段 12 磅且粗（直接格式 20 磅被画像覆盖）', Math.abs(fm.character.sizePt - 12) < 0.2 && fm.character.bold === true && fm.paragraph.styleName === 'Heading 1', JSON.stringify({ c: fm.character, s: fm.paragraph.styleName }))
    const sd = await exec('debug_para_style_info', { name: 'Standard' })
    const hd = await exec('debug_para_style_info', { name: 'Heading 1' })
    check('样式定义已改：Standard 12 磅 Times New Roman 无缩进；Heading 1 12 磅粗', sd.success && Math.abs(sd.sizePt - 12) < 0.2 && sd.fontWestern === 'Times New Roman' && sd.firstLineIndentMm === 0
      && hd.success && Math.abs(hd.sizePt - 12) < 0.2 && hd.bold === true, JSON.stringify({ sd, hd }))
    const so = await exec('apply_style_profile', { scope: 'styles-only' })
    check('styles-only 不碰正文（paragraphs=0）', so.success === true && so.paragraphs === 0 && so.tables === 0, JSON.stringify(so).slice(0, 200))
    const bs = await exec('apply_style_profile', { scope: 'nope' })
    check('非法 scope 被拒绝', bs.success === false, JSON.stringify(bs))
    const st29 = await exec('debug_lock_state')
    check('apply_style_profile 后锁平衡', st29.controllersLocked === false && st29.lockDepth === 0 && st29.modifySuspended === 0, JSON.stringify(st29))
    // insert_toc：文首插目录，大纲里的两个标题应进目录，正文前出现「目录」
    const toc = await exec('insert_toc', { levels: 2, title: '目录', position: 'start' })
    // 主标题（流式 # 首段）不带大纲级别，不进目录；Heading 1 段进
    check('insert_toc 成功且收进 Heading 1 段', toc.success === true && toc.entries >= 1 && /第一章 总则/.test(toc.text), JSON.stringify(toc))
    const d29 = await doc()
    check('目录出现在正文之前', d29.indexOf('目录') !== -1 && d29.indexOf('目录') < d29.indexOf('正文段落内容'), d29.slice(0, 120))
    // 页码域：页脚「第 {PAGE} 页 共 {NUMPAGES} 页」
    const hf = await exec('edit_header_footer', { target: 'footer', pageNumberPattern: '第 {PAGE} 页 共 {NUMPAGES} 页', align: 'center', fontSize: 9 })
    check('页脚页码域写入（2 枚域）', hf.success === true && hf.fields === 2, JSON.stringify(hf))
    const fi = await exec('debug_footer_info')
    check('页脚文本含数字且两种域各一枚', fi.success && /第 \d+ 页 共 \d+ 页/.test(fi.text) && fi.pageNumberFields === 1 && fi.pageCountFields === 1, JSON.stringify(fi))
    const hfBad = await exec('edit_header_footer', { target: 'header' })
    check('既无 text 也无 pageNumberPattern 被拒绝', hfBad.success === false, JSON.stringify(hfBad))
    // set_page_setup：横向 + 上边距 20mm，再改回 A4 纵向
    const pg = await exec('set_page_setup', { orientation: 'landscape', margins: { top: 20 } })
    check('横向后宽 > 高且上边距 20mm', pg.success === true && pg.page.landscape === true && pg.page.width > pg.page.height && Math.abs(pg.page.margins.top - 20) < 0.05, JSON.stringify(pg))
    const pg2 = await exec('set_page_setup', { width: 210, height: 297, orientation: 'portrait' })
    check('改回 A4 纵向', pg2.success === true && pg2.page.landscape === false && Math.abs(pg2.page.width - 210) < 0.05 && Math.abs(pg2.page.height - 297) < 0.05, JSON.stringify(pg2))
    check('无参数 set_page_setup 被拒绝', (await exec('set_page_setup', {})).success === false)
    // format_table 新参：双线红框 1.5/0.5 磅、表头底纹、重复表头、厘米列宽
    const ft = await exec('format_table', { tableIndex: 0, borderStyle: 'double', borderColor: '#FF0000', outsideBorderWidthPt: 1.5, insideBorderWidthPt: 0.5, headerFill: '#DDDDDD', repeatHeader: true })
    ti = await exec('debug_table_info', { index: 0 })
    check('format_table 新参落地：外 53 内 18、红色、双线、底纹、重复表头', ft.success === true && Math.abs(ti.borderWidth - 53) <= 1 && Math.abs(ti.innerWidth - 18) <= 1 && ti.borderColor === 0xFF0000 && ti.borderStyle === 3 && ti.a1Fill === 0xDDDDDD && ti.repeatHeadline === true, JSON.stringify({ ft, ti }))
    check('非法 borderStyle 被拒绝', (await exec('format_table', { tableIndex: 0, borderStyle: 'wavy' })).success === false)
    // 列宽：本引擎的 WASM 桥没注册 TableColumnSeparator（new 与读回再设回都抛 unregistered UNO type），
    // 锁住「明确拒绝且说明原因」；将来引擎支持了这条会红，提醒把工具描述里的能力加回去。
    const cw = await exec('format_table', { tableIndex: 0, columnWidthsCm: '3,5' })
    check('列宽被明确拒绝且说明引擎原因', cw.success === false && /不支持按列设宽/.test(cw.message || ''), JSON.stringify(cw))
    // format_selection.fontNameAsian 只改中文字体
    const body29 = await exec('find_text_locations', { keyword: '正文段落内容' })
    await exec('set_selection', { anchor: body29.matches[0].anchorId })
    const fs29 = await exec('format_selection', { fontNameAsian: '宋体' })
    fm = await exec('get_formatting')
    check('fontNameAsian 只改中文字体', fs29.success === true && fm.character.fontAsian === '宋体' && fm.character.fontWestern === 'Times New Roman', JSON.stringify(fm.character))
    // 复位画像，后续（以及下次复用本 worker 的组）回到 house-default
    const rs = await exec('set_style_profile', { reset: true })
    check('reset 回到 house-default（Arial / 首行 24 磅）', rs.success === true && rs.reset === true && rs.body.fontWestern === 'Arial' && Math.abs(rs.body.firstLineIndentPt - 24) < 1, JSON.stringify(rs))
  }

  console.log('\n结果 / result: ' + passed + ' passed, ' + failed + ' failed')
} finally {
  await browser.close()
  server.close()
}
process.exit(failed ? 1 : 0)

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// undo / redo 在 Calc（xlsx）与 Impress（pptx）上到底生不生效（dev-board#799 K19）。
//
// 为什么要单独验：worker 的 undo/redo 走的是 xModel.getUndoManager()，而 Calc /
// Impress 的写入原语（sheet_write_cells / slide_set_shape_text）是 UNO API 直写
// （cell.setString / shape.getText().setString），不是派发 .uno: 命令——LibreOffice
// 里这两条路进不进撤销栈是分开的事。放行判据是「引擎真的把内容改回去了」，不是
// 「action 回了 success」：每一步都回读文档内容比对，返回 ok 而内容没变一律判失败。
//
// Run:  npm run test:lowa-undo-redo     (from frontend/)
// Env:  同 _boot.mjs（LOWA_ENGINE_DIR / PUPPETEER_EXECUTABLE_PATH / LOWA_E2E_PORT）
import fs from 'node:fs'
import path from 'node:path'
import JSZip from 'jszip'
import { here, preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

// 最小 xlsx：一张 Sheet1，A1 是内联字符串。仓里没有 xlsx 夹具，现造一份比塞一个
// 二进制夹具进库干净（review-gutter.mjs 造 docx 是同一套路）。
async function minimalXlsx(a1) {
  const NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
  const REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
  const zip = new JSZip()
  zip.file('[Content_Types].xml',
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    + '</Types>')
  zip.file('_rels/.rels',
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    + '<Relationship Id="rId1" Type="' + REL + '/officeDocument" Target="xl/workbook.xml"/></Relationships>')
  zip.file('xl/workbook.xml',
    '<workbook xmlns="' + NS + '" xmlns:r="' + REL + '">'
    + '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>')
  zip.file('xl/_rels/workbook.xml.rels',
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    + '<Relationship Id="rId1" Type="' + REL + '/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
  zip.file('xl/worksheets/sheet1.xml',
    '<worksheet xmlns="' + NS + '"><sheetData><row r="1">'
    + '<c r="A1" t="inlineStr"><is><t>' + a1 + '</t></is></c>'
    + '</row></sheetData></worksheet>')
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}

preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
let passed = 0, failed = 0
function check(label, cond, detail) {
  if (cond) { passed++; console.log('  PASS ' + label) }
  else { failed++; console.log('  FAIL ' + label + (detail ? '  [' + detail + ']' : '')) }
}
try {
  const page = await openEditor(browser, { clipboard: false })
  page.on('pageerror', (e) => console.error('PAGE ERROR:', e.message))
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)

  // ---------- A. Calc（xlsx）----------
  console.log('\n[A] Calc（xlsx）：sheet_write_cells 之后 undo / redo')
  {
    const ORIG = '原值', EDIT = '新值'
    const ld = await exec('load_document', { bytes: await minimalXlsx(ORIG), name: 'undo-redo.xlsx', authorName: '测试用户' })
    check('load_document 打开 xlsx 且 kind=calc', ld.success === true && ld.kind === 'calc', JSON.stringify(ld))

    const a1 = async () => {
      const r = await exec('sheet_read_range', { range: 'A1:A1' })
      return r.success === true && r.rows && r.rows[0] ? r.rows[0][0] : '<read-failed:' + JSON.stringify(r) + '>'
    }
    check('写入前 A1 = ' + ORIG, (await a1()) === ORIG, await a1())

    const wr = await exec('sheet_write_cells', { startCell: 'A1', rows: [[EDIT]] })
    check('sheet_write_cells 写入成功', wr.success === true, JSON.stringify(wr))
    // 防空断言：改动必须真的落进文档，后面的 undo 才有东西可撤。
    check('写入后 A1 = ' + EDIT + '（改动确已落地）', (await a1()) === EDIT, await a1())

    const un = await exec('undo')
    const afterUndo = await a1()
    check('undo 返回 success', un.success === true && un.undone >= 1, JSON.stringify(un))
    check('undo 后 A1 真的回到 ' + ORIG + '（判据是内容，不是返回值）', afterUndo === ORIG, afterUndo)

    const re = await exec('redo')
    const afterRedo = await a1()
    check('redo 返回 success', re.success === true && re.redone >= 1, JSON.stringify(re))
    check('redo 后 A1 真的回到 ' + EDIT, afterRedo === EDIT, afterRedo)
  }

  // ---------- B. Impress（pptx）----------
  // 实测结论（2026-09-22，24.2.8-zhcn-r5）：Impress 上 undo **不生效**——
  // slide_set_shape_text 的 shape.getText().setString() 与文字光标 insertString
  // 都不往撤销栈里记一条（探针直读 getAllUndoActionTitles() 始终为空，文档的
  // modified 标志倒是翻了），um.undo() 直接抛 EmptyUndoStackException。
  // 本组因此锁的是**引擎现状 + 原语诚实拒绝**：内容一个字都没被改回去，而 undo
  // 明确回 success:false / 'nothing to undo'，不假装成功、不半途改坏。
  // 引擎（或写入原语改走 .uno: 派发）哪天真记了撤销栈，这几条会红——那正是
  // dev-board#799 K19 这道放行判据要重新过一遍的时候。
  console.log('\n[B] Impress（pptx）：slide_set_shape_text 之后 undo / redo')
  {
    const ORIG = '普通文本框内容', EDIT = '撤销测试文本'
    const pptxBytes = Array.from(fs.readFileSync(path.join(here, 'fixtures/impress-smoke.pptx')))
    const ld = await exec('load_document', { bytes: pptxBytes, name: 'undo-redo.pptx', authorName: '测试用户' })
    check('load_document 打开 pptx 且 kind=impress', ld.success === true && ld.kind === 'impress', JSON.stringify(ld))

    const p1 = await exec('slide_get_page', { slideNumber: 1 })
    const shape = (p1.shapes || []).find((s) => (s.text || '') === ORIG)
    check('第 1 页定位到文本框「' + ORIG + '」', !!(shape && shape.name), JSON.stringify(p1.shapes))

    const textOf = async () => {
      const r = await exec('slide_get_page', { slideNumber: 1 })
      const s = (r.shapes || []).find((x) => x.name === shape.name)
      return s ? (s.text || '') : '<shape-gone:' + JSON.stringify(r).slice(0, 200) + '>'
    }
    const sst = await exec('slide_set_shape_text', { slideNumber: 1, shapeName: shape.name, text: EDIT })
    check('slide_set_shape_text 写入成功', sst.success === true && sst.previousText === ORIG, JSON.stringify(sst))
    // 防空断言：同上，先证明改动真的落地——否则下面「撤不回来」是假绿。
    check('写入后形状文字 = ' + EDIT + '（改动确已落地）', (await textOf()) === EDIT, await textOf())

    const un = await exec('undo')
    const afterUndo = await textOf()
    check('undo 明确拒绝（撤销栈是空的，不假装成功）',
      un.success === false && un.undone === 0 && /nothing to undo/.test(un.message || ''), JSON.stringify(un))
    check('undo 之后形状文字仍是 ' + EDIT + '——Impress 上撤不回来（K19 放行判据：不通过）',
      afterUndo === EDIT, afterUndo)

    const re = await exec('redo')
    check('redo 同样明确拒绝', re.success === false && re.redone === 0 && /nothing to redo/.test(re.message || ''), JSON.stringify(re))
    check('redo 之后形状文字没有被动过', (await textOf()) === EDIT, await textOf())

    // 结构操作同样没进撤销栈（slide_add_page 的插页走 XDrawPages.insertNewByIndex，
    // 也是 API 直写）。这一条顺带守住「不会半途改坏」：拒绝了就一页都别动。
    const before = await exec('slide_get_overview')
    const add = await exec('slide_add_page', { position: 1 })
    check('slide_add_page 插页成功', add.success === true, JSON.stringify(add))
    const mid = await exec('slide_get_overview')
    check('插页后页数 +1（改动确已落地）', mid.slideCount === before.slideCount + 1, mid.slideCount + ' vs ' + before.slideCount)
    const un2 = await exec('undo')
    const after2 = await exec('slide_get_overview')
    check('结构操作后 undo 也明确拒绝', un2.success === false && un2.undone === 0, JSON.stringify(un2))
    check('拒绝之后页数原封不动（没有被撤掉一半）', after2.slideCount === mid.slideCount, after2.slideCount + ' vs ' + mid.slideCount)
  }

  console.log('\n结果 / result: ' + passed + ' passed, ' + failed + ' failed')
} finally {
  await browser.close()
  server.close()
}
process.exit(failed ? 1 : 0)

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1018：损坏 / 含非法 XML 字符的 docx 交给真引擎 load_document，必须在装载预算内
// 以明确错误（code DOC_REJECTED）返回，不能挂住；正常文档前后都照常能开。
//
// 夹具在内存里由 fixtures/flexmark-table.docx 重打包得到（不另存二进制）：
//   ctrl-char   word/document.xml 的正文里混进 U+0002（PDF 抽文字带出来、docx4j 原样写入的形态）
//   entity      同一位置写成 &#2;（XML 1.0 同样非法）
//   cell        U+0002 落在表格单元格里
//   truncated   zip 截掉后半截
//   crc         STORE 条目里改一个字节（CRC 不符）
// 实测结论（24.2.8-zhcn-r5）：不传 InteractionHandler 时引擎不弹模态框，loadComponentFromURL
// 约 0.1s 返回 null——所以断言「预算内报错」而不是「超时」。引擎哪天改成弹框挂住，本组会红。
//
// 跑法：LOWA_ENGINE_DIR=<引擎目录> node tests/lowa-e2e/load-rejected.mjs（无头）
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { readFileSync } from 'node:fs'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'
import { loadBudgetMs } from '../../src/utils/editorLoadFailure.js'

preflight()
const good = readFileSync(new URL('./fixtures/flexmark-table.docx', import.meta.url))
const repack = async (fn) => { const z = await JSZip.loadAsync(good); await fn(z); return Buffer.from(await z.generateAsync({ type: 'uint8array' })) }
const docXml = (z) => z.file('word/document.xml').async('string')
const bad = {
  'ctrl-char': await repack(async (z) => z.file('word/document.xml', (await docXml(z)).replace('<w:t>', '<w:t>\u0002坏字符'))),
  entity: await repack(async (z) => z.file('word/document.xml', (await docXml(z)).replace('<w:t>', '<w:t>&#2;坏字符'))),
  cell: await repack(async (z) => {
    const x = await docXml(z)
    const j = x.indexOf('<w:t>', x.indexOf('<w:tc>'))
    assert.ok(j > 0, '夹具里必须有表格单元格文字')
    z.file('word/document.xml', x.slice(0, j) + '<w:t>\u0002' + x.slice(j + 5))
  }),
  truncated: good.subarray(0, Math.floor(good.length / 2)),
  crc: await (async () => {
    const b = await repack(async (z) => z.file('word/document.xml', await docXml(z), { compression: 'STORE' }))
    const i = b.indexOf(Buffer.from('<w:body>'))
    assert.ok(i > 0, 'STORE 条目里必须能找到 <w:body>')
    b[i + 3] = 0x58
    return b
  })(),
}
// 还原病灶自检：非法字符确实写进去了（不然「报错」可能只是别的原因）
{
  const z = await JSZip.loadAsync(bad['ctrl-char'])
  assert.ok((await z.file('word/document.xml').async('string')).includes('\u0002'), 'ctrl-char 夹具必须真含 U+0002')
}

const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
let failed = 0
const check = (name, fn) => { try { fn(); console.log('  ok  ' + name) } catch (e) { failed++; console.log('  FAIL ' + name + ': ' + e.message) } }
try {
  const page = await openEditor(browser)
  const load = async (bytes, name = 'x.docx') => {
    const budget = loadBudgetMs(bytes.length)
    const t0 = Date.now()
    const r = await Promise.race([
      page.evaluate((b, n) => window.__loExecutor.executeCommand('load_document', { bytes: b, name: n })
        .then((x) => ({ success: x.success, code: x.code || null, message: x.message || null })), Array.from(bytes), name),
      new Promise((res) => setTimeout(() => res({ hung: true }), budget)),
    ])
    return Object.assign(r, { ms: Date.now() - t0, budget })
  }
  const g0 = await load(good)
  console.log('good', JSON.stringify(g0))
  check('正常夹具能打开', () => assert.equal(g0.success, true))
  for (const [k, bytes] of Object.entries(bad)) {
    const r = await load(bytes)
    console.log(k, JSON.stringify(r))
    check(k + '：预算内返回（不挂住）', () => assert.notEqual(r.hung, true, '超过 ' + r.budget + 'ms 仍无回音'))
    check(k + '：以 DOC_REJECTED 明确失败', () => { assert.equal(r.success, false); assert.equal(r.code, 'DOC_REJECTED') })
  }
  const g1 = await load(good)
  console.log('good-after', JSON.stringify(g1))
  check('拒收之后引擎仍可用，正常夹具照常打开', () => assert.equal(g1.success, true))
  const t = await page.evaluate(() => window.__loExecutor.executeCommand('table_read', { tableIndex: 0 }))
  check('打开的是真文档（表格可读）', () => assert.equal(t.success !== false && Array.isArray(t.cells), true))
} finally {
  await browser.close()
  server.close()
}
console.log(failed ? failed + ' 项失败' : '全部通过')
process.exit(failed ? 1 : 0)

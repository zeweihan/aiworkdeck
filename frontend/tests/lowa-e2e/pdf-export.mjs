#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真引擎回归（dev-board#886）：原生「文件→导出为→直接导出 PDF / 导出为 PDF…」与原生工具栏
// PDF 图标，点了必须有结果。
//
// 根因（本组第 1 步的基线探针实证，24.2.8-zhcn-r5）：这两条命令在 WASM 构建里起不来
// 文件选择器 / PDF 选项对话框，派发静默结束（ExportDirectToPDF 回 State=FAILURE、
// ExportToPDF 回 Result=false），不弹窗、不产出文件。修法：worker 的派发拦截器把两条
// 命令转成 export-pdf-request 发给宿主，宿主调 export_pdf 取 PDF 字节走应用自己的下载链路。
//
// 菜单项 / 工具栏图标走的是 frame 的派发链（与 DispatchHelper.executeDispatch 同一条），
// 所以这里用测试专用的 debug_dispatch_probe 在 frame 上派发，和用户点菜单同路径。
//
// Run:  npm run test:lowa-pdf-export   (from frontend/)
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

const probe = `
  debug_dispatch_probe(p) {
    const r = css.frame.DispatchHelper.create(context).executeDispatch(ctrl.getFrame(), p.url, '', 0, []);
    let state = null; try { state = r ? r.State : null; } catch (e) {}
    return { success: true, state: state };
  },
`
preflight()
const server = await startServer({ patchServed(url, buf) {
  if (url === '/office_thread.js') {
    const s = buf.toString().replace('const EXEC = {', 'const EXEC = {' + probe)
    if (!s.includes('debug_dispatch_probe(p)')) throw new Error('worker probe patch did not land')
    return Buffer.from(s)
  }
  if (url.startsWith('/assets/')) {
    const s = buf.toString()
    const t = s.replace('["update_comment",', '["debug_dispatch_probe","update_comment",')
    return Buffer.from(t)
  }
  return buf
} })
const browser = await launchBrowser(await loadPuppeteer())
let failures = 0
const check = (label, ok, detail = '') => {
  console.log((ok ? 'PASS  ' : 'FAIL  ') + label + (detail ? '  — ' + detail : ''))
  if (!ok) failures++
}
const settle = (ms = 800) => new Promise((r) => setTimeout(r, ms))
try {
  const page = await openEditor(browser, { clipboard: false })
  const exec = (a, p = {}) => page.evaluate((a2, p2) => window.__loExecutor.executeCommand(a2, p2), a, p)
  // verify 页没有宿主，传输把 lo-relay 发给自己（editor-main.js pickTransport）——在这里收
  await page.evaluate(() => {
    window.__relay = []
    window.addEventListener('message', (e) => { if (e.data && e.data.__lo === 'lo-relay') window.__relay.push(e.data.type) })
  })
  const requests = () => page.evaluate(() => window.__relay.filter((t) => t === 'export-pdf-request').length)
  const modifiedCount = () => page.evaluate(() => window.__relay.filter((t) => t === 'modified').length)

  await exec('insert_at_cursor', { text: 'PDF-MARKER-886 甲方应于十日内付款。' })
  await settle(1000)
  const textBefore = (await exec('get_document_text', {})).paragraphs.map((x) => x.text).join('\n')

  for (const url of ['.uno:ExportDirectToPDF', '.uno:ExportToPDF']) {
    const before = await requests()
    const r = await exec('debug_dispatch_probe', { url })
    await settle(600)
    check(url + '：派发后宿主收到导出请求（不再静默结束）', (await requests()) === before + 1, JSON.stringify(r) + ' requests=' + (await requests()))
  }

  // 宿主收到请求后做的事（LibreOfficeEditor.exportPdf）：export_pdf 取字节
  const modBefore = await modifiedCount()
  const pdf = await page.evaluate(async () => {
    const r = await window.__loExecutor.executeCommand('export_pdf', {})
    const b = r && r.bytes ? new Uint8Array(r.bytes.buffer || r.bytes, r.bytes.byteOffset || 0, r.bytes.byteLength || r.bytes.length) : null
    return { success: r && r.success, size: b ? b.length : 0, head: b ? String.fromCharCode(...b.slice(0, 5)) : '', tail: b ? String.fromCharCode(...b.slice(-6)) : '', message: r && r.message }
  })
  check('export_pdf 产出 PDF 字节（%PDF- 头、%%EOF 尾）', pdf.success && pdf.head === '%PDF-' && /%%EOF/.test(pdf.tail) && pdf.size > 1000, JSON.stringify(pdf))
  await settle(1200)
  check('导出 PDF 不改文档内容', (await exec('get_document_text', {})).paragraphs.map((x) => x.text).join('\n') === textBefore)
  check('导出 PDF 不把文档标脏（不会顺带触发一轮自动保存）', (await modifiedCount()) === modBefore, 'modified relays +' + ((await modifiedCount()) - modBefore))
  const after = await exec('insert_at_cursor', { text: '续' })
  check('导出后照常可编辑', after && after.success !== false, JSON.stringify(after))
  await settle(1200)
  check('防空断言：真编辑确实会上报 modified（上面的「+0」才有意义）', (await modifiedCount()) > modBefore)
} finally {
  await browser.close()
  server.close()
}
console.log(failures ? '\n' + failures + ' FAILED' : '\nall passed')
process.exit(failures ? 1 : 0)

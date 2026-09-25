// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board#886 宿主半边：worker 把原生「导出为 PDF」拦成 export-pdf-request 之后，
// LibreOfficeEditor 必须真的接住——取 export_pdf 字节、走 blob + a[download] 下载、
// 成功失败都给提示。引擎半边（拦截 + PDF 字节）由 tests/lowa-e2e/pdf-export.mjs 真引擎守。
// 还原病灶（删掉 subscribeHostEvents 里 export-pdf-request 分支，或 exportPdf 不再点下载链接）即转红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { watchDownloadDone, displayName } from '../../src/utils/downloadDone.js'

const SRC = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const BODY = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')

function setup({ result, throws, fs, localPath } = {}) {
  const toasts = [], clicks = [], sent = []
  globalThis.uni = { showToast: (o) => toasts.push(o.title), showLoading() {}, hideLoading() {} }
  // BUG-34：exportPdf() 的方法体是从源码剥了 import 之后现场求值的，host /
  // getFileLocalPath / watchDownloadDone / displayName 在这个作用域里本该是模块顶层的
  // import，这里挂到 globalThis 上。默认 fs 是空壳，等价于浏览器态（没有桌面桥）。
  globalThis.host = { fs: fs || {} }
  globalThis.getFileLocalPath = localPath || (async () => ({ data: null }))
  globalThis.watchDownloadDone = watchDownloadDone
  globalThis.displayName = displayName
  globalThis.URL.createObjectURL = (blob) => { clicks.push({ blobType: blob.type, size: blob.size }); return 'blob:x' }
  globalThis.URL.revokeObjectURL = () => {}
  globalThis.document = {
    createElement: () => ({ click() { clicks[clicks.length - 1].download = this.download; clicks[clicks.length - 1].clicked = true }, remove() {} }),
    body: { appendChild() {} },
  }
  // setTimeout 桩掉：exportPdf 60s 后才 revokeObjectURL，真定时器会把测试进程拖住 60s
  const options = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'setTimeout', BODY)(null, null, null, () => 0)
  const vm = {
    file: { id: 7, name: '甲版合同.docx' },
    ready: true,
    appendLog() {},
    $t: (k, p) => k + (p ? JSON.stringify(p) : ''),
    executor: { executeCommand: async (a, p) => { sent.push(a); if (throws) throw new Error(throws); return result } },
  }
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  return { vm, toasts, clicks, sent }
}

test('export-pdf-request 消息接到 exportPdf', () => {
  assert.match(SRC, /msg\.type === 'export-pdf-request'\) \{\s*this\.exportPdf\(\)/)
})

test('拿到 PDF 字节：以「原名.pdf」下载，并提示成功', async () => {
  const bytes = new Uint8Array([37, 80, 68, 70, 45, 49])
  const { vm, toasts, clicks, sent } = setup({ result: { success: true, bytes } })
  assert.equal(await vm.exportPdf(), true)
  assert.deepEqual(sent, ['export_pdf'])
  assert.equal(clicks.length, 1)
  assert.equal(clicks[0].clicked, true)
  assert.equal(clicks[0].download, '甲版合同.pdf')
  assert.equal(clicks[0].blobType, 'application/pdf')
  assert.equal(clicks[0].size, 6)
  assert.match(toasts[0], /^editor\.pdfExported/)
})

test('引擎失败或超时：不下载，给出失败原因（不许点了没反应）', async () => {
  const a = setup({ result: { success: false, message: '当前文档类型不支持导出 PDF' } })
  assert.equal(await a.vm.exportPdf(), false)
  assert.equal(a.clicks.length, 0)
  assert.match(a.toasts[0], /^editor\.pdfExportFailed.*不支持导出 PDF/)
  const b = setup({ throws: 'relay timeout' })
  assert.equal(await b.vm.exportPdf(), false)
  assert.match(b.toasts[0], /relay timeout/)
})

// ---------- v0.49.0 BUG-34：默认目录 + 成功提示时机（J1 复核后重做） ----------

/** 桌面桥桩：记录 setNextExportSource，onDownloadDone 可以手动触发 done。 */
function desktopFs() {
  const sources = [], listeners = new Set()
  return {
    sources,
    emit: (d) => { for (const fn of [...listeners]) fn(d) },
    listenerCount: () => listeners.size,
    setNextExportSource: async (p) => { sources.push(p) },
    onDownloadDone: (fn) => { listeners.add(fn); return () => listeners.delete(fn) },
  }
}
const PDF = { success: true, bytes: new Uint8Array([37, 80, 68, 70]) }
const tick = () => new Promise((r) => setImmediate(r))

test('桌面端：把源文件**完整绝对路径**交给主进程（Windows 反斜杠路径不在渲染层截）', async () => {
  const fs = desktopFs()
  const winPath = 'C:\\Users\\lawyer\\案件\\甲版合同.docx'
  const { vm } = setup({ result: PDF, fs, localPath: async (id) => ({ data: { path: winPath, exists: true, id } }) })
  await vm.exportPdf()
  assert.deepEqual(fs.sources, [winPath])
  fs.emit({ filename: '甲版合同.pdf', state: 'cancelled', savePath: '' })
})

test('桌面端：存盘真完成才提示成功（提示里是实际存的文件名）；对话框开着时不提示', async () => {
  const fs = desktopFs()
  const { vm, toasts } = setup({ result: PDF, fs })
  assert.equal(await vm.exportPdf(), true)
  assert.deepEqual(toasts, [], '对话框刚弹出就说「已导出」——用户存完时提示早就没了')
  fs.emit({ filename: '别的下载.zip', state: 'completed', savePath: '/x/别的下载.zip' })
  await tick()
  assert.deepEqual(toasts, [], '别的下载的回报不能认领')
  fs.emit({ filename: '甲版合同.pdf', state: 'completed', savePath: 'D:\\案件\\甲版合同-终稿.pdf' })
  await tick()
  assert.equal(toasts.length, 1)
  assert.match(toasts[0], /^editor\.pdfExported.*甲版合同-终稿\.pdf/)
  assert.equal(fs.listenerCount(), 0, '认领后要退订')
})

test('桌面端：用户取消不提示；存盘中断提示失败', async () => {
  const a = desktopFs()
  const ca = setup({ result: PDF, fs: a })
  await ca.vm.exportPdf()
  a.emit({ filename: '甲版合同.pdf', state: 'cancelled', savePath: '' })
  await tick()
  assert.deepEqual(ca.toasts, [])

  const b = desktopFs()
  const cb = setup({ result: PDF, fs: b })
  await cb.vm.exportPdf()
  b.emit({ filename: '甲版合同.pdf', state: 'interrupted', savePath: '' })
  await tick()
  assert.equal(cb.toasts.length, 1)
  assert.match(cb.toasts[0], /^editor\.pdfExportFailed/)
})

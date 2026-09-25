// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-61：收藏夹 / 剪贴板面板点「插入」写进文档后，Cmd+Z 撤销不掉。
// 插入本身走的是与 AI doc_* 同一条 insert_at_cursor 原语、进引擎撤销栈；
// 撤不掉是因为点「插入」时键盘焦点落在宿主侧面板上——DOM 键盘事件到不了引擎
// （tests/lowa-e2e/escape-chrome.mjs 第 3 条），Cmd+Z 只有在编辑器的 IME 覆盖层
// 拿着焦点时才会转成引擎的 undo。修法：插入成功后把焦点还给这份 executor 所属的编辑器。
// 自动保存仍按 LibreOfficeEditor 的正常节奏（scheduleAutoSave），这条路径不主动存盘。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')

function extractMethod(src, header) {
  const start = src.indexOf(header)
  assert.ok(start > 0, '找不到 ' + header)
  const braceStart = src.indexOf('{', start + header.length - 1)
  let depth = 0
  let i = braceStart
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break } }
  }
  return src.slice(start, i)
}

function loadLibrePool() {
  const body = read('pages/project-overview/librePool.js')
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace('export const librePoolMethods =', 'return')
  // eslint-disable-next-line no-new-func
  return new Function('host', 'isDesktopHost', body)({}, () => true)
}

function loadInsert(uni) {
  const method = extractMethod(read('pages/project-overview/project-overview.vue'), '    async insertPlainTextToWps(payload) {')
  // eslint-disable-next-line no-new-func
  return new Function('uni', 'fetch', 'FileReader', `return ({ ${method} })`)(uni, null, null).insertPlainTextToWps
}

function makePage({ fail = false } = {}) {
  const toasts = []
  const sent = []
  const focused = []
  const execLeft = { executeCommand: async (a, p) => { sent.push([a, p]); if (fail) throw new Error('boom'); return { success: true } } }
  const execRight = { executeCommand: async () => ({ success: true }) }
  const vm = {
    $t: (k) => k,
    libreOfficeActive: true,
    libreOfficeExecutor: execLeft,
    _libreExecMap: { 'left:1': execLeft, 'right:2': execRight },
    _libreRefs: {
      'left:1': { focusEditor: () => focused.push('left:1') },
      'right:2': { focusEditor: () => focused.push('right:2') },
    },
  }
  Object.assign(vm, loadLibrePool())
  vm.insertPlainTextToWps = loadInsert({ showToast: (o) => toasts.push(o.title) })
  return { vm, toasts, sent, focused }
}

test('收藏插入成功后把焦点还给 executor 所属的那个编辑器（Cmd+Z 才进得了引擎）', async () => {
  const { vm, sent, focused, toasts } = makePage()
  await vm.insertPlainTextToWps({ type: 'TEXT', content: 'https://example.com 收藏的链接' })
  assert.deepEqual(sent.map((s) => s[0]), ['insert_at_cursor'], '仍走与 AI doc_* 同一条可撤销原语')
  assert.deepEqual(focused, ['left:1'], '焦点应回到收到插入的那份编辑器，不是另一个窗格')
  assert.deepEqual(toasts, ['workbench.insertedToDoc'])
  assert.ok(!sent.some((s) => /save|export/.test(s[0])), '插入路径不主动存盘，走正常自动保存节奏')
})

test('插入失败时不抢焦点', async () => {
  const { vm, focused } = makePage({ fail: true })
  await vm.insertPlainTextToWps({ type: 'TEXT', content: 'x' })
  assert.deepEqual(focused, [])
})

test('LibreOfficeEditor.focusEditor 聚焦编辑器容器（webview / iframe）', () => {
  const SRC = read('components/LibreOfficeEditor.vue')
  const BODY = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')
  // eslint-disable-next-line no-new-func
  const options = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'setTimeout', BODY)(null, null, null, () => 0)
  let n = 0
  const vm = { webviewEl: { focus: () => { n++ } } }
  assert.equal(typeof options.methods.focusEditor, 'function')
  options.methods.focusEditor.call(vm)
  assert.equal(n, 1)
  options.methods.focusEditor.call({ webviewEl: null }) // 未建元素时不抛
})

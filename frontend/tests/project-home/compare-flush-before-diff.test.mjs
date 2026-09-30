// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 新建文件比对前必须先落盘本次的两份编辑器（dev-board#1102）。
//
// 病灶：onCompareDialogConfirm 以前同步关对话框、直接 openDiffTab。比对读的是磁盘
// 字节，而自动保存是防抖的——用户在 A 里敲完字、新建空白 B、立刻比对，比到的是 A
// 的旧字节。修复：确认后先 flush 本次 source/target 已打开且待保存的 Office 编辑器
// （判据同 closeFile：ready 且非 docLoadFailed），失败保留对话框提示重试，不碰无关文档。
//
// fileOpenTabs.js import 了 @/ 别名跑不进 node，按本目录既有方式把方法体切出来真跑。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')

const from = SRC.indexOf('    async onCompareDialogConfirm(')
const end = SRC.indexOf('    openDiffTab(source, target) {', from)
assert.ok(from > 0 && end > from, 'fileOpenTabs.js 里应能切出 onCompareDialogConfirm 方法体')

const toasts = []
const methods = new Function('uni', 'return {' + SRC.slice(from, end) + '}')(
  { showToast: (o) => toasts.push(o) })

function libre(file, { dirty = false, saving = false, ready = true, docLoadFailed = false } = {}) {
  const inst = { dirty, saving, ready, docLoadFailed, file, flushed: 0 }
  inst.flushSave = async () => { inst.flushed++; inst.dirty = false; inst.saving = false; return true }
  return inst
}

function makeVm(docs, refs, projectId = 7) {
  const vm = {
    events: [],
    projectId,
    compareDocuments: docs,
    showCompareDialog: true,
    _libreRefs: refs,
    $t: (k, p) => (p && p.msg !== undefined ? `${k}:${p.msg}` : k),
    openDiffTab(source, target) { this.events.push(['diff', source.id, target.id]) },
  }
  return vm
}

const A = { id: 1, name: 'A.docx' }
const B = { id: 2, name: 'B.docx' }

test('脏 A + 干净 B：只落盘 A，随后关对话框开比对', async () => {
  toasts.length = 0
  const a = libre(A, { dirty: true })
  const b = libre(B)
  const vm = makeVm([A, B], { 'left:1': a, 'right:2': b })
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(a.flushed, 1, '脏的 A 必须先落盘')
  assert.equal(b.flushed, 0, '干净的 B 不空存一次')
  assert.deepEqual(vm.events, [['diff', 1, 2]])
  assert.equal(vm.showCompareDialog, false)
  assert.equal(toasts.length, 0)
})

test('B 保存在途：等它落盘完才开比对', async () => {
  toasts.length = 0
  const b = libre(B, { saving: true })
  let release
  const gate = new Promise(resolve => { release = resolve })
  b.flushSave = async () => { await gate; b.flushed++; b.saving = false; return true }
  const vm = makeVm([A, B], { 'right:2': b })
  const pending = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.deepEqual(vm.events, [], '保存没结束不许开比对')
  release()
  await pending
  assert.equal(b.flushed, 1)
  assert.deepEqual(vm.events, [['diff', 1, 2]])
})

for (const kind of ['throws', 'returns-false', 'stays-dirty']) {
  test(`保存失败（${kind}）：保留对话框提示重试，不开比对、不装成已落盘`, async () => {
    toasts.length = 0
    const a = libre(A, { dirty: true })
    if (kind === 'throws') a.flushSave = async () => { throw new Error('offline') }
    else if (kind === 'returns-false') a.flushSave = async () => false
    else a.flushSave = async () => undefined // 返回了但 dirty 没清
    const vm = makeVm([A, B], { 'left:1': a })
    await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
    assert.deepEqual(vm.events, [], '没存下来不许开比对')
    assert.equal(vm.showCompareDialog, true, '对话框必须留着让用户重试')
    assert.equal(a.dirty, true, '不能清脏标记假装已落盘')
    assert.equal(toasts.length, 1)
    assert.match(toasts[0].title, /saveFailedNamed/)
  })
}

test('无关文档再脏也不碰；加载失败的空白原型不存', async () => {
  toasts.length = 0
  const a = libre(A, { dirty: true, docLoadFailed: true })
  const c = libre({ id: 3, name: 'C.docx' }, { dirty: true })
  const vm = makeVm([A, B], { 'left:1': a, 'left:3': c })
  await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  assert.equal(c.flushed, 0, '本次比对之外文档不保存')
  assert.equal(a.flushed, 0, '加载失败的实例存空白会覆盖真文件，跳过（同 closeFile）')
  assert.deepEqual(vm.events, [['diff', 1, 2]], '跳过不算失败，只能比磁盘上的字节')
})

for (const change of ['cancel-dialog', 'swap-pair', 'switch-project']) {
  test(`等待保存期间${change}：旧确认不许再开比对`, async () => {
    toasts.length = 0
    const a = libre(A, { dirty: true })
    let release
    const gate = new Promise(resolve => { release = resolve })
    a.flushSave = async () => { await gate; a.flushed++; a.dirty = false; return true }
    const vm = makeVm([A, B], { 'left:1': a })
    const pending = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
    await new Promise(resolve => setTimeout(resolve, 20))
    if (change === 'cancel-dialog') vm.showCompareDialog = false
    else if (change === 'swap-pair') vm.compareDocuments = [B, { id: 9, name: 'D.docx' }]
    else vm.projectId = 8
    release()
    await pending
    assert.equal(a.flushed, 1, '保存本身不拦——拦的是过期的开比对')
    assert.deepEqual(vm.events, [], '过期确认不许开比对')
    assert.equal(toasts.length, 0, '不是保存失败，不弹重试提示')
  })
}

test('保存期间重复点确认只开一次比对', async () => {
  toasts.length = 0
  const a = libre(A, { dirty: true })
  let release
  const gate = new Promise(resolve => { release = resolve })
  a.flushSave = async () => { await gate; a.flushed++; a.dirty = false; return true }
  const vm = makeVm([A, B], { 'left:1': a })
  const p1 = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  const p2 = methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
  await new Promise(resolve => setTimeout(resolve, 20))
  release()
  await Promise.all([p1, p2])
  assert.equal(a.flushed, 1)
  assert.deepEqual(vm.events, [['diff', 1, 2]])
})

for (const change of ['edit-saved-a', 'register-dirty-a']) {
  test(`B 保存期间 ${change}：最后重查当前编辑器，不能比旧字节`, async () => {
    toasts.length = 0
    const a = libre(A, { dirty: true })
    const b = libre(B, { saving: true })
    const vm = makeVm([A, B], { 'left:1': a, 'right:2': b })
    b.flushSave = async () => {
      if (change === 'edit-saved-a') a.dirty = true
      else vm._libreRefs['left:1'] = libre(A, { dirty: true })
      b.saving = false
      return true
    }
    await methods.onCompareDialogConfirm.call(vm, { source: A, target: B })
    assert.equal(a.flushed, 1)
    assert.deepEqual(vm.events, [])
    assert.equal(vm.showCompareDialog, true)
    assert.equal(toasts.length, 1)
  })
}

test('保存期间对话框不能反转比较方向或重复确认，仍可取消；失败后可重试', () => {
  const source = readFileSync(new URL('../../src/components/CompareDocDialog.vue', import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace('export default', 'return')
  const dialog = new Function(script)()
  const events = []
  const vm = { busy: true, canConfirm: true, documents: [A, B], sourceIndex: 0, targetIndex: 1,
    $emit: (...args) => events.push(args) }
  dialog.methods.selectSource.call(vm, 1)
  dialog.methods.selectTarget.call(vm, 0)
  dialog.methods.handleConfirm.call(vm)
  assert.equal(vm.sourceIndex, 0)
  assert.equal(vm.targetIndex, 1)
  assert.equal(events.length, 0)
  dialog.methods.handleCancel.call(vm)
  assert.equal(events[0][0], 'cancel')
  events.length = 0
  vm.busy = false
  dialog.methods.selectSource.call(vm, 1)
  dialog.methods.handleConfirm.call(vm)
  assert.deepEqual(events[0], ['confirm', { source: B, target: A }])
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/pages/project-overview/librePool.js', import.meta.url), 'utf8')
const GiB = 1024 ** 3
function vmFor(totalBytes) {
  const body = src.replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const librePoolMethods = \{/, 'return {')
  const methods = new Function('isDesktopHost', 'host', body)(() => true, totalBytes === undefined ? {} : { systemMemory: { totalBytes } })
  const vm = {
    libreLruKeys: [], libreSpares: [], _libreRefs: {}, _libreExecMap: {},
    leftFiles: [], rightFiles: [], splitMode: false, activeFileIdLeft: null, activeFileIdRight: null,
    useLibreEditor: () => true,
  }
  for (const [key, fn] of Object.entries(methods)) vm[key] = fn.bind(vm)
  return vm
}
function add(vm, pane, id, flushSave = async () => true) {
  if (pane === 'right') vm.splitMode = true
  const file = { id, fileSize: 300 * 1024 }
  vm[pane + 'Files'].push(file)
  vm['activeFile' + (pane === 'left' ? 'Left' : 'Right')] = file
  vm._libreRefs[pane + ':' + id] = { file, ready: true, flushSave }
  vm['activeFileId' + (pane === 'left' ? 'Left' : 'Right')] = id
  vm.onActiveOfficeFileChanged(pane, file)
  return file
}
const settle = () => new Promise(resolve => setImmediate(resolve))

for (const total of [8 * GiB, 7.7 * GiB, undefined, 0, NaN]) {
  test(`8 GiB or unknown memory keeps first-open prewarming but never adds a spare beside a document (${total})`, () => {
    const vm = vmFor(total)
    vm.initLibreSpare()
    assert.equal(vm.libreSpares.length, 1)
    const spare = vm.libreSpares[0]
    add(vm, 'left', 1)
    assert.equal(spare.file.id, 1)
    vm.onLibreSpareReady(spare, { executeCommand() {} })
    assert.equal(vm._libreSpareTimer, undefined)
    vm.initLibreSpare()
    assert.equal(vm.libreSpares.length, 1)
  })
}
test('known 16 GiB device retains prewarming', () => {
  const vm = vmFor(16 * GiB)
  vm.initLibreSpare()
  vm.initLibreSpare()
  assert.equal(vm.libreSpares.length, 1)
})
test('8 GiB keeps active document and one recent background document; adopted entries leave too', async () => {
  const vm = vmFor(8 * GiB)
  const first = add(vm, 'left', 1)
  vm.libreSpares.push({ key: 1, file: first })
  add(vm, 'left', 2)
  add(vm, 'left', 3)
  await settle()
  assert.deepEqual(vm.libreLruKeys, ['left:3', 'left:2'])
  assert.equal(vm.libreSpares.length, 0)
})
test('two active split documents reserve both slots even when an inactive document is more recent', async () => {
  const vm = vmFor(8 * GiB)
  add(vm, 'right', 1)
  add(vm, 'left', 2)
  add(vm, 'left', 3)
  await settle()
  assert.deepEqual(vm.libreLruKeys, ['left:3', 'right:1'])
})
test('failed save retains the only unsaved copy even above the instance budget', async () => {
  const vm = vmFor(8 * GiB)
  let saves = 0
  add(vm, 'left', 1, async () => { saves++; return false })
  add(vm, 'left', 2)
  add(vm, 'left', 3)
  await settle()
  assert.ok(saves > 0)
  assert.ok(vm.libreLruKeys.includes('left:1'))
})
test('activation during pending save cancels eviction', async () => {
  const vm = vmFor(8 * GiB)
  let finishSave
  const first = add(vm, 'left', 1, () => new Promise(resolve => { finishSave = resolve }))
  add(vm, 'left', 2)
  add(vm, 'left', 3)
  assert.equal(typeof finishSave, 'function')
  vm.activeFileIdLeft = 1
  vm.onActiveOfficeFileChanged('left', first)
  finishSave(true)
  await settle()
  assert.ok(vm.libreLruKeys.includes('left:1'))
})
test('low memory policy leaves hidden merge engines untouched and permits their release', async () => {
  const vm = vmFor(8 * GiB)
  const pending = vm.acquireLibreHiddenInstance({ timeoutMs: 500 })
  const sp = vm.libreSpares[0]
  assert.equal(sp.hidden, true)
  vm.onLibreSpareReady(sp, { executeCommand: async () => ({ success: true }) })
  const handle = await pending
  assert.ok(handle)
  vm.initLibreSpare()
  assert.equal(vm.libreSpares.length, 2)
  add(vm, 'left', 1)
  assert.equal(sp.file, null, 'hidden merge instance must never be adopted')
  vm.releaseLibreHiddenInstance(handle)
  assert.equal(vm.libreSpares.length, 1)
})

test('closing another document while a save is pending keeps the newly affordable cached instance', async () => {
  const vm = vmFor(8 * GiB)
  let finishSave
  add(vm, 'left', 1, () => new Promise(resolve => { finishSave = resolve }))
  add(vm, 'left', 2)
  add(vm, 'left', 3)
  assert.equal(typeof finishSave, 'function')
  vm.leftFiles = vm.leftFiles.filter(file => file.id !== 2)
  vm.libreLruKeys = vm.libreLruKeys.filter(key => key !== 'left:2')
  finishSave(true)
  await settle()
  assert.deepEqual(vm.libreLruKeys, ['left:3', 'left:1'])
})

test('closing split view does not spend a resident-engine slot on the unmounted right pane', async () => {
  const vm = vmFor(8 * GiB)
  add(vm, 'right', 1)
  const a = add(vm, 'left', 2)
  vm.splitMode = false
  delete vm._libreRefs['right:1']
  const b = add(vm, 'left', 3)
  await settle()
  assert.ok(vm.libreLruKeys.includes('left:2'))
  for (const file of [a, b]) {
    vm.activeFileIdLeft = file.id
    vm.onActiveOfficeFileChanged('left', file)
    await settle()
    assert.ok(vm.libreLruKeys.includes('left:2'))
    assert.ok(vm.libreLruKeys.includes('left:3'))
  }
})

test('reopening split view reapplies the budget without changing the remembered right document', async () => {
  const page = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const watch = page.match(/    splitMode\(\) \{([\s\S]*?)\n    activeToolKey/)[1].replace(/\},\s*$/, '')
  const onSplitMode = new Function(watch)
  const vm = vmFor(8 * GiB)
  vm.pushMenuState = () => {}
  vm.$nextTick = fn => fn()
  vm.rebindTabsWheel = () => {}
  add(vm, 'right', 1)
  add(vm, 'left', 2)
  vm.splitMode = false
  onSplitMode.call(vm)
  delete vm._libreRefs['right:1']
  add(vm, 'left', 3)
  await settle()
  assert.ok(vm.libreLruKeys.includes('left:2'))
  vm.splitMode = true
  onSplitMode.call(vm)
  await settle()
  assert.ok(vm.libreLruKeys.includes('left:3'))
  assert.ok(vm.libreLruKeys.includes('right:1'))
  assert.ok(!vm.libreLruKeys.includes('left:2'))
})

test('a document opened before the warmup timer prevents a second blank engine', () => {
  const vm = vmFor(8 * GiB)
  vm.scheduleLibreSpare()
  assert.ok(vm._libreSpareTimer)
  clearTimeout(vm._libreSpareTimer)
  add(vm, 'left', 1)
  vm.initLibreSpare()
  assert.equal(vm.libreSpares.length, 0)
})
test('closing all documents schedules first-open prewarming again; reopening before it fires cancels the extra instance', () => {
  const vm = vmFor(8 * GiB)
  const first = add(vm, 'left', 1)
  vm.libreSpares.push({ key: 1, file: first })
  vm.leftFiles = []
  vm.activeFileIdLeft = null
  vm.pruneClosedLibreSpares()
  assert.ok(vm._libreSpareTimer)
  clearTimeout(vm._libreSpareTimer)
  assert.equal(vm.libreSpares.length, 0)
  add(vm, 'left', 2)
  vm.initLibreSpare()
  assert.equal(vm.libreSpares.length, 0)
  vm.leftFiles = []
  vm.activeFileIdLeft = null
  vm.pruneClosedLibreSpares()
  clearTimeout(vm._libreSpareTimer)
  vm.initLibreSpare()
  assert.equal(vm.libreSpares.length, 1)
  assert.equal(vm.libreSpares[0].file, null)
})

test('opening the first document directly in the right pane retires the unusable left warm spare', () => {
  const vm = vmFor(8 * GiB)
  vm.initLibreSpare()
  vm.libreSpares.push({ key: 99, file: null, hidden: true })
  add(vm, 'right', 1)
  assert.equal(vm.libreSpares.length, 1)
  assert.equal(vm.libreSpares[0].hidden, true)
})

test('closing the sole Office pane restores prewarming beside a non-Office left tab', () => {
  const page = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const watch = page.match(/    splitMode\(\) \{([\s\S]*?)\n    activeToolKey/)[1].replace(/\},\s*$/, '')
  const vm = vmFor(8 * GiB)
  vm.pushMenuState = () => {}
  vm.$nextTick = fn => fn()
  vm.rebindTabsWheel = () => {}
  vm.useLibreEditor = file => file.fileSize != null
  vm.leftFiles = [{ id: 'home' }]
  vm.activeFileIdLeft = 'home'
  vm.initLibreSpare()
  add(vm, 'right', 1)
  assert.equal(vm.libreSpares.length, 0)
  vm.splitMode = false
  delete vm._libreRefs['right:1']
  new Function(watch).call(vm)
  assert.ok(vm._libreSpareTimer, 'closing the only Office pane must schedule first-open prewarming')
  clearTimeout(vm._libreSpareTimer)
  vm.initLibreSpare()
  assert.equal(vm.libreSpares.length, 1)
  assert.equal(vm.libreSpares[0].file, null)
})

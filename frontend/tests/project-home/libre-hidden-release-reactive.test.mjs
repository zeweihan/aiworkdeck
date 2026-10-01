// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { reactive } from 'vue'
const src = readFileSync(new URL('../../src/pages/project-overview/librePool.js', import.meta.url), 'utf8')
function makeVm() {
  const body = src.replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const librePoolMethods = \{/, 'return {')
  const methods = new Function('isDesktopHost', 'host', body)(() => true, {})
  const vm = reactive({ libreSpares: [], _libreSpareSeq: 0 })
  for (const [key, fn] of Object.entries(methods)) vm[key] = fn.bind(vm)
  return vm
}
async function acquire(vm) {
  const pending = vm.acquireLibreHiddenInstance()
  vm.libreSpares.at(-1).executor = { executeCommand: () => ({ success: true }) }
  return pending
}
test('real Vue proxy entry is removed by the raw acquire handle', async () => {
  const vm = makeVm(), handle = await acquire(vm)
  assert.notEqual(handle._spare, vm.libreSpares[0], 'exercise the raw/proxy identity mismatch')
  vm.releaseLibreHiddenInstance(handle)
  assert.equal(vm.libreSpares.length, 0)
})
test('a late old handle cannot remove a newly acquired hidden engine', async () => {
  const vm = makeVm(), old = await acquire(vm)
  vm.releaseLibreHiddenInstance(old)
  const current = await acquire(vm)
  vm.releaseLibreHiddenInstance(old)
  assert.deepEqual(vm.libreSpares.map(x => x.key), [current._spare.key])
  vm.releaseLibreHiddenInstance(current)
  assert.equal(vm.libreSpares.length, 0)
})
test('cold-start timeout removes its raw spare and preserves other pool entries', async () => {
  const vm = makeVm()
  vm._libreSpareSeq = 1
  vm.libreSpares.push({ key: 1, file: { id: 10 } })
  assert.equal(await vm.acquireLibreHiddenInstance({ timeoutMs: -1 }), null)
  assert.deepEqual(vm.libreSpares.map(x => x.key), [1])
})
test('missing, invalid and unknown handles leave the reactive pool unchanged', () => {
  const vm = makeVm()
  vm.libreSpares.push({ key: 1, file: null }, { key: 2, hidden: true, file: null })
  for (const handle of [null, undefined, {}, { _spare: {} }, { key: null }, { key: NaN }, { key: 0 }, { key: '1' }, { key: 999 }])
    vm.releaseLibreHiddenInstance(handle)
  assert.deepEqual(vm.libreSpares.map(x => x.key), [1, 2])
  vm.releaseLibreHiddenInstance(vm.libreSpares[1])
  assert.deepEqual(vm.libreSpares.map(x => x.key), [1])
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(
  new URL('../../src/components/MemoryBrowser.vue', import.meta.url), 'utf8')

function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

function loadOptions(api, uni) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import\s*\{[\s\S]*?\}\s*from\s*'@\/services\/api\.js'\s*/, '')
    .replace(/import\s*\{\s*markdownFileLinks\s*\}\s*from\s*'@\/composables\/memoryBrowserState\.mjs'\s*/, '')
    .replace(/export\s+default/, 'return')
  return new Function(
    'deleteMemoryFile', 'downloadMemoryFile', 'getMemoryFile', 'getMemoryFiles',
    'getMemorySpaces', 'saveMemoryFile', 'markdownFileLinks', 'uni', script,
  )(
    api.deleteMemoryFile, api.downloadMemoryFile, api.getMemoryFile, api.getMemoryFiles,
    api.getMemorySpaces, api.saveMemoryFile, () => [], uni,
  )
}

function makeVm(api, uni = { showToast() {}, showModal() {} }) {
  const options = loadOptions(api, uni)
  const vm = Object.assign({ open: true, projectId: 7, $t: (key) => key }, options.data())
  for (const [name, method] of Object.entries(options.methods)) vm[name] = method.bind(vm)
  for (const [name, computed] of Object.entries(options.computed)) {
    Object.defineProperty(vm, name, { get: computed.bind(vm) })
  }
  return vm
}

const space = (id) => ({ id, label: id, available: true, writable: true })
const file = (owner, revision = 2) => ({ path: 'remember.md', content: `${owner} content`, revision, writable: true })

test('a late file read cannot attach the old space document to the new active space', async () => {
  const oldRead = deferred()
  const api = {
    getMemoryFiles: async () => [{ path: 'remember.md' }],
    getMemoryFile: (spaceId) => spaceId === 'personal' ? oldRead.promise : Promise.resolve(file('team')),
  }
  const vm = makeVm(api)
  vm.activeSpace = space('personal')

  const pendingOldRead = vm.openFile('remember.md')
  await vm.selectSpace(space('team:1'))
  oldRead.resolve(file('personal'))
  await pendingOldRead

  assert.equal(vm.activeSpace.id, 'team:1')
  assert.equal(vm.current.content, 'team content')
  assert.equal(vm.draft, 'team content')
})

test('a late save response cannot replace a document opened in another space', async () => {
  const oldSave = deferred()
  const api = {
    getMemoryFiles: async () => [{ path: 'remember.md' }],
    getMemoryFile: async () => file('team'),
    saveMemoryFile: () => oldSave.promise,
  }
  const vm = makeVm(api)
  vm.activeSpace = space('personal')
  vm.current = file('personal', 1)
  vm.currentSpaceId = 'personal'
  vm.draft = 'personal edit'

  const pendingSave = vm.saveCurrent()
  await vm.selectSpace(space('team:1'))
  oldSave.resolve(file('personal saved', 2))
  await pendingSave

  assert.equal(vm.activeSpace.id, 'team:1')
  assert.equal(vm.current.content, 'team content')
  assert.equal(vm.draft, 'team content')
})

test('delete confirmation keeps the file identity shown in the modal', async () => {
  let modal
  const deletes = []
  const api = {
    deleteMemoryFile: async (...args) => { deletes.push(args) },
    getMemoryFiles: async () => [],
  }
  const vm = makeVm(api, {
    showToast() {},
    showModal(options) { modal = options },
  })
  vm.activeSpace = space('personal')
  vm.current = { path: 'a.md', revision: 3, writable: true }
  vm.currentSpaceId = 'personal'
  vm.confirmDelete()
  vm.current = { path: 'b.md', revision: 8, writable: true }

  await modal.success({ confirm: true })

  assert.deepEqual(deletes, [['personal', 'a.md', 3]])
})

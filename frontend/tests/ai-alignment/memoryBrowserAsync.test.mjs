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
  vm.watchers = options.watch
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

test('selecting a visible space supersedes a pending space refresh without leaving loading stuck', async () => {
  const oldSpaces = deferred()
  const vm = makeVm({
    getMemorySpaces: () => oldSpaces.promise,
    getMemoryFiles: async () => [],
  })
  vm.spaces = [space('team:1')]

  const pendingRefresh = vm.loadSpaces()
  assert.equal(vm.loading, true)
  await vm.selectSpace(vm.spaces[0])
  assert.equal(vm.loading, false)

  oldSpaces.resolve([space('personal')])
  await pendingRefresh
  assert.equal(vm.loading, false)
  assert.equal(vm.activeSpace.id, 'team:1')
})

test('changing project while open reloads its project-scoped spaces', async () => {
  const projects = []
  const vm = makeVm({
    getMemorySpaces: async (projectId) => { projects.push(projectId); return [] },
  })
  vm.projectId = 8

  await vm.watchers.projectId.call(vm)

  assert.deepEqual(projects, [8])
})

test('a delete refresh superseded by navigation does not issue another read in the new space', async () => {
  const oldRefresh = deferred()
  const reads = []
  const vm = makeVm({
    deleteMemoryFile: async () => {},
    getMemoryFiles: (spaceId) => spaceId === 'personal'
      ? oldRefresh.promise
      : Promise.resolve([{ path: 'team.md' }]),
    getMemoryFile: async (spaceId, path) => {
      reads.push([spaceId, path])
      return { path, content: 'team', revision: 1, writable: true }
    },
  })
  vm.activeSpace = space('personal')
  vm.current = { path: 'old.md', revision: 1, writable: true }
  vm.currentSpaceId = 'personal'

  const pendingDelete = vm.deleteCurrent()
  await Promise.resolve()
  await vm.selectSpace(space('team:1'))
  oldRefresh.resolve([{ path: 'personal-next.md' }])
  await pendingDelete

  assert.deepEqual(reads, [['team:1', 'team.md']])
})

// v0.38.3 走查 D5：uni 的 v-model 有 100ms 节流，打完字立刻点保存/创建，
// data 里还是旧值，末尾几个字就丢了。保存与创建必须读控件当下的值。
const liveRef = (value) => ({ $el: { querySelector: () => ({ value }) } })

test('D5: save right after typing sends what the editor shows, not the throttled v-model value', async () => {
  const saves = []
  const vm = makeVm({
    saveMemoryFile: async (payload) => { saves.push(payload); return { ...payload, revision: 2, writable: true } },
    getMemoryFiles: async () => [{ path: 'remember.md' }],
  })
  vm.activeSpace = space('personal')
  vm.current = file('personal', 1)
  vm.currentSpaceId = 'personal'
  vm.draft = 'personal content'
  vm.$refs = { editor: liveRef('personal content plus the last words') }

  await vm.saveCurrent()

  assert.equal(saves.length, 1)
  assert.equal(saves[0].content, 'personal content plus the last words')
  assert.equal(vm.draft, 'personal content plus the last words')
})

test('D5: creating a topic right after typing uses the input value shown on screen', async () => {
  const saves = []
  const vm = makeVm({
    saveMemoryFile: async (payload) => { saves.push(payload); return { path: payload.path, revision: 1, content: payload.content } },
    getMemoryFiles: async () => [],
    getMemoryFile: async (spaceId, path) => ({ path, revision: 1, content: '', writable: true }),
  })
  vm.activeSpace = space('personal')
  vm.newTopic = 'client-pre'
  vm.$refs = { topicInput: liveRef('client-preferences') }

  await vm.createTopic({ detail: { x: 1, y: 2 } })

  assert.equal(saves[0]?.path, 'client-preferences.md')
})

test('D5: the confirm event value wins over a stale v-model value', async () => {
  const saves = []
  const vm = makeVm({
    saveMemoryFile: async (payload) => { saves.push(payload); return { path: payload.path, revision: 1, content: payload.content } },
    getMemoryFiles: async () => [],
    getMemoryFile: async (spaceId, path) => ({ path, revision: 1, content: '', writable: true }),
  })
  vm.activeSpace = space('personal')
  vm.newTopic = 'deadl'

  await vm.createTopic({ detail: { value: 'deadlines' } })

  assert.equal(saves[0]?.path, 'deadlines.md')
})

test('D2: content over the 128 KiB contract is refused locally with a message instead of a failed request', async () => {
  const saves = []
  const toasts = []
  const vm = makeVm({ saveMemoryFile: async (payload) => { saves.push(payload); return payload } },
    { showToast(options) { toasts.push(options.title) }, showModal() {} })
  vm.activeSpace = space('personal')
  vm.current = file('personal', 1)
  vm.currentSpaceId = 'personal'
  vm.draft = '中'.repeat(50000) // 150000 UTF-8 bytes
  vm.$refs = {}

  assert.equal(vm.draftTooLarge, true)
  await vm.saveCurrent()

  assert.equal(saves.length, 0)
  assert.deepEqual(toasts, ['chat.memoryTooLarge'])
})

test('unsaved edits are flagged until the save lands', async () => {
  const vm = makeVm({})
  vm.current = file('personal', 1)
  vm.draft = 'personal content'
  vm.serverDraft = 'personal content'
  assert.equal(vm.hasUnsavedChanges, false)
  vm.draft = 'personal content edited'
  assert.equal(vm.hasUnsavedChanges, true)
})

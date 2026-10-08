// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Mount the real Options API with Vue watchers; only rendering and I/O are stubbed.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRenderer, h, nextTick, reactive } from 'vue'
import * as taskUtils from '../../src/components/calendar/taskUtils.js'
import { writableProjects } from '../../src/utils/personalCollections.js'

function loadOptions(path, deps = {}) {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export default/, 'return')
  return new Function(...Object.keys(deps), script)(...Object.values(deps))
}

const select = loadOptions('../../src/components/AwdSelect.vue')
const renderer = createRenderer({
  createComment: () => ({}), insert() {}, remove() {}, parentNode: () => null,
  nextSibling: () => null,
})
const projects = [
  { id: 7, name: '甲项目', myRole: 'OWNER' },
  { id: 8, name: '乙项目', myRole: 'OWNER' },
]

async function mount(t, overrides = {}) {
  const calls = { created: [], updated: [], files: [], members: [], toasts: [] }
  const options = loadOptions('../../src/components/calendar/TaskDialog.vue', {
    ...taskUtils, writableProjects,
    AwdSelect: {}, AwdDatePicker: {}, AwdSwitch: {}, FilePickerDialog: {}, MentionInput: {},
    getProjectFiles: async (pid) => { calls.files.push(pid); return [] },
    getProjectMembers: async (pid) => { calls.members.push(pid); return { data: [] } },
    createTask: async (body) => { calls.created.push(body); return { id: 1, ...body } },
    updateTask: async (id, body) => { calls.updated.push({ id, body }); return { id, ...body } },
    deleteTask: async () => {}, excludeSystemFolders: (files) => files,
    dirLabelOf: () => '', roleLabel: (role) => role, memberName: () => '',
    uni: { showToast: (toast) => calls.toasts.push(toast) },
  })
  const props = reactive({ visible: true, projects: [], presetDate: '2026-10-08', ...overrides })
  let vm
  const component = { ...options, render: () => null }
  const app = renderer.createApp({ render: () => h(component, { ...props, ref: (value) => { vm = value } }) })
  app.config.globalProperties.$t = (key) => key
  app.mount({})
  t.after(() => app.unmount())
  await nextTick()
  vm.form.title = '日程回归'
  return { vm, props, calls }
}

function pick(vm, index) {
  select.methods.pick.call({
    value: vm.projectIndex, detachDismiss() {},
    $emit: (event, value) => { if (event === 'change') vm.onProjectChange(value) },
  }, index)
}

test('welcome dialog: projects arrive after opening; displayed first project can be saved', async (t) => {
  const { vm, props, calls } = await mount(t)
  props.projects = projects
  await nextTick()
  assert.equal(vm.projectLabels[vm.projectIndex], '甲项目')
  pick(vm, 0) // AwdSelect intentionally does not emit change for its current index.
  await vm.submit()
  assert.equal(calls.created.length, 1, 'displayed project must not fail requiredProject validation')
  assert.equal(calls.created[0].projectId, 7)
  assert.deepEqual(calls.files, [7])
  assert.deepEqual(calls.members, [7])
  assert.equal(calls.toasts.some((toast) => toast.title === 'calendar.requiredProject'), false)
})

test('project refresh preserves explicit selection, while switching clears project-specific fields', async (t) => {
  const { vm, props, calls } = await mount(t, { projects })
  vm.form.fileIds = [41]
  vm.form.assigneeId = 5
  pick(vm, 1)
  await nextTick()
  assert.deepEqual([...vm.form.fileIds], [])
  assert.equal(vm.form.assigneeId, null)
  vm.form.notes = '保留备注'
  props.projects = projects.map((project) => ({ ...project }))
  await nextTick()
  await vm.submit()
  assert.equal(calls.created[0].projectId, 8)
  assert.equal(calls.created[0].notes, '保留备注')
})

test('no writable project still blocks creation; delayed list selects the first writable project', async (t) => {
  const { vm, props, calls } = await mount(t)
  const client = { id: 6, name: '客户只读项目', myRole: 'CLIENT' }
  props.projects = [client]
  await nextTick()
  await vm.submit()
  assert.equal(calls.created.length, 0)
  assert.equal(calls.toasts.at(-1).title, 'calendar.requiredProject')
  props.projects = [client, ...projects]
  await nextTick()
  await vm.submit()
  assert.equal(calls.created[0].projectId, 7)
})

test('locked project and editing do not adopt an asynchronously loaded default project', async (t) => {
  for (const overrides of [
    { projectId: 42 },
    { mode: 'edit', task: { id: 9, projectId: 42, title: '已有事项', dueDate: '2026-10-08' } },
  ]) {
    const { vm, props, calls } = await mount(t, overrides)
    props.projects = projects
    await nextTick()
    assert.equal(vm.effectiveProjectId, 42)
    assert.equal(vm.form.projectId, null)
    await vm.submit()
    if (overrides.task) assert.equal(calls.updated[0].id, 9)
    else assert.equal(calls.created[0].projectId, 42)
  }
})

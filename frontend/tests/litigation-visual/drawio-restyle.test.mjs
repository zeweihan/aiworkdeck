// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'

function loadComponent(name, bindings) {
  const source = readFileSync(new URL(`../../src/components/${name}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  return new Function(...Object.keys(bindings), script.replace('export default', 'return'))(...Object.values(bindings))
}
function vm(component, extra = {}) {
  const instance = { ...component.data(), ...extra }
  for (const [name, method] of Object.entries(component.methods)) instance[name] = method.bind(instance)
  return instance
}
function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}
function setup(confirm = true) {
  const events = []
  const listeners = {}
  let calls = 0
  const uni = {
    showModal: options => options.success({ confirm }),
    showLoading() {}, hideLoading() {}, showToast() {},
    $emit(name, event) { events.push(name); for (const f of listeners[name] || []) f(event) },
    $on(name, f) { (listeners[name] ||= []).push(f) }
  }
  const editor = vm(loadComponent('DrawioEditor', { uni, createSerialQueue }), {
    projectId: 1, file: { id: 10, parentId: 2, name: '当事人关系图.drawio' }, $t: k => k
  })
  const panel = vm(loadComponent('LitigationVisualPanel', {
    uni, t: key => key,
    restyleLitigationDiagram: async (projectId, folderId, mode, confirmed) => {
      assert.equal(confirmed, true); calls++
    }
  }), { projectId: 1, $t: k => k })
  uni.$on('awd:litviz-restyling', editor.onRestyling)
  uni.$on('awd:litviz-restyled', editor.onRestyled)
  return { editor, panel, events, calls: () => calls }
}

test('取消换风格：即使旧元数据说没有手改，也询问且完全不修改、不暂停编辑器', async () => {
  const s = setup(false)
  s.editor.dirty = true
  await s.panel.restyle({ folderId: 2, handEdited: false }, '白描')
  assert.equal(s.calls(), 0)
  assert.equal(s.editor.restylePending, false)
  assert.equal(s.editor.dirty, true)
  assert.deepEqual(s.events, [])
})

test('确认换风格：排空已接受的保存，阻止新的旧图保存，然后重载当前画布', async () => {
  const s = setup()
  const save = deferred()
  const order = []
  s.editor.persistNow = async xml => { await save.promise; order.push(`saved:${xml}`) }
  s.editor.boot = async () => { order.push('reload'); s.editor.xml = 'restyled XML' }
  const saving = s.editor.persist('manual XML')
  await Promise.resolve()
  const restyling = s.panel.restyle({ folderId: 2, handEdited: false }, '白描')
  await Promise.resolve()
  assert.equal(s.editor.restylePending, true)
  assert.equal(s.calls(), 0)
  await s.editor.persist('stale late save')
  save.resolve()
  await saving
  await restyling
  await Promise.resolve()
  assert.deepEqual(order, ['saved:manual XML', 'reload'])
  assert.equal(s.calls(), 1)
  assert.equal(s.editor.xml, 'restyled XML')
  assert.equal(s.editor.restylePending, false)
})

test('换风格失败保留原画布与未保存修改；其它图及普通保存不会重载编辑器', async () => {
  const s = setup()
  let reloads = 0
  s.editor.boot = async () => { reloads++ }
  s.editor.dirty = true
  s.editor.restylePending = true
  await s.editor.onRestyled({ projectId: 1, folderId: 2, kind: 'restyle', failed: true })
  await s.editor.onRestyled({ projectId: 1, folderId: 2, kind: 'save' })
  await s.editor.onRestyled({ projectId: 1, folderId: 3, kind: 'restyle' })
  assert.equal(reloads, 0)
  assert.equal(s.editor.dirty, true)
  assert.equal(s.editor.restylePending, false)
})

test('嵌入式编辑器 load 传真实文件名供 PNG 导出使用', () => {
  const s = setup()
  const source = {}
  s.editor.$refs = { frame: { contentWindow: source } }
  s.editor.xml = '<mxfile/>'
  let message
  s.editor.post = msg => { message = msg }
  s.editor.onMessage({ source, data: JSON.stringify({ event: 'init' }) })
  assert.equal(message.title, '当事人关系图.drawio')
  assert.equal(message.xml, '<mxfile/>')
})

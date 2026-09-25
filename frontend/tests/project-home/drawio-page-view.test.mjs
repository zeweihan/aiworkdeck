// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board BUG-22：draw.io 标签里能画的区域只占编辑区约 55% 宽。iframe 本身已铺满
// 主区域（工具栏顶到右缘），窄的是 draw.io 内部的「页面视图」——只把一张纸宽画成
// 白底网格、纸外全灰。修法是在 draw.io 配置层关掉页面视图（URL 参数 pv=0），并在
// load 消息里让它按编辑区宽度 fit 一次（只缩不放）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'

function loadEditor() {
  const source = readFileSync(new URL('../../src/components/DrawioEditor.vue', import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const component = new Function('uni', 'createSerialQueue', script.replace('export default', 'return'))({}, createSerialQueue)
  const vm = { ...component.data(), projectId: 1, file: { id: 10, parentId: 2, name: '关系图.drawio' }, $t: k => k }
  for (const [name, method] of Object.entries(component.methods)) vm[name] = method.bind(vm)
  return vm
}

test('编辑器 URL 关掉页面视图（pv=0），不重复追加、不覆盖宿主已给的 pv', () => {
  const vm = loadEditor()
  const base = 'http://127.0.0.1:47614/index.html?embed=1&proto=json&ui=min'
  assert.equal(vm.withEditorParams(base), base + '&pv=0')
  assert.equal(vm.withEditorParams('/drawio/index.html'), '/drawio/index.html?pv=0')
  assert.equal(vm.withEditorParams(base + '&pv=1'), base + '&pv=1')
  assert.equal(vm.withEditorParams(''), '')
})

test('boot() 挂上的 iframe 地址带 pv=0', async () => {
  const vm = loadEditor()
  const url = 'http://127.0.0.1:47614/index.html?embed=1&proto=json'
  vm.probeEditor = async () => true
  vm.loadXml = async () => '<mxfile/>'
  // boot() 读的 host / isDesktopHost 是被抠掉的 import，这里带上它们重新求值一次
  const source = readFileSync(new URL('../../src/components/DrawioEditor.vue', import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const host = { drawio: { getEditor: async () => ({ available: true, url }) } }
  const component = new Function('uni', 'createSerialQueue', 'host', 'isDesktopHost', script.replace('export default', 'return'))(
    {}, createSerialQueue, host, () => true)
  const boot = component.methods.boot.bind(vm)
  await boot()
  assert.equal(vm.phase, 'ready')
  assert.equal(vm.editorUrl, url + '&pv=0')
})

test('init 后的 load 消息带 fit（只缩不放），打开即按编辑区宽度显示', () => {
  const vm = loadEditor()
  const source = {}
  vm.$refs = { frame: { contentWindow: source } }
  vm.xml = '<mxfile/>'
  let message
  vm.post = msg => { message = msg }
  vm.onMessage({ source, data: JSON.stringify({ event: 'init' }) })
  assert.equal(message.action, 'load')
  assert.equal(message.fit, 1)
  assert.equal(message.maxFitScale, 1)
  assert.equal(message.xml, '<mxfile/>')
})

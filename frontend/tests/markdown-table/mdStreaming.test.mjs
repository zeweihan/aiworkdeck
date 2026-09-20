// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// MarkdownPreview.vue 的流式渲染开销用例（dev-board#750）。
//
// 病灶：后端每个模型 token 发一条 text_delta，而这里的渲染是「整篇重新解析 + 整段
// innerHTML 重写」——不是追加。一篇 N 个 token 的回答要把全文重建 N 遍，而且
// markdown-it 实例原先放在 data() 里被 Vue 做成了响应式代理，解析本身又慢 3.7 倍
// （实测 8450 字正文：普通实例 0.545ms / 响应式 2.028ms / 在 computed 里 2.377ms；
//  整条流累计 313ms → 1972ms）。
//
// 三条断言方向：
//   ① md 实例不许再进 data()（响应式代理那一刀）；
//   ② 流式期间多次内容变更只渲染一次，且最后一次内容一定渲染得出来（不丢尾巴）；
//   ③ 首屏仍是同步渲染——静态预览挂载后立刻要有内容，合帧不能把首字推后。
//
// 手法同 mdTable.test.mjs：把 <script> 剥出来当普通对象跑，依赖用形参喂回去。
// 组件在 Node 下拿不到 requestAnimationFrame，会自动回落 setTimeout(16ms)，
// 所以用例里等真实定时器即可。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { renderMarkdown, markdownInstance } from '../../src/utils/markdownRenderer.js'

const SRC = readFileSync(new URL('../../src/components/MarkdownPreview.vue', import.meta.url), 'utf8')

/** 把 SFC 的 <script> 变成可调用的组件对象；renderMarkdown 可替换成计数桩。 */
function loadComponent(render = renderMarkdown) {
  const deps = {
    renderMarkdown: render,
    getFileDownloadUrl: async () => '',
    getAuthHeaders: () => ({}),
  }
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  return new Function(...names, script.replace('export default', 'return'))(...names.map((n) => deps[n]))
}

/** 建一个「够用的」vm：data + computed + methods 接到同一个对象上。 */
function makeVm(component, content = '') {
  const vm = { $t: (k) => k, content, loadedContent: '', loading: false }
  Object.assign(vm, component.data.call(vm))
  vm.content = content
  for (const [k, fn] of Object.entries(component.computed || {})) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  for (const [k, fn] of Object.entries(component.methods || {})) vm[k] = fn.bind(vm)
  return vm
}

/** 模拟一次 content 变更：改值 + 触发 watch（真实环境里由 Vue 的响应式触发）。 */
function feed(component, vm, text) {
  vm.content = text
  component.watch.sourceText.call(vm)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

test('markdown-it 实例不在 data() 里（放进去会被 Vue 做成响应式代理，解析慢 3.7 倍）', () => {
  const component = loadComponent()
  const state = component.data.call({ content: '# hi' })
  for (const [key, value] of Object.entries(state)) {
    assert.equal(
      typeof value?.render, 'undefined',
      `data() 里不许出现 markdown-it 实例（字段 ${key}）——Vue 会把它整个 reactive() 一遍`,
    )
  }
  assert.doesNotMatch(
    SRC, /new\s+MarkdownIt/,
    'MarkdownPreview 不该再自己 new MarkdownIt，共享 src/utils/markdownRenderer.js 的模块级单例',
  )
})

test('renderMarkdown 用的是模块级单例（两次取到同一个实例）', () => {
  assert.equal(markdownInstance(), markdownInstance())
  assert.equal(typeof markdownInstance().render, 'function')
})

test('首屏同步渲染：data() 返回时就已经有 HTML，不用等一帧', () => {
  const component = loadComponent()
  const vm = makeVm(component, '# 标题')
  assert.match(vm.renderedHtml, /<h1>标题<\/h1>/, '静态预览/历史消息挂载后必须立刻有内容')
})

test('流式期间多次变更只渲染一次，且渲染的是最后一次的内容', async () => {
  let calls = 0
  const spy = (text) => { calls += 1; return renderMarkdown(text) }
  const component = loadComponent(spy)
  const vm = makeVm(component, '')

  const firstRender = calls // data() 里那一次同步渲染
  assert.equal(firstRender, 1, 'data() 应当同步渲染一次')

  // 模拟 40 个 token 陆续到达
  let acc = ''
  for (let i = 0; i < 40; i += 1) {
    acc += `第 ${i} 段。`
    feed(component, vm, acc)
  }
  assert.equal(calls, firstRender, '一帧之内不管来多少 token，都不该立刻重渲')

  await sleep(60)
  assert.equal(calls, firstRender + 1, `40 个 token 只该合并成一次渲染，实际 ${calls - firstRender} 次`)
  assert.match(vm.renderedHtml, /第 39 段。/, '合帧不能丢最后一次内容')
})

test('帧回调跑完后再来的变更会重新排帧（不会从此不再渲染）', async () => {
  let calls = 0
  const component = loadComponent((text) => { calls += 1; return renderMarkdown(text) })
  const vm = makeVm(component, '')

  feed(component, vm, 'A')
  await sleep(60)
  const afterFirst = calls

  feed(component, vm, 'A B')
  await sleep(60)
  assert.equal(calls, afterFirst + 1, '新一帧必须重新排上')
  assert.match(vm.renderedHtml, /A B/)
})

test('卸载时取消待执行的帧（组件没了就不该再渲染）', async () => {
  let calls = 0
  const component = loadComponent((text) => { calls += 1; return renderMarkdown(text) })
  const vm = makeVm(component, '')

  feed(component, vm, '正在写……')
  const before = calls
  component.beforeUnmount.call(vm)
  await sleep(60)
  assert.equal(calls, before, '卸载后不该再有渲染发生')
  assert.equal(vm.renderFrame, null)
})

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
import { nextStableLength } from '../../src/utils/markdownStableSplit.js'

const SRC = readFileSync(new URL('../../src/components/MarkdownPreview.vue', import.meta.url), 'utf8')

/** 把 SFC 的 <script> 变成可调用的组件对象；renderMarkdown 可替换成计数桩。 */
function loadComponent(render = renderMarkdown) {
  const deps = {
    renderMarkdown: render,
    getFileDownloadUrl: async () => '',
    getAuthHeaders: () => ({}),
    // 组件的模块依赖变了就要跟着喂：t 供代码块复制键的文字，copyToClipboard 供事件委托（dev-board#790），
    // nextStableLength 供流式分段渲染（dev-board#811 K31）
    t: (k) => k,
    copyToClipboard: () => true,
    nextStableLength,
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

// 正文现在分成「已定稿前缀 + 尾巴」两段各自 v-html（dev-board#811 K31），
// 用户看到的是两段拼起来的那一整篇。
const shown = (vm) => (vm.stableHtml || '') + (vm.tailHtml || '')

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
  assert.match(shown(vm), /<h1>标题<\/h1>/, '静态预览/历史消息挂载后必须立刻有内容')
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
  assert.match(shown(vm), /第 39 段。/, '合帧不能丢最后一次内容')
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
  assert.match(shown(vm), /A B/)
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

// ---- 分段增量渲染（dev-board#811 K31，审查 C-10）----
//
// 病灶：renderNow 原先是 `renderMarkdown(整篇)`，结果整段写进一个 v-html。长回答后期
// 每帧的解析成本随正文长度线性上涨，而且**每次整段重写都会把用户在正文里选中的文字清掉**。
// 现在正文被切成「已定稿前缀」与「还在长的尾巴」两段各自 v-html：前缀的字符串不变，
// Vue 就不碰它的 DOM。
//
// 这里钉三件事：分段结果必须与整篇渲染逐字相同；前缀真的不再重算；围栏不许被切开。

const longBody = (paragraphs) => Array.from({ length: paragraphs },
  (_, i) => `## 第 ${i + 1} 节\n\n本节说明付款期限与违约责任，建议补充验收标准与逾期解除条件。`).join('\n\n')

test('分段渲染的结果与整篇渲染逐字相同', () => {
  const component = loadComponent()
  const text = longBody(400)
  assert.ok(text.length > 4000, '用例正文要够长才会触发分段')
  const vm = makeVm(component, '')
  vm.content = text
  vm.renderNow()
  assert.ok(vm.stableHtml.length > 0, '够长的正文必须真的切出了定稿前缀')
  assert.equal(shown(vm), renderMarkdown(text, { copyLabel: 'chat.copyCode' }),
    '分段拼起来必须与整篇渲染一模一样，否则用户在流式期间看到的排版是错的')
})

test('前缀定稿后不再重新解析：喂进解析器的字符数不再随正文长度上涨', () => {
  let fed = 0
  const component = loadComponent((text, env) => { fed += (text || '').length; return renderMarkdown(text, env) })
  const vm = makeVm(component, '')
  const text = longBody(400)
  const frames = 30
  fed = 0
  // 分 30 帧把正文喂完（模拟一条长回答的流式过程）
  let wholeEveryFrame = 0
  for (let i = 1; i <= frames; i += 1) {
    const slice = text.slice(0, Math.floor(text.length * i / frames))
    wholeEveryFrame += slice.length   // 改造前每帧要把这么多字符重新解析一遍
    vm.content = slice
    vm.renderNow()
  }
  assert.ok(fed < wholeEveryFrame / 3,
    `分段之后喂进解析器的总字符数应当远小于「每帧整篇」（实测 ${fed}，每帧整篇 ${wholeEveryFrame}）`)
  console.log(`  [K31] 30 帧流式：分段喂进解析器 ${fed} 字符，每帧整篇则是 ${wholeEveryFrame} 字符`)
})

test('围栏里的空行不许当切点：代码块不会被劈成两半', () => {
  const component = loadComponent()
  const filler = longBody(200)
  const text = `${filler}\n\n\`\`\`js\nconst a = 1\n\nconst b = 2\n\nconst c = 3\n\`\`\`\n`
  const vm = makeVm(component, '')
  vm.content = text
  vm.renderNow()
  assert.equal(shown(vm), renderMarkdown(text, { copyLabel: 'chat.copyCode' }))
  assert.equal((shown(vm).match(/<code class="language-js">/g) || []).length, 1, '代码块只该有一个')
})

test('松散列表不许被切开：切点之后必须是一个肯定独立的顶层块', () => {
  const component = loadComponent()
  const filler = longBody(200)
  const list = ['- 第一项', '', '- 第二项', '', '- 第三项'].join('\n')
  const text = `${filler}\n\n${list}\n`
  const vm = makeVm(component, '')
  vm.content = text
  vm.renderNow()
  assert.equal(shown(vm), renderMarkdown(text, { copyLabel: 'chat.copyCode' }),
    '在松散列表的空行处切开会把一张清单渲染成两张')
})

test('正文被换掉（重新生成 / 换一条消息）时推倒重来，不会把两篇拼在一起', () => {
  const component = loadComponent()
  const vm = makeVm(component, '')
  vm.content = longBody(400)
  vm.renderNow()
  assert.ok(vm.stableHtml.length > 0)
  vm.content = '# 换了一篇'
  vm.renderNow()
  assert.equal(shown(vm), renderMarkdown('# 换了一篇', { copyLabel: 'chat.copyCode' }))
})

test('短正文不分段：形态与改造前一致（stableHtml 恒为空串）', () => {
  const component = loadComponent()
  const vm = makeVm(component, '')
  vm.content = '# 标题\n\n一小段正文。'
  vm.renderNow()
  assert.equal(vm.stableHtml, '')
  assert.match(vm.tailHtml, /<h1>标题<\/h1>/)
})

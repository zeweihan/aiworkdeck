// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-13（工具栏溢出无提示）/ BUG-15（下拉 Esc/点外面关不掉、横滚后与按钮脱开）
// 的真渲染回归。
//
// 第一版的病灶：渐隐遮罩与「横滚即收下拉」都监听在 <uni-scroll-view> 根元素上，而
// uni-h5 把 scroll-view 渲染成 uni-scroll-view > div > div(overflow-x:auto) > .uni-scroll-view-content，
// 真正滚动、真正派发 scroll 的是倒数第二层——scroll 不冒泡，外层一次都收不到，外层也从不
// 溢出。只做源码正则断言的测试全绿，真实 DOM 里整段是死代码。
//
// 所以这里：
//  1) 先对 node_modules 里 uni-h5 的 ScrollView render 做一次结构金丝雀——升级 uni 改了
//     层级，这条先红，别让下面的假 DOM 悄悄过时；
//  2) 在 jsdom 里用真 Vue 挂 EditorToolbar.vue 的**真实组件选项**（<script> 原文抽出来，
//     只替换两条 import），模板换成与 uni-h5 同构的 DOM，驱动真实的 mounted/beforeUnmount/
//     methods；jsdom 不排版，scrollWidth/clientWidth/scrollLeft 用属性注入。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { JSDOM } from 'jsdom'

const require = createRequire(import.meta.url)

// ---- jsdom 全局（必须在 import vue 之前：runtime-dom 在模块求值时就取 document）----
const dom = new JSDOM('<!doctype html><html><body><div id="app"></div><div id="outside"></div></body></html>',
  { pretendToBeVisual: true, url: 'http://localhost/' })
for (const k of ['window', 'document', 'navigator', 'Node', 'Element', 'HTMLElement', 'SVGElement',
  'Event', 'KeyboardEvent', 'MouseEvent', 'FocusEvent', 'MutationObserver', 'getComputedStyle']) {
  Object.defineProperty(globalThis, k, { configurable: true, writable: true, value: dom.window[k] })
}
const Vue = await import('vue')
const { bindHorizontalWheel } = await import('../../src/utils/horizontalWheel.js')
const { watchScrollEdges, scrollViewMain, scrollEdges } = await import('../../src/utils/scrollEdges.js')

// ---- 1) uni-h5 ScrollView 渲染结构金丝雀 ----
test('金丝雀：uni-h5 的 scroll-view 仍是 uni-scroll-view > div > div(滚动层) > .uni-scroll-view-content', () => {
  const src = readFileSync(require.resolve('@dcloudio/uni-h5/dist/uni-h5.es.js'), 'utf8')
  const i = src.indexOf('return createVNode("uni-scroll-view"')
  assert.ok(i > 0, 'uni-h5 里找不到 ScrollView 的 render（uni 升级改了实现？先核对真实 DOM 再改 scrollEdges.js）')
  const body = src.slice(i, i + 1600)
  const order = ['"uni-scroll-view"', '"ref": wrap', '"ref": main', 'mainStyle.value', '"ref": content', '"uni-scroll-view-content"']
  let at = 0
  for (const token of order) {
    const j = body.indexOf(token, at)
    assert.ok(j >= at, 'ScrollView render 结构变了，缺少/乱序：' + token)
    at = j
  }
  // overflow-x:auto 挂在 main（倒数第二层）上
  assert.match(src, /realScrollX\.value \? style \+= "overflow-x:auto;"/)
})

// ---- 与 uni-h5 同构的 DOM ----
function defineBox(el, box) {
  for (const [k, v] of Object.entries(box)) {
    Object.defineProperty(el, k, { configurable: true, get: () => box[k], set: (nv) => { box[k] = nv } })
  }
  return box
}
function buildUniScrollView(doc, className) {
  const root = doc.createElement('uni-scroll-view')
  root.className = className
  const wrap = doc.createElement('div'); wrap.className = 'uni-scroll-view'
  const main = doc.createElement('div'); main.className = 'uni-scroll-view'; main.style.overflowX = 'auto'
  const content = doc.createElement('div'); content.className = 'uni-scroll-view-content'
  const row = doc.createElement('div'); row.className = 'etb-row'
  content.appendChild(row); main.appendChild(content); wrap.appendChild(main); root.appendChild(wrap)
  // 外层与 wrap 都不溢出（真实浏览器里就是这样），只有 main 溢出
  defineBox(root, { scrollLeft: 0, clientWidth: 600, scrollWidth: 600 })
  defineBox(wrap, { scrollLeft: 0, clientWidth: 600, scrollWidth: 600 })
  const box = defineBox(main, { scrollLeft: 0, clientWidth: 600, scrollWidth: 1400 })
  return { root, main, content, row, box }
}
const scroll = (el, left, box) => { box.scrollLeft = left; el.dispatchEvent(new dom.window.Event('scroll')) }

test('scrollViewMain 找到的是倒数第二层（真正 overflow 的 div），不是 <uni-scroll-view> 本身', () => {
  const { root, main } = buildUniScrollView(document, 'etb-scroll')
  assert.equal(scrollViewMain(root), main)
  assert.deepEqual(scrollEdges(root), { left: false, right: false }, '外层从不溢出——第一版就是量的它')
  assert.deepEqual(scrollEdges(main), { left: false, right: true })
})

test('watchScrollEdges：内层滚动派发的 scroll 才驱动两端状态；scrollLeft 不变的回调不算「滚了」', () => {
  const { root, main, box, row } = buildUniScrollView(document, 'etb-scroll')
  document.body.appendChild(root)
  const edges = []; let scrolled = 0
  const off = watchScrollEdges(root, { onEdges: (e) => edges.push(e), onScrolled: () => scrolled++ })
  assert.deepEqual(edges.at(-1), { left: false, right: true }, '挂上时先量一次')
  scroll(main, 300, box)
  assert.deepEqual(edges.at(-1), { left: true, right: true })
  assert.equal(scrolled, 1)
  scroll(main, 800, box)
  assert.deepEqual(edges.at(-1), { left: true, right: false })
  // 同一 scrollLeft 再来一次 scroll：不算滚动
  main.dispatchEvent(new dom.window.Event('scroll'))
  assert.equal(scrolled, 2)
  // 外层收到 scroll（真实浏览器里不会发生，这里证明监听不在外层）
  const n = edges.length
  root.dispatchEvent(new dom.window.Event('scroll'))
  assert.equal(edges.length, n, '监听挂错层了：外层的 scroll 不该驱动任何东西')
  off()
  scroll(main, 0, box)
  assert.equal(scrolled, 2, '摘除后不再回调')
  root.remove()
  void row
})

test('watchScrollEdges：按钮组进出（DOM 增删）会重新量，但不算滚动', async () => {
  const { root, box, row } = buildUniScrollView(document, 'etb-scroll')
  box.scrollWidth = 600
  document.body.appendChild(root)
  const edges = []; let scrolled = 0
  const off = watchScrollEdges(root, { onEdges: (e) => edges.push(e), onScrolled: () => scrolled++ })
  assert.deepEqual(edges.at(-1), { left: false, right: false })
  box.scrollWidth = 1400
  row.appendChild(document.createElement('div')) // 表格组 v-if 出现
  await new Promise((r) => setTimeout(r, 0))
  assert.deepEqual(edges.at(-1), { left: false, right: true })
  assert.equal(scrolled, 0)
  off(); root.remove()
})

// ---- 2) 挂真实 EditorToolbar 组件选项 ----
function loadToolbarOptions() {
  const SFC = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
  const script = SFC.slice(SFC.indexOf('<script>') + '<script>'.length, SFC.indexOf('</script>'))
  const imports = script.match(/^import .*$/gm) || []
  assert.deepEqual(imports.map((l) => l.replace(/\s+/g, ' ')), [
    "import { bindHorizontalWheel } from '@/utils/horizontalWheel.js'",
    "import { watchScrollEdges } from '@/utils/scrollEdges.js'",
  ], 'EditorToolbar 的 import 变了，本测试的注入表要跟上')
  const body = script.replace(/^import .*$/gm, '').replace(/export\s+default/, 'return')
  // eslint-disable-next-line no-new-func
  return new Function('bindHorizontalWheel', 'watchScrollEdges', body)(bindHorizontalWheel, watchScrollEdges)
}

async function mountToolbar() {
  const options = loadToolbarOptions()
  let parts = null
  const Comp = {
    ...options,
    // 模板换成与 uni-h5 同构的最小 DOM：.etb 根 > uni-scroll-view.etb-scroll > …；
    // 组件逻辑（data/mounted/beforeUnmount/methods）全是原文。
    render() {
      return Vue.h('div', { class: 'etb' }, [Vue.h('div', { class: 'etb-trigger' })])
    },
    // bindToolbarWheel 在原 mounted 的 nextTick 里跑：在那之前把 uni 结构塞进根节点
    mounted() {
      parts = buildUniScrollView(document, 'etb-scroll')
      this.$el.appendChild(parts.root)
      return options.mounted.call(this)
    },
  }
  const host = document.getElementById('app')
  const app = Vue.createApp(Comp)
  app.config.globalProperties.$t = (k) => k
  app.config.warnHandler = () => {}
  const vm = app.mount(host)
  await Vue.nextTick(); await Vue.nextTick()
  return { vm, app, parts: () => parts }
}

test('EditorToolbar 真挂载：内层横滚驱动 canScrollLeft/Right（BUG-13）', async () => {
  const { vm, app, parts } = await mountToolbar()
  const { main, box } = parts()
  assert.equal(vm.canScrollLeft, false)
  assert.equal(vm.canScrollRight, true, '1400 宽内容塞进 600 宽容器，右边必须提示还能滚')
  scroll(main, 400, box)
  assert.equal(vm.canScrollLeft, true)
  assert.equal(vm.canScrollRight, true)
  scroll(main, 800, box)
  assert.equal(vm.canScrollRight, false)
  app.unmount()
})

test('EditorToolbar 真挂载：下拉开着时横滚即收起（BUG-15 脱开），按钮组进出不收', async () => {
  const { vm, app, parts } = await mountToolbar()
  const { main, box, row } = parts()
  vm.menu = 'insert'
  row.appendChild(document.createElement('div'))
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(vm.menu, 'insert', '内容宽度变化不是滚动，不能把刚打开的下拉关掉')
  scroll(main, 120, box)
  assert.equal(vm.menu, '', '工具栏横滚后弹层会跟触发器脱开，必须收起')
  app.unmount()
})

test('EditorToolbar 真挂载：Esc、点工具栏外、window blur（点画布 webview/iframe）都收起；点工具栏内不收', async () => {
  const { vm, app } = await mountToolbar()
  const outside = document.getElementById('outside')
  const inside = vm.$el.querySelector('.etb-trigger')

  vm.menu = 'style'
  inside.dispatchEvent(new dom.window.MouseEvent('mousedown', { bubbles: true }))
  assert.equal(vm.menu, 'style', '点工具栏自己的 DOM 不能被 document 级收口误关')
  outside.dispatchEvent(new dom.window.MouseEvent('mousedown', { bubbles: true }))
  assert.equal(vm.menu, '', '点右栏/别处要收起')

  vm.menu = 'font'
  document.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  assert.equal(vm.menu, '', 'Esc 要收起')

  vm.menu = 'insert'; vm.insertMode = 'table'
  window.dispatchEvent(new dom.window.FocusEvent('blur'))
  assert.equal(vm.menu, '', '焦点进画布（宿主 window blur）要收起')
  assert.equal(vm.insertMode, '')

  // 卸载后全局监听要摘干净
  app.unmount()
  vm.menu = 'style'
  document.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  window.dispatchEvent(new dom.window.FocusEvent('blur'))
  assert.equal(vm.menu, 'style', '组件卸载后 document/window 上的监听必须摘掉')
})

test('焦点在画布 iframe 里时 Esc 进的是客体 document，宿主收不到——靠 blur 路径兜住（写明的设计，不是漏网）', () => {
  // 验证前提：客体 frame 里的 keydown 不会冒到宿主 document
  const iframe = document.createElement('iframe')
  document.body.appendChild(iframe)
  let hostGot = 0
  const onKey = () => { hostGot++ }
  document.addEventListener('keydown', onKey, true)
  iframe.contentDocument.body.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  document.removeEventListener('keydown', onKey, true)
  iframe.remove()
  assert.equal(hostGot, 0)
  const SFC = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
  assert.match(SFC, /window\.addEventListener\('blur', this\.closeMenus\)/)
})

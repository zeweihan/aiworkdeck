// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-20（章程 C5-05）：顶栏「右栏」开关关掉 AI 面板再打开，当前会话丢失、
// 回到空白新对话，3/3 复现。
//
// 病灶：工作台模板里 `.side-panel-ai` 挂在 `v-if="showAiPanel"` 上。关面板 = 卸载整棵
// 子树，ChatInterface 连同它持有的 currentConversationId、消息列表和 useAgentStream
// 实例一起销毁；再打开是一个全新的 ChatInterface，自然是空白新对话，进行中的流也被
// 卸载钩子掐断。
//
// 这里不抄一份模板：从真源码里抠出 `.side-panel-ai` 开标签上的 v-if / v-show 与
// toggleAiPanel 方法体，用 @vue/compiler-dom 编出真实渲染函数、在自制的内存 renderer
// 上用真实 Vue 运行时挂载，ChatInterface 换成一个记挂载/卸载次数、持有会话 id 与
// 「流进行中」状态的桩。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { compile } from '@vue/compiler-dom'
import * as Vue from 'vue'

const { createRenderer, defineComponent, ref, onMounted, onUnmounted, nextTick } = Vue

const page = readFileSync(
  new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

// ——模板：AI 面板开标签上的显隐指令——
const marker = '<!-- 右侧 AI 面板（可拖拽宽度） -->'
const tagStart = page.indexOf('<view', page.indexOf(marker))
const openTag = page.slice(tagStart, page.indexOf('>', tagStart) + 1)
assert.match(openTag, /class="side-panel side-panel-ai"/, '没抠到 AI 面板的开标签')
const directives = (openTag.match(/\sv-(?:if|show)="[^"]*"/g) || []).join('')
assert.ok(directives, 'AI 面板开标签上没有任何显隐指令')

// ——方法：真实的 toggleAiPanel——
const mStart = page.indexOf('    toggleAiPanel() {')
const mEnd = page.indexOf('\n    },\n', mStart)
const toggleAiPanel = new Function(`return ({${page.slice(mStart, mEnd)}\n    }}).toggleAiPanel`)()

// ——最小内存 renderer（v-show 只需要 el.style.display）——
const nodeOps = {
  insert(child, parent, anchor) {
    child.parent = parent
    const i = anchor ? parent.children.indexOf(anchor) : -1
    if (i >= 0) parent.children.splice(i, 0, child); else parent.children.push(child)
  },
  remove(child) {
    const p = child.parent
    if (p) p.children.splice(p.children.indexOf(child), 1)
    child.parent = null
  },
  createElement: (tag) => ({ tag, children: [], style: {}, props: {}, parent: null }),
  createText: (text) => ({ text, children: [], style: {}, parent: null }),
  createComment: (text) => ({ comment: text, children: [], style: {}, parent: null }),
  setText(node, text) { node.text = text },
  setElementText(el, text) { el.children = []; el.text = text },
  parentNode: (n) => n.parent,
  nextSibling(n) { const p = n.parent; return p ? p.children[p.children.indexOf(n) + 1] || null : null },
  patchProp(el, key, prev, next) { el.props[key] = next },
}
const { createApp } = createRenderer(nodeOps)

function mountWorkbench() {
  const stats = { mounted: 0, unmounted: 0, streamAborted: 0, resized: 0, historyFetched: 0 }
  const ChatInterface = defineComponent({
    setup(_, { expose }) {
      const conversationId = ref(null)
      const streaming = ref(false)
      onMounted(() => { stats.mounted++ })
      onUnmounted(() => {
        stats.unmounted++
        if (streaming.value) stats.streamAborted++ // useAgentStream 的卸载钩子会掐流
      })
      expose({
        conversationId, streaming,
        send(id) { conversationId.value = id; streaming.value = true },
      })
      return () => null
    },
  })
  const { code } = compile(`<div${directives}><ChatInterface ref="chatInterface" /></div>`, { mode: 'function' })
  const render = new Function('Vue', code)(Vue)
  render._rc = true // 运行时编译的 with(_ctx) 渲染函数要走 RuntimeCompiled 代理，否则每次渲染刷 warn
  const App = defineComponent({
    components: { ChatInterface },
    render,
    data: () => ({ showAiPanel: false, aiPanelMounted: false }),
    methods: {
      toggleAiPanel,
      triggerWorkbenchResize() { stats.resized++ },
      refreshAiContextPreview() {},
      fetchChatHistory() { stats.historyFetched++ },
      restoreLastConversation() {},
    },
  })
  const root = nodeOps.createElement('root')
  const vm = createApp(App).mount(root)
  return { vm, stats, root }
}

const panelEl = (root) => root.children.find((n) => n.tag === 'div')

test('BUG-20：关掉右栏再打开，ChatInterface 是同一个实例、会话 id 不变', async () => {
  const { vm, stats } = mountWorkbench()
  vm.toggleAiPanel(); await nextTick(); await nextTick()
  const chat = vm.$refs.chatInterface
  assert.ok(chat, '打开右栏后 ChatInterface 应已挂载')
  chat.send('conv-1')
  for (let i = 0; i < 3; i++) {
    vm.toggleAiPanel(); await nextTick(); await nextTick() // 关
    vm.toggleAiPanel(); await nextTick(); await nextTick() // 开
    assert.equal(vm.$refs.chatInterface, chat, `第 ${i + 1} 轮开关后 ChatInterface 被重建了`)
    assert.equal(vm.$refs.chatInterface.conversationId, 'conv-1', `第 ${i + 1} 轮开关后会话丢了`)
  }
  assert.equal(stats.mounted, 1)
  assert.equal(stats.unmounted, 0)
})

test('BUG-20：流进行中关掉右栏，流不被卸载钩子掐断，再打开仍在流', async () => {
  const { vm, stats } = mountWorkbench()
  vm.toggleAiPanel(); await nextTick(); await nextTick()
  vm.$refs.chatInterface.send('conv-streaming')
  vm.toggleAiPanel(); await nextTick(); await nextTick()
  assert.equal(stats.streamAborted, 0, '关右栏把进行中的流掐了')
  vm.toggleAiPanel(); await nextTick(); await nextTick()
  assert.equal(vm.$refs.chatInterface.streaming, true)
  assert.equal(vm.$refs.chatInterface.conversationId, 'conv-streaming')
})

test('关掉时面板真的隐藏、打开时真的显示；每次开关都触发 triggerWorkbenchResize', async () => {
  const { vm, stats, root } = mountWorkbench()
  vm.toggleAiPanel(); await nextTick(); await nextTick()
  assert.notEqual(panelEl(root).style.display, 'none')
  vm.toggleAiPanel(); await nextTick(); await nextTick()
  assert.equal(panelEl(root).style.display, 'none', '关右栏后面板仍可见')
  vm.toggleAiPanel(); await nextTick(); await nextTick()
  assert.notEqual(panelEl(root).style.display, 'none')
  assert.equal(stats.resized, 3)
  assert.equal(stats.historyFetched, 2, '只有打开那一支刷历史')
})

test('右栏从没打开过时不挂 ChatInterface（保留懒挂载，不给启动加负担）', async () => {
  const { vm, stats } = mountWorkbench()
  await nextTick()
  assert.equal(stats.mounted, 0)
  assert.equal(vm.$refs.chatInterface ?? null, null)
})

test('工作台 data 里声明了懒挂载标志 aiPanelMounted 且默认 false', () => {
  assert.match(page, /\n\s+aiPanelMounted: false,/)
})

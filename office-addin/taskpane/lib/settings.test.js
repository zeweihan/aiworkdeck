// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * settings.js 的 localStorage 安全兜底回归用例。
 *   node --test office-addin/taskpane/lib/settings.test.js
 *
 * 背景（dev-board#74 稳定性审计）：loadSettings/saveSettings 等函数原先是裸
 * localStorage.getItem/setItem，没有 try/catch。Office 的任务窗格 webview
 * 在第三方存储被策略禁用时，localStorage.getItem 会抛 SecurityError；
 * App.vue 的 `<script setup>` 顶层就同步调用 `reactive(loadSettings())`，
 * 早于任何 onMounted，异常会让 createApp(...).mount() 整个抛出、
 * 任务窗格永远白屏，用户连「设置」都进不去。
 *
 * 这里用一个 getItem/setItem 都抛 SecurityError 的假 localStorage 模拟该场景，
 * 断言各导出函数不抛且退回合理默认值。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

/** 替换 globalThis.localStorage，返回恢复函数 */
function stubLocalStorage(impl) {
  const original = globalThis.localStorage
  globalThis.localStorage = impl
  return () => {
    if (original === undefined) delete globalThis.localStorage
    else globalThis.localStorage = original
  }
}

function throwingStorage() {
  return {
    getItem() { throw new DOMException('The operation is insecure.', 'SecurityError') },
    setItem() { throw new DOMException('The operation is insecure.', 'SecurityError') },
    removeItem() { throw new DOMException('The operation is insecure.', 'SecurityError') }
  }
}

test('localStorage.getItem 抛 SecurityError 时 loadSettings 不抛且返回默认值', async () => {
  const restore = stubLocalStorage(throwingStorage())
  try {
    // 每个用例都要拿到未被之前 import 缓存污染的最新模块状态；
    // 但 settings.js 是纯函数式（无模块级可变状态），直接动态 import 一次即可。
    const { loadSettings } = await import('./settings.js')
    let settings
    assert.doesNotThrow(() => { settings = loadSettings() })
    assert.equal(settings.token, '')
    assert.equal(settings.projectId, '')
    // serverUrl 允许退回构建期默认值（非 vite 环境下为空串），但不能抛异常
    assert.equal(typeof settings.serverUrl, 'string')
  } finally {
    restore()
  }
})

test('localStorage.setItem 抛 SecurityError 时 saveSettings 不抛（静默降级）', async () => {
  const restore = stubLocalStorage(throwingStorage())
  try {
    const { saveSettings } = await import('./settings.js')
    assert.doesNotThrow(() => saveSettings({ serverUrl: 'https://example.com', token: 'awdk_x' }))
  } finally {
    restore()
  }
})

test('localStorage 整个未定义（引用即 ReferenceError）时 loadSettings 仍不抛', async () => {
  const original = globalThis.localStorage
  delete globalThis.localStorage
  try {
    const { loadSettings } = await import('./settings.js')
    assert.doesNotThrow(() => loadSettings())
  } finally {
    if (original !== undefined) globalThis.localStorage = original
  }
})

test('saveProjectId / loadConversationId / saveConversationId 在存储抛异常时也不抛', async () => {
  const restore = stubLocalStorage(throwingStorage())
  try {
    const { saveProjectId, loadConversationId, saveConversationId } = await import('./settings.js')
    assert.doesNotThrow(() => saveProjectId('123'))
    let conv
    assert.doesNotThrow(() => { conv = loadConversationId('123') })
    assert.equal(conv, '')
    assert.doesNotThrow(() => saveConversationId('123', 'conv-1'))
    assert.doesNotThrow(() => saveConversationId('123', ''))
  } finally {
    restore()
  }
})

test('存储正常可用时读写行为不受影响（回归正常路径）', async () => {
  const store = new Map()
  const restore = stubLocalStorage({
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)) },
    removeItem: (k) => { store.delete(k) }
  })
  try {
    const { saveSettings, loadSettings, saveProjectId } = await import('./settings.js')
    saveSettings({ serverUrl: 'https://api.example.com/', token: ' awdk_abc ' })
    saveProjectId(42)
    const settings = loadSettings()
    assert.equal(settings.serverUrl, 'https://api.example.com')
    assert.equal(settings.token, 'awdk_abc')
    assert.equal(settings.projectId, '42')
  } finally {
    restore()
  }
})

/**
 * 会话 ID 的存储键还要按**文档**分一层（dev-board#717）。
 *
 * 病灶：只按「项目+宿主」分键时，同一个项目里同时开着的两份 Word 拿到同一个
 * conversationId。跨文档读写是按 conversationId 往 SSE 推命令的，两个窗格于是在通道上
 * 分不开——抢到 emitter 的那个窗格会替另一个执行 read_for_reference，把自己的正文当成
 * 对方文档的内容交回去，全链路没有一处报错。把 docKey 这层去掉，下面两条立刻转红。
 */
test('同项目同宿主的两份文档各有各的会话 ID，互不串门', async () => {
  const store = new Map()
  const restore = stubLocalStorage({
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)) },
    removeItem: (k) => { store.delete(k) }
  })
  try {
    const { loadConversationId, saveConversationId } = await import('./settings.js')
    const docA = 'file:///cases/A/主合同.docx'
    const docB = 'file:///cases/A/补充协议.docx'

    saveConversationId('11', 'conv-A', 'word', docA)

    assert.equal(loadConversationId('11', 'word', docA), 'conv-A')
    assert.equal(loadConversationId('11', 'word', docB), '', 'B 文档不该捡到 A 的会话')

    saveConversationId('11', 'conv-B', 'word', docB)
    assert.equal(loadConversationId('11', 'word', docA), 'conv-A', 'B 落盘不该覆盖 A')
    assert.equal(loadConversationId('11', 'word', docB), 'conv-B')
  } finally {
    restore()
  }
})

test('升级迁移：先开的那份文档认领按项目+宿主分的旧键，认领后旧键即删', async () => {
  const store = new Map()
  const restore = stubLocalStorage({
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)) },
    removeItem: (k) => { store.delete(k) }
  })
  try {
    const { loadConversationId } = await import('./settings.js')
    store.set('awd_addin_conv_word_11', 'conv-老会话')

    assert.equal(loadConversationId('11', 'word', 'file:///cases/A/主合同.docx'), 'conv-老会话')
    assert.equal(store.has('awd_addin_conv_word_11'), false, '旧键留着，第二份文档下次还会读到它')
    // 第二份文档从空白开始，而不是又并回同一条会话
    assert.equal(loadConversationId('11', 'word', 'file:///cases/A/补充协议.docx'), '')
  } finally {
    restore()
  }
})

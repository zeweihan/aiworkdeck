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

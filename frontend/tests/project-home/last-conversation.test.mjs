// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#779 K7④：刷新后回到上次那段对话。
//
// 病灶（2026-09-22 实测 t4-after-reload.png）：律师在 AI 面板里正聊着，打断一次、
// 刷新一次，回来是一段空会话——刚才那段上下文只能自己去历史抽屉里翻。
//
// 这里钉两层：① 纯函数的键名/按项目隔离/容错；② 接线——写在 ChatInterface（会话 id
// 归它所有）、读在工作台页，而且两边是同一个模块。这一层最容易腐烂：各写一份键名
// 字面量不会报错，只是恢复永远不发生。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { loadLastConversation, saveLastConversation } from '../../src/utils/lastConversation.js'

function fakeStorage(initial = {}) {
  const store = { ...initial }
  return {
    store,
    getStorageSync: (key) => (key in store ? store[key] : ''),
    setStorageSync: (key, value) => { store[key] = value },
    removeStorageSync: (key) => { delete store[key] },
  }
}

test('没写过时返回空串', () => {
  assert.equal(loadLastConversation(fakeStorage(), 7), '')
})

test('写进 awd_ 前缀 + projectId 的键，能原样读回', () => {
  const storage = fakeStorage()
  saveLastConversation(storage, 7, 'conv-123-abc')
  assert.equal(storage.store.awd_last_conversation_7, 'conv-123-abc')
  assert.equal(loadLastConversation(storage, 7), 'conv-123-abc')
})

test('按项目隔离：换个案卷读不到上一个案卷的会话', () => {
  const storage = fakeStorage()
  saveLastConversation(storage, 7, 'conv-in-seven')
  saveLastConversation(storage, 8, 'conv-in-eight')
  assert.equal(loadLastConversation(storage, 7), 'conv-in-seven')
  assert.equal(loadLastConversation(storage, 8), 'conv-in-eight')
  assert.equal(loadLastConversation(storage, 9), '')
})

test('数字与字符串 projectId 指向同一个键（工作台传 Number、面板传 String）', () => {
  const storage = fakeStorage()
  saveLastConversation(storage, 7, 'conv-x')
  assert.equal(loadLastConversation(storage, '7'), 'conv-x')
})

test('传空会话 id 即清除：刚点过「新对话」，刷新后不该把旧会话拽回来', () => {
  const storage = fakeStorage({ awd_last_conversation_7: 'conv-old' })
  saveLastConversation(storage, 7, null)
  assert.equal('awd_last_conversation_7' in storage.store, false)
  assert.equal(loadLastConversation(storage, 7), '')
})

test('没有 removeStorageSync 的宿主上降级为写空串，读出来仍是空', () => {
  const store = { awd_last_conversation_7: 'conv-old' }
  const storage = {
    getStorageSync: (key) => (key in store ? store[key] : ''),
    setStorageSync: (key, value) => { store[key] = value },
  }
  saveLastConversation(storage, 7, '')
  assert.equal(loadLastConversation(storage, 7), '')
})

test('projectId 缺失时既不读也不写（不许落到一个所有项目共用的键上）', () => {
  const storage = fakeStorage()
  saveLastConversation(storage, null, 'conv-x')
  saveLastConversation(storage, '', 'conv-x')
  assert.deepEqual(Object.keys(storage.store), [])
  assert.equal(loadLastConversation(storage, undefined), '')
})

test('存储不可用时静默降级，不向上抛', () => {
  const boom = {
    getStorageSync: () => { throw new Error('boom') },
    setStorageSync: () => { throw new Error('boom') },
  }
  assert.equal(loadLastConversation(boom, 7), '')
  assert.doesNotThrow(() => saveLastConversation(boom, 7, 'conv-x'))
})

test('坏值（非字符串）按没写过处理', () => {
  for (const bad of [1, true, {}, [], null]) {
    assert.equal(loadLastConversation(fakeStorage({ awd_last_conversation_7: bad }), 7), '')
  }
})

// ======================================================================
// 接线核实
// ======================================================================

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')

test('ChatInterface 在 currentConversationId 变化时落盘，且刻意不加 immediate', () => {
  const src = read('components/ChatInterface.vue')
  assert.match(src, /import\s*\{\s*saveLastConversation\s*\}\s*from\s*'@\/utils\/lastConversation\.js'/,
    '必须复用同一个模块，不许再写一份键名字面量')
  const line = src.split('\n').find((l) => l.includes('saveLastConversation(uni, props.projectId'))
  assert.ok(line, '找不到落盘的 watch')
  assert.match(line, /watch\(currentConversationId/, '落盘必须挂在 currentConversationId 上')
  assert.doesNotMatch(line, /immediate/,
    '加 immediate 会在挂载那一刻用 null 回写，把工作台正要读的记录当场抹掉——恢复永远不会发生')
})

test('工作台按项目恢复，并且三条闸都在', () => {
  const src = read('pages/project-overview/project-overview.vue')
  assert.match(src, /import\s*\{\s*loadLastConversation\s*\}\s*from\s*'@\/utils\/lastConversation\.js'/)

  const start = src.indexOf('async restoreLastConversation() {')
  assert.ok(start > 0, '找不到 restoreLastConversation')
  const body = src.slice(start, src.indexOf('\n    },', start))

  assert.match(body, /if \(this\.restoredLastConversation\) return/, '每个页面实例只试一次')
  assert.match(body, /this\.restoredLastConversation = true/)
  assert.match(body, /loadLastConversation\(uni, this\.projectId\)/, '必须按项目读，不能读全局键')
  assert.match(body, /this\.chatHistoryList\.some\(/, '会话必须还在这个项目的列表里才恢复')
  assert.match(body, /if \(this\.currentConversationId\) return/, '用户已经自己开了一段就让开，绝不覆盖')
  assert.match(body, /loadHistoryChat\(\{ conversationId \}\)/)
  assert.doesNotMatch(body, /showToast/, '恢复是便利，失败不该用报错打扰人')
})

test('恢复挂在「打开 AI 面板」上：面板默认收起，onLoad 时 ChatInterface 还不存在', () => {
  const src = read('pages/project-overview/project-overview.vue')
  const start = src.indexOf('toggleAiPanel() {')
  assert.ok(start > 0)
  const body = src.slice(start, src.indexOf('async restoreLastConversation() {', start))
  assert.match(body, /this\.restoreLastConversation\(\)/, '打开面板时必须尝试恢复')
})

test('三条「已经指定了要开哪一条」的入口都先关掉恢复，避免两条路同时 loadHistoryChat', () => {
  const src = read('pages/project-overview/project-overview.vue')
  for (const [label, anchor, stop] of [
    ['深链 query.conversationId', 'if (query.conversationId) {', 'toggleAiPanel()'],
    ['openConversationInPanel', 'openConversationInPanel(conversationId) {', 'const wasOpen'],
    ['resolveChatInterface', 'async resolveChatInterface() {', 'this.rightPaneKey'],
  ]) {
    const start = src.indexOf(anchor)
    assert.ok(start > 0, '找不到 ' + label)
    const body = src.slice(start, src.indexOf(stop, start))
    assert.match(body, /this\.restoredLastConversation = true/, label + ' 必须先把恢复闸关掉')
  }
})

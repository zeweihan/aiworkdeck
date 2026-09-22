// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#793 K14 ⑤（审查 E-9）：发 AI 消息前把当前文档落盘。
//
// 病灶：LOWA 的自动保存是防抖的（最长 2.5 秒），而后端读的是磁盘上已保存的那一版。
// 用户敲完一段话立刻回车问「我刚改的这段有没有问题」，模型看到的是改动之前的版本，
// 末位提醒却说「其正文已内联注入…可直接阅读分析」。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { flushActiveDocument } from '../../src/pages/project-overview/flushActiveDocument.js'

function inst(overrides = {}) {
  return {
    ready: true,
    docLoadFailed: false,
    _reloading: false,
    canWrite: true,
    dirty: true,
    saving: false,
    file: { id: 123 },
    flushSave: async function () { this.dirty = false; return true },
    ...overrides,
  }
}

test('脏文档：落盘成功返回 true', async () => {
  const i = inst()
  assert.equal(await flushActiveDocument({ 'left:123': i }, { side: 'left', fileId: 123 }), true)
  assert.equal(i.dirty, false)
})

test('超时参数默认 1.5 秒（这一步串在回车到消息发出之间，等不起 10 秒）', async () => {
  let seen = null
  const i = inst({ flushSave: async function (opt) { seen = opt; this.dirty = false; return true } })
  await flushActiveDocument({ 'left:123': i }, { side: 'left', fileId: 123 })
  assert.equal(seen.timeoutMs, 1500)
})

test('保存返回了但还脏 → false（只信最终状态，不信调用返回）', async () => {
  const i = inst({ flushSave: async () => true })  // 不清 dirty
  assert.equal(await flushActiveDocument({ 'left:123': i }, { side: 'left', fileId: 123 }), false)
})

test('flushSave 抛异常 → false，不往外抛（消息照发，只是降级带壳）', async () => {
  const i = inst({ flushSave: async () => { throw new Error('boom') } })
  assert.equal(await flushActiveDocument({ 'left:123': i }, { side: 'left', fileId: 123 }), false)
})

test('本来就不脏 → true（没什么可落的，磁盘那份就是当前那份）', async () => {
  let called = false
  const i = inst({ dirty: false, flushSave: async () => { called = true; return true } })
  assert.equal(await flushActiveDocument({ 'left:123': i }, { side: 'left', fileId: 123 }), true)
  assert.equal(called, false, '不脏就别白跑一次导出')
})

test('这份文件没开在 LOWA 里（纯文本标签 / 还没加载完）→ true，不要无谓降级', async () => {
  assert.equal(await flushActiveDocument({}, { side: 'left', fileId: 123 }), true)
  assert.equal(await flushActiveDocument({ 'left:123': inst({ ready: false }) },
    { side: 'left', fileId: 123 }), true)
  assert.equal(await flushActiveDocument({ 'left:123': inst({ docLoadFailed: true }) },
    { side: 'left', fileId: 123 }), true)
})

test('焦点在右栏但文档开在左栏也找得到（两者不一定同侧）', async () => {
  const i = inst()
  assert.equal(await flushActiveDocument({ 'left:123': i }, { side: 'right', fileId: 123 }), true)
  assert.equal(i.dirty, false)
})

test('实例上挂的是另一份文件时不动它（绝不拿别的文档去覆盖）', async () => {
  const i = inst({ file: { id: 999 } })
  let called = false
  i.flushSave = async () => { called = true; return true }
  assert.equal(await flushActiveDocument({ 'left:123': i }, { side: 'left', fileId: 123 }), true)
  assert.equal(called, false)
})

test('fileId 非法 → false', async () => {
  assert.equal(await flushActiveDocument({}, { fileId: 'artifact-12' }), false)
  assert.equal(await flushActiveDocument({}, {}), false)
})

test('接线：工作台把它作为 prop 传给 ChatInterface，ChatInterface 在组装 activeContext 前调', () => {
  const PO = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const CI = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
  assert.ok(PO.includes(':flush-active-document="flushActiveDocumentForChat"'))
  assert.ok(PO.includes('flushActiveDocumentForChat(fileId, options)'))
  assert.ok(CI.includes('props.flushActiveDocument'))
  const submit = CI.slice(CI.indexOf('const handleSubmit'), CI.indexOf('const handleAbort'))
  assert.ok(submit.indexOf('flushActiveDocument') < submit.indexOf('const activeContext'),
    'flush 必须发生在组装 activeContext 之前，否则 staleBody 永远是旧值')
  assert.ok(submit.includes('staleBody'), '落不了盘时要让后端只带壳')
})

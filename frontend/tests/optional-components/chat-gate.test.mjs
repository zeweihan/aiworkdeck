// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// AI 对话里的组件门（设计 §4.2）：弹窗 → install → 模型 → ensure → 自动重发原消息。
// 「自动重发」是这条链的重点：让用户装完组件还得自己把刚才那句话再打一遍，
// 等于把失败的代价原样转嫁给他。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createComponentRequiredHandler } from '../../src/composables/useComponentRequired.js'

const payload = {
  action: 'component_required', packId: 'pptx-runtime', service: 'pptx-service',
  modelId: null, sizeMb: 165, features: ['pptxGenerate', 'pdfToWordLayout'], trigger: 'pptx_generate',
}

function deps(over = {}) {
  const calls = []
  return {
    calls,
    installOne: async (item) => { calls.push('install:' + item.packId); item.phase = 'ready'; return true },
    fillSizes: async () => {},
    confirm: async () => true,
    lastUserMessage: () => '帮我做一份关于并购尽调的 PPT',
    resend: async (text) => { calls.push('resend:' + text) },
    toast: () => {},
    ...over,
  }
}

test('payload 直接变成卡片可用的 item（体积从 sizeMb 换算）', () => {
  const h = createComponentRequiredHandler(deps())
  const item = h.itemFromPayload(payload)
  assert.equal(item.packId, 'pptx-runtime')
  assert.equal(item.localeKey, 'pptxRuntime')
  assert.equal(item.service, 'pptx-service')
  assert.equal(item.downloadBytes, 165 * 1024 * 1024)
  assert.equal(item.modelId, null)
  assert.equal(item.phase, 'idle')
})

test('用户确认 → 装好 → 自动把原消息重发一次', async () => {
  const d = deps()
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.deepEqual(r, { installed: true, resent: true })
  assert.deepEqual(d.calls, ['install:pptx-runtime', 'resend:帮我做一份关于并购尽调的 PPT'])
})

test('用户点「暂不下载」→ 什么都不做，绝不重发', async () => {
  const d = deps({ confirm: async () => false })
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.deepEqual(r, { installed: false, resent: false })
  assert.deepEqual(d.calls, [])
})

test('装失败 → 不重发（重发只会再撞一次同样的墙），toast 说明原因', async () => {
  const msgs = []
  const d = deps({
    installOne: async (item) => { item.phase = 'failed'; item.error = '镜像不可达'; return false },
    toast: (m) => msgs.push(m),
  })
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.equal(r.resent, false)
  assert.match(msgs.join(' '), /镜像不可达/)
})

test('拿不到原消息也要照常装完（只是不重发）', async () => {
  const d = deps({ lastUserMessage: () => '' })
  const r = await createComponentRequiredHandler(d).onAction(payload)
  assert.deepEqual(r, { installed: true, resent: false })
})

test('同一轮里重复收到同一个 packId 只处理一次（工具可能连着报两次）', async () => {
  const d = deps()
  const h = createComponentRequiredHandler(d)
  await Promise.all([h.onAction(payload), h.onAction(payload)])
  assert.equal(d.calls.filter((c) => c.startsWith('install:')).length, 1)
})

const chatSrc = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')

test('component_required 在 ChatInterface 就地拦下，不往下透到编辑器执行器', () => {
  assert.match(chatSrc, /action\.action === 'component_required'/)
  // 只看这一条分支的分支体（到下一个 `} else {` 为止）：再往后就是兜底的透传分支了
  const idx = chatSrc.indexOf("action.action === 'component_required'")
  const rest = chatSrc.slice(idx)
  const branch = rest.slice(0, rest.indexOf('} else {'))
  assert.ok(branch.length > 0 && branch.length < 800, '没能定位到 component_required 的分支体')
  assert.ok(!/emit\('client-action'/.test(branch),
    'component_required 不是编辑器命令，透到 EDITOR_ACTIONS 白名单只会得到 Unknown action')
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// AI 对话里的组件门（设计 §4.2）：弹窗 → install → 模型 → ensure → 自动重发原消息。
// 「自动重发」是这条链的重点：让用户装完组件还得自己把刚才那句话再打一遍，
// 等于把失败的代价原样转嫁给他。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createComponentRequiredHandler, shouldAutoResend } from '../../src/composables/useComponentRequired.js'

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

// ---------- dev-board#1016：生成中装完不重发，挂成「组件已就绪，继续」 ----------

test('生成中装完：不自动重发（会变成插话把这一轮打断），挂成待继续并提示可继续', async () => {
  let streaming = true
  const notices = []
  const d = deps({
    mark: () => 1,
    shouldResend: () => shouldAutoResend({
      alive: true, backgrounded: false, streaming, userCountAtGate: 1, userCountNow: 1,
    }),
    readyNotice: (item, opts) => notices.push([item.packId, opts]),
  })
  const h = createComponentRequiredHandler(d)
  const r = await h.onAction(payload)
  assert.deepEqual(r, { installed: true, resent: false, pending: true })
  assert.deepEqual(d.calls, ['install:pptx-runtime'], '生成中绝不能自动重发')
  assert.deepEqual(notices, [['pptx-runtime', { canContinue: true }]])
  const p = h.pendingContinue()
  assert.equal(p.text, '帮我做一份关于并购尽调的 PPT')
  assert.equal(p.packId, 'pptx-runtime')
  assert.equal(p.localeKey, 'pptxRuntime')

  // 仍在生成时点「继续」：兜住，不发，待继续保留
  assert.equal(await h.continuePending({ streaming: true }), false)
  assert.deepEqual(d.calls, ['install:pptx-runtime'])
  assert.ok(h.pendingContinue())

  // 生成结束后点「继续」：发出原消息，且只发一次
  streaming = false
  assert.equal(await h.continuePending({ streaming }), true)
  assert.deepEqual(d.calls, ['install:pptx-runtime', 'resend:帮我做一份关于并购尽调的 PPT'])
  assert.equal(h.pendingContinue(), null)
  assert.equal(await h.continuePending(), false)
})

test('待继续可以放弃（用户发了新消息 / 换了会话）', async () => {
  const d = deps({ shouldResend: () => false })
  const h = createComponentRequiredHandler(d)
  await h.onAction(payload)
  assert.ok(h.pendingContinue())
  h.dismissPending()
  assert.equal(h.pendingContinue(), null)
  assert.equal(await h.continuePending(), false)
  assert.deepEqual(d.calls, ['install:pptx-runtime'])
})

test('自动重发成功时不留待继续', async () => {
  const h = createComponentRequiredHandler(deps())
  await h.onAction(payload)
  assert.equal(h.pendingContinue(), null)
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

// dev-board#1016：ChatInterface 接线 + 反问判据
test('ChatInterface 接了待继续：readyNotice canContinue、继续条、四处 dismiss', () => {
  const src = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
  assert.match(src, /opts && opts\.canContinue[\s\S]{0,120}componentRequiredHandler\.pendingContinue\(\)/)
  assert.match(src, /v-if="componentReadyContinue"/)
  assert.match(src, /:class="\{ disabled: isStreaming \}"/)
  assert.match(src, /continuePending\(\{ streaming: isStreaming\.value \}\)/)
  for (const fn of ['const startNewChat = () => {', "const handleSubmit = async (requestedMode = 'steer') => {", 'const loadMessages = (conversationId, loaded) => {']) {
    const i = src.indexOf(fn)
    assert.ok(i > 0, fn)
    assert.match(src.slice(i, i + 200), /dropComponentPending\(\)/, fn)
  }
  assert.match(src, /watch\(\(\) => props\.projectId, \(\) => \{[^}]*dropComponentPending\(\)/)
})

test('isUserQuestionAwaiting：component_required 不算反问，普通 awaiting_input 算', async () => {
  const { isUserQuestionAwaiting } = await import('../../src/composables/awaitingInput.mjs')
  assert.equal(isUserQuestionAwaiting({ status: 'awaiting_input', reason: 'component_required' }), false)
  assert.equal(isUserQuestionAwaiting({ status: 'awaiting_input' }), true)
  assert.equal(isUserQuestionAwaiting({ status: 'AWAITING_INPUT' }), true)
  assert.equal(isUserQuestionAwaiting({ status: 'finished' }), false)
  assert.equal(isUserQuestionAwaiting(null), false)
  const s = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
  assert.doesNotMatch(s, /=\s*\(?[^\n]*status === 'awaiting_input'\)?\s*$/m)
})

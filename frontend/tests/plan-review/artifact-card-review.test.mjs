// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 计划审阅（dev-board#1022）Task 6：计划卡「打开修订」、SSE saved 事件、工作台接线。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolveSavedPath } from '../../src/utils/planReview.js'
const CARD = readFileSync(new URL('../../src/components/ArtifactCard.vue', import.meta.url), 'utf8')
const STREAM = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

test('从气泡正文取「已保存到项目文件」的路径（中英都认，取最后一次）', () => {
  assert.equal(resolveSavedPath('正文\n> 已保存到项目文件：AI 助手文件/conv-1/Plan.md\n'), 'AI 助手文件/conv-1/Plan.md')
  assert.equal(resolveSavedPath('> Saved to project file: AI Assistant Files/conv-1/Plan.md'), 'AI Assistant Files/conv-1/Plan.md')
  assert.equal(resolveSavedPath('没有'), null)
})
test('计划卡有「打开修订」按钮并发 open-review；useAgentStream 处理 saved', () => {
  assert.match(CARD, /chat\.openRevisionBtn/)
  assert.match(CARD, /\$emit\('open-review'/)
  assert.match(STREAM, /evt\.operation === 'saved'/)
})

// ---- 修复轮 1：修订版必须回到开审阅的那个会话 ----
const OVERVIEW = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
function loadOnPlanReviewSubmit() {
  const m = OVERVIEW.match(/\n {4}async onPlanReviewSubmit\(payload\) \{([\s\S]*?)\n {4}\},\n/)
  assert.ok(m, '找不到 onPlanReviewSubmit')
  // eslint-disable-next-line no-new-func
  return new Function('uni', 'return async function (payload) {' + m[1] + '\n}')
}
function harness({ currentId, switchOk }) {
  const log = []
  const toasts = []
  const chat = {
    currentConversationId: currentId,
    handleReviewSubmit: async (p) => { log.push(['submit', chat.currentConversationId, p.message]) }
  }
  const vm = {
    $t: (k) => k,
    resolveChatInterface: async () => chat,
    loadHistoryChat: async ({ conversationId }) => {
      log.push(['switch', conversationId])
      if (switchOk) chat.currentConversationId = conversationId
      return switchOk
    }
  }
  const fn = loadOnPlanReviewSubmit()({ showToast: (o) => toasts.push(o.title) })
  return { run: (p) => fn.call(vm, p), log, toasts }
}

test('会话不同：先切回开审阅的会话再发', async () => {
  const h = harness({ currentId: 'conv-B', switchOk: true })
  await h.run({ fileId: 1, message: 'M', displayText: 'D', conversationId: 'conv-A' })
  assert.deepEqual(h.log, [['switch', 'conv-A'], ['submit', 'conv-A', 'M']])
})
test('会话不同且切换失败：提示并不发', async () => {
  const h = harness({ currentId: 'conv-B', switchOk: false })
  await h.run({ fileId: 1, message: 'M', displayText: 'D', conversationId: 'conv-A' })
  assert.deepEqual(h.log, [['switch', 'conv-A']])
  assert.deepEqual(h.toasts, ['chat.reviewConversationSwitchFailed'])
})
test('会话相同：不切换直接发', async () => {
  const h = harness({ currentId: 'conv-A', switchOk: true })
  await h.run({ fileId: 1, message: 'M', displayText: 'D', conversationId: 'conv-A' })
  assert.deepEqual(h.log, [['submit', 'conv-A', 'M']])
})

// ---- 最终修复波 I-1：同一文件被后续计划复用时，审阅态只认自己那张卡 ----
function cardOptions() {
  const script = CARD.match(/<script>([\s\S]*?)<\/script>/)[1]
  const body = script.replace(/^import .*$/gm, '').replace('export default', 'return')
  // eslint-disable-next-line no-new-func
  return new Function('MarkdownPreview', 'lineDiffStats', body)({}, () => ({ hunks: 0, added: 0, removed: 0 }))
}
function mountCard(props) {
  const opts = cardOptions()
  const vm = Object.assign({ $t: (k) => k, type: 'implementation_plan', status: 'draft', actionable: true, data: { content: 'x' } }, props, opts.data())
  for (const [k, fn] of Object.entries(opts.computed)) Object.defineProperty(vm, k, { get: fn.bind(vm), configurable: true })
  for (const [k, fn] of Object.entries(opts.methods || {})) vm[k] = fn.bind(vm)
  // 照 Vue 的 immediate watcher：首次渲染即以当前值跑一次
  for (const [key, w] of Object.entries(opts.watch || {})) {
    if (w && w.immediate) w.handler.call(vm, key.split('.').reduce((o, p) => o && o[p], vm))
  }
  return vm
}
test('同 fileId、不同 artifactId：上一份计划的 submitted 不把第二张卡置为已完成，按钮仍在', () => {
  const vm = mountCard({ id: 'art-2', fileId: 7, reviewState: { fileId: 7, artifactId: 'art-1', status: 'submitted', hunks: 2, comments: 1 } })
  assert.equal(vm.localResolved, false)
  assert.equal(vm.effectiveStatus, 'draft')
  assert.equal(vm.showApprovalBar, true)
})
test('同 fileId、不同 artifactId：上一份计划的 open 不在第二张卡上显示「修订中」', () => {
  const vm = mountCard({ id: 'art-2', fileId: 7, reviewState: { fileId: 7, artifactId: 'art-1', status: 'open', hunks: 2, comments: 1 } })
  assert.equal(vm.reviewInProgress, false)
})
test('artifactId 对上（或回传不带 artifactId）照常采用', () => {
  const own = mountCard({ id: 'art-1', fileId: 7, reviewState: { fileId: 7, artifactId: 'art-1', status: 'submitted' } })
  assert.equal(own.localResolved, true)
  assert.equal(own.showApprovalBar, false)
  const legacy = mountCard({ id: 'art-9', fileId: 7, reviewState: { fileId: 7, status: 'open' } })
  assert.equal(legacy.reviewInProgress, true)
})
test('卡片模板的「修订中」徽标读的是过滤后的审阅态', () => {
  assert.doesNotMatch(CARD, /reviewState\.hunks/)
})

// ---- 最终修复波 I-3：宿主只在切会话成功且 sendMessage 已调用后 ack(true) ----
function harnessAck({ currentId, switchOk, chatReady = true, chatHasSubmit = true }) {
  const log = []
  const toasts = []
  const chat = {
    currentConversationId: currentId,
    handleReviewSubmit: chatHasSubmit ? async (p) => { log.push(['submit', chat.currentConversationId]); if (p.ack) p.ack(true) } : undefined
  }
  const vm = {
    $t: (k) => k,
    resolveChatInterface: async () => (chatReady ? chat : null),
    loadHistoryChat: async ({ conversationId }) => {
      if (switchOk) chat.currentConversationId = conversationId
      return switchOk
    }
  }
  const fn = loadOnPlanReviewSubmit()({ showToast: (o) => toasts.push(o.title) })
  const acks = []
  return { run: (p) => fn.call(vm, { ...p, ack: (ok) => acks.push(ok) }), log, toasts, acks }
}
test('I-3 宿主：AI 面板未就绪 → ack(false)', async () => {
  const h = harnessAck({ currentId: 'conv-A', switchOk: true, chatReady: false })
  await h.run({ fileId: 1, message: 'M', conversationId: 'conv-A' })
  assert.deepEqual(h.acks, [false])
})
test('I-3 宿主：切会话失败 → toast 且 ack(false)', async () => {
  const h = harnessAck({ currentId: 'conv-B', switchOk: false })
  await h.run({ fileId: 1, message: 'M', conversationId: 'conv-A' })
  assert.deepEqual(h.acks, [false])
  assert.deepEqual(h.toasts, ['chat.reviewConversationSwitchFailed'])
})
test('I-3 宿主：对话组件缺 handleReviewSubmit → ack(false)', async () => {
  const h = harnessAck({ currentId: 'conv-A', switchOk: true, chatHasSubmit: false })
  await h.run({ fileId: 1, message: 'M', conversationId: 'conv-A' })
  assert.deepEqual(h.acks, [false])
})
test('I-3 宿主：成功路径的 ack 由 handleReviewSubmit 在 sendMessage 调用后给出（只一次 true）', async () => {
  const h = harnessAck({ currentId: 'conv-B', switchOk: true })
  await h.run({ fileId: 1, message: 'M', conversationId: 'conv-A' })
  assert.deepEqual(h.log, [['submit', 'conv-A']])
  assert.deepEqual(h.acks, [true])
})

// ChatInterface.handleReviewSubmit：sendMessage 调用之后才 ack(true)，没消息 ack(false)
const CHAT = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
function loadHandleReviewSubmit(sendMessage) {
  const m = CHAT.match(/\n {4}const handleReviewSubmit = async \((.*?)\) => \{([\s\S]*?)\n {4}\}\n/)
  assert.ok(m, '找不到 handleReviewSubmit')
  const reviewStates = { value: {} }
  // eslint-disable-next-line no-new-func
  const fn = new Function('sendMessage', 'reviewStates', 'props', 'currentModelId', 'currentSkillIds', 'scrollToBottom',
    `return async (${m[1]}) => {${m[2]}\n}`)(sendMessage, reviewStates, { projectId: 1 }, { value: 'm' }, () => [], () => {})
  return { fn, reviewStates }
}
test('I-3 ChatInterface：sendMessage 已调用后才 ack(true)；submitted 态带 artifactId', async () => {
  const order = []
  const { fn, reviewStates } = loadHandleReviewSubmit(() => { order.push('send'); return new Promise(() => {}) })
  fn({ fileId: 7, artifactId: 'art-1', message: 'M', displayText: 'D', ack: (ok) => order.push(['ack', ok]) })
  await new Promise((r) => setTimeout(r, 0))
  assert.deepEqual(order, ['send', ['ack', true]], 'sendMessage 的流还没结束也要先 ack，编辑器才能落库退出')
  assert.equal(reviewStates.value[7].artifactId, 'art-1')
  assert.equal(reviewStates.value[7].status, 'submitted')
})
test('I-3 ChatInterface：没有消息 → ack(false)，不发', async () => {
  const sent = []
  const { fn } = loadHandleReviewSubmit(() => { sent.push(1) })
  const acks = []
  await fn({ fileId: 7, message: '', ack: (ok) => acks.push(ok) })
  assert.deepEqual(acks, [false])
  assert.deepEqual(sent, [])
})

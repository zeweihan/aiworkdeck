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

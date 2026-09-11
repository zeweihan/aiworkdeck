// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Isolated rendering fixture: real chat components, synthetic conversations, no live AI calls.
window.uni = {
  getStorageSync: key => key === 'awd_app_language' ? new URLSearchParams(location.search).get('lang') || 'zh-CN' : '',
  setStorageSync() {}, removeStorageSync() {}, $on() {}, $off() {}, $emit() {}, showToast() {},
  getSystemInfoSync: () => ({ platform: 'mac', windowWidth: innerWidth }),
  request: ({ success }) => success?.({ statusCode: 200, data: [] })
}
window.fetch = async (url, options = {}) => {
  if (String(url).endsWith('/api/agent/chat')) {
    const payload = JSON.parse(options.body)
    return new Response(JSON.stringify({ status: 'accepted', messageId: payload.clientRequestId, state: 'applied', submissionMode: payload.submissionMode, runId: 'fixture-run', sequence: 1 }), { headers: { 'Content-Type': 'application/json' } })
  }
  if (String(url).includes('/connect/')) return new Response(new ReadableStream({ start(controller) { window.sseController = controller } }), { headers: { 'Content-Type': 'text/event-stream' } })
  return new Response(JSON.stringify([]), { headers: { 'Content-Type': 'application/json' } })
}
const { createApp, h, ref, nextTick } = await import('vue')
const { default: ChatInterface } = await import('../../src/components/ChatInterface.vue')
const { i18n } = await import('../../src/i18n/index.js')
const chat = ref()
const app = createApp({ setup: () => () => h(ChatInterface, { ref: chat, projectId: '1', projectName: '合同审查 · 对话展示' }) })
app.use(i18n)
app.mount('#app')
await nextTick()
window.chat = chat.value
window.chatState = chat.value.$.setupState
const todo = [
  { content: '阅读合同及附件', status: 'completed' },
  { content: '核对违约责任与付款安排', activeForm: '正在核对付款与违约条款', status: 'in_progress' },
  { content: '整理修改建议', status: 'pending' }
]
const toolLog = Array.from({ length: 16 }, (_, i) => `<process name="读取第 ${i + 1} 份资料"><step>核对合同材料 ${i + 1}</step><tool_code>read_document({"fileId":${i + 1}})</tool_code><tool_output status="SUCCESS">已核对条款 ${i + 1}，付款期限为30日。</tool_output></process>`).join('')
const planLog = `<process><tool_code>todo_write(${JSON.stringify({ todos: JSON.stringify(todo) })})</tool_code><tool_output status="SUCCESS">已更新清单</tool_output></process>`
window.loadFixture = async (kind = 'long') => {
  const history = [
    { id: 'u1', role: 'USER', content: '请审查这份采购合同，重点关注付款和违约责任。' },
    { id: 'a1', role: 'ASSISTANT', content: `<thinking>我会先核对条款，再整理需要调整的内容。</thinking>${toolLog}${planLog}<final>第一部分：付款安排已核对。</final><final>第二部分：建议明确验收标准及逾期付款责任。</final>` }
  ]
  if (kind !== 'single') history.push(
    { id: 'u2', role: 'USER', content: '请给出完整的风险清单，并说明需要我确认的事项。' },
    { id: 'a2', role: 'ASSISTANT', content: `<thinking>按条款逐一整理风险与修改建议。</thinking>${toolLog}${planLog}<final>## 合同审查结果\n\n${Array.from({ length: 25 }, (_, i) => `### ${i + 1}. ${i % 2 ? '付款安排' : '违约责任'}\n建议补充明确的期限、验收标准和责任边界，以便双方按约履行。\n`).join('\n')}</final>${kind === 'question' ? '<question>是否按30日付款期限修订？<option>按30日修订</option><option>保持原期限</option></question>' : kind === 'approval' ? '<artifact type="implementation_plan">1. 明确付款期限\n2. 补充违约责任</artifact>' : ''}` }
  )
  chat.value.loadMessages(`fixture-${kind}`, history)
  await nextTick()
  await new Promise(resolve => requestAnimationFrame(resolve))
}
await window.loadFixture()
window.ready = true

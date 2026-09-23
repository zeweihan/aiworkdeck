// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Isolated rendering fixture: real chat components, synthetic conversations, no live AI calls.
// 待处理消息（插话）夹具：receipt 的 state 由测试现场决定，DELETE 回一份去掉该条的
// 快照——这正是后端 AgentInboxService.snapshot() 的形状（applied 留在 items 里，
// 只有 DELETED 被过滤掉）。
window.inboxItems = []
window.nextReceiptState = 'applied'
// 项目文件清单：`@` 引用选择器与「从项目选择」页签都从这里来（dev-board#794 K15）。
// 形状照 GET /api/projects/{id}/files?tree=true 的真实返回：扁平、带 parentId 与 isFolder。
window.projectFiles = [
  { id: 1, name: '交易文件', parentId: null, isFolder: true },
  { id: 11, name: '股份认购协议.docx', fileType: 'docx', parentId: 1, isFolder: false },
  { id: 12, name: '股份认购协议-附件清单.xlsx', fileType: 'xlsx', parentId: 1, isFolder: false },
  { id: 13, name: '公司章程.docx', fileType: 'docx', parentId: null, isFolder: false },
]
window.fixtureStorage = JSON.parse(sessionStorage.getItem('chat-fixture-storage') || 'null') || { checkba_user: { id: 1001 }, checkba_session_id: 'synthetic-session' }
window.chatPosts = []
window.uni = {
  getStorageSync: key => key === 'awd_app_language' ? new URLSearchParams(location.search).get('lang') || 'zh-CN' : window.fixtureStorage[key],
  setStorageSync(key, value) { window.fixtureStorage[key] = value; sessionStorage.setItem('chat-fixture-storage', JSON.stringify(window.fixtureStorage)) },
  removeStorageSync(key) { delete window.fixtureStorage[key]; sessionStorage.setItem('chat-fixture-storage', JSON.stringify(window.fixtureStorage)) },
  $on() {}, $off() {}, $emit() {},
  showToast(options) { window.lastToast = options && options.title },
  // 照 uni-app H5 的真实实现走：先试 navigator.clipboard，被拒再退回隐藏 textarea +
  // execCommand('copy')。成功/失败两条分支因此是真的由平台决定的，不是这里写死的。
  //
  // 无头 Chrome 里异步剪贴板 API 恒返回 NotAllowedError（即便 overridePermissions +
  // 用户手势），execCommand('copy') 可用但 'paste'/readText 一律被拒 —— 也就是说
  // **这个环境读不回剪贴板**。所以用例断言的是 copiedText（组件算出来、交给平台的那段文字）
  // 加上 success 分支确实走到了；「系统剪贴板里最后是什么」只能留给真机走查。
  setClipboardData({ data, success, fail }) {
    window.copiedText = data
    const viaTextarea = () => {
      const ta = document.createElement('textarea')
      ta.value = data
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      ta.remove()
      if (!ok) throw new Error('execCommand copy rejected')
    }
    Promise.resolve()
      .then(() => (navigator.clipboard ? navigator.clipboard.writeText(data) : Promise.reject(new Error('no clipboard api'))))
      .catch(() => viaTextarea())
      .then(() => success && success())
      .catch(() => (fail ? fail() : undefined))
  },
  getSystemInfoSync: () => ({ platform: 'mac', windowWidth: innerWidth }),
  request: ({ url, method, data, success }) => {
    // 模型目录：用例在页面脚本执行前把合成的 GET /api/ai/models 响应挂到 window.__modelsFixture
    // （model-pricing.mjs）；没挂时维持原来的空清单，不影响其它用例
    if (String(url).includes('/api/ai/models') && window.__modelsFixture) {
      return success?.({ statusCode: 200, data: window.__modelsFixture })
    }
    if (String(url).includes('/api/ai/config') && new URLSearchParams(location.search).get('provider') === 'local') {
      return success?.({ statusCode: 200, data: { activeProvider: 'OLLAMA' } })
    }
    // 回退/重新生成走的是同一条后端通道，记下来供用例断言「确实先截断了才重发」
    if (String(url).includes('/api/agent/history/rollback')) {
      window.rollbackCalls = [...(window.rollbackCalls || []), data]
      return success?.({ statusCode: 200, data: { status: 'ok' } })
    }
    if (String(url).includes('/api/agent/inbox/')) {
      if ((method || 'GET').toUpperCase() === 'DELETE') {
        const messageId = decodeURIComponent(String(url).split('?')[0].split('/').pop())
        window.inboxItems = window.inboxItems.filter(item => item.id !== messageId)
      }
      return success?.({ statusCode: 200, data: { code: 0, data: { items: window.inboxItems, runId: 'fixture-run', status: 'RUNNING' } } })
    }
    if (/\/api\/projects\/[^/]+\/files/.test(String(url))) {
      return success?.({ statusCode: 200, data: window.projectFiles })
    }
    return success?.({ statusCode: 200, data: [] })
  }
}
window.fetch = async (url, options = {}) => {
  if (String(url).endsWith('/api/agent/chat')) {
    const payload = JSON.parse(options.body)
    window.chatPosts.push(payload)
    const state = window.nextReceiptState
    const receipt = { status: 'accepted', messageId: payload.clientRequestId, state, submissionMode: payload.submissionMode, runId: 'fixture-run', sequence: 1 }
    if (state === 'pending') {
      window.inboxItems = [...window.inboxItems, {
        id: payload.clientRequestId, message: payload.message, displayText: '',
        submissionMode: payload.submissionMode, state: 'pending', position: window.inboxItems.length,
        revision: 0, clientRequestId: payload.clientRequestId, runId: 'fixture-run'
      }]
    }
    return new Response(JSON.stringify(receipt), { headers: { 'Content-Type': 'application/json' } })
  }
  if (String(url).includes('/api/agent/cancel/')) {
    window.cancelCalls = (window.cancelCalls || 0) + 1
    return new Response('{}', { headers: { 'Content-Type': 'application/json' } })
  }
  if (String(url).includes('/connect/')) return new Response(new ReadableStream({ start(controller) { window.sseController = controller } }), { headers: { 'Content-Type': 'text/event-stream' } })
  return new Response(JSON.stringify([]), { headers: { 'Content-Type': 'application/json' } })
}
const { createApp, h, ref, reactive, nextTick, watch } = await import('vue')
// 性能用例（perf.mjs）跑在 page.evaluate 里，那段代码不经 vite 转译，`import 'vue'` /
// `import '@/…'` 都解析不了裸说明符。所以在这里把它要用的两样东西挂出去。
window.__vueWatch = watch
window.__markdownInstance = (await import('@/utils/markdownRenderer.js')).markdownInstance
window.__chatTurnsUrl = new URL('../../src/components/AgentMessage/chatTurns.mjs', import.meta.url).href
const { default: ChatInterface } = await import('../../src/components/ChatInterface.vue')
const { i18n } = await import('../../src/i18n/index.js')
const chat = ref()
window.chatFixtureProps = reactive({ projectId: '1', projectName: '合同审查 · 对话展示' })
const app = createApp({ setup: () => () => h(ChatInterface, { ref: chat, ...window.chatFixtureProps }) })
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
// 钢琴键导航的长会话夹具（dev-board#791 K12）：每轮都是「一问 + 一段思考 + 一次工具
// + 一段正文」，历史执行段默认折叠，DOM 规模与真实长会话同量级。
window.loadManyTurns = async (count = 200) => {
  const history = []
  for (let i = 1; i <= count; i += 1) {
    history.push({ id: `mu${i}`, role: 'USER', content: `第 ${i} 问：请核对第 ${i} 份材料的付款与违约责任。` })
    history.push({
      id: `ma${i}`, role: 'ASSISTANT',
      content: `<thinking>核对第 ${i} 份材料。</thinking><process name="读取第 ${i} 份材料"><tool_code>read_document({"fileId":${i}})</tool_code><tool_output status="SUCCESS">第 ${i} 份材料付款期限为30日。</tool_output></process><final>第 ${i} 份材料：付款期限30日，逾期按万分之五计违约金，建议补充验收标准与逾期解除条件。</final>`
    })
  }
  chat.value.loadMessages(`fixture-many-${count}`, history)
  await nextTick()
  await new Promise(resolve => requestAnimationFrame(resolve))
}

// 运行中的一轮：状态条要报「正在<工具名> · N 秒」而不是「正在执行 N 项操作」（dev-board#792）。
// 直接造一条流式气泡，不经 SSE——这里验的是展示层怎么读数据，不是解析器。
// startedSecondsAgo 让秒数从一个确定值起跳，用例才能断言它在走。
window.loadRunningFixture = async (startedSecondsAgo = 12) => {
  await window.loadFixture('single')
  window.chatState.bubbles.push({
    id: 'live-run', role: 'ASSISTANT', content: '', isStreaming: true,
    thinking: { content: '', status: 'done', duration: 0, startTime: 0 },
    processes: [{
      id: 'live-proc', title: '工具执行', items: [
        { type: 'tool', code: 'search_web({"query":"违约金 上限"})', output: '已返回 5 条结果', status: 'success', startTime: Date.now() - 40000 },
        { type: 'tool', code: 'read_document({"fileId":7})', output: '', status: 'loading', startTime: Date.now() - startedSecondsAgo * 1000 }
      ]
    }],
    artifacts: [], planTodos: [], timeline: []
  })
  await nextTick()
}
await window.loadFixture()
window.ready = true

import { reactive, ref } from 'vue'
import {
  postChat, postCancel, postOfficeResult, createConversation, fetchConversationHistory
} from './api.js'
import { createSseConnection, createTagStreamParser } from './sse.js'
import { readActiveDocument, detectHost } from './wordDoc.js'
import { executeOfficeCommand, commandDisplayName } from './officeExecutor.js'
import { loadConversationId, saveConversationId, isConfigured } from './settings.js'

/**
 * 对话会话的模块级单例状态（import 即共享）。
 *
 * 为什么不放在 ChatView 内：任务窗格切到「设置」视图时 ChatView 被卸载，
 * 组件内的 messages 与 SSE 连接随之销毁——切回来时系统消息与后续回复全收不到。
 * 会话态与连接生命周期因此整体提到组件之外；视图只负责渲染与交互。
 *
 * 连接只在「新对话 / 停止 / 切换项目或账户」时主动关闭；切视图不关。
 */

// ==================== 对外状态 ====================

export const messages = ref([])
/** 输入框草稿：切到设置再切回来不该丢 */
export const input = ref('')
export const streaming = ref(false)
export const reconnecting = ref(false)
/** 错误类提示（红） */
export const banner = ref('')
/** 中性提示（灰），如「上一次的任务仍在进行中」 */
export const notice = ref('')
export const includeDocument = ref(true)
/** 滚动到底部的信号：store 不碰 DOM，视图 watch 这个计数器 */
export const scrollSignal = ref(0)

// ==================== 内部状态 ====================

let ctx = { settings: null, projectId: '' }
// 会话身份 = 服务器 + 令牌 + 项目：任一变化都视为换了会话，重置状态
let sessionKey = null
// 并发保护：activate 期间（拉历史/建连）身份又变了，旧流程的结果一律丢弃
let generation = 0

// 会话 ID 优先由服务端签发（POST /api/agent/conversations）；
// 端点不存在或失败时静默回退客户端生成的 conv-<毫秒>（与主前端一致）。插件会话独立。
let conversationId = null
let connection = null
let parser = null
let currentAssistant = null
// SSE 是否发生过断线重连：只有重连后的 run_state 才用于兜底解锁
// （首连的 run_state 在 send 已置 streaming 之后到达，不能当终态看）
let everReconnected = false
// 本次建连是否由「回灌」触发（任务窗格重建后恢复既有会话）。
// 区分「谁触发的建连」是 run_state 的两种读法的分水岭：
//   - send 触发：streaming 已由 send 置起，首个 run_state 不能当终态；
//   - 回灌触发：首个 run_state 就是当前运行状态的权威答案，据它决定锁不锁输入框。
let restorePending = false

function bumpScroll() {
  scrollSignal.value++
}

// ==================== 会话激活与恢复 ====================

/**
 * 绑定当前的连接配置与项目并恢复会话。视图挂载时、以及 settings/projectId 变化时调用。
 * 身份未变时是空操作——切视图不会打断进行中的对话。
 */
export async function activateSession({ settings, projectId }) {
  ctx.settings = settings
  const pid = projectId || ''
  const key = `${settings ? settings.serverUrl : ''}|${settings ? settings.token : ''}|${pid}`
  if (key === sessionKey) return
  sessionKey = key
  ctx.projectId = pid
  const gen = ++generation

  // 换了项目或账户：旧会话的连接与消息一律丢弃
  closeConnection()
  messages.value = []
  currentAssistant = null
  parser = null
  streaming.value = false
  reconnecting.value = false
  everReconnected = false
  banner.value = ''
  notice.value = ''
  conversationId = null

  if (!pid || !settings || !isConfigured(settings)) return

  // 任务窗格重建（切文档、重开窗格）后：接着上次的会话，而不是从空白开始
  const stored = loadConversationId(pid)
  if (!stored) return
  conversationId = stored

  const history = await fetchConversationHistory(settings, stored)
  if (gen !== generation) return
  if (history.length) {
    messages.value = history.map(toLocalMessage)
    bumpScroll()
  }

  restorePending = true
  try {
    await ensureConnection()
  } catch (e) {
    // 回灌建连失败（后端不可达/令牌失效）不打断用户：下次发送时会再建一次并给出明确报错
    restorePending = false
    console.warn('[Addin] 会话恢复建连失败', e)
  }
}

/**
 * 后端 GET /api/ai/history 的一条记录 → 插件消息模型。
 * 字段：role(USER|ASSISTANT) / content。ASSISTANT 的 content 是带标签的整段文本
 * （<thinking>/<final>/<process>… 见 AgentStreamHandler 协议），用与流式渲染同一个
 * 解析器拆成正文与思考，标签种类保持一致。
 * 工具活动 chip 无法从落库正文还原（历史里没有 requestId/状态），故不回灌——宁缺毋假。
 */
function toLocalMessage(row) {
  const content = row && row.content ? String(row.content) : ''
  const role = row && row.role ? String(row.role).toUpperCase() : 'USER'
  if (role === 'USER') return { role: 'user', text: content }
  let text = ''
  let thinking = ''
  const p = createTagStreamParser({
    onMainText: (t) => { text += t },
    onThinkingText: (t) => { thinking += t }
  })
  p.feed(content)
  p.flush()
  return reactive({ role: 'assistant', text, thinking, streaming: false, error: '', tools: [] })
}

// ==================== SSE ====================

function finishStreaming() {
  if (currentAssistant) currentAssistant.streaming = false
  streaming.value = false
  notice.value = ''
}

/**
 * 取当前正在生成的助手气泡；没有就新建一个。
 * 回灌场景下（窗格重建时后端仍在跑）本地没有气泡，后续 text_delta 到达时才补建。
 */
function ensureAssistantBubble() {
  if (currentAssistant) return currentAssistant
  const assistant = reactive({ role: 'assistant', text: '', thinking: '', streaming: true, error: '', tools: [] })
  messages.value.push(assistant)
  currentAssistant = assistant
  attachParser(assistant)
  return assistant
}

function attachParser(assistant) {
  parser = createTagStreamParser({
    onMainText: (t) => { assistant.text += t },
    onThinkingText: (t) => { assistant.thinking += t }
  })
}

/**
 * 回灌后发现后端还在跑：把历史里最后那条助手消息接着用（编排器按轮次增量落库，
 * 那条正是本轮已生成的部分），后续 text_delta 续写同一个气泡，而不是另起一个。
 */
function adoptLastAssistantBubble() {
  const last = messages.value[messages.value.length - 1]
  if (!last || last.role !== 'assistant') return
  last.streaming = true
  currentAssistant = last
  attachParser(last)
}

function handleEvent(evt, dataStr) {
  if (evt === 'text_delta') {
    let content = dataStr
    try { content = JSON.parse(dataStr).content || '' } catch (e) { /* 按原文处理 */ }
    ensureAssistantBubble()
    if (parser) parser.feed(content)
    bumpScroll()
  } else if (evt === 'bubble_end') {
    if (parser) parser.flush()
    finishStreaming()
  } else if (evt === 'error') {
    let msg = '执行出错'
    try { msg = JSON.parse(dataStr).message || msg } catch (e) { /* ignore */ }
    if (currentAssistant) currentAssistant.error = msg
    finishStreaming()
  } else if (evt === 'cancelled') {
    if (currentAssistant && !currentAssistant.text) currentAssistant.text = '（已停止）'
    finishStreaming()
  } else if (evt === 'client_action') {
    handleClientAction(dataStr)
  } else if (evt === 'run_state') {
    handleRunState(dataStr)
  }
  // connected/heartbeat/plan_update 等其余事件：先忽略
}

/**
 * 建连时后端推送当前运行状态。两种读法，取决于这条连接是谁建的：
 *   - 回灌建连（restorePending）：窗格重建后本地没有 streaming 状态，这条就是权威答案。
 *     仍在跑 → 锁输入并提示，等后续正文经 SSE 推来；否则保持空闲。
 *   - send 建连：streaming 已经置起，首个 run_state 不能当终态看（后端可能还没标 RUNNING）。
 *     只有断线重连之后（everReconnected）才用它兜底解锁——断线期间可能漏掉了 bubble_end。
 */
function handleRunState(dataStr) {
  let status = null
  try { status = JSON.parse(dataStr).status } catch (e) { /* ignore */ }
  const stillRunning = status === 'RUNNING' || status === 'PAUSED' || status === 'AWAITING_APPROVAL'

  if (restorePending) {
    restorePending = false
    if (stillRunning) {
      streaming.value = true
      notice.value = '上一次的任务仍在进行中，正在接收后续回复……'
      adoptLastAssistantBubble()
    }
    return
  }

  if (everReconnected && streaming.value && !stillRunning) {
    if (parser) parser.flush()
    finishStreaming()
  }
}

/**
 * office_command 执行链（Phase C 工具桥）：
 * 后端 OfficeBridgeService 下发 {tool:'office_command', requestId, command, args}
 * → Office.js 执行 → POST /api/agent/office/result 回传。
 * 其余 client_action（editor_command 等 LOWA 契约）与本插件无关，忽略。
 */
async function handleClientAction(dataStr) {
  let action = null
  try { action = JSON.parse(dataStr) } catch (e) { return }
  if (!action || action.tool !== 'office_command' || !action.requestId) return

  const chip = reactive({ label: commandDisplayName(action.command), status: 'running' })
  const assistant = ensureAssistantBubble()
  if (!assistant.tools) assistant.tools = []
  assistant.tools.push(chip)
  bumpScroll()

  const result = await executeOfficeCommand(action.command, action.args)
  chip.status = result.ok ? 'done' : 'failed'
  await postOfficeResult(ctx.settings, {
    requestId: action.requestId,
    ok: result.ok,
    data: result.ok ? result.data : null,
    error: result.ok ? null : result.error
  })
}

async function ensureConnection() {
  if (connection) return
  const conn = createSseConnection({
    baseUrl: ctx.settings.serverUrl,
    token: ctx.settings.token,
    conversationId,
    onEvent: handleEvent,
    onStatus: (status) => {
      if (connection !== conn) return
      if (status === 'reconnecting') {
        everReconnected = true
        reconnecting.value = true
      } else if (status === 'connected') {
        reconnecting.value = false
      }
    },
    onClose: () => {
      if (connection === conn) connection = null
      reconnecting.value = false
      // 连接彻底关闭时不静默卡死输入框（断线重连由 sse.js 内部处理，不走这里）
      if (streaming.value) finishStreaming()
    }
  })
  connection = conn
  try {
    await conn.ready
  } catch (e) {
    if (connection === conn) connection = null
    throw e
  }
}

function closeConnection() {
  restorePending = false
  if (connection) {
    connection.close()
    connection = null
  }
}

// ==================== 交互 ====================

export async function send() {
  banner.value = ''
  if (!ctx.settings || !isConfigured(ctx.settings)) return { needSettings: true }
  if (!ctx.projectId) {
    banner.value = '尚未选择项目：在顶部下拉中选一个项目'
    return { needSettings: false }
  }
  const prompt = input.value.trim()
  if (!prompt || streaming.value) return { needSettings: false }

  const settings = ctx.settings
  const projectId = ctx.projectId
  input.value = ''
  messages.value.push({ role: 'user', text: prompt })

  currentAssistant = null
  parser = null
  const assistant = ensureAssistantBubble()
  // 本轮由 send 触发：run_state 回到「不能当终态」的读法（回灌建连若还没收到 run_state，到此作废）
  restorePending = false
  streaming.value = true
  bumpScroll()

  try {
    // 首条消息前先请求服务端签发会话 ID；旧后端无该端点时静默回退客户端生成。
    // 会话 ID 按项目落本机存储，任务窗格重建后据它接回同一场对话。
    if (!conversationId) {
      conversationId = (await createConversation(settings, parseInt(projectId, 10)))
        || `conv-${Date.now()}`
      saveConversationId(projectId, conversationId)
    }
    await ensureConnection()

    // 当前文档内容以内联形式随请求上送（activeContext.inlineContent）
    let activeContext = null
    if (includeDocument.value) {
      activeContext = await readActiveDocument()
      if (!activeContext) banner.value = '未能读取文档内容，本条消息不附带文档内容'
    }

    await postChat(settings, {
      projectId: parseInt(projectId, 10),
      conversationId,
      message: prompt,
      mode: 'AGENT',
      activeContext,
      // 声明客户端能力（Phase C）：后端据此让本会话只见 office_* 工具、隐藏 doc_*；
      // officeHost 再按宿主细分（word/excel/powerpoint），点名对应工具面
      clientCapability: 'office',
      officeHost: detectHost() || 'word'
    })
  } catch (e) {
    assistant.error = e.message || '消息发送失败'
    finishStreaming()
  }
  return { needSettings: false }
}

export async function stop() {
  if (conversationId) await postCancel(ctx.settings, conversationId)
  closeConnection()
  if (currentAssistant && !currentAssistant.text) currentAssistant.text = '（已停止）'
  finishStreaming()
}

export function newConversation() {
  closeConnection()
  if (ctx.projectId) saveConversationId(ctx.projectId, '')
  conversationId = null
  messages.value = []
  currentAssistant = null
  parser = null
  banner.value = ''
  notice.value = ''
  reconnecting.value = false
  everReconnected = false
  streaming.value = false
}

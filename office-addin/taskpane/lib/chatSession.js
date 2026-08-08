import { reactive, ref } from 'vue'
import {
  postChat, postCancel, postOfficeResult, createConversation, fetchConversationHistory
} from './api.js'
import { createSseConnection, createTagStreamParser } from './sse.js'
import { readActiveDocument, detectHost, hashContent } from './wordDoc.js'
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
/**
 * 最近一轮的耗时切片（毫秒整数）。界面暂不展示，控制台每轮打一条 [AddinPerf]。
 * 「响应慢」得先能被测量：这里把一次发送拆成读文档 / 建连 / 请求受理 / 首字 / 全程五段，
 * 优化前后各跑一遍就能说清快在哪一段。不上报遥测。
 */
export const lastPerf = ref(null)

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
// 建连有三种来源，run_state 的读法各不相同（详见 handleRunState）：
//   - 回灌触发（本标记位为 true）：首个 run_state 就是当前运行状态的权威答案；
//   - 预连触发（进面板/新对话时提前建连）：本地没有进行中的轮次，run_state 无副作用；
//   - send 触发（兜底重试）：streaming 已由 send 置起，首个 run_state 不能当终态。
let restorePending = false

/**
 * 正文省传（内容哈希去重）的会话内状态。
 * 文档没变时只上送哈希，后端按会话从 InlineContentCache 取回上一轮正文。
 * - confirmed：上一轮正常收尾（bubble_end）过——只有这时才敢省传，
 *   因为「后端确实收下并用了这份正文」只有轮次跑完才算数；
 * - disabled：本会话出过 error（也覆盖旧后端不认 inlineContentHash 的情况），
 *   之后整场退回恒传全文，宁可多传也不让模型看不到正文。
 */
let docCache = { conversationId: null, hash: '', confirmed: false, disabled: false }
/** 本轮上送的正文哈希（轮次成功收尾时才提交进 docCache） */
let pendingDocHash = ''

function resetDocCache() {
  docCache = { conversationId: null, hash: '', confirmed: false, disabled: false }
  pendingDocHash = ''
}

function bumpScroll() {
  scrollSignal.value++
}

// ==================== 耗时埋点 ====================

let perfRound = null

function nowMs() {
  return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now()
}

/** 从用户点「发送」的那一刻起表 */
function perfStart() {
  perfRound = {
    t0: nowMs(),
    docReadMs: 0,      // 读当前文档正文 + 算哈希
    docChars: 0,       // 本轮实际上送的正文字符数（省传时为 0）
    docReused: false,  // 本轮是否命中省传（只上送哈希）
    connectMs: 0,      // 本次发送触发的 SSE 建连（预连已就绪时为 0）
    chatAcceptedMs: 0, // POST /chat 返回 200（相对起表）
    firstTokenMs: 0,   // 本轮第一个 text_delta 到达（相对起表）
    totalMs: 0         // 终态事件到达（相对起表）
  }
}

function perfSince() {
  return perfRound ? Math.round(nowMs() - perfRound.t0) : 0
}

function perfEnd() {
  if (!perfRound) return
  const { t0, ...fields } = perfRound
  perfRound = null
  const out = { ...fields, totalMs: Math.round(nowMs() - t0) }
  lastPerf.value = out
  console.info('[AddinPerf]', out)
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
  resetDocCache()

  if (!pid || !settings || !isConfigured(settings)) return

  // 任务窗格重建（切文档、重开窗格）后：接着上次的会话，而不是从空白开始
  const stored = loadConversationId(pid)
  if (stored) {
    conversationId = stored
    const history = await fetchConversationHistory(settings, stored)
    if (gen !== generation) return
    if (history.length) {
      messages.value = history.map(toLocalMessage)
      bumpScroll()
    }
    // 本次建连属于「回灌」，首个 run_state 是权威状态（见 handleRunState）
    restorePending = true
  }

  // 有既有会话就只建连（回灌），没有就先签发再建连（预连）——同一条链，不存在两条并行建连
  try {
    await preconnect()
  } catch (e) {
    // 建连失败（后端不可达/令牌失效）不打断用户：下次发送时会再建一次并给出明确报错
    restorePending = false
    console.warn('[Addin] 会话预连失败', e)
  }
}

/**
 * 备好会话 ID 与 SSE 连接。签发一个往返、建连一个往返，两个都从「发消息」的
 * 关键路径上挪到这里——进面板/切项目时、以及新对话后就做完。
 * 三处调用：activateSession（回灌或预连）、newConversation（新会话预连）、
 * send（兜底重试：前两处失败或还没跑完时）。都已就位时是空操作。
 */
async function preconnect() {
  if (!ctx.projectId || !ctx.settings || !isConfigured(ctx.settings)) return
  if (!conversationId) {
    // 会话 ID 优先服务端签发；旧后端无该端点时静默回退客户端生成。
    // 按项目落本机存储，任务窗格重建后据它接回同一场对话。
    const gen = generation
    const issued = await createConversation(ctx.settings, parseInt(ctx.projectId, 10))
    if (gen !== generation) return
    conversationId = issued || `conv-${Date.now()}`
    saveConversationId(ctx.projectId, conversationId)
  }
  await ensureConnection()
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
  perfEnd()
}

/** 轮次正常收尾：本轮上送的正文哈希可以作为下一轮省传的依据了 */
function commitDocHash() {
  if (!pendingDocHash) return
  docCache = {
    conversationId,
    hash: pendingDocHash,
    confirmed: true,
    disabled: docCache.disabled
  }
  pendingDocHash = ''
}

/** 轮次出错：本会话整场退回恒传全文（也覆盖旧后端不认 inlineContentHash 的情况） */
function disableDocDedup() {
  docCache = { conversationId: null, hash: '', confirmed: false, disabled: true }
  pendingDocHash = ''
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
    if (perfRound && !perfRound.firstTokenMs) perfRound.firstTokenMs = perfSince()
    ensureAssistantBubble()
    if (parser) parser.feed(content)
    bumpScroll()
  } else if (evt === 'bubble_end') {
    if (parser) parser.flush()
    commitDocHash()
    finishStreaming()
  } else if (evt === 'error') {
    let msg = '执行出错'
    try { msg = JSON.parse(dataStr).message || msg } catch (e) { /* ignore */ }
    if (currentAssistant) currentAssistant.error = msg
    disableDocDedup()
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
 * 建连时后端推送当前运行状态。读法取决于这条连接是谁建的，共三种来源：
 *   1. 回灌建连（restorePending=true）：窗格重建后本地没有 streaming 状态，这条就是权威答案。
 *      仍在跑 → 锁输入并提示，等后续正文经 SSE 推来；否则保持空闲。
 *   2. 预连建连（restorePending=false 且 streaming=false）：进面板/新对话时提前建的连，
 *      本地没有进行中的轮次，这条 run_state 不该产生任何副作用——两个 if 都不进，
 *      正是这里要的「无副作用」：既不锁输入（没人在发消息），也不解锁（本来就没锁）。
 *   3. send 建连（兜底重试，streaming=true）：streaming 已由 send 置起，
 *      首个 run_state 不能当终态看（后端可能还没标 RUNNING）。只有断线重连之后
 *      （everReconnected）才用它兜底解锁——断线期间可能漏掉了 bubble_end。
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
  const startedAt = nowMs()
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
  // 只有「本次发送触发了建连」才记时——预连时没有轮次在跑，perfRound 为空
  if (perfRound) perfRound.connectMs = Math.round(nowMs() - startedAt)
}

function closeConnection() {
  restorePending = false
  if (connection) {
    connection.close()
    connection = null
  }
}

// ==================== 交互 ====================

/**
 * 读当前文档正文并算内容哈希。与「确保连接」并行跑——它是发送路径上唯一的长活儿
 * （整篇正文最多 20 万字符），不该排在建连后面等。
 */
async function readDocumentForSend() {
  const startedAt = nowMs()
  const doc = await readActiveDocument()
  const hash = doc ? await hashContent(doc.inlineContent) : ''
  if (perfRound) perfRound.docReadMs = Math.round(nowMs() - startedAt)
  return { doc, hash }
}

/**
 * 组装 activeContext：正文没变（哈希相同）且上一轮正常收尾过，就只上送哈希，
 * 让后端从会话缓存取回正文，省掉整篇正文的上行；否则全文与哈希一起上送。
 * 哈希算不出（crypto.subtle 不可用）时恒传全文。
 */
function buildActiveContext(doc, hash) {
  pendingDocHash = hash
  const reusable = Boolean(hash) && docCache.confirmed && !docCache.disabled
    && docCache.conversationId === conversationId && docCache.hash === hash
  if (reusable) {
    if (perfRound) perfRound.docReused = true
    return { id: doc.id, name: doc.name, fileType: doc.fileType, inlineContentHash: hash }
  }
  if (perfRound) perfRound.docChars = (doc.inlineContent || '').length
  return hash ? { ...doc, inlineContentHash: hash } : { ...doc }
}

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
  perfStart()
  const assistant = ensureAssistantBubble()
  // 本轮由 send 触发：run_state 回到「不能当终态」的读法（回灌建连若还没收到 run_state，到此作废）
  restorePending = false
  // 上一轮若被中途停止，它的待提交哈希就此作废——本轮带不带正文由本轮说了算
  pendingDocHash = ''
  streaming.value = true
  bumpScroll()

  try {
    // 会话 ID 与 SSE 连接正常情况下已由预连备好，这里的 preconnect 只是兜底重试；
    // 读文档与它并行——两件事互不依赖，串起来就是白等一个往返。
    const [read] = await Promise.all([
      includeDocument.value ? readDocumentForSend() : Promise.resolve(null),
      preconnect()
    ])

    // 当前文档内容以内联形式随请求上送（activeContext.inlineContent / inlineContentHash）
    let activeContext = null
    if (read) {
      if (read.doc) activeContext = buildActiveContext(read.doc, read.hash)
      else banner.value = '未能读取文档内容，本条消息不附带文档内容'
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
    if (perfRound) perfRound.chatAcceptedMs = perfSince()
  } catch (e) {
    assistant.error = e.message || '消息发送失败'
    disableDocDedup()
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
  resetDocCache()
  // 立刻预连新会话（签发新 ID + 建 SSE），让下一条消息零建连成本；
  // 这条连接没有轮次在跑，其 run_state 不产生任何副作用（见 handleRunState 第 2 种来源）
  preconnect().catch((e) => console.warn('[Addin] 新会话预连失败', e))
}

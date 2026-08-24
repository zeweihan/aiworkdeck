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
      sealStaleQuestions()
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
    // 会话 ID 优先服务端签发；仅旧后端（端点 404）时回退客户端生成。
    // 按项目落本机存储，任务窗格重建后据它接回同一场对话。
    const gen = generation
    const issued = await createConversation(ctx.settings, parseInt(ctx.projectId, 10))
    if (gen !== generation) return
    conversationId = issued || `conv-${Date.now()}`
    saveConversationId(ctx.projectId, conversationId)
  }
  try {
    await ensureConnection()
  } catch (e) {
    // 自愈：存量会话 ID 已死（云后端签发登记簿是内存态，重启即清；或 localStorage 里
    // 留着历史版本自造的 conv-*）。特征是 connect 403 且本地没有任何消息——有消息的
    // 会话走 DB 归属判定不会 403。丢弃死 ID → 重新签发 → 只重试一次。
    const canHeal = e && e.status === 403 && !messages.value.length
    if (!canHeal) throw e
    const gen = generation
    console.warn('[Addin] 存量会话已失效（connect 403），丢弃并重新签发', conversationId)
    conversationId = null
    saveConversationId(ctx.projectId, '')
    const issued = await createConversation(ctx.settings, parseInt(ctx.projectId, 10))
    if (gen !== generation) return
    conversationId = issued || `conv-${Date.now()}`
    saveConversationId(ctx.projectId, conversationId)
    await ensureConnection()
  }
}

/**
 * 后端 GET /api/ai/history 的一条记录 → 插件消息模型。
 * 字段：role(USER|ASSISTANT) / content / displayContent(可空)。
 *
 * USER 的正文取 `displayContent || content`：模型看 content（可能是回喂给模型的
 * 长文案），用户看 displayContent（一句人话）——「发送内容 ≠ 显示内容」通道，
 * 缺省为 null 时两者同源，与旧后端行为一致。
 *
 * ASSISTANT 的 content 是带标签的整段文本（<thinking>/<final>/<question>… 见
 * AgentStreamHandler 协议），用与流式渲染同一个解析器拆成正文、思考与反问选项，
 * 标签种类保持一致——窗格重建后反问的选项按钮也跟着回来。
 * 工具活动 chip 无法从落库正文还原（历史里没有 requestId/状态），故不回灌——宁缺毋假。
 */
function toLocalMessage(row) {
  const content = row && row.content ? String(row.content) : ''
  const role = row && row.role ? String(row.role).toUpperCase() : 'USER'
  if (role === 'USER') {
    const display = row && row.displayContent ? String(row.displayContent) : ''
    return { role: 'user', text: display || content }
  }
  let text = ''
  let thinking = ''
  let question = null
  const p = createTagStreamParser({
    onMainText: (t) => { text += t },
    onThinkingText: (t) => { thinking += t },
    onQuestion: (q) => { question = q.options.length ? { options: q.options, answered: false } : null }
  })
  p.feed(content)
  p.flush()
  return reactive({ role: 'assistant', text, thinking, streaming: false, error: '', tools: [], question })
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
  const assistant = reactive({
    role: 'assistant', text: '', thinking: '', streaming: true, error: '', tools: [], question: null
  })
  messages.value.push(assistant)
  currentAssistant = assistant
  attachParser(assistant)
  return assistant
}

function attachParser(assistant) {
  parser = createTagStreamParser({
    onMainText: (t) => { assistant.text += t },
    onThinkingText: (t) => { assistant.thinking += t },
    // 反问的选项：正文已经流进气泡，这里只挂备选答案给界面做按钮（无选项则不挂，
    // 用户直接在输入框回答）。一轮里问第二次时后一次覆盖前一次——可点的只有最后一问。
    onQuestion: (q) => {
      assistant.question = q.options.length ? { options: q.options, answered: false } : null
    }
  })
}

/**
 * 只有最末那条消息上的反问才可点：更早的反问后面已经跟了新消息，
 * 留着按钮只会让人以为还能再选一次。与桌面端「仅最新一条助手消息可操作」同口径。
 * 不传 all 时保留最末一条；all=true 表示用户已经作答，全部封掉。
 */
function sealStaleQuestions(all = false) {
  const list = messages.value
  list.forEach((m, i) => {
    if (m.question && (all || i !== list.length - 1)) m.question.answered = true
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
    let status = ''
    try { status = String(JSON.parse(dataStr).status || '') } catch (e) { /* 无 status 按普通收尾 */ }
    finishStreaming()
    // awaiting_input：编排器为了反问主动停机，球在用户这边。输入框此时已解锁
    // （答案就是新一轮普通用户消息），只补一行状态提示，别让人以为回答被吞了。
    // notice 由 finishStreaming 清空，所以要放在它之后。
    if (status.toLowerCase() === 'awaiting_input') notice.value = '等待你的回答，回答后 AI 继续'
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
 *
 * 状态分两档，**不能合成一个 stillRunning**：
 *   - generating（RUNNING/PAUSED）：后端在生成，锁输入等正文；
 *   - awaitingUser（AWAITING_APPROVAL/AWAITING_INPUT）：轮次没结束但球在用户这边。
 *     这一档**必须解锁输入**——插件任务窗格没有桌面端那种「继续」按钮，
 *     答案/确认就是新一轮普通用户消息，锁着输入等于让用户永远答不上话。
 * run_state 的 status 是枚举名（大写），bubble_end 用的是小写字面量，
 * 这里统一大写后比对，免得两套拼写差异变成静默故障。
 */
function handleRunState(dataStr) {
  let status = null
  try { status = JSON.parse(dataStr).status } catch (e) { /* ignore */ }
  const name = status ? String(status).toUpperCase() : ''
  const generating = name === 'RUNNING' || name === 'PAUSED'
  const awaitingUser = name === 'AWAITING_APPROVAL' || name === 'AWAITING_INPUT'
  const awaitingHint = name === 'AWAITING_INPUT'
    ? '等待你的回答，回答后 AI 继续'
    : 'AI 等你确认后继续：把意见发过去即可'

  if (restorePending) {
    restorePending = false
    if (generating) {
      streaming.value = true
      notice.value = '上一次的任务仍在进行中，正在接收后续回复……'
      adoptLastAssistantBubble()
    } else if (awaitingUser) {
      // 窗格重建后接回「等用户」的轮次：不锁输入，只提示球在自己这边
      // （末条助手消息里的反问选项已由历史回灌还原成按钮）
      notice.value = awaitingHint
    }
    return
  }

  if (everReconnected && streaming.value && !generating) {
    if (parser) parser.flush()
    finishStreaming()
    // 断线期间漏掉了 bubble_end：解锁之后把「等用户」这一档的提示补回来
    if (awaitingUser) notice.value = awaitingHint
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

/**
 * 发一条消息。overrideText 非空字符串时这条消息不来自输入框（点反问选项作答），
 * 此时不清空输入框——用户可能正打着别的内容，点个选项不该把草稿吞掉。
 * 类型判断是必需的：模板里若直接把本函数绑到 @click，第一个实参会是事件对象。
 */
export async function send(overrideText) {
  const override = typeof overrideText === 'string' ? overrideText : null
  banner.value = ''
  // 「等你回答/等你确认」的提示随本轮发送作废，别悬在下一轮的流式过程里
  notice.value = ''
  if (!ctx.settings || !isConfigured(ctx.settings)) return { needSettings: true }
  if (!ctx.projectId) {
    banner.value = '尚未选择项目：在顶部下拉中选一个项目'
    return { needSettings: false }
  }
  const prompt = (override === null ? input.value : override).trim()
  if (!prompt || streaming.value) return { needSettings: false }

  const settings = ctx.settings
  const projectId = ctx.projectId
  if (override === null) input.value = ''
  // 用户已经作答（不管是点选项还是自己打字）：所有反问的按钮就此封掉
  sealStaleQuestions(true)
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

/**
 * 点击反问里的一个选项作答。
 *
 * 契约（与桌面端一致）：选项文字**原样**作为这轮的用户消息发出——它本来就短、
 * 像用户自己打的，所以不拼装「我选择了……」这类机器口吻长句，也就不需要
 * 「显示内容 ≠ 发送内容」通道的 displayText。答案是**新一轮普通用户消息**，
 * 不是把上一轮唤醒（编排器侧刻意如此，见 AWAITING_INPUT 停机语义）。
 */
export async function answerQuestion(optionText) {
  const text = (optionText || '').trim()
  if (!text || streaming.value) return { needSettings: false }
  return send(text)
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

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { computed, reactive, ref } from 'vue'
import {
  postChat, postCancel, postOfficeResult, createConversation, fetchConversationHistory,
  fetchConversations, fetchModels, fetchSkills, fetchProjectFiles,
  createProjectFile, uploadFileBytes, ensureAddinDefaultProject,
  deleteConversation as apiDeleteConversation, renameConversation as apiRenameConversation,
  uploadRelayDocument
} from './api.js'
import { createSseConnection, createTagStreamParser } from './sse.js'
import {
  readActiveDocument, readDocumentMeta, detectHost, hashContent,
  executeCommand, hostFamily, documentIdentity
} from './hostBridge.js'
import {
  loadConversationId, saveConversationId, isConfigured, loadModelChoice, saveModelChoice,
  loadArchiveLinks
} from './settings.js'
import { isReadOnlyCommand, captureDocumentBytes, sha256Hex } from './docSnapshot.js'
import { t, getLang, getLangTag } from './i18n.js'
import {
  questionFromParsed, normalizeAskUserEvent, formatAskUserAnswer, parseAskUserAnswer,
  answerDisplayText, linkAskUserAnswers, isAskUserQuestion
} from './askUser.js'
import { runCrossDocWrite, mergeCrossDocBanner } from './crossDocWrite.js'
import { record as recordRevision } from './revisionLog.js'

/**
 * 本窗格的宿主标签，用作会话 ID 存储键的一层作用域（settings.loadConversationId）。
 * **取不到时用 'unknown'，绝不能回落成 'word'**——回落成 word 正好把三个宿主的
 * 窗格并回同一个会话，也就是 dev-board#285 那个 1 Hz 互顶风暴的成因。
 */
function hostScope() {
  return detectHost() || 'unknown'
}

/**
 * 会话 ID 存储键的第三层作用域：**哪一份文档**（dev-board#717 立键，dev-board#767 立规矩）。
 *
 * 只按「项目+宿主」分的话，同一个项目里同时开着的两份 Word 会共用一个 conversationId。
 * 跨文档读写是按 conversationId 往 SSE 推命令的，两个窗格因此在通道上分不开：
 * 抢到 emitter 的那个窗格会替另一个执行 read_for_reference，把自己的正文当成对方文档的
 * 内容交给模型——「参考 A 改 B」这条主用例静默读错文档。后端现在会拒绝这种寻址不到的目标
 * （OpenDocSource.requireAddressable），根子在这里。
 *
 * 缓存一份而不是每处现取：读的键与写的键必须是同一个，中途换文档要整体换（refreshDocScope）。
 * 取不到（普通浏览器调试）回空串 = 退回按「项目+宿主」分，与改造前一致。
 */
let docScopeCache = null

/**
 * 重新取一次文档身份。两处刻意不动缓存：
 *   - **流式进行中**：SSE 通道绑在当前会话上，这一轮的工具命令还在往这份文档下发，
 *     半途换键等于把正在跑的轮次拆掉。等本轮收尾后的下一次再切。
 *   - **取不到身份**（宿主忙/半初始化，key 为空）：当成「没变」，绝不拿空值顶掉一条活会话。
 */
function refreshDocScope() {
  if (docScopeCache && streaming.value) return
  const next = documentIdentity()
  if (!next.key && docScopeCache) return
  docScopeCache = next
}

function docIdentity() {
  if (!docScopeCache) docScopeCache = documentIdentity()
  return docScopeCache
}

function docScope() {
  return docIdentity().key
}

/**
 * 这份文档的会话该不该落盘（dev-board#767）。
 *
 * 新建、还没存过盘的文档一律不落：它没有稳定身份，下次的「新建空白文档」是另一份文档，
 * 把上一份的对话恢复上去正是维护者报的那个病（空白 Document1 里挂着上一篇新闻摘要）。
 * 不落盘 = 每次打开都是新对话；旧对话一条没丢，仍在历史面板里可以手动翻回去。
 * 宿主判不出（普通浏览器调试，key 为空）时退回按「项目+宿主」分，与改造前一致。
 */
function docPersist() {
  const id = docIdentity()
  return id.saved || !id.key
}

/** 记住当前文档的会话 ID（未保存的文档不落盘，见 docPersist）；空值即清除 */
function rememberConversation(convId) {
  if (!ctx.projectId || !docPersist()) return
  saveConversationId(ctx.projectId, convId, hostScope(), docScope())
}

/** 取当前文档上次的会话 ID（未保存的文档从来没落过，恒空串 = 新对话） */
function recallConversation(pid) {
  if (!pid || !docPersist()) return ''
  return loadConversationId(pid, hostScope(), docScope())
}

/**
 * 任务窗格实例身份：每次窗格载入生成一次，**不持久化**。
 * 后端据它区分「同一个窗格重连」与「另一个窗格来抢同一个会话」，
 * 后者做一次性移交（superseded）而不是无声互顶。
 */
function makePaneId() {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  } catch (e) { /* 老内核没有 randomUUID */ }
  return 'pane-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
}
export const paneId = makePaneId()

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
/**
 * 模型正在 <tool_code> 里生成工具参数（如整篇要写入文档的内容）：这段可长达
 * 一两分钟且不产生任何可见正文，此前是渲染盲区——界面据此显示「正在准备文档内容」，
 * 别让盲区伪装成卡死。由流式解析器的 onToolPrep 进出回调驱动。
 */
export const toolPrep = ref(false)
/**
 * 整篇分段过卷的进度（dev-board#422）：{ chunk, total, replaced }，没在过卷时为 null。
 * 后端每次 office_pass_step 返回后推一条 pass_progress，界面据此把「正在操作文档…」
 * 换成「校对 3/12 段 · 已改 7 处」——整篇校对要跑十几分钟，一个不动的转圈跟卡死没区别。
 */
export const passProgress = ref(null)
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

/** 模型清单（null=未拉到/后端不支持，界面隐藏选择器）与当前选择（空串=后端默认） */
export const modelCatalog = ref(null)
export const selectedModel = ref(loadModelChoice())
/**
 * 用户是否显式选中过一个「看不了图」的模型（chooseModel 维护）。
 *
 * 为什么是常驻标记而不是一次性提示：选模型是**常驻配置**不是瞬时事件——做成一次性
 * toast，用户「先选模型、过一会儿才加图片」时就静默了；反过来「先加图片、后换模型」
 * 由 visionNotice 里的附件条件覆盖。两个时机各自会漏一半，所以两条都要有。
 * 也正因为要常驻，刻意不复用 notice/banner：那两个被 send() 与 finishStreaming()
 * 无条件清空，发一条消息提示就没了。选到支持视觉的模型时自行落下。
 */
const nonVisionModelPicked = ref(false)
/** 已启用的 skill 清单与本会话勾选的 skillIds（随每条消息上送，后端按并集激活） */
export const skillList = ref([])
export const selectedSkillIds = ref([])
/** 附加的项目文件（随每条消息以 contextItems 上送，后端按 fileId 读内容） */
export const attachedFiles = ref([])
/** 本地文件上传的中间态条目（上传中/失败；成功即撤下并入 attachedFiles，dev-board#262） */
export const uploadingFiles = ref([])
/** 客户端单文件上限：超限直接标失败，不发起请求 */
export const UPLOAD_MAX_BYTES = 20 * 1024 * 1024
/**
 * 跨文档写入横幅（dev-board#717）：别的窗格的 AI 刚改了本文档时为 {originDocName, count, at}，
 * 否则 null。同一来源 10 秒内连续改合并计数（crossDocWrite.mergeCrossDocBanner）；
 * 界面点「查看」打开修订记录并把它置回 null。
 */
export const crossDocBanner = ref(null)

// ==================== 内部状态 ====================

let ctx = { settings: null, projectId: '' }
// 会话身份 = 服务器 + 令牌 + 项目：任一变化都视为换了会话，重置状态
let sessionKey = null
// 并发保护：activate 期间（拉历史/建连）身份又变了，旧流程的结果一律丢弃
let generation = 0

// 会话 ID 优先由服务端签发（POST /api/agent/conversations）；
// 端点不存在或失败时静默回退客户端生成的 conv-<毫秒>（与主前端一致）。插件会话独立。
let conversationId = null
/**
 * 当前会话在服务端是否已经有落库消息（dev-board#715）。
 *
 * 它是「403 能不能就地自愈」的唯一判据：后端 canUseConversation 对**无消息**的会话
 * 走签发登记簿（内存态，重启即清、24 小时过期），对**有消息**的会话走 DB 归属。
 * 所以 403 有两种含义——无消息＝这个 ID 已经死了（丢掉换一个即可，什么都不会丢），
 * 有消息＝它是别人的会话（换一个也修不好，得如实告诉用户）。
 *
 * **不能拿 messages.value.length 当判据**：send() 在调 preconnect/postChat 之前
 * 就把用户气泡推进去了，于是「本地没有消息」在发送路径上永远不成立——
 * 旧的 connect 自愈因此在发送路径上是一段死代码（dev-board#142 只覆盖到进面板那一次）。
 */
let conversationPersisted = false

/**
 * 窗格身份（dev-board#717）：心跳上送给云端登记簿的那三项。跨窗格下发按「目标窗格
 * **当前**的 conversationId」推 SSE，所以会话一变就得立刻通知心跳补发——否则云端
 * 在下一次心跳（最多 30 秒）之前都拿着旧会话，别的窗格发来的命令全投进一条没人听的
 * 会话里白等超时。conversationId 的每一处赋值都走 setConversationId，别绕过它。
 */
const identityListeners = new Set()
let lastIdentityKey = ''

export function paneIdentity() {
  const pid = parseInt(ctx.projectId, 10)
  return { paneId, conversationId, projectId: Number.isFinite(pid) ? pid : null }
}

/** 注册会话身份变化的回调，返回退订函数 */
export function onIdentityChange(cb) {
  identityListeners.add(cb)
  return () => identityListeners.delete(cb)
}

function notifyIdentity() {
  const id = paneIdentity()
  const key = `${id.projectId}|${id.conversationId}`
  if (key === lastIdentityKey) return
  lastIdentityKey = key
  for (const cb of identityListeners) {
    try { cb(id) } catch (e) { /* 监听者出错不影响会话流程 */ }
  }
}

function setConversationId(next) {
  conversationId = next
  notifyIdentity()
}

let connection = null
/**
 * 当前 SSE 连接是按哪个 conversationId 建的（dev-board#764）。
 *
 * 通道是**按会话**建的：`createSseConnection` 在创建时就把 conversationId 焊进
 * GET /api/agent/connect/{cid} 的地址，之后的重连也一直用它。所以「连接还在」
 * 不等于「连的是当前这条会话」——两者一旦错开，POST /chat 走会话 B、SSE 听着
 * 会话 A，后端推给 B 的 client_action/text_delta/bubble_end 一条都到不了窗格：
 * 工具 chip 不出现、正文不出现、office_command 全部空等 30 秒超时，而**两端都
 * 不报错**（后端把事件存进补发缓冲等一个永远不会来的重连）。2026-09-21 真机
 * Excel 就是这样：SSE 在 conv-…2590、chat 在 conv-…2592，两条会话 ID 由两次
 * 并发签发产生，只差 2 毫秒。
 *
 * 记下绑定的 ID，ensureConnection 每次都比对——不一致即换通道，让这类错配
 * 立刻可见地自愈，而不是静默吞掉整轮。
 */
let connectionConvId = null
let parser = null
let currentAssistant = null
// SSE 是否发生过**轮次中途**的断线重连：只有这种重连之后的 run_state 才用于兜底解锁
// （首连的 run_state 在 send 已置 streaming 之后到达，不能当终态看）。
//
// 判据必须带上「轮次中途」（dev-board#285）：后端每轮结束都会主动关流，
// 客户端随即排一次重连——把那次也算成「断线过」，等于从第一轮结束起就把
// run_state 兜底永久武装上，此后任何一条迟到的 run_state 都能把正在跑的轮次
// 判成已完成并解锁输入框。
let everReconnected = false
// **本轮**有没有真的断过线（dev-board#768）。与 everReconnected 同一处置起，区别只在
// 生命周期：everReconnected 一旦置起就管到会话结束（run_state 兜底一经武装就不撤），
// 而「这一轮的回复是不是被断线吃掉的」必须按轮判——用会话级标志去写文案，等于此后
// 每一次空回复都栽赃给连接。真机那一轮（11:19:38-11:20:32）服务端日志里没有任何重连，
// 界面却言之凿凿地说「回复在连接中断时丢失」，根因就是没有这一维。
let turnDisconnected = false
// 「连接中断，正在自动重连……」的宽限计时器：正常收尾造成的那一秒重连不该报警，
// 否则每一轮结束都闪一次断线横幅，真故障反而淹没在狼来了里（用户录屏里的那条
// 横幅就分不清是哪种）。
let reconnectNoticeTimer = null
const RECONNECT_NOTICE_GRACE_MS = 3000

function clearReconnectNotice() {
  if (reconnectNoticeTimer) { clearTimeout(reconnectNoticeTimer); reconnectNoticeTimer = null }
  reconnecting.value = false
}
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
 * 绑定当前的连接配置、项目**与当前文档**并恢复会话。视图挂载时、settings/projectId 变化时、
 * 以及窗格重新拿到焦点时（可能换了文档，见 syncActiveDocument）调用。
 * 身份未变时是空操作——切视图不会打断进行中的对话。
 *
 * 文档进会话身份是 dev-board#767 的落点：会话按文档绑定，换了文档就该换会话，
 * 而换会话要做的事（关连接、清消息、按新键恢复或新签发、预连）与换项目逐条相同。
 */
function sessionIdentityKey(settings, pid) {
  const cfg = settings || {}
  return `${cfg.serverUrl || ''}|${cfg.token || ''}|${pid || ''}|${docScope()}`
}

export async function activateSession({ settings, projectId }) {
  ctx.settings = settings
  const pid = projectId || ''
  refreshDocScope()
  const key = sessionIdentityKey(settings, pid)
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
  toolPrep.value = false
  passProgress.value = null
  clearReconnectNotice()
  everReconnected = false
  turnDisconnected = false
  banner.value = ''
  notice.value = ''
  setConversationId(null)
  conversationPersisted = false
  attachedFiles.value = []
  uploadingFiles.value = []
  // 换了账户/项目就要重新拉清单，旧清单上做出的「刚选了看不了图的模型」提示随之作废
  nonVisionModelPicked.value = false
  resetDocCache()

  if (!pid || !settings || !isConfigured(settings)) return

  // 模型/skill 清单随会话身份拉一次（失败静默：选择器隐藏，主链路不受影响）
  refreshCatalogs()

  // 任务窗格重建（重开窗格、切回这份文档）后：接着**这份文档**上次的会话，而不是从空白开始。
  // 这份文档没开过插件（含新建、还没存过盘的）时 stored 为空 = 新对话。
  const stored = recallConversation(pid)
  if (stored) {
    setConversationId(stored)
    const history = await fetchConversationHistory(settings, stored)
    if (gen !== generation) return
    // 服务端有落库消息 = 这条会话是真实存在的，之后再 403 就不是「已失效」而是归属问题
    conversationPersisted = history.length > 0
    if (history.length) {
      messages.value = historyToMessages(history)
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
 * 当前文档可能换了（dev-board#767）：窗格随文档窗口走、用户在同一窗格里切了文档，
 * 或者刚把新建的文档另存成了一份有名字的文件。换了就按新文档的会话重来一遍
 * （这份文档开过插件就恢复它最新的那条，没开过就是新对话）；没换是空操作。
 * 返回 true 表示确实换了文档，调用方据此把别的按文档分的状态（修订记录）一并重绑。
 *
 * 流式进行中不切（refreshDocScope 会守住缓存），下一次调用再说。
 */
export async function syncActiveDocument() {
  const before = docIdentity()
  refreshDocScope()
  const after = docIdentity()
  if (after.key === before.key) return false
  // 新建的文档刚被保存：它一直就是这一份文档，只是从此有了名字。把当前会话挂到新键上、
  // 会话身份原地跟上，不重来一遍——否则用户按一次 Ctrl+S，正在进行的对话就被清屏重拉。
  // 真正的「另存为新名」（存过盘的 A → 另一条路径 B）不走这条，按维护者定的规矩当新文档处理。
  if (!before.saved && after.saved && conversationId) {
    rememberConversation(conversationId)
    sessionKey = sessionIdentityKey(ctx.settings, ctx.projectId)
    return true
  }
  await activateSession({ settings: ctx.settings, projectId: ctx.projectId })
  return true
}

/** 模型与 skill 清单：与会话无关，按连接配置拉一次；全部静默降级 */
async function refreshCatalogs() {
  if (!ctx.settings || !isConfigured(ctx.settings)) return
  const settings = ctx.settings
  fetchModels(settings).then((cat) => {
    modelCatalog.value = cat
    // 记住的模型已不在清单里（下线/换区）：回落后端默认，别让请求 400
    if (cat && selectedModel.value && !cat.models.some((m) => m.id === selectedModel.value)) {
      selectedModel.value = ''
      saveModelChoice('')
    }
  }).catch(() => {})
  fetchSkills(settings).then((list) => {
    // 只给用户看「已启用且非 disabled 生效方式」的；勾选态剔除已消失的
    const usable = list.filter((s) => s && s.enabled !== false && s.activation !== 'disabled')
    skillList.value = usable
    selectedSkillIds.value = selectedSkillIds.value.filter((id) => usable.some((s) => s.id === id))
  }).catch(() => {})
}

export function chooseModel(modelId) {
  selectedModel.value = modelId || ''
  saveModelChoice(selectedModel.value)
  // 选定的那一刻就告诉用户这个模型看不了图（三态里只有明确的 false 才算数）
  nonVisionModelPicked.value = activeModelVision.value === false
}

export function toggleSkill(skillId) {
  const cur = selectedSkillIds.value
  selectedSkillIds.value = cur.includes(skillId)
    ? cur.filter((id) => id !== skillId)
    : [...cur, skillId]
}

/** 我在本项目的历史会话列表（给历史面板用；失败回空数组） */
export async function loadConversationList() {
  if (!ctx.settings || !ctx.projectId) return []
  return fetchConversations(ctx.settings, parseInt(ctx.projectId, 10))
}

/** 项目文件清单（附件选择器用；失败回空数组） */
export async function loadProjectFiles() {
  if (!ctx.settings || !ctx.projectId) return []
  return fetchProjectFiles(ctx.settings, ctx.projectId)
}

export function toggleAttachedFile(file) {
  const cur = attachedFiles.value
  attachedFiles.value = cur.some((f) => String(f.id) === String(file.id))
    ? cur.filter((f) => String(f.id) !== String(file.id))
    : [...cur, { id: file.id, name: file.name, fileType: file.fileType || '' }]
}

/** 扩展名 → 后端 fileType（与桌面端 ChatInterface.vue 的 getFileTypeFromName 同一张表） */
function fileTypeFromName(name) {
  const ext = String(name || '').split('.').pop().toLowerCase()
  const map = {
    doc: 'word', docx: 'word',
    xls: 'excel', xlsx: 'excel',
    pdf: 'pdf',
    txt: 'txt',
    ppt: 'ppt', pptx: 'ppt',
    jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', webp: 'image', bmp: 'image',
    md: 'markdown'
  }
  return map[ext] || 'other'
}

// ==================== 模型的视觉能力（能不能直接看图） ====================

/**
 * 当前生效模型支不支持视觉输入。**三态**，undefined 是真实的一档：
 *   true      支持，图片附件作为 image 内容块直送模型；
 *   false     不支持，后端自动降级走既有 OCR 抽文本（降级全自动，客户端不拦截）；
 *   undefined 未知——清单还没拉到，或后端根本不返回 vision 字段。
 *
 * 插件连的是用户自填的服务器地址，很可能是旧后端：把 undefined 当 false 会对**所有**
 * 模型误报「不支持读图」，所以未知时界面什么都不提示。
 *
 * 「当前生效模型」= 显式选中的那个；selectedModel 为空串的语义是「跟随后端默认」，
 * 此时生效的是 modelCatalog.defaultModel——那是绝大多数用户的状态，只对显式选中的
 * 模型判能力等于对多数人永远静默。
 *
 * 用 computed 而非一次性赋值的 ref：refreshCatalogs 在模型下线时会把 selectedModel
 * 静默改回空串，赋值式的 ref 会僵在一个已经不生效的模型上。
 */
export const activeModelVision = computed(() => {
  const cat = modelCatalog.value
  if (!cat || !Array.isArray(cat.models)) return undefined
  const id = selectedModel.value || cat.defaultModel
  if (!id) return undefined
  const hit = cat.models.find((m) => m && m.id === id)
  if (!hit || typeof hit.vision !== 'boolean') return undefined
  return hit.vision
})

/**
 * 后端默认模型在清单里的条目（null=清单里没有它/没拉到）。
 * 模型菜单的「默认模型」那一行据此显示真名与能力角标——那一行此前完全不知道
 * 默认模型是谁，而它恰恰是默认状态。
 */
export const defaultModelInfo = computed(() => {
  const cat = modelCatalog.value
  if (!cat || !cat.defaultModel || !Array.isArray(cat.models)) return null
  return cat.models.find((m) => m && m.id === cat.defaultModel) || null
})

/**
 * 这个条目是不是图片。**不能只看 fileType**：项目树里的 fileType 是后端原样透传的，
 * 桌面端那张扩展名映射表没有 bmp，从桌面端传上来的 .bmp 在项目树里是 'other'；
 * 本模块的 fileTypeFromName 含 bmp，两条判据取并集。
 * 收 {name, fileType} 形状——项目文件、已附附件、上传中间态条目都能直接传。
 */
export function isImageAttachment(item) {
  if (!item) return false
  if (String(item.fileType || '').toLowerCase() === 'image') return true
  return fileTypeFromName(item.name) === 'image'
}

/**
 * 「当前模型看不了图」的一句中性提示（空串=不提示），合并两个各自会漏一半的时机：
 *   - 已经附了图片：条件恒真，用户后来才换模型也照样提示（附件跨轮不清空，
 *     「加图片时提示一次」在换模型后就失效了）；
 *   - 只是刚选了看不了图的模型、还没加图片：由 nonVisionModelPicked 兜住
 *     （「选模型时提示一次」在用户后来才加图片时同样失效）。
 * 不是报错——降级本身是后端自动完成的正常路径，用户只是有权提前知道。
 */
export const visionNotice = computed(() => {
  if (activeModelVision.value !== false) return ''
  // 上传中间态由 upload-row 的条目角标自己交代，这里只看已经附上的
  if (attachedFiles.value.some(isImageAttachment)) return t('visionImagesDowngraded')
  return nonVisionModelPicked.value ? t('visionModelPicked') : ''
})

let uploadSeq = 0

/**
 * 上传本机文件成项目文件并附进对话（dev-board#262）。两步走，参数对齐桌面端
 * confirmUploadAndAddContext：先 createProjectFile 建记录，再 uploadFileBytes 传字节；
 * 成功的文件并入 attachedFiles（真实 fileId，contextItems 契约不变——图片/PDF 由
 * 后端既有 OCR/Tika 抽文本进模型）。期间每个文件在 uploadingFiles 里挂一条中间态，
 * 失败标可读错误、可重试/移除。超限（20MB）直接标失败，不发起请求。
 *
 * 目标项目：未选项目时先懒建「插件临时项目」（App.vue 启动时通常已做过，这里兜底；
 * 账号已有项目但没选中时 ensure 会返回 null，退回「请选择项目」提示）。
 */
export async function uploadLocalFiles(fileList) {
  if (!ctx.settings || !isConfigured(ctx.settings)) return
  const files = Array.from(fileList || []).filter(Boolean)
  if (!files.length) return
  let pid = ctx.projectId
  if (!pid) {
    const created = await ensureAddinDefaultProject(ctx.settings)
    if (created) pid = String(created.id)
  }
  if (!pid) {
    banner.value = t('noProjectBanner')
    return
  }
  const tasks = []
  for (const file of files) {
    const entry = reactive({
      key: ++uploadSeq, name: file.name || t('uploadUnnamedFile'),
      status: 'uploading', error: '', file, projectId: pid
    })
    uploadingFiles.value = [...uploadingFiles.value, entry]
    if ((file.size || 0) > UPLOAD_MAX_BYTES) {
      entry.status = 'failed'
      entry.error = t('uploadTooLarge')
      continue
    }
    tasks.push(runUpload(entry))
  }
  await Promise.all(tasks)
}

/** 单个文件的两步上传。永不 reject——失败全部落在条目上给用户看 */
async function runUpload(entry) {
  const settings = ctx.settings
  try {
    const created = await createProjectFile(settings, entry.projectId, {
      name: entry.name,
      fileType: fileTypeFromName(entry.name),
      size: entry.file.size || 0,
      wpsFileId: `project_${entry.projectId}_doc_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`
    })
    await uploadFileBytes(settings, created.id, entry.file, entry.file.size || 0)
    // 成功：中间态撤下，正式并入附件。createFile 失败时什么都没发生（无半截状态）；
    // 字节上传失败时记录已在服务端但内容为空，同样不并入——附一个空文件比不附更糟，
    // 条目留在失败态可重试（重试会另建一条记录，空记录由用户在项目里自行清理）。
    uploadingFiles.value = uploadingFiles.value.filter((e) => e !== entry)
    if (!attachedFiles.value.some((f) => String(f.id) === String(created.id))) {
      attachedFiles.value = [...attachedFiles.value, {
        id: created.id, name: created.name || entry.name, fileType: created.fileType || ''
      }]
    }
  } catch (e) {
    entry.status = 'failed'
    entry.error = (e && e.message) || t('uploadFailed')
  }
}

export function removeUpload(key) {
  uploadingFiles.value = uploadingFiles.value.filter((e) => e.key !== key)
}

export async function retryUpload(key) {
  const entry = uploadingFiles.value.find((e) => e.key === key)
  if (!entry || entry.status !== 'failed') return
  // 超限的重试没有意义（文件没变），保持失败态等用户移除
  if ((entry.file.size || 0) > UPLOAD_MAX_BYTES) return
  entry.status = 'uploading'
  entry.error = ''
  await runUpload(entry)
}

/** 删除会话：删的是当前会话时就地转为新对话（清本地 ID 与消息） */
export async function removeConversation(convId) {
  if (!ctx.settings) throw new Error(t('connectionNotReadySimple'))
  await apiDeleteConversation(ctx.settings, convId)
  if (convId === conversationId) newConversation()
}

export async function retitleConversation(convId, title) {
  if (!ctx.settings) throw new Error(t('connectionNotReadySimple'))
  await apiRenameConversation(ctx.settings, convId, title)
}

/**
 * 切换到既有会话：与 activateSession 的回灌路径同一套语义（restorePending +
 * 首个 run_state 权威），但不重置会话身份。当前会话仍在流式时不允许切（按钮已禁用，
 * 这里再守一道）。
 */
export async function switchConversation(convId) {
  if (!convId || convId === conversationId) return
  if (!ctx.projectId || !ctx.settings || !isConfigured(ctx.settings)) return
  if (streaming.value) return
  const gen = ++generation
  closeConnection()
  messages.value = []
  currentAssistant = null
  parser = null
  clearReconnectNotice()
  everReconnected = false
  turnDisconnected = false
  banner.value = ''
  notice.value = ''
  resetDocCache()
  setConversationId(convId)
  rememberConversation(convId)
  const history = await fetchConversationHistory(ctx.settings, convId)
  if (gen !== generation) return
  conversationPersisted = history.length > 0
  if (history.length) {
    messages.value = historyToMessages(history)
    sealStaleQuestions()
    bumpScroll()
  }
  restorePending = true
  try {
    await preconnect()
  } catch (e) {
    restorePending = false
    console.warn('[Addin] 切换会话建连失败', e)
  }
}

/**
 * 同一时刻只许有一条 preconnect 在跑（dev-board#764）。
 *
 * 不串起来的后果是**两条会话**：`activateSession` 的预连还卡在签发那个往返上时，
 * 用户已经把消息发出去了，send 的兜底 preconnect 看到 conversationId 仍是 null，
 * 于是又签发一次。两个 ID 只差几毫秒（真机实测 conv-…2590 / conv-…2592），
 * 先回来的那个建了 SSE，后回来的那个覆盖了 conversationId 并被 POST /chat 带走——
 * 从此这一轮的所有事件都推给一条没人听的会话。
 *
 * 只共享**正在跑**的那一次：settle 后立刻清空，下一次调用照旧真跑一遍
 * （send 前的 reconnectNow 唤醒不能被跳过，见 ensureConnection）。
 * 按 generation 判等，是因为切项目/切账户/切会话/新对话都会换掉会话身份——
 * 那之后旧流程的结果已经作废，不能再复用。
 */
let preconnectInFlight = null
let preconnectGen = -1

function preconnect() {
  if (preconnectInFlight && preconnectGen === generation) return preconnectInFlight
  const gen = generation
  preconnectGen = gen
  const run = runPreconnect().finally(() => {
    // 期间身份又变了的话，清掉的就不是自己这一次了——按 generation 认领
    if (preconnectGen === gen) { preconnectInFlight = null; preconnectGen = -1 }
  })
  preconnectInFlight = run
  return run
}

/**
 * 备好会话 ID 与 SSE 连接。签发一个往返、建连一个往返，两个都从「发消息」的
 * 关键路径上挪到这里——进面板/切项目时、以及新对话后就做完。
 * 三处调用：activateSession（回灌或预连）、newConversation（新会话预连）、
 * send（兜底重试：前两处失败或还没跑完时）。都已就位时是空操作。
 * 外面永远经 preconnect() 调，别直接调这个——并发保护在那一层。
 */
async function runPreconnect() {
  if (!ctx.projectId || !ctx.settings || !isConfigured(ctx.settings)) return
  // 签发到一半会话身份又变了（切项目/切账户）：本次流程整体作废，别拿旧身份去建连
  if (!conversationId && !(await issueConversation())) return
  try {
    await ensureConnection()
  } catch (e) {
    // 自愈：存量会话 ID 已死（云后端签发登记簿是内存态，重启即清、24 小时过期；
    // 或 localStorage 里留着历史版本自造的 conv-*）。丢弃死 ID → 重新签发 → 只重试一次。
    if (!canHealConversation(e)) throw e
    console.warn('[Addin] 存量会话已失效（connect 403），丢弃并重新签发', conversationId)
    if (!(await renewConversation())) return
    await ensureConnection()
  }
}

/**
 * 这次失败是不是「会话 ID 已经死了、换一条就能继续」。
 *
 * 403 = 后端 canUseConversation 不放行；404 = 会话不存在（旧后端/被删）。两者在
 * **服务端没有落库消息**时含义相同：这个 ID 作废了，换一个新的什么都不会丢。
 * 有落库消息的会话 403 是归属问题（别的账号/别的设备），换 ID 修不好，也不该
 * 悄悄把用户看得见的历史扔掉——那一档走 conversationDenied 文案如实交代。
 */
function canHealConversation(e) {
  const status = e && e.status
  return (status === 403 || status === 404) && !conversationPersisted
}

/** 签发一个新会话 ID 并落盘（旧后端端点 404 时回退客户端自造）。 */
async function issueConversation() {
  const gen = generation
  const issued = await createConversation(ctx.settings, parseInt(ctx.projectId, 10))
  if (gen !== generation) return false
  setConversationId(issued || `conv-${Date.now()}`)
  rememberConversation(conversationId)
  conversationPersisted = false
  return true
}

/**
 * 丢弃当前（已死的）会话 ID 换一个新的。返回 false 表示期间会话身份又变了，
 * 调用方应当整体放弃本次流程（结果已作废）。
 *
 * 连接也要一并关掉：它是绑在旧 conversationId 上建起来的，留着它的话
 * ensureConnection 会当成「已连好」直接返回，新会话反而没有通道。
 */
async function renewConversation() {
  closeConnection()
  setConversationId(null)
  conversationPersisted = false
  rememberConversation('')
  // 新会话在后端没有 InlineContentCache 条目，正文省传的前提不复存在
  resetDocCache()
  return issueConversation()
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
    // ask_user 的结构化回答（dev-board#868）：读回当时选了什么，给上一问的只读态高亮
    // （linkAskUserAnswers）。显示文本缺失时（旧后端不存 displayContent）从回答里拼一句，
    // 绝不把 <ask_user_answer id=…> 原文摆进用户气泡
    const askAnswer = parseAskUserAnswer(content)
    if (askAnswer) {
      return { role: 'user', text: display || answerDisplayText(askAnswer, getLang() === 'en'), askAnswer }
    }
    return { role: 'user', text: display || content }
  }
  let text = ''
  let thinking = ''
  let question = null
  let artifact = ''
  const p = createTagStreamParser({
    // 标签间的裸换行不进正文（与桌面端 useAgentStream 的守卫同口径，dev-board#147）；
    // 首个非空块自带的前导空白同样裁掉——"\n\n正文" 混合块曾让气泡顶部先空一截（dev-board#197）
    onMainText: (t) => { if (!text) { t = t.replace(/^\s+/, ''); if (!t) return } text += t },
    onThinkingText: (t) => { if (!thinking) { t = t.replace(/^\s+/, ''); if (!t) return } thinking += t },
    onQuestion: (q) => { question = questionFromParsed(q) },
    onArtifact: (c) => { artifact = artifact ? artifact + '\n\n' + c : c }
  })
  p.feed(content)
  p.flush()
  text = text.replace(/\s+$/, '')
  return reactive({ role: 'assistant', text, thinking, streaming: false, error: '', tools: [], question, artifact })
}

/**
 * 一整页历史 → 插件消息列表。ask_user 的问题与紧跟着的那条结构化回答在这里对上
 * （回灌后的旧问题卡是只读的，并高亮当时的选择）。
 */
function historyToMessages(history) {
  return linkAskUserAnswers(history.map(toLocalMessage))
}

// ==================== SSE ====================

function finishStreaming() {
  if (currentAssistant) currentAssistant.streaming = false
  streaming.value = false
  toolPrep.value = false
  passProgress.value = null
  notice.value = ''
  perfEnd()
  // 文档镜像（dev-board#299）：本轮真的写过文档且项目有归档绑定才触发；
  // finishStreaming 是所有轮次终态（bubble_end/error/cancelled/run_state 兜底）的
  // 单一汇合点，挂这里保证「写了就有机会归档」，maybeArchiveSnapshot 自身全程无害降级
  maybeArchiveSnapshot()
}

// ==================== 文档镜像（dev-board#299） ====================

/** 本轮是否执行过成功的写入类 office_command（handleClientAction 置位，快照触发后清零） */
let turnHadWrite = false
let snapshotInFlight = false
let snapshotQueued = false
/** 每个 (设备|项目|文件) 上一次成功上传的内容哈希：没变就不重复上传 */
const lastSnapshotHash = new Map()
/** 「此环境拿不到文档字节」只提示一次（拍板点 4：只提示不硬凑） */
let archiveUnsupportedNotified = false

function randomId() {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  } catch (e) { /* 老内核 */ }
  // 服务端 clientMediaId 只收 UUID 形态（路径穿越围栏），手工拼一个合规的
  const hex = () => Math.floor(Math.random() * 0xffff).toString(16).padStart(4, '0')
  return `${hex()}${hex()}-${hex()}-4${hex().slice(1)}-8${hex().slice(1)}-${hex()}${hex()}${hex()}`
}

function maybeArchiveSnapshot() {
  if (!turnHadWrite) return
  const pid = ctx && ctx.projectId ? String(ctx.projectId) : ''
  const binding = pid ? loadArchiveLinks()[pid] : null
  if (!binding || !binding.deviceId || !binding.projectKey) {
    turnHadWrite = false
    return
  }
  turnHadWrite = false
  if (snapshotInFlight) {
    snapshotQueued = true
    return
  }
  runArchiveSnapshot(binding)
}

async function runArchiveSnapshot(binding) {
  snapshotInFlight = true
  try {
    const cap = await captureDocumentBytes()
    if (!cap) {
      // 网页版 Word/Excel、未保存过的 WPS 新文档、或 WPS FileSystem 链不可用：
      // 诚实降级，绝不上传文本重构的假文档
      if (!archiveUnsupportedNotified) {
        archiveUnsupportedNotified = true
        if (!notice.value) notice.value = t('archiveCaptureUnsupported')
      }
      return
    }
    const hash = await sha256Hex(cap.bytes)
    const key = binding.deviceId + '|' + binding.projectKey + '|' + cap.fileName
    if (hash && lastSnapshotHash.get(key) === hash) return
    await uploadRelayDocument(ctx.settings, {
      bytes: cap.bytes,
      fileName: cap.fileName,
      deviceId: binding.deviceId,
      projectKey: binding.projectKey,
      clientMediaId: randomId()
    })
    // 哈希在上传成功后才提交：失败时下一轮写入会连同本轮内容一起重传
    if (hash) lastSnapshotHash.set(key, hash)
    console.info('[AddinArchive] 文档快照已归档', cap.fileName)
  } catch (e) {
    console.warn('[AddinArchive] 文档快照归档失败（下次写入后重试）', e)
  } finally {
    snapshotInFlight = false
    if (snapshotQueued) {
      snapshotQueued = false
      runArchiveSnapshot(binding)
    }
  }
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
    // 前导空白守卫（dev-board#147）：模型输出的协议标签之间全是裸换行，栈空时会被
    // 解析器当主文本放行；正文还没开张就先攒十几个换行，光标被推着往下走一屏。
    // 与桌面端 useAgentStream 的同名守卫保持同口径：正文为空时纯空白直接丢弃，
    // 且首个非空块的前导空白也裁掉（"\n\n正文" 混合块此前会带着换行进气泡，dev-board#197）。
    onMainText: (t) => { if (!assistant.text) { t = t.replace(/^\s+/, ''); if (!t) return } assistant.text += t },
    onThinkingText: (t) => { if (!assistant.thinking) { t = t.replace(/^\s+/, ''); if (!t) return } assistant.thinking += t },
    // <artifact>（计划/交付物）整块闭合时挂到消息上，界面渲染成计划卡（dev-board#150）
    onArtifact: (content) => {
      assistant.artifact = assistant.artifact ? assistant.artifact + '\n\n' + content : content
      bumpScroll()
    },
    // 反问的选项：正文已经流进气泡，这里只挂备选答案给界面做按钮（无选项则不挂，
    // 用户直接在输入框回答）。一轮里问第二次时后一次覆盖前一次——可点的只有最后一问。
    // ask_user 的提问（dev-board#868）带 id/header/说明/多选，无选项也挂（卡片里直接给文本框）；
    // 紧随其后的 SSE ask_user 事件会再整块覆盖一次（handleAskUserEvent）
    onQuestion: (q) => {
      assistant.question = questionFromParsed(q)
      bumpScroll()
    },
    // 工具参数生成期（<tool_code> 进/出）：期间没有任何可见正文，据此点亮
    // 「正在准备文档内容」提示（历史回灌用的 toLocalMessage 解析器刻意不传本回调）
    onToolPrep: (active) => {
      toolPrep.value = !!active
      if (active) bumpScroll()
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

/**
 * 断线重连续流（与桌面端 `useAgentStream.handleStateRecovery` 同语义）。
 *
 * 后端 `AiAgentController.connect` 对仍在 RUNNING 的会话推来本轮**从头到现在的全量快照**
 * （`AgentOrchestrator.activeStreamContent`，按用户轮次初始化、跨步骤累加）。
 * 桌面端一直在消费它，**插件端此前整个忽略这个事件**——于是断线期间的正文永久丢失，
 * 而收尾事件照常到达，界面渲染成「已完成 · N 秒」的空白气泡
 * （dev-board#287，2026-08-29 生产日志实证：9 分钟 1 Hz 重连风暴期间跑的那一轮）。
 *
 * 语义要点：快照是全量不是增量，所以必须**先清空气泡与解析器状态再整块喂**——
 * 直接追加会把断线前已渲染的部分变成两份；不重建解析器则旧标签栈会把快照劈错。
 */
function handleStateRecovery(dataStr) {
  let content = ''
  try { content = String(JSON.parse(dataStr).content || '') } catch (e) { /* 空快照也要走重建 */ }
  const bubble = ensureAssistantBubble()
  bubble.text = ''
  bubble.thinking = ''
  bubble.artifact = ''
  bubble.question = null
  bubble.error = ''
  bubble.notice = ''
  bubble.done = false
  bubble.streaming = true
  attachParser(bubble)
  // 后端认为这一轮还在跑，本地状态跟上（预连/回灌路径上 streaming 可能还没置起）
  streaming.value = true
  if (content) parser.feed(content)
  bumpScroll()
}

/**
 * 这条助手消息对用户来说有没有内容。思考区不算——只有思考没有正文，用户看到的
 * 就是一个空白气泡。计划卡（artifact）与反问按钮算，它们本身就是可见产出。
 */
function hasVisibleContent(msg) {
  if (!msg) return false
  return Boolean((msg.text && msg.text.trim()) || msg.artifact || msg.question)
}

/**
 * 终态却零正文时的补救：后端每轮都会把助手消息落库，去 /api/ai/history 取回最后
 * 一条助手消息补进这个气泡。取不到就明说这一轮没有正文，并提示先看文档
 * ——工具调用是直接落到文档里的，正文没出来不代表活没干。
 *
 * **文案按「本轮到底断没断线」分两种**（dev-board#768）：空白气泡的成因不止断线一种，
 * 模型整轮只输出 <process>/<tool_code>、一个 <final> 都没有时同样零正文，而服务端日志
 * 里一次重连都没有。把两种都说成「回复在连接中断时丢失」，用户会去查网络、查代理、
 * 重启 Word——全在错误的方向上使劲，真正该看的是文档里已经写进去的那部分。
 */
async function recoverEmptyBubble(target) {
  const gen = generation
  const convId = conversationId
  let recovered = false
  if (ctx.settings && isConfigured(ctx.settings) && convId) {
    try {
      const history = await fetchConversationHistory(ctx.settings, convId)
      if (gen !== generation || conversationId !== convId) return
      for (let i = history.length - 1; i >= 0; i--) {
        if (String(history[i].role || '').toUpperCase() !== 'ASSISTANT') continue
        const local = toLocalMessage(history[i])
        if (local && local.text && local.text.trim()) {
          target.text = local.text
          if (local.thinking && !target.thinking) target.thinking = local.thinking
          recovered = true
        }
        break // 只认最后一条助手消息，再往前就是上一轮了
      }
    } catch (e) {
      console.warn('[Addin] 空气泡补取历史失败', e)
    }
  }
  target.notice = recovered ? t('emptyAnswerRecovered')
    : turnDisconnected ? t('emptyAnswerLost') : t('emptyAnswerNone')
  if (!recovered) target.text = ''
  target.done = true
  bumpScroll()
}

/**
 * 整篇分段过卷的进度（dev-board#422）。后端每次 office_pass_step 返回后推一条。
 * done 之后立刻归位——过卷结束了还挂着「校对 12/12 段」，用户会以为还在跑。
 * 载荷坏掉不影响这一轮，静默忽略即可（纯展示）。
 */
function handlePassProgress(dataStr) {
  try {
    const d = JSON.parse(dataStr)
    if (d && d.done) {
      passProgress.value = null
      return
    }
    const total = Number(d && d.total) || 0
    const chunk = Number(d && d.chunk) || 0
    if (!total || !chunk) {
      passProgress.value = null
      return
    }
    passProgress.value = { chunk, total, replaced: Number(d.replaced) || 0 }
  } catch (e) {
    console.warn('[Addin] pass_progress 载荷无法解析', e)
  }
}

/**
 * SSE `ask_user` 事件（dev-board#868）：结构化的问题卡数据。后端先把同一问题以
 * `<question kind="ask_user">` 标记流过来（解析器已据此拼出一份），这条事件紧随其后、
 * 以它为准整块覆盖——不受标记转义影响（与桌面端 useAgentStream 同口径）。
 * 气泡指针为空（断线重连补发时）落到最后一条助手消息上；已作答的状态不能被补发冲掉。
 */
function handleAskUserEvent(dataStr) {
  let q = null
  try { q = normalizeAskUserEvent(JSON.parse(dataStr)) } catch (e) {
    console.warn('[Addin] ask_user 载荷无法解析', e)
    return
  }
  if (!q) return
  let target = currentAssistant
  if (!target) {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant') target = last
  }
  if (!target) return
  const prev = target.question
  if (isAskUserQuestion(prev) && prev.id === q.id) {
    q.answered = !!prev.answered
    q.answer = prev.answer || null
  }
  target.question = q
  bumpScroll()
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
    // 尾部空白随收尾裁掉（前导由 attachParser 守卫，这里对称收尾）
    if (currentAssistant && currentAssistant.text) {
      currentAssistant.text = currentAssistant.text.replace(/\s+$/, '')
    }
    commitDocHash()
    let status = ''
    let reason = ''
    try {
      const d = JSON.parse(dataStr)
      status = String(d.status || '')
      reason = String(d.reason || '')
    } catch (e) { /* 无 status 按普通收尾 */ }
    const lower = status.toLowerCase()
    // 后端的 bubble_end 有五种 status：finished / paused(max_depth|max_tokens) /
    // awaiting_approval / awaiting_input / 无（空信封）。
    // **只有 finished 与空信封算"写完了"**——paused 是编排器撞上步数或长度预算主动停机，
    // awaiting_* 是球在用户这边。此前它们统统被渲染成「已完成 · N 秒」，
    // 用户以为活干完了，其实还差一半（dev-board#288）。
    const stoppedEarly = lower === 'paused'
    const ballWithUser = lower === 'awaiting_input' || lower === 'awaiting_approval'
    const finished = currentAssistant
    finishStreaming()
    if (stoppedEarly && finished) {
      finished.notice = reason === 'max_tokens' ? t('pausedMaxTokens') : t('pausedMaxDepth')
    }
    if (ballWithUser && lower === 'awaiting_approval') notice.value = t('awaitingConfirmation')
    // 显式完成态（dev-board#147）：光标消失太隐晦，「写完了没」要有明示。
    // 只有正常收尾才标——error/cancelled/paused/awaiting_* 各有自己的可见反馈。
    if (finished && !finished.error && !stoppedEarly && !ballWithUser) {
      finished.durationMs = lastPerf.value ? lastPerf.value.totalMs : 0
      if (hasVisibleContent(finished)) {
        finished.done = true
      } else {
        // 「已完成 · N 秒」配一个空气泡是本轮最伤人的一种失败（dev-board#287 实测）：
        // 断线期间服务端对没有 emitter 的会话是静默丢事件的，正文就此永久消失，
        // 而收尾事件照样到达，于是界面言之凿凿地宣布完成。
        // 后端按轮次落库，先去历史里把这一轮补回来；补不回来也要说人话，不许留白。
        recoverEmptyBubble(finished)
      }
    }
    // awaiting_input：编排器为了反问主动停机，球在用户这边。输入框此时已解锁
    // （答案就是新一轮普通用户消息），只补一行状态提示，别让人以为回答被吞了。
    // notice 由 finishStreaming 清空，所以要放在它之后。
    if (lower === 'awaiting_input') notice.value = t('awaitingAnswer')
  } else if (evt === 'error') {
    let msg = t('executionError')
    try { msg = JSON.parse(dataStr).message || msg } catch (e) { /* ignore */ }
    if (currentAssistant) {
      // 配额耗尽（后端 LlmErrorClassifier.QUOTA_EXHAUSTED_MARKER）：载荷是上游英文原文，
      // 原样拼给用户等于没有信息。换成引导文案并打标记，界面据此追加充值入口（dev-board#198）
      if (msg.includes('AI_QUOTA_EXHAUSTED')) {
        currentAssistant.error = t('quotaExhaustedNotice')
        currentAssistant.errorKind = 'quota'
      } else {
        currentAssistant.error = msg
      }
    }
    disableDocDedup()
    finishStreaming()
  } else if (evt === 'cancelled') {
    if (currentAssistant && !currentAssistant.text) currentAssistant.text = t('stoppedPlaceholder')
    finishStreaming()
  } else if (evt === 'pass_progress') {
    handlePassProgress(dataStr)
  } else if (evt === 'ask_user') {
    handleAskUserEvent(dataStr)
  } else if (evt === 'client_action') {
    handleClientAction(dataStr)
  } else if (evt === 'state_recovery') {
    handleStateRecovery(dataStr)
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
  // **PAUSED 不算「还在生成」**（dev-board#288）：它是编排器撞上步数/长度预算主动停机，
  // 后端此刻什么都没在跑。桌面端有「继续」按钮，任务窗格没有——把它并进 generating
  // 会把输入框永久锁死，用户既等不到下文也答不上话。与 AWAITING_* 同一条纪律
  //（「等用户的状态不许锁输入」），只是提示语不同。
  const generating = name === 'RUNNING'
  const stoppedEarly = name === 'PAUSED'
  const awaitingUser = name === 'AWAITING_APPROVAL' || name === 'AWAITING_INPUT' || stoppedEarly
  const awaitingHint = stoppedEarly
    ? t('pausedMaxDepth')
    : (name === 'AWAITING_INPUT' ? t('awaitingAnswer') : t('awaitingConfirmation'))

  if (restorePending) {
    restorePending = false
    if (generating) {
      streaming.value = true
      notice.value = t('previousTaskInProgress')
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
    const finished = currentAssistant
    finishStreaming()
    // 断线期间漏掉了 bubble_end：解锁之后把「等用户」这一档的提示补回来
    if (awaitingUser) {
      notice.value = awaitingHint
    } else if (name === 'COMPLETED' || name === 'FINISHED') {
      if (finished && !finished.error) finished.done = true
    } else if (finished && !finished.error) {
      // ERROR、或后端重启后拿不到状态（name 为空）：**不许标「已完成」**。
      // 这一轮多半没跑完，标成完成等于替后端把话说满（dev-board#288）。
      finished.notice = t('runEndedUnknown')
    }
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
  // 带 origin = 别的窗格的 AI 经云端下发、要读/改本文档（dev-board#717），走另一条路
  if (action.origin) {
    crossDocQueue = crossDocQueue.then(() => handleCrossDocAction(action))
    return
  }

  // chip 上存的是 command 而不是翻好的 label（dev-board#713）：显示名由界面渲染时经
  // commandDisplayName 现查，切语言后已经画出来的 chip 也跟着换。
  const chip = reactive({ command: action.command, status: 'running', error: '' })
  const assistant = ensureAssistantBubble()
  if (!assistant.tools) assistant.tools = []
  assistant.tools.push(chip)
  bumpScroll()

  // 执行与回传都要兜底（dev-board#147 窗口 B）：这里以前没有 try/catch，回传网络
  // 失败是未处理 rejection，chip 卡 running、后端 future 干等 30s 超时，用户只看到
  // 光标一直闪。失败要落在 chip 上（错误详情给用户看，不只回传给模型）。
  let result
  try {
    result = await executeCommand(action.command, action.args)
  } catch (e) {
    result = { ok: false, error: (e && e.message) || String(e) }
  }
  chip.status = result.ok ? 'done' : 'failed'
  if (!result.ok) chip.error = result.error || ''
  // 文档镜像触发条件（dev-board#299）：本轮有成功的写入类命令。读命令不算——
  // 不在只读名单里的一律按写处理（多拍无害：内容哈希不变时上传会被跳过）
  if (result.ok && !isReadOnlyCommand(action.command)) turnHadWrite = true
  try {
    await postOfficeResult(ctx.settings, {
      requestId: action.requestId,
      ok: result.ok,
      data: result.ok ? result.data : null,
      error: result.ok ? null : result.error
    })
  } catch (e) {
    chip.status = 'failed'
    chip.error = t('resultSendFailedPrefix') + ((e && e.message) || t('networkError'))
    banner.value = t('toolResultSendFailed')
  }
}

/**
 * 跨文档命令逐条串行执行：「取改前值 → 执行 → 读回改后值」必须是一个整体，两条命令
 * 交错的话后一条记下的改前值会是前一条改到一半的状态，撤销就会写回错的东西。
 */
let crossDocQueue = Promise.resolve()

/**
 * 执行别的窗格发来的命令（SSE client_action 带 origin）。与本会话自己的命令三点不同：
 *   - 这是**别的会话**的动作，不往本窗格当前会话的气泡里挂工具 chip；
 *   - 写入走 runCrossDocWrite：Word 强制修订（标不了就拒绝）、Excel/PPT 记改前值；
 *   - 写入成功记进修订记录并弹横幅（同一来源合并计数），本文档的用户一眼看得到谁改了什么。
 * 无论成败都照常回传结果——发起方的工具调用在等它。永不 throw。
 */
async function handleCrossDocAction(action) {
  let outcome
  try {
    outcome = await runCrossDocWrite({
      command: action.command,
      args: action.args || {},
      origin: action.origin,
      host: detectHost(),
      family: hostFamily()
    })
  } catch (e) {
    outcome = { result: { ok: false, error: (e && e.message) || String(e) }, entry: null }
  }
  const result = outcome.result
  if (outcome.entry) {
    try { recordRevision(outcome.entry) } catch (e) { /* 记录失败不影响回传 */ }
    crossDocBanner.value = mergeCrossDocBanner(crossDocBanner.value, outcome.entry.originDocName, nowMs())
  }
  // 本文档被改过：与本窗格自己的写入同样触发文档镜像（dev-board#299，多拍无害）。
  // **必须就地汇合一次**：turnHadWrite 平时由 finishStreaming 消费，而本窗格这会儿并没有
  // 在跑自己的轮次——只置位的话，镜像要等到本窗格的用户下次自己发消息才跑，用户从此不再
  // 用这个窗格的话就永远不跑，桌面端项目里那份副本停在改动之前，还没有任何迹象说明它是旧的。
  if (result.ok && !isReadOnlyCommand(action.command)) {
    turnHadWrite = true
    maybeArchiveSnapshot()
  }
  try {
    await postOfficeResult(ctx.settings, {
      requestId: action.requestId,
      ok: result.ok,
      data: result.ok ? result.data : null,
      error: result.ok ? null : result.error
    })
  } catch (e) {
    banner.value = t('toolResultSendFailed')
  }
}

async function ensureConnection() {
  // 通道必须绑在**当前**会话上（dev-board#764）：连接是按 conversationId 建的，
  // 会话一变就得换通道。正常路径（newConversation/switchConversation/renewConversation）
  // 都会先 closeConnection，走不到这里；能走到就说明有人换了会话却没换通道——
  // 那种状态下后端推给新会话的事件一条都到不了窗格，且两端都不报错。
  if (connection && connectionConvId !== conversationId) closeConnection()
  if (connection) {
    // 连接对象在但可能处于重连退避（后端每轮结束会主动关流）：发送前把它唤醒并
    // 等到 emitter 真正挂上，否则 POST /chat 的快回合事件会被服务端静默丢弃
    // （dev-board#147 窗口 A）。健康连接上这是空操作。
    if (connection.reconnectNow) await connection.reconnectNow()
    return
  }
  const startedAt = nowMs()
  const conn = createSseConnection({
    baseUrl: ctx.settings.serverUrl,
    token: ctx.settings.token,
    conversationId,
    clientId: paneId,
    onEvent: handleEvent,
    onStatus: (status) => {
      if (connection !== conn) return
      if (status === 'reconnecting') {
        // 轮次中途断的才算「断线过」；每轮结束那次是后端主动收尾，不是故障
        if (streaming.value) { everReconnected = true; turnDisconnected = true }
        if (!reconnectNoticeTimer) {
          reconnectNoticeTimer = setTimeout(() => {
            reconnectNoticeTimer = null
            if (connection === conn) reconnecting.value = true
          }, RECONNECT_NOTICE_GRACE_MS)
        }
      } else if (status === 'connected') {
        clearReconnectNotice()
      } else if (status === 'unstable') {
        // 一分钟内反复建连：不再走宽限期，立刻交底。退避已顶到上限，
        // 这里只负责让用户知道发生了什么，别让界面停在「正在自动重连……」像正常等待
        if (reconnectNoticeTimer) { clearTimeout(reconnectNoticeTimer); reconnectNoticeTimer = null }
        reconnecting.value = true
        banner.value = t('connectionUnstable')
      } else if (status === 'superseded') {
        // 同一个会话被另一个任务窗格接管：不再重连，也不要装作还连着
        clearReconnectNotice()
        if (streaming.value) finishStreaming()
        banner.value = t('conversationTakenOver')
      }
    },
    onClose: () => {
      if (connection === conn) { connection = null; connectionConvId = null }
      clearReconnectNotice()
      // 连接彻底关闭时不静默卡死输入框（断线重连由 sse.js 内部处理，不走这里）
      if (streaming.value) finishStreaming()
    }
  })
  connection = conn
  connectionConvId = conversationId
  try {
    await conn.ready
  } catch (e) {
    if (connection === conn) { connection = null; connectionConvId = null }
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
  connectionConvId = null
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
 *
 * sendOpts（只给 answerAskUser 用）：
 *   - displayText：「显示内容 ≠ 发送内容」通道（契约 D）——模型收到 overrideText，
 *     用户气泡与落库的 displayContent 是这一句；
 *   - onAccepted：消息确定要发出（过了所有前置守卫、用户气泡已入列）的那一刻回调，
 *     问题卡据此把选择记成只读态。前置守卫没过就不调，卡片仍可再点。
 */
export async function send(overrideText, sendOpts) {
  const override = typeof overrideText === 'string' ? overrideText : null
  const opts = override !== null && sendOpts && typeof sendOpts === 'object' ? sendOpts : {}
  const displayText = typeof opts.displayText === 'string' ? opts.displayText.trim() : ''
  banner.value = ''
  // 「等你回答/等你确认」的提示随本轮发送作废，别悬在下一轮的流式过程里
  notice.value = ''
  if (!ctx.settings || !isConfigured(ctx.settings)) return { needSettings: true }
  if (!ctx.projectId) {
    banner.value = t('noProjectBanner')
    return { needSettings: false }
  }
  const prompt = (override === null ? input.value : override).trim()
  if (!prompt || streaming.value) return { needSettings: false }

  const settings = ctx.settings
  const projectId = ctx.projectId
  if (override === null) input.value = ''
  // 用户已经作答（不管是点选项还是自己打字）：所有反问的按钮就此封掉
  sealStaleQuestions(true)
  messages.value.push({ role: 'user', text: displayText || prompt })
  if (typeof opts.onAccepted === 'function') opts.onAccepted()

  currentAssistant = null
  parser = null
  perfStart()
  const assistant = ensureAssistantBubble()
  // 本轮由 send 触发：run_state 回到「不能当终态」的读法（回灌建连若还没收到 run_state，到此作废）
  restorePending = false
  // 「本轮断过线没有」按轮清零（everReconnected 刻意不清，见它的定义）
  turnDisconnected = false
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

    // 当前文档内容以内联形式随请求上送（activeContext.inlineContent / inlineContentHash）。
    // 不附带正文时也要上送壳（id/name/fileType，不带 inlineContent）：后端
    // ContextAssemblerService 的整段 office 工具指引挂在 activeContext 上，壳都没有
    // 的话模型连「用户开着文档、该用哪套工具」都不知道（dev-board#150）。
    let activeContext = null
    if (read) {
      if (read.doc) activeContext = buildActiveContext(read.doc, read.hash)
      else banner.value = t('docReadFailedBanner')
    }
    if (!activeContext) activeContext = readDocumentMeta()

    const buildPayload = (context) => ({
      projectId: parseInt(projectId, 10),
      conversationId,
      message: prompt,
      // 契约 D：用户看的那一句（ask_user 的回答只显示所选各项）。空值不上送，同值等于不传
      ...(displayText && displayText !== prompt ? { displayText } : {}),
      mode: 'AGENT',
      activeContext: context,
      // 按次指定模型与手选 skill（后端 AgentChatRequest 原生字段；空值不上送走默认）
      ...(selectedModel.value ? { model: selectedModel.value } : {}),
      ...(selectedSkillIds.value.length ? { skillIds: [...selectedSkillIds.value] } : {}),
      // 附加的项目文件：contextItems 是后端既有字段，按 fileId 由服务端读内容
      ...(attachedFiles.value.length ? {
        contextItems: attachedFiles.value.map((f) => ({ id: String(f.id), name: f.name, fileType: f.fileType || '' }))
      } : {}),
      // 声明客户端能力（Phase C）：后端据此让本会话只见 office_* 工具、隐藏 doc_*；
      // officeHost 再按宿主细分（word/excel/powerpoint），点名对应工具面；
      // officeFamily（dev-board#298）只用于对话镜像的来源标注（Word 插件 vs WPS 文字），
      // 不参与工具过滤，旧后端不认识该字段也无害
      clientCapability: 'office',
      officeHost: detectHost() || 'word',
      officeFamily: hostFamily() === 'wps' ? 'wps' : 'office',
      // 本轮界面语言（dev-board#713）：后端据它选中/英文 system prompt，回答语言跟随界面。
      // 必须随请求体走而不是只靠 X-App-Language 头——编排循环跑在池线程上，
      // HTTP 线程上的语言作用域不跟着过去，而且请求体会被 AgentInbox 持久化，
      // 排队/续跑的那一轮照样说对语言。旧后端不认识该字段，无害。
      appLanguage: getLangTag()
    })

    try {
      await postChat(settings, buildPayload(activeContext))
    } catch (e) {
      // chat 与 connect 走的是同一个 canUseConversation（dev-board#715）：连得上不等于
      // 发得出——SSE 的重连循环把 403 吞在退避里只显示「正在重连」，POST /chat 却当场
      // 报错，于是用户看到「每条都失败，只有点新对话才恢复」。这里与 connect 同一套自愈：
      // 换一条会话 → 重发这一条 → 再失败才报错。
      if (!canHealConversation(e)) throw e
      console.warn('[Addin] 会话已失效（chat HTTP ' + e.status + '），丢弃并重新签发后重发一次', conversationId)
      if (!(await renewConversation())) return { needSettings: false }
      await ensureConnection()
      // 新会话在后端没有正文缓存：重建 activeContext，让省传退回全文，
      // 否则重发的这条消息在模型眼里是一份「只有哈希、没有正文」的空上下文
      let retryContext = activeContext
      if (read && read.doc) retryContext = buildActiveContext(read.doc, read.hash)
      await postChat(settings, buildPayload(retryContext))
      notice.value = t('conversationRenewedNotice')
    }
    // 消息已被后端收下 = 这条会话从此有落库消息，后续 403 不再是「已失效」
    conversationPersisted = true
    if (perfRound) perfRound.chatAcceptedMs = perfSince()
  } catch (e) {
    assistant.error = sendErrorText(e)
    disableDocDedup()
    finishStreaming()
  }
  return { needSettings: false }
}

/**
 * 发送失败的用户可读文案：裸的「HTTP 403」对用户没有任何可操作信息，
 * 换成说清处境与下一步的两句话（红线：不含「登录/未授权/请先」，见 api.js 文件头）。
 */
function sendErrorText(e) {
  const status = e && e.status
  if (status === 403 || status === 404) {
    return conversationPersisted ? t('conversationDenied') : t('conversationExpiredRetryFailed')
  }
  return (e && e.message) || t('sendFailed')
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

/**
 * 在 ask_user 问题卡上作答（dev-board#868）。
 *
 * 与旧反问（answerQuestion，选项原文当消息）不同：这里发给模型的是以
 * `<ask_user_answer id="…">` 开头的结构化回答（后端 AskUserQuestion.isAnswerMessage 据此认出
 * 「这是对哪一问的回答」），用户气泡只显示所选各项（displayText）。
 * 消息确定发出的那一刻把选择记到卡上，卡随即变只读并高亮所选。
 *
 * @param {{id: string, question: string, selected: string[], other: string}} answer
 */
export async function answerAskUser(answer) {
  if (!answer || streaming.value) return { needSettings: false }
  const formatted = formatAskUserAnswer(answer, { english: getLang() === 'en' })
  if (!formatted) return { needSettings: false }
  let target = null
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const q = messages.value[i].question
    if (isAskUserQuestion(q) && q.id === (answer.id || '')) { target = q; break }
  }
  return send(formatted.prompt, {
    displayText: formatted.displayText,
    onAccepted: () => {
      if (target) {
        target.answered = true
        target.answer = { selected: [...(answer.selected || [])], other: answer.other || '' }
      }
    }
  })
}

export async function stop() {
  if (conversationId) await postCancel(ctx.settings, conversationId)
  closeConnection()
  if (currentAssistant && !currentAssistant.text) currentAssistant.text = t('stoppedPlaceholder')
  finishStreaming()
}

export function newConversation() {
  // 换会话 = 换会话身份：in-flight 的旧预连（连同它已经签发的 ID）就此作废，
  // 不能被下面这次 preconnect 复用，也不该再把结果写回来（dev-board#764）
  generation++
  closeConnection()
  rememberConversation('')
  setConversationId(null)
  conversationPersisted = false
  messages.value = []
  currentAssistant = null
  parser = null
  banner.value = ''
  notice.value = ''
  clearReconnectNotice()
  everReconnected = false
  turnDisconnected = false
  streaming.value = false
  toolPrep.value = false
  passProgress.value = null
  resetDocCache()
  // 立刻预连新会话（签发新 ID + 建 SSE），让下一条消息零建连成本；
  // 这条连接没有轮次在跑，其 run_state 不产生任何副作用（见 handleRunState 第 2 种来源）
  preconnect().catch((e) => console.warn('[Addin] 新会话预连失败', e))
}

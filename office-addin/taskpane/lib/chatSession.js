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
  executeCommand, commandDisplayName, hostFamily
} from './hostBridge.js'
import {
  loadConversationId, saveConversationId, isConfigured, loadModelChoice, saveModelChoice,
  loadArchiveLinks
} from './settings.js'
import { isReadOnlyCommand, captureDocumentBytes, sha256Hex } from './docSnapshot.js'
import { t } from './i18n.js'

/**
 * 本窗格的宿主标签，用作会话 ID 存储键的一层作用域（settings.loadConversationId）。
 * **取不到时用 'unknown'，绝不能回落成 'word'**——回落成 word 正好把三个宿主的
 * 窗格并回同一个会话，也就是 dev-board#285 那个 1 Hz 互顶风暴的成因。
 */
function hostScope() {
  return detectHost() || 'unknown'
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
// SSE 是否发生过**轮次中途**的断线重连：只有这种重连之后的 run_state 才用于兜底解锁
// （首连的 run_state 在 send 已置 streaming 之后到达，不能当终态看）。
//
// 判据必须带上「轮次中途」（dev-board#285）：后端每轮结束都会主动关流，
// 客户端随即排一次重连——把那次也算成「断线过」，等于从第一轮结束起就把
// run_state 兜底永久武装上，此后任何一条迟到的 run_state 都能把正在跑的轮次
// 判成已完成并解锁输入框。
let everReconnected = false
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
  toolPrep.value = false
  clearReconnectNotice()
  everReconnected = false
  banner.value = ''
  notice.value = ''
  conversationId = null
  attachedFiles.value = []
  uploadingFiles.value = []
  // 换了账户/项目就要重新拉清单，旧清单上做出的「刚选了看不了图的模型」提示随之作废
  nonVisionModelPicked.value = false
  resetDocCache()

  if (!pid || !settings || !isConfigured(settings)) return

  // 模型/skill 清单随会话身份拉一次（失败静默：选择器隐藏，主链路不受影响）
  refreshCatalogs()

  // 任务窗格重建（切文档、重开窗格）后：接着上次的会话，而不是从空白开始
  const stored = loadConversationId(pid, hostScope())
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
  banner.value = ''
  notice.value = ''
  resetDocCache()
  conversationId = convId
  saveConversationId(ctx.projectId, convId, hostScope())
  const history = await fetchConversationHistory(ctx.settings, convId)
  if (gen !== generation) return
  if (history.length) {
    messages.value = history.map(toLocalMessage)
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
    saveConversationId(ctx.projectId, conversationId, hostScope())
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
    saveConversationId(ctx.projectId, '', hostScope())
    const issued = await createConversation(ctx.settings, parseInt(ctx.projectId, 10))
    if (gen !== generation) return
    conversationId = issued || `conv-${Date.now()}`
    saveConversationId(ctx.projectId, conversationId, hostScope())
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
  let artifact = ''
  const p = createTagStreamParser({
    // 标签间的裸换行不进正文（与桌面端 useAgentStream 的守卫同口径，dev-board#147）；
    // 首个非空块自带的前导空白同样裁掉——"\n\n正文" 混合块曾让气泡顶部先空一截（dev-board#197）
    onMainText: (t) => { if (!text) { t = t.replace(/^\s+/, ''); if (!t) return } text += t },
    onThinkingText: (t) => { if (!thinking) { t = t.replace(/^\s+/, ''); if (!t) return } thinking += t },
    onQuestion: (q) => { question = q.options.length ? { options: q.options, answered: false } : null },
    onArtifact: (c) => { artifact = artifact ? artifact + '\n\n' + c : c }
  })
  p.feed(content)
  p.flush()
  text = text.replace(/\s+$/, '')
  return reactive({ role: 'assistant', text, thinking, streaming: false, error: '', tools: [], question, artifact })
}

// ==================== SSE ====================

function finishStreaming() {
  if (currentAssistant) currentAssistant.streaming = false
  streaming.value = false
  toolPrep.value = false
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
    onQuestion: (q) => {
      assistant.question = q.options.length ? { options: q.options, answered: false } : null
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
 * 一条助手消息补进这个气泡。取不到就明说这一轮丢了，并提示先看文档
 * ——工具调用是直接落到文档里的，正文丢了不代表活没干。
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
  target.notice = recovered ? t('emptyAnswerRecovered') : t('emptyAnswerLost')
  if (!recovered) target.text = ''
  target.done = true
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

  const chip = reactive({ label: commandDisplayName(action.command), status: 'running', error: '' })
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

async function ensureConnection() {
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
        if (streaming.value) everReconnected = true
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
      if (connection === conn) connection = null
      clearReconnectNotice()
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

    await postChat(settings, {
      projectId: parseInt(projectId, 10),
      conversationId,
      message: prompt,
      mode: 'AGENT',
      activeContext,
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
      officeFamily: hostFamily() === 'wps' ? 'wps' : 'office'
    })
    if (perfRound) perfRound.chatAcceptedMs = perfSince()
  } catch (e) {
    assistant.error = e.message || t('sendFailed')
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
  if (currentAssistant && !currentAssistant.text) currentAssistant.text = t('stoppedPlaceholder')
  finishStreaming()
}

export function newConversation() {
  closeConnection()
  if (ctx.projectId) saveConversationId(ctx.projectId, '', hostScope())
  conversationId = null
  messages.value = []
  currentAssistant = null
  parser = null
  banner.value = ''
  notice.value = ''
  clearReconnectNotice()
  everReconnected = false
  streaming.value = false
  toolPrep.value = false
  resetDocCache()
  // 立刻预连新会话（签发新 ID + 建 SSE），让下一条消息零建连成本；
  // 这条连接没有轮次在跑，其 run_state 不产生任何副作用（见 handleRunState 第 2 种来源）
  preconnect().catch((e) => console.warn('[Addin] 新会话预连失败', e))
}

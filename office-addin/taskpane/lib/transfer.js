import { ref } from 'vue'
import { normalizeBaseUrl } from './settings.js'
import { t } from './i18n.js'

/**
 * 跨设备文件传输（dev-board#251）：A 侧（插件）发起，云后端 /api/mobile/transfer 转达
 * 给 B 侧（桌面）。契约见 docs/superpowers/specs/2026-08-28-cross-device-transfer.md 2.2。
 *
 * 与 chatSession.js 同一个模式：面板开关是模块级单例状态，不放进组件——
 * ChatView「+」菜单与 App.vue 的 remote:: 下拉两个入口都要能打开同一个面板并各自预选参数。
 */

// ==================== 面板开关（模块级单例） ====================

export const transferOpen = ref(false)
/** 打开面板时的预选参数：{deviceId, projectKey} 或 null（未预选，从零开始挑设备） */
export const transferPreset = ref(null)

export function openTransfer(preset) {
  transferPreset.value = preset || null
  transferOpen.value = true
}

export function closeTransfer() {
  transferOpen.value = false
  transferPreset.value = null
}

// ==================== 常量 ====================

/** 单文件上限（服务端 MAX_TRANSFER_BYTES 同款，nginx 单请求上限同款） */
export const MAX_TRANSFER_BYTES = 200 * 1024 * 1024

// ==================== 请求 ID ====================

/**
 * client 生成的幂等键。优先 crypto.randomUUID；不可用环境（旧 webview）回退
 * 时间戳+随机 hex，仍要落在服务端围栏 `^[A-Fa-f0-9-]{8,64}$` 之内。
 */
export function newRequestId() {
  try {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  } catch (e) {
    // 回退拼接
  }
  let tail = ''
  for (let i = 0; i < 16; i++) tail += Math.floor(Math.random() * 16).toString(16)
  return `${Date.now().toString(16)}-${tail}`
}

// ==================== API 封装 ====================

function headers(token) {
  return { 'Content-Type': 'application/json', 'X-Session-Id': token || '' }
}

/**
 * 信封解析：服务端错误一律 HTTP 200 + {code:1,message}（现网中转约定），
 * message 是给用户看的文案（余额不足/设备不在线/未开通等），原样抛出即可。
 */
async function readEnvelope(resp) {
  if (!resp.ok) throw new Error(t('transferHttpFailed', { status: resp.status }))
  let data
  try {
    data = await resp.json()
  } catch (e) {
    throw new Error(t('transferBadResponse'))
  }
  if (!data || data.code !== 0) {
    const message = data && data.message ? String(data.message) : ''
    throw new Error(message || t('transferBadResponse'))
  }
  return data
}

async function getJson(base, path, token) {
  let resp
  try {
    resp = await fetch(`${base}${path}`, { headers: headers(token) })
  } catch (e) {
    throw new Error(t('transferBackendUnreachable'))
  }
  return readEnvelope(resp)
}

async function postJson(base, path, token, body) {
  let resp
  try {
    resp = await fetch(`${base}${path}`, {
      method: 'POST',
      headers: headers(token),
      body: JSON.stringify(body || {})
    })
  } catch (e) {
    throw new Error(t('transferBackendUnreachable'))
  }
  return readEnvelope(resp)
}

function requireBase(serverUrl) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error(t('apiServerUrlEmpty'))
  return base
}

/** GET /api/mobile/transfer/quote?bytes=N → {credits, balanceCents} */
export async function fetchQuote({ serverUrl, token }, bytes) {
  const base = requireBase(serverUrl)
  const data = await getJson(base, `/api/mobile/transfer/quote?bytes=${encodeURIComponent(bytes)}`, token)
  return { credits: data.credits, balanceCents: data.balanceCents != null ? data.balanceCents : null }
}

/** POST /api/mobile/transfer/list {deviceId, projectKey, requestId} → id */
export async function createList({ serverUrl, token }, { deviceId, projectKey, requestId }) {
  const base = requireBase(serverUrl)
  const data = await postJson(base, '/api/mobile/transfer/list', token, { deviceId, projectKey, requestId })
  return data.id
}

/** GET /api/mobile/transfer/{id} → transfer 详情 */
export async function fetchTransfer({ serverUrl, token }, id) {
  const base = requireBase(serverUrl)
  const data = await getJson(base, `/api/mobile/transfer/${encodeURIComponent(id)}`, token)
  return data.transfer
}

/** POST /api/mobile/transfer/pull {...} → {id, credits}（撞既有 requestId 幂等回既有行） */
export async function createPull({ serverUrl, token }, { deviceId, projectKey, remoteFileId, fileName, fileSize, requestId }) {
  const base = requireBase(serverUrl)
  const data = await postJson(base, '/api/mobile/transfer/pull', token, {
    deviceId, projectKey, remoteFileId, fileName, fileSize, requestId
  })
  return { id: data.id, credits: data.credits }
}

/** POST /api/mobile/transfer/{id}/save-to-project {projectId} → {fileId, name} */
export async function saveToProject({ serverUrl, token }, id, projectId) {
  const base = requireBase(serverUrl)
  const data = await postJson(base, `/api/mobile/transfer/${encodeURIComponent(id)}/save-to-project`, token, { projectId })
  return { fileId: data.fileId, name: data.name }
}

/** POST /api/mobile/transfer/push {...} → {id, credits} */
export async function createPush({ serverUrl, token }, { targetDeviceId, projectKey, fileId, requestId }) {
  const base = requireBase(serverUrl)
  const data = await postJson(base, '/api/mobile/transfer/push', token, {
    targetDeviceId, projectKey, fileId, requestId
  })
  return { id: data.id, credits: data.credits }
}

/** POST /api/mobile/transfer/{id}/cancel（LIST/PULL 的 PENDING 态可取消，触发退款） */
export async function cancelTransfer({ serverUrl, token }, id) {
  const base = requireBase(serverUrl)
  await postJson(base, `/api/mobile/transfer/${encodeURIComponent(id)}/cancel`, token, {})
}

/**
 * 当前项目文件清单，供投送 tab 选源文件用（GET /api/projects/{pid}/files?tree=true）。
 * 与 api.js 的 fetchProjectFiles 同一个端点，但那份是给聊天附件场景拍平的，
 * 丢了 fileSize——投送报价要按字节数算，这里另起一份轻量拉取保留 size。
 * 失败静默回空数组，与 api.js 同款降级口径。
 */
export async function fetchLocalProjectFiles({ serverUrl, token }, projectId) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base || !projectId) return []
  try {
    const resp = await fetch(`${base}/api/projects/${encodeURIComponent(projectId)}/files?tree=true`, {
      headers: headers(token)
    })
    if (!resp.ok) return []
    let data = await resp.json()
    if (data && typeof data === 'object' && 'data' in data) data = data.data
    const flat = []
    const walk = (nodes) => {
      for (const n of (Array.isArray(nodes) ? nodes : [])) {
        if (!n) continue
        const isDir = n.isFolder || n.isDir || n.fileType === 'folder'
        if (!isDir && n.id != null) {
          flat.push({ id: n.id, name: n.name || String(n.id), size: Number(n.fileSize) || 0 })
        }
        if (Array.isArray(n.children)) walk(n.children)
      }
    }
    walk(data)
    return flat
  } catch (e) {
    return []
  }
}

// ==================== 轮询 ====================

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 轮询直到 fn() 返回真值（终态）或超时。
 * fn 自己判定「是否到终态」——不同流程（LIST 等 DONE/FAILED，PULL 等 STAGED/FAILED/EXPIRED）
 * 的终态集合不同，交给调用方在 fn 内部判断，pollUntil 只管节奏与超时。
 */
export async function pollUntil(fn, { intervalMs = 3000, timeoutMs } = {}) {
  const deadline = timeoutMs ? Date.now() + timeoutMs : 0
  for (;;) {
    const result = await fn()
    if (result) return result
    if (deadline && Date.now() >= deadline) throw new Error(t('transferPollTimeout'))
    await sleep(intervalMs)
  }
}

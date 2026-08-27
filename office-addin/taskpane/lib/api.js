import { normalizeBaseUrl } from './settings.js'
import { t } from './i18n.js'

/**
 * 后端 REST 访问。鉴权统一走 X-Session-Id 请求头携带 awdt_ 设备令牌
 * （后端 getUserIdFromSession 支持前缀解析）。
 *
 * 错误文案：本文件自造的文案统一说「连接未就绪/令牌无效」，不写「登录/未授权/请先」——
 * 主前端早年靠这三个子串判掉线（PR4-0 起已改成只认 code=4010），沿用它做措辞基线，
 * 也让插件的提示与桌面端保持一个口径。**唯一的例外是账户登录那两条**：
 * 服务端的「验证码错误或已过期」之类是用户唯一能据此改正的信息，必须透传（见 postAnonymous）。
 */

function headers(token) {
  return { 'Content-Type': 'application/json', 'X-Session-Id': token || '' }
}

/**
 * 拉取我的项目列表；同时用作「连接测试」——通了说明地址可达且令牌有效。
 */
export async function fetchMyProjects({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error(t('apiServerUrlEmpty'))
  let resp
  try {
    resp = await fetch(`${base}/api/projects/my`, { headers: headers(token) })
  } catch (e) {
    throw new Error(t('apiBackendUnreachable'))
  }
  if (!resp.ok) {
    throw new Error(t('apiConnectFailedHttp', { status: resp.status }))
  }
  const data = await resp.json()
  if (!Array.isArray(data)) throw new Error(t('apiBadResponseFormat'))
  return data
}

/**
 * 一个项目都没有的账号：让后端懒建「插件临时项目」（POST /api/projects/ensure-addin-default）。
 * 项目是后端的租户隔离维度不能为空，但用户不该被逼着先去建项目。
 * 已有项目时后端返回 {created:false, project:null}；本函数只关心拿不拿得到项目。
 * 旧后端没有该端点（404）或任何失败时返回 null，由调用方降级为「请选择项目」的现状，不报错。
 */
export async function ensureAddinDefaultProject({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) return null
  try {
    const resp = await fetch(`${base}/api/projects/ensure-addin-default`, {
      method: 'POST',
      headers: headers(token)
    })
    if (!resp.ok) return null
    const data = await resp.json()
    if (data && data.project && data.project.id != null) {
      return { id: data.project.id, name: data.project.name }
    }
  } catch (e) {
    // 静默降级：由调用方走「请选择项目」的现状路径
  }
  return null
}

/**
 * 新建项目（POST /api/projects，dev-board#196）。插件端只建 BLANK 类型——
 * 尽调等结构化项目类型要填公司信息，那是桌面端向导的事。
 * 返回 {id, name}；失败抛错由界面提示（新建是显式动作，不静默吞）。
 */
export async function createProject({ serverUrl, token }, name) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error(t('apiServerUrlEmpty'))
  let resp
  try {
    resp = await fetch(`${base}/api/projects`, {
      method: 'POST',
      headers: headers(token),
      body: JSON.stringify({ projectType: 'BLANK', name: (name || '').trim() })
    })
  } catch (e) {
    throw new Error(t('apiBackendUnreachable'))
  }
  if (!resp.ok) throw new Error(t('apiConnectFailedHttp', { status: resp.status }))
  const data = await resp.json()
  if (data && data.id != null) return { id: data.id, name: data.name }
  throw new Error(t('apiBadResponseFormat'))
}

/**
 * 我在本项目的历史会话列表（GET /api/ai/conversations?projectId=，user-scoped 裸数组）。
 * 每条：{conversationId, title, lastMessage, updatedAt, runStatus}（title 是 LLM 生成的，
 * 可能为空）。失败静默回空数组——历史列表拿不到不该打断当前对话（dev-board#148）。
 */
export async function fetchConversations({ serverUrl, token }, projectId) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base || !projectId) return []
  try {
    const resp = await fetch(
      `${base}/api/ai/conversations?projectId=${encodeURIComponent(projectId)}`,
      { headers: headers(token) })
    if (!resp.ok) return []
    const data = await resp.json()
    return Array.isArray(data) ? data : []
  } catch (e) {
    return []
  }
}

/**
 * 可用模型清单（GET /api/ai/models → {models:[{id,name,vendor,...}], defaultModel}）。
 * 失败回 null：模型选择器隐藏，发消息不带 model 字段走后端默认，不影响主链路。
 */
export async function fetchModels({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) return null
  try {
    const resp = await fetch(`${base}/api/ai/models`, { headers: headers(token) })
    if (!resp.ok) return null
    const data = await resp.json()
    if (data && Array.isArray(data.models)) {
      return { models: data.models, defaultModel: data.defaultModel || '' }
    }
  } catch (e) {
    // 静默降级
  }
  return null
}

/**
 * Skill 清单（GET /api/skills/list，登录即可，裸数组）。只取已启用的给斜杠菜单用；
 * 失败回空数组——skill 菜单隐藏，不影响主链路（dev-board#150）。
 */
export async function fetchSkills({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) return []
  try {
    const resp = await fetch(`${base}/api/skills/list`, { headers: headers(token) })
    if (!resp.ok) return []
    const data = await resp.json()
    return Array.isArray(data) ? data : []
  } catch (e) {
    return []
  }
}

/**
 * 删除整个会话（DELETE /api/ai/conversation/{id}）。会话进行中时后端回 409。
 * 失败抛错（带后端文案），由界面提示——删除是显式动作，不静默吞。
 */
export async function deleteConversation({ serverUrl, token }, conversationId) {
  const base = normalizeBaseUrl(serverUrl)
  const resp = await fetch(`${base}/api/ai/conversation/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
    headers: headers(token)
  })
  if (!resp.ok) {
    // 守卫类错误（403/409/400）后端回的是纯文本文案（LangText 解析后的字符串），透传给用户
    let msg = t('apiDeleteFailedHttp', { status: resp.status })
    try { const serverText = (await resp.text()).trim(); if (serverText && serverText.length < 200 && !serverText.startsWith('<')) msg = serverText.replace(/^"|"$/g, '') } catch (e) { /* 保底文案 */ }
    throw new Error(msg)
  }
}

/**
 * 重命名会话（POST /api/ai/conversation/{id}/title {title}，1-60 字符）。
 */
export async function renameConversation({ serverUrl, token }, conversationId, title) {
  const base = normalizeBaseUrl(serverUrl)
  const resp = await fetch(`${base}/api/ai/conversation/${encodeURIComponent(conversationId)}/title`, {
    method: 'POST',
    headers: headers(token),
    body: JSON.stringify({ title })
  })
  if (!resp.ok) {
    let msg = t('apiRenameFailedHttp', { status: resp.status })
    try { const serverText = (await resp.text()).trim(); if (serverText && serverText.length < 200 && !serverText.startsWith('<')) msg = serverText.replace(/^"|"$/g, '') } catch (e) { /* 保底文案 */ }
    throw new Error(msg)
  }
}

/**
 * 项目文件清单（GET /api/projects/{pid}/files?tree=true），给附件选择器用。
 * 返回拍平后的文件数组（滤掉文件夹）；失败回空数组静默降级。
 */
export async function fetchProjectFiles({ serverUrl, token }, projectId) {
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
        if (!isDir && n.id != null) flat.push({ id: n.id, name: n.name || String(n.id), fileType: n.fileType || '' })
        if (Array.isArray(n.children)) walk(n.children)
      }
    }
    walk(data)
    return flat
  } catch (e) {
    return []
  }
}

/**
 * 语音听写（POST /api/voice/dictate，dev-board#153）。失败抛错（透传服务端文案）。
 */
export async function postDictate({ serverUrl, token }, { audioBase64, format, durationMs }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error(t('apiServerUrlEmpty'))
  const resp = await fetch(`${base}/api/voice/dictate`, {
    method: 'POST',
    headers: headers(token),
    body: JSON.stringify({ audioBase64, format, durationMs })
  })
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`
    try { const serverText = (await resp.text()).trim(); if (serverText && serverText.length < 300 && !serverText.startsWith('<')) msg = serverText.replace(/^"|"$/g, '') } catch (e) { /* 保底 */ }
    throw new Error(msg)
  }
  const data = await resp.json()
  return data && typeof data.text === 'string' ? data.text : ''
}

/**
 * 当前登录用户信息（GET /api/auth/me，dev-board#176 头像）。
 * 返回 {id, username, displayName, avatarUrl, ...}；未登录/失败一律 null 静默降级——
 * 头像取不到就显示首字母，不该打断使用。
 */
export async function fetchMe({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base || !token) return null
  try {
    const resp = await fetch(`${base}/api/auth/me`, { headers: headers(token) })
    if (!resp.ok) return null
    const data = await resp.json()
    if (data && data.code === 0 && data.data) return data.data
  } catch (e) {
    // 静默降级
  }
  return null
}

/**
 * 退出登录（POST /api/auth/logout，尽力而为）。设备令牌的本地清除才是真正的退出，
 * 服务端调用失败不阻塞也不报错。
 */
export async function postLogout({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base || !token) return
  try {
    await fetch(`${base}/api/auth/logout`, { method: 'POST', headers: headers(token) })
  } catch (e) {
    // 尽力而为
  }
}

/**
 * 拉某个会话的历史消息（GET /api/ai/history?conversationId=...）。
 * 任务窗格重建后据此把上一场对话回灌到界面。
 * 403/404/网络失败一律返回空数组静默降级——历史拿不到不该打断用户开新的对话。
 */
export async function fetchConversationHistory({ serverUrl, token }, conversationId) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base || !conversationId) return []
  try {
    const resp = await fetch(
      `${base}/api/ai/history?conversationId=${encodeURIComponent(conversationId)}`,
      { headers: headers(token) })
    if (!resp.ok) return []
    const data = await resp.json()
    return Array.isArray(data) ? data : []
  } catch (e) {
    // 静默降级：当作空会话
  }
  return []
}

/**
 * 人机验证的公开配置（匿名端点 GET /api/auth/account-login/captcha-config）。
 *
 * **刻意不用 `/api/account/captcha-config`**：那条要 `X-Session-Id`，而云后端
 * （local-mode=false）下插件用户此刻还没登录——「取控件参数得先有会话、有会话得先登录、
 * 登录得先过控件」是死循环。
 *
 * 静默降级成「未启用」：拿不到配置时返回 `{provider: null}`，调用方跳过控件直接发码，
 * 官网若确实开着闸会在发码那步给出可读的报错，比在这里把登录整个卡死强。
 */
export async function getAccountLoginCaptchaConfig({ serverUrl }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) return { provider: null }
  try {
    const resp = await fetch(base + '/api/auth/account-login/captcha-config', {
      headers: { Accept: 'application/json' },
    })
    if (!resp.ok) return { provider: null }
    const data = await resp.json()
    if (data && data.code === 0 && data.data) return data.data
  } catch (e) {
    // 静默降级：老版本云后端没有这个端点，当作未启用
  }
  return { provider: null }
}

/**
 * 账户登录：给手机号发验证码（匿名端点 POST /api/auth/account-login/send-code）。
 * 后端只是转发官网，真正的冷却与日配额在官网侧。
 *
 * `captchaToken` 必须一路带到官网：官网 send-code 把 `verifyCaptcha` 排在发短信之前，
 * 不带就是 403「请先完成安全验证后再试」。插件端曾经整条链都没有这个参数，
 * 表现成「点获取验证码永远弹不出滑块」（dev-board#88）。
 */
export async function postAccountLoginSendCode({ serverUrl }, phone, captchaToken) {
  await postAnonymous(serverUrl, '/api/auth/account-login/send-code', {
    phone: (phone || '').trim(),
    captchaToken: (captchaToken || '').trim(),
  })
}

/**
 * 账户登录：手机号+验证码 或 邮箱+口令 换取本服务器的 awdt_ 设备令牌
 * （匿名端点 POST /api/auth/account-login）。凭据用完即弃，只有换回的令牌被保存。
 *
 * @param credentials {phone, code} 或 {account, password}
 * @returns awdt_ 令牌字符串
 */
export async function postAccountLogin({ serverUrl }, credentials) {
  const data = await postAnonymous(serverUrl, '/api/auth/account-login', credentials || {})
  if (data && data.data && data.data.token) return data.data.token
  throw new Error(t('apiAccountVerifyFailed'))
}

/**
 * 两个匿名登录端点共用的出站与信封解析。
 *
 * 与 postAwdkLogin 的一处刻意不同：**这里透传服务端 message**。
 * 「验证码错误或已过期」与「账号或密码不正确」是用户唯一能据此改正的信息，
 * 换成一句自造的通用文案等于把界面做成哑巴。信封本身照旧只认 code=0/1，
 * 绝不会出现 code=4010（后端护栏 AuthControllerHardeningTest）。
 */
async function postAnonymous(serverUrl, path, body) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error(t('apiServerUrlEmpty'))
  let resp
  try {
    resp = await fetch(`${base}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
  } catch (e) {
    throw new Error(t('apiBackendUnreachable'))
  }
  if (resp.status === 404) {
    // 旧版本后端没有这两个端点
    throw new Error(t('apiAccountLoginUnsupported'))
  }
  if (!resp.ok) throw new Error(t('apiAccountConnectFailedHttp', { status: resp.status }))
  let data
  try {
    data = await resp.json()
  } catch (e) {
    throw new Error(t('apiBadResponseFormat'))
  }
  if (data && data.code === 0) return data
  const message = data && data.message ? String(data.message) : ''
  if (message.includes('未开启账户桥接')) {
    throw new Error(t('apiAccountBridgeDisabled'))
  }
  // message 是服务端文案，必须透传（见文件头注释），只有拿不到 message 时才用客户端兜底文案
  throw new Error(message || t('apiAccountConnectFailedRetry'))
}

/**
 * 用官网账户 Key（awdk_ 开头）换取本服务器的 awdt_ 设备令牌。
 * 匿名端点 POST /api/auth/awdk-login，body {key}；服务端开关未开时返回业务错误。
 * 成功返回 awdt_ 令牌字符串；一切失败以 Error 抛出（文案不含「登录/未授权/请先」，
 * 且不透传服务端原文——服务端文案不受本红线约束）。
 */
export async function postAwdkLogin({ serverUrl }, key) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error(t('apiServerUrlEmpty'))
  let resp
  try {
    resp = await fetch(`${base}/api/auth/awdk-login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key: (key || '').trim() })
    })
  } catch (e) {
    throw new Error(t('apiBackendUnreachable'))
  }
  if (resp.status === 404) {
    // 旧版本后端没有该端点
    throw new Error(t('apiAwdkBridgeDisabled'))
  }
  if (!resp.ok) throw new Error(t('apiAwdkConnectFailedHttp', { status: resp.status }))
  let data
  try {
    data = await resp.json()
  } catch (e) {
    throw new Error(t('apiBadResponseFormat'))
  }
  if (data && data.code === 0 && data.data && data.data.token) {
    return data.data.token
  }
  const message = data && data.message ? String(data.message) : ''
  if (message.includes('未开启') || message.includes('桥接')) {
    throw new Error(t('apiAwdkBridgeDisabled'))
  }
  throw new Error(t('apiAwdkVerifyFailed'))
}

/**
 * 取本账号的平台 AI 通道额度（GET /api/platform-ai/key/status）。
 * 旧后端没有该端点（404）或任何失败时返回 null，由调用方隐藏额度卡片而不是报错——
 * 额度是附加信息，拿不到不该妨碍对话。
 */
export async function fetchPlatformAiStatus({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base || !token) return null
  try {
    const resp = await fetch(`${base}/api/platform-ai/key/status`, { headers: headers(token) })
    if (!resp.ok) return null
    const data = await resp.json()
    if (data && data.code === 0 && data.data) return data.data
  } catch (e) {
    // 静默降级：额度未知
  }
  return null
}

/**
 * 用官网账户 Key 重新取一把平台 AI 通道密钥（POST /api/platform-ai/key/refresh）。
 * 用于「在官网分配额度/重发密钥之后」——服务端不保存账户 Key，只能由用户再贴一次。
 * Key 用完即弃，不落本机存储。成功返回最新额度状态。
 */
export async function refreshPlatformAiKey({ serverUrl, token }, key) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error(t('apiServerUrlEmpty'))
  let resp
  try {
    resp = await fetch(`${base}/api/platform-ai/key/refresh`, {
      method: 'POST',
      headers: headers(token),
      body: JSON.stringify({ key: (key || '').trim() })
    })
  } catch (e) {
    throw new Error(t('apiBackendUnreachable'))
  }
  if (resp.status === 404) {
    throw new Error(t('apiAiRefreshUnsupported'))
  }
  if (!resp.ok) throw new Error(t('apiAiRefreshFailedHttp', { status: resp.status }))
  let data
  try {
    data = await resp.json()
  } catch (e) {
    throw new Error(t('apiBadResponseFormat'))
  }
  if (data && data.code === 0 && data.data) return data.data
  throw new Error(t('apiAiRefreshVerifyFailed'))
}

/**
 * 请求后端签发会话 ID（POST /api/agent/conversations，body {projectId}）。
 * 契约与后端并行分支约定；旧后端没有该端点（404）或任何失败时返回 null，
 * 由调用方静默回退到客户端生成的 conv-<毫秒>。
 */
export async function createConversation({ serverUrl, token }, projectId) {
  const base = normalizeBaseUrl(serverUrl)
  const resp = await fetch(`${base}/api/agent/conversations`, {
    method: 'POST',
    headers: headers(token),
    body: JSON.stringify({ projectId })
  })
  // 只有 404（旧后端没有签发端点）允许回退客户端自造 ID——那种后端也不校验签发。
  // 403/5xx 一律抛出：强制签发的云后端上，自造 ID 生来就是死的，落盘等于把用户锁死
  // （2026-08-24 mac 插件「SSE 403」事故的根因之一）。
  if (resp.status === 404) return null
  if (!resp.ok) {
    const err = new Error(t('apiConversationIssueFailedHttp', { status: resp.status }))
    err.status = resp.status
    throw err
  }
  let data = null
  try { data = await resp.json() } catch (e) { return null }
  if (data && typeof data.conversationId === 'string' && data.conversationId) {
    return data.conversationId
  }
  return null
}

/**
 * 发送一条对话消息（后端异步 200，回复经 SSE 推送）。
 */
export async function postChat({ serverUrl, token }, payload) {
  const base = normalizeBaseUrl(serverUrl)
  let resp
  try {
    resp = await fetch(`${base}/api/agent/chat`, {
      method: 'POST',
      headers: headers(token),
      body: JSON.stringify(payload)
    })
  } catch (e) {
    throw new Error(t('apiChatUnreachable'))
  }
  if (!resp.ok) throw new Error(t('apiChatFailedHttp', { status: resp.status }))
}

/**
 * 回传一条 office_command 的执行结果（Phase C 工具桥）。
 * payload: {requestId, ok, data|error}；后端按挂起表校验会话归属。
 * 回传失败时后端工具会在超时后拿到明确错误，这里只记录不重试。
 */
export async function postOfficeResult({ serverUrl, token }, payload) {
  const base = normalizeBaseUrl(serverUrl)
  try {
    const resp = await fetch(`${base}/api/agent/office/result`, {
      method: 'POST',
      headers: headers(token),
      body: JSON.stringify(payload)
    })
    if (!resp.ok) console.warn('[Addin] office 结果回传被拒绝', resp.status)
  } catch (e) {
    console.warn('[Addin] office 结果回传失败', e)
  }
}

/**
 * 请求后端停止当前会话的执行。
 */
export async function postCancel({ serverUrl, token }, conversationId) {
  const base = normalizeBaseUrl(serverUrl)
  try {
    await fetch(`${base}/api/agent/cancel/${conversationId}`, {
      method: 'POST',
      headers: headers(token)
    })
  } catch (e) {
    // 停止是尽力而为：连接断了也要允许前端解锁
  }
}

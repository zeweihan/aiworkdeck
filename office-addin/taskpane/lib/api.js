import { normalizeBaseUrl } from './settings.js'

/**
 * 后端 REST 访问。鉴权统一走 X-Session-Id 请求头携带 awdt_ 设备令牌
 * （后端 getUserIdFromSession 支持前缀解析）。
 *
 * 注意：错误文案不得含「登录/未授权/请先」子串——主前端以这三个子串判定
 * 未登录并清会话，这里沿用同一红线，统一说「连接未就绪/令牌无效」。
 */

function headers(token) {
  return { 'Content-Type': 'application/json', 'X-Session-Id': token || '' }
}

/**
 * 拉取我的项目列表；同时用作「连接测试」——通了说明地址可达且令牌有效。
 */
export async function fetchMyProjects({ serverUrl, token }) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) throw new Error('连接未就绪：后端地址为空')
  let resp
  try {
    resp = await fetch(`${base}/api/projects/my`, { headers: headers(token) })
  } catch (e) {
    throw new Error('后端不可达：请检查地址、网络与 HTTPS/证书')
  }
  if (!resp.ok) {
    throw new Error(`连接失败（HTTP ${resp.status}）：令牌无效或后端拒绝了请求`)
  }
  const data = await resp.json()
  if (!Array.isArray(data)) throw new Error('后端响应格式异常')
  return data
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
    throw new Error('后端不可达：消息未送出')
  }
  if (!resp.ok) throw new Error(`对话请求失败（HTTP ${resp.status}）`)
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

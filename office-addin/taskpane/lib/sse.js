/**
 * SSE 消费：与主前端 useAgentStream 同一方式——fetch + ReadableStream 手工解析
 * `event:`/`data:` 行（不用 EventSource，因为要携带 X-Session-Id 请求头）。
 *
 * 断线自动重连：
 * - 首次建连失败：ready reject，不重连（保持「后端不可达」的即时报错体验）；
 * - 首次建连成功后流中断（网络抖动/后端重启/代理掐空闲连接）：指数退避重连
 *   （1s 起、每次翻倍、上限 30s），重连成功即复位退避；
 * - 死连接判定：后端每 15s 发一次 heartbeat 事件（SseEmitterService），
 *   连续两个心跳周期加余量（40s）收不到任何字节即视为死连接，掐掉重连；
 * - 事件是纯推送（后端不重放历史），重连后不会重复收到已渲染的消息；
 *   重连期间漏掉的终态事件（bubble_end 等）由调用方消费 run_state 事件兜底。
 * - onClose 只在 close() 主动关闭时触发；重连状态经 onStatus('reconnecting'|'connected') 通知。
 */
const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 30000
// 心跳周期 15s（后端 HEARTBEAT_INTERVAL_SECONDS）x2 + 余量
const DEAD_CONNECTION_MS = 40000
const WATCHDOG_TICK_MS = 5000

export function createSseConnection({ baseUrl, token, conversationId, onEvent, onClose, onStatus }) {
  let controller = null
  let closed = false
  let backoffMs = RECONNECT_BASE_MS
  let reconnectTimer = null
  let watchdogTimer = null
  let lastActivity = Date.now()
  let reading = false

  const notifyStatus = (status) => {
    try { if (onStatus) onStatus(status) } catch (e) { /* ignore */ }
  }

  async function connectOnce() {
    controller = new AbortController()
    const resp = await fetch(`${baseUrl}/api/agent/connect/${conversationId}`, {
      method: 'GET',
      headers: { 'X-Session-Id': token || '' },
      signal: controller.signal
    })
    if (!resp.ok) throw new Error(`SSE 建连失败（HTTP ${resp.status}）：令牌无效或后端拒绝了请求`)
    return resp
  }

  function startWatchdog() {
    stopWatchdog()
    lastActivity = Date.now()
    watchdogTimer = setInterval(() => {
      if (Date.now() - lastActivity > DEAD_CONNECTION_MS) {
        console.warn('[Addin] SSE 心跳缺失，判定死连接，主动掐掉重连')
        // abort 会让读流抛 AbortError，由 readLoop 的收尾逻辑走重连
        if (controller) controller.abort()
      }
    }, WATCHDOG_TICK_MS)
  }

  function stopWatchdog() {
    if (watchdogTimer) { clearInterval(watchdogTimer); watchdogTimer = null }
  }

  async function readLoop(resp) {
    reading = true
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let eventName = null
    let eventData = ''
    const flush = () => {
      if (eventData) {
        try { onEvent(eventName, eventData) } catch (e) { console.error('[Addin] onEvent 异常', e) }
      }
      eventName = null
      eventData = ''
    }
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        lastActivity = Date.now()
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() // 保留最后一段不完整行
        for (const line of lines) {
          if (!line.trim()) { flush(); continue }
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim()
          } else if (line.startsWith('data:')) {
            let v = line.substring(5)
            if (v.startsWith(' ')) v = v.substring(1)
            eventData += (eventData ? '\n' : '') + v
          }
        }
      }
    } catch (e) {
      if (e.name !== 'AbortError' || !closed) console.warn('[Addin] SSE 读流中断', e)
    } finally {
      reading = false
      stopWatchdog()
      if (closed) {
        if (onClose) onClose()
      } else {
        scheduleReconnect()
      }
    }
  }

  function scheduleReconnect() {
    if (closed || reconnectTimer) return
    notifyStatus('reconnecting')
    const delay = backoffMs
    backoffMs = Math.min(backoffMs * 2, RECONNECT_MAX_MS)
    reconnectTimer = setTimeout(async () => {
      reconnectTimer = null
      if (closed) return
      try {
        const resp = await connectOnce()
        backoffMs = RECONNECT_BASE_MS
        notifyStatus('connected')
        startWatchdog()
        readLoop(resp)
      } catch (e) {
        if (!closed) {
          console.warn('[Addin] SSE 重连失败，继续退避', e)
          scheduleReconnect()
        }
      }
    }, delay)
  }

  const ready = (async () => {
    // 首连失败直接抛给调用方（不进重连循环）；成功后交给 readLoop 维护
    const resp = await connectOnce()
    notifyStatus('connected')
    startWatchdog()
    readLoop(resp)
  })()

  return {
    ready,
    close() {
      closed = true
      stopWatchdog()
      if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
      if (controller) controller.abort()
      // 没有活跃读流（重连等待期/首连未成）时不会再有 finally 收尾，这里直接通知关闭
      if (!reading && onClose) onClose()
    }
  }
}

/**
 * text_delta 的 content 是带 XML 标签的文本流（<thinking>/<process>/<final> 等，
 * 见后端 AgentStreamHandler 协议）。MVP 只区分三类：
 *   主文本 = 标签外的裸文本 + <final> 内容
 *   思考   = <thinking> 内容
 *   其余（process/artifact/title/walkthrough/tool_code 等）不渲染。
 * 未知标签当普通文本放行，避免误吞正文里的 "<"。
 */
const KNOWN_TAGS = new Set([
  'thinking', 'title', 'process', 'artifact', 'final', 'walkthrough',
  'tool_code', 'step', 'tool', 'tool_output', 'bubble_type'
])
const TAG_RE = /^<(\/?)([a-zA-Z_][\w-]*)(\s[^>]*)?>$/
// "<" 之后仍可能补成合法标签的形态（尚未见到 ">"）
const PARTIAL_TAG_RE = /^<\/?[a-zA-Z_]?[\w-]*(\s[^>]*)?$/

export function createTagStreamParser({ onMainText, onThinkingText }) {
  let pending = ''
  const stack = []

  const route = (text) => {
    if (!text) return
    if (stack.includes('final') || stack.length === 0) { onMainText(text); return }
    if (stack.includes('thinking')) { onThinkingText(text) }
    // 其余标签内的内容：MVP 不渲染
  }

  const step = () => {
    const lt = pending.indexOf('<')
    if (lt === -1) { route(pending); pending = ''; return false }
    if (lt > 0) { route(pending.slice(0, lt)); pending = pending.slice(lt) }
    const gt = pending.indexOf('>')
    if (gt === -1) {
      // 结尾是半截标签：暂存等下一段字节；明显不是标签则按普通文本放行
      if (pending.length <= 300 && PARTIAL_TAG_RE.test(pending)) return false
      route('<')
      pending = pending.slice(1)
      return true
    }
    const candidate = pending.slice(0, gt + 1)
    const m = TAG_RE.exec(candidate)
    if (m && KNOWN_TAGS.has(m[2])) {
      const name = m[2]
      if (m[1] === '/') {
        const idx = stack.lastIndexOf(name)
        if (idx !== -1) stack.splice(idx, 1)
      } else if (!candidate.endsWith('/>')) {
        stack.push(name)
      }
    } else {
      route(candidate) // 未知标签按普通文本放行
    }
    pending = pending.slice(gt + 1)
    return true
  }

  return {
    feed(chunk) {
      pending += chunk
      while (pending) { if (!step()) break }
    },
    flush() {
      route(pending)
      pending = ''
    }
  }
}

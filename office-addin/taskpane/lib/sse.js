/**
 * SSE 消费：与主前端 useAgentStream 同一方式——fetch + ReadableStream 手工解析
 * `event:`/`data:` 行（不用 EventSource，因为要携带 X-Session-Id 请求头）。
 */
export function createSseConnection({ baseUrl, token, conversationId, onEvent, onClose }) {
  const controller = new AbortController()

  const ready = (async () => {
    const resp = await fetch(`${baseUrl}/api/agent/connect/${conversationId}`, {
      method: 'GET',
      headers: { 'X-Session-Id': token || '' },
      signal: controller.signal
    })
    if (!resp.ok) throw new Error(`SSE 建连失败（HTTP ${resp.status}）：令牌无效或后端拒绝了请求`)

    // 建连成功后在后台持续读流；读流的生命周期与 ready 的 resolve 解耦
    ;(async () => {
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
        if (e.name !== 'AbortError') console.warn('[Addin] SSE 读流中断', e)
      } finally {
        if (onClose) onClose()
      }
    })()
  })()

  return {
    ready,
    close() { controller.abort() }
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

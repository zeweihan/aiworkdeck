/**
 * 流式渲染与发送契约的回归用例（dev-board#147/#148/#150）。
 *   node --test office-addin/taskpane/lib/streamRender.test.js
 *
 * 钉住四件事：
 * 1. 协议标签之间的裸换行不进正文（把 chatSession 的守卫拿掉即转红）——
 *    维护者截图「一个光标+一串空行往下走」的根因；
 * 2. bubble_end 后消息标记 done、尾部空白裁掉、streaming 解锁；
 * 3. SSE 处于重连退避时 reconnectNow 立即重连（发送不再撞上服务端静默丢事件窗口）；
 * 4. 发送 payload 契约：不附带正文也上送 activeContext 壳、model/skillIds 按选择上送。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage / Office 环境桩（须在 import 被测模块之前就位） ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}
// detectHost 的兜底路径：有 Word 全局即判 word 宿主（node 里没有 Office.js）
globalThis.Word = globalThis.Word || {}

const {
  activateSession, messages, streaming, input, send, stop,
  chooseModel, selectedSkillIds
} = await import('./chatSession.js')
const { createSseConnection } = await import('./sse.js')

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

/** 依次吐出 chunks 的 SSE 响应体；吐完后挂起（不结束流） */
function sseScriptedResponse(chunks) {
  const queue = [...chunks]
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: () => (queue.length
          ? Promise.resolve({ done: false, value: new TextEncoder().encode(queue.shift()) })
          : new Promise(() => {}))
      })
    }
  }
}

function sseEvent(event, data) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`
}

function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return { calls, restore: () => { globalThis.fetch = original } }
}

async function until(cond, ms = 2000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (cond()) return true
    await new Promise((r) => setTimeout(r, 20))
  }
  return cond()
}

test('标签间裸换行不进正文；bubble_end 标记 done、裁尾部空白、解锁 streaming', async () => {
  store.clear()
  const chunks = [
    sseEvent('text_delta', { content: '\n\n' }),
    sseEvent('text_delta', { content: '<thinking>想一想</thinking>\n\n' }),
    sseEvent('text_delta', { content: '<final>你好，正文来了</final>\n' }),
    sseEvent('bubble_end', { status: 'completed' })
  ]
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-sr-1' })
    if (url.includes('/api/agent/connect/')) return sseScriptedResponse([])
    if (url.endsWith('/api/agent/chat')) return jsonReply({ code: 0 })
    if (url.includes('/api/ai/models') || url.includes('/api/skills/list')) return jsonReply([], false, 404)
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error('未预期请求: ' + url)
  })
  try {
    await activateSession({
      settings: { serverUrl: 'https://x.example', token: 'awdt_sr1' }, projectId: '21'
    })
    // 发送后把脚本化事件从一条新连接里灌进来：send 的 preconnect 复用既有连接，
    // 这里直接换 fetch 的 connect 响应为脚本流并强断重连太绕——改走更直接的路：
    // 关掉现有连接，让 send 兜底重建时拿到脚本化响应。
    await stop()
    globalThis.fetch = async (url, options = {}) => {
      if (String(url).includes('/api/agent/connect/')) return sseScriptedResponse(chunks)
      if (String(url).endsWith('/api/agent/chat')) return jsonReply({ code: 0 })
      if (String(url).includes('/api/ai/history')) return jsonReply([])
      return jsonReply({}, false, 404)
    }
    input.value = '测试'
    await send()
    assert.ok(await until(() => {
      const last = messages.value[messages.value.length - 1]
      return last && last.role === 'assistant' && last.done
    }), '等待 bubble_end 标记 done 超时')
    const last = messages.value[messages.value.length - 1]
    assert.equal(last.text, '你好，正文来了', '前导换行必须被守卫吞掉、尾部空白必须裁掉')
    assert.equal(streaming.value, false)
    assert.equal(last.streaming, false)
  } finally {
    await stop()
    f.restore()
  }
})

test('重连退避期 reconnectNow 立即重连，不等退避周期', async () => {
  let connectCalls = 0
  const f = stubFetch((url) => {
    if (url.includes('/connect/')) {
      connectCalls++
      if (connectCalls === 1) {
        // 首连成功但流立刻正常结束（后端每轮结束会主动关流）→ 进退避
        return {
          ok: true, status: 200,
          body: { getReader: () => ({ read: () => Promise.resolve({ done: true }) }) }
        }
      }
      return sseScriptedResponse([])
    }
    throw new Error('未预期请求: ' + url)
  })
  try {
    const conn = createSseConnection({
      baseUrl: 'https://x.example', token: 't', conversationId: 'c-rn',
      onEvent: () => {}, onStatus: () => {}, onClose: () => {}
    })
    await conn.ready
    await until(() => connectCalls >= 1)
    // 此刻处于 1s 退避等待中；reconnectNow 应当立即触发第二次 connect
    const t0 = Date.now()
    await conn.reconnectNow()
    assert.equal(connectCalls, 2, 'reconnectNow 未立即重连')
    assert.ok(Date.now() - t0 < 500, `重连耗时 ${Date.now() - t0}ms，像是等了退避计时器`)
    conn.close()
  } finally {
    f.restore()
  }
})

test('<artifact> 整块捕获为计划卡内容，不混进正文（流式与历史回放同口径）', async () => {
  const { createTagStreamParser } = await import('./sse.js')
  let main = ''
  const artifacts = []
  const p = createTagStreamParser({
    onMainText: (t) => { main += t },
    onThinkingText: () => {},
    onQuestion: () => {},
    onArtifact: (c) => artifacts.push(c)
  })
  p.feed('<artifact>\n一、先改定义条款\n二、再改违约条款\n</artifact>')
  p.feed('<final>计划在上面的卡片里。</final>')
  p.flush()
  assert.deepEqual(artifacts, ['一、先改定义条款\n二、再改违约条款'])
  assert.ok(!main.includes('定义条款'), '计划内容不许混进正文')
  assert.equal(main, '计划在上面的卡片里。')

  // 截断（未闭合）也不丢已解析的计划内容
  const arts2 = []
  const p2 = createTagStreamParser({
    onMainText: () => {}, onThinkingText: () => {}, onQuestion: () => {}, onArtifact: (c) => arts2.push(c)
  })
  p2.feed('<artifact>只有半截计划')
  p2.flush()
  assert.deepEqual(arts2, ['只有半截计划'])
})

test('发送契约：不附带正文也上送 activeContext 壳；model/skillIds 随选择上送', async () => {
  store.clear()
  let chatBody = null
  const f = stubFetch((url, options) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-sr-2' })
    if (url.includes('/api/agent/connect/')) return sseScriptedResponse([])
    if (url.endsWith('/api/agent/chat')) {
      chatBody = JSON.parse(options.body)
      return jsonReply({ code: 0 })
    }
    if (url.includes('/api/ai/models') || url.includes('/api/skills/list')) return jsonReply([], false, 404)
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error('未预期请求: ' + url)
  })
  try {
    await activateSession({
      settings: { serverUrl: 'https://x.example', token: 'awdt_sr2' }, projectId: '22'
    })
    const { includeDocument } = await import('./chatSession.js')
    includeDocument.value = false
    chooseModel('test-model-x')
    selectedSkillIds.value = ['skill-a', 'skill-b']
    const { attachedFiles } = await import('./chatSession.js')
    attachedFiles.value = [{ id: 88, name: '尽调底稿.docx', fileType: 'docx' }]
    input.value = '走一条'
    await send()
    assert.ok(chatBody, 'POST /chat 没发出去')
    // 壳契约：不带正文也要有 id/name/fileType，否则后端整段 office 工具指引不注入
    assert.equal(chatBody.activeContext.id, 'office-current-document')
    assert.equal(chatBody.activeContext.fileType, 'docx')
    assert.equal(chatBody.activeContext.inlineContent, undefined)
    assert.equal(chatBody.model, 'test-model-x')
    assert.deepEqual(chatBody.skillIds, ['skill-a', 'skill-b'])
    assert.deepEqual(chatBody.contextItems, [{ id: '88', name: '尽调底稿.docx', fileType: 'docx' }])
  } finally {
    includeDocumentReset()
    await stop()
    f.restore()
  }

  async function includeDocumentReset() {
    const { includeDocument } = await import('./chatSession.js')
    includeDocument.value = true
    chooseModel('')
    selectedSkillIds.value = []
    const { attachedFiles } = await import('./chatSession.js')
    attachedFiles.value = []
  }
})

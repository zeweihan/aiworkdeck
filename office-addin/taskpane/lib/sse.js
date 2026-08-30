/**
 * SSE 消费：与主前端 useAgentStream 同一方式——fetch + ReadableStream 手工解析
 * `event:`/`data:` 行（不用 EventSource，因为要携带 X-Session-Id 请求头）。
 * 老内核降级：WPS 任务窗格的 CEF 内核版本参差，探测不到流式 fetch 能力
 * （或运行时拿不到 resp.body 读流）时整条降级为 XHR onprogress 增量读，
 * 行协议解析、心跳看门狗、重连语义两条通道完全一致。
 *
 * 断线自动重连：
 * - 首次建连失败：ready reject，不重连（保持「后端不可达」的即时报错体验）；
 * - 首次建连成功后流中断（网络抖动/后端重启/代理掐空闲连接）：指数退避重连
 *   （1s 起、每次翻倍、上限 30s）；
 * - 死连接判定：后端每 15s 发一次 heartbeat 事件（SseEmitterService），
 *   连续两个心跳周期加余量（40s）收不到任何字节即视为死连接，掐掉重连；
 * - **退避只在连接活够 STABLE_MS 之后才复位**（dev-board#285）：老写法在
 *   「建连成功」那一刻就把 backoff 复位成 1s，而真实故障形态恰恰是
 *   「每次都连得上、连上就被立刻断开」（两个任务窗格抢同一个 conversationId 时
 *   互相顶掉，实测 1 Hz 打满 9 分钟）——指数退避于是永远不生效。
 *   现在连接必须活满 STABLE_MS 才算「这次是好连接」，短命连接一律继续翻倍退避。
 * - **断点续传**：每个事件带 id（后端自增序号），重连时用 Last-Event-ID 请求头
 *   要回断线期间漏掉的事件（SSE 规范内建机制）。旧后端不认这个头时行为不变
 *   （不补发，退回 run_state 兜底），不会更糟。
 * - onClose 只在 close() 主动关闭时触发；重连状态经 onStatus('reconnecting'|'connected') 通知。
 * - `superseded` 事件：后端告知本连接已被同会话的另一个窗格接管。这时**必须停止重连**
 *   （继续重连就是互顶循环的另一半），交给调用方提示用户。
 */
const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 30000
// 心跳周期 15s（后端 HEARTBEAT_INTERVAL_SECONDS）x2 + 余量
const DEAD_CONNECTION_MS = 40000
const WATCHDOG_TICK_MS = 5000
// 连接活够这么久才算「稳定」，才敢把退避复位。取值要大于一次心跳间隔的一半、
// 又小于用户会察觉的等待——5s 足以区分「正常连接」与「连上即被顶断」。
const STABLE_CONNECTION_MS = 5000
// 熔断：滚动窗口内的建连次数超过阈值就直接顶到最大退避并告警。
// 退避复位修好了「每次都成功」的形态，这一条兜住其余所有意料之外的抖动源。
const CHURN_WINDOW_MS = 60000
const CHURN_LIMIT = 8

/**
 * fetch 流式消费的能力探测。WPS 任务窗格跑在随宿主版本参差的 CEF 内核里，
 * 老内核可能没有 ReadableStream/AbortController（fetch 本身也可能没有）——
 * 探测不过就整条降级到 XHR onprogress 通道（任何年代的内核都有）。
 * Office 家族的现代 webview 恒走 fetch 主路，行为不变。
 */
function streamFetchSupported() {
  try {
    return typeof fetch === 'function'
      && typeof AbortController === 'function'
      && typeof TextDecoder === 'function'
      && typeof ReadableStream !== 'undefined'
  } catch (e) {
    return false
  }
}

export function createSseConnection({ baseUrl, token, conversationId, clientId, onEvent, onClose, onStatus }) {
  let controller = null
  let closed = false
  let backoffMs = RECONNECT_BASE_MS
  let reconnectTimer = null
  let watchdogTimer = null
  let stableTimer = null
  let lastActivity = Date.now()
  let reading = false
  // 后端已把本会话移交给另一个窗格：置起后不再重连（见文件头 superseded 那条）
  let superseded = false
  // 本次建连是否还活着。**稳定计时器只许给活着的连接上**：两条通道里
  // 「读流结束」与「建连 promise 兑现」的先后并不固定（同步收尾的实现会先收尾后兑现），
  // 不看这个标记的话，会给一条已经断掉的连接留下一个 5 秒后复位退避的计时器，
  // 退避于是又永远长不起来——正是本次要修的那个病，绕了个弯回来。
  let connectionLive = false
  // 断点续传游标：收到的最后一个事件 id，重连时经 Last-Event-ID 上送要回漏掉的事件
  let lastEventId = ''
  // 建连时刻的滚动记录，用于熔断（见 CHURN_LIMIT）
  const connectTimes = []
  // fetch 主路在运行时发现拿不到读流（个别内核 resp.body 为空）时永久翻到 XHR
  let forceXhr = !streamFetchSupported()

  const notifyStatus = (status) => {
    try { if (onStatus) onStatus(status) } catch (e) { /* ignore */ }
  }

  const connectUrl = () => `${baseUrl}/api/agent/connect/${conversationId}`

  /**
   * 两条读流通道共用的请求头。
   * - X-Client-Instance：本任务窗格实例的身份。后端据它判断「同会话的新连接是不是
   *   同一个窗格」——不同窗格抢同一个会话时做一次性移交（给旧连接发 superseded 再关），
   *   而不是无声互顶（dev-board#285）。
   * - Last-Event-ID：SSE 规范里的断点续传游标，后端按它补发断线期间的事件。
   */
  const requestHeaders = () => {
    const h = { 'X-Session-Id': token || '' }
    if (clientId) h['X-Client-Instance'] = clientId
    if (lastEventId) h['Last-Event-ID'] = lastEventId
    return h
  }

  function httpError(status) {
    const err = new Error(`SSE 建连失败（HTTP ${status}）：令牌无效或后端拒绝了请求`)
    // 挂上状态码：403（会话不归当前用户/签发登记已丢）是调用方能自愈的，其余不能
    err.status = status
    return err
  }

  /** `id:`/`event:`/`data:` 行协议的增量解析（fetch 与 XHR 两条读流通道共用） */
  function createLineParser() {
    let buffer = ''
    let eventName = null
    let eventData = ''
    let eventId = null
    const flush = () => {
      if (eventData) {
        // 游标在派发之前推进：onEvent 抛异常也不该让同一个事件在下次重连时再来一遍
        if (eventId) lastEventId = eventId
        // 移交通知要在派发之前记下：后端紧接着就会关流，收尾逻辑读的就是这个标记
        if (eventName === 'superseded') superseded = true
        try { onEvent(eventName, eventData) } catch (e) { console.error('[Addin] onEvent 异常', e) }
      }
      eventName = null
      eventData = ''
      eventId = null
    }
    return {
      feed(text) {
        buffer += text
        const lines = buffer.split(/\r?\n/)
        buffer = lines.pop() // 保留最后一段不完整行
        for (const line of lines) {
          if (!line.trim()) { flush(); continue }
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim()
          } else if (line.startsWith('id:')) {
            eventId = line.substring(3).trim()
          } else if (line.startsWith('data:')) {
            let v = line.substring(5)
            if (v.startsWith(' ')) v = v.substring(1)
            eventData += (eventData ? '\n' : '') + v
          }
        }
      }
    }
  }

  async function connectOnce() {
    controller = new AbortController()
    const resp = await fetch(connectUrl(), {
      method: 'GET',
      headers: requestHeaders(),
      signal: controller.signal
    })
    if (!resp.ok) throw httpError(resp.status)
    return resp
  }

  /**
   * 建连成功后的共用记账：稳定计时器（活满 STABLE_CONNECTION_MS 才复位退避）+ 熔断计数。
   * 复位放在计时器里而不是这里，是本次修复的要害——见文件头注释。
   */
  function markConnected() {
    clearStableTimer()
    if (connectionLive) {
      stableTimer = setTimeout(() => {
        stableTimer = null
        backoffMs = RECONNECT_BASE_MS
      }, STABLE_CONNECTION_MS)
    }
    const now = Date.now()
    connectTimes.push(now)
    while (connectTimes.length && now - connectTimes[0] > CHURN_WINDOW_MS) connectTimes.shift()
    if (connectTimes.length > CHURN_LIMIT) {
      // 一分钟内建连超过阈值：不管起因是什么，先把频率压到最低，并让界面能说明白
      backoffMs = RECONNECT_MAX_MS
      notifyStatus('unstable')
    }
  }

  function clearStableTimer() {
    if (stableTimer) { clearTimeout(stableTimer); stableTimer = null }
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

  /** 读流收尾（两条通道共用）：主动关闭→通知，意外断流→重连 */
  function finishReading() {
    reading = false
    connectionLive = false
    stopWatchdog()
    clearStableTimer()
    if (closed) {
      if (onClose) onClose()
    } else if (superseded) {
      // 本会话已被另一个窗格接管：再重连就是互顶循环的另一半，就此收手
      notifyStatus('superseded')
    } else {
      scheduleReconnect()
    }
  }

  async function readLoop(resp) {
    reading = true
    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    const parser = createLineParser()
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        lastActivity = Date.now()
        parser.feed(decoder.decode(value, { stream: true }))
      }
    } catch (e) {
      if (e.name !== 'AbortError' || !closed) console.warn('[Addin] SSE 读流中断', e)
    } finally {
      finishReading()
    }
  }

  /**
   * XHR 降级通道：onreadystatechange 里增量读 responseText。
   * 返回 Promise，语义与 connectOnce 对齐——头部到达且 200 时 resolve（此时
   * reading 已置起、读流由回调驱动），非 200/网络失败时 reject。整条响应会
   * 累积在 responseText 里，后端每轮结束主动关流，单轮体量有限，可接受。
   */
  function connectAndReadXhr() {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      controller = { abort() { try { xhr.abort() } catch (e) { /* 已关 */ } } }
      let seen = 0
      let opened = false
      let settled = false
      const parser = createLineParser()
      const consumeNew = () => {
        let text = ''
        try { text = xhr.responseText || '' } catch (e) { return /* abort 后个别引擎不让读 */ }
        if (text.length > seen) {
          lastActivity = Date.now()
          parser.feed(text.slice(seen))
          seen = text.length
        }
      }
      xhr.onreadystatechange = () => {
        if (!opened && xhr.readyState >= 2 && xhr.status) {
          if (xhr.status === 200) {
            opened = true
            reading = true
            if (!settled) { settled = true; resolve() }
          } else {
            const err = httpError(xhr.status)
            try { xhr.abort() } catch (e) { /* ignore */ }
            if (!settled) { settled = true; reject(err) }
            return
          }
        }
        if (opened && (xhr.readyState === 3 || xhr.readyState === 4)) consumeNew()
        if (xhr.readyState === 4 && opened) finishReading()
      }
      xhr.onerror = xhr.ontimeout = () => {
        if (!settled) {
          settled = true
          reject(new Error('SSE 建连失败：网络不可达'))
          return
        }
        // 已在读流中失败：走统一收尾（重连或关闭）
        if (opened && reading) finishReading()
      }
      // abort（看门狗/close 主动掐）在部分引擎只触发 onabort 不触发 readystatechange，
      // 两边都挂收尾；finishReading 内 reading 置 false，双触发也只收尾一次
      xhr.onabort = () => {
        if (opened && reading) finishReading()
      }
      try {
        xhr.open('GET', connectUrl(), true)
        const headers = requestHeaders()
        for (const k of Object.keys(headers)) {
          try { xhr.setRequestHeader(k, headers[k]) } catch (e) { /* 个别内核拒收自定义头，不致命 */ }
        }
        xhr.send()
      } catch (e) {
        if (!settled) { settled = true; reject(e) }
      }
    })
  }

  /**
   * 建连一次（通道自适应）：fetch 主路拿不到读流（老内核 resp.body 缺失）时
   * 永久翻到 XHR 再试本次。resolve 即已连上且读流开始维护。
   */
  async function connectAndRead() {
    connectionLive = true
    if (!forceXhr) {
      const resp = await connectOnce()
      if (resp.body && typeof resp.body.getReader === 'function') {
        startWatchdog()
        markConnected()
        readLoop(resp)
        return
      }
      console.warn('[Addin] fetch 响应无读流，SSE 降级到 XHR 通道')
      forceXhr = true
    }
    await connectAndReadXhr()
    startWatchdog()
    markConnected()
  }

  let connecting = false

  async function attemptReconnect() {
    if (closed || connecting) return
    connecting = true
    try {
      await connectAndRead()
      // 退避复位不在这里做：连得上不等于连得住（见 markConnected 与文件头注释）
      notifyStatus('connected')
    } catch (e) {
      if (!closed) {
        console.warn('[Addin] SSE 重连失败，继续退避', e)
        scheduleReconnect()
      }
    } finally {
      connecting = false
    }
  }

  function scheduleReconnect() {
    if (closed || superseded || reconnectTimer) return
    notifyStatus('reconnecting')
    const delay = backoffMs
    backoffMs = Math.min(backoffMs * 2, RECONNECT_MAX_MS)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      attemptReconnect()
    }, delay)
  }

  const ready = (async () => {
    // 首连失败直接抛给调用方（不进重连循环）；成功后由读流通道维护
    await connectAndRead()
    notifyStatus('connected')
  })()

  return {
    ready,
    /**
     * 处于重连退避等待时立刻重连；健康（读流在跑）或已关闭时是空操作。
     * 给发送路径用：后端每轮结束会主动关 SSE，若下一条消息落在退避窗口里，
     * POST /chat 发出去后 emitter 不在，快回合的 text_delta 乃至 bubble_end
     * 会被服务端静默丢弃（SseEmitterService 对无 emitter 会话不报错），
     * 最坏要等满一个 30s 退避周期才靠 run_state 兜底解锁——表现就是
     * 「文档都写完了光标还一直闪」（dev-board#147 窗口 A）。
     */
    reconnectNow() {
      if (closed || reading) return Promise.resolve()
      if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
      return attemptReconnect()
    },
    close() {
      closed = true
      stopWatchdog()
      clearStableTimer()
      if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
      // 先记住 abort 前是否有活跃读流：XHR 通道的 abort 会**同步**触发 finishReading
      // （其中已含 onClose），abort 后再看 reading 会误判成「没有读流」而二次 onClose
      const wasReading = reading
      if (controller) controller.abort()
      // 没有活跃读流（重连等待期/首连未成）时不会再有收尾回调，这里直接通知关闭
      if (!wasReading && onClose) onClose()
    }
  }
}

/**
 * text_delta 的 content 是带 XML 标签的文本流（<thinking>/<process>/<final> 等，
 * 见后端 AgentStreamHandler 协议）。MVP 只区分三类：
 *   主文本 = 标签外的裸文本 + <final> 内容 + <question> 正文
 *   思考   = <thinking> 内容
 *   其余（process/artifact/title/walkthrough/tool_code 等）不渲染。
 *
 * 反问 <question>：正文进主文本（与桌面端 useAgentStream 同语义），内含的
 * <option> 子标签是备选答案，不进正文，闭合时整块经 onQuestion 交给界面做按钮。
 *
 * 未知标签的默认从「当正文放行」改成「只吞标记」：默认放行意味着后端每新增一个
 * 标签，插件都比桌面端慢一步，而代价是用户当场看到裸的 XML 源码。判据收紧到
 * 「协议标签的形状」而不是「所有尖括号」，详见 step() 里的取舍说明。
 */
// 必须覆盖后端 AgentTagProtocol.TAGS（= 桌面端 agentTagProtocol.mjs 的 PROTOCOL_TAGS）：
// 后端只中和那份清单里的标签，清单外的标签名若在这里被当成标签、又出现在工具载荷里，
// 本解析器的标签栈就会在载荷中间错位、把工具输出漏进正文（历史回灌同一条路径）。
// 多认的 tool / bubble_type 不在中和清单里也无害：它们只会被 push/忽略，不会顶掉外层标签。
// 插件不渲染工具载荷，故不需要解转义；对拍由 backend AgentTagProtocolTest 守。
const KNOWN_TAGS = new Set([
  'thinking', 'title', 'process', 'artifact', 'final', 'walkthrough',
  'tool_code', 'step', 'tool', 'tool_output', 'bubble_type',
  'question', 'option'
])
const TAG_RE = /^<(\/?)([a-zA-Z_][\w-]*)(\s[^>]*)?>$/
// "<" 之后仍可能补成合法标签的形态（尚未见到 ">"）
const PARTIAL_TAG_RE = /^<\/?[a-zA-Z_]?[\w-]*(\s[^>]*)?$/
// 协议标签的形状：全小写 ASCII 的短 snake_case 名（协议里全部标签都长这样）。
// 未知但符合这个形状的标签按「像协议标签」处理，其余尖括号一律当正文。
const PROTOCOL_TAG_SHAPE_RE = /^[a-z][a-z0-9_]{0,23}$/

export function createTagStreamParser({ onMainText, onThinkingText, onQuestion, onArtifact, onToolPrep }) {
  let pending = ''
  const stack = []
  // 当前 <question> 块（未闭合时非空）与正在累积的 <option> 文案
  let question = null
  let optionBuf = ''
  // <artifact>（计划/交付物）内容：闭合时整块经 onArtifact 交给界面渲染成计划卡
  // （dev-board#150——此前直接丢弃，插件端看不到计划审批的内容本体）
  let artifactBuf = ''
  // 兜底缓冲（dev-board#287）：本气泡有没有产出过主文本，以及那些「路由到无处」的散文。
  // 插件刻意不渲染 process/step/walkthrough/title 这些作用域（MVP 取舍），但模型并不
  // 保证每一轮都输出 <final>——不输出的那一轮，整轮回复被逐字丢弃，用户拿到一个
  // 空白气泡，而气泡下面还写着「已完成 · N 秒」。这一对变量把「不渲染」与「丢光」
  // 分开：正常轮次行为完全不变，只有「一个字都没进正文」的轮次才把散文捞回来。
  let mainEmitted = false
  let salvageBuf = ''
  // 兜底缓冲的上限：只是为了不让空气泡，不是第二条渲染通道
  const SALVAGE_MAX = 20000

  const finishOption = () => {
    const text = optionBuf.trim()
    optionBuf = ''
    if (question && text) question.options.push(text)
  }

  const emitQuestion = () => {
    const q = question
    question = null
    if (q && onQuestion) onQuestion(q)
  }

  const route = (text) => {
    if (!text) return
    // <option> 内容是按钮文案，不能混进正文
    if (stack.includes('option')) { optionBuf += text; return }
    if (stack.includes('artifact')) { artifactBuf += text; return }
    if (stack.includes('final') || stack.includes('question') || stack.length === 0) {
      mainEmitted = true
      onMainText(text)
      return
    }
    if (stack.includes('thinking')) { onThinkingText(text); return }
    // 其余标签内的内容：MVP 不渲染，但要留一份兜底（见 mainEmitted/salvageBuf）。
    // 工具载荷除外——那是 JSON 参数，捞出来给用户看比空白更糟。
    if (stack.includes('tool_code') || stack.includes('tool') || stack.includes('tool_output')) return
    if (salvageBuf.length < SALVAGE_MAX) salvageBuf += text
  }

  const emitArtifact = () => {
    const content = artifactBuf.trim()
    artifactBuf = ''
    if (content && onArtifact) onArtifact(content)
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
        if (name === 'option') finishOption()
        else if (name === 'question') emitQuestion()
        else if (name === 'artifact') emitArtifact()
        // 退出工具参数区（tool_code 内容不渲染，但生成期可能长达一两分钟——
        // 界面据此撤下「正在准备文档内容」提示，别让盲区伪装成卡死）
        else if (name === 'tool_code' && onToolPrep && !stack.includes('tool_code')) onToolPrep(false)
      } else if (!candidate.endsWith('/>')) {
        if (name === 'tool_code' && onToolPrep && !stack.includes('tool_code')) onToolPrep(true)
        stack.push(name)
        if (name === 'question') question = { options: [] }
        else if (name === 'option') optionBuf = ''
      }
    } else if (m && PROTOCOL_TAG_SHAPE_RE.test(m[2])) {
      // 未知标签、但形状像协议标签：只吞掉标记本身，内容按外层上下文继续渲染。
      // 为什么不连内容一起吞：万一后端把承载正文的标签改名（如 final→answer），
      // 连吞会让用户收到一个空气泡，比多显示一段正文糟得多。
      // 为什么不整体放行：默认放行就是把 XML 源码给用户看，这正是本次要修的缺陷。
    } else {
      // 不像协议标签的尖括号——合同里的占位符（<甲方>、<Party A>、<甲方 全称>）
      // 都落在这里，原样当正文。判据是「协议标签的形状」，不是「所有尖括号」。
      //
      // **只放行这个 '<' 本身，从下一个字符重扫**（dev-board#70）：候选串是
      // 「本 '<' 到最近一个 '>'」，里面可能正包着真正的协议闭合标签——
      // 「净利润<0，需追加担保</thinking>」整段放行会把 </thinking> 一并当
      // 正文吞掉，标签栈从此错位，后续 <process>/<tool_code> 载荷全部漏进
      // 思考区（真机「金冠纾困」会话实况；逐字节流式路径因 PARTIAL_TAG_RE
      // 早失配反而没踩到，一次性喂入的历史回放路径必踩）。
      route('<')
      pending = pending.slice(1)
      return true
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
      // 标签没闭合就断流（截断、出错收尾）：已解析出的选项/计划内容不丢
      if (stack.includes('option')) finishOption()
      if (question) emitQuestion()
      emitArtifact()
      // tool_code 没闭合就断流：提示不能悬着
      if (onToolPrep && stack.includes('tool_code')) onToolPrep(false)
      // 兜底：整轮一个字都没进正文时，把被作用域规则丢掉的散文捞回来（dev-board#287）。
      // 触发条件是「本气泡从未产出主文本」，所以正常轮次（有 <final> 或裸文本）
      // 走不到这里，渲染口径不变；只有原本注定空白的那一轮会多出内容。
      if (!mainEmitted) {
        const salvaged = salvageBuf.trim()
        if (salvaged) {
          mainEmitted = true
          onMainText(salvaged)
        }
      }
      salvageBuf = ''
    }
  }
}

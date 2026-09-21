// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「一个窗格只许有一条会话」的端到端回归（dev-board#764）：
 *   node --test office-addin/taskpane/lib/chatSessionExcelBridge.test.js
 *
 * 病灶（2026-09-21 真机 Mac Excel）：用户发「Please add a total row under the table
 * and make the header row bold.」，表格纹丝不动，工具 chip 一个都没出现，气泡是空的，
 * 底下只有一句「这一轮的结束状态没能确认」。云后端日志显示后端其实干得好好的——
 * 模型两条 office_excel_* 都发出来了，OfficeBridgeService 也都「Sent office command」，
 * 然后一条接一条 30 秒超时。对不上的是会话 ID：
 *
 *   SSE  : GET /api/agent/connect/conv-1789960022590-UqGCq9i2hpH2xmWd
 *   chat : POST /api/agent/chat  conversationId=conv-1789960022592-i6jMU3F_ViRrQoY_
 *
 * 两条会话由**两次并发签发**产生（时间戳只差 2 毫秒）：activateSession 的预连还卡在
 * 签发那个往返上，用户已经把消息发出去了，send 的兜底 preconnect 看到 conversationId
 * 仍是 null，于是又签发了一次。先回来的那个建了 SSE，后回来的那个覆盖了
 * conversationId 并被 POST /chat 带走。此后后端推给 chat 那条会话的
 * client_action / text_delta / bubble_end 一条都到不了窗格（事件进了补发缓冲，
 * 等一个永远不会来的重连），**两端都不报错**。
 *
 * 与宿主无关——Excel 只是碰巧撞上了那个窗口（真机日志里 Word 隔了 15 秒才发第一条、
 * PPT 隔了 9 秒，Excel 只隔了 1.6 秒）。所以用例把那个窗口**做成确定的**：
 * 把签发端点闸住，让预连与发送必然并发。
 *
 * 还原病灶的办法（任一条都会让下面的用例转红）：
 *   - 把 preconnect 的 in-flight 去重去掉（改回直接跑 runPreconnect 的那种写法）；
 *   - 去重与 ensureConnection 里 `connectionConvId !== conversationId` 那道换通道的闸
 *     一起去掉（两道防线任留其一都挡得住这个形态，所以要一起去才看得到红）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage 内存桩 ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}

// ---- 宿主桩：Excel 任务窗格 ----
globalThis.Office = {
  HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
  context: {
    host: 'Excel',
    document: { url: '/Users/x/awd-pilot-costs.xlsx' },
    requirements: { isSetSupported: () => true }
  }
}

// ==================== 假工作簿 ====================

/** 'A5' / 'A1:B1' → {r0,c0,r1,c1}（0 起，闭区间） */
function parseAddress(addr) {
  const one = (s) => {
    const m = /^([A-Z]+)(\d+)$/.exec(s.trim().toUpperCase())
    let col = 0
    for (const ch of m[1]) col = col * 26 + (ch.charCodeAt(0) - 64)
    return { r: parseInt(m[2], 10) - 1, c: col - 1 }
  }
  const [a, b] = String(addr).split('!').pop().split(':')
  const start = one(a)
  const end = b ? one(b) : start
  return { r0: start.r, c0: start.c, r1: end.r, c1: end.c }
}

function colName(index) {
  let col = ''
  let n = index + 1
  while (n > 0) { const rem = (n - 1) % 26; col = String.fromCharCode(65 + rem) + col; n = Math.floor((n - 1) / 26) }
  return col
}
const cellKey = (r, c) => `${colName(c)}${r + 1}`

/** A1:B4 是表头 Item/Amount 加三行数字——与真机截图同一份数据 */
function makeWorkbook() {
  return {
    name: 'costs',
    cells: new Map([
      ['A1', 'Item'], ['B1', 'Amount'],
      ['A2', 'Site survey'], ['B2', 12500],
      ['A3', 'Structural report'], ['B3', 4800],
      ['A4', 'Legal fees'], ['B4', 9600]
    ]),
    bold: new Set()
  }
}

function makeRange(wb, addr) {
  const box = parseAddress(addr)
  return {
    get address() { return `${wb.name}!${cellKey(box.r0, box.c0)}:${cellKey(box.r1, box.c1)}` },
    get rowCount() { return box.r1 - box.r0 + 1 },
    get columnCount() { return box.c1 - box.c0 + 1 },
    rowIndex: box.r0,
    columnIndex: box.c0,
    isNullObject: false,
    load() {},
    getResizedRange(dr, dc) {
      return makeRange(wb, `${cellKey(box.r0, box.c0)}:${cellKey(box.r1 + dr, box.c1 + dc)}`)
    },
    get values() {
      const out = []
      for (let r = box.r0; r <= box.r1; r++) {
        const row = []
        for (let c = box.c0; c <= box.c1; c++) row.push(wb.cells.has(cellKey(r, c)) ? wb.cells.get(cellKey(r, c)) : '')
        out.push(row)
      }
      return out
    },
    set values(v) {
      for (let r = 0; r < v.length; r++) {
        for (let c = 0; c < v[r].length; c++) wb.cells.set(cellKey(box.r0 + r, box.c0 + c), v[r][c])
      }
    },
    format: {
      font: {
        set bold(on) {
          for (let r = box.r0; r <= box.r1; r++) {
            for (let c = box.c0; c <= box.c1; c++) {
              if (on) wb.bold.add(cellKey(r, c)); else wb.bold.delete(cellKey(r, c))
            }
          }
        }
      },
      fill: {}
    },
    select() {}
  }
}

function installExcel(wb) {
  const sheet = {
    name: wb.name,
    load() {},
    getRange: (addr) => makeRange(wb, addr || 'A1'),
    getUsedRangeOrNullObject: () => makeRange(wb, 'A1:B4'),
    getRangeByIndexes: (r, c, nr, nc) => makeRange(wb, `${cellKey(r, c)}:${cellKey(r + nr - 1, c + nc - 1)}`)
  }
  globalThis.Excel = {
    run: async (cb) => cb({
      workbook: { worksheets: { getActiveWorksheet: () => sheet, getItem: () => sheet } },
      sync: async () => {}
    })
  }
}

// ==================== 假云后端 ====================

const enc = new TextEncoder()

/** 一条 SSE 通道：测试往里推事件，读流那头一条条拿 */
function makeStream() {
  const queue = []
  let waiting = null
  return {
    push(event, data) {
      const chunk = enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
      if (waiting) { const w = waiting; waiting = null; w({ done: false, value: chunk }) }
      else queue.push({ done: false, value: chunk })
    },
    reader: {
      read() {
        if (queue.length) return Promise.resolve(queue.shift())
        return new Promise((resolve) => { waiting = resolve })
      }
    }
  }
}

function jsonReply(body, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body) }
}

/**
 * 与真后端同款语义：事件是**按会话**推的，推给没人听的那条会话就静静地没了
 * （真后端把它存进补发缓冲，等一个永远不会来的重连）。这一条是本用例的要害——
 * 不照着做的话，会话 ID 错配在测试里反而「能跑通」。
 */
function makeBackend({ issueGate }) {
  const state = {
    issued: [],          // 签发过的会话 ID，按顺序
    connected: [],       // GET /api/agent/connect/{cid} 的 cid，按顺序
    chats: [],           // POST /api/agent/chat 的请求体
    results: [],         // POST /api/agent/office/result 的回执
    streams: new Map(),  // cid -> stream
    pending: new Map()   // requestId -> resolve
  }
  let seq = 0

  const pushTo = (cid, event, data) => {
    const s = state.streams.get(cid)
    if (s) s.push(event, data)
  }

  /** 收到 chat 后照真机那一轮的形态走：两条 office_command，各等回执，然后收尾 */
  async function driveTurn(cid) {
    pushTo(cid, 'text_delta', { content: '<final>Adding the total row and bolding the header.' })
    const step = (requestId, command, args) => {
      const done = new Promise((resolve) => { state.pending.set(requestId, resolve) })
      pushTo(cid, 'client_action', { tool: 'office_command', requestId, command, args })
      // 真后端等 30 秒超时；用例等回执，等不到就让 await 挂住，由断言暴露
      return done
    }
    await step('req-1', 'excel_set_values', {
      sheetName: 'costs', rangeAddress: 'A5', values: [['Total', '=SUM(B2:B4)']]
    })
    await step('req-2', 'excel_format_cells', { sheetName: 'costs', rangeAddress: 'A1:B1', bold: true })
    pushTo(cid, 'text_delta', { content: ' Done.</final>' })
    pushTo(cid, 'bubble_end', { status: 'finished' })
  }

  return {
    state,
    async fetch(url, options = {}) {
      if (url.includes('/api/ai/history')) return jsonReply([])
      if (url.includes('/api/ai/models')) return jsonReply({ models: [], defaultModel: '' })
      if (url.includes('/api/ai/skills')) return jsonReply([])
      if (url.includes('/api/projects/addin-links')) return jsonReply({ code: 0, data: [] })

      if (url.endsWith('/api/agent/conversations')) {
        await issueGate.wait()
        const n = ++seq
        // 第二条之后的签发故意慢一拍，把真机那个**致命的**先后顺序钉成确定的：
        // 先签发的那条建完 SSE（通道焊死在它身上），后签发的那条才回来覆盖
        // conversationId 并被 POST /chat 带走。顺序反过来反而是良性的——
        // 不钉住的话，这个用例会在病灶还在的时候侥幸变绿。
        if (n > 1) await new Promise((r) => setTimeout(r, 25))
        // 真后端的 ID 带毫秒时间戳；这里用自增序号，两次并发签发照样拿到两条不同的 ID
        const id = `conv-1789960022${588 + n * 2}-stub`
        state.issued.push(id)
        return jsonReply({ conversationId: id })
      }

      if (url.includes('/api/agent/connect/')) {
        const cid = url.split('/api/agent/connect/')[1]
        state.connected.push(cid)
        const s = makeStream()
        state.streams.set(cid, s)
        return { ok: true, status: 200, body: { getReader: () => s.reader } }
      }

      if (url.endsWith('/api/agent/chat')) {
        const body = JSON.parse(options.body)
        state.chats.push(body)
        // 后端是异步 200，正文经 SSE 推；这里也一样，不阻塞 POST 的返回
        setTimeout(() => { driveTurn(body.conversationId) }, 0)
        return jsonReply({ code: 0 })
      }

      if (url.endsWith('/api/agent/office/result')) {
        const body = JSON.parse(options.body)
        state.results.push(body)
        const resolve = state.pending.get(body.requestId)
        if (resolve) { state.pending.delete(body.requestId); resolve() }
        return jsonReply({ code: 0 })
      }

      return jsonReply({ code: 0 })
    }
  }
}

/** 可以闸住的签发端点：release() 之前所有 POST /conversations 都挂着 */
function makeGate() {
  let release
  const opened = new Promise((r) => { release = r })
  let open = false
  return {
    wait: () => (open ? Promise.resolve() : opened),
    release() { open = true; release() }
  }
}

const SETTINGS = { serverUrl: 'https://addin.workdeck.ai', token: 'awdt_t' }

async function tick(n = 6) {
  for (let i = 0; i < n; i++) await new Promise((r) => setTimeout(r, 0))
}

const { activateSession, send, messages, stop } = await import('./chatSession.js')
const { t } = await import('./i18n.js')

test('预连与发送并发时：窗格只签发一条会话，SSE 与 chat 走同一条，两条 Excel 命令落到工作簿并收尾', async () => {
  store.clear()
  const wb = makeWorkbook()
  installExcel(wb)
  const gate = makeGate()
  const backend = makeBackend({ issueGate: gate })
  const original = globalThis.fetch
  globalThis.fetch = (url, options) => backend.fetch(String(url), options)

  try {
    // 1) 进面板：预连开始签发，卡在闸上（真机里这就是那个往返）
    const activating = activateSession({ settings: SETTINGS, projectId: '1' })
    await tick()
    assert.equal(backend.state.issued.length, 0, '闸没开之前不该签发出会话')

    // 2) 用户在预连还没跑完时就把消息发了出去——真机 Excel 那一次只隔了 1.6 秒
    const sending = send('Please add a total row under the table and make the header row bold.')
    await tick()

    // 3) 放闸，两个流程各自往下跑
    gate.release()
    await activating
    await sending
    await tick(40)

    // ---- 不变式一：一个窗格同一时刻只有一条会话 ----
    assert.equal(backend.state.issued.length, 1,
      `一个窗格只该签发一条会话，实际签发了 ${backend.state.issued.length} 条：${backend.state.issued.join(' / ')}`)

    // ---- 不变式二：SSE 听的与 chat 发的必须是同一条 ----
    assert.equal(backend.state.chats.length, 1, '应当只发出一条 chat 请求')
    const chatConv = backend.state.chats[0].conversationId
    assert.ok(backend.state.connected.length > 0, 'SSE 应当已建连')
    for (const cid of backend.state.connected) {
      assert.equal(cid, chatConv,
        `SSE 连的是 ${cid}、chat 发的是 ${chatConv}——事件会推给一条没人听的会话`)
    }
    assert.equal(backend.state.chats[0].officeHost, 'excel', 'officeHost 应当是 excel')

    // ---- 不变式三：两条命令都到了执行器，工作簿真的被改了 ----
    assert.equal(wb.cells.get('A5'), 'Total', `A5 应当写进 Total，实际 ${JSON.stringify(wb.cells.get('A5'))}`)
    assert.equal(wb.cells.get('B5'), '=SUM(B2:B4)', `B5 应当写进合计公式，实际 ${JSON.stringify(wb.cells.get('B5'))}`)
    assert.ok(wb.bold.has('A1') && wb.bold.has('B1'), '表头 A1:B1 应当被加粗')

    // ---- 不变式四：两条回执都回传了，且都是成功 ----
    assert.equal(backend.state.results.length, 2,
      `两条命令都该回执，实际 ${backend.state.results.length} 条`)
    for (const r of backend.state.results) {
      assert.equal(r.ok, true, `回执应当成功：${JSON.stringify(r.error)}`)
    }
    assert.deepEqual(backend.state.results.map((r) => r.requestId), ['req-1', 'req-2'])

    // ---- 不变式五：轮次被确认，不是那句「结束状态没能确认」 ----
    const assistant = messages.value.filter((m) => m.role === 'assistant').pop()
    assert.ok(assistant, '应当有助手气泡')
    assert.notEqual(assistant.notice, t('runEndedUnknown'),
      '轮次应当正常收尾，而不是落到「结束状态没能确认」')
    assert.equal(assistant.done, true, '轮次应当标为已完成')
    assert.match(assistant.text, /Adding the total row/)

    // 工具 chip 两条都在（真机那次一个都没出现）
    assert.equal((assistant.tools || []).length, 2, '两条命令各该有一个工具 chip')
    assert.deepEqual(assistant.tools.map((c) => c.status), ['done', 'done'])
  } finally {
    // 关掉 SSE（连同它的心跳看门狗定时器），否则 node 不会退出。
    // 放在恢复 fetch 之前：stop() 会发一次 POST /cancel，要走桩
    await stop()
    globalThis.fetch = original
  }
})

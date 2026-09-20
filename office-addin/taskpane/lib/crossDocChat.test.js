// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 目标窗格收到别的窗格发来的命令（SSE client_action 带 origin，dev-board#717）：
 *   node --test office-addin/taskpane/lib/crossDocChat.test.js
 *
 * 钉住 chatSession 这一侧的接线：
 *   - 这是别的会话的动作，**不许**往本窗格当前会话的气泡里挂工具 chip；
 *   - 写入成功 → 修订记录新增一条（带来源与改前值）+ 横幅计数（同一来源合并）；
 *   - 只读命令不留记录、不弹横幅；
 *   - 无论成败都照常回传 /api/agent/office/result（发起方在等这个结果）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}

/* ---- 最小 Excel 宿主：一张表、按格存值 ---- */
const grid = new Map([['B2', 100], ['C2', '=B2*0.1']])
function colName(i) { return String.fromCharCode(65 + i) }
function makeRange(r0, c0, r1, c1) {
  const key = (r, c) => `${colName(c)}${r + 1}`
  const read = () => {
    const out = []
    for (let r = r0; r <= r1; r++) {
      const row = []
      for (let c = c0; c <= c1; c++) row.push(grid.has(key(r, c)) ? grid.get(key(r, c)) : '')
      out.push(row)
    }
    return out
  }
  const write = (v) => { for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) grid.set(key(r, c), v[r - r0][c - c0]) }
  return {
    rowIndex: r0,
    columnIndex: c0,
    get rowCount() { return r1 - r0 + 1 },
    get columnCount() { return c1 - c0 + 1 },
    get address() { return `报价!${key(r0, c0)}:${key(r1, c1)}` },
    load() {},
    getResizedRange(dr, dc) { return makeRange(r0, c0, r1 + dr, c1 + dc) },
    get values() { return read() },
    set values(v) { write(v) },
    get formulas() { return read() },
    set formulas(v) { write(v) }
  }
}
function parseCell(a) {
  const m = /^([A-Z])(\d+)$/.exec(a)
  return { r: Number(m[2]) - 1, c: m[1].charCodeAt(0) - 65 }
}
const sheet = {
  name: '报价',
  load() {},
  getRange(addr) {
    const [a, b] = addr.split(':')
    const p = parseCell(a)
    const q = b ? parseCell(b) : p
    return makeRange(p.r, p.c, q.r, q.c)
  },
  getUsedRangeOrNullObject() { return makeRange(1, 1, 1, 2) }
}
globalThis.Office = {
  HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
  context: { host: 'Excel', document: { url: 'C:/x/报价.xlsx' }, requirements: { isSetSupported: () => true } }
}
globalThis.Excel = {
  run: async (cb) => cb({
    workbook: { worksheets: { getItem: () => sheet, getActiveWorksheet: () => sheet } },
    sync: async () => {}
  })
}

const { activateSession, messages, crossDocBanner } = await import('./chatSession.js')
const revisionLog = await import('./revisionLog.js')

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

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

async function until(cond, ms = 3000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (cond()) return true
    await new Promise((r) => setTimeout(r, 10))
  }
  return cond()
}

test('带 origin 的写入：记修订记录、弹横幅、照常回传，不挂本会话的工具 chip', async () => {
  revisionLog.bindDocument('crossDocChatDoc', globalThis.localStorage)
  const posted = []
  const origin = { paneId: 'pane-A', docName: '合同A.docx', conversationId: 'conv-A' }
  const events = [
    sseEvent('client_action', {
      tool: 'office_command', requestId: 'r1', command: 'excel_set_values',
      args: { rangeAddress: 'B2:C2', values: [[200, 20]] }, conversationId: 'conv-B', origin
    }),
    sseEvent('client_action', {
      tool: 'office_command', requestId: 'r2', command: 'excel_get_range',
      args: { rangeAddress: 'B2:C2' }, conversationId: 'conv-B', origin
    }),
    sseEvent('client_action', {
      tool: 'office_command', requestId: 'r3', command: 'excel_set_values',
      args: { rangeAddress: 'B2', values: [[300]] }, conversationId: 'conv-B', origin
    })
  ]
  const original = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    const u = String(url)
    if (u.includes('/api/ai/history')) return jsonReply([])
    if (u.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-B' })
    if (u.includes('/api/agent/connect/')) return sseScriptedResponse(events)
    if (u.endsWith('/api/agent/office/result')) {
      posted.push(JSON.parse(options.body))
      return jsonReply({ code: 0 })
    }
    return jsonReply({}, false, 404)
  }
  try {
    await activateSession({ settings: { serverUrl: 'https://x.example', token: 'awdt_b' }, projectId: '9' })
    assert.ok(await until(() => posted.length === 3), `三条都要回传，实际 ${posted.length}`)

    const byId = Object.fromEntries(posted.map((p) => [p.requestId, p]))
    assert.equal(byId.r1.ok, true)
    assert.equal(byId.r2.ok, true)
    assert.equal(byId.r3.ok, true)
    assert.equal(grid.get('B2'), 300)

    // 只有两条写入进了修订记录，新在前，带来源与改前值
    assert.equal(revisionLog.entries.length, 2)
    const [latest, first] = revisionLog.entries
    assert.equal(first.originDocName, '合同A.docx')
    assert.equal(first.originConversationId, 'conv-A')
    assert.equal(first.undoable, true)
    assert.deepEqual(first.before.formulas, [[100, '=B2*0.1']])
    assert.deepEqual(latest.before.formulas, [[200]])
    assert.equal(revisionLog.unread.value, 2)

    // 横幅：同一来源合并计数
    assert.equal(crossDocBanner.value.originDocName, '合同A.docx')
    assert.equal(crossDocBanner.value.count, 2)

    // 本窗格会话里没有凭空多出助手气泡或工具 chip
    const chips = messages.value.flatMap((m) => m.tools || [])
    assert.equal(chips.length, 0)
    assert.equal(messages.value.length, 0)
  } finally {
    await activateSession({ settings: { serverUrl: '', token: '' }, projectId: '' })
    globalThis.fetch = original
  }
})

/**
 * 跨文档写入之后，文档镜像要在**本窗格**就地跑一次（dev-board#299 + #717）。
 *
 * turnHadWrite 平时由 finishStreaming 消费，而收到跨文档命令的窗格并没有在跑自己的轮次：
 * 只置位不汇合的话，镜像要等本窗格的用户下次自己发消息才跑——用户从此不再用这个窗格
 * （很常见：被改的那份文档只是被参考/被改，人不在那儿聊天），镜像就永远不跑，
 * 桌面端项目里那份副本停在改动之前，界面上还没有任何迹象说明它是旧的。
 */
test('带 origin 的写入：本窗格没有在跑自己的轮次，也要就地触发一次文档镜像', async () => {
  revisionLog.bindDocument('crossDocChatDoc3', globalThis.localStorage)
  crossDocBanner.value = null
  store.set('awd_addin_archive_links', JSON.stringify({ 12: { deviceId: 'dev-1', projectKey: '77' } }))

  const zip = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4])
  const savedOffice = globalThis.Office
  globalThis.Office = {
    ...savedOffice,
    FileType: { Compressed: 'compressed' },
    AsyncResultStatus: { Succeeded: 'succeeded' },
    context: {
      ...savedOffice.context,
      document: {
        ...savedOffice.context.document,
        getFileAsync: (type, options, cb) => cb({
          status: 'succeeded',
          value: {
            size: zip.length,
            sliceCount: 1,
            getSliceAsync: (i, done) => done({ status: 'succeeded', value: { data: zip } }),
            closeAsync: (done) => { if (done) done() }
          }
        })
      }
    }
  }

  const uploads = []
  const posted = []
  const events = [
    sseEvent('client_action', {
      tool: 'office_command', requestId: 'm1', command: 'excel_set_values',
      args: { rangeAddress: 'B2', values: [[999]] }, conversationId: 'conv-M',
      origin: { paneId: 'pane-A', docName: '合同A.docx', conversationId: 'conv-A' }
    })
  ]
  const original = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    const u = String(url)
    if (u.includes('/api/ai/history')) return jsonReply([])
    if (u.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-M' })
    if (u.includes('/api/agent/connect/')) return sseScriptedResponse(events)
    if (u.endsWith('/api/agent/office/result')) { posted.push(JSON.parse(options.body)); return jsonReply({ code: 0 }) }
    if (u.endsWith('/api/mobile/media')) { uploads.push(options); return jsonReply({ code: 0 }) }
    return jsonReply({}, false, 404)
  }
  try {
    await activateSession({ settings: { serverUrl: 'https://x.example', token: 'awdt_m' }, projectId: '12' })
    assert.ok(await until(() => posted.length === 1), '结果要回传给发起方')
    assert.ok(await until(() => uploads.length === 1),
      `跨文档写入之后应当就地上传一次镜像，实际 ${uploads.length} 次`)
  } finally {
    await activateSession({ settings: { serverUrl: '', token: '' }, projectId: '' })
    globalThis.fetch = original
    globalThis.Office = savedOffice
    store.delete('awd_addin_archive_links')
  }
})

test('带 origin 的写入执行失败：不记修订、不弹横幅，但失败照样回传给发起方', async () => {
  revisionLog.bindDocument('crossDocChatDoc2', globalThis.localStorage)
  crossDocBanner.value = null
  const posted = []
  const events = [
    sseEvent('client_action', {
      tool: 'office_command', requestId: 'f1', command: 'excel_set_values',
      args: { rangeAddress: 'B2:C2', values: [[1, 2, 3]] }, conversationId: 'conv-C',
      origin: { paneId: 'pane-A', docName: '合同A.docx', conversationId: 'conv-A' }
    })
  ]
  const original = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    const u = String(url)
    if (u.includes('/api/ai/history')) return jsonReply([])
    if (u.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-C' })
    if (u.includes('/api/agent/connect/')) return sseScriptedResponse(events)
    if (u.endsWith('/api/agent/office/result')) { posted.push(JSON.parse(options.body)); return jsonReply({ code: 0 }) }
    return jsonReply({}, false, 404)
  }
  try {
    await activateSession({ settings: { serverUrl: 'https://x.example', token: 'awdt_c' }, projectId: '10' })
    assert.ok(await until(() => posted.length === 1))
    assert.equal(posted[0].ok, false)
    assert.ok(posted[0].error)
    assert.equal(revisionLog.entries.length, 0)
    assert.equal(crossDocBanner.value, null)
  } finally {
    await activateSession({ settings: { serverUrl: '', token: '' }, projectId: '' })
    globalThis.fetch = original
  }
})

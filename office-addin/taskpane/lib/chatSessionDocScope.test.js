// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「对话按文档绑定」回归用例（dev-board#767）：
 *   node --test office-addin/taskpane/lib/chatSessionDocScope.test.js
 *
 * 维护者报的病：在 Windows Word 里新建一份空白 Document1，一开窗格就展示上一篇新闻摘要的
 * 对话——那条会话属于另一份文档。规矩由此定死三条：
 *   1. 这份文档之前开过插件并用过 → 恢复**这份文档**最新的那条对话；
 *   2. 这份文档没开过插件（含新建、还没存过盘的）→ 新对话；
 *   3. 旧对话一条没丢，仍在历史面板里可以手动翻回去（本文件不验界面，只验不被自动认领）。
 *
 * 还原病灶的办法（任一条都会让下面某条转红）：
 *   - 让 settings.loadConversationId 重新去继承「项目+宿主」或「只按项目」的旧键；
 *   - 让 chatSession.docPersist 对未保存的文档也返回 true（新建空白文档又会捡回上一条）；
 *   - 把 docScope 从 activateSession 的会话身份键（sessionIdentityKey）里拿掉（切文档不切会话）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage 内存桩：settings.js 的持久化走它 ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}

/**
 * 宿主桩：Word 面，文档路径可改（另存为/切文档都表现为 url 变了）。
 * Word.run 给一个最小可用的桩，免得读正文那一步刷 console.warn。
 */
const doc = { url: 'file:///C:/cases/主合同.docx' }
globalThis.Office = { context: { document: doc } }
globalThis.Word = {
  run: async (cb) => cb({ document: { body: { load() {}, text: '' } }, sync: async () => {} })
}

const { activateSession, syncActiveDocument, messages, stop } = await import('./chatSession.js')

const KEY = (projectId, docKey) => `awd_addin_conv_word_${projectId}_${docKey}`
const SETTINGS = { serverUrl: 'https://cloud.example', token: 'awdt_t' }

function sseOkResponse() {
  return { ok: true, status: 200, body: { getReader: () => ({ read: () => new Promise(() => {}) }) } }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body, text: async () => JSON.stringify(body) }
}

/** 服务端每次签发一个新 ID；记录历史请求，便于断言「恢复了哪条会话」 */
function stubBackend() {
  const original = globalThis.fetch
  const calls = []
  let issued = 0
  globalThis.fetch = async (url, options = {}) => {
    const u = String(url)
    calls.push(u)
    if (u.includes('/api/ai/history')) return jsonReply([])
    if (u.endsWith('/api/agent/conversations')) { issued++; return jsonReply({ conversationId: 'conv-新签发-' + issued }) }
    if (u.includes('/api/agent/connect/')) return sseOkResponse()
    if (u.includes('/api/agent/cancel/')) return jsonReply({})
    if (u.includes('/api/ai/models') || u.includes('/api/ai/skills')) return jsonReply([])
    return jsonReply({})
  }
  return {
    calls,
    connectedIds: () => calls.filter((u) => u.includes('/api/agent/connect/'))
      .map((u) => u.split('/api/agent/connect/')[1]),
    restore: () => { globalThis.fetch = original }
  }
}

/** 每个用例从干净的会话身份起步：换个项目号即可（sessionKey 随之不同） */
let nextProject = 100
function freshProject() {
  return String(nextProject++)
}

test('这份文档开过插件：恢复它上次的那条对话', async () => {
  const pid = freshProject()
  doc.url = 'file:///C:/cases/主合同.docx'
  store.set(KEY(pid, doc.url), 'conv-主合同')
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pid })
    assert.deepEqual(f.connectedIds(), ['conv-主合同'], '该恢复这份文档自己的会话')
    assert.equal(store.get(KEY(pid, doc.url)), 'conv-主合同')
  } finally {
    await stop()
    f.restore()
  }
})

test('同项目里的另一份文档不捡这条：各是各的会话', async () => {
  const pid = freshProject()
  doc.url = 'file:///C:/cases/主合同.docx'
  store.set(KEY(pid, doc.url), 'conv-主合同')
  doc.url = 'file:///C:/cases/补充协议.docx'
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pid })
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1'], '补充协议该是新对话')
    assert.equal(store.get(KEY(pid, 'file:///C:/cases/主合同.docx')), 'conv-主合同', '不该动别人的键')
  } finally {
    await stop()
    f.restore()
  }
})

/**
 * 维护者报的那一幕：新建空白 Document1（没有 document.url），localStorage 里躺着上一篇
 * 新闻摘要留下的旧键。旧键不认领 + 未保存不落盘，两条合起来才是「每次都是新对话」。
 */
test('新建未保存的文档：新对话，且一个会话键都不落盘', async () => {
  const pid = freshProject()
  doc.url = ''
  // 历史版本留下的两级旧键
  store.set(`awd_addin_conv_word_${pid}`, 'conv-上一篇新闻摘要')
  store.set(`awd_addin_conv_${pid}`, 'conv-更老的会话')
  const before = [...store.keys()].filter((k) => k.startsWith('awd_addin_conv_')).sort()
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pid })
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1'], '空白新文档必须是新对话')
    assert.equal(messages.value.length, 0, '不该把别的文档的消息回灌进来')
    const after = [...store.keys()].filter((k) => k.startsWith('awd_addin_conv_')).sort()
    assert.deepEqual(after, before, '未保存的文档不落任何会话键，旧键也原样留着')
  } finally {
    await stop()
    f.restore()
  }
})

test('未保存的文档重开窗格还是新对话（上一次的不会被捡回来）', async () => {
  doc.url = ''
  const f = stubBackend()
  try {
    const p1 = freshProject()
    await activateSession({ settings: SETTINGS, projectId: p1 })
    await stop()
    // 同一个窗格实例里换个项目号再回来，等价于「再开一次」：键若落过盘，这里就会读到
    const p2 = freshProject()
    await activateSession({ settings: SETTINGS, projectId: p2 })
    await stop()
    await activateSession({ settings: SETTINGS, projectId: p1 })
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1', 'conv-新签发-2', 'conv-新签发-3'])
  } finally {
    await stop()
    f.restore()
  }
})

/**
 * 存过盘、但这份文档从没开过插件：同样不该认领历史版本留下的旧键。
 * 未保存那条路有「不落盘」兜着，看不见旧键继承；这一条才是 settings.loadConversationId
 * 那道闸在会话链路上的落点。
 */
test('存过盘但没开过插件的文档：新对话，不认领历史版本的旧键', async () => {
  const pid = freshProject()
  doc.url = 'file:///C:/cases/新拿到的合同.docx'
  store.set(`awd_addin_conv_word_${pid}`, 'conv-上一篇新闻摘要')
  store.set(`awd_addin_conv_${pid}`, 'conv-更老的会话')
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pid })
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1'], '没开过插件的文档必须是新对话')
    assert.equal(store.get(`awd_addin_conv_word_${pid}`), 'conv-上一篇新闻摘要', '旧键原样留着')
  } finally {
    await stop()
    f.restore()
  }
})

test('切项目：同一份文档在另一个项目里是另一条会话', async () => {
  const pidA = freshProject()
  const pidB = freshProject()
  doc.url = 'file:///C:/cases/主合同.docx'
  store.set(KEY(pidA, doc.url), 'conv-项目A')
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pidB })
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1'])
    await activateSession({ settings: SETTINGS, projectId: pidA })
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1', 'conv-项目A'])
  } finally {
    await stop()
    f.restore()
  }
})

/* ==================== 同一个窗格里换文档 ==================== */

test('窗格里换了文档：syncActiveDocument 切到那份文档的会话', async () => {
  const pid = freshProject()
  doc.url = 'file:///C:/cases/主合同.docx'
  store.set(KEY(pid, doc.url), 'conv-主合同')
  store.set(KEY(pid, 'file:///C:/cases/补充协议.docx'), 'conv-补充协议')
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pid })
    assert.deepEqual(f.connectedIds(), ['conv-主合同'])

    doc.url = 'file:///C:/cases/补充协议.docx'
    assert.equal(await syncActiveDocument(), true, '文档变了该报 true，调用方据此重绑修订记录')
    assert.deepEqual(f.connectedIds(), ['conv-主合同', 'conv-补充协议'])

    // 文档没再变：空操作，不拆连接也不重建会话
    assert.equal(await syncActiveDocument(), false)
    assert.deepEqual(f.connectedIds(), ['conv-主合同', 'conv-补充协议'])
  } finally {
    await stop()
    f.restore()
  }
})

test('另存为新名：当新文档处理（新对话），原文档的会话键原样留着', async () => {
  const pid = freshProject()
  doc.url = 'file:///C:/cases/主合同.docx'
  store.set(KEY(pid, doc.url), 'conv-主合同')
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pid })
    doc.url = 'file:///C:/cases/主合同-修订版.docx'
    assert.equal(await syncActiveDocument(), true)
    assert.deepEqual(f.connectedIds(), ['conv-主合同', 'conv-新签发-1'])
    assert.equal(store.get(KEY(pid, 'file:///C:/cases/主合同.docx')), 'conv-主合同')
  } finally {
    await stop()
    f.restore()
  }
})

/**
 * 新建的文档第一次存盘是「同一份文档有了名字」，不是换了文档：会话原地跟着换键，
 * 不清屏重拉。做成「当新文档处理」的话，用户按一次 Ctrl+S 正在进行的对话就没了。
 */
test('未保存的文档首次存盘：会话原地跟到新键上，不重建', async () => {
  const pid = freshProject()
  doc.url = ''
  const f = stubBackend()
  try {
    await activateSession({ settings: SETTINGS, projectId: pid })
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1'])

    doc.url = 'file:///C:/cases/刚起草的备忘录.docx'
    assert.equal(await syncActiveDocument(), true)
    // 没有第二次建连 = 没有重建会话
    assert.deepEqual(f.connectedIds(), ['conv-新签发-1'])
    // 从此落盘：重开窗格能恢复
    assert.equal(store.get(KEY(pid, doc.url)), 'conv-新签发-1')
  } finally {
    await stop()
    f.restore()
  }
})

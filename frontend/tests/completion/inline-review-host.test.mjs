// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { createInlineReviewHost } from '../../src/composables/inlineReviewHost.js'

function fixture(t, opts = {}) {
  let revision = 1, timerId = 0
  const tasks = new Map(), calls = [], messages = [], stored = new Map()
  // execCalls：发给 worker 的命令；calls：发给 /insight/review 的 HTTP 请求。
  // 关闭态与后台态要求两者都是 0，所以分开记。
  const execCalls = [], delays = [], states = []
  const text = { body: '第一条 价款：【待填写】。' }
  const dependencies = {
    execute: async (action) => action === 'set_revision_view' ? { mode: 'margin' }
      : action === 'get_review_context' ? { success: true, revision }
        : { success: true, revision, paragraphs: [{ index: 0, text: text.body }] },
    review: async (pid, body) => { calls.push({ pid, body }); return { findings: [{ kind: 'BLANK', paragraphIndex: 0, quote: '【待填写】', title: '尚有待填写内容' }] } },
  }
  const clock = { at: 1_000_000 }
  const host = createInlineReviewHost({ projectId: 7, fileId: 8, userId: Math.random(),
    execute: (...args) => { execCalls.push(args[0]); return dependencies.execute(...args) },
    review: (...args) => dependencies.review(...args),
    send: (m) => messages.push(m), storage: { get: (k) => stored.get(k), set: (k, v) => stored.set(k, v) },
    openInsight: () => calls.push({ open: true }),
    openPanel: () => calls.push({ panel: true }),
    onState: (s) => states.push(s),
    now: () => clock.at,
    timers: { set: (fn, ms) => { delays.push(ms); tasks.set(++timerId, fn); return timerId }, clear: (id) => tasks.delete(id) }, ...opts,
  })
  t.after(() => host.destroy())
  const tick = async () => { const entries = [...tasks]; tasks.clear(); await Promise.all(entries.map(([, fn]) => fn())) }
  const request = (action, data, session = host.session) => host.handle({ type: 'inline-review-request', session, action, data })
  return { host, dependencies, calls, execCalls, delays, states, messages, tasks, stored, tick, request, text, clock,
    paragraph: text.body, edit() { revision++; host.modified() },
    retype(body) { text.body = body; revision++; host.modified() } }
}

test('opening and rapid typing coalesce into rules-only checks of the live document', async (t) => {
  const f = fixture(t); f.host.start()
  for (let i = 0; i < 20; i++) f.edit()
  assert.equal(f.tasks.size, 1)
  await f.tick()
  assert.equal(f.calls.length, 1)
  assert.equal(f.calls[0].body.deep, false)
  assert.equal(f.calls[0].body.docFileId, 8)
  const state = f.messages.at(-1)
  assert.equal(state.status, 'ready')
  assert.equal(state.revision, 21)
  assert.equal(state.findings[0].expectedParagraph, f.paragraph)
  assert.equal(state.findings[0].start, f.paragraph.indexOf('【待填写】'))
})

test('repeated explicit clicks cannot duplicate the paid request', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  let finish
  f.dependencies.review = async (pid, body) => { f.calls.push({ pid, body }); return new Promise((r) => { finish = r }) }
  const pending = f.request('deep')
  await new Promise(setImmediate)
  await f.request('deep')
  assert.equal(f.calls.filter((c) => c.body?.deep).length, 1)
  finish({ findings: [] }); await pending
  assert.equal(f.messages.at(-1).deepStatus, 'ready')
})

test('typing invalidates a slow AI result and schedules only a local recheck', async (t) => {
  const f = fixture(t); let finish
  f.dependencies.review = async (pid, body) => { f.calls.push({ pid, body }); return new Promise((r) => { finish = r }) }
  const pending = f.request('deep'); await new Promise(setImmediate)
  f.edit(); finish({ findings: [{ paragraphIndex: 0, quote: '【待填写】' }] }); await pending
  assert.equal(f.messages.at(-1).findings.length, 0)
  f.dependencies.review = async (pid, body) => { f.calls.push({ pid, body }); return { findings: [] } }
  await f.tick()
  assert.deepEqual(f.calls.map((c) => c.body.deep), [true, false])
})

test('worker revision fence rejects a result before a delayed modified relay arrives', async (t) => {
  const f = fixture(t)
  f.dependencies.execute = async (action) => action === 'set_revision_view' ? { mode: 'margin' }
    : action === 'get_review_context' ? { success: true, revision: 2 }
      : { success: true, revision: 1, paragraphs: [{ index: 0, text: f.paragraph }] }
  f.host.start(); await f.tick()
  assert.equal(f.messages.at(-1).status, 'stale')
  assert.equal(f.messages.at(-1).findings.length, 0)
})

test('pagination reaches later paragraphs but never mixes document revisions', async (t) => {
  const f = fixture(t); const reads = []
  f.dependencies.execute = async (action, p) => {
    if (action === 'set_revision_view') return { mode: 'margin' }
    if (action === 'get_review_context') return { success: true, revision: 1 }
    reads.push(p.startParagraph)
    return { success: true, revision: p.startParagraph ? 2 : 1,
      paragraphs: [{ index: p.startParagraph, text: '测试正文' }], truncated: !p.startParagraph, nextStartParagraph: 500 }
  }
  f.host.start(); await f.tick()
  assert.deepEqual(reads, [0, 500]); assert.equal(f.calls.length, 0)
  assert.equal(f.messages.at(-1).status, 'stale')
})

test('read-only members can use local checks but cannot call a model', async (t) => {
  const f = fixture(t, { writable: false }); f.host.start(); await f.tick(); await f.request('deep')
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].body.deep, false)
})

test('opening evidence is read-only and stale guest sessions have no effects', async (t) => {
  const f = fixture(t)
  assert.equal(await f.request('deep', {}, 'old-document'), false)
  await f.request('open-insight')
  assert.deepEqual(f.calls, [{ open: true }])
})

test('关掉 AI 只停模型那一层，不花钱的规则检查照常跑', async (t) => {
  const f = fixture(t); f.host.start(); await f.request('preferences', { ai: false })
  await f.tick()
  assert.equal(f.calls.length, 1, '规则检查不受 AI 开关影响')
  assert.equal(f.calls[0].body.deep, false)
  assert.equal(f.messages.at(-1).status, 'ready')
  assert.equal(f.messages.at(-1).ai, false)
  assert.equal(f.tasks.size, 0, '关着 AI 就不许排自动 AI 审校')
  f.retype('第一条 价款：人民币一百万元。'); await f.tick()
  assert.equal(f.calls.length, 2)
  assert.equal(f.calls.every((c) => c.body.deep === false), true, '关着的时候一次模型调用都不许发')
})

test('inline revision display reads the guarded final text snapshot', async (t) => {
  const f = fixture(t), calls = [], original = f.dependencies.execute
  f.dependencies.execute = async (action, params) => { calls.push({action, params}); return original(action, params) }
  f.host.start(); await f.tick()
  assert.equal(f.calls.length, 1)
  assert.equal(calls.find(c => c.action === 'get_document_text').params.__agent, true)
  assert.equal(f.messages.at(-1).status, 'ready')
})

test('destroy prevents late responses, timers and all future actions', async (t) => {
  const f = fixture(t); let finish
  f.dependencies.review = () => new Promise((r) => { finish = r })
  const pending = f.request('deep'); await new Promise(setImmediate)
  f.host.destroy(); const count = f.messages.length; finish({ findings: [] }); await pending
  assert.equal(f.messages.length, count); assert.equal(f.tasks.size, 0)
  assert.equal(f.messages.at(-1).status, 'disabled')
  assert.equal(await f.request('deep'), false)
})

test('unlocatable model quotes are excluded; repeated quotes cannot acquire an inferred edit', async (t) => {
  const f = fixture(t)
  f.dependencies.execute = async (action) => action === 'set_revision_view' ? { mode: 'margin' }
    : action === 'get_review_context' ? { revision: 1 }
      : { success: true, revision: 1, paragraphs: [{ index: 0, text: '甲乙甲乙' }] }
  f.dependencies.review = async () => ({ findings: [
    { paragraphIndex: 0, quote: '并不存在', replacement: '编造的结论' },
    { paragraphIndex: 0, quote: '甲乙', replacement: '丙丁' },
  ] })
  await f.request('deep')
  assert.equal(f.messages.at(-1).findings.length, 1)
  assert.equal(f.messages.at(-1).findings[0].replacement, undefined)
})

test('a late local failure cannot erase completed deep review of the same revision', async (t) => {
  const f = fixture(t); let failLocal
  f.dependencies.review = async (pid, body) => body.deep
    ? { findings: [{ paragraphIndex: 0, quote: '【待填写】' }], summary: { deepComplete: true } }
    : new Promise((resolve, reject) => { failLocal = reject })
  f.host.start(); const local = f.tick(); await new Promise(setImmediate)
  await f.request('deep'); failLocal(new Error('local timeout')); await local
  assert.equal(f.messages.at(-1).status, 'ready')
  assert.equal(f.messages.at(-1).deepStatus, 'ready')
  assert.equal(f.messages.at(-1).findings.length, 1)
})

test('an oversized paragraph does not prevent checking subsequent paragraphs and is disclosed', async (t) => {
  const f = fixture(t)
  f.dependencies.execute = async (action, p) => action === 'set_revision_view' ? { mode: 'margin' }
    : action === 'get_review_context' ? { revision: 1 }
      : p.startParagraph === 0
        ? { success: true, revision: 1, paragraphs: [{ index: 0, text: '甲'.repeat(20001) }], truncated: true, nextStartParagraph: 1 }
        : { success: true, revision: 1, paragraphs: [{ index: 1, text: f.paragraph }] }
  f.host.start(); await f.tick()
  assert.deepEqual(f.calls[0].body.paragraphs, [{ index: 1, text: f.paragraph }])
  assert.equal(f.calls[0].body.truncated, true)
  assert.equal(f.messages.at(-1).truncated, true)
})

test('malformed deep output is explicitly incomplete even when rule results are available', async (t) => {
  const f = fixture(t)
  f.dependencies.review = async () => ({ findings: [], summary: { deepComplete: false } })
  await f.request('deep')
  assert.equal(f.messages.at(-1).deepStatus, 'error')
  assert.equal(f.messages.at(-1).message, 'REVIEW_DEEP_INCOMPLETE')
})

test('an incomplete deep review forwards the server reason code so the guest can name the cause', async (t) => {
  const f = fixture(t)
  f.dependencies.review = async () => ({
    findings: [], summary: { deepComplete: false, deepReason: 'DEEP_TIMEOUT', deepRetried: true } })
  await f.request('deep')
  assert.equal(f.messages.at(-1).message, 'REVIEW_DEEP_INCOMPLETE')
  assert.equal(f.messages.at(-1).deepReason, 'DEEP_TIMEOUT')
  assert.equal(f.messages.at(-1).deepRetried, true)
})

test('a complete deep review clears any earlier reason code', async (t) => {
  const f = fixture(t)
  f.dependencies.review = async () => ({
    findings: [], summary: { deepComplete: false, deepReason: 'DEEP_TIMEOUT' } })
  await f.request('deep')
  assert.equal(f.messages.at(-1).deepReason, 'DEEP_TIMEOUT')
  f.dependencies.review = async () => ({ findings: [], summary: { deepComplete: true } })
  await f.request('deep')
  assert.equal(f.messages.at(-1).deepReason, '')
  assert.equal(f.messages.at(-1).deepRetried, false)
})

test('layout key is stable per user across documents and differs between users', (t) => {
  const a = fixture(t, { userId: 'user-a', fileId: 8 }); a.host.start()
  const b = fixture(t, { userId: 'user-a', fileId: 9 }); b.host.start()
  const c = fixture(t, { userId: 'user-b', fileId: 8 }); c.host.start()
  assert.equal(a.messages.at(-1).layoutKey, b.messages.at(-1).layoutKey)
  assert.notEqual(a.messages.at(-1).layoutKey, c.messages.at(-1).layoutKey)
  assert.equal(a.messages.at(-1).layoutKey.includes('user-a'), false)
})

// ---- dev-board#723/#724：默认开但安静、关掉就彻底安静、只给激活实例跑、哈希去重 ----

test('默认开启但安静：没有存过偏好时仍然开着，防抖是 2.5 秒', async (t) => {
  const f = fixture(t); f.host.start()
  assert.equal(f.messages.at(-1).ai, true)
  assert.equal(f.messages.at(-1).hidden, false)
  assert.deepEqual(f.delays, [2500], '防抖 1.2s → 2.5s（dev-board#724 降资源第三项）')
  await f.tick()
  assert.equal(f.calls.length, 1)
})

test('AI 开关落盘、跨标签同步，关掉之后手动仍可跑一次', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  f.host.setAiEnabled(false)
  assert.equal([...f.stored.values()][0].ai, false, '偏好落盘')
  assert.equal([...f.stored.keys()][0].startsWith('awd_ai_review_'), true, '换了新键：旧键的 enabled 语义对不上')
  assert.equal(f.messages.at(-1).ai, false)
  assert.equal(f.tasks.size, 0)
  await f.host.runDeep()
  assert.equal(f.calls.at(-1).body.deep, true, '关的是自动，手动的「立即 AI 审校」仍然给跑')
  f.host.setAiEnabled(true)
  assert.equal(f.messages.at(-1).ai, true)
})

test('停笔到冷却时间后自动跑一次 AI；同一份正文不会再跑第二次', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  assert.equal(f.calls.length, 1)
  assert.equal(f.delays.at(-1), 20000, '规则跑完排一次自动 AI，冷却 20 秒')
  await f.tick()
  assert.equal(f.calls.length, 2)
  assert.equal(f.calls.at(-1).body.deep, true)
  assert.equal(f.messages.at(-1).deepStatus, 'ready')
  // 只是 revision 前进（改格式、滚页、切标签）不该再花一次钱
  f.edit(); await f.tick()
  assert.equal(f.calls.length, 2, '正文一个字没动：不再自动跑 AI')
  assert.equal(f.tasks.size, 0)
})

test('继续输入把自动 AI 推迟，只留规则检查那一轮', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  f.edit()
  assert.equal(f.tasks.size, 1, '输入撤掉在排的自动 AI，只剩规则那一轮')
  assert.equal(f.delays.at(-1), 2500)
  await f.tick()
  assert.equal(f.calls.every((c) => c.body.deep === false), true)
})

test('两次自动 AI 之间有最小间隔，手动那条不受限制', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick(); await f.tick()
  assert.equal(f.calls.at(-1).body.deep, true)
  f.retype('第一条 价款：【待填写】。第二条 交付：【待填写】。')
  await f.tick()
  assert.equal(f.delays.at(-1), 180000, '刚跑过一次：下一次自动 AI 至少隔 3 分钟')
  await f.host.runDeep()
  assert.equal(f.calls.at(-1).body.deep, true, '手动按钮不受最小间隔限制')
  f.clock.at += 600000
  f.retype('第一条 价款：人民币一百万元。')
  await f.tick()
  assert.equal(f.delays.at(-1), 20000, '隔得够久了就回到 20 秒冷却')
})

test('额度/限流一类的失败之后，本会话不再自动跑 AI', async (t) => {
  const f = fixture(t)
  f.dependencies.review = async (pid, body) => { f.calls.push({ pid, body }); return body.deep
    ? { findings: [], summary: { deepComplete: false, deepReason: 'DEEP_QUOTA' } }
    : { findings: [] } }
  f.host.start(); await f.tick(); await f.tick()
  assert.equal(f.calls.at(-1).body.deep, true)
  assert.equal(f.messages.at(-1).autoBlocked, 'DEEP_QUOTA')
  f.retype('第一条 价款：人民币一百万元。')
  await f.tick()
  assert.equal(f.tasks.size, 0, '不再排自动 AI')
  const before = f.calls.length
  await f.host.runDeep()
  assert.equal(f.calls.length, before + 1, '手动重试仍然可用')
})

test('旧偏好键的「关掉」按 AI 关迁移过来，不替用户重新打开要花钱的那条链路', async (t) => {
  const stored = new Map([['awd_inline_review_legacy', { enabled: false, hidden: true }]])
  const messages = []
  const host = createInlineReviewHost({ projectId: 1, fileId: 2, userId: 'legacy',
    execute: async () => ({ success: true, revision: 1, paragraphs: [] }), review: async () => ({ findings: [] }),
    send: (m) => messages.push(m), storage: { get: (k) => stored.get(k), set: (k, v) => stored.set(k, v) },
    timers: { set: () => 0, clear: () => {} } })
  t.after(() => host.destroy())
  host.start()
  assert.equal(messages.at(-1).ai, false)
  assert.equal(messages.at(-1).hidden, true)
  assert.equal(messages.at(-1).status, 'stale', '规则检查不因旧的关闭偏好停掉')
})

test('浮球显隐（hidden）与 AI 开关（ai）是两件事', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  const httpBefore = f.calls.length
  f.host.setBallHidden(true)
  assert.equal(f.messages.at(-1).hidden, true)
  assert.equal(f.messages.at(-1).ai, true)
  assert.equal(f.messages.at(-1).status, 'ready', '藏浮球不该把这一轮结果作废')
  f.retype('第一条 价款：【待填写】。第二条 交付。')
  await f.tick()
  assert.equal(f.calls.length, httpBefore + 1, '藏了浮球照样继续检查')
  f.host.setBallHidden(false)
  assert.equal(f.messages.at(-1).hidden, false)
})

test('只给当前激活的编辑器实例跑；切回来若正文已变则补一轮', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  const execBefore = f.execCalls.length, httpBefore = f.calls.length
  f.host.setActive(false)
  assert.equal(f.messages.at(-1).active, false)
  f.retype('第一条 价款：人民币一百万元。')
  assert.equal(f.tasks.size, 0, '后台标签不排检查')
  await f.tick()
  assert.equal(f.execCalls.length, execBefore, '后台标签一条 worker 命令都不发')
  assert.equal(f.calls.length, httpBefore)
  f.host.setActive(true)
  assert.equal(f.tasks.size, 1, '切回激活且正文已变：补一轮')
  await f.tick()
  assert.equal(f.calls.length, httpBefore + 1)
})

test('后台实例连显式的深入审校都不跑', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  const before = f.calls.length
  f.host.setActive(false)
  assert.equal(await f.host.runDeep(), false)
  assert.equal(f.calls.length, before)
})

test('全文快照没变就不再发 POST，但结论照常发布', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  assert.equal(f.calls.length, 1)
  f.edit(); await f.tick()
  assert.equal(f.calls.length, 1, '正文一个字没动：不重发 /insight/review')
  assert.equal(f.messages.at(-1).status, 'ready')
  assert.equal(f.messages.at(-1).revision, 2)
  assert.equal(f.messages.at(-1).findings.length, 1, '复用上一轮结论，不是清空')
  f.retype('第一条 价款：【待填写】。第二条。')
  await f.tick()
  assert.equal(f.calls.length, 2, '正文变了就要重发')
})

test('宿主拿得到状态快照，浮球点击走宿主的打开回调', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  assert.equal(f.states.at(-1).status, 'ready')
  assert.equal(f.states.at(-1).findings.length, 1)
  assert.equal(f.states.at(-1).session, f.host.session)
  await f.request('open-panel')
  assert.deepEqual(f.calls.at(-1), { panel: true })
})

test('面板上的重新检查与深入审校走同一条宿主接口', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  f.host.refresh()
  assert.equal(f.messages.at(-1).status, 'stale')
  assert.equal(f.tasks.size, 1)
  await f.tick()
  await f.host.runDeep()
  assert.equal(f.calls.at(-1).body.deep, true)
})

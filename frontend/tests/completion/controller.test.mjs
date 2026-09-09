// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { attachWritingAssistance } from '../../src/composables/zetaOfficeCompletion.js'

const tick = () => new Promise(resolve => setTimeout(resolve, 0))
const debounce = () => new Promise(resolve => setTimeout(resolve, 190))
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const entries = [
  { id: 'one', text: '北京当红晴天律师事务所', kind: 'COMPANY', scope: 'project', source: 'project', entityId: 1 },
  { id: 'two', text: '北京当红齐天集团', kind: 'COMPANY', scope: 'project', source: 'project' },
]
function harness(t, overrides = {}) {
  const dom = new JSDOM('<!doctype html><html><head></head><body><canvas></canvas><input aria-hidden="true"><button id="outside">外部</button></body></html>', { pretendToBeVisual: true })
  const { document: doc } = dom.window, canvas = doc.querySelector('canvas'), input = doc.querySelector('input')
  const calls = [], messages = [], subscribers = new Set()
  let context = { success: true, available: true, hasSelection: false, selectedText: '', before: '北京当红', after: '', paragraph: '北京当红', token: 'cursor-1' }
  const deliver = msg => { for (const listener of subscribers) listener({ __lo: 'lo-relay', ...msg }) }
  const transport = {
    subscribe: fn => { subscribers.add(fn); return () => subscribers.delete(fn) },
    send: msg => {
      messages.push(msg)
      if (msg.action === 'preferences' || (msg.action === 'learn' && !overrides.deferLearning)) deliver({ type: 'writing-response', id: msg.id, session: msg.session, result: {} })
    },
  }
  const execute = async (action, params) => {
    calls.push({ action, params })
    if (overrides[action]) return overrides[action](params)
    if (action === 'get_completion_context') return { ...context }
    return { success: true, token: 'cursor-2' }
  }
  input.focus()
  const api = attachWritingAssistance({ canvas, input, execute, transport, focus: () => input.focus() })
  const config = value => deliver({ type: 'writing-config', config: { session: 'doc-1', enabled: true, learning: false, hints: true, writable: true, items: entries, ...value } })
  config()
  const key = (key, props = {}) => { const event = new dom.window.KeyboardEvent('keydown', { key, cancelable: true, ...props }); return { handled: api.keydown(event), event } }
  const button = text => [...doc.querySelectorAll('.awd-writing-assistance button')].find(b => b.textContent === text)
  const respond = (action, result, error) => {
    const msg = messages.findLast(m => m.action === action)
    assert.ok(msg, action + ' request exists')
    deliver({ type: 'writing-response', id: msg.id, session: msg.session, result, error })
  }
  t.after(() => { api.destroy(); dom.window.close() })
  return { dom, doc, canvas, input, api, calls, messages, config, key, button, respond, deliver, setContext: value => { context = { ...context, ...value } }, panel: () => doc.querySelector('.awd-wa-panel') }
}
async function suggestions(h) { h.api.committed('北京当红'); await debounce(); assert.equal(h.input.getAttribute('aria-expanded'), 'true') }
async function openManagement(h) {
  h.button('写作辅助').click(); h.button('已学词库').click(); await tick()
  h.respond('refresh', {}); await tick()
}

test('跨文档偏好消息保留当前词库，切换文档仍清空旧词库', async t => {
  const h = harness(t)
  h.config({ items: [{ text: '向阳律师事务所', kind: 'COMPANY', source: 'variable', scope: 'project' }] })
  h.deliver({ type: 'writing-config', config: { session: 'doc-1', learning: false } })
  h.setContext({ before: '向阳' })
  h.api.committed('向阳'); await debounce()
  assert.equal(h.doc.querySelector('[role="option"]').firstChild.textContent, '向阳律师事务所')
  h.deliver({ type: 'writing-config', config: { session: 'doc-2', learning: false } })
  h.api.committed('向阳'); await debounce()
  assert.equal(h.input.getAttribute('aria-expanded'), 'false')
})

test('候选接管 Tab/上下键并维护 ARIA，普通键和修饰方向键保留编辑行为', async t => {
  const h = harness(t)
  await suggestions(h)
  const first = h.input.getAttribute('aria-activedescendant')
  assert.equal(h.doc.getElementById(first).getAttribute('aria-selected'), 'true')
  assert.equal(h.key('ArrowDown').handled, true)
  assert.notEqual(h.input.getAttribute('aria-activedescendant'), first)
  assert.equal(h.key('Tab').handled, true)
  await tick()
  assert.equal(h.calls.find(c => c.action === 'accept_completion').params.text, entries[1].text)
  assert.equal(h.key('a').handled, false)
  await suggestions(h)
  assert.equal(h.key('ArrowDown', { shiftKey: true }).handled, false)
  assert.equal(h.input.getAttribute('aria-expanded'), 'false')
  assert.equal(h.key('Tab').handled, false)
})

test('IME 组合不接受候选，旧上下文响应及禁用后的响应不能重新显示', async t => {
  const waiting = deferred(), h = harness(t, { get_completion_context: () => waiting.promise })
  h.api.committed('北京当红'); await debounce()
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionstart'))
  assert.equal(h.key('Tab', { isComposing: true }).handled, false)
  waiting.resolve({ success: true, available: true, before: '北京当红', token: 'old' }); await tick()
  assert.equal(h.panel().hidden, true)
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionend'))
  h.config({ enabled: false })
  h.api.committed('晴'); await debounce()
  assert.equal(h.calls.length, 1, '关闭自动补全后打字不查询上下文')
})

test('禁用、关闭面板和销毁均使在飞建议失效', async t => {
  const waiting = deferred(), h = harness(t, { get_completion_context: () => waiting.promise })
  h.api.committed('北京当红'); await debounce()
  h.config({ enabled: false })
  waiting.resolve({ success: true, available: true, before: '北京当红', token: 'old' }); await tick()
  assert.equal(h.panel().hidden, true)
  h.api.destroy()
  assert.equal(h.doc.querySelector('.awd-writing-assistance'), null)
  assert.equal(h.input.getAttribute('aria-hidden'), 'true')
  assert.equal(h.input.hasAttribute('role'), false)
  h.api.committed('北京当红'); await debounce()
  assert.equal(h.calls.length, 1)
})

test('迟到的接受结果不能显示旧资料、向新项目学习或抢焦点', async t => {
  const waiting = deferred(), h = harness(t, { accept_completion: () => waiting.promise })
  h.config({ learning: true }); await suggestions(h)
  h.key('Tab')
  h.config({ session: 'doc-2', learning: true })
  h.doc.querySelector('#outside').focus()
  const learnedBefore = h.messages.filter(m => m.action === 'learn').length
  waiting.resolve({ success: true, token: 'old-document' }); await tick()
  assert.equal(h.panel().hidden, true)
  assert.equal(h.doc.activeElement.id, 'outside')
  assert.equal(h.messages.filter(m => m.action === 'learn').length, learnedBefore)
})

test('接受后相关资料保留；详情选择框可聚焦；纯文本和表格一并插入且不可双击重发', async t => {
  const waiting = deferred(), h = harness(t, { insert_completion_content: () => waiting.promise })
  await suggestions(h); h.key('Tab'); await tick()
  assert.ok(h.button('查看已有资料'), 'focus() 不隐藏刚显示的相关资料')
  assert.equal(h.doc.activeElement, h.input)
  h.button('查看已有资料').click()
  const variant = { text: '工商基本信息', rows: [['名称', entries[0].text]] }
  h.respond('detail', { title: '工商资料', variants: [variant, { text: '另一份资料' }] }); await tick()
  h.doc.querySelector('.awd-wa-panel select').focus()
  assert.equal(h.panel().hidden, false, 'input blur 到面板控件不能关闭详情')
  const insert = h.button('插入以上内容'); insert.click(); insert.click(); await tick()
  const calls = h.calls.filter(c => c.action === 'insert_completion_content')
  assert.equal(calls.length, 1)
  assert.deepEqual(calls[0].params, { token: 'cursor-2', ...variant })
  waiting.resolve({ success: true }); await tick()
  assert.ok(h.panel().textContent.includes('已插入'))
})

test('只有选中后右键产生外查菜单，显式点击才查询；晚回结果不能复活关闭的菜单', async t => {
  const h = harness(t)
  h.setContext({ available: false, hasSelection: true, selectedText: '示例企业', token: 'selection-1' })
  h.canvas.dispatchEvent(new h.dom.window.MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 20, clientY: 30 })); await tick()
  assert.equal(h.messages.some(m => m.action === 'lookup'), false)
  h.button('查询机构工商信息').click()
  const request = h.messages.find(m => m.action === 'lookup')
  assert.deepEqual(request.data, { kind: 'COMPANY', text: '示例企业' })
  assert.equal(request.type, 'writing-request'); assert.equal(request.session, 'doc-1')
  h.button('×').click()
  h.respond('lookup', { title: '迟到', variants: [{ text: '旧资料' }] }); await tick()
  assert.equal(h.panel().hidden, true)
})

test('学习仅来自自己提交的文字并分别按个人/项目范围发送；关闭学习清掉未发送缓冲', async t => {
  const h = harness(t)
  h.config({ enabled: false, learning: true })
  h.api.committed('法定代表人：张三。')
  const learned = h.messages.filter(m => m.action === 'learn')
  assert.deepEqual(learned.map(m => m.data.scope).sort(), ['project', 'user'])
  assert.ok(learned.every(m => m.data.entries.some(e => e.text === '张三' && e.kind === 'PERSON')))
  assert.equal(h.calls.length, 0)
  h.api.committed('联系人：李四')
  h.config({ enabled: false, learning: false })
  h.api.invalidate()
  assert.equal(h.messages.filter(m => m.action === 'learn').length, 2)
})

test('管理删除失败显示错误，清空确认按发起时范围；过期结果不跳回旧面板', async t => {
  const h = harness(t)
  h.config({ items: [{ text: '违约责任', kind: 'WORD', scope: 'project', source: 'learned', id: 'learned:1', uses: 2 }] })
  await openManagement(h); h.button('删除').click()
  h.respond('delete', null, '删除失败'); await tick()
  assert.ok(h.panel().textContent.includes('删除失败'))
  await openManagement(h)
  h.button('清空此范围的已学记录').click()
  assert.equal(h.messages.some(m => m.action === 'clear'), false)
  h.button('再次点击确认清空').click()
  assert.equal(h.messages.find(m => m.action === 'clear').data.scope, 'project')
  h.button('我的词库').click()
  h.respond('clear', {}); await tick()
  assert.ok(h.panel().textContent.includes('我的词库'))
  assert.equal(h.panel().textContent.includes('违约责任'), false)
})

test('缓存详情可复用，外查后刷新词库不破坏当前详情和选区 token', async t => {
  const h = harness(t)
  const cached = { id: 'learned:7', text: '北京当红晴天律师事务所', kind: 'COMPANY', source: 'learned', scope: 'user', hasDetail: true }
  h.config({ items: [cached] })
  h.setContext({ before: cached.text })
  h.api.committed('所'); await debounce()
  h.button('查看已有资料').click()
  assert.equal(h.messages.find(m => m.action === 'detail').data.id, 'learned:7')
  h.respond('detail', { title: cached.text, variants: [{ text: '本地已有资料' }] }); await tick()
  assert.equal(h.messages.some(m => m.action === 'lookup'), false)
  h.api.invalidate()
  h.setContext({ available: false, hasSelection: true, selectedText: cached.text, token: 'selected-cache' })
  h.canvas.dispatchEvent(new h.dom.window.MouseEvent('contextmenu', { bubbles: true })); await tick()
  assert.ok(h.button('查看已有资料'))
  h.button('查询机构工商信息').click()
  h.respond('lookup', { title: cached.text, variants: [{ text: '新的工商资料' }] }); await tick()
  assert.ok(h.messages.find(m => m.action === 'refresh'))
  h.config({ items: [cached] })
  h.respond('refresh', {})
  h.button('插入以上内容').click(); await tick()
  assert.deepEqual(h.calls.find(c => c.action === 'insert_completion_content').params, { token: 'selected-cache', text: '新的工商资料' })
})

test('首次聚焦用宿主已有快照，后续聚焦按 30 秒节流刷新且不外查', t => {
  let now = 100000
  t.mock.method(Date, 'now', () => now)
  const h = harness(t)
  const refocus = () => { h.doc.querySelector('#outside').focus(); h.input.focus() }
  refocus(); now += 29000; refocus()
  assert.equal(h.messages.length, 0)
  now += 1001; refocus()
  assert.equal(h.messages.length, 1)
  assert.equal(h.messages[0].action, 'refresh')
  refocus(); assert.equal(h.messages.length, 1)
  h.config({ session: 'another-doc' }); refocus()
  assert.equal(h.messages.length, 1, '新 session 刚收到 snapshot 不再查询')
})

test('WORD/PHRASE 的低频学习项不抢候选；错误 session 的回复被忽略', async t => {
  const h = harness(t)
  h.config({ items: [
    { id: 'learned:1', text: '违约责任', kind: 'WORD', source: 'learned', scope: 'project', uses: 1 },
    { id: 'learned:2', text: '违约损害赔偿', kind: 'PHRASE', source: 'learned', scope: 'user', uses: 2 },
  ] })
  h.setContext({ before: '违约' })
  h.api.committed('违约'); await debounce()
  assert.deepEqual([...h.doc.querySelectorAll('[role="option"]')].map(el => el.firstChild.textContent), ['违约损害赔偿'])
  h.api.invalidate()
  h.setContext({ available: false, hasSelection: true, selectedText: '违约责任', token: 'selection' })
  h.canvas.dispatchEvent(new h.dom.window.MouseEvent('contextmenu', { bubbles: true })); await tick()
  h.button('查询法规与条款').click()
  const request = h.messages.find(m => m.action === 'lookup')
  h.deliver({ type: 'writing-response', id: request.id, session: 'wrong', result: { title: '不可显示', variants: [{ text: '错误' }] } }); await tick()
  assert.equal(h.panel().textContent.includes('不可显示'), false)
  h.config({ writable: false })
  h.respond('lookup', { title: '迟到', variants: [{ text: '错误' }] }); await tick()
  assert.equal(h.panel().hidden, true)
  assert.equal(h.doc.querySelector('.awd-wa-toggle').hidden, true)
})

test('管理入口等待个人/项目学习落盘，再刷新带 ID 的词库供立即删除', async t => {
  const h = harness(t, { deferLearning: true })
  h.config({ learning: true, enabled: false, items: [] })
  h.api.committed('联系人：李四。')
  h.button('写作辅助').click(); h.button('已学词库').click(); await tick()
  const writes = h.messages.filter(m => m.action === 'learn')
  assert.equal(writes.length, 2)
  assert.equal(h.messages.some(m => m.action === 'refresh'), false, '学习未落盘前不提前刷新')
  h.deliver({ type: 'writing-response', id: writes[0].id, session: writes[0].session, result: { learned: 1 } }); await tick()
  assert.equal(h.messages.some(m => m.action === 'refresh'), false, '等两个范围的学习都结束')
  h.deliver({ type: 'writing-response', id: writes[1].id, session: writes[1].session, result: { learned: 1 } }); await tick()
  assert.ok(h.messages.find(m => m.action === 'refresh'))
  h.config({ learning: true, enabled: false, items: [{ id: 'learned:41', text: '李四', kind: 'PERSON', scope: 'project', source: 'learned', uses: 1 }] })
  h.respond('refresh', {}); await tick()
  assert.ok(h.panel().textContent.includes('李四'))
  h.button('删除').click()
  assert.deepEqual(h.messages.find(m => m.action === 'delete').data, { id: 'learned:41' })
  h.respond('delete', {}); await tick()
  assert.equal(h.panel().textContent.includes('李四'), false)
})

test('真实编辑器 AI 通知只让对应 guest 失效，未开审阅面板也能关闭旧候选', async t => {
  const h = harness(t)
  const source = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
  const body = source.match(/onDocMutatedEvent\(payload\) \{([\s\S]*?)\n    \},/)[1]
  const notify = new Function('payload', body).bind({ file: { id: 7 }, reviewOpen: false, reviewRefreshKey: 0, _transportSend: h.deliver })
  await suggestions(h)
  notify({ fileId: 8 })
  assert.equal(h.input.getAttribute('aria-expanded'), 'true', '别的文档的 AI 修改不干扰当前候选')
  notify({ fileId: 7 })
  assert.equal(h.panel().hidden, true)
  assert.equal(h.key('Tab').handled, false, '旧候选关闭后不再抢 Tab')
  await suggestions(h); h.key('Tab'); await tick()
  assert.ok(h.button('查看已有资料'), '人工接受本身仍保持后续提示')
  h.button('查看已有资料').click()
  notify({ fileId: 7 })
  h.respond('detail', { title: '旧结果', variants: [{ text: '过期资料' }] }); await tick()
  assert.equal(h.panel().hidden, true, 'AI 修改前发出的详情晚回也不复活')
})

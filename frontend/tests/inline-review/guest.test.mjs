// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 客体页（正文那一层）的形态契约，dev-board#723/#724 改造后：
// 正文里只剩一颗浮球 + 光标旁的小标记，清单在宿主右栏的「审校」标签里。
// 380px 大浮窗必须不存在——它压着正文且关不掉，是本次改造的病灶本身。
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { attachInlineReview } from '../../src/composables/zetaOfficeInlineReview.js'
const tick = () => new Promise(r => setTimeout(r, 230))
function harness(t, override, language) {
  const dom = new JSDOM('<canvas></canvas><input><div class="awd-wa-panel" hidden></div>', { pretendToBeVisual: true, url: 'https://guest.test/' })
  const doc = dom.window.document, canvas = doc.querySelector('canvas'), input = doc.querySelector('input')
  let receive; const calls = [], messages = []
  let result = { success: true, available: true, revision: 1, paragraphIndex: 0, text: '应于30日付款', offset: 5, hasSelection: false, cursorRectRaw: { pos: { X: 1000, Y: 1000 }, zoom: 100, charHeightPt: 12 } }
  const api = attachInlineReview({ canvas, input, language, execute: async (action, params) => { calls.push({ action, params }); return override ? override(action, params) : result }, transport: { subscribe(fn) { receive = fn; return () => {} }, send(msg) { messages.push(msg) } } })
  const finding = { id: '1', paragraphIndex: 0, start: 2, end: 4, expectedParagraph: '应于30日付款', quote: '30', replacement: '15', title: '期限', message: '请核对期限' }
  const state = patch => receive({ __lo: 'lo-relay', type: 'inline-review-state', session: 'doc1', layoutKey: 'awd_inline_review_layout_x', enabled: true, hidden: false, writable: true, status: 'ready', revision: 1, findings: [finding], ...patch })
  const click = () => canvas.dispatchEvent(new dom.window.MouseEvent('mouseup', { button: 0, clientX: 150, clientY: 150 }))
  const ball = () => doc.querySelector('.awd-ir-ball')
  const chip = () => doc.querySelector('.awd-ir-chip')
  const drag = (from, to) => {
    ball().dispatchEvent(new dom.window.MouseEvent('mousedown', { bubbles: true, button: 0, clientX: from[0], clientY: from[1] }))
    dom.window.dispatchEvent(new dom.window.MouseEvent('mousemove', { clientX: to[0], clientY: to[1] }))
    dom.window.dispatchEvent(new dom.window.MouseEvent('mouseup'))
  }
  t.after(() => { api.destroy(); dom.window.close() })
  return { doc, dom, input, canvas, api, calls, messages, state, click, ball, chip, drag, setResult: r => { result = r } }
}

test('正文里只有浮球和行旁标记，不再有可压住正文的大浮窗', async t => {
  const h = harness(t); h.state(); h.click(); await tick()
  assert.equal(h.doc.querySelector('.awd-ir-panel'), null, '380px 浮窗必须彻底消失')
  assert.equal(h.ball().hidden, false)
  assert.equal(h.ball().textContent.includes('1'), true, '浮球带未读计数')
  assert.ok(h.ball().title.includes('即时审校'))
  assert.equal(h.chip().hidden, false)
  // 显示提示不发任何请求，只读光标上下文
  assert.equal(h.messages.length, 0)
  assert.ok(h.calls.every(c => c.action === 'get_review_context'))
})

test('点浮球和点行旁标记都只通知宿主打开审校清单，不在正文里画面板', async t => {
  const h = harness(t); h.state(); h.click(); await tick()
  h.ball().click(); await Promise.resolve()
  assert.equal(h.messages.length, 1)
  assert.equal(h.messages[0].type, 'inline-review-request')
  assert.equal(h.messages[0].action, 'open-panel')
  assert.equal(h.messages[0].session, 'doc1')
  h.chip().click(); await Promise.resolve()
  assert.equal(h.messages[1].action, 'open-panel')
  assert.equal(h.doc.querySelector('.awd-ir-panel'), null)
})

test('关闭态正文里什么都不挂，也不再读光标', async t => {
  const h = harness(t); h.state({ enabled: false, status: 'disabled' }); h.click(); await tick()
  assert.equal(h.ball().hidden, true)
  assert.equal(h.chip().hidden, true)
  assert.equal(h.doc.body.textContent.includes('已关闭'), false, '关闭态不许在正文里留提示')
  const count = h.calls.length
  h.api.cursorMoved(); await tick()
  assert.equal(h.calls.length, count)
})

test('hidden 偏好只藏浮球，检查照跑、宿主面板照收', async t => {
  const h = harness(t); h.state({ hidden: true }); h.click(); await tick()
  assert.equal(h.ball().hidden, true)
  assert.equal(h.chip().hidden, true, '藏浮球时行旁标记一起安静')
  h.state({ hidden: false }); h.click(); await tick()
  assert.equal(h.ball().hidden, false)
})

test('浮球可拖、松手靠边吸附、位置按用户键落 localStorage；拖完那一下不算点击', async t => {
  const h = harness(t); h.state(); await tick()
  // jsdom 里元素的 getBoundingClientRect 恒为 0，所以按下点即抓取偏移量。
  h.drag([10, 10], [520, 300])
  assert.equal(h.ball().style.top, '290px')
  assert.equal(h.ball().style.left, '948px', '松手吸到最近的一边（1024 宽视口的右侧）')
  const saved = JSON.parse(h.dom.window.localStorage.getItem('awd_inline_review_layout_x'))
  assert.equal(saved.y, 290)
  h.ball().click(); await Promise.resolve()
  assert.equal(h.messages.length, 0, '拖动结束后的那一次 click 不该打开清单')
  await new Promise(r => setTimeout(r, 5))
  h.ball().click(); await Promise.resolve()
  assert.equal(h.messages.at(-1).action, 'open-panel')
})

test('视口变小后浮球仍在可见范围内并留在原来那一边', async t => {
  const h = harness(t); h.state(); await tick()
  h.drag([10, 10], [520, 300])
  h.dom.window.innerWidth = 500; h.dom.window.innerHeight = 360
  h.dom.window.dispatchEvent(new h.dom.window.Event('resize'))
  assert.equal(h.ball().style.left, '424px')
  assert.equal(h.ball().style.top, '290px')
})

test('IME 期间立即藏提示且不接管键盘，迟到快照不在组合期间复活', async t => {
  let resolve
  const h = harness(t, () => new Promise(r => { resolve = r })); h.state(); h.click(); await tick()
  const event = new h.dom.window.KeyboardEvent('keydown', { key: 'Tab', cancelable: true }); h.input.dispatchEvent(event)
  assert.equal(event.defaultPrevented, false)
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionstart'))
  resolve({ success: true, available: true, revision: 1, paragraphIndex: 0, text: '应于30日付款', cursorRectRaw: { pos: { X: 10, Y: 10 } } })
  await new Promise(r => setTimeout(r, 30)); assert.equal(h.chip().hidden, true)
})

test('版本不符或非正文光标不画行旁标记，浮球上的计数照旧', async t => {
  const h = harness(t); h.state({ revision: 2 }); h.click(); await tick()
  assert.equal(h.chip().hidden, true)
  const other = harness(t); other.setResult({ success: false, available: false, reason: 'body-only', revision: 1 })
  other.state(); await tick()
  assert.equal(other.chip().hidden, true)
  assert.equal(other.ball().hidden, false)
  assert.ok(other.ball().textContent.includes('1'))
})

test('引擎的同尺寸 resize 心跳保留提示，实际视口变化才隐藏光标提示', async t => {
  const h = harness(t); h.state(); h.click(); await tick()
  assert.equal(h.chip().hidden, false)
  h.dom.window.dispatchEvent(new h.dom.window.Event('resize'))
  assert.equal(h.chip().hidden, false)
  assert.equal(h.ball().hidden, false)
  h.dom.window.innerWidth = 800
  h.dom.window.dispatchEvent(new h.dom.window.Event('resize'))
  assert.equal(h.chip().hidden, true)
  assert.equal(h.ball().hidden, false, '浮球不随滚动/缩放消失')
})

test('方向键和取消 IME 只刷新光标上下文，不会让浮球永久失效', async t => {
  const h = harness(t); h.state(); h.click(); await tick()
  assert.equal(h.chip().hidden, false)
  h.input.dispatchEvent(new h.dom.window.KeyboardEvent('keydown', { key: 'ArrowRight' }))
  await tick()
  assert.equal(h.ball().hidden, false)
  assert.equal(h.chip().hidden, false)
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionstart'))
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionend', { data: '' }))
  await tick()
  assert.equal(h.chip().hidden, false)
})

test('写作建议出现只隐藏行旁标记，浮球与它的位置不受影响', async t => {
  const h = harness(t); h.state(); h.click(); await tick()
  h.drag([10, 10], [420, 280])
  const position = [h.ball().style.left, h.ball().style.top]
  const completion = h.doc.querySelector('.awd-wa-panel')
  completion.hidden = false
  await Promise.resolve(); await Promise.resolve()
  assert.equal(h.chip().hidden, true)
  assert.equal(h.ball().hidden, false)
  assert.deepEqual([h.ball().style.left, h.ball().style.top], position)
  completion.hidden = true
  await Promise.resolve(); await Promise.resolve()
  assert.equal(h.ball().hidden, false)
})

test('English ball label is localized', async t => {
  const h = harness(t, null, 'en-US'); h.state(); await tick()
  assert.ok(h.ball().title.includes('Inline review'), h.ball().title)
  assert.ok(h.ball().title.includes('suggestions'), h.ball().title)
})

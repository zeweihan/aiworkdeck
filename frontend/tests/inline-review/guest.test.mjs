// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { attachInlineReview } from '../../src/composables/zetaOfficeInlineReview.js'
const tick = () => new Promise(r => setTimeout(r, 230))
function harness(t, override, language) {
  const dom = new JSDOM('<canvas></canvas><input><div class="awd-wa-panel" hidden></div>', { pretendToBeVisual: true })
  const doc = dom.window.document, canvas = doc.querySelector('canvas'), input = doc.querySelector('input')
  let receive; const calls = [], messages = []
  let result = { success: true, available: true, revision: 1, paragraphIndex: 0, text: '应于30日付款', offset: 5, hasSelection: false, cursorRectRaw: { pos: { X: 1000, Y: 1000 }, zoom: 100, charHeightPt: 12 } }
  const api = attachInlineReview({ canvas, input, language, execute: async (action, params) => { calls.push({ action, params }); return override ? override(action, params) : result }, transport: { subscribe(fn) { receive = fn; return () => {} }, send(msg) { messages.push(msg) } } })
  const finding = { id: '1', paragraphIndex: 0, start: 2, end: 4, expectedParagraph: '应于30日付款', quote: '30', replacement: '15', title: '期限', message: '请核对期限' }
  const state = patch => receive({ __lo: 'lo-relay', type: 'inline-review-state', session: 'doc1', enabled: true, writable: true, status: 'ready', revision: 1, findings: [finding], ...patch })
  const click = () => canvas.dispatchEvent(new dom.window.MouseEvent('mouseup', { button: 0, clientX: 150, clientY: 150 }))
  const button = label => [...doc.querySelectorAll('.awd-ir-panel button')].find(b => b.textContent === label)
  const open = async () => { doc.querySelector('.awd-ir-status').click(); await Promise.resolve(); await Promise.resolve() }
  t.after(() => { api.destroy(); dom.window.close() })
  return { doc, dom, input, canvas, api, calls, messages, state, click, button, open, setResult: r => { result = r } }
}
test('只显示提示不触发外查/AI/正文修改，深度与偏好仅点击发送', async t => {
  const h = harness(t); h.state({ summary: { findingCount: 1 } }); h.click(); await tick(); await h.open()
  assert.ok(h.button('采用建议')); assert.equal(h.messages.length, 0); assert.ok(h.calls.every(c => c.action === 'get_review_context'))
  assert.equal(h.doc.body.textContent.includes('[object Object]'), false)
  h.button('深入审校（AI）').click(); await Promise.resolve(); await Promise.resolve()
  assert.equal(h.messages[0].action, 'deep'); assert.equal(h.messages[0].revision, 1); assert.equal(h.messages[0].session, 'doc1')
  assert.equal(h.button('深入审校中…').disabled, true)
  h.button('关闭即时检查').click(); await Promise.resolve(); await Promise.resolve()
  assert.deepEqual(h.messages[1].data, { enabled: false }); assert.equal(h.messages[1].action, 'preferences')
})
test('输入/IME/滚动立即藏提示且不接管键盘，迟到快照不复活', async t => {
  let resolve
  const h = harness(t, () => new Promise(r => { resolve = r })); h.state(); h.click(); await tick()
  const event = new h.dom.window.KeyboardEvent('keydown', { key: 'Tab', cancelable: true }); h.input.dispatchEvent(event)
  assert.equal(event.defaultPrevented, false)
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionstart'))
  resolve({ success: true, available: true, revision: 1, paragraphIndex: 0, text: '应于30日付款', cursorRectRaw: { pos: { X: 10, Y: 10 } } })
  await tick(); assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, true); assert.equal(h.calls.length, 1)
})
test('非正文光标允许读同世代的全量清单，禁止行旁chip；只读不提供采用', async t => {
  const h = harness(t); h.setResult({ success: false, available: false, reason: 'body-only', revision: 1 }); h.state({ writable: false }); await tick(); await h.open()
  assert.ok(h.doc.querySelector('.awd-ir-panel').textContent.includes('请核对期限')); assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, true); assert.equal(h.button('采用建议'), undefined)
})
test('版本不符不展示旧条目；禁用后停止读取；错误码有可读文案', async t => {
  const h = harness(t); h.state({ revision: 2 }); h.click(); await tick(); await h.open()
  assert.equal(h.doc.querySelector('.awd-ir-panel').textContent.includes('请核对期限'), false)
  h.state({ enabled: false, status: 'disabled' }); h.click(); await tick(); const count = h.calls.length
  h.api.cursorMoved(); await tick(); assert.equal(h.calls.length, count)
  h.state({ status: 'error', message: 'REVIEW_INLINE_REVISIONS' }); await h.open(); assert.ok(h.doc.querySelector('.awd-ir-panel').textContent.includes('页边修订'))
})

test('同份正文的深入审校进度和结果保持已打开卡片，正文变动立即收起', async t => {
  const h = harness(t); h.state(); await tick(); await h.open()
  h.state({ deepStatus: 'checking' })
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
  assert.equal(h.button('深入审校中…').disabled, true)
  h.state({ deepStatus: 'ready' })
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
  assert.ok(h.doc.querySelector('.awd-ir-panel').textContent.includes('请核对期限'))
  h.state({ revision: null, status: 'stale' })
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, true)
})

test('引擎的同尺寸 resize 心跳保留提示，实际视口变化立即隐藏', async t => {
  const h = harness(t); h.state(); h.click(); await tick(); await h.open()
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, false)
  h.dom.window.dispatchEvent(new h.dom.window.Event('resize'))
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, false)
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
  h.dom.window.innerWidth = 800
  h.dom.window.dispatchEvent(new h.dom.window.Event('resize'))
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, true)
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, true)
})

test('English review controls and scope are localized', async t => {
  const h = harness(t, null, 'en-US'); h.state(); await tick(); await h.open()
  assert.ok(h.button('Deep review (AI)'))
  assert.ok(h.button('Apply suggestion'))
  assert.ok(h.doc.querySelector('.awd-ir-panel').textContent.includes('Scope: body paragraphs.'))
  h.state({ status: 'error', message: 'REVIEW_DEEP_INCOMPLETE' }); await h.open()
  assert.ok(h.doc.querySelector('.awd-ir-panel').textContent.includes('Deep review did not finish completely'))
})

test('检查结果未到时先校准点击位置，输入发生后不再用旧点击校准', async t => {
  const h = harness(t); h.state({ revision: null, status: 'checking' }); h.click(); await tick()
  assert.equal(h.calls.length, 1, 'caret calibration does not wait for review results')
  h.state(); await tick()
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, false)
  const second = harness(t); second.state({ revision: null, status: 'checking' }); second.click()
  second.input.dispatchEvent(new second.dom.window.CompositionEvent('compositionstart'))
  second.input.dispatchEvent(new second.dom.window.CompositionEvent('compositionend'))
  second.state(); await tick()
  assert.equal(second.doc.querySelector('.awd-ir-chip').hidden, true, 'old click is not mapped to the new typing position')
})

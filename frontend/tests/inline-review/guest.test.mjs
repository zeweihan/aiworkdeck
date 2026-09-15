// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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
  const state = patch => receive({ __lo: 'lo-relay', type: 'inline-review-state', session: 'doc1', enabled: true, writable: true, status: 'ready', revision: 1, findings: [finding], ...patch })
  const click = () => canvas.dispatchEvent(new dom.window.MouseEvent('mouseup', { button: 0, clientX: 150, clientY: 150 }))
  const button = label => [...doc.querySelectorAll('.awd-ir-panel button')].find(b => b.textContent === label)
  const open = async () => { [...doc.querySelectorAll('.awd-ir-status button')].find(b => b.textContent === (language?.startsWith('en') ? 'Expand' : '展开')).click(); await Promise.resolve(); await Promise.resolve() }
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
test('IME 期间立即藏提示且不接管键盘，迟到快照不在组合期间复活', async t => {
  let resolve
  const h = harness(t, () => new Promise(r => { resolve = r })); h.state(); h.click(); await tick()
  const event = new h.dom.window.KeyboardEvent('keydown', { key: 'Tab', cancelable: true }); h.input.dispatchEvent(event)
  assert.equal(event.defaultPrevented, false)
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionstart'))
  resolve({ success: true, available: true, revision: 1, paragraphIndex: 0, text: '应于30日付款', cursorRectRaw: { pos: { X: 10, Y: 10 } } })
  await new Promise(r => setTimeout(r, 30)); assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, true)
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

test('同份正文的深入审校进度和结果保持已打开卡片，正文变动保留窗口但禁用旧结果', async t => {
  const h = harness(t); h.state(); await tick(); await h.open()
  h.state({ deepStatus: 'checking' })
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
  assert.equal(h.button('深入审校中…').disabled, true)
  h.state({ deepStatus: 'ready' })
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
  assert.ok(h.doc.querySelector('.awd-ir-panel').textContent.includes('请核对期限'))
  h.state({ revision: null, status: 'stale' })
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
  assert.ok(h.doc.querySelector('.awd-ir-panel').textContent.includes('正文已变化'))
  assert.equal(h.button('定位原文'), undefined)
})

test('引擎的同尺寸 resize 心跳保留提示，实际视口变化只隐藏光标提示', async t => {
  const h = harness(t); h.state(); h.click(); await tick(); await h.open()
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, false)
  h.dom.window.dispatchEvent(new h.dom.window.Event('resize'))
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, false)
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
  h.dom.window.innerWidth = 800
  h.dom.window.dispatchEvent(new h.dom.window.Event('resize'))
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, true)
  assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
})

test('缩小视口会把收起入口和展开面板重新限制在可见范围', async t => {
  const collapsed = harness(t); collapsed.state(); await tick()
  const status = collapsed.doc.querySelector('.awd-ir-status')
  status.dispatchEvent(new collapsed.dom.window.MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 10, clientY: 10 }))
  collapsed.dom.window.dispatchEvent(new collapsed.dom.window.MouseEvent('mousemove', { clientX: 5000, clientY: 5000 }))
  collapsed.dom.window.dispatchEvent(new collapsed.dom.window.MouseEvent('mouseup'))
  collapsed.dom.window.innerWidth = 500; collapsed.dom.window.innerHeight = 360
  collapsed.dom.window.dispatchEvent(new collapsed.dom.window.Event('resize'))
  assert.equal(status.style.left, '282px')
  assert.equal(status.style.top, '310px')

  const expanded = harness(t); expanded.state(); await tick(); await expanded.open()
  const panel = expanded.doc.querySelector('.awd-ir-panel'), title = expanded.doc.querySelector('.awd-ir-title')
  title.dispatchEvent(new expanded.dom.window.MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 10, clientY: 10 }))
  expanded.dom.window.dispatchEvent(new expanded.dom.window.MouseEvent('mousemove', { clientX: 5000, clientY: 5000 }))
  expanded.dom.window.dispatchEvent(new expanded.dom.window.MouseEvent('mouseup'))
  expanded.dom.window.innerWidth = 600; expanded.dom.window.innerHeight = 420
  expanded.dom.window.dispatchEvent(new expanded.dom.window.Event('resize'))
  assert.equal(panel.style.left, '212px')
  assert.equal(panel.style.top, '92px')
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

test('全量问题按类别显示计数、筛选空态，并保持深入审校入口', async t => {
  const h = harness(t)
  h.state({ findings: [
    { id: 'p', kind: 'PLACEHOLDER', title: '待填写', message: '补充内容' },
    { id: 'c', kind: 'COUNT_MISMATCH', title: '数量', message: '前后不一致' },
    { id: 'n', kind: 'NUMBERING', title: '编号', message: '编号跳号' },
    { id: 'u', kind: 'USCC_INVALID', title: '号码', message: '号码错误' },
    { id: 'a', kind: 'LOGIC_REVIEW', title: '逻辑', message: '待人工确认' },
  ], summary: { findingCount: 5 } })
  await tick(); await h.open()
  assert.ok(h.button('全部 5')); assert.ok(h.button('待补充 1')); assert.ok(h.button('一致性 1'))
  assert.ok(h.button('格式与号码 2')); assert.ok(h.button('AI 审校 1'))
  h.button('AI 审校 1').click(); await Promise.resolve(); await Promise.resolve()
  const text = h.doc.querySelector('.awd-ir-body').textContent
  assert.ok(text.includes('待人工确认')); assert.equal(text.includes('编号跳号'), false)
  assert.ok(h.button('深入审校（AI）'))
  h.state({ findings: [] }); await tick(); await h.open()
  assert.ok(h.doc.querySelector('.awd-ir-body').textContent.includes('此分类暂无问题'))
})

test('标题拖柄移动并限制在视口内，按钮不会误触拖动，位置按审校session保存', async t => {
  const h = harness(t); h.state(); await tick(); await h.open()
  const panel = h.doc.querySelector('.awd-ir-panel'), title = h.doc.querySelector('.awd-ir-title')
  title.dispatchEvent(new h.dom.window.MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 10, clientY: 10 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mousemove', { clientX: 5000, clientY: 5000 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mouseup'))
  assert.equal(panel.style.left, '636px'); assert.equal(panel.style.top, '440px')
  assert.ok(h.dom.window.sessionStorage.getItem('awd_inline_review_panel_doc1'))
  const before = [panel.style.left, panel.style.top]
  h.button('收起').dispatchEvent(new h.dom.window.MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 1, clientY: 1 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mousemove', { clientX: 30, clientY: 30 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mouseup'))
  assert.deepEqual([panel.style.left, panel.style.top], before)
  h.button('收起').click(); await Promise.resolve(); await Promise.resolve()
  assert.equal(panel.hidden, true); assert.equal(h.doc.querySelector('.awd-ir-status').hidden, false)
  assert.equal(h.doc.querySelector('.awd-ir-status').style.left, before[0])
})

test('紧凑入口避开状态栏且可独立拖动，只有展开按钮打开面板', async t => {
  const h = harness(t); h.state({ layoutKey: 'layout-user-a' }); await tick()
  const status = h.doc.querySelector('.awd-ir-status')
  assert.equal(status.hidden, false); assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, true)
  assert.equal(h.dom.window.getComputedStyle(status).bottom, '64px')
  status.dispatchEvent(new h.dom.window.MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 10, clientY: 700 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mousemove', { clientX: 300, clientY: 300 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mouseup'))
  assert.equal(status.style.left, '290px'); assert.ok(h.dom.window.sessionStorage.getItem('layout-user-a'))
  status.click(); await Promise.resolve(); assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, true)
  const expand = [...status.querySelectorAll('button')].find(b => b.textContent === '展开')
  expand.click(); await Promise.resolve(); await Promise.resolve()
  assert.equal(status.hidden, true); assert.equal(h.doc.querySelector('.awd-ir-panel').hidden, false)
})

test('方向键和取消IME只刷新光标上下文，不会令分类列表永久失效', async t => {
  const h = harness(t); h.state({ findings: [
    { id: 'n', kind: 'NUMBERING', paragraphIndex: 0, title: '编号', message: '编号跳号', expectedParagraph: '应于30日付款' },
  ] }); h.click(); await tick(); await h.open()
  assert.ok(h.button('格式与号码 1'))
  h.input.dispatchEvent(new h.dom.window.KeyboardEvent('keydown', { key: 'ArrowRight' }))
  await tick()
  assert.ok(h.button('格式与号码 1')); assert.equal(h.doc.querySelector('.awd-ir-panel').textContent.includes('正文已变化'), false)
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionstart'))
  h.input.dispatchEvent(new h.dom.window.CompositionEvent('compositionend', { data: '' }))
  await tick()
  assert.ok(h.button('格式与号码 1')); assert.equal(h.doc.querySelector('.awd-ir-panel').textContent.includes('正文已变化'), false)
})

test('写作建议出现只隐藏行旁chip，不收起用户已展开和拖动的审校窗', async t => {
  const h = harness(t); h.state(); h.click(); await tick(); await h.open()
  const panel = h.doc.querySelector('.awd-ir-panel'), title = h.doc.querySelector('.awd-ir-title')
  title.dispatchEvent(new h.dom.window.MouseEvent('mousedown', { bubbles: true, button: 0, clientX: 10, clientY: 10 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mousemove', { clientX: 420, clientY: 280 }))
  h.dom.window.dispatchEvent(new h.dom.window.MouseEvent('mouseup'))
  const position = [panel.style.left, panel.style.top]
  const completion = h.doc.querySelector('.awd-wa-panel')
  completion.hidden = false
  await Promise.resolve(); await Promise.resolve()
  assert.equal(h.doc.querySelector('.awd-ir-chip').hidden, true)
  assert.equal(panel.hidden, false)
  assert.deepEqual([panel.style.left, panel.style.top], position)
  completion.hidden = true
  await Promise.resolve(); await Promise.resolve()
  assert.equal(panel.hidden, false)
  assert.ok(h.button('全部 1'), 'panel mode and category controls remain mounted')
})

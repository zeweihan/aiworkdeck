// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 候选可见时的键盘归属：Tab/Esc/方向键必须由宿主先处理，落在画布上的按键也算。
// 真机（v0.44.1，macOS）两条症状：候选出现后按 Tab 没有接受，只能用鼠标点；
// 按 Esc 时引擎收到 .uno:Escape，顶层窗口退出全屏、画布里冒出带标题栏的小窗。
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { attachImeOverlay } from '../../src/composables/zetaOfficeImeOverlay.js'
import { attachWritingAssistance } from '../../src/composables/zetaOfficeCompletion.js'

const debounce = () => new Promise(resolve => setTimeout(resolve, 190))
const tick = () => new Promise(resolve => setTimeout(resolve, 10))
const entries = [
  { id: 'one', text: '北京当红晴天律师事务所', kind: 'COMPANY', scope: 'project', source: 'project' },
  { id: 'two', text: '北京当红齐天集团', kind: 'COMPANY', scope: 'project', source: 'project' },
]

function harness(t) {
  const dom = new JSDOM('<!doctype html><html><head></head><body><div><canvas tabindex="0"></canvas></div></body></html>', { pretendToBeVisual: true })
  const keys = ['document', 'navigator', 'getComputedStyle']
  const saved = Object.fromEntries(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  for (const [key, value] of Object.entries({ document: dom.window.document, navigator: { platform: 'MacIntel' }, getComputedStyle: dom.window.getComputedStyle })) {
    Object.defineProperty(globalThis, key, { configurable: true, value })
  }
  const doc = dom.window.document, canvas = doc.querySelector('canvas')
  const commands = [], calls = [], subscribers = new Set()
  // 引擎那一侧：落到 window 的按键就是「引擎能看到的按键」。
  const leaked = []
  dom.window.addEventListener('keydown', e => leaked.push(e.key))
  let writing = null, before = '北京当红'
  const overlay = attachImeOverlay({
    canvas,
    commit: text => { before += text; return { success: true } },
    onCommitted: text => writing?.committed(text),
    onCursorMoved: event => writing?.cursorMoved(event),
    onEnter: async () => { commands.push({ action: 'insert_paragraph', params: {} }); return { success: true } },
    sendCommand: async (action, params) => { commands.push({ action, params }); return { success: true } },
    onAssistanceKey: event => writing?.keydown(event) || false,
  })
  const transport = {
    subscribe: fn => { subscribers.add(fn); return () => subscribers.delete(fn) },
    send: msg => { for (const listener of subscribers) listener({ __lo: 'lo-relay', type: 'writing-response', id: msg.id, session: msg.session, result: {} }) },
  }
  const execute = async (action, params) => {
    calls.push({ action, params })
    if (action === 'get_completion_context') {
      return { success: true, available: true, hasSelection: false, before, after: '', paragraph: '北京当红', token: 'cursor-1' }
    }
    return { success: true, token: 'cursor-2' }
  }
  writing = attachWritingAssistance({ canvas, input: overlay.element, execute, transport, focus: overlay.focus })
  for (const listener of subscribers) {
    listener({ __lo: 'lo-relay', type: 'writing-config', config: { session: 'doc-1', enabled: true, learning: false, hints: false, writable: true, items: entries } })
  }
  t.after(() => {
    writing.destroy(); overlay.destroy(); dom.window.close()
    for (const key of keys) {
      if (saved[key]) Object.defineProperty(globalThis, key, saved[key])
      else delete globalThis[key]
    }
  })
  const press = (target, key, props = {}) => {
    const event = new dom.window.KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...props })
    target.dispatchEvent(event)
    return event
  }
  const deliver = msg => { for (const listener of subscribers) listener({ __lo: 'lo-relay', ...msg }) }
  return { dom, doc, canvas, input: overlay.element, overlay, writing, commands, calls, leaked, deliver,
    press, setBefore: value => { before = value }, expanded: () => overlay.element.getAttribute('aria-expanded'),
    // 设置面板没有画布上的入口了（dev-board#755），只能由宿主工具栏发指令开合。
    openSettings: () => deliver({ type: 'writing-assistance-panel', open: true }),
    open: async () => { writing.committed('北京当红'); await debounce(); assert.equal(overlay.element.getAttribute('aria-expanded'), 'true', '候选应已展开') } }
}

test('候选可见时落在画布上的 Tab 接受候选，不插制表符也不漏给引擎', async t => {
  const h = harness(t)
  await h.open()
  h.leaked.length = 0
  const event = h.press(h.canvas, 'Tab')
  await tick()
  assert.ok(h.calls.some(c => c.action === 'accept_completion'), '候选应被接受：' + JSON.stringify(h.calls.map(c => c.action)))
  assert.deepEqual(h.commands.filter(c => c.action === 'tab_key'), [], 'Tab 不该落到 tab_key（那是插制表符/跳单元格）')
  assert.ok(event.defaultPrevented, '浏览器默认的焦点跳转要吃掉')
  assert.deepEqual(h.leaked, [], '处理掉的按键不许再冒泡到引擎')
  assert.equal(h.expanded(), 'false', '接受之后候选收起')
})

test('候选可见时落在画布上的 Esc 只关候选，不派发 .uno:Escape', async t => {
  const h = harness(t)
  await h.open()
  h.leaked.length = 0
  const event = h.press(h.canvas, 'Escape')
  await tick()
  assert.equal(h.expanded(), 'false', '候选应被关掉')
  assert.deepEqual(h.commands.filter(c => c.action === 'ui_command' && c.params.name === 'escape'), [],
    'Esc 有候选可关时不许下传引擎：.uno:Escape 会让顶层窗口退出全屏')
  assert.ok(event.defaultPrevented)
  assert.deepEqual(h.leaked, [])
})

test('没有候选可关、Esc 落在输入框上：宿主自己取消选区（引擎不管 <input> 里的按键）', async t => {
  const h = harness(t)
  h.input.focus()
  h.press(h.input, 'Escape')
  await tick()
  assert.deepEqual(h.commands.filter(c => c.action === 'ui_command').map(c => c.params.name), ['escape'],
    'worker 侧 ui_command escape 是视图光标塌陷，不是 .uno:Escape（那条会让全屏帧退出全屏）')
})

test('没有候选可关、Esc 落在画布上：原样交给引擎（关它自己的原生右键弹窗独此一路）', async t => {
  const h = harness(t)
  h.leaked.length = 0
  const event = h.press(h.canvas, 'Escape')
  await tick()
  assert.deepEqual(h.commands, [], '画布上的 Esc 不转发任何命令')
  assert.equal(event.defaultPrevented, false, '不吃掉它')
  assert.deepEqual(h.leaked, ['Escape'], '事件要继续冒泡到引擎自己的按键处理')
})

test('落在画布上的方向键与回车照旧走引擎，宿主面板里的按键不被劫持', async t => {
  const h = harness(t)
  h.press(h.canvas, 'ArrowLeft')
  h.press(h.canvas, 'Enter')
  await tick()
  assert.deepEqual(h.commands.map(c => c.action), ['move_cursor', 'insert_paragraph'])
  h.openSettings()
  const panelButton = h.doc.querySelector('.awd-writing-assistance button')
  assert.ok(panelButton, '自动补全设置面板已打开')
  h.commands.length = 0
  h.press(panelButton, 'Tab')
  h.press(panelButton, 'ArrowLeft')
  await tick()
  assert.deepEqual(h.commands, [], '宿主自己的 DOM 面板里的按键不归覆盖层管')
})


test('真实覆盖层重定位不清掉连续输入的候选选择及快速筛选', async t => {
  const h = harness(t)
  h.setBefore('北京当')
  await h.open()
  h.press(h.input, 'ArrowDown')
  const picked = h.input.getAttribute('aria-activedescendant')
  const delays = [], original = globalThis.setTimeout
  t.mock.method(globalThis, 'setTimeout', (fn, delay, ...args) => { delays.push(delay); return original(fn, delay, ...args) })
  h.press(h.input, '红')
  h.input.dispatchEvent(new h.dom.window.InputEvent('input', { data: '红', inputType: 'insertText', bubbles: true }))
  await new Promise(resolve => original(resolve, 120))
  assert.ok(delays.includes(35), '真正的插入→光标重定位→提交链路必须使用35ms刷新')
  assert.equal(h.input.getAttribute('aria-activedescendant'), picked, '仍有匹配的已选第二项不能跳回第一项')
})

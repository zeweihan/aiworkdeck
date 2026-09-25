// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// v0.49.0 BUG-05：xlsx 里用键盘打字进不了单元格。
//
// IME 覆盖层把每个按键转成一条 worker 原语（insert_at_cursor / insert_paragraph /
// move_cursor / delete_* / tab_key / ui_command），这些原语原先一律从 Writer 的
// ctrl.getViewCursor() 起步。Calc / Impress 的控制器没有这个方法（真引擎：
// "ctrl.getViewCursor is not a function"），execCommand 把异常吞成 success:false，
// 用户看到的就是「打了没反应」。修法：表格与演示文稿改由引擎自己的按键处理接手
// （XToolkitRobot 把按键投递给文档窗口），Writer 路径一字不动。
//
// 这里按原文从 office_thread.js 抠出原语与 helper，用假的 UNO 对象跑：
//   · Calc / Impress：不许碰视图光标，按键按顺序投递给组件窗口；
//   · Writer：仍走视图光标，一个按键都不投递。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')

// 顶层函数：`function NAME(` 起按花括号配平（这些函数体的字符串里没有花括号）。
// 修复前不存在的 helper 返回空串——原语本身照样能抠出来跑，红在行为上。
function topLevel(name) {
  const at = SRC.indexOf('\nfunction ' + name + '(')
  if (at < 0) return ''
  let depth = 0
  for (let i = SRC.indexOf('{', at); i < SRC.length; i++) {
    if (SRC[i] === '{') depth++
    else if (SRC[i] === '}' && --depth === 0) return SRC.slice(at + 1, i + 1)
  }
  throw new Error(name + ' 花括号不配平')
}
// EXEC 里的方法：两格缩进的 `name(p) {` 到同缩进的 `},`。
function method(name) {
  const m = SRC.match(new RegExp('\\n  ' + name + '\\((p)?\\) \\{\\n[\\s\\S]*?\\n  \\},\\n'))
  assert.ok(m, 'office_thread.js 里找不到原语 ' + name)
  return m[0].trim()
}
// 顶层常量 NATIVE_*（按键表）一并带上；修复前不存在则为空。
const consts = [...SRC.matchAll(/^const NATIVE_[A-Z_]+ = [^\n]*;$/gm)].map((m) => m[0]).join('\n')

const ACTIONS = ['insert_at_cursor', 'insert_paragraph', 'move_cursor', 'delete_backward', 'delete_forward', 'tab_key', 'ui_command']
const HELPERS = ['isWriterDoc', 'isCalcDoc', 'isImpressDoc', 'nativeKeyDoc', 'postNativeKeys', 'restoreFullScreenSoon']

const KEY = { DOWN: 1024, UP: 1025, LEFT: 1026, RIGHT: 1027, HOME: 1028, END: 1029, PAGEUP: 1030, PAGEDOWN: 1031, RETURN: 1280, ESCAPE: 1281, TAB: 1282, BACKSPACE: 1283, DELETE: 1286 }
const SERVICE = { writer: 'com.sun.star.text.TextDocument', calc: 'com.sun.star.sheet.SpreadsheetDocument', impress: 'com.sun.star.presentation.PresentationDocument' }

function build(kind) {
  const seen = { posted: [], dispatched: [], writerInserts: [], timers: [], fullScreen: true }
  const componentWindow = { name: 'component-window' }
  const container = {}
  Object.defineProperty(container, 'FullScreen', { get: () => seen.fullScreen, set: (v) => { seen.fullScreen = v } })
  const frame = { getComponentWindow: () => componentWindow, getContainerWindow: () => container }
  const vc = {
    collapseToEnd() {}, collapseToStart() {}, goLeft(n, ex) { seen.writerInserts.push('left' + (ex ? '+' : '')) },
    goRight() {}, goUp() {}, goDown() {}, getPropertyValue() { return null },
    getText: () => ({ insertControlCharacter: () => seen.writerInserts.push('<para>') }),
  }
  const ctrl = { getFrame: () => frame }
  if (kind === 'writer') ctrl.getViewCursor = () => vc
  const xModel = { supportsService: (s) => s === SERVICE[kind] }
  const css = {
    awt: {
      Key: KEY, KeyModifier: { SHIFT: 1, MOD1: 2, MOD2: 4 },
      KeyEvent: function KeyEvent(init) { Object.assign(this, init) },
      Toolkit: { create: () => ({
        keyPress: (ev) => seen.posted.push({ code: ev.KeyCode, ch: ev.KeyChar, mod: ev.Modifiers, source: ev.Source }),
        keyRelease: () => {},
      }) },
    },
    text: { ControlCharacter: { PARAGRAPH_BREAK: 0 } },
  }
  const deps = {
    ctrl, xModel, css, context: {}, errStr: (e) => String(e && e.message || e),
    MD_MARKER_RE: /\*\*|^#/m,
    insertTextAtCursor: (_, t) => seen.writerInserts.push(t), insertInlineStyled: (_, t) => seen.writerInserts.push(t),
    verifySnapshot: () => ({}), viewCursorCellName: () => null,
    dispatchUno: (url) => seen.dispatched.push(url),
    UI_COMMANDS: { line_start: '.uno:GoToStartOfLine', page_down: '.uno:PageDown', bold: '.uno:Bold' },
    deselect: () => ({ success: true, name: 'escape', writer: true }),
    ensureFullScreen: () => { seen.fullScreen = true },
    setTimeout: (fn) => { seen.timers.push(fn) },
  }
  const body = consts + '\nlet keyRobot = null;\n' + HELPERS.map(topLevel).join('\n')
    + '\nconst EXEC = {\n' + ACTIONS.map(method).join('\n') + '\n};\nreturn EXEC;'
  const EXEC = new Function(...Object.keys(deps), body)(...Object.values(deps))
  return { EXEC, seen, componentWindow }
}

for (const kind of ['calc', 'impress']) {
  test(kind + '：键入的字按键投递给文档窗口，不碰 Writer 视图光标', () => {
    const { EXEC, seen, componentWindow } = build(kind)
    const r = EXEC.insert_at_cursor({ text: 'xc5中' })
    assert.equal(r.success, true, JSON.stringify(r))
    assert.deepEqual(seen.posted.map((k) => k.ch), ['x', 'c', '5', '中'])
    assert.ok(seen.posted.every((k) => k.source === componentWindow && k.code === 0 && k.mod === 0))
    assert.deepEqual(seen.writerInserts, [])
  })

  test(kind + '：文本里的换行投递回车键，不当字符插入（否则被引擎静默吞掉，success:true 但内容少一段）', () => {
    const { EXEC, seen } = build(kind)
    const r = EXEC.insert_at_cursor({ text: 'a\nb' })
    assert.equal(r.success, true, JSON.stringify(r))
    assert.deepEqual(seen.posted.map((k) => [k.code, k.ch]), [[0, 'a'], [KEY.RETURN, '\r'], [0, 'b']])
  })

  test(kind + '：扩展区汉字/emoji 按码点整个投递，不按 UTF-16 code unit 拆成孤立代理对', () => {
    const { EXEC, seen } = build(kind)
    const r = EXEC.insert_at_cursor({ text: '\u{20000}x' })
    assert.equal(r.success, true, JSON.stringify(r))
    assert.equal(seen.posted.length, 2, JSON.stringify(seen.posted))
    assert.equal(seen.posted[0].ch, '\u{20000}')
    assert.equal(seen.posted[1].ch, 'x')
  })

  test(kind + '：Backspace / Delete / 方向键 / Tab 都走原生按键，不派发 Writer 专属的 .uno:SwBackspace', () => {
    const { EXEC, seen } = build(kind)
    for (const [a, p] of [['delete_backward'], ['delete_forward'], ['move_cursor', { dir: 'right' }], ['move_cursor', { dir: 'up', extend: true }], ['tab_key', { shift: true }]]) {
      const r = EXEC[a](p || {})
      assert.equal(r.success, true, a + ' ' + JSON.stringify(r))
    }
    assert.deepEqual(seen.posted.map((k) => [k.code, k.mod]), [[KEY.BACKSPACE, 0], [KEY.DELETE, 0], [KEY.RIGHT, 0], [KEY.UP, 1], [KEY.TAB, 1]])
    assert.deepEqual(seen.dispatched, [], '.uno:SwBackspace 在表格里是哑弹，.uno:Delete 在 Calc 是「删除内容」对话框')
    assert.equal(EXEC.move_cursor({ dir: 'sideways' }).success, false)
  })

  test(kind + '：Home/End/PageDown 与 Esc 走原生按键，加粗之类的格式命令照旧派发', () => {
    const { EXEC, seen } = build(kind)
    for (const name of ['line_start', 'line_end_sel', 'page_down', 'escape', 'bold']) {
      assert.equal(EXEC.ui_command({ name }).success, true, name)
    }
    assert.deepEqual(seen.posted.map((k) => [k.code, k.mod]), [[KEY.HOME, 0], [KEY.END, 1], [KEY.PAGEDOWN, 0], [KEY.ESCAPE, 0]])
    assert.deepEqual(seen.dispatched, ['.uno:Bold'])
  })
}

for (const kind of ['calc', 'impress']) {
  test(kind + '：回车只投递一个回车（Calc 由引擎提交并下移，Impress 文本框里是换段；补「下」会跳两格，真引擎实测）', () => {
    const { EXEC, seen } = build(kind)
    assert.equal(EXEC.insert_paragraph({}).success, true)
    assert.deepEqual(seen.posted.map((k) => k.code), [KEY.RETURN])
  })
}

test('calc：Esc 之后把被引擎退掉的全屏补回来（非编辑态的 Esc 会退出全屏，真引擎实测）', () => {
  const { EXEC, seen } = build('calc')
  EXEC.ui_command({ name: 'escape' })
  assert.ok(seen.timers.length > 0, 'Esc 之后要排一次全屏复位')
  seen.fullScreen = false
  seen.timers.forEach((fn) => fn())
  assert.equal(seen.fullScreen, true)
})

test('writer：原语照旧走视图光标，一个按键都不投递', () => {
  const { EXEC, seen } = build('writer')
  assert.equal(EXEC.insert_at_cursor({ text: '甲乙' }).success, true)
  assert.equal(EXEC.insert_paragraph({}).success, true)
  assert.equal(EXEC.move_cursor({ dir: 'left', extend: true }).success, true)
  assert.equal(EXEC.delete_backward({}).success, true)
  assert.equal(EXEC.delete_forward({}).success, true)
  assert.equal(EXEC.ui_command({ name: 'line_start' }).success, true)
  assert.deepEqual(EXEC.ui_command({ name: 'escape' }), { success: true, name: 'escape', writer: true })
  assert.deepEqual(seen.posted, [])
  assert.deepEqual(seen.writerInserts, ['甲乙', '<para>', 'left+'])
  assert.deepEqual(seen.dispatched, ['.uno:SwBackspace', '.uno:Delete', '.uno:GoToStartOfLine'])
})

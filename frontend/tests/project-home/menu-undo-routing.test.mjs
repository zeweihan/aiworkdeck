// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-30：菜单栏「编辑 > 撤销/重做」在文档里无效。
//
// J1 复核否决第一版的理由：文档标签激活时把撤销一律转成文档 .uno:Undo，焦点在 AI 输入框、
// 查找替换、批注表单、重命名框里时 ⌘Z 也去撤销文档了。现在按 document.activeElement 分流
// （utils/undoRouting.js），这里在 jsdom 里真聚焦各种元素验证落点。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { resolveUndoTarget, runMenuUndoRedo, isEditableElement } from '../../src/utils/undoRouting.js'

function page() {
  const dom = new JSDOM(`<!doctype html><body>
    <div class="chat"><uni-textarea><textarea id="ai"></textarea></uni-textarea></div>
    <input id="find" type="text"><input id="rename"><input id="chk" type="checkbox">
    <div id="note" contenteditable="true"><span id="inner">x</span></div>
    <div class="libre-editor-wrapper"><div class="libre-canvas-wrap"><iframe id="canvas"></iframe></div></div>
    <iframe id="drawio"></iframe>
    <button id="btn">b</button>
  </body>`, { pretendToBeVisual: true })
  const doc = dom.window.document
  const execs = []
  doc.execCommand = (cmd) => { execs.push(cmd); return true }
  return { dom, doc, execs }
}

test('可编辑元素判定：textarea / 文本 input / contenteditable 是；复选框、按钮不是', () => {
  const { doc } = page()
  assert.equal(isEditableElement(doc.getElementById('ai')), true)
  assert.equal(isEditableElement(doc.getElementById('find')), true)
  assert.equal(isEditableElement(doc.getElementById('rename')), true, '没写 type 的 input 默认 text')
  assert.equal(isEditableElement(doc.getElementById('inner')), true, 'contenteditable 里的子元素也算')
  assert.equal(isEditableElement(doc.getElementById('chk')), false)
  assert.equal(isEditableElement(doc.getElementById('btn')), false)
  assert.equal(isEditableElement(null), false)
})

test('焦点在 AI 输入框 / 查找框 / 批注 contenteditable：撤销那个输入框，不碰文档', () => {
  for (const id of ['ai', 'find', 'rename', 'note']) {
    const { doc, execs } = page()
    doc.getElementById(id).focus()
    assert.equal(doc.activeElement.id, id)
    let editor = 0
    const target = runMenuUndoRedo('undo', { doc, docTab: true, runEditor: () => { editor++ } })
    assert.equal(target, 'native', id + ' 聚焦时应走原生撤销')
    assert.deepEqual(execs, ['undo'])
    assert.equal(editor, 0, id + ' 聚焦时 ⌘Z 撤销了文档——这就是 J1 复核否决的那条')
  }
})

test('焦点在编辑器画布（.libre-editor-wrapper 里的 webview/iframe）：发给编辑器', () => {
  const { doc, execs } = page()
  doc.getElementById('canvas').focus()
  assert.equal(doc.activeElement.id, 'canvas')
  const calls = []
  const target = runMenuUndoRedo('redo', { doc, docTab: true, runEditor: (v) => calls.push(v) })
  assert.equal(target, 'editor')
  assert.deepEqual(calls, ['redo'])
  assert.deepEqual(execs, [])
})

test('焦点无主（body）且文档标签开着：撤销文档；不在文档标签：原生', () => {
  const { doc } = page()
  doc.activeElement && doc.activeElement.blur && doc.activeElement.blur()
  assert.equal(resolveUndoTarget(doc.activeElement, { docTab: true }), 'editor')
  assert.equal(resolveUndoTarget(doc.activeElement, { docTab: false }), 'native')
})

test('焦点在别的内嵌客体（draw.io 等）：让它自己原生撤销，等同原来的 role', () => {
  const { doc, execs } = page()
  const frame = doc.getElementById('drawio')
  const got = []
  frame.undo = () => got.push('undo') // <webview> 自带 undo()/redo()
  frame.focus()
  let editor = 0
  const target = runMenuUndoRedo('undo', { doc, docTab: true, runEditor: () => { editor++ } })
  assert.equal(target, 'frame')
  assert.deepEqual(got, ['undo'])
  assert.equal(editor, 0)
  assert.deepEqual(execs, [])
})

test('接线：appMenuBridge 的 edit.undo/edit.redo 走 runMenuUndoRedo，不再无条件发 wb:undo', () => {
  const SRC = readFileSync(new URL('../../src/utils/appMenuBridge.js', import.meta.url), 'utf8')
  const block = SRC.slice(SRC.indexOf("if (action === 'edit.undo' || action === 'edit.redo')"))
  assert.ok(block.length > 0)
  const body = block.slice(0, block.indexOf('return\n  }') + 10)
  assert.match(body, /runMenuUndoRedo\(verb,/)
  assert.match(body, /runEditor:\s*\(\)\s*=>\s*uni\.\$emit\(COMMAND_EVENT/)
  // 在 runMenuUndoRedo 之外不许有直接 emit（那就又变成无条件撤销文档）
  const outside = body.replace(/runEditor:[^\n]*\n/, '')
  assert.doesNotMatch(outside, /uni\.\$emit\(COMMAND_EVENT/)
})

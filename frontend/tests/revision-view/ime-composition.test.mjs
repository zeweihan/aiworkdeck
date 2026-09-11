// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { attachImeOverlay } from '../../src/composables/zetaOfficeImeOverlay.js'

// Exercise the real input listeners. These event sequences cover composition
// ordering differences; they do not claim to emulate a particular system IME.
function fixture(t) {
  const dom = new JSDOM('<div><canvas></canvas></div>', { pretendToBeVisual: true })
  const saved = Object.fromEntries(['document', 'navigator', 'getComputedStyle'].map(key =>
    [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  for (const [key, value] of Object.entries({ document: dom.window.document,
    navigator: dom.window.navigator, getComputedStyle: dom.window.getComputedStyle })) {
    Object.defineProperty(globalThis, key, { configurable: true, value })
  }
  const commits = [], commands = []
  const overlay = attachImeOverlay({ canvas: document.querySelector('canvas'),
    commit: text => { commits.push(text); return { success: true } },
    sendCommand: async (action, params) => { commands.push({ action, params }); return { success: true } },
  })
  t.after(() => {
    overlay.destroy(); dom.window.close()
    for (const [key, descriptor] of Object.entries(saved)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  })
  const input = overlay.element
  const composition = (type, data = '') => input.dispatchEvent(
    new dom.window.CompositionEvent(type, { data, bubbles: true }))
  const change = (data, inputType, isComposing, value = data) => {
    input.value = value
    input.dispatchEvent(new dom.window.InputEvent('input', { data, inputType, isComposing, bubbles: true }))
  }
  const start = () => {
    composition('compositionstart')
    composition('compositionupdate', 'zhongwen')
    change('zhongwen', 'insertCompositionText', true)
  }
  const key = props => {
    const event = new dom.window.KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...props })
    input.dispatchEvent(event)
    return event
  }
  return { commits, commands, input, composition, change, start, key }
}

for (const inputType of ['insertText', 'insertFromComposition']) {
  test(`confirmed ${inputType} commits before a delayed compositionend without requiring a click`, t => {
    const f = fixture(t); f.start()
    f.change('中文', inputType, false)
    assert.deepEqual(f.commits, ['中文'], 'a final non-composing input must not stay in the transparent input')
    f.composition('compositionend', '中文')
    assert.deepEqual(f.commits, ['中文'], 'the delayed end event must not repeat the committed phrase')
  })
}

for (const inputType of ['insertText', 'insertFromComposition', 'insertCompositionText']) {
  test(`empty compositionend does not discard a following final ${inputType}`, t => {
    const f = fixture(t); f.start()
    f.composition('compositionend', '')
    assert.deepEqual(f.commits, [], 'an empty end can be cancellation and must not commit raw preedit')
    f.change('中文', inputType, false)
    assert.deepEqual(f.commits, ['中文'])
  })
}

test('nonempty compositionend deduplicates its matching trailing insertText', t => {
  const f = fixture(t); f.start()
  f.composition('compositionend', '中文')
  assert.deepEqual(f.commits, ['中文'])
  f.change('中文', 'insertText', false)
  assert.deepEqual(f.commits, ['中文'])
})

test('mid-composition text and Space/number candidate keys never commit preedit or forward editor commands', t => {
  const f = fixture(t); f.start()
  for (const isComposing of [true, false]) {
    assert.equal(f.key({ key: ' ', code: 'Space', keyCode: 32, isComposing }).defaultPrevented, false)
    assert.equal(f.key({ key: '2', code: 'Digit2', keyCode: 50, isComposing }).defaultPrevented, false)
  }
  f.composition('compositionupdate', '中文')
  f.change('中文', 'insertCompositionText', true)
  assert.deepEqual(f.commits, [])
  assert.deepEqual(f.commands, [])
  f.composition('compositionend', '中文')
  assert.deepEqual(f.commits, ['中文'])
})

test('punctuation immediately after a composition commit is not swallowed', t => {
  const f = fixture(t); f.start()
  f.composition('compositionend', '中文')
  f.change('，', 'insertText', false)
  f.change('。', 'insertText', false)
  assert.deepEqual(f.commits, ['中文', '，', '。'])
})

test('cancelled composition does not insert preedit and does not swallow the next punctuation', t => {
  const f = fixture(t); f.start()
  f.composition('compositionend', '')
  assert.deepEqual(f.commits, [])
  f.change('，', 'insertText', false)
  assert.deepEqual(f.commits, ['，'])
})

for (const inputType of ['insertCompositionText', 'insertFromComposition']) {
  test(`normal nonempty compositionend commits once with a trailing ${inputType}`, t => {
    const f = fixture(t); f.start()
    f.composition('compositionend', '中文')
    assert.deepEqual(f.commits, ['中文'])
    f.change('中文', inputType, false)
    assert.deepEqual(f.commits, ['中文'])
  })
}

test('normal compositionend commits once even when there is no trailing input', async t => {
  const f = fixture(t); f.start()
  f.composition('compositionend', '中文')
  await new Promise(resolve => setTimeout(resolve, 0))
  assert.deepEqual(f.commits, ['中文'])
  f.change('，', 'insertText', false)
  assert.deepEqual(f.commits, ['中文', '，'])
})

test('composition cleanup deletion is not a successful commit and cannot suppress the later end text', t => {
  const f = fixture(t); f.start()
  f.change(null, 'deleteCompositionText', false, '')
  assert.deepEqual(f.commits, [])
  f.composition('compositionend', '中文')
  assert.deepEqual(f.commits, ['中文'])
})

test('deleting part of preedit cannot insert the remaining raw input value into the document', t => {
  const f = fixture(t); f.start()
  f.change(null, 'deleteContentBackward', false, 'zhong')
  assert.deepEqual(f.commits, [], 'a deletion event is not confirmation of the remaining preedit')
  f.composition('compositionend', '中')
  assert.deepEqual(f.commits, ['中'])
})

test('an empty final input does not count as a committed phrase before a nonempty end', t => {
  const f = fixture(t); f.start()
  f.change('', 'insertText', false)
  assert.deepEqual(f.commits, [])
  f.composition('compositionend', '中文')
  assert.deepEqual(f.commits, ['中文'])
})

test('a deletion outside composition never types the leftover box value into the document', t => {
  const f = fixture(t); f.start()
  f.composition('compositionend', '中文')
  f.change(null, 'deleteContentBackward', false, 'stale')
  f.change(null, 'historyUndo', false, 'zhongwen')
  assert.deepEqual(f.commits, ['中文'])
  assert.equal(f.input.value, '')
})

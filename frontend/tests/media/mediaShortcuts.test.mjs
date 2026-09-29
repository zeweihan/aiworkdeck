// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 播放器键盘快捷键（utils/media/mediaShortcuts.js，规格 3.7）：keydown → 动作。
import test from 'node:test'
import assert from 'node:assert/strict'
import { RATE_STEPS, isEditableTarget, resolveShortcut, nextRate } from '../../src/utils/media/mediaShortcuts.js'

function ev(key, extra = {}) {
  return {
    key,
    code: extra.code || '',
    shiftKey: !!extra.shiftKey,
    ctrlKey: !!extra.ctrlKey,
    metaKey: !!extra.metaKey,
    altKey: !!extra.altKey,
    isComposing: !!extra.isComposing,
    target: extra.target || { tagName: 'DIV', isContentEditable: false },
    preventDefault() {},
    stopPropagation() {},
  }
}
const VIDEO = { kind: 'video', captionsAvailable: true }
const AUDIO = { kind: 'audio', captionsAvailable: true }

test('RATE_STEPS 是契约档位', () => {
  assert.deepEqual(RATE_STEPS, [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2])
})

test('isEditableTarget：input/textarea/select/contenteditable 为真，其余为假', () => {
  assert.equal(isEditableTarget({ tagName: 'INPUT' }), true)
  assert.equal(isEditableTarget({ tagName: 'textarea' }), true)
  assert.equal(isEditableTarget({ tagName: 'SELECT' }), true)
  assert.equal(isEditableTarget({ tagName: 'DIV', isContentEditable: true }), true)
  assert.equal(isEditableTarget({ tagName: 'DIV', isContentEditable: false }), false)
  assert.equal(isEditableTarget(null), false)
  assert.equal(isEditableTarget(undefined), false)
})

test('resolveShortcut：Space / K 播放暂停', () => {
  assert.deepEqual(resolveShortcut(ev(' ', { code: 'Space' }), VIDEO), { type: 'toggle-play' })
  assert.deepEqual(resolveShortcut(ev('k'), VIDEO), { type: 'toggle-play' })
  assert.deepEqual(resolveShortcut(ev('K', { shiftKey: true }), AUDIO), { type: 'toggle-play' })
})

test('resolveShortcut：←/→ ±5s，J/L ±10s', () => {
  assert.deepEqual(resolveShortcut(ev('ArrowLeft'), VIDEO), { type: 'seek-by', value: -5 })
  assert.deepEqual(resolveShortcut(ev('ArrowRight'), VIDEO), { type: 'seek-by', value: 5 })
  assert.deepEqual(resolveShortcut(ev('j'), VIDEO), { type: 'seek-by', value: -10 })
  assert.deepEqual(resolveShortcut(ev('l'), AUDIO), { type: 'seek-by', value: 10 })
})

test('resolveShortcut：↑/↓ 音量 ±0.1，M 静音', () => {
  assert.deepEqual(resolveShortcut(ev('ArrowUp'), VIDEO), { type: 'volume-by', value: 0.1 })
  assert.deepEqual(resolveShortcut(ev('ArrowDown'), VIDEO), { type: 'volume-by', value: -0.1 })
  assert.deepEqual(resolveShortcut(ev('m'), AUDIO), { type: 'toggle-mute' })
})

test('resolveShortcut：F 全屏只对视频', () => {
  assert.deepEqual(resolveShortcut(ev('f'), VIDEO), { type: 'toggle-fullscreen' })
  assert.equal(resolveShortcut(ev('f'), AUDIO), null)
})

test('resolveShortcut：C 字幕只在字幕可用时', () => {
  assert.deepEqual(resolveShortcut(ev('c'), VIDEO), { type: 'toggle-captions' })
  assert.equal(resolveShortcut(ev('c'), { kind: 'video', captionsAvailable: false }), null)
  assert.equal(resolveShortcut(ev('c'), { kind: 'video' }), null)
})

test('resolveShortcut：Shift+, / Shift+. 倍速降升一档（key 为 < > 或 code 为 Comma/Period）', () => {
  assert.deepEqual(resolveShortcut(ev('<', { shiftKey: true, code: 'Comma' }), VIDEO), { type: 'rate-step', value: -1 })
  assert.deepEqual(resolveShortcut(ev('>', { shiftKey: true, code: 'Period' }), VIDEO), { type: 'rate-step', value: 1 })
  // 非美式布局：key 不是 < >，靠物理键位 code 兜底
  assert.deepEqual(resolveShortcut(ev(';', { shiftKey: true, code: 'Comma' }), VIDEO), { type: 'rate-step', value: -1 })
  assert.deepEqual(resolveShortcut(ev(':', { shiftKey: true, code: 'Period' }), VIDEO), { type: 'rate-step', value: 1 })
  // 不按 Shift 的逗号句号不是快捷键
  assert.equal(resolveShortcut(ev(',', { code: 'Comma' }), VIDEO), null)
  assert.equal(resolveShortcut(ev('.', { code: 'Period' }), VIDEO), null)
})

test('resolveShortcut：Home/End 跳开头结尾，Esc 关闭弹层', () => {
  assert.deepEqual(resolveShortcut(ev('Home'), VIDEO), { type: 'seek-to-ratio', value: 0 })
  assert.deepEqual(resolveShortcut(ev('End'), AUDIO), { type: 'seek-to-ratio', value: 1 })
  assert.deepEqual(resolveShortcut(ev('Escape'), VIDEO), { type: 'close-popover' })
  assert.deepEqual(resolveShortcut(ev('Esc'), VIDEO), { type: 'close-popover' })
})

test('resolveShortcut：输入框 / contenteditable 内一律放行', () => {
  assert.equal(resolveShortcut(ev(' ', { target: { tagName: 'INPUT' } }), VIDEO), null)
  assert.equal(resolveShortcut(ev('k', { target: { tagName: 'TEXTAREA' } }), VIDEO), null)
  assert.equal(resolveShortcut(ev('ArrowLeft', { target: { tagName: 'DIV', isContentEditable: true } }), VIDEO), null)
})

test('resolveShortcut：带 Ctrl/Cmd/Alt 的组合键、输入法组合中、未知键都不拦', () => {
  assert.equal(resolveShortcut(ev('f', { metaKey: true }), VIDEO), null)
  assert.equal(resolveShortcut(ev('k', { ctrlKey: true }), VIDEO), null)
  assert.equal(resolveShortcut(ev('ArrowLeft', { altKey: true }), VIDEO), null)
  assert.equal(resolveShortcut(ev('k', { isComposing: true }), VIDEO), null)
  assert.equal(resolveShortcut(ev('q'), VIDEO), null)
  assert.equal(resolveShortcut(ev('Tab'), VIDEO), null)
  assert.equal(resolveShortcut(null, VIDEO), null)
})

test('nextRate：±1 档，钳在 RATE_STEPS 内', () => {
  assert.equal(nextRate(1, 1), 1.25)
  assert.equal(nextRate(1, -1), 0.75)
  assert.equal(nextRate(2, 1), 2)
  assert.equal(nextRate(0.5, -1), 0.5)
})

test('nextRate：不在档位上的当前值取相邻档；非法值按 1x 算', () => {
  assert.equal(nextRate(1.1, 1), 1.25)
  assert.equal(nextRate(1.1, -1), 1)
  assert.equal(nextRate(3, -1), 2)
  assert.equal(nextRate(0.25, 1), 0.5)
  assert.equal(nextRate(NaN, 1), 1.25)
  assert.equal(nextRate(undefined, -1), 0.75)
})

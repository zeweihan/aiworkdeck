// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board#883 的接线护栏：守卫本身由 tests/lowa-e2e/modal-keyboard.mjs 用真引擎验，
// 但那张壳是自己 import 守卫的——真产物里谁来装它，只有这里看得见。
// 删掉 App.vue onLaunch 里的 installModalFocusGuard() 本文件即转红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const APP = readFileSync(new URL('../../src/App.vue', import.meta.url), 'utf8')
const IME = readFileSync(new URL('../../src/composables/zetaOfficeImeOverlay.js', import.meta.url), 'utf8')

test('App.vue 在 onLaunch 里装一次弹窗键盘守卫', () => {
  assert.match(APP, /import \{ installModalFocusGuard \} from '@\/utils\/modalFocusGuard\.js'/)
  const onLaunch = APP.slice(APP.indexOf('onLaunch'))
  const body = onLaunch.slice(0, onLaunch.indexOf('\n  },'))
  assert.match(body, /\n\s+installModalFocusGuard\(\)/)
})

test('守卫模块不许有 import（lowa-e2e 的壳直接按原文件加载它）', () => {
  const src = readFileSync(new URL('../../src/utils/modalFocusGuard.js', import.meta.url), 'utf8')
  assert.doesNotMatch(src, /^import /m)
})

test('客体页窗口拿回焦点且页内无焦点时，覆盖层接住键盘（弹窗关闭后的焦点归还）', () => {
  assert.match(IME, /addEventListener\('focus', onWindowFocus\)/)
  assert.match(IME, /removeEventListener\('focus', onWindowFocus\)/)
})

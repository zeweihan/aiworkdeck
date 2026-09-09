// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// wheelDeltaOf 的取值口径（dev-board#543）。
//
// 病灶：uni-h5 的 createNativeEvent 把 <scroll-view> 上的 wheel 事件重建成一个普通
// 对象，补字段的分支只有 click / mouse 系 / touch 系 / 键盘四类，wheel 不在其中——
// 回调拿到的事件**没有 deltaX/deltaY**，`scrollLeft += evt.deltaY` 得到 NaN，
// 编辑器工具栏与标签栏的横滚一起静默失效（回归自 PR#759）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { wheelDeltaOf } from '../../src/utils/wheelDelta.js'

function withWindowEvent(evt, fn) {
  const had = 'window' in globalThis
  const prev = had ? globalThis.window : undefined
  globalThis.window = { event: evt }
  try { return fn() } finally {
    if (had) globalThis.window = prev
    else delete globalThis.window
  }
}

test('回调事件缺 delta 字段时，回退到正在派发的原生事件', () => {
  // uni 重建后的形状：只有这几个字段，没有任何 delta
  const rebuilt = { type: 'wheel', timeStamp: 1, target: {}, currentTarget: {}, detail: {} }
  const got = withWindowEvent({ deltaX: 0, deltaY: 120 }, () => wheelDeltaOf(rebuilt))
  assert.equal(got, 120)
})

test('回调事件自带 delta 时优先用回调的（事件没被包装的平台）', () => {
  const got = withWindowEvent({ deltaX: 0, deltaY: 999 }, () => wheelDeltaOf({ deltaX: 0, deltaY: -40 }))
  assert.equal(got, -40)
})

test('两处都取不到 delta 就返回 0（调用方据此不动，而不是加一个 NaN）', () => {
  const rebuilt = { type: 'wheel', target: {} }
  assert.equal(withWindowEvent(null, () => wheelDeltaOf(rebuilt)), 0)
  assert.equal(withWindowEvent({ type: 'wheel' }, () => wheelDeltaOf(rebuilt)), 0)
  assert.equal(wheelDeltaOf(undefined), 0)
})

test('横向分量更大时取 deltaX（触控板横扫）', () => {
  assert.equal(wheelDeltaOf({ deltaX: -85, deltaY: 12 }), -85)
  assert.equal(wheelDeltaOf({ deltaX: 12, deltaY: -85 }), -85)
})

test('NaN / 非数字的 delta 不会被原样加到 scrollLeft 上', () => {
  assert.equal(wheelDeltaOf({ deltaX: NaN, deltaY: NaN }), 0)
  assert.equal(wheelDeltaOf({ deltaY: 60, deltaX: undefined }), 60)
})

// ---------- 唯一的调用方是 horizontalWheel.js，两个宿主都改走原生挂载 ----------

import { readFileSync } from 'node:fs'

const HWHEEL = readFileSync(new URL('../../src/utils/horizontalWheel.js', import.meta.url), 'utf8')
const TOOLBAR = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
const TABS = readFileSync(new URL('../../src/pages/project-overview/tabDragSplit.js', import.meta.url), 'utf8')
const PAGE = readFileSync(
  new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

test('位移只在 horizontalWheel.js 里取，宿主不再自己读 evt.delta*', () => {
  assert.match(HWHEEL, /wheelDeltaOf\(evt\)/, 'horizontalWheel.js 没有调用 wheelDeltaOf')
  assert.match(HWHEEL, /from '\.\/wheelDelta\.js'/, 'horizontalWheel.js 没有 import wheelDeltaOf')
  for (const [name, src] of [['EditorToolbar.vue', TOOLBAR], ['tabDragSplit.js', TABS]]) {
    assert.ok(!/Math\.abs\(evt\.deltaX\)/.test(src),
      name + ' 还在直接读 evt.deltaX：uni 重建过的事件上没有这个字段，恒 NaN')
    assert.match(src, /horizontalWheel\.js'/, name + ' 没有改走 horizontalWheel.js')
  }
})

test('模板里不许再挂 @wheel：uni 重建过的对象上 currentTarget 不是 DOM，第一道守卫就 return', () => {
  for (const [name, src] of [['EditorToolbar.vue', TOOLBAR], ['project-overview.vue', PAGE]]) {
    assert.ok(!/@wheel[.\w]*="/.test(src.slice(0, src.lastIndexOf('</template>'))),
      name + ' 的模板里还留着 @wheel（dev-board#543 走查实测：scrollLeft 0 → 0）')
  }
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「依据」实体浮窗的坐标纯函数（dev-board#541）。
//
// 锁两条：① 客体页坐标必须加上画布 rect 才是宿主页面坐标（缺一样就不弹，别弹到屏幕角落）；
//         ② 靠边时翻转 / 夹进视口——浮窗出屏等于「Cmd 点了没反应」。
import test from 'node:test'
import assert from 'node:assert/strict'

import { guestPointToHost, hoverCardPosition, HOVER_OFFSET, HOVER_MARGIN } from '../../src/utils/insightPopup.js'

// ————————————————— 客体页 → 宿主坐标 —————————————————

test('换算：客体页坐标 + 画布 rect = 宿主页面坐标', () => {
  assert.deepEqual(guestPointToHost({ left: 300, top: 120 }, 40, 60), { x: 340, y: 180 })
  // rect 是负的（画布被滚上去了一部分）也照加
  assert.deepEqual(guestPointToHost({ left: -20, top: -5 }, 40, 60), { x: 20, y: 55 })
})

test('换算：rect 或坐标缺一样就返回 null（调用方据此不弹浮窗）', () => {
  assert.equal(guestPointToHost(null, 40, 60), null)
  assert.equal(guestPointToHost({ left: 10 }, 40, 60), null, 'rect 少 top 也不能当 0 用')
  assert.equal(guestPointToHost({ left: 10, top: 10 }, undefined, 60), null)
  assert.equal(guestPointToHost({ left: 10, top: 10 }, 40, null), null)
  assert.equal(guestPointToHost({ left: 10, top: 10 }, NaN, 60), null)
})

test('换算：坐标 0 是合法坐标，不能被当成缺失', () => {
  assert.deepEqual(guestPointToHost({ left: 0, top: 0 }, 0, 0), { x: 0, y: 0 })
})

// ————————————————— 落点与翻转 —————————————————

const VP = { viewportWidth: 1200, viewportHeight: 800 }
const CARD = { width: 320, height: 240 }

test('落点：地方够就摆在点击点的右下方', () => {
  const p = hoverCardPosition({ x: 400, y: 300, ...CARD, ...VP })
  assert.deepEqual(p, { left: 400 + HOVER_OFFSET, top: 300 + HOVER_OFFSET, flipX: false, flipY: false })
})

test('落点：右边放不下就翻到点击点左侧', () => {
  const p = hoverCardPosition({ x: 1100, y: 300, ...CARD, ...VP })
  assert.equal(p.flipX, true)
  assert.equal(p.left, 1100 - HOVER_OFFSET - CARD.width)
  assert.equal(p.flipY, false)
})

test('落点：下边放不下就翻到点击点上方', () => {
  const p = hoverCardPosition({ x: 400, y: 700, ...CARD, ...VP })
  assert.equal(p.flipY, true)
  assert.equal(p.top, 700 - HOVER_OFFSET - CARD.height)
})

test('落点：右下角两边一起翻', () => {
  const p = hoverCardPosition({ x: 1150, y: 780, ...CARD, ...VP })
  assert.equal(p.flipX, true)
  assert.equal(p.flipY, true)
  assert.ok(p.left + CARD.width <= 1200 - HOVER_MARGIN)
  assert.ok(p.top + CARD.height <= 800 - HOVER_MARGIN)
})

test('落点：翻过去也放不下（视口比卡片还小）就贴边夹住，绝不出屏', () => {
  const p = hoverCardPosition({ x: 10, y: 10, width: 320, height: 240, viewportWidth: 200, viewportHeight: 150 })
  assert.equal(p.left, HOVER_MARGIN)
  assert.equal(p.top, HOVER_MARGIN)
  assert.equal(p.flipX, false, '翻过去更糟就别翻')
})

test('落点：靠左上边缘点击时不会被推成负坐标', () => {
  const p = hoverCardPosition({ x: 0, y: 0, ...CARD, ...VP })
  assert.ok(p.left >= HOVER_MARGIN)
  assert.ok(p.top >= HOVER_MARGIN)
})

test('落点：任何点击点都留在视口内（扫一遍边界）', () => {
  for (const x of [0, 1, 599, 1199, 1200, 5000]) {
    for (const y of [0, 1, 399, 799, 800, 5000]) {
      const p = hoverCardPosition({ x, y, ...CARD, ...VP })
      assert.ok(p.left >= HOVER_MARGIN, `left ${p.left} @(${x},${y})`)
      assert.ok(p.top >= HOVER_MARGIN, `top ${p.top} @(${x},${y})`)
      assert.ok(p.left + CARD.width <= VP.viewportWidth - HOVER_MARGIN, `right @(${x},${y})`)
      assert.ok(p.top + CARD.height <= VP.viewportHeight - HOVER_MARGIN, `bottom @(${x},${y})`)
    }
  }
})

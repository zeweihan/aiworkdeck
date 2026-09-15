// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// bindHorizontalWheel 的挂载契约（dev-board#543 回修）。
//
// 病灶：上一轮把「滚轮转横滚」写成模板上的 @wheel。uni-h5 对内置元素（<scroll-view>）
// 走 $nne → createNativeEvent，把 currentTarget 换成 { id, dataset, offsetTop,
// offsetLeft } 这样一个普通对象，于是「从 currentTarget 找滚动容器」第一道守卫
// （typeof root.querySelectorAll !== 'function'）就 return —— 真渲染走查实测
// scrollLeft 0 → 0，而同一套算法用原生 addEventListener 挂到真实 DOM 上是 0 → 600。
// 所以这里守的是「挂在真实元素上、passive:false、幂等、能摘干净」。
import test from 'node:test'
import assert from 'node:assert/strict'
import { bindHorizontalWheel, findHorizontalScroller } from '../../src/utils/horizontalWheel.js'

/** 够用的假元素：只实现被测代码真正会碰的那几样。 */
function fakeEl({ scrollWidth = 100, clientWidth = 100, children = [] } = {}) {
  const el = {
    scrollWidth, clientWidth, scrollLeft: 0,
    listeners: [], removed: [],
    querySelectorAll: () => children,
    addEventListener(type, fn, opts) { el.listeners.push({ type, fn, opts }) },
    removeEventListener(type, fn) { el.removed.push({ type, fn }) },
  }
  return el
}
const wheel = (deltaY) => {
  const e = { type: 'wheel', deltaX: 0, deltaY, prevented: 0 }
  e.preventDefault = () => { e.prevented++ }
  return e
}
const fire = (el, evt) => { for (const l of el.listeners) if (l.type === 'wheel') l.fn(evt) }

test('挂的是原生 wheel 监听，且 passive:false（否则 preventDefault 被忽略，横滚时页面还会纵向滚一下）', () => {
  const el = fakeEl({ scrollWidth: 900, clientWidth: 300 })
  bindHorizontalWheel(el)
  assert.equal(el.listeners.length, 1)
  assert.equal(el.listeners[0].type, 'wheel')
  assert.equal(el.listeners[0].opts && el.listeners[0].opts.passive, false)
})

test('纵向滚轮转成横向滚动', () => {
  const el = fakeEl({ scrollWidth: 900, clientWidth: 300 })
  bindHorizontalWheel(el)
  fire(el, wheel(300))
  assert.equal(el.scrollLeft, 300)
  fire(el, wheel(-120))
  assert.equal(el.scrollLeft, 180)
})

test('真正 overflow 的是内层元素时，滚的是内层（uni scroll-view 的形状）', () => {
  const inner = fakeEl({ scrollWidth: 900, clientWidth: 300 })
  const root = fakeEl({ scrollWidth: 300, clientWidth: 300, children: [inner] })
  bindHorizontalWheel(root)
  fire(root, wheel(120))
  assert.equal(inner.scrollLeft, 120)
  assert.equal(root.scrollLeft, 0)
  assert.equal(findHorizontalScroller(root), inner)
})

test('滚不动就不拦截：宽窗口下滚轮照常滚页面', () => {
  const el = fakeEl({ scrollWidth: 300, clientWidth: 300 })
  bindHorizontalWheel(el)
  const e = wheel(300)
  fire(el, e)
  assert.equal(e.prevented, 0, '没有可横滚的容器却调了 preventDefault')
  assert.equal(el.scrollLeft, 0)
})

test('真横滚时要 preventDefault（不然页面跟着纵向滚一下）', () => {
  const el = fakeEl({ scrollWidth: 900, clientWidth: 300 })
  bindHorizontalWheel(el)
  const e = wheel(120)
  fire(el, e)
  assert.equal(e.prevented, 1)
})

test('取不到 delta 就什么都不做（不是把 NaN 加进 scrollLeft）', () => {
  const el = fakeEl({ scrollWidth: 900, clientWidth: 300 })
  bindHorizontalWheel(el)
  fire(el, { type: 'wheel' })
  assert.equal(el.scrollLeft, 0)
})

test('幂等：同一个元素重复绑定只挂一次（分屏开关会让调用方反复重挂，挂两次就滚两倍）', () => {
  const el = fakeEl({ scrollWidth: 900, clientWidth: 300 })
  const off1 = bindHorizontalWheel(el)
  const off2 = bindHorizontalWheel(el)
  assert.equal(el.listeners.length, 1)
  assert.equal(off1, off2, '重复绑定要返回同一个摘除函数，否则 Set 去重不掉')
  fire(el, wheel(100))
  assert.equal(el.scrollLeft, 100, '挂了两次的话这里会是 200')
  off1()
})

test('摘除：removeEventListener 用的是同一个 handler，摘完能重新挂', () => {
  const el = fakeEl({ scrollWidth: 900, clientWidth: 300 })
  const off = bindHorizontalWheel(el)
  const handler = el.listeners[0].fn
  off()
  assert.equal(el.removed.length, 1)
  assert.equal(el.removed[0].fn, handler, '摘的必须是挂上去的那个函数引用')
  off() // 重复摘不许再摘一次别人的
  assert.equal(el.removed.length, 1)
  bindHorizontalWheel(el)
  assert.equal(el.listeners.length, 2, '摘干净之后应该能重新挂上')
})

test('传进来的不是元素（uni 重建过的那个普通对象）就安全退出', () => {
  const rebuilt = { id: '', dataset: {}, offsetTop: 0, offsetLeft: 0 }
  assert.doesNotThrow(() => bindHorizontalWheel(rebuilt)())
  assert.doesNotThrow(() => bindHorizontalWheel(null)())
  assert.equal(findHorizontalScroller(rebuilt), null)
})

// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// InlineReviewPanel.vue 的组件级测试底座：把 <script> 剥出来当普通对象跑
// （照 review-panel-vm.mjs 同法）。纯函数层（utils/inlineReviewGrouping.js）
// 喂的是**真实现**——判据走样的话这份用例就白测了。
import { readFileSync } from 'node:fs'
import * as grouping from '../../src/utils/inlineReviewGrouping.js'

const SRC = readFileSync(new URL('../../src/components/InlineReviewPanel.vue', import.meta.url), 'utf8')

export function makeInlineReviewComponent() {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const names = Object.keys(grouping)
  // eslint-disable-next-line no-new-func
  const factory = new Function(...names, script.replace('export default', 'return'))
  return factory(...names.map((n) => grouping[n]))
}

/** props 直接铺到 this 上；computed 挂成不带缓存的取值器，改完 data 立刻读到新值。 */
export function makeInlineReviewVm(executor, props) {
  const component = makeInlineReviewComponent()
  const emitted = []
  const base = Object.assign(
    { $t: (k, p) => k + (p ? JSON.stringify(p) : ''), $emit: (...args) => emitted.push(args), executor, state: null },
    props || {},
  )
  const vm = Object.assign(base, component.data.call(base), component.methods)
  for (const [k, fn] of Object.entries(component.computed || {})) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  vm.emitted = emitted
  // state 的 watch（immediate + deep）由测试显式驱动：换 state 就调它一次。
  vm.setState = (next) => {
    const prev = vm.state
    vm.state = next
    component.watch.state.handler.call(vm, next, prev)
  }
  vm.setState(props && props.state ? props.state : null)
  return vm
}

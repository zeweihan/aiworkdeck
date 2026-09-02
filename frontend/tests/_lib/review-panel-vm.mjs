// ReviewPanel.vue 的组件级测试底座：把 <script> 剥出来当普通对象跑（export default
// 换成 return，用假 this 调 methods / computed）。带 @/ 别名的 import 进不来，剥掉
// import 行后用形参把真实依赖喂回去——纯函数层（utils/reviewGrouping.js）喂的是
// **真实现**，只有子组件（EvidencePanel）是桩。
import { readFileSync } from 'node:fs'
import * as reviewGrouping from '../../src/utils/reviewGrouping.js'

const SRC = readFileSync(new URL('../../src/components/ReviewPanel.vue', import.meta.url), 'utf8')

export function makeReviewComponent() {
  // 多行 import（花括号里列一串具名导出）也要整块剥掉，别只吃掉第一行。
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const names = Object.keys(reviewGrouping)
  // eslint-disable-next-line no-new-func
  const factory = new Function('EvidencePanel', ...names, script.replace('export default', 'return'))
  return factory({}, ...names.map((n) => reviewGrouping[n]))
}

// props 里给的字段直接铺到 this 上（组件里 props 与 data 同层可见）。
export function makeReviewVm(executor, props) {
  const component = makeReviewComponent()
  const base = Object.assign(
    { $t: (k, p) => k + (p ? JSON.stringify(p) : ''), $emit: () => {}, executor, selfAuthor: '' },
    props || {},
  )
  const vm = Object.assign(base, component.data.call(base), component.methods)
  // computed 挂成不带缓存的取值器：测试里改完 data 立刻读到新值。
  for (const [k, fn] of Object.entries(component.computed || {})) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  return vm
}

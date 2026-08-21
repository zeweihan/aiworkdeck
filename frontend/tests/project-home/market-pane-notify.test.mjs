// 审计（dev-board#74）：MarketPane 的装/卸/启停不广播，左栏广场面板停在旧状态。
// 源码文本断言——本仓既有 node:test 用例的一贯写法（组件带 @/ 别名，import 不进来）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/components/MarketPane.vue', import.meta.url), 'utf8')
  .replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

/** 取两个方法名之间的那段方法体 */
const body = (from, to) => {
  const start = src.indexOf(from)
  assert.ok(start > 0, `找不到 ${from}`)
  const end = src.indexOf(to, start)
  assert.ok(end > start, `找不到 ${from} 之后的 ${to}`)
  return src.slice(start, end)
}

// MarketSidebarPanel（左栏广场面板）只订 'awd:market-changed'，
// 广场嵌在设置 tab 里时两者同屏共存：这边装完不广播，那边的按钮就一直显示「安装」。
test('notifyMarketChanged 广播左栏订阅的那个事件', () => {
  const fn = body('notifyMarketChanged() {', 'activationIndex(')
  assert.match(fn, /uni\.\$emit\('awd:market-changed'\)/,
    'MarketSidebarPanel 订的是 awd:market-changed，不发这个它就刷新不了')
})

for (const [name, next] of [
  ['async installPlugin(', 'async uninstallPlugin('],
  ['async uninstallPlugin(', 'categoryLabel('],
  ['async onToggle(', 'async loadSkills('],
  ['async installSkill(', 'async uninstallSkill('],
  ['async uninstallSkill(', 'async rescan('],
]) {
  test(`${name.replace('async ', '').replace('(', '')} 成功后通知外部刷新`, () => {
    const fn = body(name, next)
    assert.match(fn, /this\.notifyMarketChanged\(\)/,
      '装/卸/启停改变的是全局安装状态，不广播左栏与工作台会停在旧状态')
    // 广播必须在成功路径上（catch 之前），失败不该谎报变更
    const idx = fn.indexOf('this.notifyMarketChanged()')
    const catchIdx = fn.indexOf('} catch (e)')
    assert.ok(catchIdx < 0 || idx < catchIdx, '广播要留在 try 的成功路径里')
  })
}
